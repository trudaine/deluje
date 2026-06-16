package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.GeneralPath;
import javax.swing.*;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * A beautiful, interactive 16-step LFO Custom Waveform Draw Pad and Step Sequencer. Allows clicking
 * and dragging to draw custom LFO shapes with step-hold or smooth linear interpolation, writing
 * directly to the track's DSP buffer.
 */
public class LfoDrawPad extends JPanel {

  private final SynthTrackModel model;
  private final java.lang.Runnable onWaveChanged;

  private final float[] steps = new float[16];
  private boolean smoothMode = false;

  public LfoDrawPad(SynthTrackModel model, java.lang.Runnable onWaveChanged) {
    this.model = model;
    this.onWaveChanged = onWaveChanged;

    setBackground(new Color(0x10, 0x10, 0x12));
    setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1));
    setPreferredSize(new Dimension(340, 140));

    // Initialize steps from model's current customLfoWave (recover the 16 steps by subsampling)
    int[] customLfoWave = model.getCustomLfoWave();
    for (int i = 0; i < 16; i++) {
      steps[i] = (float) customLfoWave[i * 16] / 1073741824.0f;
    }

    // ── Mouse Dragging to Draw ──
    MouseAdapter drawHandler =
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            drawAtMouse(e.getX(), e.getY());
          }
        };
    addMouseListener(drawHandler);

    addMouseMotionListener(
        new MouseMotionAdapter() {
          @Override
          public void mouseDragged(MouseEvent e) {
            drawAtMouse(e.getX(), e.getY());
          }
        });
  }

  public boolean isSmoothMode() {
    return smoothMode;
  }

  public void setSmoothMode(boolean smooth) {
    this.smoothMode = smooth;
    updateModelWave();
    repaint();
  }

  private void drawAtMouse(int mx, int my) {
    int w = getWidth();
    int h = getHeight();

    int paddingX = 10;
    int paddingY = 10;
    int drawW = w - 2 * paddingX;
    int drawH = h - 2 * paddingY;

    if (drawW <= 0 || drawH <= 0) return;

    // Find which step is under the mouse
    float stepW = (float) drawW / 16.0f;
    int stepIdx = (int) ((mx - paddingX) / stepW);
    stepIdx = Math.max(0, Math.min(15, stepIdx));

    // Calculate value from Y coordinate (+1.0 at top, -1.0 at bottom)
    float baseY = paddingY + drawH;
    float normY = (baseY - my) / (float) drawH; // 0.0 to 1.0
    float val = -1.0f + 2.0f * normY; // map 0-1 to -1.0 to +1.0
    val = Math.max(-1.0f, Math.min(1.0f, val));

    steps[stepIdx] = val;
    updateModelWave();
    repaint();
  }

  /** Interpolates the 16 steps into the 256-sample model customLfoWave buffer. */
  private void updateModelWave() {
    int[] customLfoWave = model.getCustomLfoWave();
    for (int i = 0; i < 256; i++) {
      float val;
      if (smoothMode) {
        // Smooth linear interpolation between the 16 steps
        int step1 = i / 16;
        int step2 = (step1 + 1) % 16;
        float t = (i % 16) / 16.0f;
        // Smoothstep cosine blend for even cleaner curves
        float cosT = (float) ((1.0 - Math.cos(t * Math.PI)) / 2.0);
        val = steps[step1] * (1.0f - cosT) + steps[step2] * cosT;
      } else {
        // Sharp step-hold sequence
        val = steps[i / 16];
      }

      // Map to Q31 range used by DSP engine (-2^30 to +2^30)
      customLfoWave[i] = (int) (val * 1073741824.0f);
    }

    if (onWaveChanged != null) {
      onWaveChanged.run();
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    int paddingX = 10;
    int paddingY = 10;
    int drawW = w - 2 * paddingX;
    int drawH = h - 2 * paddingY;

    float stepW = (float) drawW / 16.0f;
    float baseY = paddingY + drawH;
    float centerY = paddingY + drawH / 2.0f;

    // ── 1. Draw Background Grid ──
    g2.setColor(new Color(0x1a, 0x1a, 0x22));
    g2.setStroke(new BasicStroke(0.5f));

    // Vertical step dividers
    for (int i = 0; i <= 16; i++) {
      int x = (int) (paddingX + i * stepW);
      g2.drawLine(x, paddingY, x, (int) baseY);
    }

    // Horizontal helper lines (every 0.5 amplitude)
    g2.drawLine(paddingX, (int) centerY, paddingX + drawW, (int) centerY);
    g2.drawLine(
        paddingX, (int) (centerY - drawH / 4.0f), paddingX + drawW, (int) (centerY - drawH / 4.0f));
    g2.drawLine(
        paddingX, (int) (centerY + drawH / 4.0f), paddingX + drawW, (int) (centerY + drawH / 4.0f));

    // Center zero line (glowing dotted orange)
    g2.setColor(new Color(0xff, 0x66, 0x00, 100));
    Stroke oldStroke = g2.getStroke();
    g2.setStroke(
        new BasicStroke(
            1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1.0f, new float[] {2, 2}, 0.0f));
    g2.drawLine(paddingX, (int) centerY, paddingX + drawW, (int) centerY);
    g2.setStroke(oldStroke);

    // ── 2. Draw Waveform Steps/Curves ──
    Color primaryAccent = ThemeManager.getPrimaryAccent();
    Color secondaryAccent = ThemeManager.getSecondaryAccent();
    Color neonFill =
        new Color(
            secondaryAccent.getRed(), secondaryAccent.getGreen(), secondaryAccent.getBlue(), 40);

    if (smoothMode) {
      // Draw smooth interpolated vector path
      GeneralPath path = new GeneralPath();
      boolean first = true;

      for (int i = 0; i < 256; i++) {
        float val;
        // Re-read from model's interpolated wave buffer
        val = (float) model.getCustomLfoWave()[i] / 1073741824.0f;

        float x = paddingX + ((float) i / 255.0f) * drawW;
        float y = centerY - val * (drawH / 2.0f);

        if (first) {
          path.moveTo(x, y);
          first = false;
        } else {
          path.lineTo(x, y);
        }
      }

      // Draw glowing shaded area under the curve
      GeneralPath fillPath = (GeneralPath) path.clone();
      fillPath.lineTo(paddingX + drawW, centerY);
      fillPath.lineTo(paddingX, centerY);
      fillPath.closePath();
      g2.setPaint(
          new GradientPaint(
              0,
              centerY - drawH / 2.0f,
              neonFill,
              0,
              centerY + drawH / 2.0f,
              new Color(0, 0, 0, 0)));
      g2.fill(fillPath);

      // Draw the main smooth line in primary accent
      g2.setColor(primaryAccent);
      g2.setStroke(new BasicStroke(2.0f));
      g2.draw(path);
      g2.setStroke(oldStroke);

      // Draw step node points in secondary accent
      for (int i = 0; i < 16; i++) {
        float x = paddingX + i * stepW + stepW / 2.0f;
        float y = centerY - steps[i] * (drawH / 2.0f);
        g2.setColor(Color.WHITE);
        g2.fillOval((int) x - 3, (int) y - 3, 6, 6);
        g2.setColor(secondaryAccent);
        g2.drawOval((int) x - 3, (int) y - 3, 6, 6);
      }
    } else {
      // Draw step columns (sequencer style) in secondary accent
      for (int i = 0; i < 16; i++) {
        float val = steps[i];
        int x = (int) (paddingX + i * stepW + 2);
        int stepWInt = (int) (stepW - 4);

        int y, hBar;
        if (val >= 0) {
          y = (int) (centerY - val * (drawH / 2.0f));
          hBar = (int) (centerY - y);
        } else {
          y = (int) centerY;
          hBar = (int) (-val * (drawH / 2.0f));
        }

        // Fill bar with glowing gradient
        g2.setColor(neonFill);
        g2.fillRect(x, y, stepWInt, hBar);

        // Draw top/bottom border cap in bold secondary accent
        g2.setColor(secondaryAccent);
        g2.setStroke(new BasicStroke(1.5f));
        if (val >= 0) {
          g2.drawLine(x, y, x + stepWInt, y);
        } else {
          g2.drawLine(x, y + hBar, x + stepWInt, y + hBar);
        }
        g2.setStroke(oldStroke);
      }
    }

    // ── 3. Draw Step Labels at the Bottom ──
    g2.setFont(new Font("Monospaced", Font.BOLD, 8));
    g2.setColor(new Color(0x60, 0x60, 0x68));
    for (int i = 0; i < 16; i++) {
      String label = String.valueOf(i + 1);
      int lblW = g2.getFontMetrics().stringWidth(label);
      float x = paddingX + i * stepW + (stepW - lblW) / 2.0f;
      g2.drawString(label, x, baseY + 12);
    }
  }
}
