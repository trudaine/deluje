package org.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;
import org.deluge.BridgeContract;

/** Linear Arranger timeline plotted visually side by side. */
public class SwingArrangerPanel extends JPanel {
  private final BridgeContract bridge;

  private final int trackHeight = 45;
  private final int numTracks = 8;

  private java.util.List<org.deluge.model.ArrangerClip> clips = new java.util.ArrayList<>();

  private int pixelsPerBar = 80;

  public SwingArrangerPanel(final BridgeContract bridge) {
    this.bridge = bridge;

    setBackground(new Color(0x20, 0x20, 0x20));

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            int t = e.getY() / trackHeight;
            int bar = (e.getX() - 100) / pixelsPerBar + 1;

            if (SwingUtilities.isRightMouseButton(e)) {
              clips.removeIf(c -> c.trackIndex() == t && (int) (c.getStartBar() + 1) == bar);
              repaint();
              return;
            }

            if (t >= 0 && t < numTracks && bar >= 1) {
              clips.add(
                  new org.deluge.model.ArrangerClip(
                      t,
                      new org.deluge.model.ClipModel("CLIP_" + (t + 1), 1, 16),
                      (bar - 1) * 96,
                      2 * 96));
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
    for (org.deluge.model.ArrangerClip c : clips) {
      int t = c.trackIndex();
      g2.setColor(new Color(0xff, 0xaa, 0x00, 0xaa));
      int x = 100 + (int) (c.getStartBar() * pixelsPerBar);
      g2.fillRoundRect(
          x,
          t * trackHeight + 4,
          (int) (c.getDurationBars() * pixelsPerBar) - 4,
          trackHeight - 10,
          8,
          8);

      g2.setColor(Color.BLACK);
      g2.drawString(c.clip().getName(), x + 10, t * trackHeight + 25);
    }

    // Playhead
    int playStep = (int) bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    int playheadX = 100 + (playStep * 30);
    g2.setColor(Color.RED);
    g2.setStroke(new BasicStroke(2));
    g2.drawLine(playheadX, 0, playheadX, h);
  }
}
