package org.deluge.ui;

import java.awt.*;
import javax.swing.JButton;
import org.deluge.ui.controls.UiAnimator;

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
  private boolean isTail = false;
  private boolean isBlur = false;
  private float intensity = 0.8f; // Velocity 0.0 - 1.0
  private String noteText = "";
  private boolean isSelected = false;
  private boolean drawCenterCircle = true;
  private Color textColorOverride = null;

  private boolean isScaleRoot = false;
  private boolean isScaleNote = false;
  private boolean isBeatMarker = false;
  private org.deluge.project.PreferencesManager.GridColorTheme theme =
      org.deluge.project.PreferencesManager.GridColorTheme.HARDWARE;

  public boolean isScaleRoot() {
    return isScaleRoot;
  }

  public void setScaleRoot(boolean scaleRoot) {
    if (this.isScaleRoot != scaleRoot) {
      this.isScaleRoot = scaleRoot;
      repaint();
    }
  }

  public boolean isScaleNote() {
    return isScaleNote;
  }

  public void setScaleNote(boolean scaleNote) {
    if (this.isScaleNote != scaleNote) {
      this.isScaleNote = scaleNote;
      repaint();
    }
  }

  public boolean isBeatMarker() {
    return isBeatMarker;
  }

  public void setBeatMarker(boolean beatMarker) {
    if (this.isBeatMarker != beatMarker) {
      this.isBeatMarker = beatMarker;
      repaint();
    }
  }

  public org.deluge.project.PreferencesManager.GridColorTheme getTheme() {
    return theme;
  }

  public void setTheme(org.deluge.project.PreferencesManager.GridColorTheme theme) {
    if (this.theme != theme) {
      this.theme = theme;
      repaint();
    }
  }

  public boolean isDrawCenterCircle() {
    return drawCenterCircle;
  }

  public void setDrawCenterCircle(boolean drawCenterCircle) {
    if (this.drawCenterCircle != drawCenterCircle) {
      this.drawCenterCircle = drawCenterCircle;
      repaint();
    }
  }

  public Color getTextColorOverride() {
    return textColorOverride;
  }

  public void setTextColorOverride(Color textColorOverride) {
    this.textColorOverride = textColorOverride;
    repaint();
  }

  public boolean isSelected() {
    return isSelected;
  }

  public void setSelected(boolean selected) {
    if (this.isSelected != selected) {
      this.isSelected = selected;
      repaint();
    }
  }

  private boolean isRowGlow = false;

  public boolean isRowGlow() {
    return isRowGlow;
  }

  public void setRowGlow(boolean rowGlow) {
    if (this.isRowGlow != rowGlow) {
      this.isRowGlow = rowGlow;
      repaint();
    }
  }

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

  // Shared-clock blink for the playhead ring. Registration is leak-safe: the grid recreates pad
  // buttons on refresh, so we always unregister in removeNotify(), and the tick self-unregisters
  // if the pad stops being a playhead.
  private final UiAnimator.Tick tick = this::onTick;
  private boolean tickRegistered = false;

  private void onTick(long frame) {
    if (isPlayhead && isShowing()) {
      repaint();
    } else {
      setTickRegistered(false);
    }
  }

  private void setTickRegistered(boolean on) {
    if (on == tickRegistered) {
      return;
    }
    tickRegistered = on;
    if (on) {
      UiAnimator.get().add(tick);
    } else {
      UiAnimator.get().remove(tick);
    }
  }

  @Override
  public void removeNotify() {
    setTickRegistered(false);
    super.removeNotify();
  }

  public boolean isPlayhead() {
    return isPlayhead;
  }

  public void setPlayhead(boolean playhead) {
    if (this.isPlayhead != playhead) {
      this.isPlayhead = playhead;
      setTickRegistered(playhead);
      repaint();
    }
  }

  public boolean isTail() {
    return isTail;
  }

  public void setTail(boolean tail) {
    if (this.isTail != tail) {
      this.isTail = tail;
      repaint();
    }
  }

  public boolean isBlur() {
    return isBlur;
  }

  public void setBlur(boolean blur) {
    if (this.isBlur != blur) {
      this.isBlur = blur;
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
      if (!noteText.isEmpty()) {
        String clean = noteText.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
        if (!clean.isEmpty()) {
          setToolTipText(clean);
        }
      }
      repaint();
    }
  }

  @Override
  public void setText(String text) {
    super.setText(text);
    if (text != null && !text.isEmpty()) {
      String clean = text.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim();
      if (!clean.isEmpty()) {
        setToolTipText(clean);
      }
      if (!text.startsWith("<html>")) {
        setNoteText(text);
      }
    }
    repaint();
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
      if (isScaleRoot) {
        Color rootColor = baseColor != null ? baseColor : new Color(0x00, 0xd2, 0xff);
        g2.setColor(new Color(0x13, 0x14, 0x17));
        g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
        g2.setColor(new Color(rootColor.getRed(), rootColor.getGreen(), rootColor.getBlue(), 25));
        g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
        g2.setColor(new Color(rootColor.getRed(), rootColor.getGreen(), rootColor.getBlue(), 55));
        g2.setStroke(new BasicStroke(1.0f));
        g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
      } else if (theme == org.deluge.project.PreferencesManager.GridColorTheme.HARDWARE) {
        if (isBeatMarker) {
          g2.setColor(new Color(0x1a, 0x1a, 0x1e));
          g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
          g2.setColor(new Color(255, 255, 255, 12));
          g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
          g2.setColor(new Color(255, 255, 255, 25));
          g2.setStroke(new BasicStroke(1.0f));
          g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
        } else {
          g2.setColor(new Color(0x13, 0x13, 0x15));
          g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
          g2.setColor(new Color(0x22, 0x22, 0x26));
          g2.setStroke(new BasicStroke(1.0f));
          g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
        }
      } else {
        Color base = baseColor != null ? baseColor : SwingSynthConfigDialog.BG_CONTROL;
        if (base.equals(SwingSynthConfigDialog.BG_CONTROL)
            || base.equals(new Color(0x1d, 0x1d, 0x22))) {
          g2.setColor(new Color(0x1a, 0x1a, 0x1e));
          g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
          g2.setColor(new Color(0x2d, 0x2d, 0x35));
          g2.setStroke(new BasicStroke(1.0f));
          g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
        } else {
          g2.setColor(new Color(0x13, 0x13, 0x15));
          g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
          g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), 20));
          g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
          Color dimBorder = new Color(base.getRed(), base.getGreen(), base.getBlue(), 45);
          if (isBeatMarker) {
            dimBorder = new Color(base.getRed(), base.getGreen(), base.getBlue(), 90);
          }
          g2.setColor(dimBorder);
          g2.setStroke(new BasicStroke(1.0f));
          g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
        }
      }
    } else if (active || isTail || isBlur) {
      // 3. Active step / Tail / Blur: Full glowing silicone track color
      Color base = baseColor != null ? baseColor : Color.GREEN;
      Color ledColor;
      if (isTail) {
        ledColor = getTailColor(base);
      } else if (isBlur) {
        ledColor = getBlurColor(base);
      } else {
        ledColor = muted ? getDesaturatedColor(base) : base;
      }

      // Blend color with dynamic velocity/probability intensity
      Color finalColor = blendWithBlack(ledColor, intensity);
      g2.setColor(finalColor);
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);

      // Border for tail/blur is dimmer than note start!
      Color border =
          (isTail || isBlur)
              ? new Color(finalColor.getRed(), finalColor.getGreen(), finalColor.getBlue(), 120)
              : new Color(
                  Math.min(255, finalColor.getRed() + 20),
                  Math.min(255, finalColor.getGreen() + 20),
                  Math.min(255, finalColor.getBlue() + 20),
                  220);
      g2.setColor(border);
      g2.setStroke(new BasicStroke(isTail ? 1.0f : 1.5f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);

      // Symmetrical physical center hotspot (white silicone glowing core) - only for note starts,
      // not tails!
      boolean isUtilityCol =
          Boolean.TRUE.equals(getClientProperty("utility"))
              || (getClientProperty("col") instanceof Integer colIdx && colIdx >= 16);

      if (drawCenterCircle
          && !isTail
          && !isUtilityCol
          && noteText.isEmpty()
          && getText().isEmpty()) {
        g2.setColor(new Color(255, 255, 255, 160));
        int cw = Math.max(4, rw / 4);
        int ch = Math.max(4, rh / 4);
        g2.fillOval((w - cw) / 2, (h - ch) / 2, cw, ch);
      }
    }

    // 5. Playhead Highlight Ring (glowing neon-white), pulsing on the shared blink clock
    //    at the firmware cursor-flash rate (kFlashTime ~110ms).
    if (isPlayhead) {
      int alpha = UiAnimator.blinkOn(UiAnimator.FLASH_SLOW_MS) ? 235 : 120;
      g2.setColor(new Color(255, 255, 255, alpha));
      g2.setStroke(new BasicStroke(2.0f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
    }

    // 5.5 Multi-Cell Selection Glow Ring (neon-cyan)
    if (isSelected) {
      g2.setColor(new Color(0x00, 0xd2, 0xff, 220));
      g2.setStroke(new BasicStroke(2.5f));
      g2.drawRoundRect(xPad + 1, yPad + 1, rw - 2, rh - 2, arc, arc);

      g2.setColor(new Color(0x00, 0xd2, 0xff, 35));
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
    }

    // 5.6 Row Panning Glow (soft neon-cyan/mint)
    if (isRowGlow) {
      g2.setColor(new Color(0x00, 0xff, 0xcc, 40));
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
      g2.setColor(new Color(0x00, 0xff, 0xcc, 110));
      g2.setStroke(new BasicStroke(1.5f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
    }

    g2.dispose();
  }

  public static Color getTailColor(Color base) {
    int r = base.getRed();
    int g = base.getGreen();
    int b = base.getBlue();
    int avg = r + g + b;
    int nr = Math.min(255, Math.max(0, ((r * 21 + avg) * 120) >> 14));
    int ng = Math.min(255, Math.max(0, ((g * 21 + avg) * 120) >> 14));
    int nb = Math.min(255, Math.max(0, ((b * 21 + avg) * 120) >> 14));
    return new Color(nr, ng, nb);
  }

  public static Color getBlurColor(Color base) {
    // Faithful to RGB::forBlur (rgb.h:96): averageBrightness = r*5 + g*9 + b*9; each channel
    // becomes
    // (channel*5 + averageBrightness) >> 5. (The previous impl pre-divided avg by 32 and used a
    // 3:5 blend, which diverged from the firmware.)
    int r = base.getRed();
    int g = base.getGreen();
    int b = base.getBlue();
    int avg = (r * 5) + (g * 9) + (b * 9);
    int nr = Math.min(255, Math.max(0, (r * 5 + avg) >> 5));
    int ng = Math.min(255, Math.max(0, (g * 5 + avg) >> 5));
    int nb = Math.min(255, Math.max(0, (b * 5 + avg) >> 5));
    return new Color(nr, ng, nb);
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

  public static float adjustVelocityBrightness(float velocity) {
    int v_int = (int) (velocity * 127.0f);
    float brightness = (65.0f + v_int * 1.5f) / 255.0f;
    return Math.max(0.0f, Math.min(1.0f, brightness));
  }

  private Color blendWithBlack(Color base, float factor) {
    float adjustedFactor = adjustVelocityBrightness(factor);
    int r = (int) (base.getRed() * adjustedFactor + 0x22 * (1.0f - adjustedFactor));
    int g = (int) (base.getGreen() * adjustedFactor + 0x22 * (1.0f - adjustedFactor));
    int b = (int) (base.getBlue() * adjustedFactor + 0x22 * (1.0f - adjustedFactor));
    return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
  }
}
