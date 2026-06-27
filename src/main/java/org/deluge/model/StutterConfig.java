package org.deluge.model;

/** Encapsulates all stutter effect parameters for a synthesizer track. */
public class StutterConfig {
  private float stutterRate = 0.0f;
  private boolean stutterQuantized = true;
  private boolean stutterReversed = false;
  private boolean stutterPingPong = false;

  public float getStutterRate() {
    return stutterRate;
  }

  public void setStutterRate(float stutterRate) {
    this.stutterRate = stutterRate;
  }

  public boolean isStutterQuantized() {
    return stutterQuantized;
  }

  public void setStutterQuantized(boolean stutterQuantized) {
    this.stutterQuantized = stutterQuantized;
  }

  public boolean isStutterReversed() {
    return stutterReversed;
  }

  public void setStutterReversed(boolean stutterReversed) {
    this.stutterReversed = stutterReversed;
  }

  public boolean isStutterPingPong() {
    return stutterPingPong;
  }

  public void setStutterPingPong(boolean stutterPingPong) {
    this.stutterPingPong = stutterPingPong;
  }

  public void copyFrom(StutterConfig other) {
    this.stutterRate = other.stutterRate;
    this.stutterQuantized = other.stutterQuantized;
    this.stutterReversed = other.stutterReversed;
    this.stutterPingPong = other.stutterPingPong;
  }
}
