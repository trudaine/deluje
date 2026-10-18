package org.deluge.xml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.deluge.shadow.audio.Dx7Patch;

/**
 * Parses and exports Yamaha DX7 SysEx ({@code .syx}) voice dumps (C {@code DX7Cartridge.h}, {@code
 * DX7Cartridge.cpp}).
 *
 * <p>Supported formats:
 *
 * <ul>
 *   <li><b>Yamaha 32-voice packed cartridge (4104 bytes)</b>: {@code F0 43 0n 09 20 00} + {@code 32
 *       × 128} packed bytes + 7-bit two's-complement checksum + {@code F7} (C {@code
 *       DX7Cartridge.h:33-34}, {@code DX7Cartridge.cpp:155-214}).
 *   <li><b>Yamaha 1-voice unpacked VCED dump (163 bytes)</b>: {@code F0 43 0n 00 01 1B} + {@code
 *       155} unpacked bytes + 7-bit two's-complement checksum + {@code F7} (C {@code
 *       DX7Cartridge.h:35}, {@code DX7Cartridge.cpp:39-49}).
 *   <li><b>Legacy unpacked format</b>: 5-byte header ({@code F0 43 0n 00/01 00}) + 1 or 32 slots of
 *       ({@code 156} unpacked bytes + 1 checksum) + {@code F7}.
 * </ul>
 */
public class Dx7SyxParser {

  private static final int SYSEX_START = 0xF0;
  private static final int SYSEX_END = 0xF7;
  private static final int YAMAHA_ID = 0x43;

  private static final int FORMAT_SINGLE_VCED = 0x00;
  private static final int FORMAT_LEGACY_BULK = 0x01;
  private static final int FORMAT_PACKED_CARTRIDGE = 0x09;

  /** Unpacked DX7 voice size in internal memory (155 voice bytes + byte 155 opSwitch). */
  private static final int VOICE_SIZE = 156;

  /** Unpacked DX7 VCED SysEx payload size (bytes 0..154, without opSwitch). */
  private static final int VCED_DATA_SIZE = 155;

  /** Packed DX7 VMEM voice size inside a 32-voice cartridge. */
  private static final int PACKED_VOICE_SIZE = 128;

  /** Total packed data bytes in a 32-voice cartridge (32 * 128). */
  private static final int CARTRIDGE_DATA_SIZE = 4096;

  /** Legacy slot size (156 unpacked bytes + 1 checksum byte). */
  private static final int LEGACY_SLOT_SIZE = VOICE_SIZE + 1;

  /**
   * Parse a .syx file and return all decoded DX7 patches.
   *
   * @param file the .syx file to parse
   * @return list of decoded patches (never null, non-empty)
   * @throws IOException if the file cannot be read
   * @throws IllegalArgumentException if the data is malformed
   */
  public static List<Dx7Patch> parseSyx(File file) throws IOException {
    byte[] syxData = Files.readAllBytes(file.toPath());
    return parseSyx(syxData, file.getName());
  }

  /**
   * Parse a .syx input stream and return all decoded DX7 patches.
   *
   * @param is the input stream containing the SysEx data
   * @param sourceName name for error messages (e.g. filename)
   * @return list of decoded patches
   * @throws IOException if the stream cannot be read
   */
  public static List<Dx7Patch> parseSyx(InputStream is, String sourceName) throws IOException {
    byte[] syxData = is.readAllBytes();
    return parseSyx(syxData, sourceName);
  }

  /** Convert a Dx7Patch back to its 312-character hex string (for internal XML storage). */
  public static String patchToHex(Dx7Patch patch) {
    byte[] raw = patch.raw();
    StringBuilder sb = new StringBuilder(raw.length * 2);
    for (byte b : raw) {
      sb.append(String.format("%02X", b & 0xFF));
    }
    return sb.toString();
  }

  /**
   * Export a Dx7Patch to a standard 163-byte Yamaha DX7 VCED single-voice SysEx dump (C {@code
   * exportSysexPgm}, {@code DX7Cartridge.cpp:39-49}).
   *
   * @return 163-byte array {@code F0 43 00 00 01 1B <155 bytes> <checksum> F7}
   */
  public static byte[] patchToSyx(Dx7Patch patch) {
    byte[] raw = ensureUnpacked156(patch.raw());
    byte[] syx = new byte[6 + VCED_DATA_SIZE + 2]; // 163 bytes
    syx[0] = (byte) SYSEX_START;
    syx[1] = (byte) YAMAHA_ID;
    syx[2] = 0x00; // sub-status 0, channel 1
    syx[3] = (byte) FORMAT_SINGLE_VCED;
    syx[4] = 0x01; // byte count MSB (155 = (1 << 7) | 0x1B)
    syx[5] = 0x1B; // byte count LSB
    for (int i = 0; i < VCED_DATA_SIZE; i++) {
      syx[6 + i] = (byte) (raw[i] & 0x7F);
    }
    syx[6 + VCED_DATA_SIZE] = (byte) sysexChecksum(syx, 6, VCED_DATA_SIZE);
    syx[7 + VCED_DATA_SIZE] = (byte) SYSEX_END;
    return syx;
  }

  /**
   * Export a Dx7Patch into slot 0 of a standard 4104-byte Yamaha DX7 32-voice packed cartridge (C
   * {@code DX7Cartridge.h:33-44}, {@code DX7Cartridge.cpp:54-105}). Slots 1..31 are populated with
   * the default DX7 init voice.
   *
   * @return 4104-byte array {@code F0 43 00 09 20 00 <4096 packed bytes> <checksum> F7}
   */
  public static byte[] patchToSyxBulk(Dx7Patch patch) {
    byte[] syx = new byte[6 + CARTRIDGE_DATA_SIZE + 2]; // 4104 bytes
    syx[0] = (byte) SYSEX_START;
    syx[1] = (byte) YAMAHA_ID;
    syx[2] = 0x00; // sub-status 0, channel 1
    syx[3] = (byte) FORMAT_PACKED_CARTRIDGE;
    syx[4] = 0x20; // byte count MSB (4096 = 0x20 << 7)
    syx[5] = 0x00; // byte count LSB

    byte[] firstVoice = ensureUnpacked156(patch.raw());
    byte[] initVoice = Dx7Patch.createInitVoiceBytes();
    for (int idx = 0; idx < 32; idx++) {
      byte[] src = (idx == 0) ? firstVoice : initVoice;
      packProgram(src, syx, 6 + idx * PACKED_VOICE_SIZE);
    }

    syx[6 + CARTRIDGE_DATA_SIZE] = (byte) sysexChecksum(syx, 6, CARTRIDGE_DATA_SIZE);
    syx[7 + CARTRIDGE_DATA_SIZE] = (byte) SYSEX_END;
    return syx;
  }

  // ── Internal parsing ──

  public static List<Dx7Patch> parseSyx(byte[] data, String sourceName) {
    if (data == null || data.length == 0) {
      throw new IllegalArgumentException("Empty DX7 SysEx data in " + sourceName);
    }

    List<Dx7Patch> patches = new ArrayList<>();
    int pos = 0;
    while (pos < data.length && (data[pos] & 0xFF) != SYSEX_START) {
      pos++;
    }

    while (pos < data.length) {
      if ((data[pos] & 0xFF) != SYSEX_START) {
        break;
      }
      pos++;

      if (pos >= data.length || (data[pos] & 0xFF) != YAMAHA_ID) {
        while (pos < data.length && (data[pos] & 0xFF) != SYSEX_END) {
          pos++;
        }
        if (pos < data.length) {
          pos++;
        }
        while (pos < data.length && (data[pos] & 0xFF) != SYSEX_START) {
          pos++;
        }
        continue;
      }
      pos++; // skip 0x43

      if (pos >= data.length) {
        break;
      }
      pos++; // skip sub-status + MIDI channel byte (0x00..0x0F)

      if (pos >= data.length) {
        break;
      }
      int format = data[pos++] & 0xFF;

      if (format == FORMAT_PACKED_CARTRIDGE) {
        // Yamaha 32-voice packed cartridge: 2 byte-count bytes (0x20 0x00 = 4096), 4096 bytes, cs
        if (pos + 2 > data.length) {
          break;
        }
        int msb = data[pos++] & 0x7F;
        int lsb = data[pos++] & 0x7F;
        int byteCount = (msb << 7) | lsb;
        int dataLen = (byteCount == CARTRIDGE_DATA_SIZE) ? byteCount : CARTRIDGE_DATA_SIZE;
        if (pos + dataLen + 1 > data.length) {
          break;
        }
        validateChecksum(data, pos, dataLen, data[pos + dataLen] & 0x7F);
        for (int idx = 0; idx < 32; idx++) {
          // C cartridge.cpp:100-101, dx7note.cpp:56-58 — destination starts from init_voice.
          byte[] unpacked = Dx7Patch.createInitVoiceBytes();
          unpackProgram(data, pos + idx * PACKED_VOICE_SIZE, unpacked);
          unpacked[Dx7Patch.OFF_OP_SWITCH] = 0x3F;
          patches.add(Dx7Patch.fromHex(Dx7Patch.bytesToHex(unpacked)));
        }
        pos += dataLen + 1;
      } else if (format == FORMAT_SINGLE_VCED) {
        if (isStandardVcedDump(data, pos)) {
          // Standard Yamaha 163-byte VCED dump: byte count 0x01 0x1B (155), 155 data bytes, cs, F7
          pos += 2;
          validateChecksum(data, pos, VCED_DATA_SIZE, data[pos + VCED_DATA_SIZE] & 0x7F);
          byte[] unpacked = new byte[VOICE_SIZE];
          for (int i = 0; i < VCED_DATA_SIZE; i++) {
            unpacked[i] = (byte) (data[pos + i] & 0x7F);
          }
          unpacked[Dx7Patch.OFF_OP_SWITCH] = 0x3F;
          patches.add(Dx7Patch.fromHex(Dx7Patch.bytesToHex(unpacked)));
          pos += VCED_DATA_SIZE + 1;
        } else {
          // Legacy 5-byte-header single voice: 1 format byte + 156 bytes + 1 checksum
          if (pos >= data.length) {
            break;
          }
          pos++;
          if (pos + LEGACY_SLOT_SIZE > data.length) {
            break;
          }
          patches.add(extractLegacyPatch(data, pos));
          pos += LEGACY_SLOT_SIZE;
        }
      } else if (format == FORMAT_LEGACY_BULK) {
        // Legacy 5-byte-header bulk dump: 1 format byte + 32 × (156 + 1) bytes
        if (pos >= data.length) {
          break;
        }
        pos++;
        for (int v = 0; v < 32; v++) {
          if (pos + LEGACY_SLOT_SIZE > data.length) {
            break;
          }
          patches.add(extractLegacyPatch(data, pos));
          pos += LEGACY_SLOT_SIZE;
        }
      } else {
        while (pos < data.length && (data[pos] & 0xFF) != SYSEX_END) {
          pos++;
        }
      }

      if (pos < data.length && (data[pos] & 0xFF) == SYSEX_END) {
        pos++;
      }
      while (pos < data.length && (data[pos] & 0xFF) != SYSEX_START) {
        pos++;
      }
    }

    if (patches.isEmpty()) {
      throw new IllegalArgumentException("No valid DX7 patches found in " + sourceName);
    }
    return patches;
  }

  private static boolean isStandardVcedDump(byte[] data, int pos) {
    if (pos + 2 + VCED_DATA_SIZE + 1 > data.length) {
      return false;
    }
    int msb = data[pos] & 0x7F;
    int lsb = data[pos + 1] & 0x7F;
    return ((msb << 7) | lsb) == VCED_DATA_SIZE;
  }

  private static Dx7Patch extractLegacyPatch(byte[] data, int off) {
    validateChecksum(data, off, VOICE_SIZE, data[off + VOICE_SIZE] & 0x7F);
    byte[] unpacked = new byte[VOICE_SIZE];
    System.arraycopy(data, off, unpacked, 0, VOICE_SIZE);
    return Dx7Patch.fromHex(Dx7Patch.bytesToHex(unpacked));
  }

  private static void validateChecksum(byte[] data, int off, int len, int expected) {
    int twosComp = sysexChecksum(data, off, len);
    int xorSum = computeChecksum(data, off, len);
    if (expected != twosComp && expected != xorSum) {
      throw new IllegalArgumentException(
          String.format(
              "DX7 patch checksum mismatch: expected 0x%02X, computed 0x%02X", expected, twosComp));
    }
  }

  private static byte[] ensureUnpacked156(byte[] raw) {
    if (raw != null && raw.length >= VOICE_SIZE) {
      return raw;
    }
    byte[] out = Dx7Patch.createInitVoiceBytes();
    if (raw != null) {
      System.arraycopy(raw, 0, out, 0, raw.length);
    }
    return out;
  }

  /**
   * Unpack one 128-byte packed VMEM voice into a 156-byte unpacked voice buffer (C {@code
   * DX7Cartridge::unpackProgram}, {@code DX7Cartridge.cpp:155-214}).
   */
  static void unpackProgram(byte[] bulk, int bulkOff, byte[] unpackPgm) {
    for (int op = 0; op < 6; op++) {
      int pp = bulkOff + op * 17;
      int up = op * 21;

      // C DX7Cartridge.cpp:168-170 — loops i = 0..9 only; unpack_op[10] (right depth) is left
      // untouched at whatever the destination buffer held (init_voice = 0).
      for (int i = 0; i < 10; i++) {
        unpackPgm[up + i] = (byte) normparm(bulk[pp + i] & 0x7F, 99);
      }

      // C :172-174 — unpackBits<2, 2>(clearTopBit(bulk_op[11]))
      int curves = bulk[pp + 11] & 0x7F;
      unpackPgm[up + 11] = (byte) (curves & 0x03);
      unpackPgm[up + 12] = (byte) ((curves >> 2) & 0x03);

      // C :176-177, 188 — unpackBits<3, 4>(clearTopBit(bulk_op[12])), no normparm on detune.
      int detuneRs = bulk[pp + 12] & 0x7F;
      unpackPgm[up + 13] = (byte) (detuneRs & 0x07);
      unpackPgm[up + 20] = (byte) ((detuneRs >> 3) & 0x0F);

      // C :179-181 — unpackBits<2, 3>(clearTopBit(bulk_op[13]))
      int kvsAms = bulk[pp + 13] & 0x7F;
      unpackPgm[up + 14] = (byte) (kvsAms & 0x03);
      unpackPgm[up + 15] = (byte) ((kvsAms >> 2) & 0x07);

      // C :182 — clearTopBit(bulk_op[14]) only, no normparm.
      unpackPgm[up + 16] = (byte) (bulk[pp + 14] & 0x7F);

      // C :184-186 — unpackBits<1, 5>(clearTopBit(bulk_op[15]))
      int coarseMode = bulk[pp + 15] & 0x7F;
      unpackPgm[up + 17] = (byte) (coarseMode & 0x01);
      unpackPgm[up + 18] = (byte) ((coarseMode >> 1) & 0x1F);

      // C :187 — clearTopBit(bulk_op[16]) only, no normparm.
      unpackPgm[up + 19] = (byte) (bulk[pp + 16] & 0x7F);
    }

    // C :191-193 — pitch EG bytes 126..133 use normparm(..., 99).
    for (int i = 0; i < 8; i++) {
      unpackPgm[126 + i] = (byte) normparm(bulk[bulkOff + 102 + i] & 0x7F, 99);
    }

    // C :196 — algorithm uses clearTopNBits(..., 3) + normparm(..., 31).
    unpackPgm[134] = (byte) normparm(bulk[bulkOff + 110] & 0x1F, 31);

    // C :198-200 — unpackBits<3, 1>(clearTopBit(bulk[111]))
    int oksFb = bulk[bulkOff + 111] & 0x7F;
    unpackPgm[135] = (byte) (oksFb & 0x07);
    unpackPgm[136] = (byte) ((oksFb >> 3) & 0x01);

    // C :201-204 — LFO speed, delay, PMD, AMD use clearTopBit only, no normparm.
    unpackPgm[137] = (byte) (bulk[bulkOff + 112] & 0x7F);
    unpackPgm[138] = (byte) (bulk[bulkOff + 113] & 0x7F);
    unpackPgm[139] = (byte) (bulk[bulkOff + 114] & 0x7F);
    unpackPgm[140] = (byte) (bulk[bulkOff + 115] & 0x7F);

    // C DX7Cartridge.cpp:206 — unpackBits<1, 4, 2>(clearTopBit(bulk[116])) LSB-first:
    // bit 0 = LKS (1b), bits 1..4 = LFW (4b, no clamp), bits 5..6 = LPMS (2b).
    // (Note: C packProgram at :87 uses the Yamaha spec <1, 3, 3> layout; unpack matches :206.)
    int lpmsLfwLks = bulk[bulkOff + 116] & 0x7F;
    unpackPgm[141] = (byte) (lpmsLfwLks & 0x01);
    unpackPgm[142] = (byte) ((lpmsLfwLks >> 1) & 0x0F);
    unpackPgm[143] = (byte) ((lpmsLfwLks >> 5) & 0x03);

    // C :210 — transpose uses clearTopBit only, no normparm.
    unpackPgm[144] = (byte) (bulk[bulkOff + 117] & 0x7F);

    for (int i = 0; i < 10; i++) {
      unpackPgm[145 + i] = (byte) (bulk[bulkOff + 118 + i] & 0x7F);
    }
  }

  /**
   * Pack one 156-byte unpacked voice into a 128-byte VMEM cartridge slot (C {@code
   * DX7Cartridge::packProgram}, {@code DX7Cartridge.cpp:54-105}).
   */
  static void packProgram(byte[] src, byte[] bulk, int bulkOff) {
    int opSwitch =
        (src.length > Dx7Patch.OFF_OP_SWITCH) ? (src[Dx7Patch.OFF_OP_SWITCH] & 0x3F) : 0x3F;
    for (int op = 0; op < 6; op++) {
      int pp = bulkOff + op * 17;
      int up = op * 21;

      for (int i = 0; i < 11; i++) {
        bulk[pp + i] = (byte) (src[up + i] & 0x7F);
      }
      bulk[pp + 11] = (byte) ((src[up + 11] & 0x03) | ((src[up + 12] & 0x03) << 2));
      bulk[pp + 12] = (byte) ((src[up + 13] & 0x07) | ((src[up + 20] & 0x0F) << 3));
      bulk[pp + 13] = (byte) ((src[up + 14] & 0x03) | ((src[up + 15] & 0x07) << 2));
      // C DX7Cartridge.cpp:71-76 — opSwitch bit is indexed by operator slot (op 0..5).
      boolean opEnabled = ((opSwitch >> op) & 1) != 0;
      bulk[pp + 14] = opEnabled ? (byte) (src[up + 16] & 0x7F) : 0;
      bulk[pp + 15] = (byte) ((src[up + 17] & 0x01) | ((src[up + 18] & 0x1F) << 1));
      bulk[pp + 16] = (byte) (src[up + 19] & 0x7F);
    }

    for (int i = 0; i < 8; i++) {
      bulk[bulkOff + 102 + i] = (byte) (src[126 + i] & 0x7F);
    }
    bulk[bulkOff + 110] = (byte) (src[134] & 0x1F);
    bulk[bulkOff + 111] = (byte) ((src[135] & 0x07) | ((src[136] & 0x01) << 3));
    for (int i = 0; i < 4; i++) {
      bulk[bulkOff + 112 + i] = (byte) (src[137 + i] & 0x7F);
    }
    bulk[bulkOff + 116] =
        (byte) ((src[141] & 0x01) | ((src[142] & 0x07) << 1) | ((src[143] & 0x07) << 4));
    bulk[bulkOff + 117] = (byte) (src[144] & 0x7F);

    boolean eos = false;
    for (int i = 0; i < 10; i++) {
      int c = src[145 + i] & 0xFF;
      if (c == 0) {
        eos = true;
      }
      if (eos || c < 32 || c > 127) {
        bulk[bulkOff + 118 + i] = ' ';
      } else {
        bulk[bulkOff + 118 + i] = (byte) c;
      }
    }
  }

  /** C {@code normparm} ({@code DX7Cartridge.cpp:111-120}). */
  static int normparm(int value, int max) {
    if (value >= 0 && value <= max) {
      return value;
    }
    return (int) (((float) (value & 0xFF)) / 255.0f * max);
  }

  /**
   * Compute the standard Yamaha SysEx 7-bit two's-complement checksum (C {@code sysexChecksum},
   * {@code DX7Cartridge.cpp:32-37}).
   */
  static int sysexChecksum(byte[] data, int off, int len) {
    int sum = 0;
    for (int i = 0; i < len; i++) {
      sum -= (data[off + i] & 0xFF);
    }
    return sum & 0x7F;
  }

  /** Legacy XOR checksum masked to 7 bits. */
  static int computeChecksum(byte[] data, int off, int len) {
    int xor = 0;
    for (int i = 0; i < len; i++) {
      xor ^= (data[off + i] & 0xFF);
    }
    return xor & 0x7F;
  }

  static int computeChecksum(byte[] data) {
    return computeChecksum(data, 0, data.length);
  }
}
