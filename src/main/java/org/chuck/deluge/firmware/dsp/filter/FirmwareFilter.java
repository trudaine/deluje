package org.chuck.deluge.firmware.dsp.filter;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.util.FirmwareUtils;

public abstract class FirmwareFilter {
  public static final int ONE_Q16 = 134217728;

  protected int fc;
  protected float dryFade = 1;
  protected int wetLevel = ONE;
  protected int tannedFrequency;
  protected int divideBy1PlusTannedFrequency;

  public enum FilterMode {
    TRANSISTOR_12DB,
    TRANSISTOR_24DB,
    TRANSISTOR_24DB_DRIVE,
    SVF_BAND,
    SVF_NOTCH,
    HPLADDER,
    OFF
  }

  public int configure(
      int frequency, int resonance, FilterMode lpfMode, int lpfMorph, int filterGain) {
    // lpfmorph comes in q28 but we want q31
    return setConfig(
        frequency, resonance, lpfMode, FirmwareUtils.lshiftAndSaturate(lpfMorph, 2), filterGain);
  }

  public abstract int setConfig(
      int frequency, int resonance, FilterMode lpfMode, int lpfMorph, int filterGain);

  public abstract void doFilter(int[] startSample, int offset, int length, int sampleIncrement);

  public abstract void doFilterStereo(int[] startSample, int offset, int length);

  public abstract void resetFilter();

  public void reset(boolean fade) {
    resetFilter();
    if (fade) {
      dryFade = 1;
      wetLevel = 0;
    }
  }

  protected void curveFrequency(int frequency) {
    // Between 0 and 8. 1 represented by 268435456 (q28)
    // input frequency is q31
    tannedFrequency = FirmwareUtils.instantTan(FirmwareUtils.lshiftAndSaturate(frequency, 5));

    // this is 1q31*1q16/(1q16+tan(f)/2)
    // tan(f) is q17 (wait, Deluge says tannedFrequency is q17? Let's check)
    // In Deluge: tannedFrequency = instantTan(lshiftAndSaturate<5>(frequency));
    // instantTan returns q17?
    // Let's re-verify tannedFrequency range.

    // divideBy1PlusTannedFrequency = (q31_t)(288230376151711744.0 / (double)(ONE_Q16 +
    // (tannedFrequency >> 1)));
    // 288230376151711744.0 is 2^58.

    double denom = (double) ONE_Q16 + (tannedFrequency >> 1);
    divideBy1PlusTannedFrequency = (int) (288230376151711744.0 / denom);
    fc = (int) (((long) tannedFrequency * divideBy1PlusTannedFrequency) >> 28);
  }

  protected void updateBlend() {
    dryFade = dryFade * 0.99f;
    wetLevel = (int) (ONE * (1.0f - dryFade));
  }
}
