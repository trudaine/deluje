package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import javax.swing.*;
import org.junit.jupiter.api.Test;

public class PreferencesDialogTest {

  @Test
  public void testPreferencesDialogEscapeKeyRegistered() {
    // Run in headless-safe or EDT environment
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }
    JFrame frame = new JFrame("Test Owner");
    frame.setBounds(50, 50, 800, 600);
    PreferencesDialog dialog = new PreferencesDialog(frame, null, () -> {}, () -> {});

    KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
    ActionListener action = dialog.getRootPane().getActionForKeyStroke(esc);
    assertNotNull(action, "Escape key action must be registered on PreferencesDialog root pane");

    dialog.dispose();
    frame.dispose();
  }

  @Test
  public void testFitToScreenAndCenterClampsWithinScreen() {
    if (GraphicsEnvironment.isHeadless()) {
      return;
    }
    JFrame frame = new JFrame("Test Owner");
    frame.setBounds(0, 0, 800, 480); // Small Raspberry Pi screen simulation
    PreferencesDialog dialog = new PreferencesDialog(frame, null, () -> {}, () -> {});

    dialog.fitToScreenAndCenter(frame);

    Rectangle screenBounds =
        GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    assertTrue(
        dialog.getWidth() <= screenBounds.width,
        "Dialog width ("
            + dialog.getWidth()
            + ") must not exceed screen width ("
            + screenBounds.width
            + ")");
    assertTrue(
        dialog.getHeight() <= screenBounds.height,
        "Dialog height ("
            + dialog.getHeight()
            + ") must not exceed screen height ("
            + screenBounds.height
            + ")");
    assertTrue(
        dialog.getY() >= screenBounds.y,
        "Dialog top coordinate ("
            + dialog.getY()
            + ") must not be placed above screen top ("
            + screenBounds.y
            + ")");
    assertTrue(
        dialog.getY() + dialog.getHeight() <= screenBounds.y + screenBounds.height + 1,
        "Dialog bottom must not overflow screen bounds");

    dialog.dispose();
    frame.dispose();
  }
}
