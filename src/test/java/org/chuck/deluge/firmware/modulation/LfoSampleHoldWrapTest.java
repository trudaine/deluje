package org.chuck.deluge.firmware.modulation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Regression test for the Sample-and-Hold / Random-Walk retrigger, which is driven by an unsigned
 * uint32 phase wrap in the firmware. The Java port had used {@code (long) phase}, which
 * sign-extends a negative (upper-half) int, breaking the wrap detection for the upper half of every
 * phase cycle.
 */
public class LfoSampleHoldWrapTest {

  private static final int SENTINEL = 0x7ABCDEF1;

  /**
   * With phase in the upper half (MSB set) and an increment that wraps uint32, S&H must retrigger.
   */
  @Test
  public void sampleAndHoldRetriggersOnUpperHalfWrap() {
    LFO lfo = new LFO();
    lfo.phase = 0xFF000000; // upper half (negative as a signed int)
    lfo.holdValue = SENTINEL;
    // unsigned: 0xFF000000 + 0x02000000 = 0x101000000 > 0xFFFFFFFF -> wraps -> retrigger
    int value = lfo.render(1, LFO.LFOType.SAMPLE_AND_HOLD, 0x02000000);
    assertNotEquals(
        SENTINEL, value, "S&H must sample a fresh value when phase wraps in the upper half");
    assertNotEquals(SENTINEL, lfo.holdValue);
  }

  /** With phase in the upper half but no wrap, S&H must hold (no retrigger). */
  @Test
  public void sampleAndHoldHoldsWhenNoWrap() {
    LFO lfo = new LFO();
    lfo.phase = 0x90000000; // upper half
    lfo.holdValue = SENTINEL;
    // unsigned: 0x90000000 + 0x01000000 = 0x91000000 < 0xFFFFFFFF -> no wrap -> hold
    int value = lfo.render(1, LFO.LFOType.SAMPLE_AND_HOLD, 0x01000000);
    assertEquals(SENTINEL, value, "S&H must hold its value when phase does not wrap");
  }
}
