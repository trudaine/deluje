package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import javax.swing.*;
import org.junit.jupiter.api.Test;

public class DialogUtilsTest {

  @Test
  public void testInstallEscapeKeyClose() {
    if (GraphicsEnvironment.isHeadless()) return;

    JFrame frame = new JFrame("Owner");
    JDialog dialog = new JDialog(frame, "Test Dialog", true);

    DialogUtils.installEscapeKeyClose(dialog);

    KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
    ActionListener action = dialog.getRootPane().getActionForKeyStroke(esc);
    assertNotNull(action, "Escape key action must be bound on dialog root pane");

    dialog.dispose();
    frame.dispose();
  }

  @Test
  public void testFitToScreenAndCenterClamping() {
    if (GraphicsEnvironment.isHeadless()) return;

    JFrame frame = new JFrame("Owner");
    frame.setBounds(0, 0, 800, 600);
    JDialog dialog = new JDialog(frame, "Test Dialog", true);

    // Request large 1280x940 dialog dimensions
    DialogUtils.fitToScreenAndCenter(dialog, frame, 1280, 940, 400, 300);

    Rectangle maxBounds =
        GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();

    assertTrue(
        dialog.getWidth() <= maxBounds.width, "Dialog width must not exceed maximum screen width");
    assertTrue(
        dialog.getHeight() <= maxBounds.height,
        "Dialog height must not exceed maximum screen height");
    assertTrue(
        dialog.getY() >= maxBounds.y,
        "Dialog top Y must not be placed above screen top (" + maxBounds.y + ")");
    assertTrue(
        dialog.getY() + dialog.getHeight() <= maxBounds.y + maxBounds.height + 1,
        "Dialog bottom must not overflow screen bounds");

    dialog.dispose();
    frame.dispose();
  }

  @Test
  public void testModalDialogsHaveEscapeKeyRegistered() {
    if (GraphicsEnvironment.isHeadless()) return;

    JFrame frame = new JFrame("Owner");
    KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);

    BarAutomationDialog d1 = new BarAutomationDialog(frame, 0);
    assertNotNull(
        d1.getRootPane().getActionForKeyStroke(esc), "BarAutomationDialog must have ESC bound");
    d1.dispose();

    StepPropertiesDialog d2 = new StepPropertiesDialog(frame);
    assertNotNull(
        d2.getRootPane().getActionForKeyStroke(esc), "StepPropertiesDialog must have ESC bound");
    d2.dispose();

    PreferencesDialog d3 = new PreferencesDialog(frame, null, () -> {}, () -> {});
    assertNotNull(
        d3.getRootPane().getActionForKeyStroke(esc), "PreferencesDialog must have ESC bound");
    d3.dispose();

    TrackInspectorDialog d4 =
        new TrackInspectorDialog(frame, 0, java.util.Collections.emptyList(), () -> {});
    assertNotNull(
        d4.getRootPane().getActionForKeyStroke(esc), "TrackInspectorDialog must have ESC bound");
    d4.dispose();

    frame.dispose();
  }
}
