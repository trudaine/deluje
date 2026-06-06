package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code filter.h} Filter base class + setConfig, and
 * {@code lpladder.cpp} LpLadderFilter. The firmware uses CRTP (Curiously Recurring Template
 * Pattern) to dispatch between ladder/SVF filter types; in Java we use direct method calls.
 *
 * <p>The filter converts a Q31 frequency param (from getExp/patcher) to an internal
 * tan(f)-based coefficient via {@code curveFrequency(freq)}.
 */
public final class FilterSet {

  private FilterSet() {}

  // ── Filter mode enum (lpladder.cpp + filter.h) ──

  public enum FilterMode {
    TRANSISTOR_12DB,
    TRANSISTOR_24DB,
    TRANSISTOR_24DB_DRIVE,
    SVF_BAND,
    SVF_NOTCH,
    HPLADDER,
    OFF
  }

  // ── LpLadderFilter state (port of LpLadderFilter class fields + filter.h fields) ──

  public static class LpLadderState {
    // Filter internals (filter.h:138-141)
    public int fc;
    public float dryFade = 1.0f;
    public int wetLevel = Functions.ONE_Q31;
    public int tannedFrequency;
    public int divideBy1PlusTannedFrequency;

    // Ladder coefficients (lpladder.cpp + lpladder.h)
    public FilterMode lpfMode = FilterMode.TRANSISTOR_24DB;
    public int processedResonance;
    public int divideByTotalMoveabilityAndProcessedResonance;
    public int moveability;
    public int morph;
    public int lpf1Feedback;
    public int lpf2Feedback;
    public int lpf3Feedback;
    public boolean doOversampling;

    // Per-channel one-pole states (4-pole ladder + noise)
    public final BasicFilterComponent lpfLPF1 = new BasicFilterComponent();
    public final BasicFilterComponent lpfLPF2 = new BasicFilterComponent();
    public final BasicFilterComponent lpfLPF3 = new BasicFilterComponent();
    public final BasicFilterComponent lpfLPF4 = new BasicFilterComponent();
    public int noiseLastValue;

    // HPF state (for HpLadder)
    public boolean isHpf;
  }

  // ── curveFrequency (filter.h:128-136) ──

  /**
   * Applies a pleasing curve to the linear frequency from the knob.
   * Stores tan(f) and 1/(1+tan(f)) for use in further calculations.
   * (filter.h:128-136)
   */
  public static void curveFrequency(LpLadderState s, int frequency) {
    // Between 0 and 8, by my making. 1 represented by 268435456
    // tannedFrequency = instantTan(lshiftAndSaturate<5>(frequency));
    s.tannedFrequency = Functions.instantTan(Functions.lshiftAndSaturate(frequency, 5));

    // this is 1q31*1q16/(1q16+tan(f)/2)
    // tan(f) is q17
    // divideBy1PlusTannedFrequency = (q31_t)(288230376151711744.0 / (double)(ONE_Q16 + (tannedFrequency >> 1)));
    double denom = (double) (Functions.ONE_Q16 + (s.tannedFrequency >> 1));
    s.divideBy1PlusTannedFrequency = (int) (288230376151711744.0 / denom);

    // fc = multiply_32x32_rshift32_rounded(tannedFrequency, divideBy1PlusTannedFrequency) << 4;
    s.fc = Functions.multiply_32x32_rshift32_rounded(
        s.tannedFrequency, s.divideBy1PlusTannedFrequency) << 4;
  }

  // ── LpLadderFilter.setConfig (lpladder.cpp:53-171) ──

  /**
   * Port of LpLadderFilter::setConfig.  Computes filter coefficients from Q31 frequency,
   * resonance, mode, morph and filter gain.  Returns the adjusted filter gain (makeup).
   * (lpladder.cpp:53-171)
   */
  public static int lpLadderSetConfig(LpLadderState s, int lpfFrequency, int lpfResonance,
      FilterMode mode, int lpfMorph, int filterGain) {
    s.lpfMode = mode;
    s.morph = lpfMorph;

    // Hot transistor ladder — needs oversampling and stuff
    if (mode == FilterMode.TRANSISTOR_24DB_DRIVE) {
      // int32_t resonance = ONE_Q31 - (lpfResonance << 2); // Limits it
      int resonance = Functions.ONE_Q31 - (lpfResonance << 2);
      // processedResonance = ONE_Q31 - resonance; // Always between 0 and 2; 1 represented as 1073741824
      s.processedResonance = Functions.ONE_Q31 - resonance;

      int logFreq = Functions.quickLog(lpfFrequency);
      s.doOversampling = false;
      // logFreq = std::min(logFreq, (int32_t)63 << 24);
      logFreq = Math.min(logFreq, 63 << 24);

      // if (AudioEngine::cpuDireness < 14 && (logFreq >> 24) > 51)
      if ((logFreq >> 24) > 51) {
        int resonanceThreshold = Functions.interpolateTableSigned(
            logFreq, 30, LookupTables.resonanceThresholdsForOversampling, 6);
        s.doOversampling = (s.processedResonance > resonanceThreshold);
      }

      if (s.doOversampling) {
        // lpfFrequency >>= 1;
        lpfFrequency >>= 1;
        // logFreq -= 33554432;
        logFreq -= 33554432;
        // lpfFrequency -= (multiply_32x32_rshift32_rounded(logFreq, lpfFrequency) >> 8) * 34;
        lpfFrequency -= (Functions.multiply_32x32_rshift32_rounded(logFreq, lpfFrequency) >> 8) * 34;
        // lpfFrequency = std::min((int32_t)39056384, lpfFrequency);
        lpfFrequency = Math.min(39056384, lpfFrequency);

        int resonanceLimit = Functions.interpolateTableSigned(
            logFreq, 30, LookupTables.resonanceLimitTable, 6);
        s.processedResonance = Math.min(s.processedResonance, resonanceLimit);
      }
    }

    // Cold transistor ladder
    if (mode == FilterMode.TRANSISTOR_24DB || mode == FilterMode.TRANSISTOR_12DB) {
      int howMuchToKeep = Functions.ONE_Q31 - 33; // was - 1 * 33
      int resonanceUpperLimit = 510000000;
      // int32_t resonance = ONE_Q31 - (std::min(lpfResonance, resonanceUpperLimit) << 2);
      int resonance = Functions.ONE_Q31
          - (Math.min(lpfResonance, resonanceUpperLimit) << 2);
      // resonance = multiply_32x32_rshift32_rounded(resonance, resonance) << 1;
      resonance = Functions.multiply_32x32_rshift32_rounded(resonance, resonance) << 1;
      // processedResonance = ONE_Q31 - resonance;
      s.processedResonance = Functions.ONE_Q31 - resonance;
      // processedResonance = multiply_32x32_rshift32_rounded(processedResonance, howMuchToKeep) << 1;
      s.processedResonance = Functions.multiply_32x32_rshift32_rounded(
          s.processedResonance, howMuchToKeep) << 1;
    }

    curveFrequency(s, lpfFrequency);
    // moveability = std::max(fc, (q31_t)4317840);
    s.moveability = Math.max(s.fc, 4317840);

    // Half ladder (12 dB)
    if (mode == FilterMode.TRANSISTOR_12DB) {
      int moveabilityNegative = s.moveability - 1073741824;
      // lpf2Feedback = multiply_32x32_rshift32_rounded(moveabilityNegative, divideBy1PlusTannedFrequency) << 1;
      s.lpf2Feedback = Functions.multiply_32x32_rshift32_rounded(
          moveabilityNegative, s.divideBy1PlusTannedFrequency) << 1;
      // lpf1Feedback = multiply_32x32_rshift32_rounded(lpf2Feedback, moveability) << 1;
      s.lpf1Feedback = Functions.multiply_32x32_rshift32_rounded(
          s.lpf2Feedback, s.moveability) << 1;
      // long denominator formula
      long denom = 67108864L
          + Functions.multiply_32x32_rshift32_rounded(
              s.processedResonance,
              Functions.multiply_32x32_rshift32_rounded(
                  moveabilityNegative,
                  Functions.multiply_32x32_rshift32_rounded(s.moveability, s.moveability)));
      // divideByTotalMoveabilityAndProcessedResonance = (int64_t)67108864 * 1073741824 / ...
      s.divideByTotalMoveabilityAndProcessedResonance =
          (int) (67108864L * 1073741824L / denom);
    } else {
      // Full ladder (24 dB)
      // lpf3Feedback = multiply_32x32_rshift32_rounded(divideBy1PlusTannedFrequency, moveability);
      s.lpf3Feedback = Functions.multiply_32x32_rshift32_rounded(
          s.divideBy1PlusTannedFrequency, s.moveability);
      // lpf2Feedback = multiply_32x32_rshift32_rounded(lpf3Feedback, moveability) << 1;
      s.lpf2Feedback = Functions.multiply_32x32_rshift32_rounded(
          s.lpf3Feedback, s.moveability) << 1;
      // lpf1Feedback = multiply_32x32_rshift32_rounded(lpf2Feedback, moveability) << 1;
      s.lpf1Feedback = Functions.multiply_32x32_rshift32_rounded(
          s.lpf2Feedback, s.moveability) << 1;
      // onePlusThing = 67108864 + moveability^4 * processedResonance
      long onePlusThing = 67108864L
          + Functions.multiply_32x32_rshift32_rounded(
              s.moveability,
              Functions.multiply_32x32_rshift32_rounded(
                  s.moveability,
                  Functions.multiply_32x32_rshift32_rounded(
                      s.moveability,
                      Functions.multiply_32x32_rshift32_rounded(
                          s.moveability, s.processedResonance))));
      // divideByTotalMoveabilityAndProcessedResonance = (q31_t)(72057594037927936.0 / (double)onePlusThing);
      s.divideByTotalMoveabilityAndProcessedResonance =
          (int) (72057594037927936.0 / (double) onePlusThing);
    }

    // Cold transistor ladder: extra resonance boost / cut
    if (mode != FilterMode.TRANSISTOR_24DB_DRIVE) {
      // if (tannedFrequency <= 304587486)
      if (s.tannedFrequency <= 304587486) {
        s.processedResonance = Functions.multiply_32x32_rshift32_rounded(
            s.processedResonance, 1150000000) << 1;
      } else {
        s.processedResonance >>= 1;
      }
      // Gain modifier from resonance
      int a = Math.min(lpfResonance, 536870911);
      a = 536870912 - a;
      a = Functions.multiply_32x32_rshift32(a, a) << 3;
      a = 536870912 - a;
      int gainModifier = 268435456 + a;
      filterGain = Functions.multiply_32x32_rshift32(filterGain, gainModifier) << 3;
    } else {
      // Drive filter — reduce output amplitude
      filterGain = (int) (filterGain * 0.8f);
    }
    return filterGain;
  }

  // ── Per-sample ladder filter (lpladder.cpp do24dBLPFOnSample) ──

  /**
   * Port of do24dBLPFOnSample.  One sample through the 4-pole transistor ladder.
   * (lpladder.cpp ~187-194, using inline helper from voice.cpp)
   */
  public static int do24dBLPFOnSample(LpLadderState s, int input) {
    // Noise modulation of moveability (voice.cpp do24dBLPFOnSample)
    int noise = Functions.getNoise() >> 2;
    int distanceToGo = noise - s.noiseLastValue;
    s.noiseLastValue += distanceToGo >> 7;
    int noisyM = s.moveability
        + Functions.multiply_32x32_rshift32(s.moveability, s.noiseLastValue);

    // Sum feedback from all 4 poles
    int sum = Functions.add_saturate(
        Functions.add_saturate(
            s.lpfLPF1.getFeedbackOutputWithoutLshift(s.lpf1Feedback),
            s.lpfLPF2.getFeedbackOutputWithoutLshift(s.lpf2Feedback)),
        Functions.add_saturate(
            s.lpfLPF3.getFeedbackOutputWithoutLshift(s.lpf3Feedback),
            s.lpfLPF4.getFeedbackOutputWithoutLshift(s.divideBy1PlusTannedFrequency)));
    int feedbacksSum = Functions.lshiftAndSaturate(sum, 2);

    // scaleInput (port of scaleInput from voice.cpp)
    int resonanceFeedback = Functions.lshiftAndSaturate(
        Functions.multiply_32x32_rshift32_rounded(feedbacksSum, s.processedResonance), 3);
    int subNode = Functions.sub_saturate(input, resonanceFeedback);

    int temp;
    if (s.morph > 0 || s.processedResonance > 510000000) {
      temp = Functions.lshiftAndSaturate(
          Functions.multiply_32x32_rshift32_rounded(
              subNode, s.divideByTotalMoveabilityAndProcessedResonance), 2);
      int extra = 2 * Functions.multiply_32x32_rshift32(input, s.morph);
      temp = Functions.getTanHUnknown(Functions.add_saturate(temp, extra), 2);
    } else {
      temp = Functions.lshiftAndSaturate(
          Functions.multiply_32x32_rshift32_rounded(
              subNode, s.divideByTotalMoveabilityAndProcessedResonance), 2);
    }

    // Run through 4 cascade one-poles
    return Functions.lshiftAndSaturate(
        s.lpfLPF4.doFilter(
            s.lpfLPF3.doFilter(
                s.lpfLPF2.doFilter(
                    s.lpfLPF1.doFilter(temp, noisyM), noisyM), noisyM), noisyM), 1);
  }

  // Note: getTanHUnknown and getNoise need to be added to Functions.java
  // These are simple: getNoise = LCG (jcong = 69069*jcong+1234567), getTanHUnknown = table lookup
}
