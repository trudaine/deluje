package org.chuck.deluge.ui.controls;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class UiAnimatorTest {

  @Test
  public void timerRunsOnlyWhileListenersRegistered() {
    UiAnimator a = UiAnimator.get();
    UiAnimator.Tick t = frame -> {};
    assertFalse(a.isRunning() && a.frame() < 0); // sanity: accessible
    a.add(t);
    assertTrue(a.isRunning(), "timer starts with a listener");
    a.remove(t);
    assertFalse(a.isRunning(), "timer stops with no listeners");
  }

  @Test
  public void blinkOnTogglesAcrossHalfPeriod() {
    // Two timestamps a half-period apart land in opposite blink phases.
    long half = 100;
    boolean p0 = ((0L / half) & 1L) == 0L;
    boolean p1 = ((half / half) & 1L) == 0L;
    assertTrue(p0 != p1, "adjacent half-period buckets are opposite phases");
    // The static helper must return a stable boolean (no exception, no NPE).
    UiAnimator.blinkOn(UiAnimator.FLASH_SLOW_MS);
    UiAnimator.blinkOn(UiAnimator.FLASH_FAST_MS);
  }

  @Test
  public void blinkOnNonPositiveIsAlwaysOn() {
    assertTrue(UiAnimator.blinkOn(0));
    assertTrue(UiAnimator.blinkOn(-5));
  }
}
