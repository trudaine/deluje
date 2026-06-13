package org.chuck.deluge.ui.controls;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JComponent;
import javax.swing.Timer;
import org.chuck.deluge.firmware.hid.FirmwareDisplay;

/**
 * Modern, self-drawn transient parameter readout that replaces the legacy 4-character
 * {@code RetroLedDisplay}. Shows a dim param name and a bright value on a dark rounded chip; new
 * values glow in the accent colour and fade back to idle after a short hold (the firmware's
 * transient-popup behaviour, oled.cpp:723). All updates are mirrored to the faithful OLED, which
 * remains the primary text surface.
 *
 * <p>Drop-in API-compatible with the old display: {@code print}, {@code printTransient},
 * {@code scrollMessage}, {@code reset}.
 */
public class DelugeParamReadout extends JComponent {

  private static final Color BG_IDLE = new Color(0x14, 0x14, 0x18);
  private static final Color BG_ACTIVE = new Color(0x22, 0x18, 0x05);
  private static final Color ACCENT = new Color(0xff, 0xb3, 0x00);
  private static final Color DIM = new Color(0x66, 0x66, 0x70);

  private static final int TRANSIENT_MS = 1500;
  private static final int SCROLL_MS = 250;

  private String code = "";
  private String value = "—";
  private boolean active = false;

  private Timer resetTimer;
  private Timer scrollTimer;

  public DelugeParamReadout() {
    setOpaque(false);
    Dimension d = new Dimension(184, 46);
    setPreferredSize(d);
    setMinimumSize(d);
    setMaximumSize(d);
  }

  /** Persistent value (no auto-reset). */
  public void print(String code, String val) {
    stopTimers();
    this.code = code == null ? "" : code.toUpperCase().trim();
    this.value = val == null ? "" : val.trim();
    this.active = true;
    forwardToOled(this.code, this.value, "");
    repaint();
  }

  /** Value that fades back to idle after a short hold. */
  public void printTransient(String code, String val) {
    print(code, val);
    resetTimer = new Timer(TRANSIENT_MS, e -> reset());
    resetTimer.setRepeats(false);
    resetTimer.start();
  }

  /** Marquee a longer message (e.g. a preset/track name), then return to idle. */
  public void scrollMessage(String message) {
    stopTimers();
    final String msg = message == null ? "" : message;
    this.code = "";
    this.active = true;
    this.value = msg;
    forwardToOled("TRACK", msg, "");
    repaint();

    final String padded = "    " + msg + "    ";
    final int window = 18;
    if (padded.length() <= window) {
      resetTimer = new Timer(TRANSIENT_MS, e -> reset());
      resetTimer.setRepeats(false);
      resetTimer.start();
      return;
    }
    final int[] idx = {0};
    scrollTimer =
        new Timer(
            SCROLL_MS,
            e -> {
              if (idx[0] + window <= padded.length()) {
                value = padded.substring(idx[0], idx[0] + window);
                idx[0]++;
                repaint();
              } else {
                reset();
              }
            });
    scrollTimer.start();
  }

  public void reset() {
    stopTimers();
    this.code = "";
    this.value = "—";
    this.active = false;
    repaint();
  }

  private void stopTimers() {
    if (resetTimer != null) {
      resetTimer.stop();
    }
    if (scrollTimer != null) {
      scrollTimer.stop();
    }
  }

  private static void forwardToOled(String l1, String l2, String l3) {
    try {
      FirmwareDisplay.get().getVirtualOLED().drawThreeLineDisplay(l1, l2, l3);
    } catch (Throwable ignored) {
      // OLED not initialized in some contexts (e.g. tests) — readout still renders.
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();
    int arc = 10;

    g2.setColor(active ? BG_ACTIVE : BG_IDLE);
    g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
    g2.setStroke(new BasicStroke(1.5f));
    g2.setColor(active ? ACCENT : new Color(0x2d, 0x2d, 0x34));
    g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);

    // Param name (small, dim, top-left).
    if (!code.isEmpty()) {
      g2.setFont(new Font("SansSerif", Font.BOLD, 9));
      g2.setColor(DIM);
      g2.drawString(code, 8, 14);
    }

    // Value (large, bright accent, bottom-aligned).
    g2.setFont(new Font("Monospaced", Font.BOLD, 20));
    FontMetrics fm = g2.getFontMetrics();
    String v = value == null ? "" : value;
    int vw = fm.stringWidth(v);
    int vx = Math.max(8, w - 8 - vw);
    int vy = h - 9;
    g2.setColor(active ? ACCENT : DIM);
    g2.drawString(v, vx, vy);

    g2.dispose();
  }
}
