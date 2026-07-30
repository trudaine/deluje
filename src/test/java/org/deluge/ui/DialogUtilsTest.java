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

  /**
   * ESC must close the dialog the way the title-bar button does — running {@code windowClosing}
   * listeners, not just {@code windowClosed}.
   *
   * <p>Regression test: the helper originally called {@code dispose()} directly, which fires only
   * {@code windowClosed}. Dialogs that release resources in {@code windowClosing} were silently
   * skipped — SwingRecordingCleanerDialog stops playback there ("Stop playback on window close to
   * avoid leaks!"), so ESC leaked the active clip.
   */
  @Test
  public void testEscapeRunsWindowClosingListeners() {
    if (GraphicsEnvironment.isHeadless()) return;

    JFrame frame = new JFrame("Owner");
    JDialog dialog = new JDialog(frame, "Cleanup Dialog", false);
    final boolean[] cleanedUp = {false};
    dialog.addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosing(java.awt.event.WindowEvent e) {
            cleanedUp[0] = true;
          }
        });

    DialogUtils.installEscapeKeyClose(dialog);
    KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
    ActionListener action = dialog.getRootPane().getActionForKeyStroke(esc);
    assertNotNull(action, "Escape key action must be bound");
    action.actionPerformed(new java.awt.event.ActionEvent(dialog, 0, "ESC"));

    assertTrue(
        cleanedUp[0],
        "ESC must dispatch WINDOW_CLOSING so dialogs that release resources there are not skipped");
    assertFalse(dialog.isDisplayable(), "ESC must still dispose the dialog, not merely hide it");

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
