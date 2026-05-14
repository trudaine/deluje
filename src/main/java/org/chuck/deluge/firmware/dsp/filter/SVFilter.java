package org.chuck.deluge.firmware.dsp.filter;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.util.FirmwareUtils;

public class SVFilter extends FirmwareFilter {

  private static class SVFState {
    int low;
    int band;
  }

  private final SVFState l = new SVFState();
  private final SVFState r = new SVFState();

  private int q;
  private int in;
  private int c_low;
  private int c_band;
  private int c_high;
  private boolean band_mode;

  @Override
  public int setConfig(int freq, int res, FilterMode lpfMode, int lpfMorph, int filterGain) {
    curveFrequency(freq);

    // multiply by 1.25 to loosely correct for equivalency to ladders
    int POINT_25 = ONE / 4;
    fc = fc + multiply_32x32_rshift32(fc, POINT_25);

    band_mode = (lpfMode == FilterMode.SVF_BAND);

    // raw resonance is 0 - 536870896 (2^28ish)
    // Multiply by 4 to bring it to the q31 0-1 range
    q = (ONE - 4 * res);
    in = (q >>> 1) + (ONE >>> 1);

    // squared q is a better match for the ladders
    q = multiply_32x32_rshift32_rounded(q, q) << 1;

    int ONE_HALF = ONE >>> 1;
    if (band_mode) {
      if (lpfMorph > ONE_HALF) {
        lpfMorph = 2 * (lpfMorph - ONE_HALF);
        c_low = 0;
        c_band = ONE - lpfMorph;
        c_high = lpfMorph;
      } else {
        lpfMorph = 2 * lpfMorph;
        c_low = ONE - lpfMorph;
        c_band = lpfMorph;
        c_high = 0;
      }
    } else {
      c_low = ONE - lpfMorph;
      c_high = lpfMorph;
      c_band = 0;
    }
    return filterGain;
  }

  @Override
  public void doFilter(int[] samples, int offset, int length, int sampleIncrement) {
    for (int i = 0; i < length; i += sampleIncrement) {
      samples[offset + i] = doSVF(samples[offset + i], l);
    }
  }

  @Override
  public void doFilterStereo(int[] samples, int offset, int length) {
    for (int i = 0; i < length; i += 2) {
      samples[offset + i] = doSVF(samples[offset + i], l);
      samples[offset + i + 1] = doSVF(samples[offset + i + 1], r);
    }
  }

  @Override
  public void resetFilter() {
    l.low = 0;
    l.band = 0;
    r.low = 0;
    r.band = 0;
  }

  private int doSVF(int input, SVFState state) {
    int low = state.low;
    int band = state.band;

    input = multiply_32x32_rshift32(in, input);

    low = low + 2 * multiply_32x32_rshift32(band, fc);
    int high = input - low;
    high = high - 2 * multiply_32x32_rshift32(band, q);
    band = 2 * multiply_32x32_rshift32(high, fc) + band;

    // saturate band feedback
    band = FirmwareUtils.getTanHUnknown(band, 3);

    int lowi = low;
    int highi = high;
    int bandi = band;

    // double sample to increase the cutoff frequency
    low = low + 2 * multiply_32x32_rshift32(band, fc);
    high = input - low;
    high = high - 2 * multiply_32x32_rshift32(band, q);
    band = 2 * multiply_32x32_rshift32(high, fc) + band;

    lowi = lowi + low;
    highi = highi + high;
    bandi = bandi + band;

    int result = multiply_32x32_rshift32_rounded(lowi, c_low);
    result = multiply_accumulate_32x32_rshift32_rounded(result, highi, c_high);
    if (band_mode) {
      result = multiply_accumulate_32x32_rshift32_rounded(result, bandi, c_band);
    }

    // saturate band feedback
    band = FirmwareUtils.getTanHUnknown(band, 3);

    // compensate for division by two on each multiply
    // then multiply by 1.5 to match ladders (3 * result / 2?)
    // wait, the C++ code says "result = 3 * result;"
    // result is q31. If we multiply by 3, it might overflow.
    // Let's re-read: "result = 3 * result;"

    result = 3 * result;

    state.low = low;
    state.band = band;

    return result;
  }
}
