package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code processing/metronome/metronome.{cpp,h}}: the metronome click — a short
 * square wave (high then low) that decays after ~1000 samples. The song's post-FX volume (C reads it
 * from {@code currentSong->paramManager}) is injected into {@link #render} as a seam.
 */
public class Metronome {

  static final int ONE_Q31 = 2147483647;

  public int phase;            // uint32
  public int phaseIncrement;   // uint32
  public int timeSinceTrigger; // uint32
  public int metronomeVolume;  // uint32
  public boolean sounding;

  /** C: metronome.cpp:26-29 */
  public Metronome() {
    sounding = false;
    setVolume(25);
  }

  /** C: metronome.cpp:31-36 */
  public void trigger(int newPhaseIncrement) {
    sounding = true;
    phase = 0;
    phaseIncrement = newPhaseIncrement;
    timeSinceTrigger = 0;
  }

  /** C: metronome.h:31 — exp volume map. */
  public void setVolume(int linearParam) {
    metronomeVolume = (int) ((Math.exp((double) ((float) linearParam / 200.0f)) - 1.0) * (double) (float) (1 << 27));
  }

  /**
   * C: metronome.cpp:38-67. {@code volumePostFX} is the seam the C derives from the song (or
   * {@code ONE_Q31} when there is no song).
   */
  public void render(int[][] buffer, int numSamples, int volumePostFX) {
    if (!sounding) {
      return;
    }
    int high = Functions.multiply_32x32_rshift32(metronomeVolume, volumePostFX);

    for (int i = 0; i < numSamples; i++) {
      int value = (Integer.compareUnsigned(phase, ONE_Q31) <= 0) ? high : -high; // C:58 (phase <= ONE_Q31)
      phase += phaseIncrement;
      buffer[i][0] += value;
      buffer[i][1] += value;
    }

    timeSinceTrigger += numSamples;
    if (Integer.compareUnsigned(timeSinceTrigger, 1000) > 0) { // C:64
      sounding = false;
    }
  }
}
