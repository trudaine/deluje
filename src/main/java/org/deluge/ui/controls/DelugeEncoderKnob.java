package org.deluge.ui.controls;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.function.IntConsumer;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * A self-drawn rotary encoder emulating a Deluge data/gold encoder. The desktop maps the hardware's
 * turn / press / press-while-turning idiom to mouse gestures:
 *
 * <ul>
 *   <li><b>turn</b> — vertical drag (or mouse wheel): emits {@code onTurn(delta)} per detent
 *   <li><b>press</b> — click without dragging: emits {@code onPress()}
 *   <li><b>press+turn</b> — Alt-drag or right-button drag: emits {@code onPressTurn(delta)}
 * </ul>
 *
 * <p>Turns are passed through a {@link StickyTurnFilter} so a quick back-wiggle is ignored,
 * matching the firmware mod-encoder behaviour (encoders.cpp:244-292).
 */
public class DelugeEncoderKnob extends JComponent {

  private static final int PIXELS_PER_DETENT = 10;
  private static final double STEP_ANGLE =
      Math.toRadians(18); // cosmetic pointer rotation per detent

  private final Color accent;
  private final String label;
  private final StickyTurnFilter sticky = new StickyTurnFilter();

  private IntConsumer onTurn = d -> {};
  private Runnable onPress = () -> {};
  private IntConsumer onPressTurn = d -> {};

  private double pointerAngle = -Math.PI / 2; // start pointing up
  private int startY;
  private int accumPixels;
  private boolean dragged;

  public DelugeEncoderKnob(String label, Color accent) {
    this.label = label == null ? "" : label;
    this.accent = accent != null ? accent : new Color(0x9a, 0x9a, 0xa4);
    setOpaque(false);
    setFocusable(false);
    setPreferredSize(new Dimension(48, label.isEmpty() ? 48 : 62));

    MouseAdapter ma =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            startY = e.getY();
            accumPixels = 0;
            dragged = false;
            sticky.reset();
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            dragged = true;
            accumPixels += (startY - e.getY()); // dragging up = positive
            startY = e.getY();
            boolean pressTurn = e.isAltDown() || SwingUtilities.isRightMouseButton(e);
            while (Math.abs(accumPixels) >= PIXELS_PER_DETENT) {
              int dir = accumPixels > 0 ? 1 : -1;
              accumPixels -= dir * PIXELS_PER_DETENT;
              emitTurn(dir, pressTurn);
            }
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            if (!dragged) {
              onPress.run();
            }
            sticky.reset();
          }
        };
    addMouseListener(ma);
    addMouseMotionListener(ma);
    addMouseWheelListener(this::onWheel);
  }

  private void onWheel(MouseWheelEvent e) {
    int dir = -Integer.signum(e.getWheelRotation()); // wheel up = +1
    if (dir != 0) {
      emitTurn(dir, e.isAltDown());
    }
  }

  private void emitTurn(int rawDir, boolean pressTurn) {
    int delta = sticky.filter(rawDir, System.currentTimeMillis());
    if (delta == 0) {
      return;
    }
    pointerAngle += delta * STEP_ANGLE;
    repaint();
    if (pressTurn) {
      onPressTurn.accept(delta);
    } else {
      onTurn.accept(delta);
    }
  }

  public DelugeEncoderKnob onTurn(IntConsumer c) {
    this.onTurn = c != null ? c : d -> {};
    return this;
  }

  public DelugeEncoderKnob onPress(Runnable r) {
    this.onPress = r != null ? r : () -> {};
    return this;
  }

  public DelugeEncoderKnob onPressTurn(IntConsumer c) {
    this.onPressTurn = c != null ? c : d -> {};
    return this;
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int labelH = label.isEmpty() ? 0 : 14;
    int d = Math.min(w, getHeight() - labelH) - 6;
    int cx = w / 2;
    int cy = 3 + d / 2;

    // Knob body: dark radial with a faint accent rim.
    RadialGradientPaint body =
        new RadialGradientPaint(
            cx - d * 0.15f,
            cy - d * 0.15f,
            d * 0.75f,
            new float[] {0f, 1f},
            new Color[] {new Color(0x3a, 0x3a, 0x42), new Color(0x14, 0x14, 0x18)});
    g2.setPaint(body);
    g2.fillOval(cx - d / 2, cy - d / 2, d, d);

    g2.setStroke(new BasicStroke(2f));
    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 150));
    g2.drawOval(cx - d / 2, cy - d / 2, d, d);

    // Pointer indicator.
    int r = d / 2 - 4;
    int px = cx + (int) (Math.cos(pointerAngle) * r);
    int py = cy + (int) (Math.sin(pointerAngle) * r);
    g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g2.setColor(accent.brighter());
    g2.drawLine(cx, cy, px, py);

    // Center hub.
    g2.setColor(new Color(0xd0, 0xd0, 0xda));
    int hub = Math.max(4, d / 8);
    g2.fillOval(cx - hub / 2, cy - hub / 2, hub, hub);

    if (!label.isEmpty()) {
      g2.setFont(new Font("SansSerif", Font.BOLD, 9));
      FontMetrics fm = g2.getFontMetrics();
      int tw = fm.stringWidth(label);
      g2.setColor(new Color(0xcc, 0xcc, 0xd4));
      g2.drawString(label, cx - tw / 2, getHeight() - 3);
    }

    g2.dispose();
  }
}
