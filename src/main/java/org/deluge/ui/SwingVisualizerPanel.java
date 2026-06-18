package org.deluge.ui;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import org.chuck.core.ChuckVM;

/** Pure Swing real-time FFT oscilloscope, spectrum waterfall, and stereo phase vector scope. */
public class SwingVisualizerPanel extends JPanel {
  private final ChuckVM vm;
  private final org.deluge.BridgeContract bridge;
  private final Timer repaintTimer;

  // Waterfall scrolling history: stores magnitude arrays of size 32 (visual bars count)
  private final List<float[]> waterfallHistory = new ArrayList<>();
  private static final int WATERFALL_MAX_ROWS = 50;

  // Keep a simple peak-hold array for standard spectrum analysis
  private final float[] peakHold = new float[32];

  public SwingVisualizerPanel(ChuckVM vm, org.deluge.BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    setBackground(new Color(0x10, 0x10, 0x12));

    // Repaint at ~60 FPS!
    repaintTimer = new Timer(16, e -> repaint());
    repaintTimer.start();
  }

  @Override
  public void removeNotify() {
    super.removeNotify();
    if (repaintTimer != null) {
      repaintTimer.stop();
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g;
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();
    int quarterH = h / 4;

    // Fetch live time-domain sound buffers from either the live Pure Java driver or the core JNI
    // DAC channels
    float[] dataL = null;
    float[] dataR = null;
    if (SwingDelugeApp.pureModeActive) {
      dataL = org.deluge.engine.JavaAudioDriver.getLiveVisBufferL();
      dataR = org.deluge.engine.JavaAudioDriver.getLiveVisBufferR();
    } else {
      dataL =
          (vm != null && vm.getDacChannel(0) != null) ? vm.getDacChannel(0).getVisBuffer() : null;
      dataR =
          (vm != null && vm.getDacChannel(1) != null) ? vm.getDacChannel(1).getVisBuffer() : null;
    }

    // 1. Draw Oscilloscope (Row 1 - Left Channel)
    drawOscilloscope(g2, dataL, w, quarterH);

    // 2. Compute FFT and Draw Spectrum (Row 2)
    float[] magnitudes = computeFFT(dataL);
    float[] specBars = drawSpectrum(g2, magnitudes, w, quarterH, quarterH);

    // 3. Roll and Draw Waterfall (Row 3)
    drawWaterfall(g2, specBars, w, quarterH * 2, quarterH);

    // 4. Draw Stereo Phase Goniometer (Row 4 - Left vs Right XY Lissajous)
    drawStereoPhase(g2, dataL, dataR, w, quarterH * 3, quarterH);

    // 5. Draw Compressor Gain Reduction Meter Outline
    drawCompressorMeter(g2, w, h);
  }

  private void drawOscilloscope(Graphics2D g2, float[] data, int w, int height) {
    g2.setColor(new Color(0x18, 0x18, 0x1c));
    g2.fillRect(0, 0, w, height);

    // Draw subtle grid lines
    g2.setColor(new Color(0x2d, 0x2d, 0x32));
    g2.setStroke(new BasicStroke(1.0f));
    g2.drawLine(0, height / 2, w, height / 2);
    for (int x = 0; x < w; x += w / 10) {
      g2.drawLine(x, 0, x, height);
    }

    g2.setColor(new Color(0x00, 0xff, 0xcc));
    g2.setStroke(new BasicStroke(1.5f));

    if (data != null && data.length > 0) {
      int halfH = height / 2;
      double xStep = (double) w / data.length;
      Path2D.Float path = new Path2D.Float();
      path.moveTo(0, halfH - data[0] * halfH * 0.95f);

      for (int i = 1; i < data.length; i++) {
        float x = (float) (i * xStep);
        float y = halfH - data[i] * halfH * 0.95f;
        path.lineTo(x, y);
      }
      g2.draw(path);
    }

    g2.setFont(new Font("Monospaced", Font.BOLD, 10));
    g2.setColor(new Color(0x00, 0xff, 0xcc, 160));
    g2.drawString("LIVE TIME OSCILLOSCOPE (L)", 12, 16);
  }

  private float[] computeFFT(float[] data) {
    if (data == null || data.length < 1024) {
      return new float[512];
    }
    // Take a 1024 sample window size (power of two)
    float[] real = new float[1024];
    float[] imag = new float[1024];

    // Apply a standard Hamming window to reduce spectral leakage click sidebands
    for (int i = 0; i < 1024; i++) {
      double window = 0.54 - 0.46 * Math.cos(2.0 * Math.PI * i / 1023.0);
      real[i] = (float) (data[i] * window);
    }

    calculateRadix2FFT(real, imag);

    float[] magnitudes = new float[512];
    for (int i = 0; i < 512; i++) {
      magnitudes[i] = (float) Math.sqrt(real[i] * real[i] + imag[i] * imag[i]);
    }
    return magnitudes;
  }

  private float[] drawSpectrum(Graphics2D g2, float[] magnitudes, int w, int startY, int height) {
    g2.setColor(new Color(0x12, 0x12, 0x14));
    g2.fillRect(0, startY, w, height);

    int specY = startY + 10;
    int specH = height - 20;
    int numBars = 32;
    float[] bars = new float[numBars];

    // Logarithmic bin grouping: group 512 linear FFT buckets into 32 critical frequency bands
    for (int i = 0; i < numBars; i++) {
      double expStart = Math.pow(2.0, (double) i * 9.0 / numBars);
      double expEnd = Math.pow(2.0, (double) (i + 1) * 9.0 / numBars);
      int binStart = Math.max(0, Math.min(511, (int) expStart - 1));
      int binEnd = Math.max(binStart + 1, Math.min(512, (int) expEnd));

      float sum = 0.0f;
      for (int b = binStart; b < binEnd; b++) {
        sum += magnitudes[b];
      }
      float avg = sum / (binEnd - binStart);

      // Standard db logarithmic scaling amplification:
      float val = (float) (20.0 * Math.log10(Math.max(1e-4, avg)) + 40.0) / 40.0f;
      bars[i] = Math.max(0.0f, Math.min(1.0f, val));

      // Peak-hold micro-decay tracker
      peakHold[i] = Math.max(bars[i], peakHold[i] - 0.015f);
    }

    // Draw active logarithmic bars
    int barWidth = (w - 40) / numBars;
    for (int i = 0; i < numBars; i++) {
      int activeH = (int) (bars[i] * specH);
      int peakH = (int) (peakHold[i] * specH);
      int x = 20 + i * barWidth;

      // Draw glowing gradient spectrum bar
      g2.setPaint(
          new GradientPaint(
              x,
              specY + specH,
              new Color(0x00, 0xaa, 0xff),
              x,
              specY + specH - activeH,
              new Color(0x00, 0xff, 0xff)));
      g2.fillRect(x + 2, specY + specH - activeH, barWidth - 4, activeH);

      // Draw peak hold line dot
      g2.setColor(Color.WHITE);
      g2.drawLine(x + 2, specY + specH - peakH, x + barWidth - 2, specY + specH - peakH);
    }

    g2.setFont(new Font("Monospaced", Font.BOLD, 10));
    g2.setColor(new Color(0x00, 0xaa, 0xff, 160));
    g2.drawString("LOGARITHMIC 32-BAND FFT SPECTRUM", 12, startY + 16);
    return bars;
  }

  private void drawWaterfall(Graphics2D g2, float[] specBars, int w, int startY, int height) {
    g2.setColor(new Color(0x10, 0x10, 0x12));
    g2.fillRect(0, startY, w, height);

    // Roll historical water lines in memory queue
    if (specBars != null) {
      waterfallHistory.add(0, specBars.clone());
      if (waterfallHistory.size() > WATERFALL_MAX_ROWS) {
        waterfallHistory.remove(waterfallHistory.size() - 1);
      }
    }

    int waterY = startY + 10;
    int waterH = height - 20;
    int drawRowsCount = Math.min(waterH / 2, waterfallHistory.size());

    // Draw lines mapping bin intensity to a stunning color color scale
    for (int row = 0; row < drawRowsCount; row++) {
      float[] bins = waterfallHistory.get(row);
      int y = waterY + waterH - row * 2;
      double xStep = (double) (w - 40) / bins.length;

      for (int col = 0; col < bins.length; col++) {
        float val = bins[col];
        int x = 20 + (int) (col * xStep);
        int barW = (int) xStep + 1;

        // Dynamic spectral heat color gradient: Blue (low) -> Pink/Purple (mid) -> Yellow/Cyan
        // (high)
        int r = (int) (val * 255.0f);
        int g = (int) (Math.max(0.0f, val - 0.5f) * 510.0f);
        int b = (int) ((1.0f - val) * 255.0f + val * 128.0f);
        g2.setColor(new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b), 200));
        g2.fillRect(x, y - 2, barW, 2);
      }
    }

    g2.setFont(new Font("Monospaced", Font.BOLD, 10));
    g2.setColor(new Color(0xff, 0x00, 0x55, 160));
    g2.drawString("ROLLING SPECTRAL HEAT WATERFALL", 12, startY + 16);
  }

  private void drawStereoPhase(
      Graphics2D g2, float[] left, float[] right, int w, int startY, int height) {
    g2.setColor(new Color(0x14, 0x14, 0x16));
    g2.fillRect(0, startY, w, height);

    int centerX = w / 2;
    int centerY = startY + height / 2;
    int scale = height / 3;

    // Draw stereo polar coordinates reference guides
    g2.setColor(new Color(0x20, 0x20, 0x25));
    g2.setStroke(new BasicStroke(1.0f));
    g2.drawLine(centerX - scale, centerY, centerX + scale, centerY);
    g2.drawLine(centerX, centerY - scale, centerX, centerY + scale);

    // 45-degree angle indicators (L / R diagonals)
    g2.drawLine(centerX - scale, centerY - scale, centerX + scale, centerY + scale);
    g2.drawLine(centerX - scale, centerY + scale, centerX + scale, centerY - scale);

    g2.setColor(new Color(0xaa, 0xff, 0x00, 160));
    g2.setStroke(new BasicStroke(1.0f));

    if (left != null && right != null && left.length == right.length) {
      Path2D.Float path = new Path2D.Float();
      boolean first = true;
      int pointsCount = Math.min(512, left.length); // Plot 512 points for visual high fidelity

      for (int i = 0; i < pointsCount; i++) {
        float l = left[i];
        float r = right[i];

        // Polar coordinate rotation 45 degrees:
        float xVal = (l - r) * 0.7071f;
        float yVal = (l + r) * 0.7071f;

        float x = centerX + xVal * scale;
        float y = centerY - yVal * scale;

        if (first) {
          path.moveTo(x, y);
          first = false;
        } else {
          path.lineTo(x, y);
        }
      }
      g2.draw(path);
    }

    g2.setFont(new Font("Monospaced", Font.BOLD, 10));
    g2.setColor(new Color(0xaa, 0xff, 0x00, 160));
    g2.drawString("GONIO STEREO PHASE VECTOR SCOPE", 12, startY + 16);
  }

  private void drawCompressorMeter(Graphics2D g2, int w, int h) {
    if (bridge != null) {
      double trackVol = bridge.getTrackLevel(1);
      g2.setColor(Color.ORANGE);
      int barH = (int) (trackVol * (h - 40));
      g2.fillRect(w - 12, h - 20 - barH, 8, barH);
      g2.setColor(Color.DARK_GRAY);
      g2.drawRect(w - 12, 20, 8, h - 40);
    }
  }

  private static void calculateRadix2FFT(float[] real, float[] imag) {
    int n = real.length;
    if (n <= 1) return;

    int j = 0;
    for (int i = 0; i < n; i++) {
      if (i < j) {
        float tempR = real[i];
        float tempI = imag[i];
        real[i] = real[j];
        imag[i] = imag[j];
        real[j] = tempR;
        imag[j] = tempI;
      }
      int m = n >> 1;
      while (m >= 1 && j >= m) {
        j -= m;
        m >>= 1;
      }
      j += m;
    }

    for (int len = 2; len <= n; len <<= 1) {
      double angle = -2.0 * Math.PI / len;
      double wlenR = Math.cos(angle);
      double wlenI = Math.sin(angle);
      for (int i = 0; i < n; i += len) {
        double wR = 1.0;
        double wI = 0.0;
        int halfLen = len >> 1;
        for (int k = 0; k < halfLen; k++) {
          int uIdx = i + k;
          int vIdx = i + k + halfLen;
          float uR = real[uIdx];
          float uI = imag[uIdx];
          float tR = (float) (wR * real[vIdx] - wI * imag[vIdx]);
          float tI = (float) (wR * imag[vIdx] + wI * real[vIdx]);
          real[uIdx] = uR + tR;
          imag[uIdx] = uI + tI;
          real[vIdx] = uR - tR;
          imag[vIdx] = uI - tI;

          double nextWR = wR * wlenR - wI * wlenI;
          wI = wR * wlenI + wI * wlenR;
          wR = nextWR;
        }
      }
    }
  }
}
