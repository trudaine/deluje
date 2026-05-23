package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.JButton;

/**
 * A custom high-fidelity pad button emulating the physical Synthstrom Deluge backlit rubber
 * buttons. Renders radial gradient glowing LEDs, playhead highlighting, desaturated mutes, and
 * note-tie connectors.
 */
public class DelugePadButton extends JButton {
  private boolean active = false;
  private Color baseColor = new Color(0x33, 0x33, 0x33);
  private boolean muted = false;
  private boolean isPlayhead = false;
  private boolean isTied = false;
  private float intensity = 0.8f; // Velocity 0.0 - 1.0
  private String noteText = "";

  public DelugePadButton() {
    setContentAreaFilled(false);
    setBorderPainted(false);
    setFocusPainted(false);
    setOpaque(false);
    setMargin(new Insets(0, 0, 0, 0));
    setFont(new Font("Monospaced", Font.BOLD, 9));
  }

  @Override
  public void setBackground(Color c) {
    super.setBackground(c);
    if (c != null) {
      this.baseColor = c;
      if (!c.equals(new Color(0x33, 0x33, 0x33)) && !c.equals(new Color(0x1d, 0x1d, 0x22))) {
        this.active = true;
      } else {
        this.active = false;
      }
      repaint();
    }
  }

  public boolean isActive() {
    return active;
  }

  public void setActive(boolean active) {
    if (this.active != active) {
      this.active = active;
      repaint();
    }
  }

  public Color getBaseColor() {
    return baseColor;
  }

  public void setBaseColor(Color baseColor) {
    if (!this.baseColor.equals(baseColor)) {
      this.baseColor = baseColor;
      repaint();
    }
  }

  public boolean isMuted() {
    return muted;
  }

  public void setMuted(boolean muted) {
    if (this.muted != muted) {
      this.muted = muted;
      repaint();
    }
  }

  public boolean isPlayhead() {
    return isPlayhead;
  }

  public void setPlayhead(boolean playhead) {
    if (this.isPlayhead != playhead) {
      this.isPlayhead = playhead;
      repaint();
    }
  }

  public boolean isTied() {
    return isTied;
  }

  public void setTied(boolean tied) {
    if (this.isTied != tied) {
      this.isTied = tied;
      repaint();
    }
  }

  public float getIntensity() {
    return intensity;
  }

  public void setIntensity(float intensity) {
    this.intensity = Math.max(0.0f, Math.min(1.0f, intensity));
    repaint();
  }

  public String getNoteText() {
    return noteText;
  }

  public void setNoteText(String noteText) {
    if (noteText == null) noteText = "";
    if (!this.noteText.equals(noteText)) {
      this.noteText = noteText;
      repaint();
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    int w = getWidth();
    int h = getHeight();

    // Subtle padding inside cells for spacing
    int xPad = 2;
    int yPad = 2;
    int rw = w - 2 * xPad;
    int rh = h - 2 * yPad;
    int arc = 8; // rounded corner radius

    // 1. Draw Background (Inactive/Active/Muted)
    if (!active) {
      // Inactive dark pad
      g2.setColor(new Color(0x1d, 0x1d, 0x22));
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);

      // Subtle border for grid definition
      g2.setColor(new Color(0x32, 0x32, 0x3a));
      g2.setStroke(new BasicStroke(1));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
    } else {
      // Active backlit LED
      Color ledColor = baseColor;
      if (muted) {
        // Desaturate and dim if row is muted
        ledColor = getDesaturatedColor(baseColor);
      }

      // Blend color with intensity (velocity)
      Color finalColor = blendWithBlack(ledColor, intensity);
      Color centerColor = getBrightCenterColor(finalColor);

      // Radial gradient for premium glowing look
      float radius = Math.max(rw, rh) * 0.8f;
      float[] fractions = {0.0f, 0.4f, 1.0f};
      Color[] colors = {centerColor, finalColor, blendWithBlack(finalColor, 0.4f)};

      RadialGradientPaint gradient =
          new RadialGradientPaint(w / 2.0f, h / 2.0f, radius, fractions, colors);
      g2.setPaint(gradient);
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);

      // Accent border
      g2.setColor(new Color(finalColor.getRed(), finalColor.getGreen(), finalColor.getBlue(), 120));
      g2.setStroke(new BasicStroke(1.5f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
    }

    // 2. Draw Horizontal Tie-Drag Connector
    if (isTied) {
      g2.setColor(new Color(0xff, 0xff, 0xff, 80));
      g2.setStroke(new BasicStroke(3, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.drawLine(xPad, h / 2, w - xPad, h / 2);
    }

    // 3. Draw Playhead Highlight Ring (glowing white)
    if (isPlayhead) {
      g2.setColor(new Color(255, 255, 255, 200));
      g2.setStroke(new BasicStroke(2.5f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
    }

    // 4. Render minimal text overlays (with two-line split centering if a space is present)
    if (!noteText.isEmpty()) {
      g2.setFont(getFont());
      FontMetrics fm = g2.getFontMetrics();
      int fh = fm.getHeight();

      String[] parts = noteText.split(" ");
      if (parts.length == 2) {
        String part1 = parts[0];
        String part2 = parts[1];
        int w1 = fm.stringWidth(part1);
        int w2 = fm.stringWidth(part2);
        int maxW = Math.max(w1, w2);
        int totalH = fh * 2 - 4;

        if (active) {
          g2.setColor(new Color(0, 0, 0, 180));
          g2.fillRect((w - maxW) / 2 - 3, (h - totalH) / 2 - 2, maxW + 6, totalH + 4);
          g2.setColor(Color.WHITE);
        } else {
          g2.setColor(new Color(0x88, 0x88, 0x95));
        }

        g2.drawString(part1, (w - w1) / 2, h / 2 - 2);
        g2.drawString(part2, (w - w2) / 2, h / 2 + fh - 4);
      } else {
        int textW = fm.stringWidth(noteText);
        int textH = fm.getAscent();

        if (active) {
          g2.setColor(new Color(0, 0, 0, 180));
          g2.fillRect((w - textW) / 2 - 2, (h - textH) / 2 - 1, textW + 4, textH + 2);
          g2.setColor(Color.WHITE);
        } else {
          g2.setColor(new Color(0x88, 0x88, 0x95));
        }

        g2.drawString(noteText, (w - textW) / 2, (h + textH) / 2 - 2);
      }
    }

    g2.dispose();
  }

  private Color getBrightCenterColor(Color base) {
    // Add white hot-spot in the center of the LED
    int r = Math.min(255, base.getRed() + (255 - base.getRed()) / 2);
    int g = Math.min(255, base.getGreen() + (255 - base.getGreen()) / 2);
    int b = Math.min(255, base.getBlue() + (255 - base.getBlue()) / 2);
    return new Color(r, g, b);
  }

  private Color getDesaturatedColor(Color base) {
    // Convert to grayscale and dim to represent muted state
    int gray = (int) (base.getRed() * 0.299 + base.getGreen() * 0.587 + base.getBlue() * 0.114);
    int r = (gray + 0x33) / 2;
    int g = (gray + 0x33) / 2;
    int b = (gray + 0x33) / 2;
    return new Color(r, g, b);
  }

  private Color blendWithBlack(Color base, float factor) {
    if (factor >= 1.0f) return base;
    if (factor <= 0.0f) return new Color(0x33, 0x33, 0x33);
    int r = (int) (base.getRed() * factor + 0x22 * (1 - factor));
    int g = (int) (base.getGreen() * factor + 0x22 * (1 - factor));
    int b = (int) (base.getBlue() * factor + 0x22 * (1 - factor));
    return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
  }
}
