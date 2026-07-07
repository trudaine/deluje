package org.deluge.ui;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;

/**
 * High-fidelity 1:1 photorealistic native faceplate upper deck for the Deluge workstation.
 * Preserves exact aspect ratio of the 2256x632 calibrated faceplate image, embeds the OLED screen
 * within the native aperture, and implements full interactive hit-testing and knob rotation for all
 * physical buttons, LED indicators, and rotary encoders.
 */
public class SwingHardwareTopPanel extends JPanel {
  private static final int ORIG_WIDTH = 2256;
  private static final int ORIG_TOP_HEIGHT = 574;

  public static class ControlDef {
    public final String name;
    public final int cx;
    public final int cy;
    public final int radius;
    public final boolean isEncoder;
    public final Color ledColor;

    public ControlDef(
        String name, int cx, int cy, int radius, boolean isEncoder, Color ledColor) {
      this.name = name;
      this.cx = cx;
      this.cy = cy;
      this.radius = radius;
      this.isEncoder = isEncoder;
      this.ledColor = ledColor;
    }
  }

  private final List<ControlDef> controls = new ArrayList<>();
  private final Map<String, Double> encoderAngles = new HashMap<>();
  private BufferedImage faceplateImg;
  private final SwingOledPanel oledPanel;
  private final SwingTopBarPanel.TopBarListener listener;
  private final BridgeContract bridge;
  private final ProjectModel projectModel;

  private boolean isPlaying = false;
  private boolean isRecording = false;
  private boolean isShiftHeld = false;
  private static SwingHardwareTopPanel activeInstance;

  public static boolean isShiftActive() {
    return activeInstance != null && activeInstance.isShiftHeld;
  }

  public void setShiftHeld(boolean held) {
    if (this.isShiftHeld != held) {
      this.isShiftHeld = held;
      repaint();
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.refreshGrids();
      }
    }
  }

  private String activeView = "CLIP";
  private ControlDef hoveredControl = null;
  private ControlDef activeDragControl = null;
  private int lastDragY = 0;

  // Cached aspect-ratio draw bounding box
  private int drawX = 0;
  private int drawY = 0;
  private int drawW = ORIG_WIDTH;
  private int drawH = ORIG_TOP_HEIGHT;
  private double currentScale = 1.0;
  private int upperGoldRow = 0;
  private int lowerGoldRow = 0;
  private boolean isAffectEntire = false;
  private boolean isScaleMode = false;
  private boolean isTriplets = false;

  public SwingHardwareTopPanel(
      BridgeContract bridge,
      ProjectModel projectModel,
      SwingOledPanel oledPanel,
      SwingTopBarPanel.TopBarListener listener) {
    this.bridge = bridge;
    this.projectModel = projectModel;
    this.oledPanel = oledPanel;
    this.listener = listener;
    activeInstance = this;

    setLayout(null);
    setPreferredSize(new Dimension(1400, (int) Math.round(1400 * (ORIG_TOP_HEIGHT / (double) ORIG_WIDTH))));
    setMinimumSize(new Dimension(800, (int) Math.round(800 * (ORIG_TOP_HEIGHT / (double) ORIG_WIDTH))));
    setOpaque(true);
    setBackground(new Color(0x13, 0x13, 0x15));

    initControlsTable();
    loadFaceplateImage();

    if (oledPanel != null) {
      add(oledPanel);
    }

    addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            ControlDef hit = hitTestControl(e.getX(), e.getY());
            if (hit != null && hit.isEncoder) {
              activeDragControl = hit;
              lastDragY = e.getY();
            } else {
              handleMouseClick(e.getX(), e.getY());
            }
          }

          @Override
          public void mouseReleased(MouseEvent e) {
            activeDragControl = null;
          }
        });

    addMouseMotionListener(
        new MouseMotionAdapter() {
          @Override
          public void mouseMoved(MouseEvent e) {
            handleMouseHover(e.getX(), e.getY());
          }

          @Override
          public void mouseDragged(MouseEvent e) {
            if (activeDragControl != null && activeDragControl.isEncoder) {
              int deltaY = lastDragY - e.getY();
              if (Math.abs(deltaY) >= 3) {
                int steps = deltaY / 3;
                rotateEncoder(activeDragControl, steps);
                lastDragY = e.getY();
              }
            }
          }
        });

    addMouseWheelListener(this::handleMouseWheel);
  }

  private void initControlsTable() {
    Color amber = new Color(255, 135, 15);
    Color green = new Color(0, 220, 50);
    Color red = new Color(245, 35, 35);

    // Named function buttons
    controls.add(new ControlDef("PLAY", 2072, 332, 32, false, green));
    controls.add(new ControlDef("RECORD", 2072, 424, 32, false, red));
    controls.add(new ControlDef("SHIFT", 2072, 515, 32, false, amber));
    controls.add(new ControlDef("SESSION_VIEW", 865, 424, 28, false, amber));
    controls.add(new ControlDef("CLIP_VIEW", 865, 515, 28, false, amber));
    controls.add(new ControlDef("KEYBOARD", 1045, 516, 28, false, amber));
    controls.add(new ControlDef("SYNTH", 1187, 424, 28, false, amber));
    controls.add(new ControlDef("KIT", 1268, 424, 28, false, amber));
    controls.add(new ControlDef("MIDI", 1350, 424, 28, false, amber));
    controls.add(new ControlDef("CV", 1432, 424, 28, false, amber));
    controls.add(new ControlDef("SCALE_MODE", 1205, 515, 28, false, amber));
    controls.add(new ControlDef("AFFECT_ENTIRE", 1125, 515, 28, false, amber));
    controls.add(new ControlDef("CROSS_SCREEN", 1350, 515, 28, false, amber));
    controls.add(new ControlDef("TRIPLETS", 1440, 515, 28, false, amber));
    controls.add(new ControlDef("BACK", 1529, 241, 28, false, amber));
    controls.add(new ControlDef("LOAD", 1529, 332, 28, false, amber));
    controls.add(new ControlDef("SAVE", 1529, 424, 28, false, amber));
    controls.add(new ControlDef("LEARN", 1529, 515, 28, false, amber));
    controls.add(new ControlDef("TAP_TEMPO", 1812, 332, 28, false, amber));

    // Gold MOD assignment buttons (MOD0..MOD7)
    controls.add(new ControlDef("MOD0", 321, 332, 26, false, amber));
    controls.add(new ControlDef("MOD1", 412, 332, 26, false, amber));
    controls.add(new ControlDef("MOD2", 503, 332, 26, false, amber));
    controls.add(new ControlDef("MOD3", 593, 332, 26, false, amber));
    controls.add(new ControlDef("MOD4", 684, 332, 26, false, amber));
    controls.add(new ControlDef("MOD5", 775, 332, 26, false, amber));
    controls.add(new ControlDef("MOD6", 865, 332, 26, false, amber));
    controls.add(new ControlDef("MOD7", 956, 332, 26, false, amber));

    // Rotary encoders
    controls.add(new ControlDef("SELECT_ENC", 1067, 331, 65, true, null));
    controls.add(new ControlDef("TEMPO_ENC", 1811, 196, 65, true, null));
    controls.add(new ControlDef("MASTER_VOL", 2073, 195, 65, true, null));
    controls.add(new ControlDef("Y_ENC", 94, 469, 65, true, null));
    controls.add(new ControlDef("X_ENC", 321, 196, 65, true, null));
    controls.add(new ControlDef("MOD_ENCODER_0", 549, 469, 65, true, null));
    controls.add(new ControlDef("MOD_ENCODER_1", 775, 196, 65, true, null));

    for (ControlDef c : controls) {
      if (c.isEncoder) {
        encoderAngles.put(c.name, 0.0);
      }
    }
  }

  public void loadFaceplateImage() {
    try {
      String path = "/skin/Delugemu_Normal.png";
      java.io.InputStream in = getClass().getResourceAsStream(path);
      if (in != null) {
        BufferedImage full = ImageIO.read(in);
        faceplateImg =
            full.getSubimage(0, 0, ORIG_WIDTH, Math.min(ORIG_TOP_HEIGHT, full.getHeight()));
      }
    } catch (Exception ex) {
      System.err.println(
          "[SwingHardwareTopPanel] Could not load faceplate image: " + ex.getMessage());
    }
    repaint();
  }

  private void updateBoundingGeometry(int w, int h) {
    if (w <= 0 || h <= 0) return;
    double scaleX = (double) w / ORIG_WIDTH;
    double scaleY = (double) h / ORIG_TOP_HEIGHT;
    currentScale = Math.min(scaleX, scaleY);
    drawW = (int) Math.round(ORIG_WIDTH * currentScale);
    drawH = (int) Math.round(ORIG_TOP_HEIGHT * currentScale);
    drawX = (w - drawW) / 2;
    drawY = (h - drawH) / 2;
  }

  @Override
  public Dimension getPreferredSize() {
    int w = getWidth() > 0 ? getWidth() : 1400;
    int h = (int) Math.round(w * (ORIG_TOP_HEIGHT / (double) ORIG_WIDTH));
    return new Dimension(w, h);
  }

  @Override
  public void doLayout() {
    super.doLayout();
    updateBoundingGeometry(getWidth(), getHeight());
    if (oledPanel != null && currentScale > 0) {
      int ox = drawX + (int) Math.round(1171 * currentScale);
      int oy = drawY + (int) Math.round(268 * currentScale);
      int ow = Math.max(120, (int) Math.round(256 * currentScale));
      int oh = Math.max(36, (int) Math.round(96 * currentScale));
      oledPanel.setBounds(ox, oy, ow, oh);
    }
  }

  @Override
  protected void paintComponent(Graphics g) {
    super.paintComponent(g);
    Graphics2D g2 = (Graphics2D) g.create();
    g2.setRenderingHint(
        RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

    updateBoundingGeometry(getWidth(), getHeight());

    if (faceplateImg != null) {
      g2.drawImage(faceplateImg, drawX, drawY, drawW, drawH, null);

      // Cleanly replace old "delugemu" logo on the faceplate with "delujemu"
      int logoX = drawX + (int) Math.round(1035 * currentScale);
      int logoY = drawY + (int) Math.round(140 * currentScale);
      int logoW = (int) Math.round(385 * currentScale);
      int logoH = (int) Math.round(75 * currentScale);
      g2.setColor(Color.BLACK);
      g2.fillRect(logoX, logoY, logoW, logoH);

      g2.setFont(
          new Font("SansSerif", Font.BOLD, Math.max(12, (int) Math.round(52 * currentScale))));
      g2.setColor(Color.WHITE);
      g2.drawString(
          "delujemu",
          drawX + (int) Math.round(1046 * currentScale),
          drawY + (int) Math.round(195 * currentScale));
    } else {
      g2.setColor(new Color(0x20, 0x20, 0x24));
      g2.fillRect(drawX, drawY, drawW, drawH);
    }

    // Draw active button LEDs
    for (ControlDef c : controls) {
      if (!c.isEncoder && isControlActive(c) && c.ledColor != null) {
        drawIndicatorLed(g2, c.cx, c.cy, c.radius, c.ledColor);
      } else if (c.isEncoder) {
        drawEncoderDial(g2, c);
      }
    }

    drawModEncoderSquareLeds(g2);

    // Draw interactive hover feedback ring over buttons / knobs
    if (hoveredControl != null) {
      int screenX = drawX + (int) Math.round(hoveredControl.cx * currentScale);
      int screenY = drawY + (int) Math.round(hoveredControl.cy * currentScale);
      int screenR = (int) Math.round(hoveredControl.radius * currentScale);

      g2.setColor(new Color(0x00, 0xd2, 0xff, 95));
      g2.setStroke(new BasicStroke(2.2f));
      g2.drawOval(screenX - screenR, screenY - screenR, screenR * 2, screenR * 2);
    }

    g2.dispose();
  }

  private void drawModEncoderSquareLeds(Graphics2D g2) {
    int[] upperY = {251, 214, 177, 140}; // bottom -> top
    int[] lowerY = {523, 486, 449, 412}; // bottom -> top
    drawSquareLedColumn(g2, 671, upperY, upperGoldRow);
    drawSquareLedColumn(g2, 446, lowerY, lowerGoldRow);
  }

  private void drawSquareLedColumn(Graphics2D g2, int cx, int[] yTable, int activeRow) {
    int half = Math.max(4, (int) Math.round(9 * currentScale));
    int x = drawX + (int) Math.round(cx * currentScale);
    for (int i = 0; i < yTable.length; i++) {
      int y = drawY + (int) Math.round(yTable[i] * currentScale);
      if (i == activeRow) {
        g2.setColor(new Color(255, 140, 15, 80));
        g2.fillRect(x - half - 3, y - half - 3, (half + 3) * 2, (half + 3) * 2);
        g2.setColor(new Color(255, 150, 20, 245));
        g2.fillRect(x - half, y - half, half * 2, half * 2);
      } else {
        g2.setColor(new Color(60, 30, 5, 110));
        g2.fillRect(x - half, y - half, half * 2, half * 2);
      }
    }
  }

  private void drawEncoderDial(Graphics2D g2, ControlDef c) {
    double angle = encoderAngles.getOrDefault(c.name, 0.0);
    int screenX = drawX + (int) Math.round(c.cx * currentScale);
    int screenY = drawY + (int) Math.round(c.cy * currentScale);
    int screenR = (int) Math.round((c.radius - 12) * currentScale);

    // Subtle position dot rotating with the encoder
    int dotX = screenX + (int) Math.round(Math.cos(angle) * screenR);
    int dotY = screenY + (int) Math.round(Math.sin(angle) * screenR);
    int dotRadius = Math.max(3, (int) Math.round(4 * currentScale));

    g2.setColor(new Color(255, 200, 40, 230));
    g2.fillOval(dotX - dotRadius, dotY - dotRadius, dotRadius * 2, dotRadius * 2);
  }

  private boolean isLearnMode = false;

  private boolean isControlActive(ControlDef c) {
    if ("PLAY".equals(c.name)) return isPlaying;
    if ("RECORD".equals(c.name)) return isRecording;
    if ("SHIFT".equals(c.name)) return isShiftHeld;
    if ("CLIP_VIEW".equals(c.name)) return "CLIP".equals(activeView);
    if ("SESSION_VIEW".equals(c.name)) return "SONG".equals(activeView);
    if ("KEYBOARD".equals(c.name)) return "KEYPLAY".equals(activeView);
    if ("AFFECT_ENTIRE".equals(c.name)) return isAffectEntire;
    if ("SCALE_MODE".equals(c.name)) return isScaleMode;
    if ("TRIPLETS".equals(c.name)) return isTriplets;
    if ("CROSS_SCREEN".equals(c.name)) return org.deluge.ui.SwingGridPanel.isCrossScreenWrapActive;
    if ("LEARN".equals(c.name)) return isLearnMode;
    return false;
  }

  private void drawIndicatorLed(Graphics2D g2, int origX, int origY, int btnRadius, Color c) {
    int screenX = drawX + (int) Math.round(origX * currentScale);
    int screenY = drawY + (int) Math.round(origY * currentScale);
    int ledRadius = Math.max(5, (int) Math.round(14 * currentScale));
    int bRadius = Math.max(10, (int) Math.round(btnRadius * currentScale));

    g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 105));
    g2.fillOval(screenX - bRadius, screenY - bRadius, bRadius * 2, bRadius * 2);

    g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 245));
    g2.fillOval(screenX - ledRadius, screenY - ledRadius, ledRadius * 2, ledRadius * 2);

    g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 70));
    g2.fillOval(screenX - ledRadius * 2, screenY - ledRadius * 2, ledRadius * 4, ledRadius * 4);
  }

  private ControlDef hitTestControl(int mouseX, int mouseY) {
    if (currentScale <= 0) return null;
    double imgX = (mouseX - drawX) / currentScale;
    double imgY = (mouseY - drawY) / currentScale;

    for (ControlDef c : controls) {
      if (Math.hypot(imgX - c.cx, imgY - c.cy) <= c.radius) {
        return c;
      }
    }
    return null;
  }

  private void handleMouseHover(int mouseX, int mouseY) {
    ControlDef hit = hitTestControl(mouseX, mouseY);
    if (hit != hoveredControl) {
      hoveredControl = hit;
      if (hoveredControl != null) {
        setToolTipText(
            hoveredControl.name
                + (hoveredControl.isEncoder
                    ? " [Rotary Knob: Drag up/down or Scroll]"
                    : " [Button]"));
      } else {
        setToolTipText(null);
      }
      repaint();
    }
  }

  private void rotateEncoder(ControlDef enc, int delta) {
    double oldAngle = encoderAngles.getOrDefault(enc.name, 0.0);
    encoderAngles.put(enc.name, oldAngle + delta * 0.15);

    if ("TEMPO_ENC".equals(enc.name) && projectModel != null) {
      double bpm = projectModel.getBpm() + delta * 1.0;
      bpm = Math.max(40.0, Math.min(300.0, bpm));
      projectModel.setBpm((float) bpm);
      if (oledPanel != null) {
        oledPanel.showParamText("TEMPO", String.format("%.1f BPM", bpm));
      }
    } else if ("SELECT_ENC".equals(enc.name)) {
      if (oledPanel != null) {
        oledPanel.showParamText("SELECT", delta > 0 ? "NEXT PRESET" : "PREV PRESET");
      }
    } else if ("Y_ENC".equals(enc.name) || "UPPER_GOLD".equals(enc.name)) {
      if (oledPanel != null) {
        oledPanel.showParamText("CUTOFF", delta > 0 ? "+ LPF CUTOFF" : "- LPF CUTOFF");
      }
    } else if ("X_ENC".equals(enc.name) || "LOWER_GOLD".equals(enc.name)) {
      if (oledPanel != null) {
        oledPanel.showParamText("RESONANCE", delta > 0 ? "+ RES" : "- RES");
      }
    } else if ("MASTER_VOL".equals(enc.name)) {
      if (listener != null) {
        // Step master volume up or down
      }
      if (oledPanel != null) {
        oledPanel.showParamText("MASTER VOL", delta > 0 ? "VOLUME UP" : "VOLUME DOWN");
      }
    } else if ("MOD_ENCODER_1".equals(enc.name)) {
      upperGoldRow = (upperGoldRow + (delta > 0 ? 1 : 3)) % 4;
      String[] upperNames = {"LEVEL / PAN", "CUTOFF / RES", "ATTACK / REL", "CUSTOM"};
      if (oledPanel != null) {
        oledPanel.showParamText("UPPER MOD", upperNames[upperGoldRow]);
      }
    } else if ("MOD_ENCODER_0".equals(enc.name)) {
      lowerGoldRow = (lowerGoldRow + (delta > 0 ? 1 : 3)) % 4;
      String[] lowerNames = {"DELAY / SIDE", "RATE / STUTTER", "REVERB / DEPTH", "CUSTOM"};
      if (oledPanel != null) {
        oledPanel.showParamText("LOWER MOD", lowerNames[lowerGoldRow]);
      }
    }
    repaint();
  }

  private void handleMouseClick(int mouseX, int mouseY) {
    ControlDef hit = hitTestControl(mouseX, mouseY);
    if (hit == null || listener == null) return;

    switch (hit.name) {
      case "PLAY" -> {
        isPlaying = !isPlaying;
        listener.onPlayToggle();
      }
      case "RECORD" -> {
        isRecording = !isRecording;
        listener.onLiveRecordToggle(null);
      }
      case "SHIFT" -> setShiftHeld(!isShiftHeld);
      case "CLIP_VIEW" -> {
        activeView = "CLIP";
        listener.onViewModeChanged("CLIP");
      }
      case "SESSION_VIEW" -> {
        activeView = "SONG";
        listener.onViewModeChanged("SONG");
      }
      case "KEYBOARD" -> {
        activeView = "KEYPLAY";
        listener.onViewModeChanged("KEYPLAY");
      }
      case "SYNTH" -> listener.onAddTrack("SYNTH", isShiftHeld);
      case "KIT" -> listener.onAddTrack("KIT", isShiftHeld);
      case "MIDI" -> listener.onAddTrack("MIDI", isShiftHeld);
      case "CV" -> listener.onAddTrack("CV", isShiftHeld);
      case "LOAD" -> listener.onLoadProject();
      case "SAVE" -> listener.onSaveProject();
      case "BACK" -> listener.onUndo();
      case "AFFECT_ENTIRE" -> {
        isAffectEntire = !isAffectEntire;
        listener.onAffectEntireToggle();
        if (oledPanel != null) {
          oledPanel.showParamText("AFFECT ENTIRE", isAffectEntire ? "ALL CLIPS" : "SINGLE CLIP");
        }
      }
      case "SCALE_MODE" -> {
        isScaleMode = !isScaleMode;
        listener.onScaleModeToggle();
        if (oledPanel != null) {
          oledPanel.showParamText("SCALE MODE", isScaleMode ? "ACTIVE" : "CHROMATIC");
        }
      }
      case "TRIPLETS" -> {
        isTriplets = !isTriplets;
        listener.onTripletsToggle();
        if (oledPanel != null) {
          oledPanel.showParamText("TRIPLETS VIEW", isTriplets ? "ON (1/12T)" : "OFF (1/16)");
        }
      }
      case "CROSS_SCREEN" -> {
        org.deluge.ui.SwingGridPanel.isCrossScreenWrapActive =
            !org.deluge.ui.SwingGridPanel.isCrossScreenWrapActive;
        if (oledPanel != null) {
          oledPanel.showParamText(
              "CROSS SCREEN",
              org.deluge.ui.SwingGridPanel.isCrossScreenWrapActive ? "WRAP ON" : "WRAP OFF");
        }
      }
      case "LEARN" -> {
        isLearnMode = !isLearnMode;
        listener.onLearnToggle();
        if (oledPanel != null) {
          oledPanel.showParamText("MIDI LEARN", isLearnMode ? "LEARNING..." : "INACTIVE");
        }
      }
      case "TAP_TEMPO" -> {
        if (projectModel != null && oledPanel != null) {
          oledPanel.showParamText("TEMPO", String.format("%.1f BPM", projectModel.getBpm()));
        }
      }
      default -> {
        if (oledPanel != null) {
          oledPanel.showParamText(hit.name, "ACTIVE");
        }
      }
    }
    repaint();
  }

  private void handleMouseWheel(MouseWheelEvent e) {
    ControlDef hit = hitTestControl(e.getX(), e.getY());
    if (hit == null || !hit.isEncoder) return;

    int delta = -e.getWheelRotation();
    rotateEncoder(hit, delta);
  }

  public void setPlaybackState(boolean playing, boolean recording) {
    this.isPlaying = playing;
    this.isRecording = recording;
    repaint();
  }

  public void setActiveView(String view) {
    this.activeView = view;
    repaint();
  }
}
