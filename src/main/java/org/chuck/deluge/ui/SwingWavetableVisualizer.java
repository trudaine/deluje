package org.chuck.deluge.ui;

import java.awt.*;
import java.io.File;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.*;
import org.chuck.deluge.project.PreferencesManager;

/**
 * A luxury dark-neon visual Wavetable JComponent. Decodes full PCM float sample buffers, slices
 * them into equal single-cycle frames, and renders a dual-view oscilloscope panel: Left Panel:
 * Smooth neon-cyan active single-cycle wave curve profile. Right Panel: 3D perspective stacked
 * waterfall projection of consecutive surrounding cycles.
 */
public class SwingWavetableVisualizer extends JComponent {

  private float[] floatSamples = null;
  private int cycleSize = 2048; // Default standard wavetable cycle size
  private int activeIndexPct = 50; // Selected position index (0 to 100%)
  private File audioFile = null;
  private boolean loading = false;

  public SwingWavetableVisualizer() {
    setBackground(new Color(0x12, 0x12, 0x14));
    setPreferredSize(new Dimension(850, 320));
  }

  public void loadWavetable(String path, int cycleSize) {
    this.cycleSize = cycleSize;
    this.audioFile = resolveAudioFile(path);
    if (audioFile == null || !audioFile.exists()) {
      this.floatSamples = null;
      repaint();
      return;
    }

    this.loading = true;
    repaint();

    // project Loom background stream decoder
    new Thread(
            () -> {
              try {
                float[] decoded = decodeFullWavFile(audioFile);
                SwingUtilities.invokeLater(
                    () -> {
                      this.floatSamples = decoded;
                      this.loading = false;
                      repaint();
                    });
              } catch (Exception ex) {
                System.err.println(
                    "[WavetableVisualizer] Decoding thread error: " + ex.getMessage());
                SwingUtilities.invokeLater(
                    () -> {
                      this.loading = false;
                      repaint();
                    });
              }
            })
        .start();
  }

  public void setActiveIndexPct(int pct) {
    this.activeIndexPct = Math.max(0, Math.min(100, pct));
    repaint();
  }

  public void setCycleSize(int cycleSize) {
    this.cycleSize = cycleSize;
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Draw grid background
    g2.setColor(new Color(0x12, 0x12, 0x14));
    g2.fillRect(0, 0, w, h);

    // Subtle dark grid backing lines
    g2.setColor(new Color(0x1e, 0x1e, 0x24));
    g2.setStroke(new BasicStroke(1.0f));
    int gridGap = 30;
    for (int x = 0; x < w; x += gridGap) g2.drawLine(x, 0, x, h);
    for (int y = 0; y < h; y += gridGap) g2.drawLine(0, y, w, y);

    // Center divider separating Left single-cycle and Right 3D waterfall views
    g2.setColor(new Color(0x2d, 0x2d, 0x32));
    g2.drawLine(w / 2, 0, w / 2, h);

    if (loading) {
      g2.setColor(Color.CYAN);
      g2.setFont(new Font("SansSerif", Font.BOLD, 14));
      g2.drawString("LOADING WAVETABLE STREAM...", w / 2 - 110, h / 2 + 5);
      g2.dispose();
      return;
    }

    if (floatSamples == null || floatSamples.length < cycleSize) {
      g2.setColor(Color.DARK_GRAY);
      g2.setFont(new Font("SansSerif", Font.BOLD, 12));
      g2.drawString(
          "NO WAVETABLE SAMPLES LOADED — BROWSE A WAVETABLE SAMPLE IN SYNTH OSCILLATORS",
          30,
          h / 2 + 5);
      g2.dispose();
      return;
    }

    int totalCycles = floatSamples.length / cycleSize;
    int activeCycleIdx = (int) ((activeIndexPct / 100.0) * (totalCycles - 1));
    activeCycleIdx = Math.max(0, Math.min(totalCycles - 1, activeCycleIdx));

    // ─── LEFT SIDE VIEW: Single-Cycle Waveform Profile ───
    drawSingleCycle(g2, 0, 0, w / 2, h, activeCycleIdx);

    // ─── RIGHT SIDE VIEW: 3D perspective waterfall stack ───
    draw3DWaterfall(g2, w / 2, 0, w / 2, h, activeCycleIdx, totalCycles);

    g2.dispose();
  }

  private void drawSingleCycle(Graphics2D g2, int x, int y, int w, int h, int cycleIdx) {
    int startPos = cycleIdx * cycleSize;
    int cy = y + h / 2;

    // Draw baseline
    g2.setColor(new Color(0x2d, 0x2d, 0x35));
    g2.drawLine(x + 10, cy, x + w - 10, cy);

    // Build the wave path
    int padding = 20;
    int waveW = w - padding * 2;
    Polygon poly = new Polygon();

    for (int i = 0; i < cycleSize; i++) {
      float sample = floatSamples[startPos + i];
      int px = x + padding + (int) (((double) i / (cycleSize - 1)) * waveW);
      int py = cy - (int) (sample * (h / 2.5f));
      poly.addPoint(px, py);
    }

    // Draw active cyan glow envelope
    g2.setColor(new Color(0x00, 0xff, 0xcc, 30));
    poly.addPoint(x + padding + waveW, cy);
    poly.addPoint(x + padding, cy);
    g2.fill(poly);

    // Draw wave stroke line
    g2.setColor(new Color(0x00, 0xff, 0xcc));
    g2.setStroke(new BasicStroke(2.5f));
    poly.npoints -= 2; // Remove extra closing points
    g2.drawPolyline(poly.xpoints, poly.ypoints, poly.npoints);

    // Text details overlay
    g2.setColor(Color.LIGHT_GRAY);
    g2.setFont(new Font("SansSerif", Font.BOLD, 10));
    g2.drawString("SINGLE-CYCLE WAVEFORM PROFILE", x + 15, y + 20);
    g2.setColor(Color.GRAY);
    g2.drawString(
        String.format(
            "Cycle Frame: %d / %d (Size: %d samples)",
            cycleIdx + 1, floatSamples.length / cycleSize, cycleSize),
        x + 15,
        y + 35);
  }

  private void draw3DWaterfall(
      Graphics2D g2, int x, int y, int w, int h, int activeCycleIdx, int totalCycles) {
    int numLines = 15; // Number of perspective stacked surrounding lines to draw
    int centerIdx = numLines / 2;

    g2.setColor(Color.LIGHT_GRAY);
    g2.setFont(new Font("SansSerif", Font.BOLD, 10));
    g2.drawString("3D WATERFALL PERSPECTIVE STACK", x + 15, y + 20);

    // Draw lines from back to front (bottom/back skew to top/front skew!)
    for (int l = 0; l < numLines; l++) {
      int relativeOffset = l - centerIdx;
      int targetCycle = activeCycleIdx + relativeOffset;
      if (targetCycle < 0 || targetCycle >= totalCycles) continue;

      int startPos = targetCycle * cycleSize;

      // Skew coordinate shifts math (perspective offsets)
      int skewX = (l - centerIdx) * 12;
      int skewY = (l - centerIdx) * -15;

      int cy = y + h / 2 + skewY;
      int padding = 40;
      int waveW = w - padding * 2;

      Polygon poly = new Polygon();
      for (int i = 0; i < cycleSize; i++) {
        float sample = floatSamples[startPos + i];
        int px = x + padding + skewX + (int) (((double) i / (cycleSize - 1)) * waveW);
        int py = cy - (int) (sample * (h / 6.0f)); // Smaller amplitude for stacked look
        poly.addPoint(px, py);
      }

      // Dynamic color gradient: deep purple at back, glowing cyan in the middle, gold/amber at the
      // front!
      Color lineCol;
      float strokeWidth = 1.0f;
      if (l == centerIdx) {
        lineCol = new Color(0x00, 0xff, 0xcc); // Active cycle: cyan!
        strokeWidth = 2.5f;
      } else if (l < centerIdx) {
        // Back cycles: blue/purple HSL gradient!
        float ratio = (float) l / centerIdx;
        lineCol =
            new Color(
                (int) (0x50 * ratio),
                (int) (0x10 * ratio),
                0x7a + (int) (0x85 * ratio),
                60 + (int) (60 * ratio));
      } else {
        // Front cycles: warm orange/gold HSL gradient!
        float ratio = (float) (numLines - 1 - l) / centerIdx;
        lineCol =
            new Color(
                0xff - (int) (0x40 * ratio),
                0xb3 - (int) (0x80 * ratio),
                (int) (0x20 * ratio),
                120);
      }

      g2.setColor(lineCol);
      g2.setStroke(new BasicStroke(strokeWidth));
      g2.drawPolyline(poly.xpoints, poly.ypoints, poly.npoints);
    }
  }

  private float[] decodeFullWavFile(File file) {
    try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
      AudioFormat format = ais.getFormat();
      int bytesPerFrame = format.getFrameSize();
      if (bytesPerFrame <= 0) return null;

      byte[] buffer = new byte[16384];
      int read;
      java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
      while ((read = ais.read(buffer)) != -1) {
        baos.write(buffer, 0, read);
      }
      byte[] audioBytes = baos.toByteArray();
      int totalSamples = audioBytes.length / bytesPerFrame;

      float[] floatSamples = new float[totalSamples];
      boolean isBigEndian = format.isBigEndian();
      int bits = format.getSampleSizeInBits();

      for (int i = 0; i < totalSamples; i++) {
        int byteIndex = i * bytesPerFrame;
        float val = 0.0f;
        if (bits == 16) {
          int b1 = audioBytes[byteIndex];
          int b2 = audioBytes[byteIndex + 1];
          short sample =
              isBigEndian ? (short) ((b1 << 8) | (b2 & 0xff)) : (short) ((b2 << 8) | (b1 & 0xff));
          val = sample / 32768.0f;
        } else if (bits == 8) {
          int sample = audioBytes[byteIndex] & 0xff;
          val = (sample - 128) / 128.0f;
        }
        floatSamples[i] = val;
      }
      return floatSamples;
    } catch (Exception ex) {
      System.err.println("[Wavetable] Full PCM decode error: " + ex.getMessage());
      return null;
    }
  }

  private File resolveAudioFile(String path) {
    if (path == null || path.isBlank()) return null;
    File f = new File(path);
    if (f.exists()) return f;

    // Resolve relative to Preferences Mounted SD Card directories!
    File sdRoot = PreferencesManager.getLibraryDir();
    if (sdRoot != null && sdRoot.exists()) {
      File fSub = new File(sdRoot, path);
      if (fSub.exists()) return fSub;
      fSub = new File(new File(sdRoot, "SAMPLES"), path);
      if (fSub.exists()) return fSub;
    }
    return null;
  }
}
