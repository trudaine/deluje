package org.deluge.ui;

import java.awt.*;
import java.util.Map;
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
  private boolean hasCondition = false;
  private boolean hasProbability = false;
  private boolean isFillOnly = false;
  private org.deluge.project.PreferencesManager.GridColorTheme theme =
      org.deluge.project.PreferencesManager.GridColorTheme.HARDWARE;

  public boolean hasCondition() {
    return hasCondition;
  }

  public void setHasCondition(boolean hasCondition) {
    if (this.hasCondition != hasCondition) {
      this.hasCondition = hasCondition;
      repaint();
    }
  }

  public boolean hasProbability() {
    return hasProbability;
  }

  public void setHasProbability(boolean hasProbability) {
    if (this.hasProbability != hasProbability) {
      this.hasProbability = hasProbability;
      repaint();
    }
  }

  public boolean isFillOnly() {
    return isFillOnly;
  }

  public void setFillOnly(boolean fillOnly) {
    if (this.isFillOnly != fillOnly) {
      this.isFillOnly = fillOnly;
      repaint();
    }
  }

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

  private float hoverIntensity = 0.0f;
  private boolean mouseHovered = false;
  private boolean hoverTickRegistered = false;

  private final UiAnimator.Tick hoverTick =
      new UiAnimator.Tick() {
        @Override
        public void onTick(long frame) {
          boolean rep = false;
          if (mouseHovered) {
            if (hoverIntensity < 1.0f) {
              hoverIntensity = Math.min(1.0f, hoverIntensity + 0.15f);
              rep = true;
            }
          } else {
            if (hoverIntensity > 0.0f) {
              hoverIntensity = Math.max(0.0f, hoverIntensity - 0.15f);
              rep = true;
            }
          }
          if (rep) {
            repaint();
          } else {
            setHoverTickRegistered(false);
          }
        }
      };

  private void setHoverTickRegistered(boolean on) {
    if (on == hoverTickRegistered) return;
    hoverTickRegistered = on;
    if (on) {
      UiAnimator.get().add(hoverTick);
    } else {
      UiAnimator.get().remove(hoverTick);
    }
  }

  public DelugePadButton() {
    setContentAreaFilled(false);
    setBorderPainted(false);
    setFocusPainted(false);
    setOpaque(false);
    setMargin(new Insets(0, 0, 0, 0));
    setFont(new Font("Monospaced", Font.BOLD, 9));

    enableEvents(java.awt.AWTEvent.MOUSE_EVENT_MASK);
  }

  @Override
  protected void processMouseEvent(java.awt.event.MouseEvent e) {
    super.processMouseEvent(e);
    if (e.getID() == java.awt.event.MouseEvent.MOUSE_ENTERED) {
      mouseHovered = true;
      setHoverTickRegistered(true);
    } else if (e.getID() == java.awt.event.MouseEvent.MOUSE_EXITED) {
      mouseHovered = false;
      setHoverTickRegistered(true);
    }
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
    setHoverTickRegistered(false);
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
    boolean isSiliconeLedStyle =
        org.deluge.project.PreferencesManager.getPadRenderStyle()
            == org.deluge.project.PreferencesManager.PadRenderStyle.SILICONE_LED;
    int arc = isSiliconeLedStyle ? Math.max(8, Math.min(rw, rh) * 35 / 100) : 6;

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
          g2.setColor(new Color(0x1a, 0x1a, 0x20));
          g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
          g2.setColor(new Color(255, 255, 255, 38)); // Visible white beat marker tint
          g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
          g2.setColor(new Color(255, 255, 255, 60));
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
            g2.setColor(new Color(255, 255, 255, 36));
            g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
            dimBorder = new Color(base.getRed(), base.getGreen(), base.getBlue(), 95);
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

      // Only the explicit marker set at pad creation — the old "col >= 16" fallback
      // flat-rendered real step columns 16-23 in 24-step-wide grid modes.
      boolean isUtilityCol = Boolean.TRUE.equals(getClientProperty("utility"));

      if (isUtilityCol || (!drawCenterCircle && !isSiliconeLedStyle)) {
        g2.setColor(finalColor);
        g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
      } else {
        // Render beautiful glowing silicone RadialGradient!
        java.awt.geom.Point2D center = new java.awt.geom.Point2D.Float(w / 2.0f, h / 2.0f);
        float radius = Math.min(w, h) * 0.9f;
        float[] dist = {0.0f, 0.22f, 0.75f, 1.0f};
        Color[] colors = {
          Color.WHITE,
          getBrightCenterColor(finalColor),
          finalColor,
          blendWithBlack(finalColor, 0.5f)
        };
        java.awt.RadialGradientPaint radial =
            new java.awt.RadialGradientPaint(center, radius, dist, colors);
        g2.setPaint(radial);
        g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
      }

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
    }

    // 5. Playhead Highlight Ring (glowing neon-white), pulsing on the shared blink clock
    //    at the firmware cursor-flash rate (kFlashTime ~110ms).
    if (isPlayhead) {
      int alpha = UiAnimator.blinkOn(UiAnimator.FLASH_SLOW_MS) ? 235 : 120;
      if (org.deluge.ui.SwingGridPanel.isLiveRecordModeActive) {
        g2.setColor(new Color(255, 0, 0, alpha));
      } else {
        g2.setColor(new Color(255, 255, 255, alpha));
      }
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

    // 5.7 Mouse Hover Halo (soft glow that tracks the pointer)
    if (hoverIntensity > 0.0f) {
      Color hoverAccent =
          (active || isTail || isBlur) ? baseColor : ThemeManager.getPrimaryAccent();
      g2.setColor(
          new Color(
              hoverAccent.getRed(),
              hoverAccent.getGreen(),
              hoverAccent.getBlue(),
              (int) (hoverIntensity * 35)));
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);
      g2.setColor(
          new Color(
              hoverAccent.getRed(),
              hoverAccent.getGreen(),
              hoverAccent.getBlue(),
              (int) (hoverIntensity * 80)));
      g2.setStroke(new BasicStroke(1.0f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);
    }

    // 5.8 Hardware-Parity Conditional & Parameter Step Visualizers
    if (active && !muted) {
      if (hasCondition) {
        // Amber Conditional Corner Dot (upper right)
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillOval(xPad + rw - 10, yPad + 3, 7, 7);
        g2.setColor(new Color(0xff, 0xb7, 0x03)); // Vibrant amber
        g2.fillOval(xPad + rw - 9, yPad + 4, 5, 5);
      }
      if (hasProbability) {
        // Cyan Probability Corner Dot (upper left)
        g2.setColor(new Color(0, 0, 0, 170));
        g2.fillOval(xPad + 3, yPad + 3, 7, 7);
        g2.setColor(new Color(0x00, 0xd2, 0xff)); // Vibrant cyan
        g2.fillOval(xPad + 4, yPad + 4, 5, 5);
      }
      if (isFillOnly) {
        // Magenta Fill-Gate Bottom Pill Bar
        g2.setColor(new Color(0xff, 0x00, 0x7f, 220));
        g2.fillRoundRect(xPad + 3, yPad + rh - 5, rw - 6, 3, 2, 2);
      }
    }

    // 6. Text Overlay: When SHIFT is active, overlay authentic printed faceplate shortcut text.
    //    Otherwise, display noteText if present.
    boolean shiftActive = SwingHardwareTopPanel.isShiftActive();
    String textToDraw = null;
    Color textColor = Color.WHITE;

    Object rowProp = getClientProperty("row");
    Object colProp = getClientProperty("col");
    String headerText = null;
    if (shiftActive && rowProp instanceof Integer r && colProp instanceof Integer c && c < 16) {
      int tableRow = r % 8;
      textToDraw = getAuthenticDelugeShortcutText(tableRow, c);
      textColor = new Color(255, 215, 75); // Authentic Deluge gold faceplate lettering
      headerText = getSectionHeaderIfBoundary(tableRow, c);
    } else if (noteText != null && !noteText.isEmpty()) {
      textToDraw = noteText;
      textColor = new Color(240, 240, 248, 230);
    }

    if (headerText != null && !headerText.isEmpty()) {
      g2.setFont(new Font("SansSerif", Font.BOLD, 8));
      java.awt.FontMetrics fmH = g2.getFontMetrics();
      int hx = xPad + (rw - fmH.stringWidth(headerText)) / 2;
      int hy = yPad + 11;
      g2.setColor(new Color(0, 0, 0, 220));
      g2.drawString(headerText, hx + 1, hy + 1);
      g2.setColor(new Color(180, 225, 255)); // Crisp light cyan silk-screen header
      g2.drawString(headerText, hx, hy);
    }

    if (textToDraw != null && !textToDraw.isEmpty()) {
      int maxFont = Math.max(7, Math.min(10, rw / Math.max(3, (textToDraw.length() + 1) / 2)));
      g2.setFont(new Font("SansSerif", Font.BOLD, maxFont));
      java.awt.FontMetrics fm = g2.getFontMetrics();
      int tx = xPad + (rw - fm.stringWidth(textToDraw)) / 2;
      int ty = yPad + rh - 4;
      g2.setColor(new Color(0, 0, 0, 200));
      g2.drawString(textToDraw, tx + 1, ty + 1);
      g2.setColor(textColor);
      g2.drawString(textToDraw, tx, ty);
    }

    g2.dispose();
  }

  // Authentic printed faceplate shortcut lettering, indexed [row 0-7][col 0-15]. This delegates to
  // SwingGridPanel.SHIFT_LABELS, the pre-existing table cross-checked against the firmware's
  // paramShortcutsForSounds[16][8] (menus.cpp:1753-1771). A prior revision of this method carried
  // its own, independently-authored SHORTCUT_TABLE that silently shadowed SHIFT_LABELS here (while
  // ClipEditorController.handleShiftHover/getShiftShortcutTooltip kept reading SHIFT_LABELS
  // directly) — nearly every cell mismatched the real coordinate, and because the two tables
  // disagreed, the OLED hover preview/tooltip and the actual click-to-edit behavior showed two
  // different parameters for the same pad. Delegating here keeps click/hover/tooltip on one source
  // of truth.
  public static String getAuthenticDelugeShortcutText(int row, int col) {
    if (row < 0 || row > 7 || col < 0 || col > 15) return "";
    String v = SwingGridPanel.SHIFT_LABELS[row][col];
    return v == null ? "" : v;
  }

  // Real firmware column-group boundaries (menus.cpp:1753-1771), converted from the C table's
  // y=0..7 (bottom-to-top) ordering to this grid's row=0..7 (top-to-bottom / OLED-to-user)
  // ordering via row = 7 - y. Several columns are physically split: the C array packs two
  // unrelated parameter groups into one column (e.g. col 8 is ENV1 release/sustain/decay/attack
  // at C y=0-3, then LPF morph/mode/res/freq at C y=4-7), so the printed silk-screen group name
  // changes partway down the column, not just once at the top.
  private static String sectionName(int row, int col) {
    return switch (col) {
      case 0 -> "SAMPLE 1";
      case 1 -> "SAMPLE 2";
      case 2 -> "OSC 1";
      case 3 -> "OSC 2";
      case 4 -> "FM MOD 1";
      case 5 -> "FM MOD 2";
      case 6 -> "MASTER";
      case 7 -> "VOICE";
      case 8 -> (row <= 3) ? "LPF" : "ENV 1";
      case 9 -> (row <= 3) ? "HPF" : "ENV 2";
      case 10 -> (row <= 1) ? "EQ" : "SIDECHAIN";
      case 11 -> (row <= 1) ? "EQ" : "ARP";
      case 12 -> (row <= 4) ? "MOD FX" : "LFO 1";
      case 13 -> (row <= 4) ? "REVERB" : "LFO 2";
      case 14 -> (row <= 2) ? "" : "DELAY";
      default -> "";
    };
  }

  /**
   * Returns the section header text only on the row where it first applies (row 0, or a change).
   */
  private static String getSectionHeaderIfBoundary(int row, int col) {
    String here = sectionName(row, col);
    if (here.isEmpty()) return "";
    if (row == 0 || !here.equals(sectionName(row - 1, col))) return here;
    return "";
  }

  public static String getColumnGroupName(int row, int col) {
    return sectionName(row, col);
  }

  // Real firmware horizontal-menu sibling groups (gui/menu_item/generate/g_menus.inc for
  // lpfMenu/hpfMenu/env1Menu/env2Menu; gui/ui/menus.cpp:583-596/499-508 for reverbMenu/delayMenu
  // page 0), restricted to the subset of each group's items that already have live shift-grid
  // coordinates + working editors in this port. On real hardware, SYNTH/KIT/MIDI/CV select
  // between up to 4 sibling menu items of whichever menu is currently open
  // (horizontal_menu.cpp:97, select_map = {SYNTH:0, KIT:1, MIDI:2, CV:3}); each array below is
  // that same 4-slot order for one such group, as {row, col} pairs.
  private static final Map<String, int[][]> SIBLING_GROUPS =
      Map.of(
          "LPF", new int[][] {{5, 8}, {7, 8}, {6, 8}, {4, 8}}, // MODE, FREQ(CUTOFF), RES, MORPH
          "HPF", new int[][] {{5, 9}, {7, 9}, {6, 9}, {4, 9}},
          "ENV1", new int[][] {{3, 8}, {2, 8}, {1, 8}, {0, 8}}, // ATTACK, DECAY, SUSTAIN, RELEASE
          "ENV2", new int[][] {{3, 9}, {2, 9}, {1, 9}, {0, 9}},
          "REVERB",
              new int[][] {{3, 13}, {7, 13}, {6, 13}, {5, 13}}, // AMOUNT, ROOM SIZE, DAMPING, WIDTH
          "DELAY",
              new int[][] {{3, 14}, {4, 14}, {1, 14}, {0, 14}}); // FEEDBACK, PINGPONG, SYNC, RATE

  private static String siblingGroupOf(int row, int col) {
    for (Map.Entry<String, int[][]> e : SIBLING_GROUPS.entrySet()) {
      for (int[] coord : e.getValue()) {
        if (coord[0] == row && coord[1] == col) return e.getKey();
      }
    }
    return null;
  }

  /**
   * Given the currently-active shift-param coordinate and a slot (0=SYNTH, 1=KIT, 2=MIDI, 3=CV),
   * returns the sibling coordinate to switch to, or null if the current param has no modeled
   * sibling group.
   */
  public static int[] getSiblingCoordinate(int activeRow, int activeCol, int slot) {
    String group = siblingGroupOf(activeRow, activeCol);
    if (group == null) return null;
    return SIBLING_GROUPS.get(group)[slot];
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
