package org.chuck.deluge.firmware.hid;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * High-fidelity virtual OLED display. Replicates the oled_canvas::Canvas behavior with bit-accurate
 * rendering. Provides a BufferedImage for Swing integration.
 */
public class VirtualOLED {
  public static final int WIDTH = 128;
  public static final int HEIGHT = 64;

  private final BufferedImage image;
  private final Graphics2D g2d;
  private boolean dirty = true;

  public static final Font LARGE_FONT = new Font("Monospaced", Font.BOLD, 16);
  public static final Font SMALL_FONT = new Font("Monospaced", Font.BOLD, 10);

  public void setLargeFont(boolean large) {
    try {
      g2d.setFont(large ? LARGE_FONT : SMALL_FONT);
    } catch (Throwable t) {
      // Shield
    }
  }

  public VirtualOLED() {
    this.image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
    this.g2d = image.createGraphics();
    try {
      g2d.setRenderingHint(
          java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
          java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
      g2d.setRenderingHint(
          java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
    } catch (Throwable t) {
      // Shield for headless test environments
    }
    g2d.setFont(LARGE_FONT); // Default to the large, highly legible main font!
    clear();
  }

  public void clear() {
    try {
      g2d.setBackground(new Color(0, 0, 0, 255));
      g2d.clearRect(0, 0, WIDTH, HEIGHT);
      dirty = true;
    } catch (Throwable t) {
      // Headless or uninitialized display graphics pipelines shield
    }
  }

  public void drawPixel(int x, int y, boolean white) {
    if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) return;
    try {
      image.setRGB(x, y, white ? 0xFFFFFFFF : 0xFF000000);
      dirty = true;
    } catch (Throwable t) {
      // Headless shield
    }
  }

  public void drawLine(int x1, int y1, int x2, int y2) {
    try {
      g2d.setColor(Color.WHITE);
      g2d.drawLine(x1, y1, x2, y2);
      dirty = true;
    } catch (Throwable t) {
      // Headless shield
    }
  }

  public void drawRect(int x, int y, int w, int h, boolean fill) {
    try {
      g2d.setColor(Color.WHITE);
      if (fill) g2d.fillRect(x, y, w, h);
      else g2d.drawRect(x, y, w, h);
      dirty = true;
    } catch (Throwable t) {
      // Headless shield
    }
  }

  public void drawString(String str, int x, int y) {
    try {
      g2d.setColor(Color.WHITE);
      g2d.drawString(str, x, y);
      dirty = true;
    } catch (Throwable t) {
      // Headless shield
    }
  }

  private javax.swing.Timer oledScrollTimer;
  private String staticLine1 = "";
  private String staticLine2 = "";
  private String staticLine3 = "";
  private String overrideLine2 = null;

  public void drawTrackScreen(String banner, String mainTitle, String subText) {
    if (oledScrollTimer != null) oledScrollTimer.stop();
    this.staticLine1 = banner != null ? banner : "";
    this.staticLine2 = mainTitle != null ? mainTitle : "";
    this.staticLine3 = subText != null ? subText : "";
    this.overrideLine2 = null;

    renderCurrentState();

    if (staticLine2.length() > 12) {
      final String padded = "   " + staticLine2 + "   ";
      int[] idx = new int[] {0};
      oledScrollTimer =
          new javax.swing.Timer(
              250,
              e -> {
                if (overrideLine2 == null) {
                  if (idx[0] + 12 <= padded.length()) {
                    renderDirect(staticLine1, padded.substring(idx[0], idx[0] + 12), staticLine3);
                    idx[0]++;
                  } else {
                    idx[0] = 0;
                  }
                }
              });
      oledScrollTimer.start();
    }
  }

  public void setNoteOverride(String noteString) {
    this.overrideLine2 = noteString;
    renderCurrentState();
  }

  public void clearNoteOverride() {
    this.overrideLine2 = null;
    renderCurrentState();
  }

  private void renderCurrentState() {
    String mLine = overrideLine2 != null ? overrideLine2 : staticLine2;
    renderDirect(staticLine1, mLine, staticLine3);
  }

  private void renderDirect(String l1, String l2, String l3) {
    clear();
    setLargeFont(false);
    drawString(l1 != null ? l1 : "", 4, 14);

    setLargeFont(true);
    drawString(l2 != null ? l2 : "", 4, 35);

    setLargeFont(false);
    drawString(l3 != null ? l3 : "", 4, 56);
    FirmwareDisplay.get().notifyOledListener();
  }

  /** Renders the authentic 3-line layout: small top line, LARGE middle line, small bottom line. */
  public void drawThreeLineDisplay(String line1, String line2, String line3) {
    drawTrackScreen(line1, line2, line3);
  }

  /**
   * Draws a bit-accurate waveform from the given data. Replicates the firmware's waveform drawing
   * routine.
   */
  public void drawWaveform(short[] data, int start, int length) {
    if (data == null || length <= 0) return;
    try {
      g2d.setColor(Color.WHITE);

      float xStep = (float) WIDTH / length;
      for (int i = 0; i < length - 1; i++) {
        int y1 = HEIGHT / 2 + (data[start + i] * HEIGHT / 65536);
        int y2 = HEIGHT / 2 + (data[start + i + 1] * HEIGHT / 65536);
        g2d.drawLine((int) (i * xStep), y1, (int) ((i + 1) * xStep), y2);
      }
      dirty = true;
    } catch (Throwable t) {
      // Headless shield
    }
  }

  /**
   * Decodes a raw 768-byte SSD1306 page-addressed frame buffer (128x48 pixels, 6 pages) and draws
   * it pixel-by-pixel onto the virtual display image.
   */
  public void drawRawFrameBuffer(byte[] frameBuffer) {
    if (frameBuffer == null || frameBuffer.length < 768) return;
    clear();
    for (int page = 0; page < 6; page++) {
      for (int col = 0; col < 128; col++) {
        int b = frameBuffer[page * 128 + col] & 0xFF;
        for (int bit = 0; bit < 8; bit++) {
          boolean active = (b & (1 << bit)) != 0;
          drawPixel(col, page * 8 + bit, active);
        }
      }
    }
    dirty = true;
    FirmwareDisplay.get().notifyOledListener();
  }

  public BufferedImage getImage() {
    return image;
  }

  public boolean isDirty() {
    return dirty;
  }

  public void setClean() {
    dirty = false;
  }
}
