package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge {@code patcher.cpp} / {@code patcher.h} Patcher class.
 * Combines patch cables (source→destination with strength) and runs the stored param knob through
 * the correct firmware curve to produce per-voice {@code paramFinalValues}.
 *
 * <p>The firmware dispatching logic:
 *
 * <ul>
 *   <li>Volume params ({@code p < FIRST_LOCAL_NON_VOLUME}) → getFinalParameterValueVolume
 *   <li>Linear params → getFinalParameterValueLinear
 *   <li>Hybrid params → getFinalParameterValueHybrid
 *   <li>Exp params → getFinalParameterValueExp, with envelope-rate override
 * </ul>
 *
 * <p>Firmware reference: {@code patcher.cpp} lines 20-220.
 */
public class Patcher {

  /** Destination: a param with associated patch cables. */
  public static class Destination {
    public int paramId;
    public int sourcesMask; // bitmask of active source types
    public final java.util.ArrayList<PatchCable> cables = new java.util.ArrayList<>();

    public Destination(int paramId) {
      this.paramId = paramId;
    }
  }

  /** Patch cable: a source→destination modulation connection. */
  public static class PatchCable {
    public int source; // PatchSource ordinal
    public int amount; // Q31 strength
    public int polarity = BIPOLAR;
    public static final int BIPOLAR = 0;
    public static final int UNIPOLAR = 1;

    public int toPolarity(int srcVal) {
      if (polarity == UNIPOLAR) {
        return (srcVal + 2147483647) >> 1; // convert bipolar to unipolar positive
      }
      return srcVal;
    }
  }

  /** Patch cable set: a collection of destinations. */
  public static class PatchCableSet {
    public final java.util.ArrayList<Destination> destinations = new java.util.ArrayList<>();

    public void addCable(int paramId, PatchCable cable) {
      Destination dest = null;
      for (Destination d : destinations) {
        if (d.paramId == paramId) {
          dest = d;
          break;
        }
      }
      if (dest == null) {
        dest = new Destination(paramId);
        destinations.add(dest);
      }
      dest.cables.add(cable);
      dest.sourcesMask |= (1 << cable.source);
    }
  }

  // ── performPatching (patcher.cpp:20-120) ──

  /**
   * Combine stored knobs + patch cables for all params and produce final values.
   * (patcher.cpp:20-120, adapted for full-param pass)
   *
   * @param knobValues stored/automated knob values per param (Q31)
   * @param sourceValues current source values (envelope, LFO, velocity, etc.)
   * @param patchCableSet the cable set for this sound
   * @param paramFinalValues output: final param values after curve application
   */
  public static void performPatching(
      int[] knobValues, int[] sourceValues, PatchCableSet patchCableSet, int[] paramFinalValues) {
    System.arraycopy(
        knobValues, 0, paramFinalValues, 0, Math.min(knobValues.length, paramFinalValues.length));

    for (Destination dest : patchCableSet.destinations) {
      int p = dest.paramId;
      // Determine curve type from param index
      int staticNeutral = Functions.getParamNeutralValue(p); // curve neutral
      int finalValue;

      if (dest.cables.isEmpty()) continue; // no cables → knob stays as-is

      if (p < Param.FIRST_LOCAL_NON_VOLUME
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_NON_VOLUME)) {
        // VOLUME
        int combo = combineCablesLinear(dest, knobValues[p], sourceValues);
        finalValue = Functions.getFinalParameterValueVolume(staticNeutral, combo);
      } else if (p < Param.FIRST_LOCAL_HYBRID
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
        // LINEAR
        int combo = combineCablesLinear(dest, knobValues[p], sourceValues);
        finalValue = Functions.getFinalParameterValueLinear(staticNeutral, combo);
      } else if (p < Param.FIRST_LOCAL_EXP
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_EXP)) {
        // HYBRID
        int combo = combineCablesExp(dest, knobValues[p], sourceValues);
        finalValue = Functions.getFinalParameterValueHybrid(staticNeutral, combo);
      } else {
        // EXP
        int combo = combineCablesExp(dest, knobValues[p], sourceValues);
        int stage = getEnvStage(p);
        if (stage != -1) {
          finalValue =
              Functions.getFinalParameterValueExpWithDumbEnvelopeHack(staticNeutral, combo, p);
        } else {
          finalValue = Functions.getFinalParameterValueExp(staticNeutral, combo);
        }
      }

      paramFinalValues[p] = finalValue;
    }
  }

  // ── combineCablesLinear (patcher.cpp:143-180) ──

  /**
   * Fold the stored knob into a multiplicative cable combination. (patcher.cpp combineCablesLinear
   * + cableToLinearParamWithoutRangeAdjustment)
   */
  private static int combineCablesLinear(Destination dest, int knobValue, int[] sourceValues) {
    int runningTotal = 536870912; // "1" in Q30
    int range = Functions.getParamRange(dest.paramId);
    // First fold: the stored knob value (treated as a cable)
    runningTotal = cableToLinearParamWithoutRangeAdjustment(runningTotal, knobValue, range);
    // Then each cable
    for (PatchCable cable : dest.cables) {
      int srcVal = sourceValues[cable.source];
      srcVal = cable.toPolarity(srcVal);
      runningTotal = cableToLinearParam(runningTotal, srcVal, cable.amount);
    }
    return runningTotal - 536870912; // return in [-2^29, 3*2^29]
  }

  // ── combineCablesExp (patcher.cpp:220-245) ──

  private static int combineCablesExp(Destination dest, int knobValue, int[] sourceValues) {
    int runningTotal = 0;
    // Cables first
    for (PatchCable cable : dest.cables) {
      int srcVal = sourceValues[cable.source];
      srcVal = cable.toPolarity(srcVal);
      runningTotal = cableToExpParam(runningTotal, srcVal, cable.amount);
    }
    // Wave index hack: stretch twice as far
    if (dest.paramId == Param.LOCAL_OSC_A_WAVE_INDEX
        || dest.paramId == Param.LOCAL_OSC_B_WAVE_INDEX) {
      runningTotal <<= 1;
    }
    // Fold the knob last
    int range = Functions.getParamRange(dest.paramId);
    runningTotal = cableToExpParamWithoutRangeAdjustment(runningTotal, knobValue, range);
    return runningTotal;
  }

  // ── cableToLinearParam* (patcher.cpp:143-175) ──

  private static int cableToLinearParamWithoutRangeAdjustment(
      int runningTotal, int source, int strength) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    int madePositive = scaledSource + 536870912; // 0 to 2^30
    int preLimits = Functions.multiply_32x32_rshift32(runningTotal, madePositive);
    return Functions.lshiftAndSaturate(preLimits, 3);
  }

  private static int cableToLinearParam(int runningTotal, int source, int strength) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    scaledSource = applyRangeAdjustment(scaledSource);
    int madePositive = scaledSource + 536870912;
    int preLimits = Functions.multiply_32x32_rshift32(runningTotal, madePositive);
    return Functions.lshiftAndSaturate(preLimits, 3);
  }

  // ── cableToExpParam* (patcher.cpp:177-195) ──

  private static int cableToExpParamWithoutRangeAdjustment(
      int runningTotal, int source, int strength) {
    return runningTotal + Functions.multiply_32x32_rshift32(source, strength);
  }

  private static int cableToExpParam(int runningTotal, int source, int strength) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    scaledSource = applyRangeAdjustment(scaledSource);
    return runningTotal + scaledSource;
  }

  // ── applyRangeAdjustment (patcher.cpp) ──

  private static int applyRangeAdjustment(int value) {
    int small = Functions.multiply_32x32_rshift32(value, 536870912);
    return Functions.signed_saturate(small, 32 - 5) << 3;
  }

  // ── recalculateFinalValueForParamWithNoCables (patcher.cpp:30-62) ──

  /**
   * Port of recalculateFinalValueForParamWithNoCables. Runs an uncabled param's stored knob through
   * the correct firmware curve. (patcher.cpp:30-62)
   */
  public static void recalculateFinalValueForParamWithNoCables(
      int p, int[] knobValues, int[] sourceValues, int[] paramFinalValues) {
    // Uses which ever cable combine is appropriate for this param
    int cableCombination;
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      // Linear family: fold knob through combineCablesLinear(nullptr)
      int runningTotal = 536870912;
      int range = Functions.getParamRange(p);
      runningTotal = cableToLinearParamWithoutRangeAdjustment(runningTotal, knobValues[p], range);
      cableCombination = runningTotal - 536870912;
    } else {
      // Exp family: fold knob through combineCablesExp(nullptr)
      int range = Functions.getParamRange(p);
      cableCombination = cableToExpParamWithoutRangeAdjustment(0, knobValues[p], range);
    }

    int neutralValue = Functions.getParamNeutralValue(p);
    int finalValue;
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      if (p < Param.FIRST_LOCAL_NON_VOLUME
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_NON_VOLUME)) {
        finalValue = Functions.getFinalParameterValueVolume(neutralValue, cableCombination);
      } else {
        finalValue = Functions.getFinalParameterValueLinear(neutralValue, cableCombination);
      }
    } else {
      if (p < Param.FIRST_LOCAL_EXP || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_EXP)) {
        finalValue = Functions.getFinalParameterValueHybrid(neutralValue, cableCombination);
      } else {
        finalValue =
            Functions.getFinalParameterValueExpWithDumbEnvelopeHack(
                neutralValue, cableCombination, p);
      }
    }
    paramFinalValues[p] = finalValue;
  }

  // ── combineCablesLinearForRangeParam (patcher.cpp:175-208) ──

  /**
   * Port of combineCablesLinearForRangeParam. For range-type destinations that scale a source value
   * rather than a param. (patcher.cpp:175-208)
   */
  public static int combineCablesLinearForRangeParam(
      Destination destination, int[] sourceValues, ParamManager paramManager) {
    int runningTotal = 536870912;
    for (PatchCable cable : destination.cables) {
      int srcVal = sourceValues[cable.source];
      srcVal = cable.toPolarity(srcVal);
      int strength = cable.amount; // getModifiedPatchCableAmount simplified
      runningTotal = cableToLinearParam(runningTotal, srcVal, strength);
    }
    return Functions.getFinalParameterValueLinear(536870912, runningTotal - 536870912);
  }

  /** Minimal ParamManager stub for combineCablesLinearForRangeParam. */
  public static class ParamManager {}

  // ── performInitialPatching (patcher.cpp:275) ──

  /**
   * Port of performInitialPatching. Runs patching for all params during voice init.
   * (patcher.cpp:275-290)
   */
  public static void performInitialPatching(
      int[] knobValues, int[] sourceValues, int[] paramFinalValues) {
    for (int p = 0; p < Param.kNumParams; p++) {
      recalculateFinalValueForParamWithNoCables(p, knobValues, sourceValues, paramFinalValues);
    }
  }

  // ── getEnvStage (envelope rate dispatching) ──

  private static int getEnvStage(int p) {
    if (p >= Param.LOCAL_ENV_0_ATTACK && p <= Param.LOCAL_ENV_3_ATTACK) return 0; // attack
    if (p >= Param.LOCAL_ENV_0_DECAY && p <= Param.LOCAL_ENV_3_DECAY) return 1; // decay
    if (p >= Param.LOCAL_ENV_0_RELEASE && p <= Param.LOCAL_ENV_3_RELEASE) return 2; // release
    return -1;
  }
}
