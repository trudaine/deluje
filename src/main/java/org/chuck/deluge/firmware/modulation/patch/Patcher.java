package org.chuck.deluge.firmware.modulation.patch;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.params.ParamCurves;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of the Deluge's Patcher class. Implements 1:N modulation routing with bit-accurate linear,
 * hybrid, and exponential mapping.
 */
public class Patcher {

  public static int exponentialModShift = 0;

  private enum ParamType {
    VOLUME,
    LINEAR,
    HYBRID,
    EXP
  }

  private static ParamType getParamType(int p) {
    if (p < Param.FIRST_GLOBAL) {
      if (p < Param.FIRST_LOCAL_NON_VOLUME) return ParamType.VOLUME;
      if (p < Param.FIRST_LOCAL_HYBRID) return ParamType.LINEAR;
      if (p < Param.FIRST_LOCAL_EXP) return ParamType.HYBRID;
      return ParamType.EXP;
    } else {
      if (p < Param.FIRST_GLOBAL_NON_VOLUME) return ParamType.VOLUME;
      if (p < Param.FIRST_GLOBAL_HYBRID) return ParamType.LINEAR;
      if (p < Param.FIRST_GLOBAL_EXP) return ParamType.HYBRID;
      return ParamType.EXP;
    }
  }

  private static int getEnvStage(int p) {
    if (p >= Param.LOCAL_ENV_0_ATTACK && p <= Param.LOCAL_ENV_3_ATTACK) {
      return 0; // attack
    }
    if (p >= Param.LOCAL_ENV_0_DECAY && p <= Param.LOCAL_ENV_3_DECAY) {
      return 1; // decay
    }
    if (p >= Param.LOCAL_ENV_0_RELEASE && p <= Param.LOCAL_ENV_3_RELEASE) {
      return 2; // release
    }
    return -1;
  }

  public void performPatching(
      int sourcesChangedMask,
      FirmwareSound sound,
      ParamManager paramManager,
      int[] sourceValues,
      int[] paramFinalValues) {
    PatchCableSet patchCableSet = paramManager.getPatchCableSet();

    // ── Bit-Accurate Patching Loop ──
    for (int paramId = 0; paramId < Param.kNumParams; paramId++) {
      Destination dest = null;
      for (Destination d : patchCableSet.destinations) {
        if (d.paramId == paramId) {
          dest = d;
          break;
        }
      }

      int staticNeutral = ParamCurves.getParamNeutralValue(paramId);
      int finalValue = 0;
      ParamType type = getParamType(paramId);

      if (type == ParamType.VOLUME || type == ParamType.LINEAR) {
        int combo = combineCablesLinear(dest, paramId, sound, paramManager, sourceValues);
        if (type == ParamType.VOLUME) {
          finalValue =
              Q31.lshiftAndSaturate(
                  FirmwareUtils.getFinalParameterValueVolume(staticNeutral, combo), 2);
        } else {
          finalValue = FirmwareUtils.getFinalParameterValueLinear(staticNeutral, combo);
        }
      } else {
        int combo = combineCablesExp(dest, paramId, sound, paramManager, sourceValues);
        if (type == ParamType.HYBRID) {
          finalValue = FirmwareUtils.getFinalParameterValueHybrid(staticNeutral, combo);
        } else {
          int stage = getEnvStage(paramId);
          if (stage != -1) {
            finalValue = FirmwareUtils.finalEnvRateParam(staticNeutral, combo, stage);
          } else {
            finalValue = FirmwareUtils.getFinalParameterValueExp(staticNeutral, combo);
          }
        }
      }

      paramFinalValues[paramId] = finalValue;
    }
  }

  private int combineCablesLinear(
      Destination dest, int p, FirmwareSound sound, ParamManager paramManager, int[] sourceValues) {
    int runningTotal = 536870912; // 1 in Q30

    // Fold in the preset value (knob/automated value)
    int knobValue = sound.paramNeutralValues[p];
    org.chuck.deluge.firmware.modulation.automation.AutoParam autoParam =
        paramManager.getAutomatedParam(p);
    if (autoParam != null) {
      knobValue = autoParam.currentValue;
    }
    int range = ParamCurves.getParamRange(p);
    runningTotal = cableToLinearParamWithoutRangeAdjustment(runningTotal, knobValue, range);

    if (dest != null) {
      for (PatchCable cable : dest.cables) {
        int srcVal = sourceValues[cable.from.ordinal()];
        srcVal = cable.toPolarity(srcVal);
        int strength = cable.getAmount();
        runningTotal = cableToLinearParam(runningTotal, cable, srcVal, strength);
      }
    }

    return runningTotal - 536870912;
  }

  private int combineCablesExp(
      Destination dest, int p, FirmwareSound sound, ParamManager paramManager, int[] sourceValues) {
    int runningTotal = 0;

    if (dest != null) {
      for (PatchCable cable : dest.cables) {
        int srcVal = sourceValues[cable.from.ordinal()];
        srcVal = cable.toPolarity(srcVal);
        int strength = getModifiedPatchCableAmount(cable, p);
        runningTotal = cableToExpParam(runningTotal, cable, srcVal, strength);
      }

      if (p == Param.LOCAL_OSC_A_WAVE_INDEX || p == Param.LOCAL_OSC_B_WAVE_INDEX) {
        runningTotal <<= 1;
      }
    }

    int knobValue = sound.paramNeutralValues[p];
    org.chuck.deluge.firmware.modulation.automation.AutoParam autoParam =
        paramManager.getAutomatedParam(p);
    if (autoParam != null) {
      knobValue = autoParam.currentValue;
    }
    int range = ParamCurves.getParamRange(p);
    runningTotal = cableToExpParamWithoutRangeAdjustment(runningTotal, knobValue, range);

    return runningTotal;
  }

  private int cableToLinearParamWithoutRangeAdjustment(
      int runningTotal, int sourceValue, int cableStrength) {
    int scaledSource = Q31.multiply_32x32_rshift32(sourceValue, cableStrength);
    int madePositive = scaledSource + 536870912;
    int preLimits = Q31.multiply_32x32_rshift32(runningTotal, madePositive);
    return Q31.lshiftAndSaturate(preLimits, 3);
  }

  private int cableToLinearParam(
      int runningTotal, PatchCable patchCable, int sourceValue, int cableStrength) {
    int scaledSource = Q31.multiply_32x32_rshift32(sourceValue, cableStrength);
    scaledSource = applyRangeAdjustment(scaledSource);
    int madePositive = scaledSource + 536870912;
    int preLimits = Q31.multiply_32x32_rshift32(runningTotal, madePositive);
    return Q31.lshiftAndSaturate(preLimits, 3);
  }

  private int cableToExpParamWithoutRangeAdjustment(
      int runningTotal, int sourceValue, int cableStrength) {
    return runningTotal + Q31.multiply_32x32_rshift32(sourceValue, cableStrength);
  }

  private int cableToExpParam(
      int runningTotal, PatchCable patchCable, int sourceValue, int cableStrength) {
    int scaledSource = Q31.multiply_32x32_rshift32(sourceValue, cableStrength);
    scaledSource = applyRangeAdjustment(scaledSource);
    return runningTotal + scaledSource;
  }

  private int applyRangeAdjustment(int value) {
    int small = Q31.multiply_32x32_rshift32(value, 536870912);
    return Q31.signedSaturate(small, 32 - 5) << 3;
  }

  private int getModifiedPatchCableAmount(PatchCable cable, int p) {
    int amount = cable.getAmount();
    if (p == Param.LOCAL_PITCH_ADJUST
        || p == Param.LOCAL_OSC_A_PITCH_ADJUST
        || p == Param.LOCAL_OSC_B_PITCH_ADJUST
        || p == Param.LOCAL_MODULATOR_0_PITCH_ADJUST
        || p == Param.LOCAL_MODULATOR_1_PITCH_ADJUST
        || p == Param.GLOBAL_DELAY_RATE) {
      long output = ((long) (amount >> 15)) * (amount >> 16);
      if (amount < 0) {
        output = -output;
      }
      if (p == Param.LOCAL_PITCH_ADJUST) {
        if (cable.from == PatchSource.VELOCITY) {
          output = Q31.multiply_32x32_rshift32_rounded((int) output, 1431655765) << 1;
        } else {
          output = Q31.multiply_32x32_rshift32_rounded((int) output, 1518500250) << 1;
        }
      }
      return (int) output;
    }
    return amount;
  }
}
