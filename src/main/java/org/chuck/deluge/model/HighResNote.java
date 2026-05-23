package org.chuck.deluge.model;

/**
 * Represents an unquantized, high-resolution note event parsed directly from the raw Deluge XML
 * noteData hex, preserving exact tick coordinates (96 PPQN).
 */
public class HighResNote {
  private final int tickPos;
  private final int tickLen;
  private final float velocity;
  private final float probability;
  private final int subTriggers;

  public HighResNote(int tickPos, int tickLen, float velocity, float probability, int subTriggers) {
    this.tickPos = tickPos;
    this.tickLen = tickLen;
    this.velocity = velocity;
    this.probability = probability;
    this.subTriggers = subTriggers;
  }

  public int getTickPos() {
    return tickPos;
  }

  public int getTickLen() {
    return tickLen;
  }

  public float getVelocity() {
    return velocity;
  }

  public float getProbability() {
    return probability;
  }

  public int getSubTriggers() {
    return subTriggers;
  }

  @Override
  public String toString() {
    return String.format(
        "HighResNote{pos=%d, len=%d, vel=%.2f, prob=%.2f}",
        tickPos, tickLen, velocity, probability);
  }
}
