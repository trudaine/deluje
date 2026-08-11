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
 * Bit-diff of the Java {@link Lfo} against golden buffers emitted by a C harness that compiles the
 * <em>real</em> firmware {@code modulation/lfo.h} and {@code lfo.cpp} (sources + build script:
 * {@code tools/lfo_harness/}).
 *
 * <p><b>Why.</b> Issue #3 (moving pulse-width diverges from hardware) named LFO waveform accuracy
 * as one of its two remaining unverified suspects, and the LFO was the last major DSP block with no
 * golden harness — the existing nine cover Dx7Env, FilterSet, FmKernel, HpLadder, Ladder, Osc,
 * Reverb, Svf and WaveTable, none of them modulation-side. Per CLAUDE.md a read-audit is not
 * evidence here; this makes the claim checkable.
 *
 * <p>Each case compares two arrays: the value {@code render()} returns per block, and the phase
 * accumulator after each block. The phase is included because a divergence there can hide behind an
 * agreeing value (e.g. a flat region of a waveform) and only surface blocks later.
 *
 * <p>The random wave types additionally pin the CONG call <em>count and order</em>: draw one extra
 * {@code getNoise()} anywhere and every later value desynchronises. Both sides seed jcong to
 * 380116160 ({@link Functions#resetNoiseSeed()}).
 *
 * <p>Goldens: {@code src/test/resources/fidelity/lfo/}; regenerate with {@code
 * tools/lfo_harness/build.sh}.
 */
@Tag("slow")
class LfoGoldenBufferTest {

  private static final int NBLOCKS = 64;
  private static final int NSAMP = 64;

  private record Case(String file, Lfo.LfoType type, int inc, boolean global) {}

  private static java.util.stream.Stream<Arguments> cases() {
    java.util.List<Case> cs = new java.util.ArrayList<>();
    for (Lfo.LfoType t :
        new Lfo.LfoType[] {
          Lfo.LfoType.SINE, Lfo.LfoType.TRIANGLE, Lfo.LfoType.SQUARE, Lfo.LfoType.SAW
        }) {
      String n = t.name().toLowerCase(java.util.Locale.ROOT);
      cs.add(new Case("c_lfo_" + n + "_i8388608_n64_local.bin", t, 8388608, false));
      cs.add(new Case("c_lfo_" + n + "_i8388608_n64_global.bin", t, 8388608, true));
    }
    for (Lfo.LfoType t :
        new Lfo.LfoType[] {
          Lfo.LfoType.SAMPLE_AND_HOLD, Lfo.LfoType.RANDOM_WALK, Lfo.LfoType.WARBLER
        }) {
      String n = t.name().toLowerCase(java.util.Locale.ROOT);
      cs.add(new Case("c_lfo_" + n + "_i8388608_n64_local.bin", t, 8388608, false));
      cs.add(new Case("c_lfo_" + n + "_i71582788_n64_local.bin", t, 71582788, false));
    }
    return cs.stream().map(c -> Arguments.of(c.file, c));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("cases")
  void javaLfoMatchesCGolden(String name, Case c) throws IOException {
    int[] golden = readGolden(c.file());
    Assumptions.assumeTrue(golden != null, "missing golden resource: " + c.file());
    assertEquals(NBLOCKS * 2, golden.length, "golden size (values + phases)");

    Functions.resetNoiseSeed(); // match the C harness jcong seed (380116160)
    Lfo lfo = new Lfo();
    Lfo.LfoConfig config = new Lfo.LfoConfig(c.type());
    if (c.global()) {
      lfo.setGlobalInitialPhase(config);
    } else {
      lfo.setLocalInitialPhase(config);
    }

    int firstValDiff = -1;
    int firstPhaseDiff = -1;
    for (int b = 0; b < NBLOCKS; b++) {
      int v = lfo.render(NSAMP, config, c.inc());
      if (v != golden[b] && firstValDiff < 0) firstValDiff = b;
      if (lfo.phase != golden[NBLOCKS + b] && firstPhaseDiff < 0) firstPhaseDiff = b;
    }

    System.out.printf(
        "[%-46s] firstValueDiff=%d firstPhaseDiff=%d%n", c.file(), firstValDiff, firstPhaseDiff);

    final int fv = firstValDiff;
    if (fv >= 0) {
      Functions.resetNoiseSeed();
      Lfo re = new Lfo();
      Lfo.LfoConfig rc = new Lfo.LfoConfig(c.type());
      if (c.global()) {
        re.setGlobalInitialPhase(rc);
      } else {
        re.setLocalInitialPhase(rc);
      }
      int got = 0;
      for (int b = 0; b <= fv; b++) got = re.render(NSAMP, rc, c.inc());
      final int g = got;
      assertEquals(
          golden[fv],
          g,
          () -> "Java LFO value diverges from C golden " + c.file() + " at block " + fv);
    }
    final int fp = firstPhaseDiff;
    assertEquals(
        -1, fp, () -> "Java LFO phase diverges from C golden " + c.file() + " at block " + fp);
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream in = LfoGoldenBufferTest.class.getResourceAsStream("/fidelity/lfo/" + file)) {
      if (in == null) return null;
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) out[i] = bb.getInt();
      return out;
    }
  }
}
