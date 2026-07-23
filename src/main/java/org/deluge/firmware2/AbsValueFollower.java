package org.deluge.firmware2;

/**
 * Faithful port of {@code dsp/envelope_follower/absolute_value.cpp} (66 lines) + header (81 lines).
 * Absolute-value envelope follower with IIR attack/release smoothing. Used for level detection.
 */
public class AbsValueFollower {

  static final int ONE_Q31 = 2147483647;
  static final float ONE_Q31f = (float) ONE_Q31;
  static final int K_SAMPLE_RATE = 44100;

  // C: absolute_value.h:63-80 — state
  float a_ = (-1000.0f / K_SAMPLE_RATE) / 1.0f; // attackMS default 1
  float r_ = (-1000.0f / K_SAMPLE_RATE) / 10.0f; // releaseMS default 10
  float meanL, lastMeanL, meanR, lastMeanR;
  int attackKnobPos, releaseKnobPos;
  float attackMS = 1, releaseMS = 10;

  /** C: absolute_value.h:30-35 — reset */
  public void reset() {
    meanL = 0;
    lastMeanL = 0;
    meanR = 0;
    lastMeanR = 0;
  }

  /** C: absolute_value.h:40-46 — setAttack (exp map) */
  public int setAttack(int attack) {
    attackMS = 0.5f + (float) (Math.exp(2.0 * attack / ONE_Q31f) - 1.0) * 10.0f;
    a_ = (float) ((-1000.0 / K_SAMPLE_RATE) / attackMS);
    attackKnobPos = attack;
    return (int) attackMS;
  }

  /** C: absolute_value.h:50-56 — setRelease */
  public int setRelease(int release) {
    releaseMS = 50.0f + (float) (Math.exp(2.0 * release / ONE_Q31f) - 1.0) * 50.0f;
    r_ = (float) ((-1000.0 / K_SAMPLE_RATE) / releaseMS);
    releaseKnobPos = release;
    return (int) releaseMS;
  }

  /** C: absolute_value.cpp:21-30 — IIR envelope */
  float runEnvelope(float current, float desired, float numSamples) {
    if (desired > current) {
      return (float) (desired + Math.exp(a_ * numSamples) * (current - desired));
    } else {
      return (float) (desired + Math.exp(r_ * numSamples) * (current - desired));
    }
  }

  /** C: absolute_value.cpp:63-67 — setup */
  public void setup(int a, int r) {
    setAttack(a);
    setRelease(r);
  }

  private final float[] rmsResult = new float[2];

  /**
   * C: absolute_value.cpp:34-61 — calcApproxRMS. Returns log-mean of absolute values for L/R
   * channels.
   *
   * <p>The returned array is a single instance reused across calls (no per-call allocation on the
   * audio thread). Callers must read the two values out immediately; do not retain the reference or
   * share it across threads.
   */
  public float[] calcApproxRMS(int[][] buffer) {
    long l = 0, r = 0;
    for (int[] sample : buffer) {
      l += Math.abs(sample[0]);
      r += Math.abs(sample[1]);
    }
    float ns = buffer.length;
    meanL = (float) l / ns;
    meanR = (float) r / ns;
    // C:51-52 — weighted average with previous
    meanL = (meanL * ns + lastMeanL) / (1.0f + ns);
    meanR = (meanR * ns + lastMeanR) / (1.0f + ns);
    lastMeanL = runEnvelope(lastMeanL, meanL, ns);
    lastMeanR = runEnvelope(lastMeanR, meanR, ns);
    rmsResult[0] = (float) Math.log(lastMeanL + 1e-24f);
    rmsResult[1] = (float) Math.log(lastMeanR + 1e-24f);
    return rmsResult;
  }
}
