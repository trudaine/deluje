package org.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import javax.swing.*;

/**
 * A luxury, neon-styled 2D Waveform Editor Canvas. Displays the currently selected single-cycle
 * waveform and supports real-time mouse drawing with linear interpolation to prevent gaps during
 * fast drags.
 */
public class SwingWaveformDrawingCanvas extends JComponent {

  private final float[] cycleBuffer;
  private int cycleSize = 2048;

  private final Color GRID_COLOR = new Color(0x22, 0x22, 0x28);
  private final Color WAVE_COLOR = new Color(0x00, 0xff, 0xcc); // Sleek neon Mint/Cyan
  private final Color GLOW_COLOR = new Color(0x00, 0xff, 0xcc, 30);

  private int lastDragX = -1;
  private int lastDragY = -1;

  private Runnable onUpdateListener = null;

  public SwingWaveformDrawingCanvas(int cycleSize) {
    this.cycleSize = cycleSize;
    this.cycleBuffer = new float[cycleSize];

    setBackground(new Color(0x10, 0x10, 0x12));
    setPreferredSize(new Dimension(400, 240));
    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

    // Initialize with a simple sine wave as default
    for (int i = 0; i < cycleSize; i++) {
      cycleBuffer[i] = (float) Math.sin(2.0 * Math.PI * i / cycleSize);
    }

    MouseAdapter mouseHandler =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            drawAtMouse(e.getX(), e.getY(), true);
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            drawAtMouse(e.getX(), e.getY(), false);
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            lastDragX = -1;
            lastDragY = -1;
          }
        };

    addMouseListener(mouseHandler);
    addMouseMotionListener(mouseHandler);
  }

  public float[] getCycleBuffer() {
    return cycleBuffer;
  }

  public void setCycleBuffer(float[] source) {
    if (source == null || source.length != cycleSize) return;
    System.arraycopy(source, 0, this.cycleBuffer, 0, cycleSize);
    repaint();
  }

  public void setOnUpdateListener(Runnable listener) {
    this.onUpdateListener = listener;
  }

  private void drawAtMouse(int x, int y, boolean isStart) {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) return;

    // Constrain mouse coordinates to component bounds
    x = Math.max(0, Math.min(w - 1, x));
    y = Math.max(0, Math.min(h - 1, y));

    int currentIdx = (int) ((double) x / (w - 1) * (cycleSize - 1));
    // Screen Y increases downwards, so flip it to match amplitude [-1.0, 1.0]
    float currentVal = 1.0f - 2.0f * y / (h - 1);

    if (isStart || lastDragX == -1) {
      cycleBuffer[currentIdx] = currentVal;
    } else {
      // Linearly interpolate between the last mouse drag point and the current point
      int lastIdx = (int) ((double) lastDragX / (w - 1) * (cycleSize - 1));
      float lastVal = 1.0f - 2.0f * lastDragY / (h - 1);

      int minIdx = Math.min(lastIdx, currentIdx);
      int maxIdx = Math.max(lastIdx, currentIdx);

      if (minIdx == maxIdx) {
        cycleBuffer[minIdx] = currentVal;
      } else {
        for (int i = minIdx; i <= maxIdx; i++) {
          double t = (double) (i - minIdx) / (maxIdx - minIdx);
          float val =
              (lastIdx < currentIdx)
                  ? (float) (lastVal + t * (currentVal - lastVal))
                  : (float) (currentVal + t * (lastVal - currentVal));
          cycleBuffer[i] = val;
        }
      }
    }

    lastDragX = x;
    lastDragY = y;
    repaint();

    if (onUpdateListener != null) {
      onUpdateListener.run();
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // ── 1. Background Grid ──
    g2.setColor(getBackground());
    g2.fillRect(0, 0, w, h);

    // Vertical grid lines
    g2.setColor(GRID_COLOR);
    g2.setStroke(new BasicStroke(0.5f));
    for (int x = 0; x < w; x += 30) {
      g2.drawLine(x, 0, x, h);
    }
    // Horizontal grid lines
    for (int y = 0; y < h; y += 20) {
      g2.drawLine(0, y, w, y);
    }

    // Center horizontal baseline (0.0 amplitude)
    g2.setColor(new Color(0x3a, 0x3a, 0x42));
    g2.setStroke(new BasicStroke(1.0f));
    g2.drawLine(0, h / 2, w, h / 2);

    // ── 2. Draw Waveform Path ──
    Path2D.Double path = new Path2D.Double();
    boolean first = true;
    for (int i = 0; i < cycleSize; i++) {
      double normX = (double) i / (cycleSize - 1);
      double normY = cycleBuffer[i]; // [-1.0, 1.0]

      double x = normX * (w - 1);
      double y = (1.0 - normY) * 0.5 * (h - 1); // map to screen coords

      if (first) {
        path.moveTo(x, y);
        first = false;
      } else {
        path.lineTo(x, y);
      }
    }

    // Draw glowing under-fill shadow
    g2.setColor(GLOW_COLOR);
    g2.setStroke(new BasicStroke(4.0f));
    g2.draw(path);

    // Draw sharp main vector line
    g2.setColor(WAVE_COLOR);
    g2.setStroke(new BasicStroke(2.0f));
    g2.draw(path);

    // ── 3. Draw Info Text ──
    g2.setColor(Color.GRAY);
    g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
    g2.drawString("ACTIVE CYCLE EDITOR", 10, 15);
    g2.drawString("Draw directly with left mouse button", 10, 27);
  }
}
