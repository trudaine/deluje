package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code lpladder.cpp} and {@code lpladder.h}. Supports 12dB/24dB LP
 * transistor ladders with drive, oversampling, and analog noise modulation.
 */
public class LpLadderFilter extends Filter {
  public static class LpLadderState {
    public int noiseLastValue = 0;
    public final BasicFilterComponent lpfLPF1 = new BasicFilterComponent();
    public final BasicFilterComponent lpfLPF2 = new BasicFilterComponent();
    public final BasicFilterComponent lpfLPF3 = new BasicFilterComponent();
    public final BasicFilterComponent lpfLPF4 = new BasicFilterComponent();

    public void reset() {
      noiseLastValue = 0;
      lpfLPF1.reset();
      lpfLPF2.reset();
      lpfLPF3.reset();
      lpfLPF4.reset();
    }
  }

  private FilterSet.FilterMode lpfMode;
  public final LpLadderState l = new LpLadderState();
  public final LpLadderState r = new LpLadderState();

  public int processedResonance;
  public int divideByTotalMoveabilityAndProcessedResonance;
  public int moveability;
  public int morph;

  public int lpf1Feedback;
  public int lpf2Feedback;
  public int lpf3Feedback;
  public boolean doOversampling;

  @Override
  public void resetFilter() {
    l.reset();
    r.reset();
  }

  @Override
  public int setConfig(
      int lpfFrequency,
      int lpfResonance,
      FilterSet.FilterMode lpfmode,
      int lpfMorph,
      int filterGain) {
    lpfMode = lpfmode;
    morph = lpfMorph;

    // Hot transistor ladder - needs oversampling and stuff
    if (lpfMode == FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE) {
      int resonance = Functions.ONE_Q31 - (lpfResonance << 2); // Limits it
      processedResonance =
          Functions.ONE_Q31 - resonance; // Always between 0 and 2. 1 represented as 1073741824

      int logFreq = Functions.quickLog(lpfFrequency);
      doOversampling = false;

      logFreq = Math.min(logFreq, 63 << 24);

      if ((logFreq >> 24) > 51) {
        int resonanceThreshold =
            Functions.interpolateTableSigned(
                logFreq, 30, LookupTables.resonanceThresholdsForOversampling, 6);
        doOversampling = (processedResonance > resonanceThreshold);
      }

      if (doOversampling) {
        lpfFrequency >>= 1;
        logFreq -= 33554432;
        lpfFrequency -=
            (Functions.multiply_32x32_rshift32_rounded(logFreq, lpfFrequency) >> 8) * 34;
        lpfFrequency = Math.min(39056384, lpfFrequency);

        int resonanceLimit =
            Functions.interpolateTableSigned(logFreq, 30, LookupTables.resonanceLimitTable, 6);
        processedResonance = Math.min(processedResonance, resonanceLimit);
      }
    }

    // Cold transistor ladder
    if ((lpfMode == FilterSet.FilterMode.TRANSISTOR_24DB)
        || (lpfMode == FilterSet.FilterMode.TRANSISTOR_12DB)) {
      int howMuchToKeep = Functions.ONE_Q31 - 33;
      int resonanceUpperLimit = 510000000;
      int resonance =
          Functions.ONE_Q31 - (Math.min(lpfResonance, resonanceUpperLimit) << 2); // Limits it
      resonance = Functions.multiply_32x32_rshift32_rounded(resonance, resonance) << 1;
      processedResonance = Functions.ONE_Q31 - resonance;
      processedResonance =
          Functions.multiply_32x32_rshift32_rounded(processedResonance, howMuchToKeep) << 1;
    }

    curveFrequency(lpfFrequency);
    moveability = Math.max(fc, 4317840);

    // Half ladder
    if (lpfMode == FilterSet.FilterMode.TRANSISTOR_12DB) {
      int moveabilityNegative = moveability - 1073741824;
      lpf2Feedback =
          Functions.multiply_32x32_rshift32_rounded(
                  moveabilityNegative, divideBy1PlusTannedFrequency)
              << 1;
      lpf1Feedback = Functions.multiply_32x32_rshift32_rounded(lpf2Feedback, moveability) << 1;
      long denom =
          67108864L
              + Functions.multiply_32x32_rshift32_rounded(
                  processedResonance,
                  Functions.multiply_32x32_rshift32_rounded(
                      moveabilityNegative,
                      Functions.multiply_32x32_rshift32_rounded(moveability, moveability)));
      divideByTotalMoveabilityAndProcessedResonance = (int) ((67108864L * 1073741824L) / denom);
    } else {
      // Full ladder
      lpf3Feedback =
          Functions.multiply_32x32_rshift32_rounded(divideBy1PlusTannedFrequency, moveability);
      lpf2Feedback = Functions.multiply_32x32_rshift32_rounded(lpf3Feedback, moveability) << 1;
      lpf1Feedback = Functions.multiply_32x32_rshift32_rounded(lpf2Feedback, moveability) << 1;

      long onePlusThing =
          67108864L
              + Functions.multiply_32x32_rshift32_rounded(
                  moveability,
                  Functions.multiply_32x32_rshift32_rounded(
                      moveability,
                      Functions.multiply_32x32_rshift32_rounded(
                          moveability,
                          Functions.multiply_32x32_rshift32_rounded(
                              moveability, processedResonance))));
      divideByTotalMoveabilityAndProcessedResonance =
          (int) (72057594037927936.0 / (double) onePlusThing);
    }

    if (lpfMode != FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE) {
      if (tannedFrequency <= 304587486) {
        processedResonance =
            Functions.multiply_32x32_rshift32_rounded(processedResonance, 1150000000) << 1;
      } else {
        processedResonance >>= 1;
      }

      int a = Math.min(lpfResonance, 536870911);
      a = 536870912 - a;
      a = Functions.multiply_32x32_rshift32(a, a) << 3;
      a = 536870912 - a;
      int gainModifier = 268435456 + a;
      filterGain = Functions.multiply_32x32_rshift32(filterGain, gainModifier) << 3;
    } else {
      filterGain = (int) (filterGain * 0.8f);
    }

    return filterGain;
  }

  @Override
  public void doFilter(int[] buf, int startIdx, int endIdx, int step) {
    if (lpfMode == FilterSet.FilterMode.TRANSISTOR_12DB) {
      for (int i = startIdx; i < endIdx; i += step) {
        buf[i] = do12dBLPFOnSample(buf[i], l);
      }
    } else if (lpfMode == FilterSet.FilterMode.TRANSISTOR_24DB) {
      for (int i = startIdx; i < endIdx; i += step) {
        buf[i] = do24dBLPFOnSample(buf[i], l);
      }
    } else if (lpfMode == FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE) {
      if (doOversampling) {
        for (int i = startIdx; i < endIdx; i += step) {
          doDriveLPFOnSample(buf[i], l);
          int outputSampleToKeep = doDriveLPFOnSample(buf[i], l);
          buf[i] = Functions.getTanHUnknown(outputSampleToKeep, 4);
        }
      } else {
        for (int i = startIdx; i < endIdx; i += step) {
          int outputSampleToKeep = doDriveLPFOnSample(buf[i], l);
          buf[i] = Functions.getTanHUnknown(outputSampleToKeep, 4);
        }
      }
    }
  }

  @Override
  public void doFilterStereo(int[] buf, int startIdx, int endIdx) {
    if (lpfMode == FilterSet.FilterMode.TRANSISTOR_12DB) {
      for (int i = startIdx; i < endIdx; i += 2) {
        buf[i] = do12dBLPFOnSample(buf[i], l);
        buf[i + 1] = do12dBLPFOnSample(buf[i + 1], r);
      }
    } else if (lpfMode == FilterSet.FilterMode.TRANSISTOR_24DB) {
      for (int i = startIdx; i < endIdx; i += 2) {
        buf[i] = do24dBLPFOnSample(buf[i], l);
        buf[i + 1] = do24dBLPFOnSample(buf[i + 1], r);
      }
    } else if (lpfMode == FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE) {
      if (doOversampling) {
        for (int i = startIdx; i < endIdx; i += 2) {
          doDriveLPFOnSample(buf[i], l);
          int outL = doDriveLPFOnSample(buf[i], l);
          buf[i] = Functions.getTanHUnknown(outL, 4);

          doDriveLPFOnSample(buf[i + 1], r);
          int outR = doDriveLPFOnSample(buf[i + 1], r);
          buf[i + 1] = Functions.getTanHUnknown(outR, 4);
        }
      } else {
        for (int i = startIdx; i < endIdx; i += 2) {
          int outL = doDriveLPFOnSample(buf[i], l);
          buf[i] = Functions.getTanHUnknown(outL, 4);
          int outR = doDriveLPFOnSample(buf[i + 1], r);
          buf[i + 1] = Functions.getTanHUnknown(outR, 4);
        }
      }
    }
  }

  private int scaleInput(int input, int feedbacksSum) {
    int temp;
    if (morph > 0 || processedResonance > 510000000) {
      temp =
          Functions.multiply_32x32_rshift32_rounded(
              input
                  - Functions.lshiftAndSaturate(
                      Functions.multiply_32x32_rshift32_rounded(feedbacksSum, processedResonance),
                      3),
              divideByTotalMoveabilityAndProcessedResonance);
      temp = Functions.lshiftAndSaturate(temp, 2);
      int extra = 2 * Functions.multiply_32x32_rshift32(input, morph);
      temp = Functions.getTanHUnknown(temp + extra, 2);
    } else {
      temp =
          Functions.multiply_32x32_rshift32_rounded(
              input
                  - Functions.lshiftAndSaturate(
                      Functions.multiply_32x32_rshift32_rounded(feedbacksSum, processedResonance),
                      3),
              divideByTotalMoveabilityAndProcessedResonance);
      temp = Functions.lshiftAndSaturate(temp, 2);
    }
    return temp;
  }

  private int do12dBLPFOnSample(int input, LpLadderState state) {
    int noise = Functions.getNoise() >> 2;
    int distanceToGo = noise - state.noiseLastValue;
    state.noiseLastValue += distanceToGo >> 7;
    int noisy_m =
        moveability + Functions.multiply_32x32_rshift32(moveability, state.noiseLastValue);

    int feedbacksSum =
        state.lpfLPF1.getFeedbackOutput(lpf1Feedback)
            + state.lpfLPF2.getFeedbackOutput(lpf2Feedback)
            + state.lpfLPF3.getFeedbackOutput(divideBy1PlusTannedFrequency);
    int x = scaleInput(input, feedbacksSum);

    return Functions.lshiftAndSaturate(
        state.lpfLPF3.doAPF(
            state.lpfLPF2.doFilter(state.lpfLPF1.doFilter(x, noisy_m), noisy_m), noisy_m),
        1);
  }

  private int do24dBLPFOnSample(int input, LpLadderState state) {
    int noise = Functions.getNoise() >> 2;
    int distanceToGo = noise - state.noiseLastValue;
    state.noiseLastValue += distanceToGo >> 7;
    int noisy_m =
        moveability + Functions.multiply_32x32_rshift32(moveability, state.noiseLastValue);

    int feedbacksSum =
        (state.lpfLPF1.getFeedbackOutputWithoutLshift(lpf1Feedback)
            + state.lpfLPF2.getFeedbackOutputWithoutLshift(lpf2Feedback)
            + state.lpfLPF3.getFeedbackOutputWithoutLshift(lpf3Feedback)
            + state.lpfLPF4.getFeedbackOutputWithoutLshift(divideBy1PlusTannedFrequency));
    feedbacksSum = Functions.lshiftAndSaturate(feedbacksSum, 2);

    int x = scaleInput(input, feedbacksSum);
    return Functions.lshiftAndSaturate(
        state.lpfLPF4.doFilter(
            state.lpfLPF3.doFilter(
                state.lpfLPF2.doFilter(state.lpfLPF1.doFilter(x, noisy_m), noisy_m), noisy_m),
            noisy_m),
        1);
  }

  private int doDriveLPFOnSample(int input, LpLadderState state) {
    int noise = Functions.getNoise() >> 2;
    int distanceToGo = noise - state.noiseLastValue;
    state.noiseLastValue += distanceToGo >> 7;
    int noisy_m =
        moveability + Functions.multiply_32x32_rshift32(moveability, state.noiseLastValue);

    int feedbacksSum =
        (state.lpfLPF1.getFeedbackOutputWithoutLshift(lpf1Feedback)
            + state.lpfLPF2.getFeedbackOutputWithoutLshift(lpf2Feedback)
            + state.lpfLPF3.getFeedbackOutputWithoutLshift(lpf3Feedback)
            + state.lpfLPF4.getFeedbackOutputWithoutLshift(divideBy1PlusTannedFrequency));
    feedbacksSum = Functions.lshiftAndSaturate(feedbacksSum, 2);

    feedbacksSum = Functions.getTanHUnknown(feedbacksSum, 7);
    int x = scaleInput(input, feedbacksSum);

    int a = state.lpfLPF1.doFilter(x, noisy_m);
    int b = state.lpfLPF2.doFilter(a, noisy_m);
    int c = state.lpfLPF3.doFilter(b, noisy_m);
    int d = Functions.lshiftAndSaturate(state.lpfLPF4.doFilter(c, noisy_m), 1);
    return d;
  }
}
