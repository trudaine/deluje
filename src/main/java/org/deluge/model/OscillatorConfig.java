package org.deluge.model;

/**
 * Encapsulates the parameters for a single oscillator in a synthesizer track. Supports both
 * standard waveforms (sine, saw, etc.) and sample-playback configurations.
 */
public class OscillatorConfig {
  private String type;
  private float volume = 1.0f;
  private int transpose = 0;
  private int cents = 0;
  private int loopMode = 0; // 0=off, 1=loop, 2=oneshot
  private boolean reversed = false;
  private boolean timeStretch = false;
  private float timeStretchAmount = 0.0f;
  private boolean linearInterpolation = false;
  private String samplePath = null;
  private int pitchAdjustQ31 = Integer.MIN_VALUE;
  private int phaseWidthQ31 = Integer.MIN_VALUE;
  private int retrigPhase = -1;

  /**
   * Constructs an oscillator configuration with a default waveform type.
   *
   * @param defaultType the default waveform type (e.g. "SINE" or "NONE")
   */
  public OscillatorConfig(String defaultType) {
    this.type = defaultType;
  }

  public String getType() {
    return type;
  }

  public void setType(String type) {
    this.type = type;
  }

  public float getVolume() {
    return volume;
  }

  public void setVolume(float volume) {
    this.volume = volume;
  }

  public int getTranspose() {
    return transpose;
  }

  public void setTranspose(int transpose) {
    this.transpose = transpose;
  }

  public int getCents() {
    return cents;
  }

  public void setCents(int cents) {
    this.cents = cents;
  }

  public int getLoopMode() {
    return loopMode;
  }

  public void setLoopMode(int loopMode) {
    this.loopMode = loopMode;
  }

  public boolean isReversed() {
    return reversed;
  }

  public void setReversed(boolean reversed) {
    this.reversed = reversed;
  }

  public boolean isTimeStretch() {
    return timeStretch;
  }

  public void setTimeStretch(boolean timeStretch) {
    this.timeStretch = timeStretch;
  }

  public float getTimeStretchAmount() {
    return timeStretchAmount;
  }

  public void setTimeStretchAmount(float timeStretchAmount) {
    this.timeStretchAmount = timeStretchAmount;
  }

  public boolean isLinearInterpolation() {
    return linearInterpolation;
  }

  public void setLinearInterpolation(boolean linearInterpolation) {
    this.linearInterpolation = linearInterpolation;
  }

  public String getSamplePath() {
    return samplePath;
  }

  public void setSamplePath(String samplePath) {
    this.samplePath = samplePath;
  }

  public int getPitchAdjustQ31() {
    return pitchAdjustQ31;
  }

  public void setPitchAdjustQ31(int pitchAdjustQ31) {
    this.pitchAdjustQ31 = pitchAdjustQ31;
  }

  public int getPhaseWidthQ31() {
    return phaseWidthQ31;
  }

  public void setPhaseWidthQ31(int phaseWidthQ31) {
    this.phaseWidthQ31 = phaseWidthQ31;
  }

  public int getRetrigPhase() {
    return retrigPhase;
  }

  public void setRetrigPhase(int retrigPhase) {
    this.retrigPhase = retrigPhase;
  }

  /**
   * Copies all parameters from another oscillator configuration.
   *
   * @param other the source configuration to copy from
   */
  public void copyFrom(OscillatorConfig other) {
    this.type = other.type;
    this.volume = other.volume;
    this.transpose = other.transpose;
    this.cents = other.cents;
    this.loopMode = other.loopMode;
    this.reversed = other.reversed;
    this.timeStretch = other.timeStretch;
    this.timeStretchAmount = other.timeStretchAmount;
    this.linearInterpolation = other.linearInterpolation;
    this.samplePath = other.samplePath;
    this.pitchAdjustQ31 = other.pitchAdjustQ31;
    this.phaseWidthQ31 = other.phaseWidthQ31;
    this.retrigPhase = other.retrigPhase;
  }
}
