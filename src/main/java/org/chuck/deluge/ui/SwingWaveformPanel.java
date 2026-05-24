package org.chuck.deluge.ui;

import java.awt.*;
import java.io.File;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.swing.*;
import org.chuck.deluge.project.PreferencesManager;

/**
 * A beautiful, real-time audio sample waveform visualizer component. Decodes WAV streams in
 * parallel background virtual threads and renders a glowing, symmetric HSL gradient wave shape.
 */
public class SwingWaveformPanel extends JPanel {

  private float[] wavePoints = null;
  private String metadataText = "No Sample Loaded";
  private boolean isLoading = false;
  private String currentPath = null;

  // Visual Crop and Loop Markers (frame position offsets)
  private int startPos = -1;
  private int endPos = -1;
  private int loopStartPos = -1;
  private int loopEndPos = -1;
  private int totalFrames = 0;
  private int activeSlicesCount = 0;

  public interface WaveformLoadListener {
    void onWaveformLoaded(int totalFrames);
  }

  private WaveformLoadListener loadListener = null;

  public void setLoadListener(WaveformLoadListener l) {
    this.loadListener = l;
  }

  public void setSlicesGrid(int count) {
    this.activeSlicesCount = count;
    repaint();
  }

  public SwingWaveformPanel(String initialPath) {
    setBackground(new Color(0x0c, 0x0c, 0x0e));
    setPreferredSize(new Dimension(380, 120));
    setMinimumSize(new Dimension(200, 100));
    setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
    setSamplePath(initialPath);
  }

  /** Starts a Project Loom parallel virtual thread to decode raw audio frames in the background. */
  public void setSamplePath(String path) {
    if (path == null || path.isBlank()) {
      this.wavePoints = null;
      this.metadataText = "No Sample Loaded";
      this.currentPath = null;
      repaint();
      return;
    }

    if (path.equals(currentPath)) {
      return; // Already loaded!
    }

    this.currentPath = path;
    this.isLoading = true;
    this.metadataText = "⚡ DECODING WAVEFORM...";
    repaint();

    // Spawn Project Loom parallel virtual thread to decode without locking EDT!
    Thread.startVirtualThread(
        () -> {
          float[] decoded = decodeWavFile(path);
          SwingUtilities.invokeLater(
              () -> {
                this.wavePoints = decoded;
                this.isLoading = false;
                if (decoded == null) {
                  this.metadataText = "⚠️ LOAD ERROR: Unsupported format or missing file";
                }
                if (loadListener != null && decoded != null) {
                  loadListener.onWaveformLoaded(totalFrames);
                }
                repaint();
              });
        });
  }

  private float[] decodeWavFile(String path) {
    File file = resolveAudioFile(path);
    if (file == null || !file.exists()) return null;

    try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
      AudioFormat format = ais.getFormat();
      int bytesPerFrame = format.getFrameSize();
      if (bytesPerFrame <= 0) return null;

      // Read raw PCM byte stream
      byte[] buffer = new byte[8192];
      int read;
      java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
      while ((read = ais.read(buffer)) != -1) {
        baos.write(buffer, 0, read);
      }
      byte[] audioBytes = baos.toByteArray();
      int totalSamples = audioBytes.length / bytesPerFrame;
      if (totalSamples <= 0) return null;
      this.totalFrames = totalSamples;

      int targetPoints = 500; // high-resolution wave outline points!
      float[] rawPoints = new float[targetPoints];
      int step = Math.max(1, totalSamples / targetPoints);

      boolean isBigEndian = format.isBigEndian();
      int sampleSizeInBits = format.getSampleSizeInBits();

      for (int i = 0; i < targetPoints; i++) {
        int sampleIndex = i * step;
        int byteIndex = sampleIndex * bytesPerFrame;
        if (byteIndex + bytesPerFrame > audioBytes.length) break;

        float val = 0.0f;
        if (sampleSizeInBits == 16) {
          int b1 = audioBytes[byteIndex];
          int b2 = audioBytes[byteIndex + 1];
          short sample;
          if (isBigEndian) {
            sample = (short) ((b1 << 8) | (b2 & 0xff));
          } else {
            sample = (short) ((b2 << 8) | (b1 & 0xff));
          }
          val = Math.abs(sample / 32768.0f);
        } else if (sampleSizeInBits == 8) {
          int sample = audioBytes[byteIndex] & 0xff;
          val = Math.abs((sample - 128) / 128.0f);
        }
        rawPoints[i] = val;
      }

      // Apply 3-point moving average smoothing filter to remove sharp digital spikes
      float[] smoothed = new float[targetPoints];
      for (int i = 0; i < targetPoints; i++) {
        float sum = 0;
        int count = 0;
        for (int w = -1; w <= 1; w++) {
          int idx = i + w;
          if (idx >= 0 && idx < targetPoints) {
            sum += rawPoints[idx];
            count++;
          }
        }
        smoothed[i] = count > 0 ? (sum / count) : 0.0f;
      }

      // Formulate detailed sample metadata string
      this.metadataText =
          String.format(
              "%s  |  %.1f kHz  |  %d-bit  |  %.2f sec",
              file.getName(),
              format.getSampleRate() / 1000.0f,
              sampleSizeInBits,
              (double) totalSamples / format.getSampleRate());

      return smoothed;
    } catch (Exception ex) {
      System.err.println("[WaveformPanel] Decode error: " + ex.getMessage());
      return null;
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Draw deep dark background slate box with rounded corners
    g2.setColor(new Color(0x0a, 0x0a, 0x0c));
    g2.fillRoundRect(0, 0, w, h, 8, 8);

    // Draw border outline
    g2.setColor(new Color(0x1d, 0x1d, 0x22));
    g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);

    // Draw grid background laboratory guidelines
    g2.setColor(new Color(0x13, 0x13, 0x17));
    int gridSpacing = 25;
    for (int x = gridSpacing; x < w; x += gridSpacing) {
      g2.drawLine(x, 0, x, h);
    }
    for (int y = gridSpacing; y < h; y += gridSpacing) {
      g2.drawLine(0, y, w, y);
    }

    // Draw center zero horizontal baseline
    g2.setColor(new Color(0x1d, 0x1d, 0x22));
    g2.drawLine(0, h / 2, w, h / 2);

    if (isLoading || wavePoints == null) {
      // Draw loading prompt
      g2.setColor(isLoading ? new Color(0xff, 0xaa, 0x00) : Color.DARK_GRAY);
      g2.setFont(new Font("SansSerif", Font.BOLD, 10));
      FontMetrics fm = g2.getFontMetrics();
      g2.drawString(metadataText, (w - fm.stringWidth(metadataText)) / 2, (h / 2) + 4);
      g2.dispose();
      return;
    }

    // Draw high-fidelity symmetric gradient waveform
    int barX = 15;
    int drawW = w - 30;
    int waveMaxHeight = (h - 28) / 2;

    for (int x = 0; x < drawW; x++) {
      int ptIdx = (int) (((double) x / drawW) * wavePoints.length);
      if (ptIdx >= wavePoints.length) break;

      float amplitude = wavePoints[ptIdx];
      int waveHeight = (int) (amplitude * waveMaxHeight);
      int topY = (h / 2) - waveHeight;
      int bottomY = (h / 2) + waveHeight;

      // HSL Gradient calculation: Teal (#00ffcc) center, morphing to Magenta (#ff007f) bounds
      double ratio = Math.abs(((double) x / drawW) - 0.5) * 2.0; // 0.0 center, 1.0 edges
      int r = (int) (0x00 * (1 - ratio) + 0xff * ratio);
      int gr = (int) (0xff * (1 - ratio) + 0x00 * ratio);
      int b = (int) (0xcc * (1 - ratio) + 0x7f * ratio);
      g2.setColor(new Color(r, gr, b));

      g2.drawLine(barX + x, topY, barX + x, bottomY);
    }

    // Draw vertical dotted colored lines for crop and loop boundaries!
    if (totalFrames > 0) {
      g2.setStroke(
          new BasicStroke(
              1.5f,
              BasicStroke.CAP_BUTT,
              BasicStroke.JOIN_MITER,
              1.0f,
              new float[] {3f, 3f},
              0.0f));

      if (startPos >= 0 && startPos <= totalFrames) {
        int sx = barX + (int) (((double) startPos / totalFrames) * drawW);
        g2.setColor(new Color(0x00, 0xff, 0x66));
        g2.drawLine(sx, 4, sx, h - 22);
        g2.setFont(new Font("SansSerif", Font.BOLD, 9));
        g2.drawString("S", sx + 3, 12);
      }
      if (endPos >= 0 && endPos <= totalFrames) {
        int ex = barX + (int) (((double) endPos / totalFrames) * drawW);
        g2.setColor(new Color(0xff, 0x33, 0x33));
        g2.drawLine(ex, 4, ex, h - 22);
        g2.setFont(new Font("SansSerif", Font.BOLD, 9));
        g2.drawString("E", ex - 10, 12);
      }
      if (loopStartPos >= 0 && loopStartPos <= totalFrames) {
        int lsx = barX + (int) (((double) loopStartPos / totalFrames) * drawW);
        g2.setColor(new Color(0x33, 0x99, 0xff));
        g2.drawLine(lsx, 4, lsx, h - 22);
        g2.setFont(new Font("SansSerif", Font.BOLD, 9));
        g2.drawString("LS", lsx + 3, 24);
      }
      if (loopEndPos >= 0 && loopEndPos <= totalFrames) {
        int lex = barX + (int) (((double) loopEndPos / totalFrames) * drawW);
        g2.setColor(new Color(0xff, 0x33, 0xcc));
        g2.drawLine(lex, 4, lex, h - 22);
        g2.setFont(new Font("SansSerif", Font.BOLD, 9));
        g2.drawString("LE", lex - 14, 24);
      }
      g2.setStroke(new BasicStroke(1.0f)); // Restore standard stroke
    }

    // Draw dynamic visual orange slice-grid dividers!
    if (activeSlicesCount > 0 && totalFrames > 0) {
      g2.setStroke(
          new BasicStroke(
              1.0f,
              BasicStroke.CAP_BUTT,
              BasicStroke.JOIN_MITER,
              1.0f,
              new float[] {2f, 4f},
              0.0f));
      g2.setColor(new Color(0xff, 0x88, 0x00, 0xdd)); // translucent neon orange!
      for (int s = 1; s < activeSlicesCount; s++) {
        int slicePos = s * (totalFrames / activeSlicesCount);
        int sx = barX + (int) (((double) slicePos / totalFrames) * drawW);
        g2.drawLine(sx, 4, sx, h - 22);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 8));
        g2.drawString(String.valueOf(s + 1), sx + 3, h - 24);
      }
      g2.setStroke(new BasicStroke(1.0f));
    }

    // Draw clean metadata text at the bottom-left edge
    g2.setColor(Color.GRAY);
    g2.setFont(new Font("SansSerif", Font.PLAIN, 9));
    g2.drawString(metadataText, 15, h - 8);

    g2.dispose();
  }

  /** Set active marker frame boundaries and trigger safe panel redrawing updates. */
  public void setMarkers(int start, int end, int loopStart, int loopEnd) {
    this.startPos = start;
    this.endPos = end;
    this.loopStartPos = loopStart;
    this.loopEndPos = loopEnd;
    repaint();
  }

  public int getTotalFrames() {
    return totalFrames;
  }

  /**
   * Helper to search and resolve relative paths in XML, double folders SAMPLES/SAMPLES/ prefixes,
   * or main resources sub-paths, ensuring wav files load stably.
   */
  private static File resolveAudioFile(String path) {
    if (path == null || path.isBlank()) return null;

    File f = new File(path);
    if (f.exists()) return f;

    // Fallback 1: Resolve against Preferences SAMPLES directory
    String samplesDir = PreferencesManager.getSamplesDir();
    if (samplesDir != null && !samplesDir.isBlank()) {
      File f1 = new File(samplesDir, path);
      if (f1.exists()) return f1;

      // Try strip leading "SAMPLES/" if duplicate
      if (path.toUpperCase().startsWith("SAMPLES/")) {
        String stripped = path.substring(8);
        File f1Stripped = new File(samplesDir, stripped);
        if (f1Stripped.exists()) return f1Stripped;
      }
    }

    // Fallback 2: Resolve against current directory resources sub-paths
    String[] resourcePaths = {
      "deluge/src/main/resources",
      "deluge/src/main/resources/SAMPLES",
      "deluge/src/main/resources/KITS",
      "src/main/resources",
      "src/main/resources/SAMPLES",
      "src/main/resources/KITS"
    };
    for (String rp : resourcePaths) {
      File f2 = new File(rp, path);
      if (f2.exists()) return f2;

      // Strip leading "SAMPLES/"
      if (path.toUpperCase().startsWith("SAMPLES/")) {
        File f2Stripped = new File(rp, path.substring(8));
        if (f2Stripped.exists()) return f2Stripped;
      }

      // Just raw filename check
      File f2Name = new File(rp, new File(path).getName());
      if (f2Name.exists()) return f2Name;
    }

    // Fallback 3: Check parent directories up to 3 levels
    File parent = new File(".").getAbsoluteFile();
    for (int depth = 0; depth < 4; depth++) {
      if (parent == null) break;
      File f3 = new File(parent, "deluge/src/main/resources/SAMPLES/" + new File(path).getName());
      if (f3.exists()) return f3;
      File f4 = new File(parent, "deluge/src/main/resources/KITS/" + new File(path).getName());
      if (f4.exists()) return f4;
      parent = parent.getParentFile();
    }

    return null; // File not found!
  }
}
