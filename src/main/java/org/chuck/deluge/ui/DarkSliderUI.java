package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicSliderUI;

/**
 * A luxury, modern dark-neon JSlider UI delegate. Paints a custom sleek 4px rounded track rail with
 * split active/inactive segments and a glossy circular thumb knob with neon outer glow rings.
 */
public class DarkSliderUI extends BasicSliderUI {

  private final Color accentColor;

  public static javax.swing.plaf.ComponentUI createUI(JComponent c) {
    return new DarkSliderUI((JSlider) c, new Color(0xff, 0xb3, 0x00)); // Default System Gold Amber!
  }

  public DarkSliderUI(JSlider b, Color accentColor) {
    super(b);
    this.accentColor = accentColor;
  }

  public static void styleSlider(JSlider slider, Color accentColor) {
    slider.setUI(new DarkSliderUI(slider, accentColor));
    slider.setFocusable(false);
    slider.setBackground(
        slider.getParent() != null
            ? slider.getParent().getBackground()
            : new Color(0x1a, 0x1a, 0x1e));
  }

  @Override
  public void paintTrack(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int cy;
    int h = 4; // Sleek thin track line footprint
    Color trackColor = new Color(0x45, 0x45, 0x4e); // Subtle high-contrast silver-charcoal

    if (slider.getOrientation() == JSlider.HORIZONTAL) {
      cy = trackRect.y + (trackRect.height / 2) - (h / 2);
      int thumbX = thumbRect.x + (thumbRect.width / 2);

      // Draw active segment (left of thumb)
      g2.setColor(accentColor);
      g2.fillRoundRect(trackRect.x, cy, Math.max(0, thumbX - trackRect.x), h, h, h);

      // Draw inactive segment (right of thumb)
      g2.setColor(trackColor);
      g2.fillRoundRect(thumbX, cy, Math.max(0, (trackRect.x + trackRect.width) - thumbX), h, h, h);
    } else {
      // Vertical track splitting
      int cx = trackRect.x + (trackRect.width / 2) - (h / 2);
      int thumbY = thumbRect.y + (thumbRect.height / 2);

      // Draw active segment (bottom of thumb to end)
      g2.setColor(accentColor);
      g2.fillRoundRect(cx, thumbY, h, Math.max(0, (trackRect.y + trackRect.height) - thumbY), h, h);

      // Draw inactive segment (top to bottom of thumb)
      g2.setColor(trackColor);
      g2.fillRoundRect(cx, trackRect.y, h, Math.max(0, thumbY - trackRect.y), h, h);
    }
    g2.dispose();
  }

  @Override
  public void paintThumb(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    // Draw circular outer glow shadow ring
    Color glowColor =
        new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 45);
    g2.setColor(glowColor);
    g2.fillOval(thumbRect.x - 2, thumbRect.y - 2, thumbRect.width + 4, thumbRect.height + 4);

    // Draw main circular white thumb face
    g2.setColor(Color.WHITE);
    g2.fillOval(thumbRect.x, thumbRect.y, thumbRect.width, thumbRect.height);

    // Draw active circular accent core
    g2.setColor(accentColor);
    int cw = 6;
    g2.fillOval(
        thumbRect.x + (thumbRect.width / 2) - (cw / 2),
        thumbRect.y + (thumbRect.height / 2) - (cw / 2),
        cw,
        cw);

    g2.dispose();
  }

  @Override
  public void paintFocus(Graphics g) {
    // Suppress basic default focus ring completely for modern clean look!
  }

  @Override
  protected Dimension getThumbSize() {
    // Set standard circular dimensions footprint for the thumb knob
    return new Dimension(14, 14);
  }
}
