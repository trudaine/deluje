package org.chuck.deluge.firmware.dsp.filter;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.util.FirmwareUtils;

public class HpLadderFilter extends FirmwareFilter {

  private static class HpLadderState {
    final BasicFilterComponent hpfHPF1 = new BasicFilterComponent();
    final BasicFilterComponent hpfLPF1 = new BasicFilterComponent();
    final BasicFilterComponent hpfHPF3 = new BasicFilterComponent();
    int hpfLastWorkingValue;

    void reset() {
      hpfHPF1.reset();
      hpfLPF1.reset();
      hpfHPF3.reset();
      hpfLastWorkingValue = 0;
    }
  }

  private final HpLadderState l = new HpLadderState();
  private final HpLadderState r = new HpLadderState();

  private int hpfLPF1Feedback;
  private int hpfHPF3Feedback;
  private int hpfProcessedResonance;
  private int hpfDivideByProcessedResonance;
  private int divideByTotalMoveability;
  private int morph_;

  @Override
  public int setConfig(
      int hpfFrequency, int hpfResonance, FilterMode lpfMode, int lpfMorph, int filterGain) {
    int extraFeedback = 1200000000;
    this.morph_ = lpfMorph;
    curveFrequency(hpfFrequency);

    int resonanceUpperLimit = 536870911;
    int resonance = ONE - (Math.min(Math.abs(hpfResonance), resonanceUpperLimit) << 2);
    resonance = multiply_32x32_rshift32_rounded(resonance, resonance) << 1;

    hpfProcessedResonance = ONE - resonance;
    hpfProcessedResonance = Math.max(hpfProcessedResonance, 134217728);

    int hpfProcessedResonanceUnaltered = hpfProcessedResonance;
    hpfProcessedResonance = multiply_32x32_rshift32(hpfProcessedResonance, extraFeedback) << 1;

    hpfDivideByProcessedResonance = (int) (2147483648.0 / (double) (hpfProcessedResonance >>> 23));

    int moveabilityTimesProcessedResonance =
        multiply_32x32_rshift32(hpfProcessedResonanceUnaltered, fc);
    int moveabilitySquaredTimesProcessedResonance =
        multiply_32x32_rshift32(moveabilityTimesProcessedResonance, fc);

    hpfHPF3Feedback = -multiply_32x32_rshift32_rounded(fc, divideBy1PlusTannedFrequency);
    hpfLPF1Feedback = divideBy1PlusTannedFrequency >>> 1;

    long toDivideBy =
        (268435456L
            - (moveabilityTimesProcessedResonance >>> 1)
            + moveabilitySquaredTimesProcessedResonance);
    divideByTotalMoveability =
        (int) ((double) hpfProcessedResonance * 67108864.0 / (double) toDivideBy);

    // Adjust volume for HPF resonance
    int rawResonance = Math.min(Math.abs(hpfResonance), ONE >>> 2) << 2;
    int squared = multiply_32x32_rshift32(rawResonance, rawResonance) << 1;
    squared = (multiply_32x32_rshift32(squared, squared) >>> 4) * 19;
    filterGain = multiply_32x32_rshift32(filterGain, ONE - squared) << 1;

    return filterGain;
  }

  @Override
  public void doFilter(int[] samples, int offset, int length, int sampleIncrement) {
    for (int i = 0; i < length; i += sampleIncrement) {
      samples[offset + i] = doHPF(samples[offset + i], l);
    }
  }

  @Override
  public void doFilterStereo(int[] samples, int offset, int length) {
    for (int i = 0; i < length; i += 2) {
      samples[offset + i] = doHPF(samples[offset + i], l);
      samples[offset + i + 1] = doHPF(samples[offset + i + 1], r);
    }
  }

  @Override
  public void resetFilter() {
    l.reset();
    r.reset();
  }

  private int doHPF(int input, HpLadderState state) {
    int lowerLimit = -(ONE >>> 8);
    int tempFc =
        Math.max(multiply_accumulate_32x32_rshift32_rounded(fc, input << 4, morph_), lowerLimit);

    int firstHPFOutput = input - state.hpfHPF1.doFilter(input, tempFc);

    int feedbacksValue =
        state.hpfHPF3.getFeedbackOutput(hpfHPF3Feedback)
            + state.hpfLPF1.getFeedbackOutput(hpfLPF1Feedback);

    int a =
        multiply_32x32_rshift32_rounded(divideByTotalMoveability, firstHPFOutput + feedbacksValue)
            << (4 + 1);

    if (hpfProcessedResonance > 900000000) {
      int[] lastWork = {state.hpfLastWorkingValue};
      a = FirmwareUtils.getTanHAntialiased(a, lastWork, 1);
      state.hpfLastWorkingValue = lastWork[0];
    } else {
      state.hpfLastWorkingValue = (int) (FirmwareUtils.lshiftAndSaturate(a, 2) + 2147483648L);
      if (hpfProcessedResonance > 750000000) {
        a = FirmwareUtils.getTanHUnknown(a, 2);
      }
    }

    state.hpfLPF1.doFilter(a - state.hpfHPF3.doFilter(a, tempFc), tempFc);

    a = multiply_32x32_rshift32_rounded(a, hpfDivideByProcessedResonance) << (8 - 1);

    return a;
  }
}
