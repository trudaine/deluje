package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Sample-exact bit-diff of the Java {@link WaveTable#render} single-cycle path against golden
 * buffers emitted by a standalone C harness that re-hosts the <em>real</em> firmware {@code
 * getKernel} + {@code doRenderingLoopSingleCycle} verbatim (with the real {@code
 * windowedSincKernel} table) and runs the genuine NEON intrinsics via SIMDE (NEON-on-x86). Sources:
 * {@code tools/wt_harness/}.
 *
 * <p>The wavetable oscillator is live in {@code Voice} for every {@code OscType.WAVETABLE} voice.
 * Auditing it against the C found the kernel-row bug (§4.2sexquinquagies) and the per-tap vqdmulh
 * truncation the faithful port must match — this test pins the whole single-cycle inner loop to the
 * C at the bit level so neither can regress.
 *
 * <p>Golden files: {@code src/test/resources/fidelity/wt/}; regenerate with {@code
 * tools/wt_harness/build.sh}. The synthetic cycle here is bit-identical to the harness's {@code
 * synthCycle} (int wrap + logical shift).
 */
@Tag("slow")
class WaveTableGoldenBufferTest {

  private static final int NSAMP = 512;
  private static final int GUARD = WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE;

  private record Case(String file, int mag, int phaseInc) {}

  private static java.util.stream.Stream<Arguments> cases() {
    return java.util.stream.Stream.of(
            new Case("c_wt_mag11_low.bin", 11, 0x00123456),
            new Case("c_wt_mag11_mid.bin", 11, 0x002ABCDE),
            new Case("c_wt_mag10.bin", 10, 0x001ABCDE),
            new Case("c_wt_mag9.bin", 9, 0x0034ABCD))
        .map(c -> Arguments.of(c.file, c));
  }

  /** Bit-identical to the harness synthCycle(): uint32 wrap arithmetic + logical shift. */
  private static short synthCycle(int i) {
    int x = i * 1103515245 + 12345;
    x ^= x >>> 16;
    return (short) (x & 0xFFFF);
  }

  private static WaveTable singleCycleTable(int mag) {
    int cycleSize = 1 << mag;
    WaveTable wt = new WaveTable();
    wt.numCycles = 1;
    WaveTableBand band = new WaveTableBand();
    band.cycleSizeMagnitude = (byte) mag;
    band.cycleSizeNoDuplicates = cycleSize;
    band.maxPhaseIncrement = (int) ((0xFFFFFFFFL >>> mag) * 1.25);
    band.fromCycleNumber = 0;
    band.toCycleNumber = 1;
    band.data = new short[cycleSize + GUARD];
    for (int i = 0; i < cycleSize; i++) band.data[i] = synthCycle(i);
    for (int g = 0; g < GUARD; g++) band.data[cycleSize + g] = band.data[g]; // wrap duplicates
    wt.bands.add(band);
    return wt;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaWaveTableMatchesCGolden(String name, Case c) throws IOException {
    int[] expected = readGolden(c.file());
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP, expected.length, "golden size");

    WaveTable wt = singleCycleTable(c.mag());
    int[] buf = new int[NSAMP];
    wt.render(buf, 0, NSAMP, c.phaseInc(), 0, false, 0, 0, 0, 0, 0, 0);

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < NSAMP; i++) {
      long d = Math.abs((long) buf[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    final int fd = Math.max(firstDiff, 0);
    System.out.printf(
        "[%-20s] firstDiff=%d maxAbsDiff=%d (java=%d golden=%d)%n",
        c.file(), firstDiff, maxAbs, buf[fd], expected[fd]);
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java WaveTable single-cycle render diverges from C golden "
                + c.file()
                + " at sample "
                + fd
                + " (java="
                + buf[fd]
                + " golden="
                + expected[fd]
                + ")");
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream in =
        WaveTableGoldenBufferTest.class.getResourceAsStream("/fidelity/wt/" + file)) {
      if (in == null) return null;
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
      return out;
    }
  }
}
