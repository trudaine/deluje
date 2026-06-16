package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.geom.GeneralPath;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.LfoModel;
import org.chuck.deluge.model.LfoType;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * An animated, high-fidelity LFO Modulation Monitor panel. Displays 4 stacked LFO oscilloscope
 * lanes showing the selected waveform shapes and animates a glowing neon phase dot traveling along
 * the curves in real-time, synchronized with each LFO's current frequency rate (Hz).
 */
public class LfoMonitorComponent extends JComponent {
  private final SynthTrackModel model;
  private final float[] phases = new float[BridgeContract.LFO_COUNT];
  private final Timer animationTimer;
  private long lastTime = System.currentTimeMillis();

  // Color palette for LFO lanes
  private static final Color[] LFO_COLORS = {
    new Color(0x00, 0xff, 0xcc), // LFO 0: Neon Mint
    new Color(0x00, 0xcc, 0xff), // LFO 1: Neon Blue
    new Color(0xff, 0x00, 0x7f), // LFO 2: Neon Pink
    new Color(0xff, 0xcc, 0x00) // LFO 3: Neon Yellow
  };

  public LfoMonitorComponent(SynthTrackModel model) {
    this.model = model;
    setPreferredSize(new Dimension(320, 240));
    setMinimumSize(new Dimension(200, 160));

    // Animated phase accumulator timer (runs at a smooth 40 FPS / 25ms)
    animationTimer =
        new Timer(
            25,
            e -> {
              long now = System.currentTimeMillis();
              float dt = (now - lastTime) / 1000.0f;
              lastTime = now;

              // Advance phase for each active LFO based on its frequency rate (Hz)
              for (int l = 0; l < BridgeContract.LFO_COUNT; l++) {
                LfoModel lm = model.getLfo(l);
                float rate = (lm != null) ? lm.rateHz() : 1.0f;
                // Accumulate phase modulo 1.0
                phases[l] = (phases[l] + dt * rate) % 1.0f;
              }
              repaint();
            });
  }

  public void startAnimation() {
    lastTime = System.currentTimeMillis();
    animationTimer.start();
  }

  public void stopAnimation() {
    animationTimer.stop();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Sleek dark background
    g2.setColor(new Color(0x14, 0x14, 0x18));
    g2.fillRect(0, 0, w, h);

    int numLanes = BridgeContract.LFO_COUNT;
    int laneH = h / numLanes;

    for (int l = 0; l < numLanes; l++) {
      int laneY = l * laneH;
      Color laneColor = LFO_COLORS[l];

      // Draw subtle horizontal dividing lines
      if (l > 0) {
        g2.setColor(new Color(0x22, 0x22, 0x25));
        g2.drawLine(0, laneY, w, laneY);
      }

      LfoModel lm = model.getLfo(l);
      LfoType type = (lm != null) ? lm.waveform() : LfoType.SINE;
      float rate = (lm != null) ? lm.rateHz() : 1.0f;
      float depth = (lm != null) ? lm.depth() : 1.0f;

      // Draw subtle dotted grid inside the lane
      g2.setColor(new Color(0x1b, 0x1b, 0x20));
      Stroke oldStroke = g2.getStroke();
      g2.setStroke(
          new BasicStroke(
              1.0f,
              BasicStroke.CAP_BUTT,
              BasicStroke.JOIN_MITER,
              10.0f,
              new float[] {2.0f, 4.0f},
              0.0f));
      g2.drawLine(0, laneY + laneH / 2, w, laneY + laneH / 2);
      g2.setStroke(oldStroke);

      // Lane bounds
      int paddingX = 16;
      int paddingY = 8;
      int drawW = w - 2 * paddingX;
      int drawH = laneH - 2 * paddingY;
      float startX = paddingX;
      float centerY = laneY + paddingY + drawH / 2.0f;

      // Generate waveform path
      GeneralPath path = new GeneralPath();
      boolean first = true;

      for (int x = 0; x <= drawW; x++) {
        float phi = (float) x / drawW;
        float amp = getLfoAmplitudeAtPhase(type, phi);
        float y = centerY - amp * (drawH / 2.0f) * depth;

        if (first) {
          path.moveTo(startX + x, y);
          first = false;
        } else {
          path.lineTo(startX + x, y);
        }
      }

      // 1. Draw waveform curve (semi-transparent, glowing look)
      g2.setColor(new Color(laneColor.getRed(), laneColor.getGreen(), laneColor.getBlue(), 80));
      g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.draw(path);
      g2.setStroke(oldStroke);

      // 2. Draw animated glowing phase dot moving along the wave path
      float currentPhase = phases[l];
      float dotX = startX + currentPhase * drawW;
      float dotAmp = getLfoAmplitudeAtPhase(type, currentPhase);
      float dotY = centerY - dotAmp * (drawH / 2.0f) * depth;

      g2.setColor(laneColor);
      g2.fillOval((int) dotX - 4, (int) dotY - 4, 8, 8);

      // Add outer glow ring
      g2.setColor(new Color(laneColor.getRed(), laneColor.getGreen(), laneColor.getBlue(), 120));
      g2.drawOval((int) dotX - 5, (int) dotY - 5, 10, 10);

      // 3. Draw lane label in corner (e.g., "LFO 0: SINE 1.25Hz")
      g2.setFont(new Font("SansSerif", Font.BOLD, 9));
      g2.setColor(Color.GRAY);
      String label = String.format("LFO %d: %s (%.2f Hz)", l, type.name(), rate);
      g2.drawString(label, startX, laneY + 14);
    }
  }

  /** Calculates the normalized LFO amplitude (-1.0 to +1.0) for a given wave shape and phase. */
  private float getLfoAmplitudeAtPhase(LfoType type, float phi) {
    switch (type) {
      case SAW:
        // Falling saw wave (1.0 down to -1.0)
        return 1.0f - 2.0f * phi;
      case SQUARE:
        // Square wave (1.0 or -1.0)
        return (phi < 0.5f) ? 1.0f : -1.0f;
      case TRIANGLE:
        // Triangle wave
        if (phi < 0.25f) return phi * 4.0f;
        if (phi < 0.75f) return 2.0f - phi * 4.0f;
        return phi * 4.0f - 4.0f;
      case S_AND_H:
      case RANDOM_WALK:
        // Stepped Sample & Hold (stepped random intervals)
        // Replicate steps by hashing the phase integer cycle
        int step = (int) (phi * 8.0f); // 8 steps per cycle
        // Pseudo-random hash based on step index
        double hash = Math.sin(step * 12.9898) * 43758.5453;
        return (float) (hash - Math.floor(hash)) * 2.0f - 1.0f;
      case WARBLER:
        // Complex warble modulation (dual sine combination)
        return (float) (Math.sin(phi * 2.0 * Math.PI) * 0.7 + Math.sin(phi * 6.0 * Math.PI) * 0.3);
      case CUSTOM:
        {
          int idx = (int) (phi * 255.0f);
          idx = Math.max(0, Math.min(255, idx));
          return (float) model.getCustomLfoWave()[idx] / 1073741824.0f;
        }
      case SINE:
      default:
        // Standard Sine wave
        return (float) Math.sin(phi * 2.0 * Math.PI);
    }
  }
}
