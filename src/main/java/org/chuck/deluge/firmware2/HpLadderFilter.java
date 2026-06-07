package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code hpladder.cpp} and {@code hpladder.h}. Implements a high-pass
 * transistor ladder filter with volume-compensated high resonance, getTanHAntialiased feedback
 * saturation, and one-pole cascade elements.
 */
public class HpLadderFilter extends Filter {
  public static class HpLadderState {
    public final BasicFilterComponent hpfHPF1 = new BasicFilterComponent();
    public final BasicFilterComponent hpfLPF1 = new BasicFilterComponent();
    public final BasicFilterComponent hpfHPF3 = new BasicFilterComponent();
    public int hpfLastWorkingValue = 0x80000000;

    public void reset() {
      hpfHPF1.reset();
      hpfLPF1.reset();
      hpfHPF3.reset();
      hpfLastWorkingValue = 0x80000000;
    }
  }

  public final HpLadderState l = new HpLadderState();
  public final HpLadderState r = new HpLadderState();

  public int hpfProcessedResonance;
  public int hpfDivideByProcessedResonance;
  public int divideByTotalMoveability;
  public int morph_;

  // All feedbacks have 1 represented as 1073741824
  private int hpfLPF1Feedback;
  private int hpfHPF3Feedback;

  @Override
  public void resetFilter() {
    l.reset();
    r.reset();
  }

  @Override
  public int setConfig(
      int hpfFrequency,
      int hpfResonance,
      FilterSet.FilterMode lpfMode,
      int lpfMorph,
      int filterGain) {
    int extraFeedback = 1200000000;
    morph_ = lpfMorph;
    curveFrequency(hpfFrequency);

    int resonanceUpperLimit = 536870911;
    int resonance =
        Functions.ONE_Q31 - (Math.min(hpfResonance, resonanceUpperLimit) << 2); // Limits it
    resonance = Functions.multiply_32x32_rshift32_rounded(resonance, resonance) << 1;

    hpfProcessedResonance = Functions.ONE_Q31 - resonance;
    hpfProcessedResonance =
        Math.max(hpfProcessedResonance, 134217728); // Set minimum resonance amount

    int hpfProcessedResonanceUnaltered = hpfProcessedResonance;

    // Extra feedback
    hpfProcessedResonance =
        Functions.multiply_32x32_rshift32(hpfProcessedResonance, extraFeedback) << 1;

    hpfDivideByProcessedResonance = (int) (2147483648.0 / (double) (hpfProcessedResonance >>> 23));

    int moveabilityTimesProcessedResonance =
        Functions.multiply_32x32_rshift32(hpfProcessedResonanceUnaltered, fc);
    int moveabilitySquaredTimesProcessedResonance =
        Functions.multiply_32x32_rshift32(moveabilityTimesProcessedResonance, fc);

    hpfHPF3Feedback = -Functions.multiply_32x32_rshift32_rounded(fc, divideBy1PlusTannedFrequency);
    hpfLPF1Feedback = divideBy1PlusTannedFrequency >> 1;

    int toDivideBy =
        268435456
            - (moveabilityTimesProcessedResonance >> 1)
            + moveabilitySquaredTimesProcessedResonance;
    divideByTotalMoveability =
        (int) ((double) hpfProcessedResonance * 67108864.0 / (double) toDivideBy);

    // Adjust volume for HPF resonance
    int rawResonance = Math.min(hpfResonance, Functions.ONE_Q31 >>> 2) << 2;
    int squared = Functions.multiply_32x32_rshift32(rawResonance, rawResonance) << 1;

    // Make bigger to have more of a volume cut happen at high resonance
    squared = (int) ((Functions.multiply_32x32_rshift32(squared, squared) >>> 4) * 19L);
    filterGain = Functions.multiply_32x32_rshift32(filterGain, Functions.ONE_Q31 - squared) << 1;

    return filterGain;
  }

  @Override
  public void doFilter(int[] buf, int startIdx, int endIdx, int step) {
    for (int i = startIdx; i < endIdx; i += step) {
      buf[i] = doHPF(buf[i], l);
    }
  }

  @Override
  public void doFilterStereo(int[] buf, int startIdx, int endIdx) {
    for (int i = startIdx; i < endIdx; i += 2) {
      buf[i] = doHPF(buf[i], l);
      buf[i + 1] = doHPF(buf[i + 1], r);
    }
  }

  private int doHPF(int input, HpLadderState state) {
    int lower_limit = -(Functions.ONE_Q31 >>> 8);
    int temp_fc =
        Math.max(
            Functions.multiply_accumulate_32x32_rshift32_rounded(fc, input << 4, morph_),
            lower_limit);

    int firstHPFOutput = input - state.hpfHPF1.doFilter(input, temp_fc);

    int feedbacksValue =
        state.hpfHPF3.getFeedbackOutput(hpfHPF3Feedback)
            + state.hpfLPF1.getFeedbackOutput(hpfLPF1Feedback);

    int a =
        Functions.multiply_32x32_rshift32_rounded(
                divideByTotalMoveability, firstHPFOutput + feedbacksValue)
            << 5;

    // Only saturate / anti-alias if lots of resonance
    if (hpfProcessedResonance > 900000000) {
      int nextLastWorkingValue = Functions.lshiftAndSaturateUnknown(a, 1) + 0x80000000;
      a = Functions.getTanHAntialiased(a, state.hpfLastWorkingValue, 1);
      state.hpfLastWorkingValue = nextLastWorkingValue;
    } else {
      state.hpfLastWorkingValue = Functions.lshiftAndSaturate(a, 2) + 0x80000000;
      if (hpfProcessedResonance > 750000000) {
        a = Functions.getTanHUnknown(a, 2);
      }
    }

    state.hpfLPF1.doFilter(a - state.hpfHPF3.doFilter(a, temp_fc), temp_fc);

    a = Functions.multiply_32x32_rshift32_rounded(a, hpfDivideByProcessedResonance) << 7;

    return a;
  }
}
