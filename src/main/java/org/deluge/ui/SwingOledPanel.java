package org.deluge.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import javax.swing.JPanel;
import org.deluge.firmware.hid.FirmwareDisplay;
import org.deluge.firmware.hid.VirtualOLED;

/**
 * Swing component that renders the high-fidelity VirtualOLED display. Emulates the look of the
 * hardware's 128x64 white-on-black OLED.
 */
public class SwingOledPanel extends JPanel {
  private final VirtualOLED virtualOLED;

  public SwingOledPanel() {
    this.virtualOLED = FirmwareDisplay.get().getVirtualOLED();
    setBackground(Color.BLACK);
    setPreferredSize(new Dimension(128, 48));
    setMinimumSize(new Dimension(128, 48));
    setMaximumSize(new Dimension(128, 48));

    FirmwareDisplay.get().setOledListener(this::repaint);
  }

  public void drawRawFrameBuffer(byte[] frameBuffer) {
    virtualOLED.drawRawFrameBuffer(frameBuffer);
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2d = (Graphics2D) g;

    // Scale up for visibility
    g2d.drawImage(virtualOLED.getImage(), 0, 0, getWidth(), getHeight(), null);

    // Draw a slight scanline/pixel effect for authenticity
    g2d.setColor(new Color(0, 0, 0, 50));
    for (int y = 0; y < getHeight(); y += 2) {
      g2d.drawLine(0, y, getWidth(), y);
    }
  }
}
