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

  /** C: absolute_value.h:38 */
  public int getAttack() {
    return attackKnobPos;
  }

  /** C: absolute_value.h:39 */
  public int getAttackMS() {
    return (int) attackMS;
  }

  /**
   * C: absolute_value.h:40-46 — setAttack (exp map).
   *
   * <p>Note: C calls newlib/glibc {@code expf(float)} (not guaranteed correctly rounded), whereas
   * Java {@code (float) Math.exp(double)} can differ by 1 ULP on rare inputs; that difference is
   * negligible for an envelope time constant.
   */
  public int setAttack(int attack) {
    float expTerm = (float) Math.exp(2.0f * (float) attack / ONE_Q31f);
    attackMS = (float) (0.5 + (expTerm - 1.0f) * 10.0f);
    a_ = (float) ((-1000.0 / K_SAMPLE_RATE) / attackMS);
    attackKnobPos = attack;
    return (int) attackMS;
  }

  /** C: absolute_value.h:48 */
  public int getRelease() {
    return releaseKnobPos;
  }

  /** C: absolute_value.h:49 */
  public int getReleaseMS() {
    return (int) releaseMS;
  }

  /** C: absolute_value.h:50-56 — setRelease */
  public int setRelease(int release) {
    float expTerm = (float) Math.exp(2.0f * (float) release / ONE_Q31f);
    releaseMS = 50.0f + (expTerm - 1.0f) * 50.0f;
    r_ = (float) ((-1000.0 / K_SAMPLE_RATE) / releaseMS);
    releaseKnobPos = release;
    return (int) releaseMS;
  }

  /**
   * C: absolute_value.cpp:25-27 — {@code absForLevel}. {@code |x|}, but defined for {@code INT_MIN}
   * (which gives {@code INT_MAX}); negatives come out 1 low, which is nothing next to the level
   * this measures. Java's {@code Math.abs} returns {@code INT_MIN} for {@code INT_MIN}, so the C's
   * one-instruction form is ported rather than replaced.
   */
  static int absForLevel(int x) {
    return x ^ (x >> 31);
  }

  /** C: absolute_value.cpp:21-30 — IIR envelope */
  float runEnvelope(float current, float desired, float numSamples) {
    if (desired > current) {
      return desired + (float) Math.exp(a_ * numSamples) * (current - desired);
    } else {
      return desired + (float) Math.exp(r_ * numSamples) * (current - desired);
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
    // C absolute_value.cpp:46-59 — q31_t accumulators, each sample shifted down 8 first so a block
    // of up to 256 samples cannot overflow int32 (the C's "do not feed block over length 256").
    // Java could accumulate in a long and skip the shift, but the per-sample >> 8 truncates, so
    // that would read slightly high; keep the C's int arithmetic.
    int l = 0, r = 0;
    for (int[] sample : buffer) {
      l += absForLevel(sample[0]) >> 8;
      r += absForLevel(sample[1]) >> 8;
    }
    float ns = buffer.length;
    meanL = 256.f * l / ns;
    meanR = 256.f * r / ns;
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
