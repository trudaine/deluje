package org.chuck.deluge.ui;

import javax.swing.*;
import java.awt.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Painted step parameter bar lane. */
public class SwingVelocityLanePanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  public SwingVelocityLanePanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setPreferredSize(new Dimension(0, 120));
    setBackground(new Color(0x1a, 0x1a, 0x1a));
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    g2.setColor(new Color(0x00, 0xff, 0xcc, 0xaa));
    
    // Draw 16 columns mapping step array velocity
    int colW = w / 16;
    for (int i = 0; i < 16; i++) {
      double val = 0.75; // Simulated velocity value
      int barH = (int) (val * (h - 20));
      
      g2.fillRect(i * colW + 10, h - barH - 10, colW - 20, barH);
    }

    g2.setColor(Color.DARK_GRAY);
    g2.drawLine(0, 0, w, 0); // Top border
  }
}
