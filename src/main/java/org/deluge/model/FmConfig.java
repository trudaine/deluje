package org.deluge.model;

/**
 * Encapsulates all Frequency Modulation (FM) synthesis and DX7 emulative parameters for a
 * synthesizer track. This includes modulator/carrier ratios, feedback amounts, raw Q31 native
 * parameters, DX7 patch hex strings, modulator transpose/cents, and modulator retrigger phases.
 */
public class FmConfig {
  private float fmRatio = 1.0f;
  private float fmAmount = 0.0f;
  private float modulator1Feedback = 0.0f;
  private float modulator2Amount = 0.0f;
  private float modulator2Feedback = 0.0f;
  private float carrier1Feedback = 0.0f;
  private float carrier2Feedback = 0.0f;
  private float fmRatio2 = 1.0f;

  // Raw modulator transpose (semitones) + cents
  private int modulator1Transpose = 0;
  private int modulator1Cents = 0;
  private int modulator2Transpose = 0;
  private int modulator2Cents = 0;

  // Raw Q31 parameters for the native 2-op FM engine (Integer.MIN_VALUE = off)
  private int modulator1AmountQ31 = Integer.MIN_VALUE;
  private int modulator2AmountQ31 = Integer.MIN_VALUE;
  private int modulator1FeedbackQ31 = Integer.MIN_VALUE;
  private int modulator2FeedbackQ31 = Integer.MIN_VALUE;
  private int carrier1FeedbackQ31 = Integer.MIN_VALUE;
  private int carrier2FeedbackQ31 = Integer.MIN_VALUE;

  private boolean modulator1ToModulator0 = false;

  // DX7 FM parameters
  private String dx7patch = null;
  private int dx7RandomDetune = 0;
  private int engineType = -1; // -1=AUTO, 0=MODERN, 1=VINTAGE

  // Modulator retrigger phases (default -1 = free-running)
  private int mod1RetrigPhase = -1;
  private int mod2RetrigPhase = -1;

  public float getFmRatio() {
    return fmRatio;
  }

  public void setFmRatio(float fmRatio) {
    this.fmRatio = fmRatio;
  }

  public float getFmAmount() {
    return fmAmount;
  }

  public void setFmAmount(float fmAmount) {
    this.fmAmount = fmAmount;
  }

  public float getModulator1Feedback() {
    return modulator1Feedback;
  }

  public void setModulator1Feedback(float modulator1Feedback) {
    this.modulator1Feedback = modulator1Feedback;
  }

  public float getModulator2Amount() {
    return modulator2Amount;
  }

  public void setModulator2Amount(float modulator2Amount) {
    this.modulator2Amount = modulator2Amount;
  }

  public float getModulator2Feedback() {
    return modulator2Feedback;
  }

  public void setModulator2Feedback(float modulator2Feedback) {
    this.modulator2Feedback = modulator2Feedback;
  }

  public float getCarrier1Feedback() {
    return carrier1Feedback;
  }

  public void setCarrier1Feedback(float carrier1Feedback) {
    this.carrier1Feedback = carrier1Feedback;
  }

  public float getCarrier2Feedback() {
    return carrier2Feedback;
  }

  public void setCarrier2Feedback(float carrier2Feedback) {
    this.carrier2Feedback = carrier2Feedback;
  }

  public float getFmRatio2() {
    return fmRatio2;
  }

  public void setFmRatio2(float fmRatio2) {
    this.fmRatio2 = fmRatio2;
  }

  public int getModulator1Transpose() {
    return modulator1Transpose;
  }

  public void setModulator1Transpose(int v) {
    this.modulator1Transpose = v;
  }

  public int getModulator1Cents() {
    return modulator1Cents;
  }

  public void setModulator1Cents(int v) {
    this.modulator1Cents = v;
  }

  public int getModulator2Transpose() {
    return modulator2Transpose;
  }

  public void setModulator2Transpose(int v) {
    this.modulator2Transpose = v;
  }

  public int getModulator2Cents() {
    return modulator2Cents;
  }

  public void setModulator2Cents(int v) {
    this.modulator2Cents = v;
  }

  public int getModulator1AmountQ31() {
    return modulator1AmountQ31;
  }

  public void setModulator1AmountQ31(int modulator1AmountQ31) {
    this.modulator1AmountQ31 = modulator1AmountQ31;
  }

  public int getModulator2AmountQ31() {
    return modulator2AmountQ31;
  }

  public void setModulator2AmountQ31(int modulator2AmountQ31) {
    this.modulator2AmountQ31 = modulator2AmountQ31;
  }

  public int getModulator1FeedbackQ31() {
    return modulator1FeedbackQ31;
  }

  public void setModulator1FeedbackQ31(int modulator1FeedbackQ31) {
    this.modulator1FeedbackQ31 = modulator1FeedbackQ31;
  }

  public int getModulator2FeedbackQ31() {
    return modulator2FeedbackQ31;
  }

  public void setModulator2FeedbackQ31(int modulator2FeedbackQ31) {
    this.modulator2FeedbackQ31 = modulator2FeedbackQ31;
  }

  public int getCarrier1FeedbackQ31() {
    return carrier1FeedbackQ31;
  }

  public void setCarrier1FeedbackQ31(int carrier1FeedbackQ31) {
    this.carrier1FeedbackQ31 = carrier1FeedbackQ31;
  }

  public int getCarrier2FeedbackQ31() {
    return carrier2FeedbackQ31;
  }

  public void setCarrier2FeedbackQ31(int carrier2FeedbackQ31) {
    this.carrier2FeedbackQ31 = carrier2FeedbackQ31;
  }

  public boolean isModulator1ToModulator0() {
    return modulator1ToModulator0;
  }

  public void setModulator1ToModulator0(boolean modulator1ToModulator0) {
    this.modulator1ToModulator0 = modulator1ToModulator0;
  }

  public String getDx7patch() {
    return dx7patch;
  }

  public void setDx7patch(String dx7patch) {
    this.dx7patch = dx7patch;
  }

  public int getDx7RandomDetune() {
    return dx7RandomDetune;
  }

  public void setDx7RandomDetune(int dx7RandomDetune) {
    this.dx7RandomDetune = dx7RandomDetune;
  }

  public int getEngineType() {
    return engineType;
  }

  public void setEngineType(int engineType) {
    this.engineType = engineType;
  }

  public int getMod1RetrigPhase() {
    return mod1RetrigPhase;
  }

  public void setMod1RetrigPhase(int v) {
    this.mod1RetrigPhase = v;
  }

  public int getMod2RetrigPhase() {
    return mod2RetrigPhase;
  }

  public void setMod2RetrigPhase(int v) {
    this.mod2RetrigPhase = v;
  }

  /**
   * Copies all parameters from another FM configuration.
   *
   * @param other the source configuration to copy from
   */
  public void copyFrom(FmConfig other) {
    this.fmRatio = other.fmRatio;
    this.fmAmount = other.fmAmount;
    this.modulator1Feedback = other.modulator1Feedback;
    this.modulator2Amount = other.modulator2Amount;
    this.modulator2Feedback = other.modulator2Feedback;
    this.carrier1Feedback = other.carrier1Feedback;
    this.carrier2Feedback = other.carrier2Feedback;
    this.fmRatio2 = other.fmRatio2;
    this.modulator1Transpose = other.modulator1Transpose;
    this.modulator1Cents = other.modulator1Cents;
    this.modulator2Transpose = other.modulator2Transpose;
    this.modulator2Cents = other.modulator2Cents;
    this.modulator1AmountQ31 = other.modulator1AmountQ31;
    this.modulator2AmountQ31 = other.modulator2AmountQ31;
    this.modulator1FeedbackQ31 = other.modulator1FeedbackQ31;
    this.modulator2FeedbackQ31 = other.modulator2FeedbackQ31;
    this.carrier1FeedbackQ31 = other.carrier1FeedbackQ31;
    this.carrier2FeedbackQ31 = other.carrier2FeedbackQ31;
    this.modulator1ToModulator0 = other.modulator1ToModulator0;
    this.dx7patch = other.dx7patch;
    this.dx7RandomDetune = other.dx7RandomDetune;
    this.engineType = other.engineType;
    this.mod1RetrigPhase = other.mod1RetrigPhase;
    this.mod2RetrigPhase = other.mod2RetrigPhase;
  }
}
