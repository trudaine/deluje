package org.deluge.xml;

import java.util.ArrayList;
import java.util.List;
import org.deluge.modulation.automation.ParamNode;

/** Utility to map Deluge XML hex string formats (e.g. "0x7FFFFFFF") into usable float/Hz values. */
public class DelugeHexMapper {

  /**
   * Extracts the first 32-bit hex token when a Deluge parameter string appends 16-char automation
   * nodes after the 8-char currentValue (C auto_param.cpp:2008-2029, 2066-2070).
   */
  private static String firstHexToken(String hex) {
    String trimmed = hex.trim();
    if ((trimmed.startsWith("0x") || trimmed.startsWith("0X")) && trimmed.length() > 10) {
      return trimmed.substring(0, 10);
    }
    return trimmed;
  }

  /**
   * Returns the normalized 10-char {@code 0xCCCCCCCC} base hex prefix from a Deluge parameter
   * string (which may also carry 16-char automation nodes), or {@code null} when absent/invalid.
   */
  public static String extractBaseHex(String hex) {
    if (hex == null || hex.isBlank()) {
      return null;
    }
    String token = firstHexToken(hex);
    if ((token.startsWith("0x") || token.startsWith("0X")) && token.length() == 10) {
      return "0x" + token.substring(2).toUpperCase(java.util.Locale.ROOT);
    }
    return null;
  }

  /**
   * Parses 16-char automation nodes ({@code VVVVVVVVPPPPPPPP}, bit 31 of {@code pos} =
   * interpolated) appended after the 10-char {@code 0xCCCCCCCC} value in a Deluge parameter string
   * (C auto_param.cpp:2075-2148).
   */
  public static List<ParamNode> parseHexAutomationNodes(String hex, int readAutomationUpToPos) {
    if (hex == null || readAutomationUpToPos <= 0) {
      return List.of();
    }
    String trimmed = hex.trim();
    if (!(trimmed.startsWith("0x") || trimmed.startsWith("0X")) || trimmed.length() <= 10) {
      return List.of();
    }
    List<ParamNode> nodes = new ArrayList<>();
    int prevPos = -1;
    for (int idx = 10; idx + 16 <= trimmed.length(); idx += 16) {
      int value;
      int rawPos;
      try {
        value = Integer.parseUnsignedInt(trimmed.substring(idx, idx + 8), 16);
        rawPos = Integer.parseUnsignedInt(trimmed.substring(idx + 8, idx + 16), 16);
      } catch (NumberFormatException e) {
        break;
      }
      boolean interpolated = (rawPos & 0x80000000) != 0;
      int pos = rawPos & 0x7FFFFFFF;

      // C auto_param.cpp:2109-2112 — skip out-of-order nodes.
      if (pos <= prevPos) {
        continue;
      }

      // C auto_param.cpp:2115-2133 — stop at readAutomationUpToPos, with <= 3.1.5 endpoint fix.
      if (pos >= readAutomationUpToPos) {
        if (pos == readAutomationUpToPos && (nodes.isEmpty() || nodes.get(0).pos != 0)) {
          nodes.add(0, new ParamNode(0, value, interpolated));
        }
        break;
      }

      prevPos = pos;
      nodes.add(new ParamNode(pos, value, interpolated));
    }
    return nodes;
  }

  /**
   * Appends 16-char automation nodes ({@code VVVVVVVVPPPPPPPP}) after {@code baseHex} (C
   * auto_param.cpp:2005-2030).
   */
  public static String formatHexWithAutomationNodes(String baseHex, List<ParamNode> nodes) {
    String base = firstHexToken(baseHex);
    if (nodes == null || nodes.isEmpty()) {
      return base;
    }
    StringBuilder sb = new StringBuilder(base.length() + nodes.size() * 16);
    sb.append(base);
    for (ParamNode node : nodes) {
      int rawPos = node.pos | (node.interpolated ? 0x80000000 : 0);
      sb.append(String.format("%08X%08X", node.value, rawPos));
    }
    return sb.toString();
  }

  /**
   * Converts a 32-bit signed hex string into a normalized float [-1.0, 1.0]. Example: "0x7FFFFFFF"
   * -> 1.0f, "0x80000000" -> -1.0f
   */
  public static float hexToFloat(String hex) {
    if (hex == null || hex.isEmpty()) return 0.0f;
    try {
      long raw = Long.decode(firstHexToken(hex));
      int intVal = (int) raw;
      return Math.max(-1.0f, Math.min(1.0f, (float) intVal / Integer.MAX_VALUE));
    } catch (NumberFormatException e) {
      return 0.0f;
    }
  }

  /**
   * Converts a 32-bit hex string into the raw signed Q31 integer value as stored by the firmware
   * (e.g. "0x80000000" -> Integer.MIN_VALUE "off", "0x32000000" -> 838860800). Unlike {@link
   * #hexToFloat}, this preserves the exact bit pattern needed to reproduce the firmware's patched
   * param math. Returns 0 ("centre") when absent/unparseable.
   */
  public static int hexToQ31(String hex) {
    if (hex == null || hex.isEmpty()) return 0;
    try {
      return (int) (long) Long.decode(firstHexToken(hex));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /** Converts a raw signed Q31 integer into a normalized float [-1.0, 1.0]. */
  public static float q31ToFloat(int q31) {
    return Math.max(-1.0f, Math.min(1.0f, (float) q31 / Integer.MAX_VALUE));
  }

  /** Converts a normalized float [-1.0, 1.0] into a raw signed Q31 integer. */
  public static int floatToQ31(float value) {
    value = Math.max(-1.0f, Math.min(1.0f, value));
    return (int) (value * Integer.MAX_VALUE);
  }

  /** Formats a raw signed Q31 integer as a 10-char {@code 0xCCCCCCCC} hex string. */
  public static String q31ToHex(int value) {
    return String.format("0x%08X", value);
  }

  /** Converts a normalized float [-1.0, 1.0] back to Deluge hex format. */
  public static String floatToHex(float value) {
    return q31ToHex(floatToQ31(value));
  }

  /** Converts a raw signed Q31 integer to frequency (Hz) on [20, 20000]. */
  public static float q31ToHz(int q31) {
    float norm = q31ToFloat(q31);
    double exponent = (norm + 1.0f) / 2.0f;
    return (float) (20.0 * Math.pow(1000.0, exponent));
  }

  /** Converts frequency (Hz) on [20, 20000] into a raw signed Q31 integer. */
  public static int hzToQ31(float hz) {
    hz = Math.max(20.0f, Math.min(20000.0f, hz));
    double exponent = Math.log(hz / 20.0) / Math.log(1000.0);
    float norm = (float) (exponent * 2.0 - 1.0);
    return floatToQ31(norm);
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
    return q31ToHex(hzToQ31(hz));
  }

  /** LFO frequency exponential mapping: [-1.0, 1.0] maps to [0.01, 100.0] Hz. */
  public static float hexToLfoHz(String hex) {
    float norm = hexToFloat(hex);
    double exponent = (norm + 1.0f) / 2.0f;
    return (float) (0.01 * Math.pow(10000.0, exponent));
  }

  public static String lfoHzToHex(float hz) {
    hz = Math.max(0.01f, Math.min(100.0f, hz));
    double exponent = Math.log(hz / 0.01) / Math.log(10000.0);
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

  /** Converts a raw signed Q31 integer linearly to the unipolar range [0.0, 1.0]. */
  public static float q31ToUnipolarFloatUnified(int val) {
    double norm = ((double) val + 2147483648.0) / 4294967295.0;
    return (float) Math.max(0.0, Math.min(1.0, norm));
  }

  /** Converts a unipolar float [0.0, 1.0] linearly to a raw signed Q31 integer. */
  public static int unipolarFloatToQ31Unified(float norm) {
    norm = Math.max(0.0f, Math.min(1.0f, norm));
    return (int) (long) (norm * 4294967295.0 - 2147483648.0);
  }

  /** Helper to parse signed 32-bit hex values linearly to the unipolar range [0.0, 1.0]. */
  public static float hexToUnipolarFloatUnified(String hex) {
    if (hex == null || hex.isEmpty()) return 0.0f;
    try {
      long parsed = Long.decode(firstHexToken(hex));
      return q31ToUnipolarFloatUnified((int) parsed);
    } catch (NumberFormatException e) {
      return 0.0f;
    }
  }

  /** Helper to serialize unipolar floats linearly to signed 32-bit hex strings. */
  public static String unipolarFloatToHexUnified(float norm) {
    return q31ToHex(unipolarFloatToQ31Unified(norm));
  }

  /**
   * Read envelope time from hex string, returning seconds (unified signed unipolar exponential
   * mapping).
   */
  public static float hexToEnvTime(String hex) {
    float norm = hexToUnipolarFloatUnified(hex);
    return (float) (0.001 * Math.pow(30000.0, norm));
  }

  /** Write envelope time (seconds) to hex string (unified signed unipolar exponential mapping). */
  public static String envTimeToHex(float timeSec) {
    float clamped = Math.max(0.001f, Math.min(30.0f, timeSec));
    double norm = Math.log(clamped / 0.001) / Math.log(30000.0);
    return unipolarFloatToHexUnified((float) norm);
  }

  // ── Envelope sustain mapping ──

  /**
   * Sustain maps linearly as standard unipolar parameter: [0x80000000, 0x7FFFFFFF] -> [0.0, 1.0].
   */
  public static float hexToSustain(String hex) {
    return hexToUnipolarFloatUnified(hex);
  }

  public static String sustainToHex(float sustain) {
    return unipolarFloatToHexUnified(sustain);
  }

  // ── Legacy Bipolar Sustain Methods (retained for serializer backwards compatibility) ──
  public static float sustainFromNorm(float norm) {
    return (norm + 1.0f) * 0.5f;
  }

  public static float normFromSustain(float sustain) {
    return sustain * 2.0f - 1.0f;
  }
}
