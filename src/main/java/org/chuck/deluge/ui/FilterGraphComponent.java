package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.geom.GeneralPath;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.FilterMode;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * Custom Swing component that draws the real-time frequency response curve of the synthesizer's
 * Low-Pass Filter. Supports 12dB/oct, 24dB/oct, SVF Low-Pass, and SVF Notch modes with dynamic
 * resonance (Q) peaks.
 */
public class FilterGraphComponent extends JComponent {
  private final SynthTrackModel model;
  private final BridgeContract bridge;
  private final int trackIndex;

  public FilterGraphComponent(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    this.model = model;
    this.bridge = bridge;
    this.trackIndex = trackIndex;
    setPreferredSize(new Dimension(300, 110));
    setMinimumSize(new Dimension(200, 80));
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

    // 1. Draw glowing transparent amber gradient fill under the curve
    Color amberColor = new Color(0xff, 0xb3, 0x00);
    g2.setPaint(
        new GradientPaint(
            0,
            startY,
            new Color(amberColor.getRed(), amberColor.getGreen(), amberColor.getBlue(), 50),
            0,
            baseY,
            new Color(amberColor.getRed(), amberColor.getGreen(), amberColor.getBlue(), 0)));
    GeneralPath fillPath = (GeneralPath) path.clone();
    fillPath.lineTo(startX + drawW, baseY);
    fillPath.lineTo(startX, baseY);
    fillPath.closePath();
    g2.fill(fillPath);

    // 2. Draw the main response line in glowing amber
    g2.setColor(amberColor);
    g2.setStroke(new BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
    g2.draw(path);
    g2.setStroke(oldStroke);

    // 3. Draw a glowing cutoff point marker (OLED style)
    float cutoffX = startX + (float) Math.sqrt((fc - 0.01f) / 3.99f) * drawW;
    float cutoffY = baseY - (1.0f / (1.0f + resonance * 2.5f)) * drawH;
    cutoffY = Math.max(startY - 4, Math.min(cutoffY, baseY));

    g2.setColor(new Color(0xff, 0xcc, 0x00));
    g2.fillOval((int) cutoffX - 4, (int) cutoffY - 4, 8, 8);
    g2.setColor(new Color(0x14, 0x14, 0x18));
    g2.drawOval((int) cutoffX - 4, (int) cutoffY - 4, 8, 8);
  }
}
