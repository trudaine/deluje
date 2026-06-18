package org.deluge.ui.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Verifies the Deluge mod-encoder sticky back-wiggle model (encoders.cpp:244-292). */
public class StickyTurnFilterTest {

  @Test
  public void firstTurnInEitherDirectionPassesThrough() {
    StickyTurnFilter f = new StickyTurnFilter(500);
    assertEquals(3, f.filter(3, 1000), "first turn establishes direction and passes");
    assertEquals(1, f.establishedDirection());
  }

  @Test
  public void sameDirectionAlwaysPassesAndRefreshesGuard() {
    StickyTurnFilter f = new StickyTurnFilter(500);
    assertEquals(1, f.filter(1, 0));
    assertEquals(1, f.filter(1, 100));
    assertEquals(1, f.filter(1, 200));
  }

  @Test
  public void reversalWithinTimeoutIsSuppressed() {
    StickyTurnFilter f = new StickyTurnFilter(500);
    assertEquals(1, f.filter(1, 1000)); // establish +
    assertEquals(0, f.filter(-1, 1100), "back-wiggle within 500ms is ignored");
    assertEquals(0, f.filter(-2, 1400), "still within guard window");
    assertEquals(1, f.establishedDirection(), "direction stays +");
  }

  @Test
  public void reversalAfterTimeoutIsAccepted() {
    StickyTurnFilter f = new StickyTurnFilter(500);
    assertEquals(1, f.filter(1, 1000)); // establish +
    assertEquals(-1, f.filter(-1, 1600), "after >500ms a reversal sticks");
    assertEquals(-1, f.establishedDirection());
  }

  @Test
  public void guardCountsFromLastAcceptedNotFromSuppressed() {
    StickyTurnFilter f = new StickyTurnFilter(500);
    assertEquals(1, f.filter(1, 1000)); // establish + at t=1000
    assertEquals(0, f.filter(-1, 1300)); // suppressed, does NOT refresh the guard
    // 1600 is >500ms after the last *accepted* turn (1000), so the reversal is allowed.
    assertEquals(-1, f.filter(-1, 1600));
  }

  @Test
  public void resetForgetsDirection() {
    StickyTurnFilter f = new StickyTurnFilter(500);
    f.filter(1, 1000);
    f.reset();
    assertEquals(0, f.establishedDirection());
    assertEquals(-5, f.filter(-5, 1100), "after reset the next turn establishes fresh");
  }

  @Test
  public void zeroDeltaIsNoOp() {
    StickyTurnFilter f = new StickyTurnFilter(500);
    assertEquals(0, f.filter(0, 1000));
    assertEquals(0, f.establishedDirection());
  }
}
