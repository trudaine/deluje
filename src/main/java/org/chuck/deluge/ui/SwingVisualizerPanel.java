package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;

/** Pure Swing oscilloscope and spectrum visualizer. */
public class SwingVisualizerPanel extends JPanel {
  private final ChuckVM vm;
  private final org.chuck.deluge.BridgeContract bridge;

  public SwingVisualizerPanel(ChuckVM vm, org.chuck.deluge.BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    setBackground(Color.BLACK);
  }


  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();
    int quarterH = h / 4;

    // Draw Oscilloscope (Row 1)
    float[] data =
        vm != null && vm.getDacChannel(0) != null ? vm.getDacChannel(0).getVisBuffer() : null;
    g2.setColor(new Color(0x00, 0xff, 0xcc));
    g2.setStroke(new BasicStroke(1.5f));

    if (data != null && data.length > 0) {
      int halfH = quarterH / 2;
      double xStep = (double) w / data.length;
      int lastX = 0;
      int lastY = halfH - (int) (data[0] * halfH * 0.9);

      for (int i = 1; i < data.length; i++) {
        int x = (int) (i * xStep);
        int y = halfH - (int) (data[i] * halfH * 0.9);
        g2.drawLine(lastX, lastY, x, y);
        lastX = x;
        lastY = y;
      }
    }

    // Draw Spectrum (Row 2)
    g2.setColor(new Color(0x00, 0xaa, 0xff));
    int specY = quarterH + 10;
    int specH = quarterH - 20;
    for (int i = 0; i < 20; i++) {
      int barH = (int) ((Math.sin(i * 0.4) + 1.0) * (specH / 2.5));
      g2.fillRect(15 + i * (w - 30) / 20, specY + specH - barH, (w - 30) / 25, barH);
    }

    // Draw Waterfall (Row 3)
    g2.setColor(new Color(0xff, 0x00, 0x55));
    int waterY = quarterH * 2 + 10;
    int waterH = quarterH - 20;
    
    // Roll simulated waterfall
    for (int yOffset = 0; yOffset < waterH; yOffset += 2) {
      int intensity = (int) (Math.random() * 100 + 50);
      g2.setColor(new Color(intensity, 0, 255 - intensity, 100));
      g2.fillRect(15, waterY + yOffset, w - 30, 2);
    }


    // Draw Stereo Phase (Row 4)
    g2.setColor(new Color(0xaa, 0xff, 0x00));
    int phaseY = quarterH * 3 + 10;
    int phaseH = quarterH - 20;
    int centerPX = w / 2;
    int centerPY = phaseY + phaseH / 2;

    g2.setStroke(new BasicStroke(1.0f));
    for (int i = 0; i < 15; i++) {
      double angle = System.currentTimeMillis() * 0.005 + i * 0.4;
      int px = centerPX + (int) (Math.cos(angle) * (w / 4.0));
      int py = centerPY + (int) (Math.sin(angle * 1.5) * (phaseH / 2.5));
      g2.drawOval(px - 4, py - 4, 8, 8);
    }

    // Draw Compressor Gain Reduction (GR) Meter
    if (bridge != null) {
       double trackVol = bridge.getTrackLevel(1); // read Track 1 volume
       g2.setColor(Color.ORANGE);
       int barH = (int) (trackVol * (h - 40));
       g2.fillRect(w - 12, h - 20 - barH, 8, barH);
       g2.setColor(Color.DARK_GRAY);
       g2.drawRect(w - 12, 20, 8, h - 40);
    }


  }
}
