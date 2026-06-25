package org.deluge.hid;

import org.deluge.BridgeContract;

/**
 * Global holder for the active {@link BridgeContract} instance, allowing static factory and DSP
 * methods to query sequencer transport and live recording states.
 */
public class BridgeHolder {
  private static volatile BridgeContract activeBridge = null;

  public static void setBridge(BridgeContract bridge) {
    activeBridge = bridge;
  }

  public static BridgeContract getBridge() {
    return activeBridge;
  }
}
