package org.chuck.deluge.xml;

/** Utility to map Deluge XML hex string formats (e.g. "0x7FFFFFFF") into usable float/Hz values. */
public class DelugeHexMapper {

  /**
   * Converts a 32-bit signed hex string into a normalized float [-1.0, 1.0]. Example: "0x7FFFFFFF"
   * -> 1.0f, "0x80000000" -> -1.0f
   */
  public static float hexToFloat(String hex) {
    if (hex == null || hex.isEmpty()) return 0.0f;
    try {
      long raw = Long.decode(hex.trim());
      int intVal = (int) raw;
      return Math.max(-1.0f, Math.min(1.0f, (float) intVal / Integer.MAX_VALUE));
    } catch (NumberFormatException e) {
      return 0.0f;
    }
  }

  /** Converts a normalized float [-1.0, 1.0] back to Deluge hex format. */
  public static String floatToHex(float value) {
    value = Math.max(-1.0f, Math.min(1.0f, value));
    int intVal = (int) (value * Integer.MAX_VALUE);
    return String.format("0x%08X", intVal);
  }

  /**
   * The Deluge uses an exponential mapping for frequency (Hz) to hex. This is a reverse-engineered
   * approximation.
   */
  public static float hexToHz(String hex) {
    float norm = hexToFloat(hex); // -1 to 1
    double exponent = (norm + 1.0f) / 2.0f;
    return (float) (20.0 * Math.pow(1000.0, exponent));
  }

  public static String hzToHex(float hz) {
    hz = Math.max(20.0f, Math.min(20000.0f, hz));
    double exponent = Math.log(hz / 20.0) / Math.log(1000.0);
    float norm = (float) (exponent * 2.0 - 1.0);
    return floatToHex(norm);
  }

  // ── Envelope time mapping ──

  /**
   * Deluge envelope time (attack/decay/release) uses bipolar hex [-1, 1] mapped exponentially to
   * [MIN_TIME, MAX_TIME] seconds. -1.0 → MIN_TIME (instant), 0.0 → ~MID_TIME, +1.0 → MAX_TIME
   */
  public static final float ENV_MIN_TIME = 0.001f;

  public static final float ENV_MID_TIME = 0.15f;
  public static final float ENV_MAX_TIME = 30.0f;

  /** Convert a bipolar norm [-1, 1] to envelope time in seconds. */
  public static float envTimeFromNorm(float norm) {
    double ratio = ENV_MAX_TIME / ENV_MIN_TIME;
    return (float) (ENV_MIN_TIME * Math.pow(ratio, (norm + 1.0) / 2.0));
  }

  /** Convert envelope time in seconds to bipolar norm [-1, 1]. */
  public static float normFromEnvTime(float timeSec) {
    double ratio = ENV_MAX_TIME / ENV_MIN_TIME;
    float clamped = Math.max(ENV_MIN_TIME, Math.min(ENV_MAX_TIME, timeSec));
    return (float) (2.0 * Math.log(clamped / ENV_MIN_TIME) / Math.log(ratio) - 1.0);
  }

  /** Read envelope time from hex string, returning seconds. */
  public static float hexToEnvTime(String hex) {
    return envTimeFromNorm(hexToFloat(hex));
  }

  /** Write envelope time (seconds) to hex string. */
  public static String envTimeToHex(float timeSec) {
    return floatToHex(normFromEnvTime(timeSec));
  }

  // ── Envelope sustain mapping ──

  /** Sustain maps linearly: [-1, 1] → [0, 1]. */
  public static float sustainFromNorm(float norm) {
    return (norm + 1.0f) * 0.5f;
  }

  public static float normFromSustain(float sustain) {
    return sustain * 2.0f - 1.0f;
  }

  public static float hexToSustain(String hex) {
    return sustainFromNorm(hexToFloat(hex));
  }

  public static String sustainToHex(float sustain) {
    return floatToHex(normFromSustain(sustain));
  }
}
