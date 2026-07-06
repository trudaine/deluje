package org.deluge.midi;

import java.util.List;

/**
 * Decoder for the DSP golden-buffer tap replies from the debug firmware (trudaine fork, {@code
 * feat/dsp-buffer-dump}). The firmware captures a window of the master output (int32/q31 left
 * channel) and streams it back in chunks over the Debug SysEx namespace. This reassembles the
 * chunks into the full int32 sample window for bit-exact comparison against the engine.
 *
 * <p>Reply wire format: {@code F0 00 21 7B 01 03 41 00 <7-bit-packed payload> F7}, where the
 * unpacked payload is {@code [capturedCount:4 LE][chunkIndex:1][nSamples:2 LE][nSamples * int32
 * LE]} (matches sysex.cpp {@code dspTapSendChunk}).
 */
public final class DspTapCodec {
  private DspTapCodec() {}

  /** Bytes after the leading 0xF0 that identify a DSP-tap reply. */
  static final byte[] REPLY_HEADER = {0x00, 0x21, 0x7B, 0x01, 0x03, 0x41, 0x00};

  public record Chunk(int capturedCount, int chunkIndex, int nSamples, int[] samples) {}

  /** True if {@code sysex} is a DSP-tap reply (F0 + REPLY_HEADER … F7). */
  public static boolean isTapReply(byte[] sysex) {
    if (sysex == null || sysex.length < 2 + REPLY_HEADER.length) return false;
    if ((sysex[0] & 0xFF) != 0xF0) return false;
    for (int i = 0; i < REPLY_HEADER.length; i++) {
      if (sysex[1 + i] != REPLY_HEADER[i]) return false;
    }
    return true;
  }

  /** Decode one DSP-tap reply SysEx message into a chunk. */
  public static Chunk decode(byte[] sysex) {
    if (!isTapReply(sysex)) {
      throw new IllegalArgumentException("not a DSP-tap reply");
    }
    int start = 1 + REPLY_HEADER.length;
    int end = sysex.length;
    if (end > start && (sysex[end - 1] & 0xFF) == 0xF7) end--; // drop trailing 0xF7
    byte[] packed = new byte[end - start];
    System.arraycopy(sysex, start, packed, 0, packed.length);
    byte[] raw = DelugeMidiPacker.unpack7to8(packed);
    if (raw.length < 7) throw new IllegalArgumentException("truncated tap payload");
    int cap = le32(raw, 0);
    int chunkIndex = raw[4] & 0xFF;
    int n = (raw[5] & 0xFF) | ((raw[6] & 0xFF) << 8);
    int[] samples = new int[n];
    for (int i = 0; i < n; i++) {
      int o = 7 + i * 4;
      if (o + 3 >= raw.length) {
        throw new IllegalArgumentException("tap payload shorter than declared nSamples");
      }
      samples[i] = le32(raw, o);
    }
    return new Chunk(cap, chunkIndex, n, samples);
  }

  /**
   * Reassemble chunks (in any order) into the full captured window. All chunks must report the same
   * {@code capturedCount}; chunk {@code k} carries samples [k*perChunk .. k*perChunk+nSamples).
   */
  public static int[] reassemble(List<Chunk> chunks) {
    if (chunks.isEmpty()) return new int[0];
    int total = chunks.get(0).capturedCount();
    int[] out = new int[total];
    boolean[] filled = new boolean[total];
    int perChunk = -1;
    for (Chunk c : chunks) {
      if (c.capturedCount() != total) {
        throw new IllegalStateException("inconsistent capturedCount across chunks");
      }
      // Infer per-chunk stride from the first full chunk (all but the last are full).
      if (perChunk < 0 && c.nSamples() > 0) perChunk = c.nSamples();
    }
    if (perChunk < 0) perChunk = total;
    for (Chunk c : chunks) {
      int base = c.chunkIndex() * perChunk;
      for (int i = 0; i < c.nSamples(); i++) {
        if (base + i < total) {
          out[base + i] = c.samples()[i];
          filled[base + i] = true;
        }
      }
    }
    return out;
  }

  private static int le32(byte[] b, int o) {
    return (b[o] & 0xFF)
        | ((b[o + 1] & 0xFF) << 8)
        | ((b[o + 2] & 0xFF) << 16)
        | ((b[o + 3] & 0xFF) << 24);
  }

  /**
   * Build a firmware-style tap reply for a chunk (test helper — mirrors sysex.cpp encoding exactly,
   * so a round-trip through {@link #decode} validates the wire format without hardware).
   */
  public static byte[] encodeReply(int capturedCount, int chunkIndex, int[] samples) {
    int n = samples.length;
    byte[] raw = new byte[7 + n * 4];
    raw[0] = (byte) capturedCount;
    raw[1] = (byte) (capturedCount >> 8);
    raw[2] = (byte) (capturedCount >> 16);
    raw[3] = (byte) (capturedCount >> 24);
    raw[4] = (byte) chunkIndex;
    raw[5] = (byte) n;
    raw[6] = (byte) (n >> 8);
    for (int i = 0; i < n; i++) {
      int o = 7 + i * 4;
      int v = samples[i];
      raw[o] = (byte) v;
      raw[o + 1] = (byte) (v >> 8);
      raw[o + 2] = (byte) (v >> 16);
      raw[o + 3] = (byte) (v >> 24);
    }
    byte[] packed = DelugeMidiPacker.pack8to7(raw);
    byte[] out = new byte[1 + REPLY_HEADER.length + packed.length + 1];
    out[0] = (byte) 0xF0;
    System.arraycopy(REPLY_HEADER, 0, out, 1, REPLY_HEADER.length);
    System.arraycopy(packed, 0, out, 1 + REPLY_HEADER.length, packed.length);
    out[out.length - 1] = (byte) 0xF7;
    return out;
  }
}
