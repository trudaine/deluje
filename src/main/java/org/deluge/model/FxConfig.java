package org.deluge.model;

/**
 * Encapsulates all effect-send, equalization, and per-sound delay parameters for a synthesizer
 * track. This includes global delay/reverb sends, modulation FX (chorus/flanger), 2-band EQ, and
 * BPM-synced per-sound delay configurations.
 */
public class FxConfig {
  private float delaySend = 0.0f;
  private float reverbSend = 0.0f;
  private String modFxType = "NONE"; // NONE, CHORUS, FLANGER
  private float modFxRate = 0.0f;
  private float modFxDepth = 0.0f;
  private float modFxFeedback = 0.0f;
  private float modFxOffset = 0.0f;
  private float eqBass = 0.0f;
  private float eqTreble = 0.0f;
  private float eqBassFreq = 0.0f;
  private float eqTrebleFreq = 0.0f;

  // Per-sound delay parameters
  private int delaySyncLevel = 7; // FILE value: internal 6 (16ths) at tick magnitude 2
  private int delaySyncType = 0;
  private float delayFeedback = 0.0f;
  private int delayFeedbackQ31 = Integer.MIN_VALUE;
  private boolean delayPingPong = false;
  private boolean delayAnalog = false;

  public float getDelaySend() {
    return delaySend;
  }

  public void setDelaySend(float delaySend) {
    this.delaySend = delaySend;
  }

  public float getReverbSend() {
    return reverbSend;
  }

  public void setReverbSend(float reverbSend) {
    this.reverbSend = reverbSend;
  }

  public String getModFxType() {
    return modFxType;
  }

  public void setModFxType(String modFxType) {
    this.modFxType = modFxType;
  }

  public float getModFxRate() {
    return modFxRate;
  }

  public void setModFxRate(float modFxRate) {
    this.modFxRate = modFxRate;
  }

  public float getModFxDepth() {
    return modFxDepth;
  }

  public void setModFxDepth(float modFxDepth) {
    this.modFxDepth = modFxDepth;
  }

  public float getModFxFeedback() {
    return modFxFeedback;
  }

  public void setModFxFeedback(float modFxFeedback) {
    this.modFxFeedback = modFxFeedback;
  }

  public float getModFxOffset() {
    return modFxOffset;
  }

  public void setModFxOffset(float modFxOffset) {
    this.modFxOffset = modFxOffset;
  }

  public float getEqBass() {
    return eqBass;
  }

  public void setEqBass(float eqBass) {
    this.eqBass = eqBass;
  }

  public float getEqTreble() {
    return eqTreble;
  }

  public void setEqTreble(float eqTreble) {
    this.eqTreble = eqTreble;
  }

  public float getEqBassFreq() {
    return eqBassFreq;
  }

  public void setEqBassFreq(float eqBassFreq) {
    this.eqBassFreq = eqBassFreq;
  }

  public float getEqTrebleFreq() {
    return eqTrebleFreq;
  }

  public void setEqTrebleFreq(float eqTrebleFreq) {
    this.eqTrebleFreq = eqTrebleFreq;
  }

  public int getDelaySyncLevel() {
    return delaySyncLevel;
  }

  public void setDelaySyncLevel(int v) {
    this.delaySyncLevel = v;
  }

  public int getDelaySyncType() {
    return delaySyncType;
  }

  public void setDelaySyncType(int v) {
    this.delaySyncType = v;
  }

  public float getDelayFeedback() {
    return delayFeedback;
  }

  public void setDelayFeedback(float v) {
    this.delayFeedback = v;
    this.delayFeedbackQ31 = org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(v);
  }

  public int getDelayFeedbackQ31() {
    return delayFeedbackQ31;
  }

  public void setDelayFeedbackQ31(int v) {
    this.delayFeedbackQ31 = v;
    this.delayFeedback = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
  }

  public boolean isDelayPingPong() {
    return delayPingPong;
  }

  public void setDelayPingPong(boolean v) {
    this.delayPingPong = v;
  }

  public boolean isDelayAnalog() {
    return delayAnalog;
  }

  public void setDelayAnalog(boolean v) {
    this.delayAnalog = v;
  }

  /**
   * Copies all parameters from another FX configuration.
   *
   * @param other the source configuration to copy from
   */
  public void copyFrom(FxConfig other) {
    this.delaySend = other.delaySend;
    this.reverbSend = other.reverbSend;
    this.modFxType = other.modFxType;
    this.modFxRate = other.modFxRate;
    this.modFxDepth = other.modFxDepth;
    this.modFxFeedback = other.modFxFeedback;
    this.modFxOffset = other.modFxOffset;
    this.eqBass = other.eqBass;
    this.eqTreble = other.eqTreble;
    this.eqBassFreq = other.eqBassFreq;
    this.eqTrebleFreq = other.eqTrebleFreq;
    this.delaySyncLevel = other.delaySyncLevel;
    this.delaySyncType = other.delaySyncType;
    this.delayFeedback = other.delayFeedback;
    this.delayFeedbackQ31 = other.delayFeedbackQ31;
    this.delayPingPong = other.delayPingPong;
    this.delayAnalog = other.delayAnalog;
  }
}
