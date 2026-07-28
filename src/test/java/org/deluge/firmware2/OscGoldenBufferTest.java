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
 * Covers the basic waves (waveTable=null, doOscSync=false) and the hard-<b>sync</b> path
 * (render_wave.h renderOscSync for the band-limited tables, plus the per-sample crude reset loops
 * in oscillator.cpp for tableNumber&lt;6).
 *
 * <p>Golden files: {@code src/test/resources/fidelity/osc/}; regenerate with {@code
 * tools/osc_harness/build.sh}.
 */
@Tag("slow")
class OscGoldenBufferTest {

  private static final int NSAMP = 512;

  /**
   * Sync renders are one audio block. The C sync path reads the firmware global {@code
   * oscSyncRenderingBuffer} (SSI_TX_BUFFER_NUM_SAMPLES+4 == 132 int32) over {@code numSamples}, so
   * a longer render walks off the end of it and yields nondeterministic goldens — see
   * docs/FIDELITY_GAP_ANALYSIS.md §4.2novemquinquagies.
   */
  private static final int SYNC_NSAMP = 128;

  private static final int AMP = 1 << 27;

  private record Case(String file, OscType type, int phaseInc, int pulseWidth) {}

  private record SyncCase(
      String file, OscType type, int phaseInc, int resetterPhaseInc, boolean applyAmplitude) {}

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
            new Case("c_osc_square_pw25.bin", OscType.SQUARE, 0x004ec4ec, 0x40000000),
            // Band-limited at pitches with rich low bits — the cases above never exercise the band
            // interpolation (0x004ec4ec is the crude tableNumber<6 path for saw/square, and
            // 0x00a00000 is a multiple of 2^21 so the fraction is 0 on every sample).
            new Case("c_osc_saw_interp.bin", OscType.SAW, 0x00a12345, 0),
            new Case("c_osc_square_interp.bin", OscType.SQUARE, 0x00a12345, 0),
            new Case("c_osc_analogsquare_interp.bin", OscType.ANALOG_SQUARE, 0x0212abcd, 0),
            new Case("c_osc_triangle_interp.bin", OscType.TRIANGLE, 0x0212abcd, 0))
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

  /**
   * Hard-sync matrix: 4 wave types x 3 pitches x 3 resetter rates, plus two amplitude-applied
   * cases.
   *
   * <p>The pitches are deliberately "odd". A round increment like {@code 0x00a00000} is a multiple
   * of 2^21, so at tableSizeMagnitude 11 the band-interpolation fraction is <b>0 on every
   * sample</b>: the interpolation is never exercised and the case passes vacuously. These pitches
   * carry rich low bits (&ge;95% of samples interpolate) and straddle the crude (tableNumber&lt;6,
   * {@code 0x004ec4ec}) and band-limited paths.
   */
  private static java.util.stream.Stream<Arguments> syncCases() {
    java.util.List<Arguments> out = new java.util.ArrayList<>();
    OscType[] byCIndex = {
      OscType.SINE, OscType.TRIANGLE, OscType.SQUARE, OscType.ANALOG_SQUARE, OscType.SAW
    };
    String[] pitches = {"0x00a12345", "0x004ec4ec", "0x0212abcd"};
    String[] resetters = {"0x04000000", "0x02000000", "0x0a000000"};
    for (String p : pitches) {
      for (String r : resetters) {
        for (int t = 1; t <= 4; t++) {
          String f = "c_osc_sync_t" + t + "_p" + p + "_r" + r + ".bin";
          SyncCase c =
              new SyncCase(
                  f,
                  byCIndex[t],
                  Integer.parseUnsignedInt(p.substring(2), 16),
                  Integer.parseUnsignedInt(r.substring(2), 16),
                  false);
          out.add(Arguments.of(f, c));
        }
      }
    }
    out.add(
        Arguments.of(
            "c_osc_sync_amp_saw.bin",
            new SyncCase("c_osc_sync_amp_saw.bin", OscType.SAW, 0x00a12345, 0x04000000, true)));
    out.add(
        Arguments.of(
            "c_osc_sync_amp_square.bin",
            new SyncCase(
                "c_osc_sync_amp_square.bin", OscType.SQUARE, 0x0212abcd, 0x02000000, true)));
    return out.stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("syncCases")
  void javaOscSyncMatchesCGolden(String name, SyncCase c) throws IOException {
    int[] expected = readGolden(c.file());
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(SYNC_NSAMP, expected.length, "golden size");

    int[] buf = new int[SYNC_NSAMP];
    int[] startPhase = {0};
    Oscillator.renderOsc(
        c.type(),
        AMP,
        buf,
        0,
        SYNC_NSAMP,
        c.phaseInc(),
        /* pulseWidth= */ 0,
        startPhase,
        c.applyAmplitude(),
        /* amplitudeIncrement= */ 0,
        /* doOscSync= */ true,
        /* resetterPhase= */ 0,
        c.resetterPhaseInc(),
        /* retriggerPhase= */ 0);

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < SYNC_NSAMP; i++) {
      long d = Math.abs((long) buf[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    final int fd = Math.max(firstDiff, 0);
    final int jv = buf[fd];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java osc sync ("
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
