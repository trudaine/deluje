package org.chuck.deluge.firmware.dsp.filter;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;

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

  public void renderStereoInterleaved(StereoSample[] buffer, int numSamples) {
      if (!LPFOn && !HPFOn) return;

      int[] l = new int[numSamples];
      int[] r = new int[numSamples];
      for (int i = 0; i < numSamples; i++) {
          l[i] = buffer[i].l;
          r[i] = buffer[i].r;
      }

      switch (routing) {
          case HIGH_TO_LOW:
              renderHPFStereo(l, 0, numSamples);
              renderHPFStereo(r, 0, numSamples);
              renderLPFStereo(l, 0, numSamples);
              renderLPFStereo(r, 0, numSamples);
              break;
          case LOW_TO_HIGH:
              renderLPFStereo(l, 0, numSamples);
              renderLPFStereo(r, 0, numSamples);
              renderHPFStereo(l, 0, numSamples);
              renderHPFStereo(r, 0, numSamples);
              break;
          case PARALLEL:
              int[] l_temp = new int[numSamples];
              int[] r_temp = new int[numSamples];
              System.arraycopy(l, 0, l_temp, 0, numSamples);
              System.arraycopy(r, 0, r_temp, 0, numSamples);
              
              renderHPFStereo(l_temp, 0, numSamples);
              renderHPFStereo(r_temp, 0, numSamples);
              renderLPFStereo(l, 0, numSamples);
              renderLPFStereo(r, 0, numSamples);
              
              for (int i = 0; i < numSamples; i++) {
                  l[i] = addSaturate(l[i], l_temp[i]);
                  r[i] = addSaturate(r[i], r_temp[i]);
              }
              break;
      }

      for (int i = 0; i < numSamples; i++) {
          buffer[i].l = l[i];
          buffer[i].r = r[i];
      }
  }

  public void renderStereo(int[] buffer, int offset, int length) {
    if (!LPFOn && !HPFOn) return;

    switch (routing) {
      case HIGH_TO_LOW:
        renderHPFStereo(buffer, offset, length);
        renderLPFStereo(buffer, offset, length);
        break;
      case LOW_TO_HIGH:
        renderLPFStereo(buffer, offset, length);
        renderHPFStereo(buffer, offset, length);
        break;
      case PARALLEL:
        int[] temp = new int[length];
        System.arraycopy(buffer, offset, temp, 0, length);
        renderHPFStereo(temp, 0, length);
        renderLPFStereo(buffer, offset, length);
        for (int i = 0; i < length; i++) {
          buffer[offset + i] = addSaturate(buffer[offset + i], temp[i]);
        }
        break;
    }
  }

  private void renderLPFStereo(int[] buffer, int offset, int length) {
    if (!LPFOn) return;
    if (lpfMode == FirmwareFilter.FilterMode.SVF_BAND
        || lpfMode == FirmwareFilter.FilterMode.SVF_NOTCH) {
      lpSVF.doFilterStereo(buffer, offset, length);
    } else {
      lpLadder.doFilterStereo(buffer, offset, length);
    }
  }

  private void renderHPFStereo(int[] buffer, int offset, int length) {
    if (!HPFOn) return;
    if (hpfMode == FirmwareFilter.FilterMode.HPLADDER) {
      hpLadder.doFilterStereo(buffer, offset, length);
    } else {
      hpSVF.doFilterStereo(buffer, offset, length);
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
