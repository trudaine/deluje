package org.deluge.firmware2;

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

  public static class PatchCableSet {
    public volatile java.util.List<Destination> destinations = new java.util.ArrayList<>();

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
    // paramFinalValues must already hold the curve-applied knob values for ALL params (call
    // performInitialPatching first). This applies cable modulation on top, overwriting only the
    // cabled (destination) params and leaving non-cabled params at their curve-applied base.
    for (Destination dest : patchCableSet.destinations) {
      int p = dest.paramId;
      // C (patcher.cpp:118): the curve neutral is paramNeutralValues[param], which the C populates
      // as
      // getParamNeutralValue(param) (functions.cpp:180) — a STATIC per-param constant, NOT the
      // knob.
      // The knob (getSmoothedPatchedParamValue) is folded into the cable combination below instead.
      int staticNeutral = Functions.getParamNeutralValue(p);
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
  public static int combineCablesLinear(Destination dest, int knobValue, int[] sourceValues) {
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
    // Cables first. Exp cables take the *modified* (per-param squared) strength (patcher.cpp:253).
    for (PatchCable cable : dest.cables) {
      int srcVal = sourceValues[cable.source];
      srcVal = cable.toPolarity(srcVal);
      runningTotal =
          cableToExpParam(runningTotal, srcVal, getModifiedPatchCableAmount(cable, dest.paramId));
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
    return Functions.add_saturate(
        runningTotal, Functions.multiply_32x32_rshift32(source, strength));
  }

  private static int cableToExpParam(int runningTotal, int source, int strength) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    scaledSource = applyRangeAdjustment(scaledSource);
    return Functions.add_saturate(runningTotal, scaledSource);
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

    // C (patcher.cpp:37): param_neutral_value = paramNeutralValues[p] = getParamNeutralValue(p)
    // (static per-param constant). The knob is already folded into cableCombination above.
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
      // C (patcher.cpp:198): cable_strength = patch_cable.param.getCurrentValue() (the RAW current
      // value, NOT getModifiedPatchCableAmount — that's only for exp cables). cable.amount is the
      // fw2 stand-in for getCurrentValue (automation smoothing is the separate documented gap).
      int strength = cable.amount;
      runningTotal = cableToLinearParam(runningTotal, srcVal, strength);
    }
    return Functions.getFinalParameterValueLinear(536870912, runningTotal - 536870912);
  }

  /**
   * getModifiedPatchCableAmount (patch_cable_set.cpp:500-534). For pitch-adjust + delay-rate
   * params, square the cable strength so it slopes up slowly (small effects accessible). Used only
   * by exp cables (combineCablesExp). {@code amount} is cable.amount (the fw2 stand-in for the C
   * {@code patch_cable.param.getCurrentValue()}; automation smoothing is a separate gap).
   */
  static int getModifiedPatchCableAmount(PatchCable cable, int p) {
    int amount = cable.amount;
    switch (p) {
      case Param.LOCAL_PITCH_ADJUST:
      case Param.LOCAL_OSC_A_PITCH_ADJUST:
      case Param.LOCAL_OSC_B_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_0_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_1_PITCH_ADJUST:
      case Param.GLOBAL_DELAY_RATE:
        int output = (amount >> 15) * (amount >> 16);
        if (amount < 0) {
          output = -output;
        }
        if (p == Param.LOCAL_PITCH_ADJUST) {
          if (cable.source == PatchSource.VELOCITY.ordinal()) {
            output = Functions.multiply_32x32_rshift32_rounded(output, 1431655765) << 1; // /3*2
          } else {
            // Divides by sqrt(2): 3 octaves of shifting rather than 4.
            output = Functions.multiply_32x32_rshift32_rounded(output, 1518500250) << 1;
          }
        }
        return output;
      default:
        return amount;
    }
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

  /**
   * Convenience: curve-apply a single param's knob value (no cables), returning the final param
   * value. C: {@code recalculateFinalValueForParamWithNoCables} with no destination.
   * (patcher.cpp:30-57)
   */
  public static int computeFinalValueForParam(int p, int knobValue) {
    int cableCombination;
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      int runningTotal = 536870912;
      int range = Functions.getParamRange(p);
      runningTotal = cableToLinearParamWithoutRangeAdjustment(runningTotal, knobValue, range);
      cableCombination = runningTotal - 536870912;
    } else {
      int range = Functions.getParamRange(p);
      cableCombination = cableToExpParamWithoutRangeAdjustment(0, knobValue, range);
    }

    int neutral =
        Functions.getParamNeutralValue(p); // C: paramNeutralValues[p]; knob is in cableCombination
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      if (p < Param.FIRST_LOCAL_NON_VOLUME
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_NON_VOLUME)) {
        return Functions.getFinalParameterValueVolume(neutral, cableCombination);
      } else {
        return Functions.getFinalParameterValueLinear(neutral, cableCombination);
      }
    } else {
      if (p < Param.FIRST_LOCAL_EXP || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_EXP)) {
        return Functions.getFinalParameterValueHybrid(neutral, cableCombination);
      } else {
        return Functions.getFinalParameterValueExpWithDumbEnvelopeHack(
            neutral, cableCombination, p);
      }
    }
  }

  private static int getEnvStage(int p) {
    if (p >= Param.LOCAL_ENV_0_ATTACK && p <= Param.LOCAL_ENV_3_ATTACK) return 0; // attack
    if (p >= Param.LOCAL_ENV_0_DECAY && p <= Param.LOCAL_ENV_3_DECAY) return 1; // decay
    if (p >= Param.LOCAL_ENV_0_RELEASE && p <= Param.LOCAL_ENV_3_RELEASE) return 2; // release
    return -1;
  }
}
