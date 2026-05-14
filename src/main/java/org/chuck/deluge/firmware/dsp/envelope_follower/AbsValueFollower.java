package org.chuck.deluge.firmware.dsp.envelope_follower;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoFloatSample;
import org.chuck.deluge.firmware.dsp.StereoSample;

public class AbsValueFollower {
  private static final float kSampleRate = 44100.0f;

  private float attackMS = 1.0f;
  private float releaseMS = 10.0f;
  private float a_ = (-1000.0f / kSampleRate) / attackMS;
  private float r_ = (-1000.0f / kSampleRate) / releaseMS;

  private float meanL = 0;
  private float lastMeanL = 0;
  private float meanR = 0;
  private float lastMeanR = 0;

  private int attackKnobPos = 0;
  private int releaseKnobPos = 0;

  public void reset() {
    meanL = 0;
    lastMeanL = 0;
    meanR = 0;
    lastMeanR = 0;
  }

  public int setAttack(int attack) {
    attackMS = (float) (0.5 + (Math.exp(2.0 * (float) attack / 2147483648.0) - 1.0) * 10.0);
    a_ = (-1000.0f / kSampleRate) / attackMS;
    attackKnobPos = attack;
    return (int) attackMS;
  }

  public int setRelease(int release) {
    releaseMS = (float) (50.0 + (Math.exp(2.0 * (float) release / 2147483648.0) - 1.0) * 50.0);
    r_ = (-1000.0f / kSampleRate) / releaseMS;
    releaseKnobPos = release;
    return (int) releaseMS;
  }

  public void setup(int a, int r) {
    setAttack(a);
    setRelease(r);
  }

  public StereoFloatSample calcApproxRMS(StereoSample[] buffer) {
    long l = 0;
    long r = 0;
    for (StereoSample sample : buffer) {
      l += Math.abs(sample.l);
      r += Math.abs(sample.r);
    }

    float ns = (float) buffer.length;
    meanL = l / ns;
    meanR = r / ns;

    meanL = (meanL * ns + lastMeanL) / (1.0f + ns);
    meanR = (meanR * ns + lastMeanR) / (1.0f + ns);

    lastMeanL = runEnvelope(lastMeanL, meanL, ns);
    lastMeanR = runEnvelope(lastMeanR, meanR, ns);

    return new StereoFloatSample(
        (float) Math.log(lastMeanL + 1e-24f), (float) Math.log(lastMeanR + 1e-24f));
  }

  private float runEnvelope(float current, float desired, float numSamples) {
    if (desired > current) {
      return (float) (desired + Math.exp(a_ * numSamples) * (current - desired));
    } else {
      return (float) (desired + Math.exp(r_ * numSamples) * (current - desired));
    }
  }
}
