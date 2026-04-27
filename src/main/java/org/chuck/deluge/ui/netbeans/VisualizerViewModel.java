package org.chuck.deluge.ui.netbeans;

/** ViewModel for the Visualizer component. */
public class VisualizerViewModel extends BaseViewModel {
  private float[] audioData;
  private double compressorGr;

  public float[] getAudioData() {
    return audioData;
  }

  public void setAudioData(float[] audioData) {
    float[] oldData = this.audioData;
    this.audioData = audioData;
    firePropertyChange("audioData", oldData, audioData);
  }

  public double getCompressorGr() {
    return compressorGr;
  }

  public void setCompressorGr(double compressorGr) {
    double oldGr = this.compressorGr;
    this.compressorGr = compressorGr;
    firePropertyChange("compressorGr", oldGr, compressorGr);
  }
}
