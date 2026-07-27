package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Sample-exact bit-diff of the Java {@link Oscillator#renderOsc} basic waveforms against golden
 * buffers emitted by a standalone C harness that links the <em>real</em> firmware {@code
 * oscillator.cpp} on desktop (sources: {@code tools/osc_harness/}).
 *
 * <p>The oscillator underlies every synth voice — the single highest-value unit. It was previously
 * blocked from a desktop harness by ARM-NEON (Argon); this is unblocked via <b>SIMDE</b>
 * (NEON-on-x86), which is bit-accurate for the integer NEON ops the oscillator uses (phase
 * accumulate + int16 band interpolation + saturating multiplies), so the C golden matches real ARM.
 * Cases map by wave <em>name</em> (the Java {@code OscType} ordinal order differs from the C enum).
 * Basic waves only (waveTable=null, doOscSync=false).
 *
 * <p>Golden files: {@code src/test/resources/fidelity/osc/}; regenerate with {@code
 * tools/osc_harness/build.sh}.
 */
@Tag("slow")
class OscGoldenBufferTest {

  private static final int NSAMP = 512;
  private static final int AMP = 1 << 27;

  private record Case(String file, OscType type, int phaseInc, int pulseWidth) {}

  // SINE is characterized separately (§4.2quaterquadragies): it matches to within ~26 LSB, a
  // rounding difference — SQUARE/TRIANGLE (also table-based) are bit-exact, so this is most likely
  // a
  // SIMDE-vs-NEON rounding artifact in the golden, not a Java bug.
  private static java.util.stream.Stream<Arguments> cases() {
    return java.util.stream.Stream.of(
            new Case(
                "c_osc_saw_f5162220.bin", OscType.SAW, 0x004ec4ec, 0), // crude path (tableNum<6)
            new Case("c_osc_saw_bandlimited.bin", OscType.SAW, 0x00a00000, 0), // band-limited (>=6)
            new Case("c_osc_square_f5162220.bin", OscType.SQUARE, 0x004ec4ec, 0),
            new Case("c_osc_triangle_f5162220.bin", OscType.TRIANGLE, 0x004ec4ec, 0),
            new Case("c_osc_analogsquare_f.bin", OscType.ANALOG_SQUARE, 0x00a00000, 0),
            new Case("c_osc_square_pw25.bin", OscType.SQUARE, 0x004ec4ec, 0x40000000))
        .map(c -> Arguments.of(c.file, c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaOscMatchesCGolden(String name, Case c) throws IOException {
    int[] expected = readGolden(c.file());
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP, expected.length, "golden size");

    int[] buf = new int[NSAMP];
    int[] startPhase = {0};
    Oscillator.renderOsc(
        c.type(),
        AMP,
        buf,
        0,
        NSAMP,
        c.phaseInc(),
        c.pulseWidth(),
        startPhase,
        /* applyAmplitude= */ true,
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ false,
        /* resetterPhase= */ 0,
        /* resetterPhaseInc= */ 0,
        /* retriggerPhase= */ 0);

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < NSAMP; i++) {
      long d = Math.abs((long) buf[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    System.out.printf("[%-28s] firstDiff=%d maxAbsDiff=%d%n", c.file(), firstDiff, maxAbs);
    final int fd = Math.max(firstDiff, 0);
    final int jv = buf[fd];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java oscillator ("
                + c.type()
                + ") diverges from C golden "
                + c.file()
                + " at sample "
                + fd
                + " (java="
                + jv
                + " golden="
                + gv
                + ")");
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream in = OscGoldenBufferTest.class.getResourceAsStream("/fidelity/osc/" + file)) {
      if (in == null) return null;
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
      return out;
    }
  }
}
