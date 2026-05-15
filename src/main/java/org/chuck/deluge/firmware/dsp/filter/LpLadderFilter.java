package org.chuck.deluge.firmware.dsp.filter;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.LookupTables;

public class LpLadderFilter extends FirmwareFilter {

  private static class LpLadderState {
    int noiseLastValue;
    final BasicFilterComponent lpfLPF1 = new BasicFilterComponent();
    final BasicFilterComponent lpfLPF2 = new BasicFilterComponent();
    final BasicFilterComponent lpfLPF3 = new BasicFilterComponent();
    final BasicFilterComponent lpfLPF4 = new BasicFilterComponent();

    void reset() {
      noiseLastValue = 0;
      lpfLPF1.reset();
      lpfLPF2.reset();
      lpfLPF3.reset();
      lpfLPF4.reset();
    }
  }

  private FilterMode lpfMode;
  private final LpLadderState l = new LpLadderState();
  private final LpLadderState r = new LpLadderState();

  private int processedResonance;
  private int divideByTotalMoveabilityAndProcessedResonance;
  private int moveability;
  private int morph;
  private int lpf1Feedback;
  private int lpf2Feedback;
  private int lpf3Feedback;
  private boolean doOversampling;

  @Override
  public int setConfig(
      int lpfFrequency, int lpfResonance, FilterMode mode, int lpfMorph, int filterGain) {
    this.lpfMode = mode;
    this.morph = lpfMorph;

    if (lpfMode == FilterMode.TRANSISTOR_24DB_DRIVE) {
      int resonance = ONE - (lpfResonance << 2);
      processedResonance = ONE - resonance;

      int logFreq = FirmwareUtils.quickLog(lpfFrequency);
      logFreq = Math.min(logFreq, 63 << 24);

      doOversampling = false;
      // ── Hardware Direness Check ──
      if ((logFreq >>> 24) > 51) {
        int resonanceThreshold =
            FirmwareUtils.interpolateTableSigned(
                logFreq, 30, LookupTables.resonanceThresholdsForOversampling, 6);
        doOversampling = (processedResonance > resonanceThreshold);
      }

      if (doOversampling) {
        lpfFrequency >>>= 1;
        logFreq -= 33554432;
        lpfFrequency -= (multiply_32x32_rshift32_rounded(logFreq, lpfFrequency) >> 8) * 34;
        lpfFrequency = Math.min(39056384, lpfFrequency);
        int resonanceLimit =
            FirmwareUtils.interpolateTableSigned(logFreq, 30, LookupTables.resonanceLimitTable, 6);
        processedResonance = Math.min(processedResonance, resonanceLimit);
      }
    }

    if (lpfMode == FilterMode.TRANSISTOR_24DB || lpfMode == FilterMode.TRANSISTOR_12DB) {
      int howMuchToKeep = ONE - 33;
      int resonanceUpperLimit = 510000000;
      int resonance = ONE - (Math.min(lpfResonance, resonanceUpperLimit) << 2);
      resonance = multiply_32x32_rshift32_rounded(resonance, resonance) << 1;
      processedResonance = ONE - resonance;
      processedResonance = multiply_32x32_rshift32_rounded(processedResonance, howMuchToKeep) << 1;
    }

    curveFrequency(lpfFrequency);
    moveability = Math.max(fc, 4317840);

    if (lpfMode == FilterMode.TRANSISTOR_12DB) {
      int moveabilityNegative = moveability - 1073741824;
      lpf2Feedback =
          multiply_32x32_rshift32_rounded(moveabilityNegative, divideBy1PlusTannedFrequency) << 1;
      lpf1Feedback = multiply_32x32_rshift32_rounded(lpf2Feedback, moveability) << 1;

      long denom =
          67108864L
              + multiply_32x32_rshift32_rounded(
                  processedResonance,
                  multiply_32x32_rshift32_rounded(
                      moveabilityNegative,
                      multiply_32x32_rshift32_rounded(moveability, moveability)));
      divideByTotalMoveabilityAndProcessedResonance = (int) (67108864L * 1073741824L / denom);
    } else {
      lpf3Feedback = multiply_32x32_rshift32_rounded(divideBy1PlusTannedFrequency, moveability);
      lpf2Feedback = multiply_32x32_rshift32_rounded(lpf3Feedback, moveability) << 1;
      lpf1Feedback = multiply_32x32_rshift32_rounded(lpf2Feedback, moveability) << 1;

      long onePlusThing =
          67108864L
              + multiply_32x32_rshift32_rounded(
                  moveability,
                  multiply_32x32_rshift32_rounded(
                      moveability,
                      multiply_32x32_rshift32_rounded(
                          moveability,
                          multiply_32x32_rshift32_rounded(moveability, processedResonance))));
      divideByTotalMoveabilityAndProcessedResonance =
          (int) (72057594037927936.0 / (double) onePlusThing);
    }

    if (lpfMode != FilterMode.TRANSISTOR_24DB_DRIVE) {
      if (tannedFrequency <= 304587486) {
        processedResonance = multiply_32x32_rshift32_rounded(processedResonance, 1150000000) << 1;
      } else {
        processedResonance >>>= 1;
      }

      int a = Math.min(lpfResonance, 536870911);
      a = 536870912 - a;
      a = multiply_32x32_rshift32(a, a) << 3;
      a = 536870912 - a;
      int gainModifier = 268435456 + a;
      filterGain = multiply_32x32_rshift32(filterGain, gainModifier) << 3;
    } else {
      filterGain = (int) (filterGain * 0.8);
    }

    return filterGain;
  }

  @Override
  public void doFilter(int[] samples, int offset, int length, int sampleIncrement) {
    if (lpfMode == FilterMode.TRANSISTOR_12DB) {
      for (int i = 0; i < length; i += sampleIncrement) {
        samples[offset + i] = do12dBLPFOnSample(samples[offset + i], l);
      }
    } else if (lpfMode == FilterMode.TRANSISTOR_24DB) {
      for (int i = 0; i < length; i += sampleIncrement) {
        samples[offset + i] = do24dBLPFOnSample(samples[offset + i], l);
      }
    } else if (lpfMode == FilterMode.TRANSISTOR_24DB_DRIVE) {
      for (int i = 0; i < length; i += sampleIncrement) {
        if (doOversampling) {
          doDriveLPFOnSample(samples[offset + i], l);
          int outputSampleToKeep = doDriveLPFOnSample(samples[offset + i], l);
          samples[offset + i] = FirmwareUtils.getTanHUnknown(outputSampleToKeep, 4);
        } else {
          int outputSampleToKeep = doDriveLPFOnSample(samples[offset + i], l);
          samples[offset + i] = FirmwareUtils.getTanHUnknown(outputSampleToKeep, 4);
        }
      }
    }
  }

  @Override
  public void doFilterStereo(int[] samples, int offset, int length) {
    if (lpfMode == FilterMode.TRANSISTOR_12DB) {
      for (int i = 0; i < length; i += 2) {
        samples[offset + i] = do12dBLPFOnSample(samples[offset + i], l);
        samples[offset + i + 1] = do12dBLPFOnSample(samples[offset + i + 1], r);
      }
    } else if (lpfMode == FilterMode.TRANSISTOR_24DB) {
      for (int i = 0; i < length; i += 2) {
        samples[offset + i] = do24dBLPFOnSample(samples[offset + i], l);
        samples[offset + i + 1] = do24dBLPFOnSample(samples[offset + i + 1], r);
      }
    } else if (lpfMode == FilterMode.TRANSISTOR_24DB_DRIVE) {
      for (int i = 0; i < length; i += 2) {
        if (doOversampling) {
          doDriveLPFOnSample(samples[offset + i], l);
          int outputSampleToKeep = doDriveLPFOnSample(samples[offset + i], l);
          samples[offset + i] = FirmwareUtils.getTanHUnknown(outputSampleToKeep, 4);

          doDriveLPFOnSample(samples[offset + i + 1], r);
          outputSampleToKeep = doDriveLPFOnSample(samples[offset + i + 1], r);
          samples[offset + i + 1] = FirmwareUtils.getTanHUnknown(outputSampleToKeep, 4);
        } else {
          int outputSampleToKeep = doDriveLPFOnSample(samples[offset + i], l);
          samples[offset + i] = FirmwareUtils.getTanHUnknown(outputSampleToKeep, 4);
          outputSampleToKeep = doDriveLPFOnSample(samples[offset + i + 1], r);
          samples[offset + i + 1] = FirmwareUtils.getTanHUnknown(outputSampleToKeep, 4);
        }
      }
    }
  }

  @Override
  public void resetFilter() {
    l.reset();
    r.reset();
  }

  private int scaleInput(int input, int feedbacksSum) {
    int temp;
    if (morph > 0 || processedResonance > 510000000) {
      temp =
          multiply_32x32_rshift32_rounded(
                  (input
                      - (multiply_32x32_rshift32_rounded(feedbacksSum, processedResonance) << 3)),
                  divideByTotalMoveabilityAndProcessedResonance)
              << 2;
      int extra = 2 * multiply_32x32_rshift32(input, morph);
      temp = FirmwareUtils.getTanHUnknown(temp + extra, 2);
    } else {
      temp =
          multiply_32x32_rshift32_rounded(
                  (input
                      - (multiply_32x32_rshift32_rounded(feedbacksSum, processedResonance) << 3)),
                  divideByTotalMoveabilityAndProcessedResonance)
              << 2;
    }
    return temp;
  }

  private int do12dBLPFOnSample(int input, LpLadderState state) {
    int noise = FirmwareUtils.getNoise() >> 2;
    int distanceToGo = noise - state.noiseLastValue;
    state.noiseLastValue += distanceToGo >> 7;
    int noisy_m = moveability + multiply_32x32_rshift32(moveability, state.noiseLastValue);

    int feedbacksSum =
        state.lpfLPF1.getFeedbackOutput(lpf1Feedback)
            + state.lpfLPF2.getFeedbackOutput(lpf2Feedback)
            + state.lpfLPF3.getFeedbackOutput(divideBy1PlusTannedFrequency);
    int x = scaleInput(input, feedbacksSum);

    return state.lpfLPF3.doAPF(
            state.lpfLPF2.doFilter(state.lpfLPF1.doFilter(x, noisy_m), noisy_m), noisy_m)
        << 1;
  }

  private int do24dBLPFOnSample(int input, LpLadderState state) {
    int noise = FirmwareUtils.getNoise() >> 2;
    int distanceToGo = noise - state.noiseLastValue;
    state.noiseLastValue += distanceToGo >> 7;
    int noisy_m = moveability + multiply_32x32_rshift32(moveability, state.noiseLastValue);

    int feedbacksSum =
        (state.lpfLPF1.getFeedbackOutputWithoutLshift(lpf1Feedback)
                + state.lpfLPF2.getFeedbackOutputWithoutLshift(lpf2Feedback)
                + state.lpfLPF3.getFeedbackOutputWithoutLshift(lpf3Feedback)
                + state.lpfLPF4.getFeedbackOutputWithoutLshift(divideBy1PlusTannedFrequency))
            << 2;

    int x = scaleInput(input, feedbacksSum);

    return state.lpfLPF4.doFilter(
            state.lpfLPF3.doFilter(
                state.lpfLPF2.doFilter(state.lpfLPF1.doFilter(x, noisy_m), noisy_m), noisy_m),
            noisy_m)
        << 1;
  }

  private int doDriveLPFOnSample(int input, LpLadderState state) {
    int noise = FirmwareUtils.getNoise() >> 2;
    int distanceToGo = noise - state.noiseLastValue;
    state.noiseLastValue += distanceToGo >> 7;
    int noisy_m = moveability + multiply_32x32_rshift32(moveability, state.noiseLastValue);

    int feedbacksSum =
        (state.lpfLPF1.getFeedbackOutputWithoutLshift(lpf1Feedback)
                + state.lpfLPF2.getFeedbackOutputWithoutLshift(lpf2Feedback)
                + state.lpfLPF3.getFeedbackOutputWithoutLshift(lpf3Feedback)
                + state.lpfLPF4.getFeedbackOutputWithoutLshift(divideBy1PlusTannedFrequency))
            << 2;

    feedbacksSum = FirmwareUtils.getTanHUnknown(feedbacksSum, 7);
    int x = scaleInput(input, feedbacksSum);

    int a = state.lpfLPF1.doFilter(x, noisy_m);
    int b = state.lpfLPF2.doFilter(a, noisy_m);
    int c = state.lpfLPF3.doFilter(b, noisy_m);
    int d = state.lpfLPF4.doFilter(c, noisy_m) << 1;

    return d;
  }
}
