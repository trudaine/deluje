package org.chuck.deluge.firmware.dsp.filter;

import static org.chuck.deluge.firmware.util.Q31.*;

public class FilterSet {
  public final LpLadderFilter lpLadder = new LpLadderFilter();
  public final SVFilter lpSVF = new SVFilter();
  public final HpLadderFilter hpLadder = new HpLadderFilter();
  public final SVFilter hpSVF = new SVFilter();

  private FirmwareFilter.FilterMode lpfMode = FirmwareFilter.FilterMode.OFF;
  private FirmwareFilter.FilterMode hpfMode = FirmwareFilter.FilterMode.OFF;
  private FilterRoute routing = FilterRoute.HIGH_TO_LOW;

  private boolean LPFOn;
  private boolean HPFOn;

  public void reset() {
    lpLadder.resetFilter();
    lpSVF.resetFilter();
    hpLadder.resetFilter();
    hpSVF.resetFilter();
  }

  public int setConfig(
      int lpfFrequency,
      int lpfResonance,
      FirmwareFilter.FilterMode lpfmode,
      int lpfMorph,
      int hpfFrequency,
      int hpfResonance,
      FirmwareFilter.FilterMode hpfmode,
      int hpfMorph,
      int filterGain,
      FilterRoute routing) {

    this.LPFOn = lpfmode != FirmwareFilter.FilterMode.OFF;
    this.HPFOn = hpfmode != FirmwareFilter.FilterMode.OFF;
    this.lpfMode = lpfmode;
    this.hpfMode = hpfmode;
    this.routing = routing;

    if (LPFOn) {
      if (lpfMode == FirmwareFilter.FilterMode.SVF_BAND
          || lpfMode == FirmwareFilter.FilterMode.SVF_NOTCH) {
        filterGain = lpSVF.configure(lpfFrequency, lpfResonance, lpfMode, lpfMorph, filterGain);
      } else {
        filterGain = lpLadder.configure(lpfFrequency, lpfResonance, lpfMode, lpfMorph, filterGain);
      }
    }

    filterGain = multiply_32x32_rshift32(filterGain, 1720000000) << 1;

    if (HPFOn) {
      if (hpfMode == FirmwareFilter.FilterMode.HPLADDER) {
        filterGain = hpLadder.configure(hpfFrequency, hpfResonance, hpfMode, hpfMorph, filterGain);
      } else {
        // Invert morph for HPF SVF to match firmware behavior
        int invertedMorph = ((1 << 29) - 1) - hpfMorph;
        filterGain =
            hpSVF.configure(hpfFrequency, hpfResonance, hpfMode, invertedMorph, filterGain);
      }
    }

    return filterGain;
  }

  public void render(int[] buffer, int offset, int length, int sampleIncrement) {
    if (!LPFOn && !HPFOn) return;

    switch (routing) {
      case HIGH_TO_LOW:
        renderHPF(buffer, offset, length, sampleIncrement);
        renderLPF(buffer, offset, length, sampleIncrement);
        break;
      case LOW_TO_HIGH:
        renderLPF(buffer, offset, length, sampleIncrement);
        renderHPF(buffer, offset, length, sampleIncrement);
        break;
      case PARALLEL:
        int[] temp = new int[length];
        System.arraycopy(buffer, offset, temp, 0, length);
        renderHPF(temp, 0, length, sampleIncrement);
        renderLPF(buffer, offset, length, sampleIncrement);
        for (int i = 0; i < length; i++) {
          buffer[offset + i] += temp[i];
        }
        break;
    }
  }

  private void renderLPF(int[] buffer, int offset, int length, int sampleIncrement) {
    if (!LPFOn) return;
    if (lpfMode == FirmwareFilter.FilterMode.SVF_BAND
        || lpfMode == FirmwareFilter.FilterMode.SVF_NOTCH) {
      lpSVF.doFilter(buffer, offset, length, sampleIncrement);
    } else {
      lpLadder.doFilter(buffer, offset, length, sampleIncrement);
    }
  }

  private void renderHPF(int[] buffer, int offset, int length, int sampleIncrement) {
    if (!HPFOn) return;
    if (hpfMode == FirmwareFilter.FilterMode.HPLADDER) {
      hpLadder.doFilter(buffer, offset, length, sampleIncrement);
    } else {
      hpSVF.doFilter(buffer, offset, length, sampleIncrement);
    }
  }
}
