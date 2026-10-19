package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import org.deluge.BridgeContract;
import org.deluge.hid.VirtualOLED;
import org.deluge.midi.DelugeSysExManager;
import org.deluge.midi.MidiInputRouter;
import org.deluge.midi.MidiService;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for wiring DelugeSysExManager's DisplayListener/MidiDebugListener into
 * HardwareSidebarTab. Both listeners existed in the protocol layer with zero UI consumers -- the
 * real device's live OLED frames, 7-segment text, and debug log messages were silently dropped.
 */
public class HardwareSidebarLiveMonitorTest {

  @Test
  public void testLiveOledFrameAndDebugMessageReachUi() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract(44100, 2);
    MidiService midiService = new MidiService(bridge, new MidiInputRouter(bridge));
    SwingDelugeApp app = new SwingDelugeApp(bridge, midiService, true);
    AtomicReference<HardwareSidebarTab> tabRef = new AtomicReference<>();
    AtomicReference<HardwareSidebarTab> secondTabRef = new AtomicReference<>();
    try {
      Method wire = HardwareSidebarTab.class.getDeclaredMethod("wireLiveDeviceListeners");
      wire.setAccessible(true);
      SwingUtilities.invokeAndWait(
          () -> {
            try {
              HardwareSidebarTab t1 = new HardwareSidebarTab(null);
              HardwareSidebarTab t2 = new HardwareSidebarTab(null);
              wire.invoke(t1);
              // Simulate the second sidebar (e.g. floatingSidebar) wiring after tab so we verify
              // it never steals or overwrites tab's DisplayListener / MidiDebugListener.
              wire.invoke(t2);
              tabRef.set(t1);
              secondTabRef.set(t2);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          });
      HardwareSidebarTab tab = tabRef.get();
      HardwareSidebarTab secondTab = secondTabRef.get();

      DelugeSysExManager sysex = app.getMidiService().getSysExManager();

      Field displayListenerField = DelugeSysExManager.class.getDeclaredField("displayListener");
      displayListenerField.setAccessible(true);
      DelugeSysExManager.DisplayListener listener =
          (DelugeSysExManager.DisplayListener) displayListenerField.get(sysex);
      assertNotNull(listener, "A display listener must be registered after wiring");

      byte[] fakeFrame = new byte[768];
      fakeFrame[0] = (byte) 0xFF; // first column, all 8 rows lit
      listener.onOledFrame(fakeFrame);
      listener.onSevenSegment("42");

      Field liveOledField = HardwareSidebarTab.class.getDeclaredField("liveOled");
      liveOledField.setAccessible(true);
      VirtualOLED liveOled = (VirtualOLED) liveOledField.get(tab);
      VirtualOLED secondLiveOled = (VirtualOLED) liveOledField.get(secondTab);
      waitForEdtCondition(
          () -> liveOled.isDirty() && secondLiveOled.isDirty(),
          "Receiving a frame must mark the mirrored OLED dirty on all wired tabs");

      Field sevenSegField = HardwareSidebarTab.class.getDeclaredField("liveSevenSegLabel");
      sevenSegField.setAccessible(true);
      javax.swing.JLabel sevenSeg = (javax.swing.JLabel) sevenSegField.get(tab);
      javax.swing.JLabel secondSevenSeg = (javax.swing.JLabel) sevenSegField.get(secondTab);
      waitForEdtCondition(
          () -> "42".equals(sevenSeg.getText()) && "42".equals(secondSevenSeg.getText()),
          "7-segment text must reach all wired tabs");

      Field debugListenerField = DelugeSysExManager.class.getDeclaredField("debugListener");
      debugListenerField.setAccessible(true);
      DelugeSysExManager.MidiDebugListener debugListener =
          (DelugeSysExManager.MidiDebugListener) debugListenerField.get(sysex);
      assertNotNull(debugListener, "A debug listener must be registered after wiring");
      debugListener.onDebugMessage("hello from hardware");

      Field debugAreaField = HardwareSidebarTab.class.getDeclaredField("liveDebugLogArea");
      debugAreaField.setAccessible(true);
      JTextArea debugArea = (JTextArea) debugAreaField.get(tab);
      JTextArea secondDebugArea = (JTextArea) debugAreaField.get(secondTab);
      waitForEdtCondition(
          () ->
              debugArea.getText().contains("hello from hardware")
                  && secondDebugArea.getText().contains("hello from hardware"),
          "Debug message must reach the UI text area on all wired tabs");

      // Detaching and re-attaching (e.g. closing and reopening floating sidebar) must reconnect.
      SwingUtilities.invokeAndWait(
          () -> {
            tab.removeNotify();
            tab.addNotify();
          });
      DelugeSysExManager.MidiDebugListener reattachedListener =
          (DelugeSysExManager.MidiDebugListener) debugListenerField.get(sysex);
      assertNotNull(reattachedListener, "Debug listener must remain wired after re-attachment");
      reattachedListener.onDebugMessage("reattached ok");
      waitForEdtCondition(
          () -> debugArea.getText().contains("reattached ok"),
          "Re-attached tab must continue receiving debug messages");
    } finally {
      SwingUtilities.invokeAndWait(
          () -> {
            if (secondTabRef.get() != null) {
              secondTabRef.get().dispose();
            }
            if (tabRef.get() != null) {
              tabRef.get().dispose();
            }
            app.dispose();
            SwingDelugeApp.mainInstance = null;
          });
      bridge.shutdown();
    }
  }

  private static void waitForEdtCondition(BooleanSupplier condition, String message)
      throws Exception {
    long deadlineNanos = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
    AtomicBoolean matched = new AtomicBoolean(false);
    while (System.nanoTime() < deadlineNanos) {
      SwingUtilities.invokeAndWait(() -> matched.set(condition.getAsBoolean()));
      if (matched.get()) {
        return;
      }
      Thread.sleep(10);
    }
    SwingUtilities.invokeAndWait(() -> matched.set(condition.getAsBoolean()));
    assertTrue(matched.get(), message);
  }
}
