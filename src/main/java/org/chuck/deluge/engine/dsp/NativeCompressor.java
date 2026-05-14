package org.chuck.deluge.engine.dsp;

/** Pure Java implementation of a Sidechain Compressor. */
public class NativeCompressor {
  private final float sampleRate;
  private double threshold = 0.5;
  private double ratio = 4.0;
  private double attack = 0.01;
  private double release = 0.1;
  private double envelope = 0.0;
  private double makeupGain = 1.0;

  public NativeCompressor(float sampleRate) {
    this.sampleRate = sampleRate;
  }

  public void setParams(
      double threshold, double ratio, double attack, double release, double makeup) {
    this.threshold = Math.max(0.001, threshold);
    this.ratio = Math.max(1.0, ratio);
    this.attack = attack;
    this.release = release;
    this.makeupGain = makeup;
  }

  public float[] process(float inL, float inR, float sidechainTrigger) {
    // Simple peak detector for sidechain
    double target = Math.abs(sidechainTrigger);
    double coeff =
        (target > envelope)
            ? Math.exp(-1.0 / (attack * sampleRate))
            : Math.exp(-1.0 / (release * sampleRate));

    envelope = target + coeff * (envelope - target);

    // Compression logic
    double gain = 1.0;
    if (envelope > threshold) {
      double dbOver = 20.0 * Math.log10(envelope / threshold);
      double dbReduced = dbOver / ratio;
      gain = Math.pow(10.0, (dbReduced - dbOver) / 20.0);
    }

    gain *= makeupGain;

    return new float[] {(float) (inL * gain), (float) (inR * gain)};
  }
}
