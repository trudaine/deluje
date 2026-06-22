package org.deluge.model;

/**
 * Unified Note model representing both unquantized high-resolution XML document note events and
 * runtime sequencer playback note states.
 */
public class NoteModel {
  private int tickPos;
  private int tickLen;
  public int pos; // Alias for tickPos for Note parity
  public int length; // Alias for tickLen for Note parity
  public float velocity = 0.8f;
  public float probability = 1.0f;
  private Iterance iterance = new Iterance();
  private byte fill = 0;
  private byte lift = 0;
  private int subTriggers = 0;

  public NoteModel(int tickPos, int tickLen, float velocity, float probability, int subTriggers) {
    this.tickPos = tickPos;
    this.pos = tickPos;
    this.tickLen = tickLen;
    this.length = tickLen;
    this.velocity = velocity;
    this.probability = probability;
    this.subTriggers = subTriggers;
  }

  public NoteModel() {
    this.tickPos = 0;
    this.pos = 0;
    this.tickLen = 24; // default quarter step
    this.length = 24;
  }

  // --- Document / XML Interface (HighResNote Parity) ---

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

  // --- Sequencer / Playback Interface (playback.Note Parity) ---

  public int getPos() {
    return tickPos;
  }

  public void setPos(int pos) {
    this.tickPos = pos;
    this.pos = pos;
  }

  public int getLength() {
    return tickLen;
  }

  public void setLength(int len) {
    this.tickLen = len;
    this.length = len;
  }

  public void setVelocity(int vel) {
    this.velocity = vel / 127.0f;
  }

  public int getVelocityByte() {
    return Math.max(0, Math.min(127, (int) (velocity * 127.0f)));
  }

  public void setProbability(int prob) {
    this.probability = prob / 100.0f;
  }

  public int getProbabilityPercent() {
    return Math.max(0, Math.min(100, (int) (probability * 100.0f)));
  }

  public void setIterance(Iterance iterance) {
    this.iterance = iterance;
  }

  public Iterance getIterance() {
    return iterance;
  }

  public void setFill(int fill) {
    this.fill = (byte) fill;
  }

  public int getFill() {
    return fill & 0xFF;
  }

  public void setLift(int lift) {
    this.lift = (byte) lift;
  }

  public int getLift() {
    return lift & 0xFF;
  }

  public boolean isDrone(int effectiveLength) {
    return (tickPos == 0 && tickLen == effectiveLength);
  }

  @Override
  public String toString() {
    return String.format(
        "NoteModel{pos=%d, len=%d, vel=%.2f, prob=%.2f}", tickPos, tickLen, velocity, probability);
  }
}
