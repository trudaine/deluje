package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Random;
import org.junit.jupiter.api.Test;

public class DelugeMidiPackerTest {

  @Test
  public void testAllZeros() {
    byte[] input = new byte[100];
    byte[] packed = DelugeMidiPacker.pack8to7(input);

    // Every 8th byte should be 0 (the high bits byte)
    for (int i = 0; i < packed.length; i += 8) {
      assertEquals(0, packed[i]);
    }

    byte[] unpacked = DelugeMidiPacker.unpack7to8(packed);
    assertArrayEquals(input, unpacked);
  }

  @Test
  public void testAllHighBitsSet() {
    byte[] input = new byte[50];
    for (int i = 0; i < input.length; i++) {
      input[i] = (byte) 0xFF;
    }

    byte[] packed = DelugeMidiPacker.pack8to7(input);
    byte[] unpacked = DelugeMidiPacker.unpack7to8(packed);

    assertArrayEquals(input, unpacked);
  }

  @Test
  public void testBoundaryLengths() {
    int[] lengths = {1, 2, 6, 7, 8, 13, 14, 15, 100, 105, 1000};
    Random rand = new Random(42);

    for (int len : lengths) {
      byte[] input = new byte[len];
      rand.nextBytes(input);

      byte[] packed = DelugeMidiPacker.pack8to7(input);

      // Verify no byte in packed has the high bit set (SysEx-safe)
      for (byte b : packed) {
        assertTrue((b & 0x80) == 0, "Packed byte has MSB set: " + String.format("%02X", b));
      }

      byte[] unpacked = DelugeMidiPacker.unpack7to8(packed);
      assertArrayEquals(input, unpacked, "Failed for length: " + len);
    }
  }

  @Test
  public void testEmptyAndNull() {
    assertArrayEquals(new byte[0], DelugeMidiPacker.pack8to7(null));
    assertArrayEquals(new byte[0], DelugeMidiPacker.pack8to7(new byte[0]));
    assertArrayEquals(new byte[0], DelugeMidiPacker.unpack7to8(null));
    assertArrayEquals(new byte[0], DelugeMidiPacker.unpack7to8(new byte[0]));
  }
}
