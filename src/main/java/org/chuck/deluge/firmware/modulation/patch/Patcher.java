package org.chuck.deluge.firmware.modulation.patch;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.Q31;

public class Patcher {

  public void performPatching(
      int sourcesChangedMask,
      FirmwareSound sound,
      ParamManager paramManager,
      int[] sourceValues,
      int[] paramFinalValues) {
    PatchCableSet patchCableSet = paramManager.getPatchCableSet();

    // Simplified patching loop matching the essence of performPatching
    for (Destination dest : patchCableSet.destinations) {
      if ((dest.sourcesMask & sourcesChangedMask) == 0) {
        continue;
      }

      int paramId = dest.paramId;
      int combo = 0;

      if (paramId < Param.FIRST_LOCAL_HYBRID) {
        combo = combineCablesLinear(dest, paramId, sound, paramManager, sourceValues);
      } else {
        combo = combineCablesExp(dest, paramId, sound, paramManager, sourceValues);
      }

      int neutralValue = sound.paramNeutralValues[paramId];
      int finalValue = 0;

      if (paramId < Param.FIRST_LOCAL_NON_VOLUME) {
        // getFinalParameterValueVolume
        finalValue = Q31.multiply_32x32_rshift32_rounded(neutralValue, combo + 536870912) << 1;
      } else if (paramId < Param.FIRST_LOCAL_HYBRID) {
        // getFinalParameterValueLinear
        finalValue = neutralValue + combo;
      } else if (paramId < Param.FIRST_LOCAL_EXP) {
        // getFinalParameterValueHybrid
        finalValue = neutralValue + combo;
      } else {
        // getFinalParameterValueExp
        finalValue = FirmwareUtils.getExp(neutralValue, combo);
      }

      paramFinalValues[paramId] = finalValue;
    }
  }

  private int combineCablesLinear(
      Destination dest,
      int paramId,
      FirmwareSound sound,
      ParamManager paramManager,
      int[] sourceValues) {
    int runningTotal = 0;
    for (PatchCable cable : dest.cables) {
      int srcVal = sourceValues[cable.from.ordinal()];
      int strength = cable.getAmount(); // and automation
      int scaled = Q31.multiply_32x32_rshift32(srcVal, strength);
      runningTotal += scaled;
    }
    return runningTotal;
  }

  private int combineCablesExp(
      Destination dest,
      int paramId,
      FirmwareSound sound,
      ParamManager paramManager,
      int[] sourceValues) {
    int runningTotal = 0;
    for (PatchCable cable : dest.cables) {
      int srcVal = sourceValues[cable.from.ordinal()];
      int strength = cable.getAmount(); // and automation
      int scaled = Q31.multiply_32x32_rshift32(srcVal, strength);
      runningTotal += scaled;
    }
    return runningTotal;
  }
}
