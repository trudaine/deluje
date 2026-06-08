package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code dsp/compressor/rms_feedback.cpp} (167 lines)
 * + {@code rms_feedback.h} (234 lines).
 *
 * <p>RMS-based feedback compressor with attack/release envelope, sidechain HPF,
 * wet/dry blend, and optional tanH saturation on output. Uses float math
 * (log/exp/sqrt) — faithful to the C which also uses floats for these.</p>
 */
public class Compressor {

  /** C: rms_feedback.h:28 */
  public static final int ONE_Q31 = 2147483647;
  static final float ONE_Q31f = (float)ONE_Q31;
  static final int ONE_Q15 = ONE_Q31 >> 16;
  static final int K_SAMPLE_RATE = 44100;
  static final int SATURATION_AMOUNT = 3; // C: rms_feedback.cpp:58

  // C: rms_feedback.h:180-233 — private state
  float a_ = (-1000.0f / K_SAMPLE_RATE);
  float r_ = (-1000.0f / K_SAMPLE_RATE);
  float fraction = 0.5f;
  float er;
  float threshdb = 17;
  float threshold = 1;
  int hpfA_ = ONE_Q15;
  float state;
  int currentVolumeL;
  int currentVolumeR;
  float rms;
  float mean;
  int[] lastSaturationTanHWorkingValue = {0, 0};
  boolean onLastTime;

  // Sidechain HPF filters (C: rms_feedback.h:214-215)
  final BasicFilterComponent hpfL = new BasicFilterComponent();
  final BasicFilterComponent hpfR = new BasicFilterComponent();

  // Display params
  float attackMS;
  float releaseMS;
  float ratio = 2;
  float fc_hz;
  float baseGain_ = 1.35f; // C: rms_feedback.cpp:30

  // Knob positions
  int thresholdKnobPos;
  int ratioKnobPos;
  int attackKnobPos;
  int releaseKnobPos;
  int sideChainKnobPos;
  int dry;
  int wet;

  /** C: rms_feedback.cpp:58 — display */
  public int gainReduction;

  // ── Constructor (rms_feedback.cpp:22-31) ──

  /** C: rms_feedback.cpp:22-31 */
  public Compressor() {
    setAttack(5 << 24);
    setRelease(5 << 24);
    setThreshold(0);
    setRatio(64 << 24);
    setSidechain(0);
    setBlend(ONE_Q31);
    baseGain_ = 1.35f; // C:30
  }

  // ── setup (rms_feedback.h:31-39) ──

  /** C: rms_feedback.h:31-39 */
  public void setup(int a, int r, int t, int rat, int fc, int blend, float baseGain) {
    setAttack(a);
    setRelease(r);
    setThreshold(t);
    setRatio(rat);
    setSidechain(fc);
    setBlend(blend);
    baseGain_ = baseGain;
  }

  // ── setAttack (rms_feedback.h:80-84) ──

  /** C: rms_feedback.h:80-84 — exp map 0..2^31 to 0.5..70ms */
  int setAttack(int attack) {
    attackMS = (float)(0.5 + (Math.exp(2.0 * (double)attack / ONE_Q31f) - 1.0) * 10.0);
    a_ = (float)((-1000.0 / K_SAMPLE_RATE) / attackMS);
    attackKnobPos = attack;
    return (int)attackMS;
  }

  // ── setRelease (rms_feedback.h:95-101) ──

  /** C: rms_feedback.h:95-101 — exp map 0..2^31 to 50..400ms */
  int setRelease(int release) {
    releaseMS = (float)(50.0 + (Math.exp(2.0 * (double)release / ONE_Q31f) - 1.0) * 50.0);
    r_ = (float)((-1000.0 / K_SAMPLE_RATE) / releaseMS);
    releaseKnobPos = release;
    return (int)releaseMS;
  }

  // ── setThreshold (rms_feedback.h:108-111) ──

  /** C: rms_feedback.h:108-111 — 0→0.2, 2^31→1.0 */
  void setThreshold(int t) {
    thresholdKnobPos = t;
    threshold = (float)(1.0 - 0.8 * ((double)t / ONE_Q31f));
  }

  // ── setRatio (rms_feedback.h:122-127) ──

  /** C: rms_feedback.h:122-127 — inverse mapping, 0→2:1, max→256:1 */
  int setRatio(int rat) {
    ratioKnobPos = rat;
    fraction = (float)(0.5 + ((double)rat / ONE_Q31f) / 2.0);
    ratio = (float)(1.0 / (1.0 - fraction));
    return (int)ratio;
  }

  // ── setSidechain (rms_feedback.h:138-147) ──

  /** C: rms_feedback.h:138-147 — exp map 0..2^31 to 0..100Hz */
  int setSidechain(int f) {
    sideChainKnobPos = f;
    fc_hz = (float)((Math.exp(1.5 * (double)f / ONE_Q31f) - 1.0) * 30.0);
    float fc = fc_hz / (float)K_SAMPLE_RATE;
    float wc = fc / (1.0f + fc);
    hpfA_ = (int)(wc * ONE_Q31);
    return (int)fc_hz;
  }

  // ── setBlend (rms_feedback.h:156-161) ──

  /** C: rms_feedback.h:156-161 */
  int setBlend(int blend) {
    dry = ONE_Q31 - blend;
    wet = blend;
    return (wet > (127 << 24)) ? 100 : 100 * (wet >> 24) >> 7;
  }

  // ── reset (rms_feedback.h:42-47) ──

  /** C: rms_feedback.h:42-47 */
  public void reset() {
    state = 0;
    er = 0;
    mean = 0;
    onLastTime = false;
  }

  // ── updateER (rms_feedback.cpp:34-50) ──

  /** C: rms_feedback.cpp:34-50 */
  void updateER(float numSamples, int finalVolume) {
    float songVolumedB = (float)Math.log(finalVolume + 1e-10); // C:41
    threshdb = songVolumedB * threshold; // C:43
    float lastER = er; // C:46
    er = Math.max((songVolumedB - threshdb - 1) * fraction, 0); // C:47
    er = runEnvelope(lastER, er, numSamples); // C:49
  }

  // ── renderVolNeutral (rms_feedback.cpp:52-57) ──

  /** C: rms_feedback.cpp:52-57 */
  public void renderVolNeutral(int[][] buffer, int finalVolume) {
    int numSamples = buffer.length;
    float[][] fb = new float[numSamples][2];
    // Convert int[][2] to the format expected by render
    render(fb, numSamples, 1 << 27, 1 << 27, finalVolume >> 3);
    // Write back
    for (int i = 0; i < numSamples; i++) {
      buffer[i][0] = (int)fb[i][0];
      buffer[i][1] = (int)fb[i][1];
    }
  }

  // ── render (rms_feedback.cpp:59-128) ──

  /**
   * C: rms_feedback.cpp:59-128. Renders the compressor in-place.
   * @param buffer stereo samples (modified in-place)
   * @param volAdjustL left gain as 4.27 signed fixed
   * @param volAdjustR right gain
   * @param finalVolume peak-to-peak volume scale as 3.29 signed fixed
   */
  public void render(float[][] buffer, int numSamples, int volAdjustL, int volAdjustR, int finalVolume) {
    // C:62-64 — dry buffer for wet/dry blend
    float[][] dryBuffer = null;
    if (wet != ONE_Q31) {
      dryBuffer = new float[numSamples][2];
      for (int i = 0; i < numSamples; i++) {
        dryBuffer[i][0] = buffer[i][0];
        dryBuffer[i][1] = buffer[i][1];
      }
    }

    // C:66-73 — init saturation working values on first call
    if (!onLastTime) {
      lastSaturationTanHWorkingValue[0] =
          (Functions.lshiftAndSaturateUnknown((int)buffer[0][0], SATURATION_AMOUNT) & 0xFFFFFFFF) + 0x80000000;
      lastSaturationTanHWorkingValue[1] =
          (Functions.lshiftAndSaturateUnknown((int)buffer[0][1], SATURATION_AMOUNT) & 0xFFFFFFFF) + 0x80000000;
      onLastTime = true;
    }

    // C:75
    updateER(numSamples, finalVolume);

    // C:77-81
    float over = Math.max(0, (rms - threshdb));
    state = runEnvelope(state, over, numSamples);
    float reduction = -state * fraction;

    // C:84-88
    float dbGain = baseGain_ + er + reduction;
    float gain = (float)Math.exp(dbGain);
    gain = Math.min(gain, 31);

    // C:92-97
    float finalVolumeL = gain * (float)(volAdjustL >> 9);
    float finalVolumeR = gain * (float)(volAdjustR >> 9);

    int amplitudeIncrementL = (int)((finalVolumeL - (currentVolumeL >> 8)) / (float)numSamples) << 8;
    int amplitudeIncrementR = (int)((finalVolumeR - (currentVolumeR >> 8)) / (float)numSamples) << 8;

    // C:99-121 — process samples
    for (int i = 0; i < numSamples; i++) {
      currentVolumeL += amplitudeIncrementL; // C:101
      currentVolumeR += amplitudeIncrementR; // C:102

      int sampleL = (int)buffer[i][0];
      int sampleR = (int)buffer[i][1];

      // C:105-106 — apply gain + saturation
      sampleL = Functions.multiply_32x32_rshift32(sampleL, currentVolumeL) << 4;
      int workingL = Functions.lshiftAndSaturateUnknown(sampleL, SATURATION_AMOUNT) + 0x80000000;
      sampleL = Functions.getTanHAntialiased(sampleL, workingL, SATURATION_AMOUNT);

      sampleR = Functions.multiply_32x32_rshift32(sampleR, currentVolumeR) << 4;
      int workingR = Functions.lshiftAndSaturateUnknown(sampleR, SATURATION_AMOUNT) + 0x80000000;
      sampleR = Functions.getTanHAntialiased(sampleR, workingR, SATURATION_AMOUNT);

      // C:111-119 — wet/dry blend
      if (wet != ONE_Q31) {
        sampleL = Functions.multiply_32x32_rshift32(sampleL, wet);
        sampleL = Functions.multiply_accumulate_32x32_rshift32_rounded(
            sampleL, (int)dryBuffer[i][0], dry) << 1;
        sampleR = Functions.multiply_32x32_rshift32(sampleR, wet);
        sampleR = Functions.multiply_accumulate_32x32_rshift32_rounded(
            sampleR, (int)dryBuffer[i][1], dry) << 1;
      }

      buffer[i][0] = sampleL;
      buffer[i][1] = sampleR;
    }

    // C:125 — gain reduction for display
    gainReduction = (int)Math.max(0, Math.min(127, -reduction * 4 * 4));

    // C:127 — feedback RMS calculation
    rms = calcRMS(buffer, numSamples);
  }

  // ── runEnvelope (rms_feedback.cpp:130-139) ──

  /** C: rms_feedback.cpp:130-139 — IIR envelope with attack/release constants. */
  float runEnvelope(float current, float desired, float numSamples) {
    float s;
    if (desired > current) {
      // C:133 — attack: desired + exp(a*samples) * (current - desired)
      s = (float)(desired + Math.exp(a_ * numSamples) * (current - desired));
    } else {
      // C:136 — release
      s = (float)(desired + Math.exp(r_ * numSamples) * (current - desired));
    }
    return s;
  }

  // ── calcRMS (rms_feedback.cpp:143-167) ──

  /** C: rms_feedback.cpp:143-167 — RMS with DC-blocking HPF. */
  float calcRMS(float[][] buffer, int numSamples) {
    int sum = 0; // C:144
    float lastMean = mean; // C:146

    for (int i = 0; i < numSamples; i++) {
      int l = (int)buffer[i][0] - hpfL.doFilter((int)buffer[i][0], hpfA_); // C:149
      int r = (int)buffer[i][1] - hpfR.doFilter((int)buffer[i][1], hpfA_); // C:150
      int s = Math.max(Math.abs(l), Math.abs(r)); // C:151
      sum += Functions.multiply_32x32_rshift32(s, s); // C:152
    }

    float ns = (float)(numSamples * 2); // C:155
    mean = (float)((double)sum / ONE_Q31f) / ns; // C:156
    mean = (float)((mean * ns + lastMean) / (1.0 + ns)); // C:161
    float rmsVal = (float)(ONE_Q31 * Math.sqrt(mean)); // C:162
    float logmean = (float)Math.log(Math.max(rmsVal, 1.0f)); // C:164

    return logmean;
  }
}
