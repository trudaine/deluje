package org.chuck.audio.util;

/**
 * A lightweight shadow wrapper representing a DX7 Voice Patch. Implemented in pure Java to
 * completely decouple the Deluge UI from the heavy native ChucK VM.
 */
public class Dx7Patch {
  public static final int OFF_OP_SWITCH = 0;

  public static final int OFF_ALGORITHM = 134;
  public static final int OFF_FEEDBACK = 135;
  public static final int OFF_TRANSPOSE = 144;
  public static final int OFF_LFO_WAVEFORM = 136;
  public static final int OFF_LFO_SPEED = 137;
  public static final int OFF_LFO_DELAY = 138;
  public static final int OFF_PMOD_DEPTH = 139;
  public static final int OFF_AMOD_DEPTH = 140;
  public static final int OFF_LFO_SYNC = 141;

  private byte[] rawBytes = new byte[128];

  public static byte[] hexToBytes(String hex) {
    int len = hex.length();
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      data[i / 2] =
          (byte)
              ((Character.digit(hex.charAt(i), 16) << 4) + Character.digit(hex.charAt(i + 1), 16));
    }
    return data;
  }

  public static String bytesToHex(byte[] bytes) {
    StringBuilder sb = new StringBuilder();
    for (byte b : bytes) {
      sb.append(String.format("%02x", b));
    }
    return sb.toString();
  }

  public static Dx7Patch fromHex(String hex) {
    Dx7Patch p = new Dx7Patch();
    p.rawBytes = hexToBytes(hex);
    return p;
  }

  public byte[] raw() {
    return rawBytes;
  }

  public int algorithm() {
    return 1; // Default algorithm
  }

  public int opSwitch() {
    return 63; // All 6 operators active by default
  }

  public String name() {
    return "DX7 Voice";
  }
}
