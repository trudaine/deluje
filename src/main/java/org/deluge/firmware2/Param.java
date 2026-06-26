package org.deluge.firmware2;

/**
 * Faithful port of the Deluge params enum ({@code modulation/params/param.h}). Every constant ID
 * matches the firmware exactly.
 */
public final class Param {
  private Param() {}

  // ── Local Patched Params (Indices 0-44) ────────────────────────────────

  // Linear
  public static final int LOCAL_OSC_A_VOLUME = 0;
  public static final int LOCAL_OSC_B_VOLUME = 1;
  public static final int LOCAL_VOLUME = 2;
  public static final int LOCAL_NOISE_VOLUME = 3;
  public static final int LOCAL_MODULATOR_0_VOLUME = 4;
  public static final int LOCAL_MODULATOR_1_VOLUME = 5;
  public static final int LOCAL_FOLD = 6;

  // Non-Volume
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

  // Hybrid
  public static final int FIRST_LOCAL_HYBRID = 19;
  public static final int LOCAL_OSC_A_PHASE_WIDTH = 19;
  public static final int LOCAL_OSC_B_PHASE_WIDTH = 20;
  public static final int LOCAL_OSC_A_WAVE_INDEX = 21;
  public static final int LOCAL_OSC_B_WAVE_INDEX = 22;
  public static final int LOCAL_PAN = 23;

  // Exp
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

  // ── Global Patched Params (Indices 45-54) ───────────────────────────────

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

  public static final int kNumParams = 55;

  // ── Unpatched Params (Starting at 90) ───────────────────────────────────

  public static final int UNPATCHED_START = 90;

  // Shared
  public static final int UNPATCHED_STUTTER_RATE = 90;
  public static final int UNPATCHED_BASS = 91;
  public static final int UNPATCHED_TREBLE = 92;
  public static final int UNPATCHED_BASS_FREQ = 93;
  public static final int UNPATCHED_TREBLE_FREQ = 94;
  public static final int UNPATCHED_SAMPLE_RATE_REDUCTION = 95;
  public static final int UNPATCHED_BITCRUSHING = 96;
  public static final int UNPATCHED_MOD_FX_OFFSET = 97;
  public static final int UNPATCHED_MOD_FX_FEEDBACK = 98;
  public static final int UNPATCHED_SIDECHAIN_SHAPE = 99;
  public static final int UNPATCHED_COMPRESSOR_THRESHOLD = 100;

  // Arp
  public static final int UNPATCHED_ARP_GATE = 101;
  public static final int UNPATCHED_ARP_RHYTHM = 102;
  public static final int UNPATCHED_ARP_SEQUENCE_LENGTH = 103;
  public static final int UNPATCHED_ARP_CHORD_POLYPHONY = 104;
  public static final int UNPATCHED_ARP_RATCHET_AMOUNT = 105;
  public static final int UNPATCHED_NOTE_PROBABILITY = 106;
  public static final int UNPATCHED_REVERSE_PROBABILITY = 107;
  public static final int UNPATCHED_ARP_BASS_PROBABILITY = 108;
  public static final int UNPATCHED_ARP_SWAP_PROBABILITY = 109;
  public static final int UNPATCHED_ARP_GLIDE_PROBABILITY = 110;
  public static final int UNPATCHED_ARP_CHORD_PROBABILITY = 111;
  public static final int UNPATCHED_ARP_RATCHET_PROBABILITY = 112;
  public static final int UNPATCHED_ARP_SPREAD_GATE = 113;
  public static final int UNPATCHED_ARP_SPREAD_OCTAVE = 114;
  public static final int UNPATCHED_SPREAD_VELOCITY = 115;

  // Sound-specific
  public static final int UNPATCHED_PORTAMENTO = 116;

  // GlobalEffectable-specific
  public static final int UNPATCHED_MOD_FX_RATE = 116;
  public static final int UNPATCHED_MOD_FX_DEPTH = 117;
  public static final int UNPATCHED_DELAY_RATE = 118;
  public static final int UNPATCHED_DELAY_AMOUNT = 119;
  public static final int UNPATCHED_ARP_RATE = 120;
  public static final int UNPATCHED_PAN = 121;
  public static final int UNPATCHED_LPF_FREQ = 122;
  public static final int UNPATCHED_LPF_RES = 123;
  public static final int UNPATCHED_LPF_MORPH = 124;
  public static final int UNPATCHED_HPF_FREQ = 125;
  public static final int UNPATCHED_HPF_RES = 126;
  public static final int UNPATCHED_HPF_MORPH = 127;
  public static final int UNPATCHED_REVERB_SEND_AMOUNT = 128;
  public static final int UNPATCHED_VOLUME = 129;
  public static final int UNPATCHED_SIDECHAIN_VOLUME = 130;
  public static final int UNPATCHED_PITCH_ADJUST = 131;

  public static final int STATIC_SIDECHAIN_ATTACK = 162;
  public static final int STATIC_SIDECHAIN_RELEASE = 163;
  public static final int STATIC_SIDECHAIN_VOLUME = 164;
  public static final int PATCH_CABLE = 190;

  public static boolean paramNeedsLPF(int p, boolean fromAutomation) {
    switch (p) {
      case GLOBAL_VOLUME_POST_FX:
      case GLOBAL_VOLUME_POST_REVERB_SEND:
      case GLOBAL_REVERB_AMOUNT:
      case LOCAL_VOLUME:
      case LOCAL_PAN:
      case LOCAL_LPF_FREQ:
      case LOCAL_HPF_FREQ:
      case LOCAL_OSC_A_VOLUME:
      case LOCAL_OSC_B_VOLUME:
      case LOCAL_OSC_A_WAVE_INDEX:
      case LOCAL_OSC_B_WAVE_INDEX:
        return !fromAutomation;

      case LOCAL_MODULATOR_0_VOLUME:
      case LOCAL_MODULATOR_1_VOLUME:
      case LOCAL_MODULATOR_0_FEEDBACK:
      case LOCAL_MODULATOR_1_FEEDBACK:
      case LOCAL_CARRIER_0_FEEDBACK:
      case LOCAL_CARRIER_1_FEEDBACK:
      case GLOBAL_MOD_FX_DEPTH:
      case GLOBAL_DELAY_FEEDBACK:
        return true;

      default:
        return false;
    }
  }
}
