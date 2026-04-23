package org.chuck.deluge.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Linear Arranger timeline plotted visually side by side. */
public class SwingArrangerPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final int trackHeight = 45;
  private final int numTracks = 8;

  public SwingArrangerPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setBackground(new Color(0x20, 0x20, 0x20));

    addMouseListener(new MouseAdapter() {
      @Override
      public void mousePressed(MouseEvent e) {
        repaint();
      }
    });
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Draw Track headers and grids
    for (int t = 0; t < numTracks; t++) {
      g2.setColor(new Color(0x2d, 0x2d, 0x2d));
      g2.fillRect(0, t * trackHeight, 100, trackHeight - 2);
      g2.setColor(Color.LIGHT_GRAY);
      g2.drawString("TRACK " + (t + 1), 15, t * trackHeight + 25);

      g2.setColor(new Color(0x33, 0x33, 0x33));
      g2.drawLine(100, t * trackHeight, w, t * trackHeight);

      // Draw example clip arrangement
      g2.setColor(new Color(0xff, 0xaa, 0x00, 0xaa));
      g2.fillRoundRect(150 + t * 40, t * trackHeight + 4, 120, trackHeight - 10, 8, 8);
    }

    // Playhead
    int playStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    int playheadX = 100 + (playStep * 30);
    g2.setColor(Color.RED);
    g2.setStroke(new BasicStroke(2));
    g2.drawLine(playheadX, 0, playheadX, h);
  }
}
