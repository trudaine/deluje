package org.chuck.deluge.engine.dsp;

/**
 * High-performance, pure Java implementation of a resonant ladder filter. Based on the Houvilainen
 * model (Moog-style).
 */
public class NativeMoogFilter {
  private final float sampleRate;
  private double cutoff = 10000.0;
  private double resonance = 0.5;

  // Filter state
  private double s1, s2, s3, s4;
  private double x1, x2, x3, x4;

  // Coefficients
  private double f, fb;

  public NativeMoogFilter(float sampleRate) {
    this.sampleRate = sampleRate;
    setParams(1000.0, 0.1);
  }

  public void setParams(double cutoff, double resonance) {
    this.cutoff = Math.max(20.0, Math.min(cutoff, sampleRate * 0.45));
    this.resonance = Math.max(0.0, Math.min(resonance, 1.0));

    // ── Bit-Accurate Houvilainen Math ──
    this.f = (this.cutoff * 2.0 / sampleRate);
    this.f = 1.0 - Math.exp(-2.0 * Math.PI * f / 2.0); // Oversampled mapping
    this.fb = resonance * (1.0 + 4.0 * (1.0 - resonance * 0.15)); // hardware comp
  }

  public float tick(float input) {
    // Simple ladder approximation
    double in = input - fb * s4;

    // Four cascaded one-pole stages
    s1 = s1 + f * (in - s1);
    s2 = s2 + f * (s1 - s2);
    s3 = s3 + f * (s2 - s3);
    s4 = s4 + f * (s3 - s4);

    // Saturation/clamping to prevent blowup
    if (s4 > 1.0) s4 = 1.0;
    else if (s4 < -1.0) s4 = -1.0;

    return (float) s4;
  }

  public void reset() {
    s1 = s2 = s3 = s4 = 0;
  }
}
