package org.chuck.deluge.midi;

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
}
