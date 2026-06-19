package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware.modulation.params.ParamCurves;
import org.deluge.firmware.util.FirmwareUtils;
import org.deluge.firmware2.Param;
import org.junit.jupiter.api.Test;

/**
 * Pins the faithful Deluge patched-param curve foundation (port of functions.cpp
 * getParamNeutralValue / getParamRange / getFinalParameterValue*) to values hand-computed from the
 * firmware. This is the building block for retiring the approximate Patcher (see
 * deluge-remaining-approximations memory, item 8). Values verified by hand-tracing the C.
 */
public class FirmwareParamCurvesTest {

  @Test
  public void paramNeutralValuesMatchFirmware() {
    assertEquals(134217728, ParamCurves.getParamNeutralValue(Param.LOCAL_OSC_A_VOLUME));
    assertEquals(134217728, ParamCurves.getParamNeutralValue(Param.LOCAL_VOLUME));
    assertEquals(33554432, ParamCurves.getParamNeutralValue(Param.LOCAL_MODULATOR_0_VOLUME));
    assertEquals(33554432, ParamCurves.getParamNeutralValue(Param.LOCAL_MODULATOR_1_VOLUME));
    assertEquals(2000000, ParamCurves.getParamNeutralValue(Param.LOCAL_LPF_FREQ));
    assertEquals(2672947, ParamCurves.getParamNeutralValue(Param.LOCAL_HPF_FREQ));
    assertEquals(5931642, ParamCurves.getParamNeutralValue(Param.LOCAL_MODULATOR_0_FEEDBACK));
    assertEquals(5931642, ParamCurves.getParamNeutralValue(Param.LOCAL_CARRIER_1_FEEDBACK));
    assertEquals(16777216, ParamCurves.getParamNeutralValue(Param.LOCAL_PITCH_ADJUST));
    assertEquals(1073741824, ParamCurves.getParamNeutralValue(Param.LOCAL_ENV_0_SUSTAIN));
    assertEquals(0, ParamCurves.getParamNeutralValue(Param.LOCAL_PAN));
    assertEquals(0, ParamCurves.getParamNeutralValue(Param.LOCAL_OSC_A_WAVE_INDEX)); // default
  }

  @Test
  public void paramRangesMatchFirmware() {
    assertEquals(751619276, ParamCurves.getParamRange(Param.LOCAL_LPF_FREQ));
    assertEquals(805306368, ParamCurves.getParamRange(Param.LOCAL_ENV_0_ATTACK));
    assertEquals(536870912, ParamCurves.getParamRange(Param.LOCAL_PITCH_ADJUST));
    assertEquals(1073741824, ParamCurves.getParamRange(Param.LOCAL_MODULATOR_0_VOLUME)); // default
  }

  @Test
  public void volumeCurveMatchesHandComputedFmModulatorAmount() {
    // "049 Basic FM" modulator1Amount = 0x32000000, no cables: knob -> volume parabola.
    // Traced through the firmware integer ops: combineCablesLinear -> 209715200, then
    // getFinalParameterValueVolume(neutral=2^25): positivePatched 746586112, parabola
    // (>>16=11392)*(>>15=22784)=259555328, *2^25>>32=2027776, <<5 -> 64888832. (This also equals
    // the
    // value the FM engine computes for 049's modulator amount.)
    int neutral = ParamCurves.getParamNeutralValue(Param.LOCAL_MODULATOR_0_VOLUME);
    int range = ParamCurves.getParamRange(Param.LOCAL_MODULATOR_0_VOLUME);
    assertEquals(64888832, FirmwareUtils.finalVolumeParam(0x32000000, neutral, range));
    // "off" knob -> zero amplitude.
    assertEquals(0, FirmwareUtils.finalVolumeParam(0x80000000, neutral, range));
    // Monotonic: a higher knob gives a louder/deeper result.
    assertTrue(
        FirmwareUtils.finalVolumeParam(0x40000000, neutral, range)
            > FirmwareUtils.finalVolumeParam(0x10000000, neutral, range));
  }

  @Test
  public void hybridCurveIsCentredAndScales() {
    // Pan neutral 0: centre knob (0) -> 0; full right knob -> large positive, full left ->
    // negative.
    int neutral = ParamCurves.getParamNeutralValue(Param.LOCAL_PAN);
    assertEquals(0, FirmwareUtils.getFinalParameterValueHybrid(neutral, 0));
    assertTrue(FirmwareUtils.getFinalParameterValueHybrid(neutral, 1073741824) > 0);
    assertTrue(FirmwareUtils.getFinalParameterValueHybrid(neutral, -1073741824) < 0);
  }

  @Test
  public void expCutoffOpensWithKnob() {
    // The faithful filter-cutoff path: knob -> exp combine -> getExp. Higher knob = higher cutoff
    // param, and 0xE8000000 (049's LPF) must be far above a nearly-closed knob (the bug was it
    // mapping to ~331 Hz). Just assert strict monotonicity over the knob range.
    int neutral = ParamCurves.getParamNeutralValue(Param.LOCAL_LPF_FREQ);
    int range = ParamCurves.getParamRange(Param.LOCAL_LPF_FREQ);
    int low =
        FirmwareUtils.getFinalParameterValueExp(
            neutral, FirmwareUtils.patchCombineExpStep(0, 0xC0000000, range));
    int mid =
        FirmwareUtils.getFinalParameterValueExp(
            neutral, FirmwareUtils.patchCombineExpStep(0, 0xE8000000, range));
    int high =
        FirmwareUtils.getFinalParameterValueExp(
            neutral, FirmwareUtils.patchCombineExpStep(0, 0x40000000, range));
    assertTrue(
        low < mid && mid < high,
        "cutoff param must rise with the knob (low=" + low + " mid=" + mid + " high=" + high + ")");
  }
}
