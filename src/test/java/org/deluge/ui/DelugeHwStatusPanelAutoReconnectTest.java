package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.deluge.BridgeContract;
import org.deluge.midi.MidiInputRouter;
import org.deluge.midi.MidiService;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for "Deluge on" never lighting up after powering the hardware on while the
 * app is already running (previously required a full Swing UI restart). triggerPingTest() ran every
 * 4s but only ever pinged the *existing* connection -- DelugeSysExManager.sendRequest silently
 * no-ops with no MidiOut wired up, so if the port wasn't enumerable at startup (or a prior ping
 * timeout dropped it), nothing ever retried opening it again. Only the manual click-to-reconnect
 * path called MidiService.reconnect(). Fixed by having the periodic heartbeat also call reconnect()
 * whenever not currently connected.
 */
public class DelugeHwStatusPanelAutoReconnectTest {

  @Test
  public void triggerPingTestReconnectsWhenNotConnected() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();
    AtomicInteger reconnectCalls = new AtomicInteger(0);
    MidiService midiService =
        new MidiService(bridge, new MidiInputRouter(bridge)) {
          @Override
          public synchronized void reconnect() {
            reconnectCalls.incrementAndGet();
            // Deliberately not calling super.reconnect() -- this is a unit test, not a hardware
            // test, and must not touch real MIDI ports.
          }

          @Override
          public boolean isOutputConnected() {
            return false; // simulate: Deluge was off/unplugged when the app started
          }
        };

    // The panel's constructor already calls triggerPingTest() once immediately.
    DelugeHwStatusPanel panel = new DelugeHwStatusPanel(midiService);
    assertNotNull(panel);

    assertTrue(
        reconnectCalls.get() >= 1,
        "triggerPingTest() must call reconnect() when not currently connected, so turning the"
            + " Deluge on later gets picked up without an app restart");
  }
}
