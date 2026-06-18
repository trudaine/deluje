package org.deluge.ui;

import java.awt.*;
import java.awt.geom.GeneralPath;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.FilterMode;
import org.deluge.model.SynthTrackModel;

/**
 * Custom Swing component that draws the real-time frequency response curve of the synthesizer's
 * Low-Pass Filter, and supports interactive 2D drag-to-sweep cutoff and resonance editing.
 */
public class FilterGraphComponent extends JComponent {
  private final SynthTrackModel model;
  private final BridgeContract bridge;
  private final int trackIndex;

  private boolean isDragging = false;

  public FilterGraphComponent(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    this.model = model;
    this.bridge = bridge;
    this.trackIndex = trackIndex;
    setPreferredSize(new Dimension(300, 110));
    setMinimumSize(new Dimension(200, 80));

    // ── Mouse Listeners for Interactive Drag-to-Sweep ──
    addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (isOverNode(e.getX(), e.getY())) {
              isDragging = true;
              setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
          }

          @Override
          public void mouseReleased(java.awt.event.MouseEvent e) {
            isDragging = false;
            if (isOverNode(e.getX(), e.getY())) {
              setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else {
              setCursor(Cursor.getDefaultCursor());
            }
          }
        });

    addMouseMotionListener(
        new java.awt.event.MouseMotionAdapter() {
          @Override
          public void mouseMoved(java.awt.event.MouseEvent e) {
            if (isOverNode(e.getX(), e.getY())) {
              setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            } else {
              setCursor(Cursor.getDefaultCursor());
            }
          }

          @Override
          public void mouseDragged(java.awt.event.MouseEvent e) {
            if (!isDragging) return;

            int w = getWidth();
            int h = getHeight();
            int paddingX = 12;
            int paddingY = 12;
            int drawW = w - 2 * paddingX;
            int drawH = h - 2 * paddingY;
            float startX = paddingX;
            float baseY = paddingY + drawH;

            // 1. Map mouse X back to cutoff parameter (0.0 to 1.0)
            float u = (float) (e.getX() - startX) / drawW;
            u = Math.max(0.0f, Math.min(1.0f, u));

            // fc = 0.01 + 3.99 * u * u
            // fc = 0.01 + 0.99 * cutoff * cutoff => cutoff = sqrt(3.99 / 0.99) * u
            float cutoffVal = (float) (Math.sqrt(3.99 / 0.99) * u);
            cutoffVal = Math.max(0.0f, Math.min(1.0f, cutoffVal));

            // 2. Map mouse Y back to resonance parameter (0.0 to 1.0)
            float hRatio = (float) (baseY - e.getY()) / drawH;
            hRatio = Math.max(0.01f, Math.min(1.2f, hRatio)); // clamp to prevent division by zero

            // hRatio = 1.0 / (1.0 + resonance * 2.5)
            // resonance = ((1.0 / hRatio) - 1.0) / 2.5
            float resonanceVal = (float) (((1.0f / hRatio) - 1.0f) / 2.5f);
            resonanceVal = Math.max(0.0f, Math.min(1.0f, resonanceVal));

            // 3. Find and update parent sliders to trigger the centralized updates & audio sync
            Window win = SwingUtilities.getWindowAncestor(FilterGraphComponent.this);
            if (win instanceof Container container) {
              JSlider cutoffSlider =
                  SwingSynthConfigDialog.findSliderByName(container, "lpfCutoff");
              JSlider resSlider =
                  SwingSynthConfigDialog.findSliderByName(container, "lpfResonance");

              if (cutoffSlider != null) {
                cutoffSlider.setValue((int) (cutoffVal * 100));
              }
              if (resSlider != null) {
                resSlider.setValue((int) (resonanceVal * 100));
              }
            }

            repaint();
          }
        });
  }

  private boolean isOverNode(int mx, int my) {
    float cutoff = (float) bridge.getTrackFilterFreq(trackIndex);
    float resonance = (float) bridge.getTrackFilterRes(trackIndex);

    int w = getWidth();
    int h = getHeight();
    int paddingX = 12;
    int paddingY = 12;
    int drawW = w - 2 * paddingX;
    int drawH = h - 2 * paddingY;
    float startX = paddingX;
    float baseY = paddingY + drawH;
    float startY = paddingY;

    float fc = 0.01f + 0.99f * cutoff * cutoff;
    float cutoffX = startX + (float) Math.sqrt((fc - 0.01f) / 3.99f) * drawW;
    float cutoffY = baseY - (1.0f / (1.0f + resonance * 2.5f)) * drawH;
    cutoffY = Math.max(startY - 4, Math.min(cutoffY, baseY));

    double dx = mx - cutoffX;
    double dy = my - cutoffY;
    return (dx * dx + dy * dy <= 225); // 15-pixel active radius
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Dark grid background
    g2.setColor(new Color(0x14, 0x14, 0x18));
    g2.fillRect(0, 0, w, h);

    // Subtle dotted grid lines
    g2.setColor(new Color(0x2d, 0x2d, 0x32));
    Stroke oldStroke = g2.getStroke();
    g2.setStroke(
        new BasicStroke(
            1.0f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER,
            10.0f,
            new float[] {2.0f, 4.0f},
            0.0f));

    // Vertical grid lines
    for (int x = 40; x < w; x += 40) {
      g2.drawLine(x, 0, x, h);
    }
    // Horizontal grid lines
    for (int y = 25; y < h; y += 25) {
      g2.drawLine(0, y, w, y);
    }
    g2.setStroke(oldStroke);

    // Dynamic state fetch (from model/bridge)
    float cutoff = (float) bridge.getTrackFilterFreq(trackIndex); // 0.0 to 1.0
    float resonance = (float) bridge.getTrackFilterRes(trackIndex); // 0.0 to 1.0
    FilterMode mode = model.getFilterMode();
    boolean notch = model.isFilterNotch();

    // Layout math
    int paddingX = 12;
    int paddingY = 12;
    int drawW = w - 2 * paddingX;
    int drawH = h - 2 * paddingY;
    float startX = paddingX;
    float baseY = paddingY + drawH;
    float startY = paddingY;

    // Cutoff frequency in DSP terms (quadratic mapping to make sweep feel natural)
    float fc = 0.01f + 0.99f * cutoff * cutoff;
    // Resonance Q mapping
    float q = 0.5f + 8.5f * resonance;

    GeneralPath path = new GeneralPath();
    boolean first = true;

    // Generate response curve using 2nd-order transfer function equations
    for (int x = 0; x <= drawW; x++) {
      float u = (float) x / drawW;
      // Map screen X quadratically to frequency
      float f = 0.01f + 3.99f * u * u;
      float r = f / fc; // Frequency ratio (f / fc)

      float gain = 0f;

      if (mode == FilterMode.SVF && notch) {
        // Notch response: |1 - r^2| / sqrt((1 - r^2)^2 + r^2 / Q^2)
        float num = Math.abs(1.0f - r * r);
        float den = (float) Math.sqrt((1.0f - r * r) * (1.0f - r * r) + (r * r) / (q * q));
        gain = num / Math.max(den, 0.001f);
      } else {
        // Low-pass response: 1 / sqrt((1 - r^2)^2 + r^2 / Q^2)
        float den = (float) Math.sqrt((1.0f - r * r) * (1.0f - r * r) + (r * r) / (q * q));
        gain = 1.0f / Math.max(den, 0.001f);

        // Ladder 24dB slope is steeper, scale the steepness factor
        if (mode == FilterMode.LADDER_24) {
          gain = (float) Math.pow(gain, 1.4);
        }
      }

      // Visually compress high resonance peaks so they stay gracefully in bounds
      float maxGainDisplay = 1.0f + resonance * 2.5f;
      float displayHeightRatio = gain / maxGainDisplay;
      float yVal = baseY - Math.min(displayHeightRatio, 1.2f) * drawH;
      yVal = Math.max(startY - 4, Math.min(yVal, baseY));

      if (first) {
        path.moveTo(startX + x, yVal);
        first = false;
      } else {
        path.lineTo(startX + x, yVal);
      }
    }

    // 1. Draw glowing transparent gradient fill under the curve
    Color primaryAccent = ThemeManager.getPrimaryAccent();
    g2.setPaint(
        new GradientPaint(
            0,
            startY,
            new Color(
                primaryAccent.getRed(), primaryAccent.getGreen(), primaryAccent.getBlue(), 50),
            0,
            baseY,
            new Color(
                primaryAccent.getRed(), primaryAccent.getGreen(), primaryAccent.getBlue(), 0)));
    GeneralPath fillPath = (GeneralPath) path.clone();
    fillPath.lineTo(startX + drawW, baseY);
    fillPath.lineTo(startX, baseY);
    fillPath.closePath();
    g2.fill(fillPath);

    // 2. Draw the main response line in glowing primary accent
    g2.setColor(primaryAccent);
    g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g2.draw(path);
    g2.setStroke(oldStroke);

    // 3. Draw a glowing cutoff point marker (OLED style)
    float cutoffX = startX + (float) Math.sqrt((fc - 0.01f) / 3.99f) * drawW;
    float cutoffY = baseY - (1.0f / (1.0f + resonance * 2.5f)) * drawH;
    cutoffY = Math.max(startY - 4, Math.min(cutoffY, baseY));

    g2.setColor(ThemeManager.getSecondaryAccent());
    g2.fillOval((int) cutoffX - 4, (int) cutoffY - 4, 8, 8);
    g2.setColor(new Color(0x14, 0x14, 0x18));
    g2.drawOval((int) cutoffX - 4, (int) cutoffY - 4, 8, 8);
  }
}
