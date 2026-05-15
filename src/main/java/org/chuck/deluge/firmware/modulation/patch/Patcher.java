package org.chuck.deluge.firmware.modulation.patch;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of the Deluge's Patcher class.
 * Implements 1:N modulation routing with bit-accurate linear, hybrid, and exponential mapping.
 */
public class Patcher {

  public void performPatching(
      int sourcesChangedMask,
      FirmwareSound sound,
      ParamManager paramManager,
      int[] sourceValues,
      int[] paramFinalValues) {
    PatchCableSet patchCableSet = paramManager.getPatchCableSet();

    // ── Bit-Accurate Patching Loop ──
    for (Destination dest : patchCableSet.destinations) {
      if ((dest.sourcesMask & sourcesChangedMask) == 0) {
        continue;
      }

      int paramId = dest.paramId;
      int neutralValue = sound.paramNeutralValues[paramId];
      int finalValue = 0;

      // Hardware handles different param types with specific combinators
      if (paramId < Param.FIRST_LOCAL_NON_VOLUME) {
        // Volume params
        int combo = combineCablesLinear(dest, paramId, sound, paramManager, sourceValues);
        finalValue = Q31.multiply_32x32_rshift32_rounded(neutralValue, combo + 536870912) << 1;
      } else if (paramId < Param.FIRST_LOCAL_HYBRID) {
        // Linear params
        int combo = combineCablesLinear(dest, paramId, sound, paramManager, sourceValues);
        finalValue = neutralValue + combo;
      } else if (paramId < Param.FIRST_LOCAL_EXP) {
        // Hybrid params
        int combo = combineCablesLinear(dest, paramId, sound, paramManager, sourceValues);
        finalValue = neutralValue + combo;
      } else {
        // Exp params
        int combo = combineCablesExp(dest, paramId, sound, paramManager, sourceValues);
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
      int strength = cable.getAmount(); 
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
      int strength = cable.getAmount(); 
      int scaled = Q31.multiply_32x32_rshift32(srcVal, strength);
      runningTotal += scaled;
    }
    return runningTotal;
  }
}
