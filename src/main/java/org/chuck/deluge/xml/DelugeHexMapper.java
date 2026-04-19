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
      // Handle 32-bit sign extension manually if it parsed as a positive long > Integer.MAX_VALUE
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
    // Approximate mapping: 20Hz at -1.0 to 20kHz at +1.0
    // exponential scale: f = 20 * (20000/20)^((norm+1)/2)
    double exponent = (norm + 1.0f) / 2.0f;
    return (float) (20.0 * Math.pow(1000.0, exponent));
  }

  public static String hzToHex(float hz) {
    hz = Math.max(20.0f, Math.min(20000.0f, hz));
    // Reverse the exponential formula
    // exponent = log(hz/20) / log(1000)
    double exponent = Math.log(hz / 20.0) / Math.log(1000.0);
    float norm = (float) (exponent * 2.0 - 1.0);
    return floatToHex(norm);
  }
}
