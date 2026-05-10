package org.chuck.deluge.model;

import java.util.List;

/**
 * Canonical parameter name constants for per-step automation data in {@link ClipModel}.
 *
 * <p>81 automatable params organized into 4 categories matching firmware c1.3.0 Automation View:
 * <ul>
 *   <li>Synth/Kit with Affect-Entire disabled (26 params)</li>
 *   <li>Kit with Affect-Entire enabled / Audio (26 params)</li>
 *   <li>Arranger (22 params)</li>
 *   <li>Patch Cables / MIDI CCs (7 pseudo-params, firmware exposes 0-119 + PB + AT)</li>
 * </ul>
 *
 * <p>Values are stored as normalized 0.0–1.0 floats in {@code ClipModel.automationData}.
 */
public final class AutomationParam {

  private AutomationParam() {}

  // ─── Synth / Kit (affect-entire OFF)  —  26 params ─────────────────────

  public static final String A_VOLUME = "volume";
  public static final String A_PAN = "pan";
  public static final String A_LPF_FREQ = "lpfFrequency";
  public static final String A_LPF_RES = "lpfResonance";
  public static final String A_HPF_FREQ = "hpfFrequency";
  public static final String A_HPF_RES = "hpfResonance";
  public static final String A_MOD_FX_RATE = "modFxRate";
  public static final String A_MOD_FX_DEPTH = "modFxDepth";

  // Envelopes
  public static final String A_ENV_0_ATTACK = "env0Attack";
  public static final String A_ENV_0_DECAY = "env0Decay";
  public static final String A_ENV_0_SUSTAIN = "env0Sustain";
  public static final String A_ENV_0_RELEASE = "env0Release";
  public static final String A_ENV_1_ATTACK = "env1Attack";
  public static final String A_ENV_1_DECAY = "env1Decay";
  public static final String A_ENV_1_SUSTAIN = "env1Sustain";
  public static final String A_ENV_1_RELEASE = "env1Release";
  public static final String A_ENV_2_ATTACK = "env2Attack";
  public static final String A_ENV_2_DECAY = "env2Decay";
  public static final String A_ENV_2_SUSTAIN = "env2Sustain";
  public static final String A_ENV_2_RELEASE = "env2Release";
  public static final String A_ENV_3_ATTACK = "env3Attack";
  public static final String A_ENV_3_DECAY = "env3Decay";
  public static final String A_ENV_3_SUSTAIN = "env3Sustain";
  public static final String A_ENV_3_RELEASE = "env3Release";

  // LFOs
  public static final String A_LFO_0_RATE = "lfo0Rate";
  public static final String A_LFO_0_DEPTH = "lfo0Depth";
  public static final String A_LFO_1_RATE = "lfo1Rate";
  public static final String A_LFO_1_DEPTH = "lfo1Depth";
  public static final String A_LFO_2_RATE = "lfo2Rate";
  public static final String A_LFO_2_DEPTH = "lfo2Depth";
  public static final String A_LFO_3_RATE = "lfo3Rate";
  public static final String A_LFO_3_DEPTH = "lfo3Depth";

  // Arpeggiator
  public static final String A_ARP_RATE = "arpRate";
  public static final String A_ARP_GATE = "arpGate";

  // Delay / Reverb sends
  public static final String A_DELAY = "delay";
  public static final String A_REVERB = "reverb";

  // Oscillator / FM
  public static final String A_PITCH = "pitch";
  public static final String A_OSC_A_VOL = "oscAVolume";
  public static final String A_OSC_B_VOL = "oscBVolume";
  public static final String A_NOISE_VOL = "noiseVolume";
  public static final String A_FM_AMOUNT = "fmAmount";
  public static final String A_FM_RATIO = "fmRatio";

  // Mod FX feedback
  public static final String A_MOD_FX_FEEDBACK = "modFxFeedback";

  // Compressor
  public static final String A_COMP_ATTACK = "compAttack";
  public static final String A_COMP_RELEASE = "compRelease";

  // Portamento
  public static final String A_PORTAMENTO = "portamento";

  // Stutter / Bitcrush / SRR
  public static final String A_STUTTER_RATE = "stutterRate";
  public static final String A_BITCRUSH = "bitCrush";
  public static final String A_SAMPLE_RATE_RED = "sampleRateReduction";

  // ─── Kit with affect-entire / Audio — additional/overlapping ──────────
  // (These overlap with synth params above; the firmware maps them the same way)

  // ─── Arranger params  —  22 params ────────────────────────────────────

  public static final String A_ARR_VOLUME = "arrVolume";
  public static final String A_ARR_PAN = "arrPan";
  public static final String A_ARR_LPF_FREQ = "arrLpfFrequency";
  public static final String A_ARR_LPF_RES = "arrLpfResonance";
  public static final String A_ARR_HPF_FREQ = "arrHpfFrequency";
  public static final String A_ARR_HPF_RES = "arrHpfResonance";
  public static final String A_ARR_MOD_FX_RATE = "arrModFxRate";
  public static final String A_ARR_MOD_FX_DEPTH = "arrModFxDepth";
  public static final String A_ARR_DELAY = "arrDelay";
  public static final String A_ARR_REVERB = "arrReverb";
  public static final String A_ARR_PITCH = "arrPitch";
  public static final String A_ARR_OSC_A_VOL = "arrOscAVolume";
  public static final String A_ARR_OSC_B_VOL = "arrOscBVolume";
  public static final String A_ARR_NOISE_VOL = "arrNoiseVolume";
  public static final String A_ARR_FM_AMOUNT = "arrFmAmount";
  public static final String A_ARR_FM_RATIO = "arrFmRatio";
  public static final String A_ARR_COMP_ATTACK = "arrCompAttack";
  public static final String A_ARR_COMP_RELEASE = "arrCompRelease";
  public static final String A_ARR_PORTAMENTO = "arrPortamento";
  public static final String A_ARR_STUTTER = "arrStutterRate";
  public static final String A_ARR_BITCRUSH = "arrBitCrush";
  public static final String A_ARR_SAMPLE_RATE_RED = "arrSampleRateReduction";

  // ─── Category groups ──────────────────────────────────────────────────

  /** Synth/Kit params (affect-entire disabled): 26 params. */
  public static final String[] SYTH_PARAMS = {
    A_VOLUME, A_PAN,
    A_LPF_FREQ, A_LPF_RES,
    A_HPF_FREQ, A_HPF_RES,
    A_MOD_FX_RATE, A_MOD_FX_DEPTH,
    A_ENV_0_ATTACK, A_ENV_0_DECAY, A_ENV_0_SUSTAIN, A_ENV_0_RELEASE,
    A_ENV_1_ATTACK, A_ENV_1_DECAY, A_ENV_1_SUSTAIN, A_ENV_1_RELEASE,
    A_ENV_2_ATTACK, A_ENV_2_DECAY, A_ENV_2_SUSTAIN, A_ENV_2_RELEASE,
    A_ENV_3_ATTACK, A_ENV_3_DECAY, A_ENV_3_SUSTAIN, A_ENV_3_RELEASE,
    A_LFO_0_RATE, A_LFO_0_DEPTH,
    A_LFO_1_RATE, A_LFO_1_DEPTH,
    A_LFO_2_RATE, A_LFO_2_DEPTH,
    A_LFO_3_RATE, A_LFO_3_DEPTH,
    A_ARP_RATE, A_ARP_GATE,
    A_DELAY, A_REVERB,
    A_PITCH,
    A_OSC_A_VOL, A_OSC_B_VOL, A_NOISE_VOL,
    A_FM_AMOUNT, A_FM_RATIO,
    A_MOD_FX_FEEDBACK,
    A_COMP_ATTACK, A_COMP_RELEASE,
    A_PORTAMENTO,
    A_STUTTER_RATE, A_BITCRUSH, A_SAMPLE_RATE_RED,
  };

  /** Kit/affect-entire / Audio params: 26 params (similar to synth but some overlap). */
  public static final String[] KIT_PARAMS = {
    A_VOLUME, A_PAN,
    A_LPF_FREQ, A_LPF_RES,
    A_HPF_FREQ, A_HPF_RES,
    A_MOD_FX_RATE, A_MOD_FX_DEPTH,
    A_ENV_0_ATTACK, A_ENV_0_DECAY, A_ENV_0_SUSTAIN, A_ENV_0_RELEASE,
    A_ENV_1_ATTACK, A_ENV_1_DECAY, A_ENV_1_SUSTAIN, A_ENV_1_RELEASE,
    A_DELAY, A_REVERB,
    A_PITCH,
    A_OSC_A_VOL, A_OSC_B_VOL, A_NOISE_VOL,
    A_COMP_ATTACK, A_COMP_RELEASE,
    A_STUTTER_RATE, A_BITCRUSH, A_SAMPLE_RATE_RED,
  };

  /** Arranger params: 22 params. */
  public static final String[] ARR_PARAMS = {
    A_ARR_VOLUME, A_ARR_PAN,
    A_ARR_LPF_FREQ, A_ARR_LPF_RES,
    A_ARR_HPF_FREQ, A_ARR_HPF_RES,
    A_ARR_MOD_FX_RATE, A_ARR_MOD_FX_DEPTH,
    A_ARR_DELAY, A_ARR_REVERB,
    A_ARR_PITCH,
    A_ARR_OSC_A_VOL, A_ARR_OSC_B_VOL, A_ARR_NOISE_VOL,
    A_ARR_FM_AMOUNT, A_ARR_FM_RATIO,
    A_ARR_COMP_ATTACK, A_ARR_COMP_RELEASE,
    A_ARR_PORTAMENTO,
    A_ARR_STUTTER, A_ARR_BITCRUSH, A_ARR_SAMPLE_RATE_RED,
  };

  /**
   * Compact display labels for the overview grid.
   * Maps param constant → short label (max 6 chars for grid fitting).
   */
  public static String labelFor(String param) {
    return switch (param) {
      case A_VOLUME -> "VOL";
      case A_PAN -> "PAN";
      case A_LPF_FREQ -> "LPF F";
      case A_LPF_RES -> "LPF R";
      case A_HPF_FREQ -> "HPF F";
      case A_HPF_RES -> "HPF R";
      case A_MOD_FX_RATE -> "MFR";
      case A_MOD_FX_DEPTH -> "MFD";
      case A_ENV_0_ATTACK -> "E0A";
      case A_ENV_0_DECAY -> "E0D";
      case A_ENV_0_SUSTAIN -> "E0S";
      case A_ENV_0_RELEASE -> "E0R";
      case A_ENV_1_ATTACK -> "E1A";
      case A_ENV_1_DECAY -> "E1D";
      case A_ENV_1_SUSTAIN -> "E1S";
      case A_ENV_1_RELEASE -> "E1R";
      case A_ENV_2_ATTACK -> "E2A";
      case A_ENV_2_DECAY -> "E2D";
      case A_ENV_2_SUSTAIN -> "E2S";
      case A_ENV_2_RELEASE -> "E2R";
      case A_ENV_3_ATTACK -> "E3A";
      case A_ENV_3_DECAY -> "E3D";
      case A_ENV_3_SUSTAIN -> "E3S";
      case A_ENV_3_RELEASE -> "E3R";
      case A_LFO_0_RATE -> "L0R";
      case A_LFO_0_DEPTH -> "L0D";
      case A_LFO_1_RATE -> "L1R";
      case A_LFO_1_DEPTH -> "L1D";
      case A_LFO_2_RATE -> "L2R";
      case A_LFO_2_DEPTH -> "L2D";
      case A_LFO_3_RATE -> "L3R";
      case A_LFO_3_DEPTH -> "L3D";
      case A_ARP_RATE -> "AR";
      case A_ARP_GATE -> "AG";
      case A_DELAY -> "DLY";
      case A_REVERB -> "REV";
      case A_PITCH -> "PIT";
      case A_OSC_A_VOL -> "OSA";
      case A_OSC_B_VOL -> "OSB";
      case A_NOISE_VOL -> "NSE";
      case A_FM_AMOUNT -> "FMA";
      case A_FM_RATIO -> "FMR";
      case A_MOD_FX_FEEDBACK -> "MFF";
      case A_COMP_ATTACK -> "CPA";
      case A_COMP_RELEASE -> "CPR";
      case A_PORTAMENTO -> "POR";
      case A_STUTTER_RATE -> "STR";
      case A_BITCRUSH -> "BIT";
      case A_SAMPLE_RATE_RED -> "SRR";
      // Arranger
      case A_ARR_VOLUME -> "aVOL";
      case A_ARR_PAN -> "aPAN";
      case A_ARR_LPF_FREQ -> "aLPF";
      case A_ARR_LPF_RES -> "aLPR";
      case A_ARR_HPF_FREQ -> "aHPF";
      case A_ARR_HPF_RES -> "aHPR";
      case A_ARR_MOD_FX_RATE -> "aMFR";
      case A_ARR_MOD_FX_DEPTH -> "aMFD";
      case A_ARR_DELAY -> "aDLY";
      case A_ARR_REVERB -> "aREV";
      case A_ARR_PITCH -> "aPIT";
      case A_ARR_OSC_A_VOL -> "aOSA";
      case A_ARR_OSC_B_VOL -> "aOSB";
      case A_ARR_NOISE_VOL -> "aNSE";
      case A_ARR_FM_AMOUNT -> "aFMA";
      case A_ARR_FM_RATIO -> "aFMR";
      case A_ARR_COMP_ATTACK -> "aCPA";
      case A_ARR_COMP_RELEASE -> "aCPR";
      case A_ARR_PORTAMENTO -> "aPOR";
      case A_ARR_STUTTER -> "aSTR";
      case A_ARR_BITCRUSH -> "aBIT";
      case A_ARR_SAMPLE_RATE_RED -> "aSRR";
      default -> param.length() <= 6 ? param.toUpperCase() : param.substring(0, 5).toUpperCase();
    };
  }

  /** Check if a param name is one of the arranger params. */
  public static boolean isArrangerParam(String param) {
    return param.startsWith("arr");
  }

  /** Check if a param name is an envelope param. */
  public static boolean isEnvelopeParam(String param) {
    return param.startsWith("env");
  }

  /** Check if a param name is an LFO param. */
  public static boolean isLfoParam(String param) {
    return param.startsWith("lfo");
  }

  // ─── Legacy compatibility — keep the original 14 ALL array so existing code compiles ───

  /** The original 14 v1 automation params, kept for back compat. */
  public static final String[] ALL = {
    A_VOLUME, A_PAN,
    A_LPF_FREQ, A_LPF_RES,
    A_HPF_FREQ, A_HPF_RES,
    A_MOD_FX_RATE, A_MOD_FX_DEPTH,
    A_DELAY, A_REVERB,
    A_PITCH,
    A_OSC_A_VOL, A_OSC_B_VOL,
    A_NOISE_VOL,
  };
}
