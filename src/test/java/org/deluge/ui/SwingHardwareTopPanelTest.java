package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.image.BufferedImage;
import javax.swing.JButton;
import org.deluge.project.PreferencesManager;
import org.junit.jupiter.api.Test;

public class SwingHardwareTopPanelTest {

  /** No-op {@link SwingHardwareTopPanel.TopBarListener} that counts scale-mode toggles. */
  private static class ScaleModeCountingListener implements SwingHardwareTopPanel.TopBarListener {
    int scaleModeToggles = 0;

    @Override
    public void onLiveRecordToggle(JButton btn) {}

    @Override
    public void onResampleToggle(JButton btn) {}

    @Override
    public void onArrangerCaptureToggle(boolean active) {}

    @Override
    public void onViewModeChanged(String viewMode) {}

    @Override
    public void onAddTrack(String type, boolean isShift) {}

    @Override
    public void onPlayToggle() {}

    @Override
    public void onStop() {}

    @Override
    public void onMasterVolumeChanged(float vol) {}

    @Override
    public void onScaleModeToggle() {
      scaleModeToggles++;
    }
  }

  @Test
  public void testTopPanelStylePreferenceAndImageRendering() {
    PreferencesManager.TopPanelStyle oldStyle = PreferencesManager.getTopPanelStyle();
    try {
      PreferencesManager.setTopPanelStyle(PreferencesManager.TopPanelStyle.HARDWARE_FACEPLATE);
      assertEquals(
          PreferencesManager.TopPanelStyle.HARDWARE_FACEPLATE,
          PreferencesManager.getTopPanelStyle());

      SwingHardwareTopPanel panel = new SwingHardwareTopPanel(null, null, null, null);
      panel.setSize(new Dimension(1400, 190));
      assertNotNull(panel, "SwingHardwareTopPanel must initialize cleanly");

      BufferedImage img = new BufferedImage(1400, 190, BufferedImage.TYPE_INT_ARGB);
      Graphics2D g = img.createGraphics();
      panel.paint(g);
      g.dispose();
    } finally {
      PreferencesManager.setTopPanelStyle(oldStyle);
    }
  }

  // C: instrument_clip_view.cpp:855-916 (handleScaleButtonAction) — SCALE_MODE toggles on
  // release after a short-press check, not immediately on press.
  private static final int SCALE_MODE_CX = 1205;
  private static final int SCALE_MODE_CY = 515;

  private static MouseEvent scaleModeMouseEvent(SwingHardwareTopPanel panel, int id) {
    return new MouseEvent(
        panel,
        id,
        System.currentTimeMillis(),
        0,
        SCALE_MODE_CX,
        SCALE_MODE_CY,
        1,
        false,
        MouseEvent.BUTTON1);
  }

  @Test
  public void testScaleModeDoesNotToggleOnPress() {
    ScaleModeCountingListener listener = new ScaleModeCountingListener();
    SwingHardwareTopPanel panel = new SwingHardwareTopPanel(null, null, null, listener);

    MouseEvent press = scaleModeMouseEvent(panel, MouseEvent.MOUSE_PRESSED);
    for (MouseListener ml : panel.getMouseListeners()) {
      ml.mousePressed(press);
    }

    assertEquals(0, listener.scaleModeToggles, "pressing SCALE_MODE alone must not toggle it");
  }

  @Test
  public void testScaleModeTogglesOnShortPressRelease() {
    ScaleModeCountingListener listener = new ScaleModeCountingListener();
    SwingHardwareTopPanel panel = new SwingHardwareTopPanel(null, null, null, listener);

    for (MouseListener ml : panel.getMouseListeners()) {
      ml.mousePressed(scaleModeMouseEvent(panel, MouseEvent.MOUSE_PRESSED));
    }
    for (MouseListener ml : panel.getMouseListeners()) {
      ml.mouseReleased(scaleModeMouseEvent(panel, MouseEvent.MOUSE_RELEASED));
    }

    assertEquals(
        1, listener.scaleModeToggles, "a short press-release on SCALE_MODE must toggle it once");
  }

  @Test
  public void testScaleModeDoesNotToggleOnLongPressRelease() throws Exception {
    ScaleModeCountingListener listener = new ScaleModeCountingListener();
    SwingHardwareTopPanel panel = new SwingHardwareTopPanel(null, null, null, listener);

    for (MouseListener ml : panel.getMouseListeners()) {
      ml.mousePressed(scaleModeMouseEvent(panel, MouseEvent.MOUSE_PRESSED));
    }
    Thread.sleep(150); // exceeds the 100ms short-press window
    for (MouseListener ml : panel.getMouseListeners()) {
      ml.mouseReleased(scaleModeMouseEvent(panel, MouseEvent.MOUSE_RELEASED));
    }

    assertEquals(
        0, listener.scaleModeToggles, "a held-down long press on SCALE_MODE must not toggle it");
  }
}
