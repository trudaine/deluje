package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bit-diff of {@link Supersaw} against golden buffers from a C harness that compiles the real
 * firmware {@code dsp/oscillators/supersaw.cpp} ({@code tools/supersaw_harness/}).
 *
 * <p><b>Why these cases.</b> Spread 0 / ±2^30 cover the param ends (the detune and both mix
 * curves); saw counts 1, 3, 4 and 7 cover every {@code sideShift} value and the no-side-saw path;
 * C1/C4/C7 bracket the note range, which is what decides how many polyBLEP edges land in a buffer;
 * and one case runs with band limiting off, isolating the naive pass from the BLEP. Each renders
 * four consecutive 128-sample blocks, so the phase carried between calls is covered too.
 *
 * <p>Goldens: {@code src/test/resources/fidelity/osc/c_supersaw_*}; regenerate with {@code
 * tools/supersaw_harness/build.sh}.
 */
@Tag("slow")
class SupersawGoldenBufferTest {

  private static final int NSAMP = 128;
  private static final int BLOCKS = 4;

  private record Case(
      String file, int phaseIncrement, int spread, int numSaws, boolean bandLimit) {}

  private static java.util.stream.Stream<Arguments> cases() {
    return java.util.stream.Stream.of(
            new Case("c_supersaw_c4_spread0_saws7_bl.bin", 25480253, 0, 7, true),
            new Case("c_supersaw_c4_spread0_saws7_raw.bin", 25480253, 0, 7, false),
            new Case("c_supersaw_c4_spreadmax_saws7_bl.bin", 25480253, 1073741823, 7, true),
            new Case("c_supersaw_c4_spreadmin_saws7_bl.bin", 25480253, -1073741824, 7, true),
            new Case("c_supersaw_c4_spread0_saws1_bl.bin", 25480253, 0, 1, true),
            new Case("c_supersaw_c4_spread0_saws3_bl.bin", 25480253, 0, 3, true),
            new Case("c_supersaw_c4_spread0_saws4_bl.bin", 25480253, 0, 4, true),
            new Case("c_supersaw_c1_spreadhalf_saws7_bl.bin", 3185031, 536870912, 7, true),
            new Case("c_supersaw_c7_spreadhalf_saws7_bl.bin", 203842024, 536870912, 7, true))
        .map(c -> Arguments.of(c.file(), c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaMatchesCGolden(String name, Case c) throws IOException {
    int[] golden = readGolden(c.file());
    Assumptions.assumeTrue(golden != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP * BLOCKS, golden.length, "golden size");

    int[] phase = {0};
    int[] sidePhases = Supersaw.sidePhasesFrom(phase[0]);
    int[] ours = new int[NSAMP * BLOCKS];
    int[] buffer = new int[NSAMP + 4];
    for (int b = 0; b < BLOCKS; b++) {
      Supersaw.render(
          buffer,
          0,
          NSAMP,
          c.phaseIncrement(),
          c.spread(),
          c.numSaws(),
          phase,
          sidePhases,
          c.bandLimit());
      System.arraycopy(buffer, 0, ours, b * NSAMP, NSAMP);
    }

    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < ours.length; i++) {
      long d = Math.abs((long) ours[i] - golden[i]);
      if (d != 0 && firstDiff < 0) {
        firstDiff = i;
      }
      maxAbs = Math.max(maxAbs, d);
    }
    System.out.printf("[%-40s] firstDiff=%d maxAbs=%d%n", c.file(), firstDiff, maxAbs);

    // Non-vacuous: a silent golden would make any port "match".
    long goldenMax = 0;
    for (int v : golden) {
      goldenMax = Math.max(goldenMax, Math.abs((long) v));
    }
    assertTrue(goldenMax > (1 << 24), "golden must carry real signal, saw max " + goldenMax);

    final int firstDiffAt = firstDiff;
    assertEquals(0L, maxAbs, () -> "diverges from C " + c.file() + " at sample " + firstDiffAt);
  }

  // ── Through Oscillator.renderOsc, the path a Voice takes ──

  private record OscCase(
      String file,
      int phaseIncrement,
      int pulseWidth,
      int waveIndex,
      int amplitude,
      int ampInc,
      boolean applyAmp) {}

  private static java.util.stream.Stream<Arguments> oscCases() {
    return java.util.stream.Stream.of(
            new OscCase("c_supersaw_osc_c4_amp.bin", 25480253, 0, 0, 134217728, 0, true),
            new OscCase("c_supersaw_osc_c4_ampramp.bin", 25480253, 0, 0, 134217728, 65536, true),
            new OscCase("c_supersaw_osc_c4_noamp.bin", 25480253, 0, 0, 134217728, 0, false),
            new OscCase(
                "c_supersaw_osc_c1_pwhalf_amp.bin",
                3185031,
                1073741824,
                536870912,
                134217728,
                0,
                true))
        .map(c -> Arguments.of(c.file(), c));
  }

  /**
   * The supersaw as {@code Oscillator::renderOsc} renders it (oscillator.cpp:31-46), amplitude
   * stage included. That stage is why this case set exists: the raw-render goldens above cannot see
   * it, and a first version of the port applied amplitude in {@code Voice} at a quarter of the C's
   * level with every raw golden still passing.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("oscCases")
  void renderOscMatchesCGolden(String name, OscCase c) throws IOException {
    int[] golden = readGolden(c.file());
    Assumptions.assumeTrue(golden != null, "missing golden resource: " + c.file());
    assertEquals(NSAMP * BLOCKS, golden.length, "golden size");

    int[] phase = {0};
    int[] sidePhases = Supersaw.sidePhasesFrom(phase[0]);
    int[] ours = new int[NSAMP * BLOCKS];
    for (int b = 0; b < BLOCKS; b++) {
      int[] buffer = new int[NSAMP + 4]; // cleared per block, as the voice's source buffer is
      Oscillator.renderOsc(
          Oscillator.OscType.SUPERSAW,
          c.amplitude(),
          buffer,
          0,
          NSAMP,
          c.phaseIncrement(),
          c.pulseWidth(),
          phase,
          c.applyAmp(),
          c.ampInc(),
          false,
          0,
          0,
          0,
          0,
          c.waveIndex(),
          sidePhases);
      System.arraycopy(buffer, 0, ours, b * NSAMP, NSAMP);
    }

    int firstDiff = -1;
    long maxAbs = 0;
    long goldenMax = 0;
    for (int i = 0; i < ours.length; i++) {
      long d = Math.abs((long) ours[i] - golden[i]);
      if (d != 0 && firstDiff < 0) {
        firstDiff = i;
      }
      maxAbs = Math.max(maxAbs, d);
      goldenMax = Math.max(goldenMax, Math.abs((long) golden[i]));
    }
    System.out.printf("[%-40s] firstDiff=%d maxAbs=%d%n", c.file(), firstDiff, maxAbs);
    assertTrue(goldenMax > (1 << 20), "golden must carry real signal, saw max " + goldenMax);

    final int firstDiffAt = firstDiff;
    assertEquals(0L, maxAbs, () -> "diverges from C " + c.file() + " at sample " + firstDiffAt);
  }

  /** The ARM {@code vrecpe.f32} estimate is 8-bit accurate; two Newton steps take it to float. */
  @Test
  void reciprocalEstimateMatchesArmSpec() {
    // ARM ARM: the estimate is within 1/512 of the exact reciprocal — NOT exact, even at a power of
    // two, where RecipEstimate(256) returns 511 rather than 512. (Asserting an exact 0.5 here is
    // what first failed, and the table is right: 2^19 / 513 = 1022, (1022 + 1) >> 1 = 511.)
    assertEquals(0.49902344f, Supersaw.reciprocalEstimate(2.f), 0.f);
    assertEquals(0.24951172f, Supersaw.reciprocalEstimate(4.f), 0.f);
    for (float v : new float[] {1.f, 3.f, 7.5f, 1000.f, 25480253.f, 3185031.f, 0x1p30f}) {
      float estimate = Supersaw.reciprocalEstimate(v);
      assertTrue(
          Math.abs(estimate * v - 1.f) <= 1.f / 512.f, "estimate out of ARM spec range for " + v);
      // After refinement it is within an ULP of the true reciprocal, which is what the C uses.
      float refined = Supersaw.reciprocalEstimateRefine2(v);
      assertEquals(1.f / v, refined, Math.ulp(1.f / v) * 2.f, "refined reciprocal for " + v);
    }
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream is =
        SupersawGoldenBufferTest.class.getResourceAsStream("/fidelity/osc/" + file)) {
      if (is == null) {
        return null;
      }
      byte[] raw = is.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) {
        out[i] = bb.getInt();
      }
      return out;
    }
  }
}
