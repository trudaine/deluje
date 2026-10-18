package org.deluge.model;

/** Represents a multisample key zone mapping sample paths to pitch and velocity ranges. */
public class KeyZone {
  public String samplePath;
  public int minPitch;
  public int maxPitch;
  public int minVelocity = 0;
  public int maxVelocity = 127;
  public int startSamplePos = 0;
  public int endSamplePos = -1;
  public int startLoopPos = -1;
  public int endLoopPos = -1;
  public boolean looping = false;

  /** Per-sampleRange transpose (semitones) tuning the recorded sample to the played note. */
  public int transpose = 0;

  /** Per-sampleRange cents fine-tune (C SampleHolderForVoice cents, applied via fineTuner). */
  public int cents = 0;

  /** Returns an independent copy of this key zone. */
  public KeyZone copy() {
    KeyZone copy = new KeyZone();
    copy.samplePath = this.samplePath;
    copy.minPitch = this.minPitch;
    copy.maxPitch = this.maxPitch;
    copy.minVelocity = this.minVelocity;
    copy.maxVelocity = this.maxVelocity;
    copy.startSamplePos = this.startSamplePos;
    copy.endSamplePos = this.endSamplePos;
    copy.startLoopPos = this.startLoopPos;
    copy.endLoopPos = this.endLoopPos;
    copy.looping = this.looping;
    copy.transpose = this.transpose;
    copy.cents = this.cents;
    return copy;
  }
}
