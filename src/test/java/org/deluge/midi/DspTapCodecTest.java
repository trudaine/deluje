package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Round-trips the DSP-tap wire format (encode mirrors the firmware; decode is the real path). */
class DspTapCodecTest {

  @Test
  void roundTripSingleChunkPreservesInt32Samples() {
    int[] samples = {0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE, 0x12345678, -0x0BADF00D, 42};
    byte[] sysex = DspTapCodec.encodeReply(samples.length, 0, samples);

    assertTrue(DspTapCodec.isTapReply(sysex));
    assertEquals(0xF0, sysex[0] & 0xFF);
    assertEquals(0xF7, sysex[sysex.length - 1] & 0xFF);

    DspTapCodec.Chunk c = DspTapCodec.decode(sysex);
    assertEquals(samples.length, c.capturedCount());
    assertEquals(0, c.chunkIndex());
    assertEquals(samples.length, c.nSamples());
    assertArrayEquals(samples, c.samples());
  }

  @Test
  void reassembleMultiChunkWindow() {
    // Simulate a 430-sample capture streamed in 180-sample chunks (180 + 180 + 70).
    int total = 430;
    int per = 180;
    int[] full = new int[total];
    for (int i = 0; i < total; i++) full[i] = (i * 2654435761L % 1_000_000) == 0 ? 0 : i - 215;

    List<DspTapCodec.Chunk> chunks = new ArrayList<>();
    for (int idx = 0; idx * per < total; idx++) {
      int base = idx * per;
      int n = Math.min(per, total - base);
      int[] seg = new int[n];
      System.arraycopy(full, base, seg, 0, n);
      byte[] sysex = DspTapCodec.encodeReply(total, idx, seg);
      chunks.add(DspTapCodec.decode(sysex));
    }
    assertEquals(3, chunks.size());
    assertArrayEquals(full, DspTapCodec.reassemble(chunks));
  }

  @Test
  void rejectsNonTapSysex() {
    byte[] notTap = {(byte) 0xF0, 0x7E, 0x00, 0x06, 0x01, (byte) 0xF7};
    assertEquals(false, DspTapCodec.isTapReply(notTap));
  }
}
