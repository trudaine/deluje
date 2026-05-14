package org.chuck.deluge.engine.dsp;

/** Pure Java Stereo Delay implementation. */
public class NativeDelay {
  private final float sampleRate;
  private final float[] bufferL;
  private final float[] bufferR;
  private int writeIdx = 0;
  private int delaySamples = 0;
  private double feedback = 0.5;
  private double damping = 0.3;
  private float lastOutL = 0;
  private float lastOutR = 0;

  public NativeDelay(float sampleRate, double maxSeconds) {
    this.sampleRate = sampleRate;
    int size = (int) (sampleRate * maxSeconds) + 1;
    this.bufferL = new float[size];
    this.bufferR = new float[size];
  }

  public void setParams(double seconds, double feedback, double damping) {
    this.delaySamples = (int) (seconds * sampleRate);
    if (delaySamples >= bufferL.length) delaySamples = bufferL.length - 1;
    this.feedback = Math.max(0.0, Math.min(0.95, feedback));
    this.damping = Math.max(0.0, Math.min(0.9, damping));
  }

  public float[] tick(float inL, float inR) {
    int readIdx = writeIdx - delaySamples;
    if (readIdx < 0) readIdx += bufferL.length;

    float outL = bufferL[readIdx];
    float outR = bufferR[readIdx];

    // Damping (Low-pass in feedback)
    lastOutL = (float) (outL * (1.0 - damping) + lastOutL * damping);
    lastOutR = (float) (outR * (1.0 - damping) + lastOutR * damping);

    bufferL[writeIdx] = (float) (inL + lastOutL * feedback);
    bufferR[writeIdx] = (float) (inR + lastOutR * feedback);

    writeIdx = (writeIdx + 1) % bufferL.length;

    return new float[] {outL, outR};
  }
}
