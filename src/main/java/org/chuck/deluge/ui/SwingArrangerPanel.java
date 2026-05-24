package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Linear Arranger timeline plotted visually side by side. */
public class SwingArrangerPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;
  private final int trackHeight = 45;
  private final int numTracks = 8;

  private java.util.List<org.chuck.deluge.model.ArrangerClip> clips = new java.util.ArrayList<>();

  private int pixelsPerBar = 80;

  public SwingArrangerPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setBackground(new Color(0x20, 0x20, 0x20));

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            int t = e.getY() / trackHeight;
            int bar = (e.getX() - 100) / pixelsPerBar + 1;

            if (SwingUtilities.isRightMouseButton(e)) {
              clips.removeIf(clip -> clip.trackIndex() == t && clip.startBar() == bar);
              repaint();
              return;
            }

            if (t >= 0 && t < numTracks && bar >= 1) {
              clips.add(new org.chuck.deluge.model.ArrangerClip(t, "CLIP_" + (t + 1), bar, 2));
              repaint();
            }
          }
        });

    addMouseWheelListener(
        e -> {
          if (e.isControlDown()) {
            pixelsPerBar -= e.getWheelRotation() * 10;
            pixelsPerBar = Math.max(40, Math.min(200, pixelsPerBar));
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

      g2.setColor(SwingSynthConfigDialog.BG_CONTROL);
      g2.drawLine(100, t * trackHeight, w, t * trackHeight);
    }

    // Draw clips
    for (org.chuck.deluge.model.ArrangerClip clip : clips) {
      int t = clip.trackIndex();
      g2.setColor(new Color(0xff, 0xaa, 0x00, 0xaa));
      int x = 100 + (clip.startBar() - 1) * pixelsPerBar;
      g2.fillRoundRect(
          x, t * trackHeight + 4, clip.durationBars() * pixelsPerBar - 4, trackHeight - 10, 8, 8);

      g2.setColor(Color.BLACK);
      g2.drawString(clip.patternId(), x + 10, t * trackHeight + 25);
    }

    // Playhead
    int playStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    int playheadX = 100 + (playStep * 30);
    g2.setColor(Color.RED);
    g2.setStroke(new BasicStroke(2));
    g2.drawLine(playheadX, 0, playheadX, h);
  }
}
