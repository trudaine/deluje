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
 * Sample-exact bit-diff of the Java {@link SVFilter} against golden buffers emitted by a standalone
 * C harness that links the <em>real</em> firmware {@code svf.cpp} on desktop (sources + build
 * script: {@code tools/ladder_harness/}, sibling of {@link LadderGoldenBufferTest}).
 *
 * <p>The SVF is pure integer math (getTanHUnknown + fixed-point multiplies) with no AudioEngine, no
 * getNoise(), and no float — fully deterministic. Covers both SVF_BAND and SVF_NOTCH modes across
 * cutoff/resonance/morph points (SVF output mixes low/band/high via c_low/c_band/c_high, which are
 * morph-derived, so morph is swept).
 *
 * <p>Golden files: {@code src/test/resources/fidelity/svf/}; regenerate with {@code
 * tools/ladder_harness/build.sh}.
 */
@Tag("slow")
class SvfGoldenBufferTest {

  private static final int AMP = 1 << 27;
  private static final int NSAMP = 512;

  /** mode: 3 = SVF_BAND, 4 = SVF_NOTCH (FilterSet.FilterMode ordinal); morph is in q28. */
  private record Case(String file, int freq, int res, int mode, int morph, String signal) {}

  private static java.util.stream.Stream<Arguments> cases() {
    return java.util.stream.Stream.of(
            new Case("c_svf_band_f800_r1000_m0_step.bin", 800000000, 1000000000, 3, 0, "step"),
            new Case(
                "c_svf_band_f800_r1000_mhalf_impulse.bin",
                800000000,
                1000000000,
                3,
                134217728,
                "impulse"),
            new Case(
                "c_svf_band_f400_r2000_mfull_impulse.bin",
                400000000,
                2000000000,
                3,
                268435455,
                "impulse"),
            new Case("c_svf_notch_f800_r1000_m0_step.bin", 800000000, 1000000000, 4, 0, "step"),
            new Case(
                "c_svf_notch_f600_r1500_mhalf_sine.bin",
                600000000,
                1500000000,
                4,
                134217728,
                "sine"))
        .map(c -> Arguments.of(c.file, c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaSvfMatchesCGolden(String name, Case c) throws IOException {
    int[] expected = readGolden(c.file());
    Assumptions.assumeTrue(expected != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP, expected.length, "golden size");

    int[] buf = makeInput(c.signal());
    SVFilter filt = new SVFilter();
    filt.reset(false);
    filt.configure(c.freq(), c.res(), FilterSet.FilterMode.values()[c.mode()], c.morph(), 0);
    filt.filterMono(buf, 0, NSAMP, 1);

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < NSAMP; i++) {
      long d = Math.abs((long) buf[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) firstDiff = i;
      if (d > maxAbs) maxAbs = d;
    }
    System.out.printf("[%-40s] firstDiff=%d maxAbsDiff=%d%n", c.file(), firstDiff, maxAbs);
    final int fd = Math.max(firstDiff, 0);
    final int jv = buf[fd];
    final int gv = expected[fd];
    assertEquals(
        0L,
        maxAbs,
        () ->
            "Java SVF diverges from C golden "
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
    try (InputStream in = SvfGoldenBufferTest.class.getResourceAsStream("/fidelity/svf/" + file)) {
      if (in == null) return null;
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
      return out;
    }
  }

  private static int[] makeInput(String signal) {
    int[] buf = new int[NSAMP];
    for (int i = 0; i < NSAMP; i++) {
      buf[i] =
          switch (signal) {
            case "step" -> AMP;
            case "impulse" -> (i == 0) ? AMP : 0;
            default -> (int) (AMP * Math.sin(2.0 * Math.PI * i / 16.0));
          };
    }
    return buf;
  }
}
