package org.deluge.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;

/**
 * Reusable VU meter panel component used to display volume envelope levels in grid rows. Renders
 * levels with neon green, amber/yellow headroom, and bright red clipping regions.
 */
public class VUMeterPanel extends JPanel {
  private double lvl = 0.0;

  public VUMeterPanel() {
    // Default size hints, can be overridden by layout managers
    setPreferredSize(new Dimension(12, 24));
  }

  /** Set the current volume level (0.0 to 1.0) and trigger a repaint. */
  public void setLvl(double l) {
    this.lvl = Math.max(0.0, Math.min(1.0, l));
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2d = (Graphics2D) g;
    g2d.setColor(new Color(0x18, 0x18, 0x1a)); // deep charcoal frame
    g2d.fillRect(0, 0, getWidth(), getHeight());

    int h = (int) (lvl * getHeight());
    if (h <= 0) return;

    // Draw standard green level (0% to 65% height)
    int greenH = Math.min(h, (int) (0.65 * getHeight()));
    g2d.setColor(new Color(0x00, 0xff, 0x66)); // neon green
    g2d.fillRect(0, getHeight() - greenH, getWidth(), greenH);

    // Draw yellow headroom (65% to 85% height)
    if (h > (int) (0.65 * getHeight())) {
      int yellowH = Math.min(h, (int) (0.85 * getHeight())) - (int) (0.65 * getHeight());
      g2d.setColor(new Color(0xff, 0xaa, 0x00)); // amber/orange
      g2d.fillRect(0, getHeight() - (int) (0.65 * getHeight()) - yellowH, getWidth(), yellowH);
    }

    // Draw red clipping (85% to 100% height)
    if (h > (int) (0.85 * getHeight())) {
      int redH = h - (int) (0.85 * getHeight());
      g2d.setColor(new Color(0xff, 0x33, 0x33)); // bright red
      g2d.fillRect(0, getHeight() - (int) (0.85 * getHeight()) - redH, getWidth(), redH);
    }
  }
}
