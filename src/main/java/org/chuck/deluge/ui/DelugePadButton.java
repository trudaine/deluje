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
  private Color baseColor = SwingSynthConfigDialog.BG_CONTROL;
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
      if (!c.equals(SwingSynthConfigDialog.BG_CONTROL) && !c.equals(new Color(0x1d, 0x1d, 0x22))) {
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

  private boolean inLoop = true;

  public boolean isInLoop() {
    return inLoop;
  }

  public void setInLoop(boolean inLoop) {
    if (this.inLoop != inLoop) {
      this.inLoop = inLoop;
      repaint();
    }
  }

  private boolean applicable = true;

  public boolean isApplicable() {
    return applicable;
  }

  public void setApplicable(boolean applicable) {
    if (this.applicable != applicable) {
      this.applicable = applicable;
      repaint();
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

    int w = getWidth();
    int h = getHeight();

    int xPad = 2;
    int yPad = 2;
    int rw = w - 2 * xPad;
    int rh = h - 2 * yPad;
    int arc = 6;

    if (!applicable) {
      // Color-neutral titanium grey pad
      g2.setColor(new Color(0x15, 0x15, 0x17));
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
      g2.setColor(new Color(0x2d, 0x2d, 0x32));
      g2.setStroke(new BasicStroke(1.0f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);

      if (noteText != null && !noteText.isEmpty()) {
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        int fh = fm.getHeight();
        int fa = fm.getAscent();
        g2.setColor(new Color(0x55, 0x55, 0x5a));

        String[] parts = noteText.split("\n");
        int partsCount = parts.length;
        int totalH = fh * partsCount - (fh - fa);

        for (int i = 0; i < partsCount; i++) {
          int wp = fm.stringWidth(parts[i]);
          int yPart = (h - totalH) / 2 + i * fh + fa - 1;
          g2.drawString(parts[i], (w - wp) / 2, yPart);
        }
      }
      g2.dispose();
      return;
    }

    if (!inLoop) {
      // 1. Out of loop: deep dark matte charcoal
      g2.setColor(new Color(0x10, 0x10, 0x12));
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
      g2.setColor(new Color(0x20, 0x20, 0x24));
      g2.setStroke(new BasicStroke(1.0f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
    } else if (!active) {
      // 2. Inactive inside loop: beautiful dimmed track signature color
      Color base = baseColor != null ? baseColor : SwingSynthConfigDialog.BG_CONTROL;
      if (base.equals(SwingSynthConfigDialog.BG_CONTROL)
          || base.equals(new Color(0x1d, 0x1d, 0x22))) {
        // Neutral gray pad
        g2.setColor(new Color(0x1a, 0x1a, 0x1e));
        g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
        g2.setColor(new Color(0x2d, 0x2d, 0x35));
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
      } else {
        // Dimmed colored pad (translucent flat signature)
        Color dimBg = new Color(base.getRed(), base.getGreen(), base.getBlue(), 35); // ~14% alpha
        g2.setColor(new Color(0x15, 0x15, 0x18)); // dark back base
        g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
        g2.setColor(dimBg);
        g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);

        // Colored frame
        Color dimBorder =
            new Color(base.getRed(), base.getGreen(), base.getBlue(), 70); // ~27% alpha
        g2.setColor(dimBorder);
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
      }
    } else {
      // 3. Active step: Full bright glowing solid track color
      Color base = baseColor != null ? baseColor : Color.GREEN;
      Color ledColor = muted ? getDesaturatedColor(base) : base;

      // Blend color with dynamic velocity/probability intensity
      Color finalColor = blendWithBlack(ledColor, intensity);
      g2.setColor(finalColor);
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);

      // Bright accent border
      Color brightBorder =
          new Color(
              Math.min(255, finalColor.getRed() + 20),
              Math.min(255, finalColor.getGreen() + 20),
              Math.min(255, finalColor.getBlue() + 20),
              220);
      g2.setColor(brightBorder);
      g2.setStroke(new BasicStroke(1.5f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);

      // Symmetrical physical center hotspot (white silicone glowing core)
      if (noteText.isEmpty() && getText().isEmpty()) {
        g2.setColor(new Color(255, 255, 255, 160));
        int cw = Math.max(4, rw / 4);
        int ch = Math.max(4, rh / 4);
        g2.fillOval((w - cw) / 2, (h - ch) / 2, cw, ch);
      }
    }

    // 4. Horizontal Tie-Drag Connector
    if (isTied) {
      g2.setColor(new Color(0xff, 0xff, 0xff, 120));
      g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
      g2.drawLine(xPad + 2, h / 2, w - xPad - 2, h / 2);
    }

    // 5. Playhead Highlight Ring (glowing neon-white)
    if (isPlayhead) {
      g2.setColor(new Color(255, 255, 255, 220));
      g2.setStroke(new BasicStroke(2.0f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
    }

    // 6. Minimal Text overlays
    if (!noteText.isEmpty()) {
      g2.setFont(getFont());
      FontMetrics fm = g2.getFontMetrics();
      int fh = fm.getHeight();
      int fa = fm.getAscent();
      String[] parts = noteText.split("\n");
      int partsCount = parts.length;

      int maxW = 0;
      for (String p : parts) {
        int wp = fm.stringWidth(p);
        if (wp > maxW) maxW = wp;
      }
      int totalH = fh * partsCount - (fh - fa);

      if (active) {
        g2.setColor(new Color(0, 0, 0, 160));
        g2.fillRect((w - maxW) / 2 - 4, (h - totalH) / 2 - 2, maxW + 8, totalH + 4);
        g2.setColor(Color.WHITE);
      } else {
        g2.setColor(new Color(0xcc, 0xcc, 0xdd));
      }

      for (int i = 0; i < partsCount; i++) {
        int wp = fm.stringWidth(parts[i]);
        int yPart = (h - totalH) / 2 + i * fh + fa - 1;
        g2.drawString(parts[i], (w - wp) / 2, yPart);
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
    if (factor <= 0.0f) return SwingSynthConfigDialog.BG_CONTROL;
    int r = (int) (base.getRed() * factor + 0x22 * (1 - factor));
    int g = (int) (base.getGreen() * factor + 0x22 * (1 - factor));
    int b = (int) (base.getBlue() * factor + 0x22 * (1 - factor));
    return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
  }
}
