package org.deluge.model;

/** Encapsulates all unison voice parameters for a synthesizer track. */
public class UnisonConfig {
  private int unisonNum = 1;
  private float unisonDetune = 0.0f;
  private float unisonStereoSpread = 0.0f;

  public int getUnisonNum() {
    return unisonNum;
  }

  public void setUnisonNum(int unisonNum) {
    this.unisonNum = unisonNum;
  }

  public float getUnisonDetune() {
    return unisonDetune;
  }

  public void setUnisonDetune(float unisonDetune) {
    this.unisonDetune = unisonDetune;
  }

  public float getUnisonStereoSpread() {
    return unisonStereoSpread;
  }

  public void setUnisonStereoSpread(float unisonStereoSpread) {
    this.unisonStereoSpread = unisonStereoSpread;
  }

  public void copyFrom(UnisonConfig other) {
    this.unisonNum = other.unisonNum;
    this.unisonDetune = other.unisonDetune;
    this.unisonStereoSpread = other.unisonStereoSpread;
  }
}
