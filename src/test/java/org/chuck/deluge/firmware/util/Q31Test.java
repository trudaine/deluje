package org.chuck.deluge.firmware.util;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class Q31Test {

  @Test
  public void testMult() {
    // 0.5 * 0.5 = 0.25
    int half = Q31.fromFloat(0.5f);
    int quarter = Q31.mult(half, half);
    assertEquals(0.25f, Q31.toFloat(quarter), 0.0001f);
  }

  @Test
  public void testMultiply32x32_rshift32() {
    // Integer.MAX_VALUE * 1 >> 32 = 0
    assertEquals(0, Q31.multiply_32x32_rshift32(Integer.MAX_VALUE, 1));

    // (1 << 31) * (1 << 1) = 1 << 32. rshift32 should give 1.
    // Wait, Integer.MIN_VALUE is -2^31.
    // -2^31 * 2 = -2^32. rshift32 should give -1.
    assertEquals(-1, Q31.multiply_32x32_rshift32(Integer.MIN_VALUE, 2));
  }

  @Test
  public void testAddSaturate() {
    assertEquals(Q31.ONE, Q31.addSaturate(Q31.ONE, 100));
    assertEquals(Q31.NEGATIVE_ONE, Q31.addSaturate(Q31.NEGATIVE_ONE, -100));
    assertEquals(0, Q31.addSaturate(100, -100));
  }

  @Test
  public void testFromFloat() {
    assertEquals(Q31.ONE, Q31.fromFloat(1.0f));
    assertEquals(Q31.NEGATIVE_ONE, Q31.fromFloat(-1.0f));
    assertEquals(0, Q31.fromFloat(0.0f));
  }
}
