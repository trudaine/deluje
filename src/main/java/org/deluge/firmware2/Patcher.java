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
    public int targetSource =
        -1; // if >= 0, this modulates the range/depth of targetSource -> targetParamId
    public int targetParamId = -1;

    public Destination(int paramId) {
      this.paramId = paramId;
    }
  }

  /** Patch cable: a source→destination modulation connection. */
  public static class PatchCable {
    public int source; // PatchSource ordinal
    public int amount; // Q31 strength
    public int polarity = BIPOLAR;
    public int rangeValue = 536870912; // dynamic range adjustment value (default 1.0 in Q30)
    public static final int BIPOLAR = 0;
    public static final int UNIPOLAR = 1;

    public int toPolarity(int srcVal) {
      if (polarity == UNIPOLAR) {
        return (int)
            (((long) srcVal + 2147483647L) >> 1); // convert bipolar to unipolar positive safely
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
      Sound sound, int[] sourceValues, PatchCableSet patchCableSet, int[] paramFinalValues) {
    java.util.List<Destination> dests = patchCableSet.destinations;
    // 1. Reset all cable range values to neutral
    for (int i = 0; i < dests.size(); i++) {
      Destination dest = dests.get(i);
      java.util.ArrayList<PatchCable> cables = dest.cables;
      for (int j = 0; j < cables.size(); j++) {
        cables.get(j).rangeValue = 536870912;
      }
    }

    // 2. Evaluate range/depth modulating destinations first
    for (int i = 0; i < dests.size(); i++) {
      Destination dest = dests.get(i);
      if (dest.targetSource >= 0) {
        int combo = combineCablesLinearForRangeParam(dest, sourceValues, null);
        int val = Functions.getFinalParameterValueLinear(536870912, combo);

        // Apply this range value to the target patch cable
        for (int k = 0; k < dests.size(); k++) {
          Destination targetDest = dests.get(k);
          if (targetDest.paramId == dest.targetParamId && targetDest.targetSource < 0) {
            java.util.ArrayList<PatchCable> targetCables = targetDest.cables;
            for (int l = 0; l < targetCables.size(); l++) {
              PatchCable targetCable = targetCables.get(l);
              if (targetCable.source == dest.targetSource) {
                targetCable.rangeValue = val;
              }
            }
          }
        }
      }
    }

    // 3. Evaluate normal parameter destinations
    for (int i = 0; i < dests.size(); i++) {
      Destination dest = dests.get(i);
      if (dest.targetSource >= 0) continue; // skip range destinations in this pass

      int p = dest.paramId;
      // C (patcher.cpp:118): the curve neutral is paramNeutralValues[param], which the C populates
      // as getParamNeutralValue(param) (functions.cpp:180) — a STATIC per-param constant, NOT the
      // knob.
      int staticNeutral = Functions.getParamNeutralValue(p);
      int finalValue;

      if (dest.cables.isEmpty()) continue; // no cables → knob stays as-is

      if (p < Param.FIRST_LOCAL_NON_VOLUME
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_NON_VOLUME)) {
        // VOLUME
        int combo = combineCablesLinear(dest, sound.getSmoothedPatchedParamValue(p), sourceValues);
        finalValue = Functions.getFinalParameterValueVolume(staticNeutral, combo);
      } else if (p < Param.FIRST_LOCAL_HYBRID
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
        // LINEAR
        int combo = combineCablesLinear(dest, sound.getSmoothedPatchedParamValue(p), sourceValues);
        finalValue = Functions.getFinalParameterValueLinear(staticNeutral, combo);
      } else if (p < Param.FIRST_LOCAL_EXP
          || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_EXP)) {
        // HYBRID
        int combo = combineCablesExp(dest, sound.getSmoothedPatchedParamValue(p), sourceValues);
        finalValue = Functions.getFinalParameterValueHybrid(staticNeutral, combo);
      } else {
        // EXP
        int combo = combineCablesExp(dest, sound.getSmoothedPatchedParamValue(p), sourceValues);
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
      runningTotal = cableToLinearParam(runningTotal, srcVal, cable.amount, cable.rangeValue);
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
          cableToExpParam(
              runningTotal,
              srcVal,
              getModifiedPatchCableAmount(cable, dest.paramId),
              cable.rangeValue);
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

  private static int cableToLinearParam(
      int runningTotal, int source, int strength, int rangeValue) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    scaledSource = applyRangeAdjustment(scaledSource, rangeValue);
    int madePositive = scaledSource + 536870912;
    int preLimits = Functions.multiply_32x32_rshift32(runningTotal, madePositive);
    return Functions.lshiftAndSaturate(preLimits, 3);
  }

  // ── cableToExpParam* (patcher.cpp:177-195) ──

  // C (patcher.cpp:164-173): both exp accumulators use PLAIN wrapping addition, not a
  // saturating add — saturating here diverges exactly when strong cables push a combo past
  // int range, which is when hardware wraps.
  private static int cableToExpParamWithoutRangeAdjustment(
      int runningTotal, int source, int strength) {
    return runningTotal + Functions.multiply_32x32_rshift32(source, strength);
  }

  private static int cableToExpParam(int runningTotal, int source, int strength, int rangeValue) {
    int scaledSource = Functions.multiply_32x32_rshift32(source, strength);
    scaledSource = applyRangeAdjustment(scaledSource, rangeValue);
    return runningTotal + scaledSource;
  }

  // ── applyRangeAdjustment (patcher.cpp) ──

  private static int applyRangeAdjustment(int value, int rangeValue) {
    int small = Functions.multiply_32x32_rshift32(value, rangeValue);
    return Functions.signed_saturate(small, 32 - 5) << 3;
  }

  // ── recalculateFinalValueForParamWithNoCables (patcher.cpp:30-62) ──

  /**
   * Port of recalculateFinalValueForParamWithNoCables. Runs an uncabled param's stored knob through
   * the correct firmware curve. (patcher.cpp:30-62)
   */
  public static void recalculateFinalValueForParamWithNoCables(
      int p, Sound sound, int[] sourceValues, int[] paramFinalValues) {
    // Uses which ever cable combine is appropriate for this param
    int cableCombination;
    if (p < Param.FIRST_LOCAL_HYBRID
        || (p >= Param.FIRST_GLOBAL && p < Param.FIRST_GLOBAL_HYBRID)) {
      // Linear family: fold knob through combineCablesLinear(nullptr)
      int runningTotal = 536870912;
      int range = Functions.getParamRange(p);
      runningTotal =
          cableToLinearParamWithoutRangeAdjustment(
              runningTotal, sound.getSmoothedPatchedParamValue(p), range);
      cableCombination = runningTotal - 536870912;
    } else {
      // Exp family: fold knob through combineCablesExp(nullptr)
      int range = Functions.getParamRange(p);
      cableCombination =
          cableToExpParamWithoutRangeAdjustment(0, sound.getSmoothedPatchedParamValue(p), range);
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
      // Special exception: If we're patching aftertouch to range
      if (cable.source == PatchSource.AFTERTOUCH.ordinal()) {
        srcVal = (srcVal - 1073741824) << 1;
      } else {
        srcVal = cable.toPolarity(srcVal);
      }
      int strength = cable.amount;
      runningTotal = cableToLinearParamWithoutRangeAdjustment(runningTotal, srcVal, strength);
    }
    return runningTotal - 536870912;
  }

  /**
   * getModifiedPatchCableAmount (patch_cable_set.cpp:500-534). For pitch-adjust + delay-rate
   * params, square the cable strength so it slopes up slowly (small effects accessible). Used only
   * by exp cables (combineCablesExp). {@code amount} is cable.amount (the fw2 stand-in for the C
   * {@code patch_cable.param.getCurrentValue()}; automation smoothing is a separate gap).
   */
  public static int getModifiedPatchCableAmount(PatchCable cable, int p) {
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
   * Port of performInitialPatching (patcher.cpp:275-339): every param is first computed as if
   * unpatched, then the params that DO have cables are overwritten with the real cable combination
   * (using the source values the caller set up beforehand — note, velocity, random, local LFOs,
   * globals). The overwrite pass is the same per-destination combine+finalize the per-block
   * performPatching does.
   */
  public static void performInitialPatching(
      Sound sound, int[] sourceValues, int[] paramFinalValues) {
    for (int p = 0; p < Param.kNumParams; p++) {
      recalculateFinalValueForParamWithNoCables(p, sound, sourceValues, paramFinalValues);
    }
    performPatching(sound, sourceValues, sound.patchCableSet, paramFinalValues);
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
