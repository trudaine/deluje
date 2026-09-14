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

  /**
   * C Note::probability (note.h:40-42), the value the sequencer evaluates: 1..20 in 5% steps
   * ({@link #NUM_PROBABILITY_VALUES} = 100%), OR'ed with {@link #PROBABILITY_FOLLOW_PREVIOUS} to
   * mean "play if the previous note with this probability played" (the "latched" values the note
   * menu exposes up to {@code kNumProbabilityValues | 127}, gui/menu_item/note/probability.h:36).
   */
  private int probabilityValue = NUM_PROBABILITY_VALUES;

  /** C: definitions_cxx.hpp:708 kNumProbabilityValues. */
  public static final int NUM_PROBABILITY_VALUES = 20;

  /** Flag bit on {@link #getProbabilityValue()}; instrument_clip.cpp:821. */
  public static final int PROBABILITY_FOLLOW_PREVIOUS = 128;

  /** C: definitions_cxx.hpp:726-730 enum FillMode. */
  public static final int FILL_MODE_OFF = 0;

  public static final int FILL_MODE_NOT_FILL = 1;
  public static final int FILL_MODE_FILL = 2;

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
    this.probabilityValue = percentToProbabilityValue(Math.round(probability * 100.0f));
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

  /** UI-facing percent. Also sets the C value the sequencer plays by. */
  public void setProbability(int prob) {
    // Store the canonical C value and derive the percent from it, so what is reported is what plays
    // (0% reads back as 5%, the C minimum).
    setProbabilityValue(percentToProbabilityValue(prob));
  }

  /**
   * Nearest C probability value to a percent. The C has no 0%: its minimum is 1 = 5%
   * (gui/menu_item/note/probability.h:37), so 0..2% lands there rather than inventing a value.
   */
  static int percentToProbabilityValue(int percent) {
    return Math.max(1, Math.min(NUM_PROBABILITY_VALUES, Math.round(percent / 5.0f)));
  }

  /** C Note::setProbability (note.h:40): the raw value, follow-previous flag included. */
  public void setProbabilityValue(int value) {
    this.probabilityValue = value;
    this.probability = Math.min(value & 127, NUM_PROBABILITY_VALUES) * 5 / 100.0f;
  }

  /** C Note::getProbability (note.h:42). */
  public int getProbabilityValue() {
    return probabilityValue;
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
