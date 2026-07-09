package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.swing.JTextArea;
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
    try {
      HardwareSidebarTab tab = new HardwareSidebarTab(null);

      Method wire = HardwareSidebarTab.class.getDeclaredMethod("wireLiveDeviceListeners");
      wire.setAccessible(true);
      wire.invoke(tab);

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

      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      Field liveOledField = HardwareSidebarTab.class.getDeclaredField("liveOled");
      liveOledField.setAccessible(true);
      VirtualOLED liveOled = (VirtualOLED) liveOledField.get(tab);
      assertTrue(liveOled.isDirty(), "Receiving a frame must mark the mirrored OLED dirty");

      Field sevenSegField = HardwareSidebarTab.class.getDeclaredField("liveSevenSegLabel");
      sevenSegField.setAccessible(true);
      javax.swing.JLabel sevenSeg = (javax.swing.JLabel) sevenSegField.get(tab);
      assertEquals("42", sevenSeg.getText());

      Field debugListenerField = DelugeSysExManager.class.getDeclaredField("debugListener");
      debugListenerField.setAccessible(true);
      DelugeSysExManager.MidiDebugListener debugListener =
          (DelugeSysExManager.MidiDebugListener) debugListenerField.get(sysex);
      assertNotNull(debugListener, "A debug listener must be registered after wiring");
      debugListener.onDebugMessage("hello from hardware");
      javax.swing.SwingUtilities.invokeAndWait(() -> {});

      Field debugAreaField = HardwareSidebarTab.class.getDeclaredField("liveDebugLogArea");
      debugAreaField.setAccessible(true);
      JTextArea debugArea = (JTextArea) debugAreaField.get(tab);
      assertTrue(
          debugArea.getText().contains("hello from hardware"),
          "Debug message must reach the UI text area");
    } finally {
      app.dispose();
      bridge.shutdown();
    }
  }
}
