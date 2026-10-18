package org.deluge.model;

/** A sample-based drum sound on a Kit track. */
public class SoundDrum extends Drum {

  private String samplePath = "";
  private boolean reverse = false;
  private float startMs = 0.0f;
  private float endMs = 0.0f;
  private int startSamplePos = -1;
  private int endSamplePos = -1;

  // Loop points in zone samples
  private int startLoopPos = -1;
  private int endLoopPos = -1;

  // Second oscillator (sample or synth)
  private String osc2Type = "NONE";
  private String osc2SamplePath = "";
  private int osc2StartSamplePos = -1;
  private int osc2EndSamplePos = -1;
  // Osc2 sample-playback params
  // osc1 sample flags (C Source::repeatMode / sampleControls, sound.cpp:3392-3430). A drum's
  // sample is osc1; these were never parsed, so every drum played as CUT, forward.
  // (osc1 "reversed" is the existing reverse field.)
  private int osc1LoopMode = 0;
  private boolean osc1TimeStretch = false;
  private boolean osc1LinearInterpolation = false;
  private int osc2LoopMode = 0;
  private boolean osc2Reversed = false;
  private boolean osc2TimeStretch = false;
  private int osc1TimeStretchAmount = 0; // C source.h:51 — int8_t, -48..48
  private int osc2TimeStretchAmount = 0;
  private boolean osc2LinearInterpolation = false;
  private int wavetableIndexPct = 50; // default middle wavetable scanning position

  public int getWavetableIndexPct() {
    return wavetableIndexPct;
  }

  public void setWavetableIndexPct(int val) {
    this.wavetableIndexPct = Math.max(0, Math.min(100, val));
  }

  public SoundDrum(String name) {
    super(name);
  }

  public SoundDrum(String name, String samplePath) {
    super(name);
    this.samplePath = samplePath;
  }

  public String getSamplePath() {
    return samplePath;
  }

  public void setSamplePath(String v) {
    this.samplePath = v;
  }

  public boolean isReverse() {
    return reverse;
  }

  public void setReverse(boolean v) {
    this.reverse = v;
  }

  public float getStartMs() {
    return startMs;
  }

  public void setStartMs(float v) {
    this.startMs = v;
  }

  public float getEndMs() {
    return endMs;
  }

  public void setEndMs(float v) {
    this.endMs = v;
  }

  public int getStartSamplePos() {
    return startSamplePos;
  }

  public void setStartSamplePos(int v) {
    this.startSamplePos = v;
  }

  public int getEndSamplePos() {
    return endSamplePos;
  }

  public void setEndSamplePos(int v) {
    this.endSamplePos = v;
  }

  public int getStartLoopPos() {
    return startLoopPos;
  }

  public void setStartLoopPos(int v) {
    this.startLoopPos = v;
  }

  public int getEndLoopPos() {
    return endLoopPos;
  }

  public void setEndLoopPos(int v) {
    this.endLoopPos = v;
  }

  public String getOsc2Type() {
    return osc2Type;
  }

  public void setOsc2Type(String v) {
    this.osc2Type = v;
  }

  public String getOsc2SamplePath() {
    return osc2SamplePath;
  }

  public void setOsc2SamplePath(String v) {
    this.osc2SamplePath = v;
  }

  public int getOsc2StartSamplePos() {
    return osc2StartSamplePos;
  }

  public void setOsc2StartSamplePos(int v) {
    this.osc2StartSamplePos = v;
  }

  public int getOsc2EndSamplePos() {
    return osc2EndSamplePos;
  }

  public void setOsc2EndSamplePos(int v) {
    this.osc2EndSamplePos = v;
  }

  public int getOsc1LoopMode() {
    return osc1LoopMode;
  }

  public void setOsc1LoopMode(int v) {
    this.osc1LoopMode = v;
  }

  public boolean isOsc1TimeStretch() {
    return osc1TimeStretch;
  }

  public void setOsc1TimeStretch(boolean v) {
    this.osc1TimeStretch = v;
  }

  public boolean isOsc1LinearInterpolation() {
    return osc1LinearInterpolation;
  }

  public void setOsc1LinearInterpolation(boolean v) {
    this.osc1LinearInterpolation = v;
  }

  public int getOsc2LoopMode() {
    return osc2LoopMode;
  }

  public void setOsc2LoopMode(int v) {
    this.osc2LoopMode = v;
  }

  public boolean isOsc2Reversed() {
    return osc2Reversed;
  }

  public void setOsc2Reversed(boolean v) {
    this.osc2Reversed = v;
  }

  public boolean isOsc2TimeStretch() {
    return osc2TimeStretch;
  }

  public void setOsc2TimeStretch(boolean v) {
    this.osc2TimeStretch = v;
  }

  public int getOsc2TimeStretchAmount() {
    return osc2TimeStretchAmount;
  }

  public int getOsc1TimeStretchAmount() {
    return osc1TimeStretchAmount;
  }

  public void setOsc1TimeStretchAmount(int v) {
    this.osc1TimeStretchAmount = Math.max(-48, Math.min(48, v));
  }

  public void setOsc2TimeStretchAmount(int v) {
    this.osc2TimeStretchAmount = Math.max(-48, Math.min(48, v));
  }

  public boolean isOsc2LinearInterpolation() {
    return osc2LinearInterpolation;
  }

  public void setOsc2LinearInterpolation(boolean v) {
    this.osc2LinearInterpolation = v;
  }
}
