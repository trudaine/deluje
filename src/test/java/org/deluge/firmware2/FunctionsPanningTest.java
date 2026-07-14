package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Guards {@link Functions#shouldDoPanning}, a port of C {@code functions.cpp:1487-1498}. */
public class FunctionsPanningTest {

  @Test
  void centerPanReturnsFalseAndFlatAmplitudes() {
    int[] lr = new int[2];
    assertFalse(Functions.shouldDoPanning(0, lr));
    assertEquals(1073741823, lr[0]);
    assertEquals(1073741823, lr[1]);
  }

  @Test
  void fullLeftPanAttenuatesRightOnly() {
    int[] lr = new int[2];
    assertTrue(Functions.shouldDoPanning(-1073741824, lr));
    assertEquals(1073741823, lr[0]); // L unattenuated
    assertEquals(0, lr[1]); // R fully attenuated
  }

  @Test
  void fullRightPanAttenuatesLeftOnly() {
    int[] lr = new int[2];
    assertTrue(Functions.shouldDoPanning(1073741824, lr));
    assertEquals(0, lr[0]); // L fully attenuated
    assertEquals(1073741823, lr[1]); // R unattenuated
  }

  @Test
  void panAmountIsClampedBeyondFullScale() {
    int[] lr = new int[2];
    assertTrue(Functions.shouldDoPanning(Integer.MAX_VALUE, lr));
    assertEquals(0, lr[0]);
    assertEquals(1073741823, lr[1]);
  }
}
