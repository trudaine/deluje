package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.BridgeContract;
import org.junit.jupiter.api.Test;

/**
 * Verifies 100% C++ hardware parity for Latching Stutter Mode (Shift + Stutter / Shift + Q toggle)
 * in TransportController.
 */
public class LatchingStutterModeParityTest {

  @Test
  public void testLatchingStutterHoldsLoopAfterMomentaryRelease() {
    BridgeContract bridge = new BridgeContract();
    TransportController tc = new TransportController(bridge);

    assertFalse(tc.isStutterLatched(), "Initial latched state must be false");

    // 1. Engage Latching Stutter Mode (Shift+Q or Latch toggle)
    tc.setStutterLatched(true);
    assertTrue(tc.isStutterLatched());
    assertEquals(1L, bridge.getGlobalInt(BridgeContract.G_STUTTER_ON));

    // 2. Press momentary stutter (Q down)
    tc.setStutterActive(true);
    assertEquals(1L, bridge.getGlobalInt(BridgeContract.G_STUTTER_ON));

    // 3. Release momentary stutter (Q released) - Latched mode must keep stutter ON!
    tc.setStutterActive(false);
    assertEquals(
        1L,
        bridge.getGlobalInt(BridgeContract.G_STUTTER_ON),
        "Momentary release must not disengage latched stutter");

    // 4. Toggle Latch OFF (Shift+Q again)
    tc.toggleStutterLatched();
    assertFalse(tc.isStutterLatched());
    assertEquals(0L, bridge.getGlobalInt(BridgeContract.G_STUTTER_ON));
  }
}
