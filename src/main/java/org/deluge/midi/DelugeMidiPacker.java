package org.deluge.midi;

/**
 * Handles 7-to-8 bit packing and 8-to-7 bit unpacking for binary data transferred over MIDI.
 *
 * <p>Matches the Sequential/DSI manual packed data format implemented in the Deluge C++ firmware. A
 * 7-byte block of 8-bit data is encoded into an 8-byte SysEx-safe block, where the first byte of
 * the group holds the MSB (highest bit 0x80) of the subsequent 7 bytes.
 */
public class DelugeMidiPacker {

  /**
   * Pack a standard 8-bit byte array into a 7-bit SysEx-safe byte array.
   *
   * @param src Unpacked 8-bit source bytes
   * @return Packed 7-bit destination bytes
   */
  public static byte[] pack8to7(byte[] src) {
    if (src == null) return new byte[0];
    int srcLen = src.length;
    int packets = (srcLen + 6) / 7;
    int missing = (7 * packets - srcLen);
    int outLen = 8 * packets - missing;
    byte[] dst = new byte[outLen];

    for (int i = 0; i < packets; i++) {
      int ipos = 7 * i;
      int opos = 8 * i;
      dst[opos] = 0;
      for (int j = 0; j < 7; j++) {
        if (ipos + j >= srcLen) {
          break;
        }
        int val = src[ipos + j] & 0xFF;
        dst[opos + 1 + j] = (byte) (val & 0x7F);
        if ((val & 0x80) != 0) {
          dst[opos] |= (byte) (1 << j);
        }
      }
    }
    return dst;
  }

  /**
   * Unpack a 7-bit SysEx-safe byte array back into a standard 8-bit byte array.
   *
   * @param src Packed 7-bit source bytes
   * @return Unpacked 8-bit destination bytes
   */
  public static byte[] unpack7to8(byte[] src) {
    if (src == null) return new byte[0];
    int srcLen = src.length;
    int packets = (srcLen + 7) / 8;
    int missing = (8 * packets - srcLen);
    if (missing == 7) {
      packets--;
      missing = 0;
    }
    int outLen = 7 * packets - missing;
    byte[] dst = new byte[outLen];

    for (int i = 0; i < packets; i++) {
      int ipos = 8 * i;
      int opos = 7 * i;
      if (ipos >= srcLen) break;
      int highBits = src[ipos] & 0xFF;
      int rotBit = 1;
      for (int j = 0; j < 7; j++) {
        if (j + 1 + ipos >= srcLen) {
          break;
        }
        dst[opos + j] = (byte) (src[ipos + 1 + j] & 0x7F);
        if ((highBits & rotBit) != 0) {
          dst[opos + j] |= (byte) 0x80;
        }
        rotBit <<= 1;
      }
    }
    return dst;
  }

  /**
   * Unpack a Run-Length Encoded (RLE) 7-bit SysEx-safe byte array back into a standard 8-bit byte
   * array. Translated directly from the native Deluge C++ firmware's unpack_7to8_rle.
   */
  public static byte[] unpack7to8Rle(byte[] src, int expectedDstSize) {
    if (src == null) return new byte[0];
    byte[] dst = new byte[expectedDstSize];
    int d = 0;
    int s = 0;
    int srcLen = src.length;

    while (s < srcLen) {
      if (s + 1 > srcLen) {
        break;
      }
      int first = src[s++] & 0xFF;
      if (first < 64) {
        int size = 0;
        int off = 0;
        if (first < 4) {
          size = 2;
          off = 0;
        } else if (first < 12) {
          size = 3;
          off = 4;
        } else if (first < 28) {
          size = 4;
          off = 12;
        } else if (first < 60) {
          size = 5;
          off = 28;
        } else {
          break;
        }

        if (size > srcLen - s) {
          break;
        }
        if (size > expectedDstSize - d) {
          break;
        }
        int highbits = first - off;
        for (int j = 0; j < size; j++) {
          dst[d + j] = (byte) (src[s + j] & 0x7F);
          if ((highbits & (1 << j)) != 0) {
            dst[d + j] |= (byte) 0x80;
          }
        }
        d += size;
        s += size;
      } else {
        // RLE run
        first = first - 64;
        int high = first & 1;
        int runlen = first >> 1;
        if (runlen == 31) {
          if (s >= srcLen) {
            break;
          }
          runlen = 31 + (src[s++] & 0xFF);
        }
        if (s >= srcLen) {
          break;
        }
        int val = (src[s++] & 0xFF) + 128 * high;
        if (runlen > expectedDstSize - d) {
          break;
        }
        for (int j = 0; j < runlen; j++) {
          dst[d + j] = (byte) val;
        }
        d += runlen;
      }
    }

    if (d < expectedDstSize) {
      byte[] trimmed = new byte[d];
      System.arraycopy(dst, 0, trimmed, 0, d);
      return trimmed;
    }
    return dst;
  }
}
