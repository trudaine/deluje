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

  /** Callback interface for actions that need to reach the parent frame. */
  public interface TopBarListener {
    void onLiveRecordToggle(JButton btn);

    void onResampleToggle(JButton btn);

    void onArrangerCaptureToggle(boolean active);

    void onViewModeChanged(String viewMode);

    void onAddTrack(String type, boolean isShift);

    void onPlayToggle();

    void onStop();

    void onMasterVolumeChanged(float vol);

    default void onLoadProject() {}

    default void onSaveProject() {}

    default void onNewProject() {}

    default void onUndo() {}

    default void onRedo() {}

    default void onLearnToggle() {}

    default void onAffectEntireToggle() {}

    default void onScaleModeToggle() {}

    default void onTripletsToggle() {}

    default void onTapTempo() {}
  }

  public static class ControlDef {
    public final String name;
    public final int cx;
    public final int cy;
    public final int radius;
    public final boolean isEncoder;
    public final Color ledColor;

    public ControlDef(String name, int cx, int cy, int radius, boolean isEncoder, Color ledColor) {
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
  private final TopBarListener listener;
  private final BridgeContract bridge;
  private final ProjectModel projectModel;

  private boolean isPlaying = false;
  private boolean isRecording = false;
  private boolean isShiftHeld = false;
  private static SwingHardwareTopPanel activeInstance;

  public static boolean isShiftActive() {
    return activeInstance != null && activeInstance.isShiftHeld;
  }

  /** Refreshes the SYNTH/KIT/MIDI/CV sibling-selection LEDs after a shift-param changes. */
  public static void repaintActive() {
    if (activeInstance != null) {
      activeInstance.repaint();
    }
  }

  public SwingOledPanel getOledPanel() {
    return oledPanel;
  }

  public void setShiftHeld(boolean held) {
    if (this.isShiftHeld != held) {
      this.isShiftHeld = held;
      repaint();
      if (SwingDelugeApp.mainInstance != null) {
        // Grid panels have their own shiftHeld flag (SwingGridPanel.shiftHeld) that
        // ClipEditorController.attachListeners actually checks to decide whether a pad click
        // opens the shift-shortcut editor or plays a note — this panel's own isShiftHeld only
        // drives its LED and the shift-label overlay, so it must be propagated explicitly.
        SwingDelugeApp.mainInstance.setGridShiftHeld(held);
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
  private boolean isAffectEntire = true;
  // Matches SwingGridPanel.scaleModeEnabled's default of true (real Deluge hardware boots with
  // scale mode on).
  private boolean isScaleMode = true;
  private boolean isTriplets = false;

  public SwingHardwareTopPanel(
      BridgeContract bridge,
      ProjectModel projectModel,
      SwingOledPanel oledPanel,
      TopBarListener listener) {
    this.bridge = bridge;
    this.projectModel = projectModel;
    this.oledPanel = oledPanel;
    this.listener = listener;
    activeInstance = this;

    setLayout(null);
    setPreferredSize(
        new Dimension(1400, (int) Math.round(1400 * (ORIG_TOP_HEIGHT / (double) ORIG_WIDTH))));
    setMinimumSize(
        new Dimension(800, (int) Math.round(800 * (ORIG_TOP_HEIGHT / (double) ORIG_WIDTH))));
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
                // C: on real hardware, holding an encoder's own push-button and holding the
                // separate SHIFT button are independent inputs that often drive unrelated
                // features on the same encoder (e.g. playback_handler.cpp:2253-2316 —
                // TEMPO_ENC shift=swing, push=fine/coarse BPM toggle; timeline_view.cpp:130-222
                // — X_ENC push=zoom, shift=clip length; instrument_clip_view.cpp:6128-6197 —
                // Y_ENC push=octave/row transpose, shift=note colour). They must not be folded
                // into one flag. Mouse right-click/ctrl/alt simulate the encoder's own push
                // button (there's no physical push sensor on a mouse drag); isShiftHeld/
                // e.isShiftDown() simulate the separate hardware SHIFT button.
                boolean pushMod =
                    SwingUtilities.isRightMouseButton(e) || e.isControlDown() || e.isAltDown();
                boolean shiftMod = isShiftHeld || e.isShiftDown();
                rotateEncoder(activeDragControl, steps, pushMod, shiftMod);
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
    Color blue = new Color(0, 175, 255);

    // Named function buttons
    controls.add(new ControlDef("PLAY", 2072, 332, 32, false, green));
    controls.add(new ControlDef("RECORD", 2072, 424, 32, false, red));
    controls.add(new ControlDef("SHIFT", 2072, 515, 32, false, amber));
    controls.add(new ControlDef("SESSION_VIEW", 865, 424, 28, false, blue));
    controls.add(new ControlDef("CLIP_VIEW", 865, 515, 28, false, blue));
    controls.add(new ControlDef("KEYBOARD", 1045, 516, 28, false, amber));
    controls.add(new ControlDef("SYNTH", 1187, 424, 28, false, amber));
    controls.add(new ControlDef("KIT", 1268, 424, 28, false, amber));
    controls.add(new ControlDef("MIDI", 1350, 424, 28, false, amber));
    controls.add(new ControlDef("CV", 1432, 424, 28, false, amber));
    controls.add(new ControlDef("SCALE_MODE", 1205, 515, 28, false, amber));
    controls.add(new ControlDef("AFFECT_ENTIRE", 690, 466, 24, false, red));
    controls.add(new ControlDef("CROSS_SCREEN", 1350, 515, 28, false, amber));
    controls.add(new ControlDef("TRIPLETS", 1812, 515, 28, false, amber));
    controls.add(new ControlDef("SYNC_SCALING", 1812, 424, 28, false, blue));
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

    // Small printed arrow icons next to X_ENC (left/right, plain-turn equivalent = horizontal
    // scroll) and Y_ENC (up/down, plain-turn equivalent = vertical scroll). Desktop-only click
    // shortcuts for the same action already reachable via scrolling those encoders -- not a
    // separate physical control on real hardware, just printed artwork with no hit region before.
    controls.add(new ControlDef("X_ENC_LEFT", 401, 196, 16, false, null));
    controls.add(new ControlDef("X_ENC_RIGHT", 441, 196, 16, false, null));
    controls.add(new ControlDef("Y_ENC_UP", 174, 449, 16, false, null));
    controls.add(new ControlDef("Y_ENC_DOWN", 174, 489, 16, false, null));

    for (ControlDef c : controls) {
      if (c.isEncoder) {
        encoderAngles.put(c.name, 0.0);
      }
    }
  }

  public void loadFaceplateImage() {
    try {
      String path = "/skin/Delugemu_Normal.png";
      try (java.io.InputStream in = getClass().getResourceAsStream(path)) {
        if (in != null) {
          BufferedImage full = ImageIO.read(in);
          int subW = Math.min(ORIG_WIDTH, full.getWidth());
          int subH = Math.min(ORIG_TOP_HEIGHT, full.getHeight());
          faceplateImg = full.getSubimage(0, 0, subW, subH);
        }
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

  // Height is derived from width via the faceplate image's own aspect ratio, but capping the width
  // used for that derivation keeps the top panel from claiming an ever-larger share of a wide/
  // maximized window's height -- otherwise, on a 1920px+ wide window, it can balloon to ~490px+,
  // squeezing the grid below it. The faceplate image itself still stretches to the full actual
  // width; only the HEIGHT budget is capped, matching the panel's own reference width (see the
  // constructor's setPreferredSize(1400, ...) call a few lines up).
  private static final int MAX_HEIGHT_REFERENCE_WIDTH = 1400;

  @Override
  public Dimension getPreferredSize() {
    int w = getWidth() > 0 ? getWidth() : 1400;
    int heightRefW = Math.min(w, MAX_HEIGHT_REFERENCE_WIDTH);
    int h = (int) Math.round(heightRefW * (ORIG_TOP_HEIGHT / (double) ORIG_WIDTH));
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

      // Cleanly replace old logo on the faceplate with larger, bolder "delujemu"
      int logoX = drawX + (int) Math.round(1015 * currentScale);
      int logoY = drawY + (int) Math.round(128 * currentScale);
      int logoW = (int) Math.round(425 * currentScale);
      int logoH = (int) Math.round(95 * currentScale);
      g2.setColor(Color.BLACK);
      g2.fillRect(logoX, logoY, logoW, logoH);

      g2.setFont(
          new Font("SansSerif", Font.BOLD, Math.max(16, (int) Math.round(68 * currentScale))));
      g2.setColor(Color.WHITE);
      g2.drawString(
          "delujemu",
          drawX + (int) Math.round(1025 * currentScale),
          drawY + (int) Math.round(198 * currentScale));
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
    // Each gold knob has its own 4-square LED bargraph showing the CURRENT VALUE of whichever real
    // parameter modKnobMode currently maps that knob to -- NOT which of the 8 modes is selected
    // (the MOD0-7 buttons' own LEDs already show that, per button.h ZmodButtonX/modLed[8]).
    // Confirmed against real hardware: turning a knob fills the squares proportionally from the
    // bottom, with the boundary square partially/dimly lit for sub-square precision (e.g. 2 full +
    // a half-lit 3rd), not a discrete single-square jump.
    drawSquareLedColumn(g2, 671, upperY, currentModKnobFillLevel(0));
    drawSquareLedColumn(g2, 446, lowerY, currentModKnobFillLevel(1));
  }

  private void drawSquareLedColumn(Graphics2D g2, int cx, int[] yTable, float fillLevel) {
    int half = Math.max(4, (int) Math.round(9 * currentScale));
    int x = drawX + (int) Math.round(cx * currentScale);
    float scaledFill = fillLevel < 0f ? 0f : fillLevel * yTable.length;
    for (int i = 0; i < yTable.length; i++) {
      int y = drawY + (int) Math.round(yTable[i] * currentScale);
      float squareFill = Math.max(0f, Math.min(1f, scaledFill - i));
      if (squareFill <= 0f) {
        g2.setColor(new Color(60, 30, 5, 110));
        g2.fillRect(x - half, y - half, half * 2, half * 2);
      } else {
        int alpha = Math.round(110 + squareFill * (245 - 110));
        g2.setColor(new Color(255, 140, 15, 80));
        g2.fillRect(x - half - 3, y - half - 3, (half + 3) * 2, (half + 3) * 2);
        g2.setColor(new Color(255, 150, 20, alpha));
        g2.fillRect(x - half, y - half, half * 2, half * 2);
      }
    }
  }

  /**
   * Continuous 0..1 fill level for the knob-value bargraph, mirroring exactly which of the 16 real
   * parameters (C: sound.cpp:97-122) {@link #adjustModKnobParam} writes to for the current
   * modKnobMode. Returns -1 (renders as fully empty) if there's no current synth track, or the
   * mapped slot has no model+engine plumbing yet (mode 4 / knob 1: sidechain).
   */
  private float currentModKnobFillLevel(int knobIndex) {
    org.deluge.model.SynthTrackModel st = currentSynthTrack();
    if (st == null) {
      return -1f;
    }
    int mode = st.getModKnobMode();
    return switch (mode * 2 + knobIndex) {
      case 0 -> (st.getPan() + 1.0f) / 2.0f; // PAN -1..1
      case 1 -> st.getVolume(); // 0..1
      case 2 -> st.getLpfRes(); // 0..1
      case 3 -> { // LPF CUTOFF, log-scaled 20..20000 matching the knob's own log feel
        float freq = clamp(st.getLpfFreq(), 20.0f, 20000.0f);
        double t = (Math.log(freq) - Math.log(20.0)) / (Math.log(20000.0) - Math.log(20.0));
        yield (float) t;
      }
      case 4 -> clamp(st.getEnv(0).release() / 5.0f, 0f, 1f);
      case 5 -> clamp(st.getEnv(0).attack() / 5.0f, 0f, 1f);
      case 6 -> clamp(st.getDelayFeedbackQ31() / (float) Integer.MAX_VALUE, 0f, 1f);
      case 7 -> st.getDelaySend();
      case 8 -> st.getReverbSend();
      case 9 -> -1f; // sidechain -- not yet wired, see adjustModKnobParam
      case 10 -> st.getLfo(0).depth();
      case 11 -> {
        int cur = st.getRawKnobs().isLfoRateKnobSet(0) ? st.getRawKnobs().getLfoRateKnobQ31(0) : 0;
        yield clamp(cur / (float) Integer.MAX_VALUE, 0f, 1f);
      }
      case 12 -> {
        int cur = st.getPortamentoQ31() == Integer.MIN_VALUE ? 0 : st.getPortamentoQ31();
        yield clamp(cur / (float) Integer.MAX_VALUE, 0f, 1f);
      }
      case 13 -> st.getStutter().getStutterRate();
      case 14 -> st.getBitCrush();
      case 15 -> st.getSampleRateReduction();
      default -> -1f;
    };
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
  private boolean isSyncScaling = false;

  private javax.swing.Timer blinkTimer;

  private void startBlinkTimer() {
    if (blinkTimer == null) {
      blinkTimer =
          new javax.swing.Timer(
              250,
              e -> {
                if ("ARR".equals(activeView)) {
                  repaint();
                } else {
                  blinkTimer.stop();
                }
              });
    }
    if (!blinkTimer.isRunning()) {
      blinkTimer.start();
    }
  }

  private boolean isControlActive(ControlDef c) {
    if ("PLAY".equals(c.name)) return isPlaying;
    if ("RECORD".equals(c.name)) return isRecording;
    if ("SHIFT".equals(c.name)) return isShiftHeld;
    if ("CLIP_VIEW".equals(c.name)) return "CLIP".equals(activeView);
    if ("SESSION_VIEW".equals(c.name)) {
      if ("SONG".equals(activeView)) return true;
      if ("ARR".equals(activeView)) return (System.currentTimeMillis() % 700) < 350;
      return false;
    }
    if ("KEYBOARD".equals(c.name)) return "KEYPLAY".equals(activeView);
    if ("AFFECT_ENTIRE".equals(c.name)) return isAffectEntire;
    if ("SCALE_MODE".equals(c.name)) return isScaleMode;
    if ("TRIPLETS".equals(c.name)) return isTriplets;
    if ("CROSS_SCREEN".equals(c.name)) return org.deluge.ui.SwingGridPanel.isCrossScreenWrapActive;
    if ("LEARN".equals(c.name)) return isLearnMode;
    if ("SYNC_SCALING".equals(c.name)) return isSyncScaling;
    if ("SYNTH".equals(c.name)) return isSelectedSiblingSlot(0);
    if ("KIT".equals(c.name)) return isSelectedSiblingSlot(1);
    if ("MIDI".equals(c.name)) return isSelectedSiblingSlot(2);
    if ("CV".equals(c.name)) return isSelectedSiblingSlot(3);
    if (c.name.length() == 4 && c.name.startsWith("MOD") && Character.isDigit(c.name.charAt(3))) {
      org.deluge.model.SynthTrackModel st = currentSynthTrack();
      return st != null && st.getModKnobMode() == (c.name.charAt(3) - '0');
    }
    return false;
  }

  /**
   * The currently-edited track, if it's a synth (the only track type with the full 8-mode gold-knob
   * parameter table currently wired -- see modKnobMode on SynthTrackModel).
   */
  private org.deluge.model.SynthTrackModel currentSynthTrack() {
    SwingGridPanel gp =
        SwingDelugeApp.mainInstance != null ? SwingDelugeApp.mainInstance.activeGridPanel() : null;
    if (gp == null || gp.getProjectModel() == null) return null;
    int idx = gp.getEditedModelTrack();
    java.util.List<org.deluge.model.TrackModel> tracks = gp.getProjectModel().getTracks();
    if (idx < 0 || idx >= tracks.size()) return null;
    org.deluge.model.TrackModel t = tracks.get(idx);
    return (t instanceof org.deluge.model.SynthTrackModel st) ? st : null;
  }

  // C: horizontal_menu.cpp:546-571, updateSelectedMenuItemLED — lights the SYNTH/KIT/MIDI/CV LED
  // matching the currently selected item's column within its horizontal-menu sibling group.
  private boolean isSelectedSiblingSlot(int slot) {
    SwingGridPanel gp =
        SwingDelugeApp.mainInstance != null ? SwingDelugeApp.mainInstance.activeGridPanel() : null;
    if (gp == null || gp.getActiveShiftRow() < 0) return false;
    int[] target =
        DelugePadButton.getSiblingCoordinate(gp.getActiveShiftRow(), gp.getActiveShiftCol(), slot);
    return target != null
        && target[0] == gp.getActiveShiftRow()
        && target[1] == gp.getActiveShiftCol();
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
    rotateEncoder(enc, delta, false, isShiftHeld);
  }

  /**
   * @param pushMod encoder's own push-button held (simulated via right-click/ctrl/alt drag)
   * @param shiftMod separate hardware SHIFT button held — independent of pushMod, since real
   *     firmware frequently binds unrelated features to each on the same encoder
   */
  private void rotateEncoder(ControlDef enc, int delta, boolean pushMod, boolean shiftMod) {
    double oldAngle = encoderAngles.getOrDefault(enc.name, 0.0);
    encoderAngles.put(enc.name, oldAngle + delta * 0.15);

    SwingGridPanel gp =
        SwingDelugeApp.mainInstance != null ? SwingDelugeApp.mainInstance.activeGridPanel() : null;

    if ("TEMPO_ENC".equals(enc.name) && projectModel != null) {
      // C: playback_handler.cpp:2272-2280 — shiftButtonPressed edits swing amount, never BPM,
      // independent of whether TEMPO_ENC is also held. Verified directly:
      // commandEditTempoFine (2244-2251) moves BPM by a flat integer +/-1 per detent;
      // commandEditTempoCoarse (2222-2242) instead steps an internal tick-magnitude/digit
      // representation (getCurrentTempoParams/setTempoFromParams), which is NOT a flat BPM
      // delta — that finer algorithm isn't ported here, so coarse below is approximated as a
      // larger flat BPM step rather than 0.1 (which had fine and coarse backwards: real fine is
      // 1.0, not 0.1, and real coarse is bigger than fine, not smaller).
      if (shiftMod) {
        float swing = Math.max(0.0f, Math.min(1.0f, projectModel.getSwing() + delta * 0.02f));
        projectModel.setSwing(swing);
        if (oledPanel != null) {
          oledPanel.showParamText("SWING", String.format("%d%%", Math.round(swing * 100)));
        }
      } else {
        double step = pushMod ? 1.0 : 5.0;
        double bpm = projectModel.getBpm() + delta * step;
        bpm = Math.max(40.0, Math.min(300.0, bpm));
        projectModel.setBpm((float) bpm);
        if (oledPanel != null) {
          oledPanel.showParamText("TEMPO", String.format("%.1f BPM", bpm));
        }
      }
    } else if ("SELECT_ENC".equals(enc.name)) {
      // C: sound_editor.cpp:1030-1035 — 5x acceleration is triggered by SHIFT, not by pushing
      // the encoder itself.
      int step = shiftMod ? delta * 5 : delta;
      if (oledPanel != null) {
        oledPanel.showParamText(
            shiftMod ? "SELECT COARSE" : "SELECT",
            step > 0 ? ("+" + step + " PRESET") : (step + " PRESET"));
      }
    } else if ("Y_ENC".equals(enc.name)) {
      // C: instrument_clip_view.cpp:6148-6164, commandTransposeKey (6186-6204) — holding Y_ENC
      // ALONE transposes by octave (VerticalNudgeType::OCTAVE); holding Y_ENC *together with*
      // SHIFT transposes by a single row/semitone instead (VerticalNudgeType::ROW) — it is not
      // "push OR shift", both modifiers combine. SHIFT alone (Y_ENC not held) instead shifts the
      // track's note colour (commandShiftColour, line 6168-6170). Neither combination is a
      // "scroll by an octave" — that was fabricated in an earlier revision of this method.
      if (pushMod && shiftMod && gp != null) {
        gp.transposeTrack(-delta);
        if (oledPanel != null)
          oledPanel.showParamText("TRANSPOSE", "ROW " + (-delta > 0 ? "+" : "") + (-delta));
      } else if (pushMod && gp != null) {
        gp.transposeTrack(-delta * 12);
        if (oledPanel != null)
          oledPanel.showParamText("TRANSPOSE", "OCTAVE " + (-delta > 0 ? "+" : "") + (-delta));
      } else if (shiftMod && gp != null) {
        gp.adjustTrackColorOffset(delta);
        if (oledPanel != null) oledPanel.showParamText("TRACK COLOR", "ADJUSTED");
      } else if (gp != null) {
        gp.scrollVertically(-delta);
        if (oledPanel != null) {
          oledPanel.showParamText("Y SCROLL", -delta > 0 ? "SCROLL DOWN" : "SCROLL UP");
        }
      }
    } else if ("X_ENC".equals(enc.name)) {
      // C: timeline_view.cpp:130-222 — holding X_ENC (push) zooms; holding SHIFT instead edits
      // clip length (clip_view.cpp:145-176, simplified here to a step-count nudge rather than
      // the full quantized-to-zoom-level algorithm).
      if (gp != null) {
        if (pushMod) {
          gp.adjustZoomResolution(delta);
          if (oledPanel != null) oledPanel.showParamText("ZOOM", "RESOLUTION");
        } else if (shiftMod) {
          gp.adjustClipLength(delta);
          if (oledPanel != null)
            oledPanel.showParamText("CLIP LENGTH", delta > 0 ? "LONGER" : "SHORTER");
        } else {
          gp.scrollHorizontally(delta);
          if (oledPanel != null) {
            oledPanel.showParamText("X SCROLL", delta > 0 ? "SCROLL RIGHT" : "SCROLL LEFT");
          }
        }
      }
    } else if ("MASTER_VOL".equals(enc.name)) {
      if (oledPanel != null) {
        oledPanel.showParamText("MASTER VOL", delta > 0 ? "VOLUME UP" : "VOLUME DOWN");
      }
    } else if ("MOD_ENCODER_0".equals(enc.name)) {
      adjustModKnobParam(0, delta, pushMod);
    } else if ("MOD_ENCODER_1".equals(enc.name)) {
      adjustModKnobParam(1, delta, pushMod);
    }
    repaint();
  }

  /**
   * Turning a gold knob: adjusts whichever of the 16 real parameters (8 modes x 2 knobs, C:
   * sound.cpp:97-122) the current track's modKnobMode currently maps that knob to. knobIndex 0 =
   * MOD_ENCODER_0, 1 = MOD_ENCODER_1, matching modKnobs[mode][knobIndex] in the C source.
   */
  private void adjustModKnobParam(int knobIndex, int delta, boolean fine) {
    org.deluge.model.SynthTrackModel st = currentSynthTrack();
    if (st == null) {
      if (oledPanel != null) oledPanel.showParamText("MOD KNOB", "NO SYNTH TRACK");
      return;
    }
    int mode = st.getModKnobMode();
    float step = fine ? 0.01f : 0.04f;
    int q31Step = fine ? (Integer.MAX_VALUE / 400) : (Integer.MAX_VALUE / 50);
    String paramName;
    String valueText;

    switch (mode * 2 + knobIndex) {
      case 0 -> { // mode 0, knob 0: PAN
        float v = clamp(st.getPan() + delta * step, -1.0f, 1.0f);
        st.setPan(v);
        paramName = "PAN";
        valueText = String.format("%+.0f%%", v * 100);
      }
      case 1 -> { // mode 0, knob 1: VOLUME
        float v = clamp(st.getVolume() + delta * step, 0.0f, 1.0f);
        st.setVolume(v);
        paramName = "VOLUME";
        valueText = String.format("%.0f%%", v * 100);
      }
      case 2 -> { // mode 1, knob 0: LPF RESONANCE
        float v = clamp(st.getLpfRes() + delta * step, 0.0f, 1.0f);
        st.setLpfRes(v);
        paramName = "RESONANCE";
        valueText = String.format("%.0f%%", v * 100);
      }
      case 3 -> { // mode 1, knob 1: LPF CUTOFF (log-scaled, matches ear-linear knob feel)
        float mult = (float) Math.pow(fine ? 1.01 : 1.06, delta);
        float v = clamp(st.getLpfFreq() * mult, 20.0f, 20000.0f);
        st.setLpfFreq(v);
        paramName = "CUTOFF";
        valueText = String.format("%.0f Hz", v);
      }
      case 4 -> { // mode 2, knob 0: ENV RELEASE
        org.deluge.model.EnvelopeModel e = st.getEnv(0);
        float v = clamp(e.release() + delta * step, 0.0f, 5.0f);
        st.setEnv(
            0,
            new org.deluge.model.EnvelopeModel(
                e.attack(), e.decay(), e.sustain(), v, e.target(), e.amount()));
        paramName = "RELEASE";
        valueText = String.format("%.2f s", v);
      }
      case 5 -> { // mode 2, knob 1: ENV ATTACK
        org.deluge.model.EnvelopeModel e = st.getEnv(0);
        float v = clamp(e.attack() + delta * step, 0.0f, 5.0f);
        st.setEnv(
            0,
            new org.deluge.model.EnvelopeModel(
                v, e.decay(), e.sustain(), e.release(), e.target(), e.amount()));
        paramName = "ATTACK";
        valueText = String.format("%.2f s", v);
      }
      case 6 -> { // mode 3, knob 0: DELAY FEEDBACK
        long v = clampQ31((long) st.getDelayFeedbackQ31() + (long) delta * q31Step);
        st.setDelayFeedbackQ31((int) v);
        paramName = "DELAY FEEDBACK";
        valueText = String.format("%.0f%%", (v / (double) Integer.MAX_VALUE) * 100);
      }
      case 7 -> { // mode 3, knob 1: DELAY RATE (send level -- see FIRMWARE_PARITY note below)
        float v = clamp(st.getDelaySend() + delta * step, 0.0f, 1.0f);
        st.setDelaySend(v);
        paramName = "DELAY RATE";
        valueText = String.format("%.0f%%", v * 100);
      }
      case 8 -> { // mode 4, knob 0: REVERB AMOUNT
        float v = clamp(st.getReverbSend() + delta * step, 0.0f, 1.0f);
        st.setReverbSend(v);
        paramName = "REVERB";
        valueText = String.format("%.0f%%", v * 100);
      }
      case 9 -> { // mode 4, knob 1: SIDECHAIN/ducking depth -- NOT YET WIRED (no model+engine
        // plumbing for a synthesized SIDECHAIN->GLOBAL_VOLUME_POST_REVERB_SEND patch cable; see
        // docs/deluge_branch_audit_report.md follow-up notes)
        if (oledPanel != null) oledPanel.showParamText("SIDECHAIN", "NOT YET WIRED");
        return;
      }
      case 10 -> { // mode 5, knob 0: PITCH-LFO1 DEPTH
        org.deluge.model.LfoModel l = st.getLfo(0);
        float v = clamp(l.depth() + delta * step, 0.0f, 1.0f);
        st.setLfo(
            0,
            new org.deluge.model.LfoModel(
                l.rateHz(), l.waveform(), v, "PITCH", l.isLocal(), l.syncLevel(), l.syncType()));
        paramName = "PITCH DEPTH";
        valueText = String.format("%.0f%%", v * 100);
      }
      case 11 -> { // mode 5, knob 1: LFO1 RATE (raw knob, Q31)
        int cur = st.getRawKnobs().isLfoRateKnobSet(0) ? st.getRawKnobs().getLfoRateKnobQ31(0) : 0;
        long v = clampQ31((long) cur + (long) delta * q31Step);
        st.getRawKnobs().setLfoRateKnobQ31(0, (int) v);
        paramName = "LFO RATE";
        valueText = String.format("%.0f%%", (v / (double) Integer.MAX_VALUE) * 100);
      }
      case 12 -> { // mode 6, knob 0: PORTAMENTO
        int cur = st.getPortamentoQ31() == Integer.MIN_VALUE ? 0 : st.getPortamentoQ31();
        long v = clampQ31((long) cur + (long) delta * q31Step);
        st.setPortamentoQ31((int) v);
        paramName = "PORTAMENTO";
        valueText = String.format("%.0f%%", (v / (double) Integer.MAX_VALUE) * 100);
      }
      case 13 -> { // mode 6, knob 1: STUTTER RATE
        float v = clamp(st.getStutter().getStutterRate() + delta * step, 0.0f, 1.0f);
        st.getStutter().setStutterRate(v);
        paramName = "STUTTER RATE";
        valueText = String.format("%.0f%%", v * 100);
      }
      case 14 -> { // mode 7, knob 0: BITCRUSH
        float v = clamp(st.getBitCrush() + delta * step, 0.0f, 1.0f);
        st.setBitCrush(v);
        paramName = "BITCRUSH";
        valueText = String.format("%.0f%%", v * 100);
      }
      case 15 -> { // mode 7, knob 1: SAMPLE RATE REDUCTION
        float v = clamp(st.getSampleRateReduction() + delta * step, 0.0f, 1.0f);
        st.setSampleRateReduction(v);
        paramName = "SAMPLE RATE";
        valueText = String.format("%.0f%%", v * 100);
      }
      default -> {
        return;
      }
    }
    if (oledPanel != null) {
      oledPanel.showParamText(paramName, valueText);
    }
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.fireProjectChanged();
    }
  }

  private static float clamp(float v, float lo, float hi) {
    return Math.max(lo, Math.min(hi, v));
  }

  private static long clampQ31(long v) {
    return Math.max(Integer.MIN_VALUE + 1, Math.min(Integer.MAX_VALUE, v));
  }

  /**
   * C: horizontal_menu.cpp:70-107 — while a horizontal menu is open, SYNTH/KIT/MIDI/CV (select_map
   * = {SYNTH:0, KIT:1, MIDI:2, CV:3}) select between up to 4 sibling menu items instead of their
   * normal track-creation function. Modeled here for the subset of shift-grid parameters that
   * already have a real sibling group and a working editor (see DelugePadButton.SIBLING_GROUPS);
   * falls back to the normal add-track behavior when no shift-param is currently active or it has
   * no modeled sibling group.
   */
  private void selectSiblingOrAddTrack(String type, int slot) {
    SwingGridPanel gp =
        SwingDelugeApp.mainInstance != null ? SwingDelugeApp.mainInstance.activeGridPanel() : null;
    if (gp != null && gp.getActiveShiftRow() >= 0) {
      int[] target =
          DelugePadButton.getSiblingCoordinate(
              gp.getActiveShiftRow(), gp.getActiveShiftCol(), slot);
      if (target != null) {
        gp.handleShiftClick(target[0], target[1], new Point(0, 0), this);
        return;
      }
    }
    if (oledPanel != null) {
      oledPanel.showParamText(type, "NEW TRACK");
    }
    listener.onAddTrack(type, isShiftHeld);
  }

  // C: sound.cpp:97-122 (Sound::Sound() default modKnobs[mode][knob] table) -- each mode remaps
  // both physical gold knobs (MOD_ENCODER_0 = knob 0, MOD_ENCODER_1 = knob 1) together. Names are
  // "knob0 / knob1". Mode 4's knob1 (sidechain/ducking depth) has no model+engine plumbing yet in
  // this port (see SwingHardwareTopPanel's rotateEncoder MOD_ENCODER handling), so its turn is a
  // no-op with an OLED note rather than silently pretending to work.
  private static final String[] MOD_KNOB_MODE_NAMES = {
    "PAN / VOLUME",
    "RESONANCE / CUTOFF",
    "RELEASE / ATTACK",
    "DELAY FEEDBACK / RATE",
    "REVERB / SIDECHAIN",
    "PITCH DEPTH / LFO RATE",
    "PORTAMENTO / STUTTER",
    "BITCRUSH / SAMPLE RATE"
  };

  /** MOD0-7 button press: selects which of the 8 gold-knob parameter pairs is active. */
  private void selectModKnobMode(int mode) {
    org.deluge.model.SynthTrackModel st = currentSynthTrack();
    if (st == null) {
      if (oledPanel != null) oledPanel.showParamText("MOD" + mode, "NO SYNTH TRACK");
      return;
    }
    st.setModKnobMode(mode);
    if (oledPanel != null) {
      oledPanel.showParamText("MOD " + mode, MOD_KNOB_MODE_NAMES[mode]);
    }
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
        if ("SONG".equals(activeView)) {
          activeView = "ARR";
          listener.onViewModeChanged("ARR");
          if (oledPanel != null) oledPanel.showParamText("VIEW", "ARRANGER");
          startBlinkTimer();
        } else {
          activeView = "SONG";
          listener.onViewModeChanged("SONG");
          if (oledPanel != null) oledPanel.showParamText("VIEW", "SONG");
        }
      }
      case "KEYBOARD" -> {
        activeView = "KEYPLAY";
        listener.onViewModeChanged("KEYPLAY");
      }
      case "SYNTH" -> selectSiblingOrAddTrack("SYNTH", 0);
      case "KIT" -> selectSiblingOrAddTrack("KIT", 1);
      case "MIDI" -> selectSiblingOrAddTrack("MIDI", 2);
      case "CV" -> selectSiblingOrAddTrack("CV", 3);
      case "MOD0" -> selectModKnobMode(0);
      case "MOD1" -> selectModKnobMode(1);
      case "MOD2" -> selectModKnobMode(2);
      case "MOD3" -> selectModKnobMode(3);
      case "MOD4" -> selectModKnobMode(4);
      case "MOD5" -> selectModKnobMode(5);
      case "MOD6" -> selectModKnobMode(6);
      case "MOD7" -> selectModKnobMode(7);
      case "LOAD" -> listener.onLoadProject();
      case "SAVE" -> listener.onSaveProject();
      case "BACK" -> {
        // C: view.cpp:401-419 — on every hardware revision this firmware actually compiles for
        // (undoButtonX is never #defined anywhere in the tree, so the #ifndef branch is always
        // active), BACK performs Undo, and Shift+BACK performs Redo. A prior pass in this file
        // removed this binding on the mistaken belief (never checked against view.cpp) that BACK
        // was never bound to undo on real hardware — it was wrong; restoring it.
        if (isShiftHeld) {
          listener.onRedo();
        } else {
          listener.onUndo();
        }
      }
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
      case "SYNC_SCALING" -> {
        isSyncScaling = !isSyncScaling;
        if (oledPanel != null) {
          oledPanel.showParamText("SYNC-SCALING", isSyncScaling ? "SCALED" : "NORMAL");
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
        listener.onTapTempo();
        if (projectModel != null && oledPanel != null) {
          oledPanel.showParamText("TEMPO", String.format("%.1f BPM", projectModel.getBpm()));
        }
      }
      case "X_ENC_LEFT" -> {
        SwingGridPanel gpLeft =
            SwingDelugeApp.mainInstance != null
                ? SwingDelugeApp.mainInstance.activeGridPanel()
                : null;
        if (gpLeft != null) {
          gpLeft.scrollHorizontally(-1);
          if (oledPanel != null) oledPanel.showParamText("X SCROLL", "SCROLL LEFT");
        }
      }
      case "X_ENC_RIGHT" -> {
        SwingGridPanel gpRight =
            SwingDelugeApp.mainInstance != null
                ? SwingDelugeApp.mainInstance.activeGridPanel()
                : null;
        if (gpRight != null) {
          gpRight.scrollHorizontally(1);
          if (oledPanel != null) oledPanel.showParamText("X SCROLL", "SCROLL RIGHT");
        }
      }
      case "Y_ENC_UP" -> {
        SwingGridPanel gpUp =
            SwingDelugeApp.mainInstance != null
                ? SwingDelugeApp.mainInstance.activeGridPanel()
                : null;
        if (gpUp != null) {
          gpUp.scrollVertically(-1);
          if (oledPanel != null) oledPanel.showParamText("Y SCROLL", "SCROLL UP");
        }
      }
      case "Y_ENC_DOWN" -> {
        SwingGridPanel gpDown =
            SwingDelugeApp.mainInstance != null
                ? SwingDelugeApp.mainInstance.activeGridPanel()
                : null;
        if (gpDown != null) {
          gpDown.scrollVertically(1);
          if (oledPanel != null) oledPanel.showParamText("Y SCROLL", "SCROLL DOWN");
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
    boolean pushMod = e.isControlDown() || e.isAltDown();
    boolean shiftMod = isShiftHeld || e.isShiftDown();
    rotateEncoder(hit, delta, pushMod, shiftMod);
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
