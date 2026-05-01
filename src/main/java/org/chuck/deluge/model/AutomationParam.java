package org.chuck.deluge.model;

/**
 * Canonical parameter name constants used for per-step automation data in {@link ClipModel}.
 *
 * <p>Each constant maps to a synth/effect parameter that can be modulated on a per-step basis.
 * Values are stored as normalized 0.0–1.0 floats in {@code ClipModel.automationData} and
 * rendered in the UI on an 8-row grid (0–127 integer range).
 */
public final class AutomationParam {

  private AutomationParam() {}

  public static final String A_VOLUME = "volume";
  public static final String A_PAN = "pan";
  public static final String A_LPF_FREQ = "lpfFrequency";
  public static final String A_LPF_RES = "lpfResonance";
  public static final String A_HPF_FREQ = "hpfFrequency";
  public static final String A_HPF_RES = "hpfResonance";
  public static final String A_MOD_FX_RATE = "modFxRate";
  public static final String A_MOD_FX_DEPTH = "modFxDepth";
  public static final String A_DELAY = "delay";
  public static final String A_REVERB = "reverb";
  public static final String A_PITCH = "pitch";
  public static final String A_OSC_A_VOL = "oscAVolume";
  public static final String A_OSC_B_VOL = "oscBVolume";
  public static final String A_NOISE_VOL = "noiseVolume";

  /** All 14 v1 automation params, in display order. */
  public static final String[] ALL = {
    A_VOLUME, A_PAN,
    A_LPF_FREQ, A_LPF_RES,
    A_HPF_FREQ, A_HPF_RES,
    A_MOD_FX_RATE, A_MOD_FX_DEPTH,
    A_DELAY, A_REVERB,
    A_PITCH,
    A_OSC_A_VOL, A_OSC_B_VOL,
    A_NOISE_VOL
  };
}
