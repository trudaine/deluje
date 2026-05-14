package org.chuck.deluge.firmware.dsp.compressor;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.filter.BasicFilterComponent;
import org.chuck.deluge.firmware.util.FirmwareUtils;

public class RMSFeedbackCompressor {
  private static final int kSampleRate = 44100;
  private static final int saturationAmount = 3;

  private float a_;
  private float r_;
  private float fraction;
  private float er;
  private float threshdb;
  private float threshold;
  private int hpfA_;

  private float state;
  private int currentVolumeL;
  private int currentVolumeR;
  private float rms;
  private float mean;
  private int[] lastSaturationTanHWorkingValue = new int[2];
  private boolean onLastTime;

  private final BasicFilterComponent hpfL = new BasicFilterComponent();
  private final BasicFilterComponent hpfR = new BasicFilterComponent();

  private float attackMS;
  private float releaseMS;
  private float ratio;
  private float fc_hz;
  private float baseGain_;

  private int thresholdKnobPos;
  private int ratioKnobPos;
  private int attackKnobPos;
  private int releaseKnobPos;
  private int sideChainKnobPos;
  private int dry;
  private int wet;

  public int gainReduction = 0;

  public RMSFeedbackCompressor() {
    setAttack(5 << 24);
    setRelease(5 << 24);
    setThreshold(0);
    setRatio(64 << 24);
    setSidechain(0);
    setBlend(ONE);
    baseGain_ = 1.35f;
  }

  public void setup(int a, int r, int t, int rat, int fc, int blend, float baseGain) {
    setAttack(a);
    setRelease(r);
    setThreshold(t);
    setRatio(rat);
    setSidechain(fc);
    setBlend(blend);
    baseGain_ = baseGain;
  }

  public void reset() {
    state = 0;
    er = 0;
    mean = 0;
    onLastTime = false;
  }

  public int setAttack(int attack) {
    attackMS = (float) (0.5 + (Math.exp(2 * (float) attack / (float) ONE) - 1) * 10);
    a_ = (-1000.0f / kSampleRate) / attackMS;
    attackKnobPos = attack;
    return (int) attackMS;
  }

  public int setRelease(int release) {
    releaseMS = (float) (50 + (Math.exp(2 * (float) release / (float) ONE) - 1) * 50);
    r_ = (-1000.0f / kSampleRate) / releaseMS;
    releaseKnobPos = release;
    return (int) releaseMS;
  }

  public void setThreshold(int t) {
    thresholdKnobPos = t;
    threshold = 1 - 0.8f * ((float) thresholdKnobPos / (float) ONE);
  }

  public int setRatio(int rat) {
    ratioKnobPos = rat;
    fraction = 0.5f + ((float) ratioKnobPos / (float) ONE) / 2;
    ratio = 1 / (1 - fraction);
    return (int) ratio;
  }

  public int setSidechain(int f) {
    sideChainKnobPos = f;
    fc_hz = (float) ((Math.exp(1.5 * (float) f / (float) ONE) - 1) * 30);
    float fc = fc_hz / (float) kSampleRate;
    float wc = fc / (1 + fc);
    hpfA_ = (int) (wc * ONE);
    return (int) fc_hz;
  }

  public int setBlend(int blend) {
    dry = ONE - blend;
    wet = blend;
    return getBlendForDisplay();
  }

  public int getBlendForDisplay() {
    return wet > (127 << 24) ? 100 : 100 * (wet >> 24) >> 7;
  }

  public void setBaseGain(float baseGain) {
    baseGain_ = baseGain;
  }

  private void updateER(float numSamples, int finalVolume) {
    float songVolumedB = (float) Math.log((double) finalVolume + 1e-10);

    threshdb = songVolumedB * threshold;
    float lastER = er;
    er = Math.max((songVolumedB - threshdb - 1) * fraction, 0);
    er = runEnvelope(lastER, er, numSamples);
  }

  public void renderVolNeutral(StereoSample[] buffer, int finalVolume) {
    render(buffer, 1 << 27, 1 << 27, finalVolume >> 3);
  }

  public void render(StereoSample[] buffer, int volAdjustL, int volAdjustR, int finalVolume) {
    StereoSample[] dryBuffer = null;
    if (wet != ONE) {
      dryBuffer = new StereoSample[buffer.length];
      for (int i = 0; i < buffer.length; i++) {
        dryBuffer[i] = new StereoSample(buffer[i].l, buffer[i].r);
      }
    }

    if (!onLastTime) {
      lastSaturationTanHWorkingValue[0] =
          FirmwareUtils.lshiftAndSaturateUnknown(buffer[0].l, saturationAmount) + 2147483647;
      lastSaturationTanHWorkingValue[1] =
          FirmwareUtils.lshiftAndSaturateUnknown(buffer[0].r, saturationAmount) + 2147483647;
      onLastTime = true;
    }

    updateER(buffer.length, finalVolume);

    float over = Math.max(0, rms - threshdb);
    state = runEnvelope(state, over, buffer.length);
    float reduction = -state * fraction;

    float dbGain = baseGain_ + er + reduction;
    float gain = (float) Math.exp(dbGain);
    gain = Math.min(gain, 31);

    float finalVolumeL = gain * (float) (volAdjustL >> 9);
    float finalVolumeR = gain * (float) (volAdjustR >> 9);

    int amplitudeIncrementL =
        ((int) ((finalVolumeL - (currentVolumeL >> 8)) / (float) buffer.length)) << 8;
    int amplitudeIncrementR =
        ((int) ((finalVolumeR - (currentVolumeR >> 8)) / (float) buffer.length)) << 8;

    for (int i = 0; i < buffer.length; i++) {
      StereoSample sample = buffer[i];

      currentVolumeL += amplitudeIncrementL;
      currentVolumeR += amplitudeIncrementR;

      sample.l = multiply_32x32_rshift32(sample.l, currentVolumeL) << 4;
      int[] lastWorkL = new int[] {lastSaturationTanHWorkingValue[0]};
      sample.l = FirmwareUtils.getTanHAntialiased(sample.l, lastWorkL, saturationAmount);
      lastSaturationTanHWorkingValue[0] = lastWorkL[0];

      sample.r = multiply_32x32_rshift32(sample.r, currentVolumeR) << 4;
      int[] lastWorkR = new int[] {lastSaturationTanHWorkingValue[1]};
      sample.r = FirmwareUtils.getTanHAntialiased(sample.r, lastWorkR, saturationAmount);
      lastSaturationTanHWorkingValue[1] = lastWorkR[0];

      if (wet != ONE) {
        sample.l = multiply_32x32_rshift32(sample.l, wet);
        sample.l = multiply_accumulate_32x32_rshift32_rounded(sample.l, dryBuffer[i].l, dry);
        sample.l <<= 1;

        sample.r = multiply_32x32_rshift32(sample.r, wet);
        sample.r = multiply_accumulate_32x32_rshift32_rounded(sample.r, dryBuffer[i].r, dry);
        sample.r <<= 1;
      }
    }

    gainReduction = Math.max(0, Math.min(127, (int) (-(reduction) * 16)));
    rms = calcRMS(buffer);
  }

  public float runEnvelope(float current, float desired, float numSamples) {
    float s = 0;
    if (desired > current) {
      s = (float) (desired + Math.exp(a_ * numSamples) * (current - desired));
    } else {
      s = (float) (desired + Math.exp(r_ * numSamples) * (current - desired));
    }
    return s;
  }

  public float calcRMS(StereoSample[] buffer) {
    int sum = 0;
    float lastMean = mean;

    for (StereoSample sample : buffer) {
      int l = sample.l - hpfL.doFilter(sample.l, hpfA_);
      int r = sample.r - hpfR.doFilter(sample.r, hpfA_);
      int s = Math.max(Math.abs(l), Math.abs(r));
      sum += multiply_32x32_rshift32(s, s);
    }

    float ns = buffer.length * 2;
    mean = ((float) sum / (float) ONE) / ns;
    mean = (mean * ns + lastMean) / (1 + ns);
    float localRms = (float) ONE * (float) Math.sqrt(mean);

    return (float) Math.log(Math.max(localRms, 1.0f));
  }
}
