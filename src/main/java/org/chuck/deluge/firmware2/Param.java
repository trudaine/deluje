package org.chuck.deluge.firmware2;

/**
 * Faithful port of the Deluge params enum ({@code modulation/params/param.h}). Every constant ID
 * matches the firmware exactly.
 */
public final class Param {
  private Param() {}

  // ── Local (sound-level) params ──

  public static final int LOCAL_OSC_A_VOLUME = 0;
  public static final int LOCAL_OSC_B_VOLUME = 1;
  public static final int LOCAL_VOLUME = 2;
  public static final int LOCAL_NOISE_VOLUME = 3;
  public static final int LOCAL_MODULATOR_0_VOLUME = 4;
  public static final int LOCAL_MODULATOR_1_VOLUME = 5;
  public static final int LOCAL_FOLD = 6;

  public static final int FIRST_LOCAL_NON_VOLUME = 7;

  public static final int LOCAL_MODULATOR_0_FEEDBACK = 7;
  public static final int LOCAL_MODULATOR_1_FEEDBACK = 8;
  public static final int LOCAL_CARRIER_0_FEEDBACK = 9;
  public static final int LOCAL_CARRIER_1_FEEDBACK = 10;
  public static final int LOCAL_LPF_RESONANCE = 11;
  public static final int LOCAL_HPF_RESONANCE = 12;
  public static final int LOCAL_ENV_0_SUSTAIN = 13;
  public static final int LOCAL_ENV_1_SUSTAIN = 14;
  public static final int LOCAL_ENV_2_SUSTAIN = 15;
  public static final int LOCAL_ENV_3_SUSTAIN = 16;
  public static final int LOCAL_LPF_MORPH = 17;
  public static final int LOCAL_HPF_MORPH = 18;

  public static final int FIRST_LOCAL_HYBRID = 19;

  public static final int LOCAL_OSC_A_PHASE_WIDTH = 19;
  public static final int LOCAL_OSC_B_PHASE_WIDTH = 20;
  public static final int LOCAL_OSC_A_WAVE_INDEX = 21;
  public static final int LOCAL_OSC_B_WAVE_INDEX = 22;
  public static final int LOCAL_PAN = 23;

  public static final int FIRST_LOCAL_EXP = 24;

  public static final int LOCAL_LPF_FREQ = 24;
  public static final int LOCAL_PITCH_ADJUST = 25;
  public static final int LOCAL_OSC_A_PITCH_ADJUST = 26;
  public static final int LOCAL_OSC_B_PITCH_ADJUST = 27;
  public static final int LOCAL_MODULATOR_0_PITCH_ADJUST = 28;
  public static final int LOCAL_MODULATOR_1_PITCH_ADJUST = 29;
  public static final int LOCAL_HPF_FREQ = 30;
  public static final int LOCAL_LFO_LOCAL_FREQ_1 = 31;
  public static final int LOCAL_LFO_LOCAL_FREQ_2 = 32;
  public static final int LOCAL_ENV_0_ATTACK = 33;
  public static final int LOCAL_ENV_1_ATTACK = 34;
  public static final int LOCAL_ENV_2_ATTACK = 35;
  public static final int LOCAL_ENV_3_ATTACK = 36;
  public static final int LOCAL_ENV_0_DECAY = 37;
  public static final int LOCAL_ENV_1_DECAY = 38;
  public static final int LOCAL_ENV_2_DECAY = 39;
  public static final int LOCAL_ENV_3_DECAY = 40;
  public static final int LOCAL_ENV_0_RELEASE = 41;
  public static final int LOCAL_ENV_1_RELEASE = 42;
  public static final int LOCAL_ENV_2_RELEASE = 43;
  public static final int LOCAL_ENV_3_RELEASE = 44;

  public static final int LOCAL_LAST = 45;

  // ── Global params ──

  public static final int FIRST_GLOBAL = 45;

  public static final int GLOBAL_VOLUME_POST_FX = 45;
  public static final int GLOBAL_VOLUME_POST_REVERB_SEND = 46;
  public static final int GLOBAL_REVERB_AMOUNT = 47;
  public static final int GLOBAL_MOD_FX_DEPTH = 48;

  public static final int FIRST_GLOBAL_NON_VOLUME = 49;

  public static final int GLOBAL_DELAY_FEEDBACK = 49;

  public static final int FIRST_GLOBAL_HYBRID = 50;
  public static final int FIRST_GLOBAL_EXP = 50;

  public static final int GLOBAL_DELAY_RATE = 50;
  public static final int GLOBAL_MOD_FX_RATE = 51;
  public static final int GLOBAL_LFO_FREQ_1 = 52;
  public static final int GLOBAL_LFO_FREQ_2 = 53;
  public static final int GLOBAL_ARP_RATE = 54;

  // ── Mapped to integer for switch dispatch ──

  public static final int PATCH_CABLE = 64;
  public static final int STATIC_SIDECHAIN_ATTACK = 65;
  public static final int STATIC_SIDECHAIN_RELEASE = 66;
  public static final int STATIC_SIDECHAIN_VOLUME = 67;

  // ── Unpatched params ──

  public static final int UNPATCHED_BASS = 128 + 0;
  public static final int UNPATCHED_TREBLE = 128 + 1;

  public static final int kNumParams = 55;
}
