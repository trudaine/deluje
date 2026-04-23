package org.chuck.deluge.ui;

import javax.swing.*;
import java.awt.*;
import org.chuck.core.ChuckVM;

/** Pure Swing oscilloscope and spectrum visualizer. */
public class SwingVisualizerPanel extends JPanel {
  private final ChuckVM vm;

  public SwingVisualizerPanel(ChuckVM vm) {
    this.vm = vm;
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
    float[] data = vm != null && vm.getDacChannel(0) != null ? vm.getDacChannel(0).getVisBuffer() : null;
    g2.setColor(new Color(0x00, 0xff, 0xcc));
    g2.setStroke(new BasicStroke(1.5f));
    
    if (data != null && data.length > 0) {
      int halfH = quarterH / 2;
      double xStep = (double) w / data.length;
      int lastX = 0;
      int lastY = halfH - (int)(data[0] * halfH * 0.9);

      for (int i = 1; i < data.length; i++) {
        int x = (int)(i * xStep);
        int y = halfH - (int)(data[i] * halfH * 0.9);
        g2.drawLine(lastX, lastY, x, y);
        lastX = x;
        lastY = y;
      }
    }
    
    // Draw Spectrum placeholder (Row 2)
    g2.setColor(new Color(0x00, 0xaa, 0xff));
    g2.drawRect(10, quarterH + 10, w - 20, quarterH - 20);
    g2.drawString("SPECTRUM", 20, quarterH + 30);

    // Draw Waterfall placeholder (Row 3)
    g2.setColor(new Color(0xff, 0x00, 0x55));
    g2.drawRect(10, quarterH * 2 + 10, w - 20, quarterH - 20);
    g2.drawString("WATERFALL", 20, quarterH * 2 + 30);

    // Draw Stereo Phase (Row 4)
    g2.setColor(new Color(0xaa, 0xff, 0x00));
    g2.drawRect(10, quarterH * 3 + 10, w - 20, quarterH - 20);
    g2.drawString("STEREO PHASE", 20, quarterH * 3 + 30);
  }

}
