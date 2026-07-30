package org.deluge.ui;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import javax.swing.*;

/**
 * Shared utility for Swing dialog sizing, responsive screen bounds clamping, and keyboard
 * dismissal. Prevents modal dialogs from overflowing small, laptop, or HiDPI-scaled Raspberry Pi
 * screens, and guarantees that window title bars and bottom action buttons (Save/Cancel/Close) are
 * never pushed off-screen.
 */
public final class DialogUtils {

  private DialogUtils() {}

  /**
   * Responsively sizes and centers a dialog within the usable screen work area, accounting for
   * taskbars and display scaling.
   *
   * <p><b>Size:</b> clamped to the work area <em>unless</em> {@code minW}/{@code minH} exceed it,
   * in which case the minimum wins and the dialog is deliberately allowed to overflow — a dialog
   * below its minimum is unusable, so overflowing is the lesser failure. On a screen wide enough
   * for {@code minW} the clamp is absolute. Callers targeting very small displays should therefore
   * keep {@code minW}/{@code minH} genuinely minimal, not merely "small".
   *
   * <p><b>Position:</b> always clamped to the work area, with no exception, so the title bar
   * ({@code y >= work.y}) and the bottom action buttons stay on-screen even when the size
   * overflowed. When the owner is showing, the dialog is centred on it first and then clamped;
   * otherwise it is centred on the work area. The owner's {@code GraphicsConfiguration} selects the
   * display, so a dialog opened from a window on a secondary or differently-scaled monitor is
   * measured against <em>that</em> monitor, not the default one.
   *
   * <p>Prefer this over {@code setSize} + {@code setLocationRelativeTo}: the latter centres on the
   * owner without clamping, which is what pushes dialogs off-screen on small displays.
   *
   * @param dialog the dialog to size and position; no-op if null
   * @param owner the owner window/frame, used for display selection and centring (may be null)
   * @param targetW preferred width in logical pixels
   * @param targetH preferred height in logical pixels
   * @param minW minimum usable width; may exceed the screen, see above
   * @param minH minimum usable height; may exceed the screen, see above
   */
  public static void fitToScreenAndCenter(
      JDialog dialog, Window owner, int targetW, int targetH, int minW, int minH) {
    if (dialog == null) return;

    Rectangle maxBounds = null;
    if (owner != null && owner.getGraphicsConfiguration() != null) {
      GraphicsConfiguration gc = owner.getGraphicsConfiguration();
      Rectangle gcBounds = gc.getBounds();
      Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
      maxBounds =
          new Rectangle(
              gcBounds.x + insets.left,
              gcBounds.y + insets.top,
              gcBounds.width - insets.left - insets.right,
              gcBounds.height - insets.top - insets.bottom);
    }
    if (maxBounds == null || maxBounds.width <= 0 || maxBounds.height <= 0) {
      try {
        maxBounds = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
      } catch (Exception e) {
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        maxBounds = new Rectangle(0, 0, screen.width, screen.height);
      }
    }

    // Leave a small margin inside the work area so the dialog never sits flush against the screen
    // edge, where window-manager decorations and drop shadows can make the frame look clipped. The
    // height margin is larger because the title bar is part of the frame on most window managers.
    final int marginX = 24;
    final int marginY = 32;
    int clampedW = Math.min(targetW, Math.max(minW, maxBounds.width - marginX));
    int clampedH = Math.min(targetH, Math.max(minH, maxBounds.height - marginY));
    dialog.setSize(clampedW, clampedH);
    dialog.setMinimumSize(new Dimension(Math.min(minW, clampedW), Math.min(minH, clampedH)));

    int posX;
    int posY;
    if (owner != null && owner.isShowing()) {
      posX = owner.getX() + (owner.getWidth() - clampedW) / 2;
      posY = owner.getY() + (owner.getHeight() - clampedH) / 2;
    } else {
      posX = maxBounds.x + (maxBounds.width - clampedW) / 2;
      posY = maxBounds.y + (maxBounds.height - clampedH) / 2;
    }

    // Strictly clamp within usable screen bounds so title bar and bottom buttons are never clipped
    posX = Math.max(maxBounds.x, Math.min(posX, maxBounds.x + maxBounds.width - clampedW));
    posY = Math.max(maxBounds.y, Math.min(posY, maxBounds.y + maxBounds.height - clampedH));
    dialog.setLocation(posX, posY);
  }

  /**
   * Installs an ESC key action on the dialog's root pane that closes the dialog the same way its
   * title-bar close button does: {@code windowClosing} listeners run first, then it is disposed.
   *
   * <p>Dispatching {@code WINDOW_CLOSING} is not decoration. A bare {@code dispose()} fires only
   * {@code windowClosed}, so any dialog that cleans up in a {@code windowClosing} handler is
   * skipped — {@code SwingRecordingCleanerDialog} stops playback there ("Stop playback on window
   * close to avoid leaks!"), and ESC leaked the active clip until this dispatched the event.
   * Dialogs that instead override {@code dispose()} (e.g. {@code ThresholdRecordDialog}, which
   * stops its meter timer and capture line) were always fine and stay fine.
   *
   * <p>The explicit {@code dispose()} afterwards is deliberate: no dialog here sets a close
   * operation, so they all default to {@code HIDE_ON_CLOSE} and the dispatched event alone would
   * merely hide them. Note this means a {@code windowClosing} handler cannot veto an ESC close — no
   * current dialog tries to, but a future one wanting a confirm-on-discard prompt must handle ESC
   * itself rather than rely on this helper.
   *
   * @param dialog the dialog to attach the ESC shortcut to
   */
  public static void installEscapeKeyClose(JDialog dialog) {
    if (dialog == null || dialog.getRootPane() == null) return;
    dialog
        .getRootPane()
        .registerKeyboardAction(
            e -> {
              dialog.dispatchEvent(new WindowEvent(dialog, WindowEvent.WINDOW_CLOSING));
              dialog.dispose();
            },
            KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
  }
}
