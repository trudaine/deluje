package org.chuck.deluge.model;

/** A sample-based drum sound on a Kit track. */
public class SoundDrum extends Drum {

  private String samplePath = "";
  private boolean reverse = false;
  private float startMs = 0.0f;
  private float endMs = 0.0f;
  private int startSamplePos = -1;
  private int endSamplePos = -1;

  // Second oscillator (sample or synth)
  private String osc2Type = "NONE";
  private String osc2SamplePath = "";
  private int osc2StartSamplePos = -1;
  private int osc2EndSamplePos = -1;

  public SoundDrum(String name) {
    super(name);
  }

  public SoundDrum(String name, String samplePath) {
    super(name);
    this.samplePath = samplePath;
  }

  public String getSamplePath() { return samplePath; }
  public void setSamplePath(String v) { this.samplePath = v; }

  public boolean isReverse() { return reverse; }
  public void setReverse(boolean v) { this.reverse = v; }

  public float getStartMs() { return startMs; }
  public void setStartMs(float v) { this.startMs = v; }

  public float getEndMs() { return endMs; }
  public void setEndMs(float v) { this.endMs = v; }

  public int getStartSamplePos() { return startSamplePos; }
  public void setStartSamplePos(int v) { this.startSamplePos = v; }

  public int getEndSamplePos() { return endSamplePos; }
  public void setEndSamplePos(int v) { this.endSamplePos = v; }

  public String getOsc2Type() { return osc2Type; }
  public void setOsc2Type(String v) { this.osc2Type = v; }

  public String getOsc2SamplePath() { return osc2SamplePath; }
  public void setOsc2SamplePath(String v) { this.osc2SamplePath = v; }

  public int getOsc2StartSamplePos() { return osc2StartSamplePos; }
  public void setOsc2StartSamplePos(int v) { this.osc2StartSamplePos = v; }

  public int getOsc2EndSamplePos() { return osc2EndSamplePos; }
  public void setOsc2EndSamplePos(int v) { this.osc2EndSamplePos = v; }
}
