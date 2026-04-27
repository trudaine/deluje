package org.chuck.deluge.ui.netbeans;

/** ViewModel for Transport controls (Play, Stop, BPM, etc.). */
public class TransportViewModel extends BaseViewModel {
  private boolean playing;
  private float bpm = 120.0f;
  private float swing = 0.5f;
  private float masterVol = 0.7f;
  private boolean recording;

  public boolean isPlaying() {
    return playing;
  }

  public void setPlaying(boolean playing) {
    boolean old = this.playing;
    this.playing = playing;
    firePropertyChange("playing", old, playing);
  }

  public float getBpm() {
    return bpm;
  }

  public void setBpm(float bpm) {
    float old = this.bpm;
    this.bpm = bpm;
    firePropertyChange("bpm", old, bpm);
  }

  public float getSwing() {
    return swing;
  }

  public void setSwing(float swing) {
    float old = this.swing;
    this.swing = swing;
    firePropertyChange("swing", old, swing);
  }

  public float getMasterVol() {
    return masterVol;
  }

  public void setMasterVol(float masterVol) {
    float old = this.masterVol;
    this.masterVol = masterVol;
    firePropertyChange("masterVol", old, masterVol);
  }

  public boolean isRecording() {
    return recording;
  }

  public void setRecording(boolean recording) {
    boolean old = this.recording;
    this.recording = recording;
    firePropertyChange("recording", old, recording);
  }
}
