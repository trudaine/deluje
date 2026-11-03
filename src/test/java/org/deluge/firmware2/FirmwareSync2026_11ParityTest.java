package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Test;

/**
 * The behavioural deltas retrofitted in the 2026-11-03 firmware sync (upstream {@code a616dd8e6} →
 * {@code f649f7fa2}); see {@code docs/firmware_sync_audit.md} §11.
 *
 * <p>Each case states the C it ports, and each is written so the pre-sync Java would fail it.
 */
class FirmwareSync2026_11ParityTest {

  // ── #5235/#5228/#5232: AbsValueFollower::calcApproxRMS (absolute_value.cpp:25-59) ──

  @Test
  void absValueFollowerShiftsEachSampleDownBeforeAccumulating() {
    // The C sums |x| >> 8 and multiplies the mean by 256, so the per-sample truncation is part of
    // the result. Accumulating |x| and dividing (what this port used to do) reads high.
    AbsValueFollower follower = new AbsValueFollower();
    follower.setup(0, 0);
    follower.reset();

    int[][] buffer = new int[128][2];
    for (int i = 0; i < 128; i++) {
      buffer[i][0] = 0x1FF; // >> 8 keeps 1, so the mean lands on 256, not 511
      buffer[i][1] = 0x1FF;
    }
    follower.calcApproxRMS(buffer);
    // The raw mean is 256 * (128 * (0x1FF >> 8)) / 128 = 256, which C:51-52 then weights against
    // the (zero) previous mean: 256 * 128 / 129 = 254.02. Summing the unshifted 0x1FF instead —
    // what this port used to do — gives 511 * 128 / 129 = 507.
    assertEquals(
        254.02f, follower.meanL, 0.05f, "mean must come from the shifted-down accumulator");
  }

  @Test
  void absValueFollowerSurvivesIntMinAndAFullLoudBlock() {
    // absForLevel(INT_MIN) is INT_MAX, where Math.abs would hand back INT_MIN and drag the mean
    // negative (C absolute_value.cpp:25-27).
    assertEquals(Integer.MAX_VALUE, AbsValueFollower.absForLevel(Integer.MIN_VALUE));
    assertEquals(0x7FFFFFFE, AbsValueFollower.absForLevel(-0x7FFFFFFF)); // negatives come out 1 low
    assertEquals(1234, AbsValueFollower.absForLevel(1234));

    AbsValueFollower follower = new AbsValueFollower();
    follower.setup(0, 0);
    follower.reset();
    int[][] buffer = new int[256][2];
    for (int i = 0; i < buffer.length; i++) {
      buffer[i][0] = Integer.MIN_VALUE;
      buffer[i][1] = Integer.MAX_VALUE;
    }
    float[] rms = follower.calcApproxRMS(buffer);
    // 256 samples of full scale is the C's stated worst case; the accumulator must stay positive.
    assertTrue(follower.meanL > 0.f, "mean must not go negative on INT_MIN input");
    assertTrue(rms[0] > 19.f && rms[0] < 22.f, "log mean of full scale is ~21, saw " + rms[0]);
  }

  // ── #5198: the supersaw oscillator (dsp/oscillators/supersaw.{h,cpp}) ──

  @Test
  void supersawSidePhasesWalkTheGoldenRatio() {
    // C supersaw.h:36-42
    int[] phases = Supersaw.sidePhasesFrom(0);
    assertEquals(6, phases.length);
    for (int i = 0; i < phases.length; i++) {
      assertEquals((i + 1) * 0x9E3779B9, phases[i], "side phase " + i);
    }
    int[] offset = Supersaw.sidePhasesFrom(0x12345678);
    assertEquals(0x12345678 + 0x9E3779B9, offset[0]);
  }

  @Test
  void pulseWidthTakesSawsAway() {
    // C supersaw.h:46-51 — all seven saws at zero, down to the centre saw alone at full width,
    // with negative widths counting the same as positive.
    assertEquals(7, Supersaw.numSawsFromPulseWidth(0));
    assertEquals(1, Supersaw.numSawsFromPulseWidth(Integer.MAX_VALUE));
    assertEquals(1, Supersaw.numSawsFromPulseWidth(Integer.MIN_VALUE));
    assertEquals(
        Supersaw.numSawsFromPulseWidth(1 << 29), Supersaw.numSawsFromPulseWidth(-(1 << 29)));
    // Quarter width: removed = (2^29 * 7) >> 31 = 1, so six saws are left.
    assertEquals(6, Supersaw.numSawsFromPulseWidth(1 << 29));
    // Half width removes three of them: (2^30 * 7) >> 31 = 3.
    assertEquals(4, Supersaw.numSawsFromPulseWidth(1 << 30));
  }

  @Test
  void supersawRendersAndHoldsThePhasesOfSawsThatArentPlaying() {
    int[] buffer = new int[132];
    int[] phase = {0};
    int[] sidePhases = Supersaw.sidePhasesFrom(0);
    int[] before = sidePhases.clone();

    Supersaw.render(buffer, 0, 128, 25480253, 0, 3, phase, sidePhases, true);

    assertNotEquals(0, phase[0], "the centre phase must advance");
    for (int i = 0; i < 2; i++) {
      assertNotEquals(before[i], sidePhases[i], "playing side saw " + i + " must advance");
    }
    for (int i = 2; i < sidePhases.length; i++) {
      assertEquals(before[i], sidePhases[i], "side saw " + i + " is not playing, so it holds");
    }
    long peak = 0;
    for (int i = 0; i < 128; i++) {
      peak = Math.max(peak, Math.abs((long) buffer[i]));
    }
    assertTrue(peak > (1 << 27), "render must produce signal near a saw's level, saw " + peak);
  }

  @Test
  void spreadWidensTheDetuneSoTheOutputChanges() {
    int[] narrow = render(-(1 << 30));
    int[] wide = render(1 << 30);
    boolean differs = false;
    for (int i = 0; i < narrow.length; i++) {
      if (narrow[i] != wide[i]) {
        differs = true;
        break;
      }
    }
    assertTrue(differs, "spread must change the rendered saws");
  }

  private static int[] render(int spread) {
    int[] buffer = new int[132];
    int[] phase = {0};
    Supersaw.render(buffer, 0, 128, 25480253, spread, 7, phase, Supersaw.sidePhasesFrom(0), true);
    return java.util.Arrays.copyOf(buffer, 128);
  }

  @Test
  void renderOscRendersTheSupersawForEveryCaller() {
    // Only Oscillator.renderOsc knows how to render a supersaw (C oscillator.cpp:31-46), and the
    // ring-mod path goes through it too. The first version of this port dispatched in Voice's
    // subtractive branch only, so renderOsc(SUPERSAW, ...) wrote nothing: ring mod then multiplied
    // by a buffer of zeros and the voice went silent.
    int[] buffer = new int[132];
    int[] phase = {0};
    Oscillator.renderOsc(
        OscType.SUPERSAW,
        1 << 27,
        buffer,
        0,
        128,
        25480253,
        0,
        phase,
        false,
        0,
        false,
        0,
        0,
        0,
        0,
        0,
        Supersaw.sidePhasesFrom(0));
    long peak = 0;
    for (int i = 0; i < 128; i++) {
      peak = Math.max(peak, Math.abs((long) buffer[i]));
    }
    assertTrue(peak > (1 << 27), "renderOsc must render the supersaw, saw peak " + peak);
  }

  @Test
  void ringModWithASupersawSourceIsNotSilent() {
    Sound sound = new Sound();
    Sound.initParams(sound.patchedParamValues);
    Sound.setupAsBlankSynth(sound.patchedParamValues, sound, false);
    sound.bitcrushParam = Integer.MIN_VALUE;
    sound.srrParam = Integer.MIN_VALUE;
    sound.synthMode = 2; // RingMod
    sound.lpfMode = FilterSet.FilterMode.OFF;
    sound.hpfMode = FilterSet.FilterMode.OFF;
    sound.oscTypes[0] = OscType.SUPERSAW;
    sound.oscTypes[1] = OscType.SAW;

    Voice voice = new Voice(sound);
    voice.noteOn(60, 100, -1, null);
    int[] out = new int[256];
    voice.render(out, 128, false, false);
    long peak = 0;
    for (int v : out) {
      peak = Math.max(peak, Math.abs((long) v));
    }
    assertTrue(peak > 0, "a supersaw ring-mod source must not silence the voice");
  }

  @Test
  void supersawSitsBeforeWavetableInTheOscTypeOrder() {
    // C definitions_cxx.hpp:358-374 — SUPERSAW is ring-moddable, i.e. below
    // kLastRingmoddableOscType
    // (WAVETABLE), which is what the "> WAVETABLE" tests in Voice rely on.
    assertTrue(OscType.SUPERSAW.ordinal() < OscType.WAVETABLE.ordinal());
    assertTrue(OscType.SUPERSAW.ordinal() > OscType.ANALOG_SQUARE.ordinal());
  }
}
