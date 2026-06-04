package org.chuck.deluge.firmware.modulation.params;

/**
 * Faithful port of the Deluge's per-parameter constant tables from {@code util/functions.cpp}:
 * {@code getParamNeutralValue} (the curve "neutral"/centre used by the final-value functions) and
 * {@code getParamRange} (the strength the stored knob is scaled by in the cable combiner).
 *
 * <p>These are CONSTANTS per parameter — they do not depend on the patch. The patch supplies a
 * separate per-param knob value; the engine combines knob + cables and runs the result through the
 * matching {@code getFinalParameterValue*} curve (see {@link
 * org.chuck.deluge.firmware.util.FirmwareUtils}). Switching by {@link Param} name (not index) keeps
 * this aligned with the firmware {@code params::} enum.
 */
public final class ParamCurves {
  private ParamCurves() {}

  /** {@code kMaxSampleValue = 1 << kBitDepth (24)}. */
  public static final int K_MAX_SAMPLE_VALUE = 1 << 24;

  /** Port of {@code getParamNeutralValue(p)}. */
  public static int getParamNeutralValue(int p) {
    switch (p) {
      case Param.LOCAL_OSC_A_VOLUME:
      case Param.LOCAL_OSC_B_VOLUME:
      case Param.GLOBAL_VOLUME_POST_REVERB_SEND:
      case Param.LOCAL_NOISE_VOLUME:
      case Param.GLOBAL_REVERB_AMOUNT:
      case Param.GLOBAL_VOLUME_POST_FX:
      case Param.LOCAL_VOLUME:
        return 134217728;

      case Param.LOCAL_MODULATOR_0_VOLUME:
      case Param.LOCAL_MODULATOR_1_VOLUME:
        return 33554432;

      case Param.LOCAL_LPF_FREQ:
        return 2000000;
      case Param.LOCAL_HPF_FREQ:
        return 2672947;

      case Param.GLOBAL_LFO_FREQ_1:
      case Param.GLOBAL_LFO_FREQ_2:
      case Param.LOCAL_LFO_LOCAL_FREQ_1:
      case Param.LOCAL_LFO_LOCAL_FREQ_2:
      case Param.GLOBAL_MOD_FX_RATE:
        return 121739;

      case Param.LOCAL_LPF_RESONANCE:
      case Param.LOCAL_HPF_RESONANCE:
      case Param.LOCAL_LPF_MORPH:
      case Param.LOCAL_HPF_MORPH:
      case Param.LOCAL_FOLD:
        return 25 * 10737418;

      case Param.LOCAL_PAN:
      case Param.LOCAL_OSC_A_PHASE_WIDTH:
      case Param.LOCAL_OSC_B_PHASE_WIDTH:
        return 0;

      case Param.LOCAL_ENV_0_ATTACK:
      case Param.LOCAL_ENV_1_ATTACK:
      case Param.LOCAL_ENV_2_ATTACK:
      case Param.LOCAL_ENV_3_ATTACK:
        return 4096;

      case Param.LOCAL_ENV_0_RELEASE:
      case Param.LOCAL_ENV_1_RELEASE:
      case Param.LOCAL_ENV_2_RELEASE:
      case Param.LOCAL_ENV_3_RELEASE:
        return 140 << 9;

      case Param.LOCAL_ENV_0_DECAY:
      case Param.LOCAL_ENV_1_DECAY:
      case Param.LOCAL_ENV_2_DECAY:
      case Param.LOCAL_ENV_3_DECAY:
        return 70 << 9;

      case Param.LOCAL_ENV_0_SUSTAIN:
      case Param.LOCAL_ENV_1_SUSTAIN:
      case Param.LOCAL_ENV_2_SUSTAIN:
      case Param.LOCAL_ENV_3_SUSTAIN:
      case Param.GLOBAL_DELAY_FEEDBACK:
        return 1073741824;

      case Param.LOCAL_MODULATOR_0_FEEDBACK:
      case Param.LOCAL_MODULATOR_1_FEEDBACK:
      case Param.LOCAL_CARRIER_0_FEEDBACK:
      case Param.LOCAL_CARRIER_1_FEEDBACK:
        return 5931642;

      case Param.GLOBAL_DELAY_RATE:
      case Param.GLOBAL_ARP_RATE:
      case Param.LOCAL_PITCH_ADJUST:
      case Param.LOCAL_OSC_A_PITCH_ADJUST:
      case Param.LOCAL_OSC_B_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_0_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_1_PITCH_ADJUST:
        return K_MAX_SAMPLE_VALUE;

      case Param.GLOBAL_MOD_FX_DEPTH:
        return 526133494;

      default:
        return 0;
    }
  }

  /** Port of {@code getParamRange(p)}. */
  public static int getParamRange(int p) {
    switch (p) {
      case Param.LOCAL_ENV_0_ATTACK:
      case Param.LOCAL_ENV_1_ATTACK:
      case Param.LOCAL_ENV_2_ATTACK:
      case Param.LOCAL_ENV_3_ATTACK:
        return (int) (536870912 * 1.5);

      case Param.GLOBAL_DELAY_RATE:
        return 536870912;

      case Param.LOCAL_PITCH_ADJUST:
      case Param.LOCAL_OSC_A_PITCH_ADJUST:
      case Param.LOCAL_OSC_B_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_0_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_1_PITCH_ADJUST:
        return 536870912;

      case Param.LOCAL_LPF_FREQ:
        return (int) (536870912 * 1.4);

      default:
        return 1073741824;
    }
  }
}
