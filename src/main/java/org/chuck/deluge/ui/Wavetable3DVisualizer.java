package org.chuck.deluge.ui;

import java.awt.*;
import java.awt.geom.Path2D;
import java.io.File;
import java.io.IOException;
import javax.swing.*;
import org.chuck.deluge.firmware2.WaveTable;
import org.chuck.deluge.firmware2.WaveTableBand;
import org.chuck.deluge.firmware2.WaveTableReader;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * A stunning, vector-based 3D Wavetable Scanner & Morphing Visualizer. Renders the stacked
 * single-cycle waveforms of a loaded wavetable file using a perspective projection, displaying a
 * glowing neon playhead plane tracking the real-time waveIndex position.
 */
public class Wavetable3DVisualizer extends JPanel {

  private final SynthTrackModel model;
  private final int oscIndex; // 0 = Osc A, 1 = Osc B

  private WaveTable loadedWavetable = null;
  private String lastLoadedPath = null;
  private Timer animationTimer;

  public Wavetable3DVisualizer(SynthTrackModel model, int oscIndex) {
    this.model = model;
    this.oscIndex = oscIndex;

    setBackground(new Color(0x10, 0x10, 0x12));
    setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1));
    setPreferredSize(new Dimension(300, 200));

    // 30 FPS animation timer to smoothly track LFO modulations and dragging
    animationTimer =
        new Timer(
            33,
            e -> {
              checkWavetableReload();
              repaint();
            });
  }

  public void startAnimation() {
    if (animationTimer != null && !animationTimer.isRunning()) {
      animationTimer.start();
    }
  }

  public void stopAnimation() {
    if (animationTimer != null && animationTimer.isRunning()) {
      animationTimer.stop();
    }
  }

  private void checkWavetableReload() {
    String path = (oscIndex == 0) ? model.getOsc1SamplePath() : model.getOsc2SamplePath();
    boolean isWavetableMode =
        (oscIndex == 0)
            ? "WAVETABLE".equalsIgnoreCase(model.getOsc1Type())
            : "WAVETABLE".equalsIgnoreCase(model.getOsc2Type());

    if (!isWavetableMode) {
      loadedWavetable = null;
      lastLoadedPath = null;
      return;
    }

    if (path == null || path.isBlank()) {
      loadedWavetable = null;
      lastLoadedPath = null;
      return;
    }

    if (path.equals(lastLoadedPath)) {
      return; // Already loaded
    }

    lastLoadedPath = path;
    File f = new File(path);
    if (!f.exists() || !f.isFile()) {
      loadedWavetable = null;
      return;
    }

    // Load in a background thread to prevent GUI hiccups
    new Thread(
            () -> {
              try {
                WaveTable wt = new WaveTable();
                WaveTableReader.readWavetable(wt, f.getAbsolutePath());
                SwingUtilities.invokeLater(
                    () -> {
                      // Verify path hasn't changed in the meantime
                      if (f.getAbsolutePath().equals(lastLoadedPath)) {
                        this.loadedWavetable = wt;
                        repaint();
                      }
                    });
              } catch (IOException e) {
                System.err.println("[Wavetable3D] Failed to read wavetable: " + e.getMessage());
                SwingUtilities.invokeLater(() -> this.loadedWavetable = null);
              }
            })
        .start();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // ── 1. If not in Wavetable mode or no file loaded, draw beautiful placeholder grid ──
    if (loadedWavetable == null
        || loadedWavetable.bands.isEmpty()
        || loadedWavetable.numCycles <= 0) {
      drawPlaceholder(g2, w, h);
      return;
    }

    WaveTableBand band = loadedWavetable.bands.get(0); // Use full-bandwidth band
    short[] data = band.data;
    int cycleSize = band.cycleSizeNoDuplicates;
    int numCycles = loadedWavetable.numCycles;

    if (cycleSize <= 0 || data == null || data.length == 0) {
      drawPlaceholder(g2, w, h);
      return;
    }

    // ── 2. Perspective Layout Constants ──
    int centerX = w / 2;
    int centerY = h / 2 - 10;

    // Depth settings
    double depthSpread = 130.0; // Pixels Z-axis extends back
    double angleY = 0.35; // Vertical camera angle skew

    // Max cycles to draw to avoid clutter (subsample if wavetable has e.g. 64 or 128 cycles)
    int maxDrawCycles = 32;
    int cycleStep = Math.max(1, numCycles / maxDrawCycles);

    // Current playhead wave index position (0.0 to 1.0)
    float waveIndex = model.getWaveIndex();
    int playheadCycleIdx = Math.round(waveIndex * (numCycles - 1));

    // ── 3. Draw Stacked Curves from Back to Front (Painter's Algorithm) ──
    for (int i = numCycles - 1; i >= 0; i -= cycleStep) {
      // Normalize depth (0.0 at front, 1.0 at back)
      double depthNorm = (double) i / (numCycles - 1);

      // Calculate screen projection coordinates for this cycle
      Path2D.Double path = new Path2D.Double();
      int cycleStartIndex =
          i * (cycleSize + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);

      boolean first = true;
      for (int s = 0; s < cycleSize; s++) {
        if (cycleStartIndex + s >= data.length) break;

        // Get normalized coordinates (-1.0 to 1.0)
        double normX = -1.0 + 2.0 * s / (cycleSize - 1);
        double normY = (double) data[cycleStartIndex + s] / 32768.0;

        // Apply 3D perspective projection
        // Z moves from 1.0 (back) to 0.0 (front)
        double z = 1.0 - depthNorm;
        double scale = 0.55 + 0.45 * z; // Perspective scale factor

        double projX = centerX + normX * (w * 0.35) * scale;
        double projY = centerY - normY * (h * 0.22) * scale + (depthNorm - 0.5) * depthSpread;

        if (first) {
          path.moveTo(projX, projY);
          first = false;
        } else {
          path.lineTo(projX, projY);
        }
      }

      // Determine curve styling based on proximity and playhead selection
      boolean isPlayhead = (Math.abs(i - playheadCycleIdx) < cycleStep);
      if (isPlayhead) {
        // Glowing bold white for the active morphed cycle
        g2.setColor(Color.WHITE);
        g2.setStroke(new BasicStroke(2.0f));
      } else {
        // Neon color gradient: Cyan at front, fading to Indigo/Purple at back
        int r = (int) (120 * depthNorm + 0 * (1 - depthNorm));
        int gr = (int) (0 * depthNorm + 255 * (1 - depthNorm));
        int b = (int) (220 * depthNorm + 204 * (1 - depthNorm));
        int alpha = (int) (60 + 155 * (1 - depthNorm)); // fade transparency in distance

        g2.setColor(new Color(r, gr, b, alpha));
        g2.setStroke(new BasicStroke(1.0f));
      }

      g2.draw(path);
    }

    // ── 4. Draw Glowing Playhead Plane ──
    double playheadDepthNorm = (double) playheadCycleIdx / (numCycles - 1);
    double pZ = 1.0 - playheadDepthNorm;
    double pScale = 0.55 + 0.45 * pZ;

    // Calculate boundaries of the playhead plane cutting across the screen
    double leftX = centerX - 1.05 * (w * 0.35) * pScale;
    double rightX = centerX + 1.05 * (w * 0.35) * pScale;
    double planeY = centerY + (playheadDepthNorm - 0.5) * depthSpread;

    // Draw a semi-transparent orange neon plane cutting through the stack
    g2.setColor(new Color(0xff, 0x66, 0x00, 20)); // ultra-subtle fill
    g2.fillRect((int) leftX, (int) (planeY - 20), (int) (rightX - leftX), 40);

    g2.setColor(new Color(0xff, 0x66, 0x00, 160)); // glowing orange border line
    g2.setStroke(
        new BasicStroke(
            1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1.0f, new float[] {4, 4}, 0.0f));
    g2.drawLine((int) leftX, (int) planeY, (int) rightX, (int) planeY);

    // Draw label index text in orange next to the plane
    g2.setColor(new Color(0xff, 0x88, 0x00));
    g2.setFont(new Font("Monospaced", Font.BOLD, 9));
    g2.drawString(
        String.format("IDX: %.2f (CYC %d/%d)", waveIndex, playheadCycleIdx + 1, numCycles),
        10,
        h - 10);
  }

  private void drawPlaceholder(Graphics2D g2, int w, int h) {
    // Draw an ultra-sleek dark grid backdrop
    g2.setColor(new Color(0x1a, 0x1a, 0x22));
    g2.setStroke(new BasicStroke(0.5f));
    for (int x = 0; x < w; x += 20) {
      g2.drawLine(x, 0, x, h);
    }
    for (int y = 0; y < h; y += 20) {
      g2.drawLine(0, y, w, y);
    }

    g2.setColor(Color.GRAY);
    g2.setFont(new Font("SansSerif", Font.BOLD, 10));
    String msg = "📁 LOAD A WAVETABLE (.WAV) TO SCAN IN 3D";
    int msgW = g2.getFontMetrics().stringWidth(msg);
    g2.drawString(msg, (w - msgW) / 2, h / 2 - 5);

    g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
    g2.setColor(new Color(0x60, 0x60, 0x68));
    String sub = "Ensure Osc Type is set to WAVETABLE to preview morphing";
    int subW = g2.getFontMetrics().stringWidth(sub);
    g2.drawString(sub, (w - subW) / 2, h / 2 + 12);
  }
}
