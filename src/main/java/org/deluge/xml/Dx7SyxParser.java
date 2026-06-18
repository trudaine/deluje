package org.deluge.xml;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.chuck.audio.util.Dx7Patch;

/**
 * Parses Roland SysEx (.syx) bulk dumps containing Yamaha DX7 voice patches.
 *
 * <p>Bulk dump format (32 voices):
 *
 * <pre>
 *   F0 43 00/01 00 01  {32 × (156 bytes data + 1 checksum)}  F7
 * </pre>
 *
 * Single voice dump format:
 *
 * <pre>
 *   F0 43 00/01 00 00  {156 bytes data + 1 checksum}  F7
 * </pre>
 *
 * <p>Checksum: XOR of all 156 data bytes must equal the checksum byte.
 */
public class Dx7SyxParser {

  /** Roland SysEx manufacturer ID. */
  private static final int SYSEX_START = 0xF0;

  private static final int SYSEX_END = 0xF7;
  private static final int YAMAHA_ID = 0x43;

  /** Sub-status: 0x00 = single voice, 0x01 = bulk dump. */
  private static final int SUB_SINGLE = 0x00;

  private static final int SUB_BULK = 0x01;

  /** Size of a single DX7 voice in bytes (excluding checksum). */
  private static final int VOICE_SIZE = 156;

  /** Size of a voice slot in the SysEx stream (voice data + checksum). */
  private static final int VOICE_SLOT_SIZE = VOICE_SIZE + 1;

  /**
   * Parse a .syx file and return all decoded DX7 patches.
   *
   * @param file the .syx file to parse
   * @return list of decoded patches (never null, may be empty)
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
   * Export a Dx7Patch to a Roland SysEx single-voice dump byte array.
   *
   * @return byte array containing F0 43 00 00 <156 data bytes> <checksum> F7
   */
  public static byte[] patchToSyx(Dx7Patch patch) {
    byte[] raw = patch.raw();
    int checksum = computeChecksum(raw);

    byte[] syx = new byte[2 + 4 + VOICE_SIZE + 1 + 1]; // F0 43 00 00 00 + data + cs + F7
    syx[0] = (byte) SYSEX_START;
    syx[1] = (byte) YAMAHA_ID;
    syx[2] = 0x00; // MIDI channel 1
    syx[3] = 0x00; // sub-status: single voice
    syx[4] = 0x00; // format number (always 0 for single voice)
    System.arraycopy(raw, 0, syx, 5, VOICE_SIZE);
    syx[5 + VOICE_SIZE] = (byte) (checksum & 0x7F);
    syx[6 + VOICE_SIZE] = (byte) SYSEX_END;
    return syx;
  }

  /**
   * Export a Dx7Patch to a Roland SysEx bulk dump byte array (single patch in bulk format).
   *
   * @return byte array containing F0 43 00 00 01 <156 data bytes> <checksum> F7
   */
  public static byte[] patchToSyxBulk(Dx7Patch patch) {
    byte[] raw = patch.raw();
    int checksum = computeChecksum(raw);

    byte[] syx = new byte[2 + 4 + VOICE_SIZE + 1 + 1];
    syx[0] = (byte) SYSEX_START;
    syx[1] = (byte) YAMAHA_ID;
    syx[2] = 0x00; // MIDI channel 1
    syx[3] = 0x01; // sub-status: bulk dump
    syx[4] = 0x00; // format number 0 (voice 1 of 32)
    System.arraycopy(raw, 0, syx, 5, VOICE_SIZE);
    syx[5 + VOICE_SIZE] = (byte) (checksum & 0x7F);
    syx[6 + VOICE_SIZE] = (byte) SYSEX_END;
    return syx;
  }

  // ── Internal parsing ──

  private static List<Dx7Patch> parseSyx(byte[] data, String sourceName) {
    List<Dx7Patch> patches = new ArrayList<>();

    // Skip leading non-F0 bytes
    int pos = 0;
    while (pos < data.length && (data[pos] & 0xFF) != SYSEX_START) {
      pos++;
    }

    while (pos < data.length) {
      // Expect F0
      if ((data[pos] & 0xFF) != SYSEX_START) {
        break; // no more SysEx messages
      }
      pos++;

      // Expect Yamaha ID (0x43)
      if (pos >= data.length || (data[pos] & 0xFF) != YAMAHA_ID) {
        continue; // skip non-Yamaha SysEx
      }
      pos++;

      // Byte channel (00 or 01 — we accept both)
      if (pos >= data.length) break;
      // int channel = data[pos] & 0xFF; // 0x00 or 0x01 typically
      pos++;

      // Sub-status: 00 = single voice, 01 = bulk
      if (pos >= data.length) break;
      int subStatus = data[pos] & 0xFF;
      pos++;

      if (subStatus == SUB_SINGLE) {
        // Single voice: followed by format byte (usually 00), then 156 data bytes + checksum
        if (pos >= data.length) break;
        // int formatNum = data[pos] & 0xFF;
        pos++;

        if (pos + VOICE_SLOT_SIZE > data.length) break;
        patches.add(extractPatch(data, pos));
        pos += VOICE_SLOT_SIZE;
      } else if (subStatus == SUB_BULK) {
        // Bulk dump: followed by format byte, then 32 × (156 + 1) bytes
        if (pos >= data.length) break;
        // int formatNum = data[pos] & 0xFF; // format number / starting voice
        pos++;

        int voiceCount = 32;
        for (int v = 0; v < voiceCount; v++) {
          if (pos + VOICE_SLOT_SIZE > data.length) break;
          patches.add(extractPatch(data, pos));
          pos += VOICE_SLOT_SIZE;
        }
      } else {
        // Unknown sub-status, skip to next F7 or end
        while (pos < data.length && (data[pos] & 0xFF) != SYSEX_END) pos++;
        pos++;
      }

      // Skip trailing F7
      if (pos < data.length && (data[pos] & 0xFF) == SYSEX_END) {
        pos++;
      }
    }

    if (patches.isEmpty()) {
      throw new IllegalArgumentException("No valid DX7 patches found in " + sourceName);
    }

    return patches;
  }

  private static Dx7Patch extractPatch(byte[] data, int off) {
    // Validate checksum: XOR of all 156 data bytes should equal the checksum byte
    int checksum = data[off + VOICE_SIZE] & 0x7F;
    int computed = computeChecksum(data, off, VOICE_SIZE);
    if (checksum != computed) {
      throw new IllegalArgumentException(
          String.format(
              "DX7 patch checksum mismatch: expected 0x%02X, computed 0x%02X", checksum, computed));
    }

    // Build hex string from the 156 data bytes
    StringBuilder hex = new StringBuilder(VOICE_SIZE * 2);
    for (int i = 0; i < VOICE_SIZE; i++) {
      hex.append(String.format("%02X", data[off + i] & 0xFF));
    }

    return Dx7Patch.fromHex(hex.toString());
  }

  /**
   * Compute the DX7 checksum: XOR of all bytes in the range. Checksum is masked to 7 bits (0-127)
   * as per SysEx convention.
   */
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
