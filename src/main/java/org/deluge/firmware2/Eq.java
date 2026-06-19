package org.deluge.firmware2;

/**
 * Faithful port of {@code ModControllableAudio::doEQ} and its setup
 * (mod_controllable_audio.cpp:142-192, 373-403): a stereo bass/treble shelving EQ built from two
 * one-pole splits with persistent state. The bass/treble amounts are bipolar Q31 params (0 = flat),
 * and the corner frequencies are selected from the freq params via {@link Functions#getExp}.
 */
public final class Eq {
  // Persistent filter state (C members).
  private int withoutTrebleL;
  private int withoutTrebleR;
  private int bassOnlyL;
  private int bassOnlyR;
  private int bassFreq; // recomputed while bass is active
  private int trebleFreq; // recomputed while treble is active

  /**
   * Process a block of stereo samples in place.
   *
   * @param buffer {@code [numSamples][2]} — {l, r} per sample
   * @param bassParam UNPATCHED_BASS (bipolar Q31, 0 = flat)
   * @param trebleParam UNPATCHED_TREBLE (bipolar Q31, 0 = flat)
   * @param bassFreqParam UNPATCHED_BASS_FREQ
   * @param trebleFreqParam UNPATCHED_TREBLE_FREQ
   */
  public void process(
      int[][] buffer,
      int numSamples,
      int bassParam,
      int trebleParam,
      int bassFreqParam,
      int trebleFreqParam) {
    boolean doBass = bassParam != 0; // C:142-145 hasBassAdjusted
    boolean doTreble = trebleParam != 0; // C:147-150 hasTrebleAdjusted

    // Bass: no-change at 0, off completely at -536870912. (C:171-173)
    int positive = (bassParam >> 1) + 1073741824;
    int bassAmount =
        (Functions.multiply_32x32_rshift32_rounded(positive, positive) << 1) - 536870912;

    // Treble: no-change at 536870912. (C:175-177)
    positive = (trebleParam >> 1) + 1073741824;
    int trebleAmount = Functions.multiply_32x32_rshift32_rounded(positive, positive) << 1;

    if (doBass || doTreble) { // C:179
      if (doBass) {
        bassFreq = Functions.getExp(120000000, (bassFreqParam >> 5) * 6); // C:181
      }
      if (doTreble) {
        trebleFreq = Functions.getExp(700000000, (trebleFreqParam >> 5) * 6); // C:185
      }
      for (int i = 0; i < numSamples; i++) {
        doEQ(doBass, doTreble, buffer[i], bassAmount, trebleAmount);
      }
    }
  }

  /** C: doEQ (mod_controllable_audio.cpp:373-403). sample = {l, r}, modified in place. */
  private void doEQ(
      boolean doBass, boolean doTreble, int[] sample, int bassAmount, int trebleAmount) {
    int inL = sample[0];
    int inR = sample[1];
    int trebleOnlyL = 0;
    int trebleOnlyR = 0;

    if (doTreble) {
      int distanceToGoL = inL - withoutTrebleL;
      int distanceToGoR = inR - withoutTrebleR;
      withoutTrebleL += Functions.multiply_32x32_rshift32(distanceToGoL, trebleFreq) << 1;
      withoutTrebleR += Functions.multiply_32x32_rshift32(distanceToGoR, trebleFreq) << 1;
      trebleOnlyL = inL - withoutTrebleL;
      trebleOnlyR = inR - withoutTrebleR;
      inL = withoutTrebleL; // input now has the treble removed
      inR = withoutTrebleR;
    }

    if (doBass) {
      int distanceToGoL = inL - bassOnlyL;
      int distanceToGoR = inR - bassOnlyR;
      bassOnlyL += Functions.multiply_32x32_rshift32(distanceToGoL, bassFreq);
      bassOnlyR += Functions.multiply_32x32_rshift32(distanceToGoR, bassFreq);
    }

    if (doTreble) {
      inL += Functions.multiply_32x32_rshift32(trebleOnlyL, trebleAmount) << 3;
      inR += Functions.multiply_32x32_rshift32(trebleOnlyR, trebleAmount) << 3;
    }
    if (doBass) {
      inL += Functions.multiply_32x32_rshift32(bassOnlyL, bassAmount) << 3;
      inR += Functions.multiply_32x32_rshift32(bassOnlyR, bassAmount) << 3;
    }

    sample[0] = inL;
    sample[1] = inR;
  }
}
