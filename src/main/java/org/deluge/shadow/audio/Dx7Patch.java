package org.deluge.shadow.audio;

/**
 * Lightweight pure-Java model of a 156-byte unpacked Yamaha DX7 voice patch (C {@code
 * dx7note.h:42}, {@code dx7note.cpp:42-48}, {@code DX7Cartridge.cpp:155-214}).
 */
public class Dx7Patch {
  /** Unpacked DX7 voice size in bytes (155 voice bytes + byte 155 opSwitch; C dx7note.h:42). */
  public static final int VOICE_SIZE = 156;

  public static final int OFF_ALGORITHM = 134;
  public static final int OFF_FEEDBACK = 135;
  public static final int OFF_OSC_SYNC = 136;
  public static final int OFF_LFO_SPEED = 137;
  public static final int OFF_LFO_DELAY = 138;
  public static final int OFF_PMOD_DEPTH = 139;
  public static final int OFF_AMOD_DEPTH = 140;
  public static final int OFF_LFO_SYNC = 141;
  // C dx7note.cpp:122, DX7Cartridge.cpp:208 — LFO waveform is unpacked byte 142 (0..5).
  public static final int OFF_LFO_WAVEFORM = 142;
  public static final int OFF_PMOD_SENS = 143;
  public static final int OFF_TRANSPOSE = 144;
  public static final int OFF_NAME = 145;
  public static final int NAME_LEN = 10;
  // C dx7note.h:44, dx7note.cpp:48 — operator on/off bitmask (0x00..0x3F) is unpacked byte 155.
  public static final int OFF_OP_SWITCH = 155;

  // Verbatim init_voice from C dx7note.cpp:42-48.
  private static final int[] INIT_VOICE = {
    99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7, 99, 99, 99, 99, 99, 99,
    99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7, 99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 1, 0, 7, 99, 99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7, 99,
    99, 99, 99, 99, 99, 99, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 0, 7, 99, 99, 99, 99, 99, 99, 99, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 99, 0, 1, 0, 7, 99, 99, 99, 99, 50, 50, 50, 50, 0, 0, 1, 35, 0, 0, 0, 1,
    0, 3, 24, 73, 78, 73, 84, 32, 86, 79, 73, 67, 69, 63
  };

  private byte[] rawBytes = createInitVoiceBytes();
  // C param.cpp:283-288 — unpacked slot 0..5 is DX7 OP6..OP1 (buffer[2] = '6' - op).
  // operators()[0..5] exposes OP1..OP6 in hardware/manual order (slot 5..0).
  private final Operator[] ops =
      new Operator[] {
        new Operator(this, slotForOperator(0)),
        new Operator(this, slotForOperator(1)),
        new Operator(this, slotForOperator(2)),
        new Operator(this, slotForOperator(3)),
        new Operator(this, slotForOperator(4)),
        new Operator(this, slotForOperator(5))
      };

  /**
   * Convert a 0-based DX7 operator index (0 = OP1 .. 5 = OP6) to its unpacked byte slot (5 = OP1 ..
   * 0 = OP6; C {@code param.cpp:288}).
   */
  public static int slotForOperator(int opIndex0To5) {
    return 5 - opIndex0To5;
  }

  public static byte[] createInitVoiceBytes() {
    byte[] out = new byte[VOICE_SIZE];
    for (int i = 0; i < VOICE_SIZE; i++) {
      out[i] = (byte) INIT_VOICE[i];
    }
    return out;
  }

  public static byte[] hexToBytes(String hex) {
    if (hex == null || hex.isEmpty()) {
      return new byte[0];
    }
    int len = hex.length() & ~1;
    byte[] data = new byte[len / 2];
    for (int i = 0; i < len; i += 2) {
      int hi = Character.digit(hex.charAt(i), 16);
      int lo = Character.digit(hex.charAt(i + 1), 16);
      data[i / 2] = (byte) (((hi < 0 ? 0 : hi) << 4) + (lo < 0 ? 0 : lo));
    }
    return data;
  }

  public static String bytesToHex(byte[] bytes) {
    if (bytes == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      sb.append(String.format("%02x", b & 0xFF));
    }
    return sb.toString();
  }

  public static Dx7Patch fromHex(String hex) {
    Dx7Patch p = new Dx7Patch();
    byte[] decoded = hexToBytes(hex);
    if (decoded.length >= VOICE_SIZE) {
      p.rawBytes = decoded;
    } else {
      byte[] padded = createInitVoiceBytes();
      System.arraycopy(decoded, 0, padded, 0, decoded.length);
      p.rawBytes = padded;
    }
    return p;
  }

  public byte[] raw() {
    return rawBytes;
  }

  private int getByte(int index) {
    if (rawBytes == null || index < 0 || index >= rawBytes.length) {
      return 0;
    }
    return rawBytes[index] & 0xFF;
  }

  public int algorithm() {
    return getByte(OFF_ALGORITHM) & 0x1F;
  }

  public int opSwitch() {
    return (rawBytes != null && rawBytes.length > OFF_OP_SWITCH)
        ? (rawBytes[OFF_OP_SWITCH] & 0x3F)
        : 0x3F;
  }

  /**
   * Decode the 10-character ASCII voice name at bytes 145..154, applying the same character
   * normalization as C {@code DX7Cartridge::normalizePgmName} ({@code DX7Cartridge.h:49-81}).
   */
  public String name() {
    if (rawBytes == null || rawBytes.length < OFF_NAME + NAME_LEN) {
      return "INIT VOICE";
    }
    char[] buf = new char[NAME_LEN];
    for (int j = 0; j < NAME_LEN; j++) {
      int c = rawBytes[OFF_NAME + j] & 0x7F;
      switch (c) {
        case 92 -> c = 'Y'; // yen
        case 126 -> c = '>'; // >>
        case 127 -> c = '<'; // <<
        default -> {
          if (c < 32) {
            c = 32;
          }
        }
      }
      buf[j] = (char) c;
    }
    int end = NAME_LEN;
    while (end > 0 && buf[end - 1] == ' ') {
      end--;
    }
    return end == 0 ? "INIT VOICE" : new String(buf, 0, end);
  }

  public int feedback() {
    return getByte(OFF_FEEDBACK) & 0x07;
  }

  public int transpose() {
    return getByte(OFF_TRANSPOSE);
  }

  public static class Operator {
    private final Dx7Patch parent;
    private final int opIndex;

    public Operator() {
      this(null, 0);
    }

    Operator(Dx7Patch parent, int opIndex) {
      this.parent = parent;
      this.opIndex = opIndex;
    }

    private int read(int fieldOffset, int fallback) {
      if (parent == null) {
        return fallback;
      }
      return parent.getByte(opIndex * 21 + fieldOffset);
    }

    public int outputLevel() {
      return read(16, 99);
    }

    public int mode() {
      return read(17, 0) & 0x01;
    }

    public int coarseFreq() {
      return read(18, 1) & 0x1F;
    }

    public int fineFreq() {
      return read(19, 0);
    }

    public int detune() {
      return read(20, 7) & 0x0F;
    }

    public int velSens() {
      return read(15, 0) & 0x07;
    }

    public int ampModSens() {
      return read(14, 0) & 0x03;
    }

    public int[] egRate() {
      return new int[] {read(0, 99), read(1, 99), read(2, 99), read(3, 99)};
    }

    public int[] egLevel() {
      return new int[] {read(4, 99), read(5, 99), read(6, 99), read(7, 0)};
    }
  }

  public Operator[] operators() {
    return ops;
  }
}
