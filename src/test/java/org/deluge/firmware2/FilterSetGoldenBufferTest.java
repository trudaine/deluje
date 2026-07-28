package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.deluge.firmware2.FilterSet.FilterMode;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bit-diff of the Java {@link FilterSet} <em>control path</em> — {@code setConfig} + {@code
 * renderLongStereo} — against golden buffers from a C harness that links the real firmware {@code
 * filter_set.cpp}, {@code lpladder.cpp}, {@code hpladder.cpp} and {@code svf.cpp} ({@code
 * tools/filterset_harness/}).
 *
 * <p><b>Why this level.</b> The individual filter cores are already proven bit-exact ({@code
 * HpLadderGoldenBufferTest}, {@code SvfGoldenBufferTest}, {@code LadderGoldenBufferTest} — all
 * maxAbsDiff=0), yet the CALIB hardware corpus scores the HPF at median 0.677 with nine cases at
 * <em>negative</em> cosine (docs/FIDELITY_GAP_ANALYSIS.md §4.2quattuorsexagies). A defect that
 * survives bit-exact cores must live in the glue: mode dispatch, the HPF morph inversion, the
 * filterGain chain, resonance quantisation, or routing. This test covers exactly that glue.
 *
 * <p>Renders are one 128-sample audio block: the PARALLEL route uses the firmware's global {@code
 * tempRenderBuffer}, sized {@code SSI_TX_BUFFER_NUM_SAMPLES * 2}.
 *
 * <p>Goldens: {@code src/test/resources/fidelity/filterset/}; regenerate with {@code
 * tools/filterset_harness/build.sh}.
 */
@Tag("slow")
class FilterSetGoldenBufferTest {

  private static final int NSAMP = 128;
  private static final int MIN = Integer.MIN_VALUE;

  private record Case(
      String file,
      int hpFreq,
      int hpRes,
      FilterMode hpMode,
      int hpMorph,
      int lpFreq,
      int lpRes,
      FilterMode lpMode,
      FilterRoute route) {

    static Case hp(String file, int freq, int res, FilterMode mode, int morph) {
      return new Case(
          file,
          freq,
          res,
          mode,
          morph,
          Integer.MAX_VALUE,
          MIN,
          FilterMode.OFF,
          FilterRoute.HIGH_TO_LOW);
    }
  }

  private static java.util.stream.Stream<Arguments> cases() {
    java.util.List<Case> cs = new java.util.ArrayList<>();
    // Cutoffs are NON-NEGATIVE. curveFrequency (filter.h:128-136) feeds instantTan, which indexes
    // tanTable with `input >> 25`; a negative frequency is a negative index and the C reads out of
    // bounds. Our Functions.instantTan clamps for array safety, so negative-cutoff behaviour is
    // unportable by construction and deliberately not golden-tested. Real presets only use
    // non-negative cutoffs (0x80000000 is the "off" sentinel doHPF filters out first).
    for (FilterMode m :
        new FilterMode[] {FilterMode.HPLADDER, FilterMode.SVF_BAND, FilterMode.SVF_NOTCH}) {
      String n = name(m);
      cs.add(Case.hp("c_fs_" + n + "_flow_q00.bin", 268435456, MIN, m, MIN));
      cs.add(Case.hp("c_fs_" + n + "_fmid_q00.bin", 1073741824, MIN, m, MIN));
      cs.add(Case.hp("c_fs_" + n + "_fhigh_q00.bin", 1879048192, MIN, m, MIN));
      cs.add(Case.hp("c_fs_" + n + "_fhigh_q50.bin", 1879048192, 0, m, MIN));
    }
    // Morph sweep — the HPF slot inverts morph, and the inversion's constant implies a [0,2^29)
    // range while the value supplied is a raw q31.
    for (int morph : new int[] {-1073741824, 0, 536870911, 1073741824}) {
      String tag = morph < 0 ? "n" + (-(long) morph) : String.valueOf(morph);
      cs.add(
          Case.hp("c_fs_SVF_Band_morph" + tag + ".bin", 1073741824, 0, FilterMode.SVF_BAND, morph));
      cs.add(
          Case.hp(
              "c_fs_SVF_Notch_morph" + tag + ".bin", 1073741824, 0, FilterMode.SVF_NOTCH, morph));
    }
    cs.add(Case.hp("c_fs_HPLadder_fmid_q75.bin", 1073741824, 1073741824, FilterMode.HPLADDER, MIN));
    // Both filters live, exercising the two non-default routings.
    cs.add(
        new Case(
            "c_fs_route_L2H.bin",
            1073741824,
            0,
            FilterMode.HPLADDER,
            MIN,
            1073741824,
            0,
            FilterMode.TRANSISTOR_24DB,
            FilterRoute.LOW_TO_HIGH));
    cs.add(
        new Case(
            "c_fs_route_PARA.bin",
            1073741824,
            0,
            FilterMode.HPLADDER,
            MIN,
            1073741824,
            0,
            FilterMode.TRANSISTOR_24DB,
            FilterRoute.PARALLEL));
    return cs.stream().map(c -> Arguments.of(c.file(), c));
  }

  private static String name(FilterMode m) {
    return switch (m) {
      case HPLADDER -> "HPLadder";
      case SVF_BAND -> "SVF_Band";
      case SVF_NOTCH -> "SVF_Notch";
      default -> m.name();
    };
  }

  /** ~262 Hz saw — the C4 the calibration corpus plays; identical to the harness generator. */
  private static int[] sawStereo(int n) {
    int[] buf = new int[n * 2];
    for (int i = 0; i < n; i++) {
      int phase = (int) ((i * 4294967296L * 262L / 44100L) & 0xFFFFFFFFL);
      int v = phase >> 4;
      buf[2 * i] = v;
      buf[2 * i + 1] = v;
    }
    return buf;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaFilterSetMatchesCGolden(String name, Case c) throws IOException {
    int[] expected = readGolden(c.file());
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP, expected.length, "golden size");

    // The LP ladder's moveability is noise-modulated (LpLadderFilter: getNoise() per sample), and
    // the C harness seeds jcong to a fixed value at process start (ladder_harness/support.cpp), so
    // every golden was generated from the SAME PRNG state. These cases share one JVM and one static
    // PRNG, so without this reset each case after the first LP-ladder render starts mid-stream and
    // diverges — which is exactly what made c_fs_route_PARA fail while c_fs_route_L2H passed.
    Functions.resetNoiseSeed();
    int[] buf = sawStereo(NSAMP);
    FilterSet fs = new FilterSet();
    fs.setConfig(
        c.lpFreq(),
        c.lpRes(),
        c.lpMode(),
        MIN,
        c.hpFreq(),
        c.hpRes(),
        c.hpMode(),
        c.hpMorph(),
        0,
        c.route());
    fs.renderLongStereo(buf, NSAMP);

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < NSAMP; i++) {
      long d = Math.abs((long) buf[2 * i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    final int fd = Math.max(firstDiff, 0);
    final int jv = buf[2 * fd];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java FilterSet diverges from C golden "
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
    try (InputStream in =
        FilterSetGoldenBufferTest.class.getResourceAsStream("/fidelity/filterset/" + file)) {
      if (in == null) return null;
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
      return out;
    }
  }
}
