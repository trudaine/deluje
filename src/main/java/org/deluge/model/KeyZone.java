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
}
