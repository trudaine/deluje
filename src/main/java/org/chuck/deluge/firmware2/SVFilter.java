package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code svf.cpp} and {@code svf.h}. Implements a State Variable
 * Filter (SVF) with band and notch modes, double-sample iteration for cutoff expansion, and 3x
 * output scaling matching ladder filters.
 */
public class SVFilter extends Filter {
  public static class SVFState {
    public int low = 0;
    public int band = 0;

    public void reset() {
      low = 0;
      band = 0;
    }
  }

  public final SVFState l = new SVFState();
  public final SVFState r = new SVFState();

  public int q;
  public int in;
  public int c_low;
  public int c_band;
  public int c_high;
  public boolean band_mode;

  @Override
  public void resetFilter() {
    l.reset();
    r.reset();
  }

  @Override
  public int setConfig(
      int freq, int res, FilterSet.FilterMode lpfMode, int lpfMorph, int filterGain) {
    curveFrequency(freq);

    // multiply by 1.25 to loosely correct for equivalency to ladders
    int POINT_25 = (int) (Functions.ONE_Q31 * 0.25f);
    fc = fc + Functions.multiply_32x32_rshift32(fc, POINT_25);

    band_mode = (lpfMode == FilterSet.FilterMode.SVF_BAND);

    // raw resonance is 0 - 536870896 (2^28ish)
    // Multiply by 4 to bring it to the q31 0-1 range
    q = Functions.ONE_Q31 - (res << 2);
    in = (q >> 1) + (Functions.ONE_Q31 >> 1);

    // squared q is a better match for the ladders
    q = Functions.multiply_32x32_rshift32_rounded(q, q) << 1;

    int ONE_HALF = Functions.ONE_Q31 >> 1;
    if (band_mode) {
      if (lpfMorph > ONE_HALF) {
        lpfMorph = 2 * (lpfMorph - ONE_HALF);
        c_low = 0;
        c_band = Functions.ONE_Q31 - lpfMorph;
        c_high = lpfMorph;
      } else {
        lpfMorph = 2 * lpfMorph;
        c_low = Functions.ONE_Q31 - lpfMorph;
        c_band = lpfMorph;
        c_high = 0;
      }
    } else {
      c_low = Functions.ONE_Q31 - lpfMorph;
      c_high = lpfMorph;
      c_band = 0;
    }

    return filterGain;
  }

  @Override
  public void doFilter(int[] buf, int startIdx, int endIdx, int step) {
    for (int i = startIdx; i < endIdx; i += step) {
      buf[i] = doSVF(buf[i], l);
    }
  }

  @Override
  public void doFilterStereo(int[] buf, int startIdx, int endIdx) {
    for (int i = startIdx; i < endIdx; i += 2) {
      buf[i] = doSVF(buf[i], l);
      buf[i + 1] = doSVF(buf[i + 1], r);
    }
  }

  private int doSVF(int input, SVFState state) {
    int high = 0;
    int lowi;
    int highi;
    int bandi;
    int low = state.low;
    int band = state.band;

    input = Functions.multiply_32x32_rshift32(in, input);

    low = low + 2 * Functions.multiply_32x32_rshift32(band, fc);
    high = input - low;
    high = high - 2 * Functions.multiply_32x32_rshift32(band, q);
    band = 2 * Functions.multiply_32x32_rshift32(high, fc) + band;

    // saturate band feedback
    band = Functions.getTanHUnknown(band, 3);

    lowi = low;
    highi = high;
    bandi = band;

    // double sample to increase the cutoff frequency
    low = low + 2 * Functions.multiply_32x32_rshift32(band, fc);
    high = input - low;
    high = high - 2 * Functions.multiply_32x32_rshift32(band, q);
    band = 2 * Functions.multiply_32x32_rshift32(high, fc) + band;

    lowi = lowi + low;
    highi = highi + high;
    bandi = bandi + band;

    int result = Functions.multiply_32x32_rshift32_rounded(lowi, c_low);
    result = Functions.multiply_accumulate_32x32_rshift32_rounded(result, highi, c_high);
    if (band_mode) {
      result = Functions.multiply_accumulate_32x32_rshift32_rounded(result, bandi, c_band);
    }

    // saturate band feedback
    band = Functions.getTanHUnknown(band, 3);
    result = 3 * result;

    state.low = low;
    state.band = band;

    return result;
  }
}
