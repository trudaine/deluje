package org.chuck.deluge.ui;

import java.awt.*;
import java.util.logging.Logger;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.Consequence;
import org.chuck.deluge.model.SongSection;

/** Unified 18x8 Grid Panel handling both sequence matrix and clip launch arrangements. */
public class SwingGridPanel extends JPanel {
  private static final Logger LOG = Logger.getLogger(SwingGridPanel.class.getName());
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private org.chuck.deluge.model.ProjectModel projectModel;
  private java.util.function.BiConsumer<Integer, Integer> onEditRequest;
  private static final int MAX_GRID_ROWS = 64;
  private static final int MAX_GRID_COLS = 26; // max columns: 24 steps + MUTE + SOLO
  private JButton[][] pads = new JButton[MAX_GRID_ROWS][MAX_GRID_COLS];
  private org.rtmidijava.RtMidiOut finalMidiOut;
  private double[] vuLevels = new double[MAX_GRID_ROWS];
  private Timer activeStutterTimer;
  private boolean[] isOneShotTrack = new boolean[MAX_GRID_ROWS];
  private int activeClipId = 0;
  private int baseTrackId = 0;
  private int editedModelTrack = 0; // model track index currently being edited in CLIP mode
  private int soloRow = -1; // -1 = no solo
  private Timer playheadTimer; // single timer for playhead updates, avoids leaks
  private int scrollOffset = 0; // vertical scroll offset for voice rows in CLIP mode
  private int scrollOffsetX = 0; // horizontal scroll offset for step columns in CLIP mode
  private int voiceRowCount = 8; // total number of voice rows for current track
  private org.chuck.deluge.project.PreferencesManager.GridMode gridMode =
      org.chuck.deluge.project.PreferencesManager.getGridMode();
  private int stepCount = 16; // steps per row, derived from gridMode
  int columnCount = 18; // stepCount + 2 (MUTE + SOLO), derived from gridMode

  public enum GridViewMode {
    CLIP,
    SONG,
    ARRANGEMENT,
    AUTOMATION
  }

  private GridViewMode viewMode = GridViewMode.SONG;

  private String selectedAutomationParam = org.chuck.deluge.model.AutomationParam.SYTH_PARAMS[0];
  private javax.swing.JComboBox<String> automationParamCombo;
  private boolean automationDragging = false;

  private boolean shiftHeld = false;
  private String activeShiftParam = null;
  private int activeShiftRow = -1;
  private int activeShiftCol = -1;

  private int clonePreviewStartRow = -1;
  private int clonePreviewStartCol = -1;
  private int clonePreviewCurrentRow = -1;
  private int clonePreviewCurrentCol = -1;

  // Multi-cell step selection state variables
  private final java.util.Set<String> selectedCells = new java.util.HashSet<>();
  private int dragSelStartRow = -1;
  private int dragSelStartCol = -1;
  private int dragSelCurrRow = -1;
  private int dragSelCurrCol = -1;
  private boolean isDragSelecting = false;

  public int getActiveShiftRow() {
    return activeShiftRow;
  }

  public int getActiveShiftCol() {
    return activeShiftCol;
  }

  public String getActiveShiftParam() {
    return activeShiftParam;
  }

  public void setShiftHeld(boolean held) {
    if (this.shiftHeld != held) {
      this.shiftHeld = held;
      if (!held) {
        activeShiftParam = null;
        activeShiftRow = -1;
        activeShiftCol = -1;
        if (SwingDelugeApp.mainInstance != null) {
          SwingDelugeApp.mainInstance.updateHardwareLedDisplay(null, null);
        }
      }
      repaint();
      refresh();
    }
  }

  public boolean isShiftHeld() {
    return shiftHeld;
  }

  // ── Shift hardware colors layout ──
  private static final Color COLOR_PEACH = new Color(229, 115, 115); // Vibrant soft coral
  private static final Color COLOR_YELLOW = new Color(229, 229, 0); // Vibrant solid yellow
  private static final Color COLOR_SLATE = new Color(79, 154, 221); // Slate steel blue
  private static final Color COLOR_DEEP_BLUE = new Color(100, 181, 246); // Sky blue
  private static final Color COLOR_BEIGE = new Color(255, 204, 128); // Warm orange peach
  private static final Color COLOR_ORANGE = new Color(255, 167, 38); // Vibrant orange
  private static final Color COLOR_PINK = new Color(240, 98, 146); // Vibrant rose pink
  private static final Color COLOR_BRIGHT_YELLOW = new Color(175, 216, 84); // Lime green LPF/HPF
  private static final Color COLOR_SOFT_GREEN = new Color(175, 216, 84); // Lime green LPF/HPF
  private static final Color COLOR_SOFT_BLUE = new Color(100, 181, 246); // Sky blue LFO
  private static final Color COLOR_SOFT_RED = new Color(239, 154, 154); // Light coral red
  private static final Color COLOR_OCHRE = new Color(255, 204, 128); // Warm orange
  private static final Color COLOR_WHITE = new Color(245, 245, 245); // Off-white

  private static final String[] MACRO_TOOLTIPS = {
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>LEVEL (Col 0)</b><br>Adjusts track master volume gain.<br>• <i>Virtual:</i> Sets master output level (0.0 to 1.5 multiplier).<br>• <i>Physical Deluge:</i> [VOLUME] Button (Upper Mode) ➔ Turn Left Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>PAN (Col 1)</b><br>Adjusts track stereo panning placement.<br>• <i>Virtual:</i> Stereo pan balance (-1.0 Left to +1.0 Right).<br>• <i>Physical Deluge:</i> [VOLUME] Button (Upper Mode) ➔ Turn Right Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>PITCH (Col 2)</b><br>Adjusts track pitch transposition.<br>• <i>Virtual:</i> Shifts track tuning pitch (-24 to +24 semitones).<br>• <i>Physical Deluge:</i> [TRANSPOSE] Button (Upper Mode) ➔ Turn Left Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>FILTER (Col 3)</b><br>Adjusts LPF Cutoff or FM Coarse Ratio.<br>• <i>Synth/Kit:</i> Sets Lowpass Filter Cutoff frequency (20Hz - 20kHz).<br>• <i>FM Mode:</i> Sets Active Operator Coarse Ratio (0 - 31).<br>• <i>Physical Deluge:</i> [CUTOFF / FM] Button (Upper Mode) ➔ Turn Left Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>RESONANCE (Col 4)</b><br>Adjusts LPF Resonance or FM Operator Level.<br>• <i>Synth/Kit:</i> Sets lowpass filter resonance feedback Q (0.0 - 1.0).<br>• <i>FM Mode:</i> Sets Active Operator Output Level volume (0 - 99).<br>• <i>Physical Deluge:</i> [CUTOFF / FM] Button (Upper Mode) ➔ Turn Right Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>OSC1 (Col 5)</b><br>Adjusts Osc A mix level or FM EG Rates.<br>• <i>Synth Mode:</i> Sets Oscillator A relative volume mix (0.0 to 1.0).<br>• <i>FM Mode:</i> Opens active Operator Envelope Rates (Rates 1 - 4).<br>• <i>Physical Deluge:</i> [ENV1] Button (Upper Mode) ➔ Turn Encoders.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>OSC2 (Col 6)</b><br>Adjusts Noise mix level or FM EG Levels.<br>• <i>Synth Mode:</i> Sets background white noise generator level (0.0 to 1.0).<br>• <i>FM Mode:</i> Opens active Operator Envelope Levels (Levels 1 - 4).<br>• <i>Physical Deluge:</i> [ENV2] Button (Lower Mode) ➔ Turn Encoders.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>LFO (Col 7)</b><br>Adjusts primary LFO frequency speed rate.<br>• <i>Virtual:</i> Sets LFO 1 wave oscillation speed (0.0Hz to 20.0Hz).<br>• <i>Physical Deluge:</i> [LFO1] Button (Upper Mode) ➔ Turn Left Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>MOD FX (Col 8)</b><br>Adjusts Chorus/Flanger modulation depth.<br>• <i>Virtual:</i> Sets Mod spatial Chorus/Flanger depth (0.0 to 1.0).<br>• <i>Physical Deluge:</i> [MODRATE] Button (Upper Mode) ➔ Turn Right Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>DELAY (Col 9)</b><br>Adjusts track Delay spatial send level.<br>• <i>Virtual:</i> Sets delay feedback loop send amount (0.0 to 1.0).<br>• <i>Physical Deluge:</i> [PAN] Button (Lower Mode) ➔ Turn Left Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>REVERB (Col 10)</b><br>Adjusts track Reverb spatial send level.<br>• <i>Virtual:</i> Sets reverb spatial room send amount (0.0 to 1.0).<br>• <i>Physical Deluge:</i> [PAN] Button (Lower Mode) ➔ Turn Right Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>STUTTER (Col 11)</b><br>Adjusts real-time step repeat stutter rate.<br>• <i>Virtual:</i> Sets performance repeat loop frequency speed (0.0 to 1.0).<br>• <i>Physical Deluge:</i> Performance repeat shortcut buttons.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>PROBABILITY (Col 12)</b><br>Adjusts active steps trigger probability.<br>• <i>Virtual:</i> Sets trigger probability (0% to 100%) for all steps in clip.<br>• <i>Physical Deluge:</i> Hold pad and turn Select Encoder knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>GATE (Col 13)</b><br>Adjusts active steps duration length gate.<br>• <i>Virtual:</i> Sets gate length duration multiplier (0.0 to 2.0).<br>• <i>Physical Deluge:</i> [GATE] Button (Lower Mode) ➔ Turn Left Gold Knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>VELOCITY (Col 14)</b><br>Adjusts active steps strike velocity values.<br>• <i>Virtual:</i> Sets step strike velocity input (0.0 to 1.0).<br>• <i>Physical Deluge:</i> Hold pad and turn Volume/Level Encoder knob.</body></html>",
    "<html><body style='font-size: 9px; font-family: sans-serif;'><b>SAMPLE / ARP RATE (Col 15)</b><br>Swaps active sample wav or sets Arp Speed.<br>• <i>Kit Mode:</i> Opens multi-sample drum kit load swap browser.<br>• <i>Synth Mode:</i> Sets active Arpeggiator step speed rate (0.0 to 2.0).<br>• <i>Physical Deluge:</i> [ARP RATE] Button (Upper Mode) ➔ Turn Left Gold Knob.</body></html>"
  };

  private static final String[][] SHIFT_LABELS = {
    {
      "WAVE FORM",
      "WAVE FORM",
      "NOISE",
      "OSC SYNC",
      "DIRECTION",
      "",
      "SATURATE",
      "",
      "CUTOFF",
      "CUTOFF",
      "BASS FREQ",
      "TREBLE FREQ",
      "RATE",
      "SIZE",
      "X",
      "Y"
    },
    {
      "INTER POLATION",
      "INTER POLATION",
      "WAVETABLE",
      "WAVETABLE",
      "DESTI NATION",
      "DESTI NATION",
      "BITCRUSH",
      "",
      "RESONANCE",
      "RESONANCE",
      "BASS GAIN",
      "TREBLE GAIN",
      "DEPTH",
      "DAMP",
      "ENV 1",
      "ENV 2"
    },
    {
      "FEED BACK",
      "FEED BACK",
      "FEED BACK",
      "FEED BACK",
      "FEED BACK",
      "FEED BACK",
      "DECIMATE",
      "",
      "SLOPE",
      "",
      "SEND",
      "",
      "FDBACK",
      "WIDTH",
      "LFO 1",
      "LFO 2"
    },
    {
      "RECORD",
      "RECORD",
      "RETRIG PHASE",
      "RETRIG PHASE",
      "RETRIG",
      "RETRIG",
      "SYNTH MODE",
      "UNISON VOICES",
      "",
      "",
      "SHAPE",
      "ARP MODE",
      "OFFSET",
      "PAN",
      "MONO/ STEREO",
      "SDCHAIN"
    },
    {
      "PITCH SPEED",
      "PITCH SPEED",
      "PW",
      "PW",
      "PW",
      "PW",
      "PAN",
      "UNISON DETUNE",
      "ATTACK",
      "ATTACK",
      "ATTACK",
      "ARP OCTAVES",
      "TYPE",
      "AMOUNT",
      "AMOUNT",
      "NOTE"
    },
    {
      "SPEED",
      "SPEED",
      "TYPE",
      "TYPE",
      "TYPE",
      "TYPE",
      "VIBRATO",
      "VOICE PRIORITY",
      "DECAY",
      "DECAY",
      "VOL DUCK",
      "ARP GATE",
      "SHAPE",
      "SHAPE",
      "DIGI/ ANALOG",
      "RANDOM"
    },
    {
      "REVERSE",
      "REVERSE",
      "TRANS POSE",
      "TRANS POSE",
      "TRANS POSE",
      "TRANS POSE",
      "TRANS POSE",
      "POLY PHONY",
      "SUSTAIN",
      "SUSTAIN",
      "SYNC",
      "ARP SYNC",
      "SYNC",
      "SYNC",
      "SYNC",
      "VELOCITY"
    },
    {
      "MODE",
      "MODE",
      "LEVEL",
      "LEVEL",
      "LEVEL",
      "LEVEL",
      "GLIDE",
      "GLIDE",
      "RELEASE",
      "RELEASE",
      "LEVEL",
      "ARP RATE",
      "RATE",
      "RATE",
      "RATE",
      "AFTER TOUCH"
    }
  };

  private static final Color[][] SHIFT_COLORS = {
    {
      COLOR_PEACH, COLOR_PEACH, COLOR_PEACH, COLOR_PEACH,
      COLOR_YELLOW, COLOR_WHITE, COLOR_YELLOW, COLOR_WHITE,
      COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW,
      COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW, COLOR_WHITE, COLOR_WHITE
    },
    {
      COLOR_PEACH, COLOR_PEACH, COLOR_PEACH, COLOR_PEACH,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_YELLOW, COLOR_WHITE,
      COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW,
      COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW, COLOR_YELLOW, COLOR_YELLOW
    },
    {
      COLOR_PEACH, COLOR_PEACH, COLOR_PEACH, COLOR_PEACH,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_YELLOW, COLOR_WHITE,
      COLOR_BRIGHT_YELLOW, COLOR_WHITE, COLOR_PINK, COLOR_WHITE,
      COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW, COLOR_SLATE, COLOR_SLATE
    },
    {
      COLOR_PEACH, COLOR_PEACH, COLOR_PEACH, COLOR_PEACH,
      COLOR_WHITE, COLOR_WHITE, COLOR_DEEP_BLUE, COLOR_DEEP_BLUE,
      COLOR_WHITE, COLOR_WHITE, COLOR_PINK, COLOR_YELLOW,
      COLOR_BRIGHT_YELLOW, COLOR_BRIGHT_YELLOW, COLOR_PINK, COLOR_ORANGE
    },
    {
      COLOR_PEACH, COLOR_PEACH, COLOR_PEACH, COLOR_PEACH,
      COLOR_WHITE, COLOR_WHITE, COLOR_DEEP_BLUE, COLOR_DEEP_BLUE,
      COLOR_ORANGE, COLOR_ORANGE, COLOR_PINK, COLOR_YELLOW,
      COLOR_BRIGHT_YELLOW, COLOR_SLATE, COLOR_PINK, COLOR_ORANGE
    },
    {
      COLOR_PEACH, COLOR_PEACH, COLOR_PEACH, COLOR_PEACH,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_DEEP_BLUE, COLOR_DEEP_BLUE,
      COLOR_ORANGE, COLOR_ORANGE, COLOR_PINK, COLOR_YELLOW,
      COLOR_SLATE, COLOR_SLATE, COLOR_PINK, COLOR_ORANGE
    },
    {
      COLOR_PEACH, COLOR_PEACH, COLOR_PEACH, COLOR_PEACH,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_DEEP_BLUE, COLOR_DEEP_BLUE,
      COLOR_ORANGE, COLOR_ORANGE, COLOR_PINK, COLOR_YELLOW,
      COLOR_SLATE, COLOR_SLATE, COLOR_SLATE, COLOR_ORANGE
    },
    {
      COLOR_PEACH, COLOR_PEACH, COLOR_PEACH, COLOR_PEACH,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_DEEP_BLUE, COLOR_DEEP_BLUE,
      COLOR_ORANGE, COLOR_ORANGE, COLOR_PINK, COLOR_YELLOW,
      COLOR_SLATE, COLOR_SLATE, COLOR_PINK, COLOR_ORANGE
    }
  };
  private String autoDragParam; // param name being dragged (for undo capture)
  private int autoDragStep = -1; // step index being dragged
  private float autoDragOldValue = -1f; // value before drag started
  private boolean autoOverviewMode = true; // true=overview grid, false=detail editor
  private int autoColScroll = 0; // horizontal scroll for overview param cols

  private DelugeGestureCoordinator gestureCoordinator;

  private Color[] trackColors = {
    new Color(0x00, 0xff, 0xcc), // Cyan
    new Color(0xff, 0x33, 0xcc), // Magenta
    new Color(0x33, 0xff, 0x33), // Lime Green
    new Color(0xff, 0x99, 0x33), // Orange
    new Color(0xcc, 0x33, 0xff), // Purple
    new Color(0xff, 0xff, 0x33), // Yellow
    new Color(0x33, 0x99, 0xff), // Blue
    new Color(0xff, 0x33, 0x33), // Red
    new Color(0xff, 0x66, 0x99), // Pink
    new Color(0x99, 0xff, 0x99), // Mint
    new Color(0xff, 0xcc, 0x66), // Peach
    new Color(0x66, 0xcc, 0xff), // Sky Blue
    new Color(0xcc, 0x99, 0xff), // Lavender
    new Color(0x66, 0xff, 0x66), // Bright Green
    new Color(0xff, 0x99, 0x66), // Coral
    new Color(0x99, 0xcc, 0xff), // Baby Blue
  };

  /**
   * Returns the backing JButton pad array. Used by SwingPicTransport to render physical pad
   * colours.
   */
  public JButton[][] getPadButtons() {
    return pads;
  }

  /** Cached pad size, computed once per resize so refresh() is idempotent. */
  int cachedPadSz = 48;

  private boolean refreshInProgress = false;

  /** Constrain max size to preferred so BoxLayout can't grow this panel unbounded. */
  @Override
  public Dimension getMaximumSize() {
    return getPreferredSize();
  }

  /** Recompute cachedPadSz from current width/height. Called on resize, not on every refresh(). */
  private boolean recomputePadSize() {
    // Block recursive recompute triggered by revalidate() during refresh() — the inflated
    // 3000-wide preferred sizes cause getWidth() to balloon and padSz to grow on each cycle.
    if (refreshInProgress) return false;
    int availWidth = Math.min(getWidth() > 0 ? getWidth() : 1200, 1600);
    int availHeight = Math.min(getHeight() > 0 ? getHeight() : 600, 700);
    int labelWidth = Math.max(60, Math.min(140, availWidth / 12));
    int cellsWidth = availWidth - labelWidth - 69 - 5 - 12 - 5 - 20;
    int rowsInView = gridMode.rows + 3;
    int padSz = Math.min(cellsWidth / columnCount, (availHeight - 30) / rowsInView);
    int newSz = Math.max(16, Math.min(200, padSz));
    if (newSz != cachedPadSz) {
      System.out.println(
          "DEBUG recomputePadSize: "
              + cachedPadSz
              + " -> "
              + newSz
              + " (avail="
              + availWidth
              + "x"
              + availHeight
              + " rowsInView="
              + rowsInView
              + " colCount="
              + columnCount
              + ")");
      cachedPadSz = newSz;
      return true;
    }
    return false;
  }

  public SwingGridPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = new org.chuck.deluge.model.ProjectModel();

    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

    addMouseWheelListener(
        e -> {
          if (shiftHeld && activeShiftParam != null) {
            int rotation = e.getWheelRotation();
            adjustRotaryParameter(-rotation);
            return;
          }
          int rotation = e.getWheelRotation();
          if (e.isShiftDown()) {
            scrollHorizontally(rotation);
          } else {
            scrollVertically(rotation);
          }
        });

    // Recompute padSz on user window resize (not on internal revalidate from refresh)
    addComponentListener(
        new java.awt.event.ComponentAdapter() {
          private int lastW = -1, lastH = -1;

          @Override
          public void componentResized(java.awt.event.ComponentEvent e) {
            int w = getWidth(), h = getHeight();
            if (w != lastW || h != lastH) {
              lastW = w;
              lastH = h;
              System.out.println("DEBUG resize: " + lastW + "x" + lastH + " -> recomputePadSize");
              if (recomputePadSize() && !refreshInProgress) {
                refresh();
              }
            }
          }
        });

    // Apply saved grid mode to step/column counts
    this.stepCount = gridMode.columns;
    this.columnCount = gridMode.columns + 2;

    // Keyboard navigation for scrollable CLIP mode
    InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
    ActionMap am = getActionMap();

    im.put(KeyStroke.getKeyStroke("PAGE_UP"), "scrollPageUp");
    im.put(KeyStroke.getKeyStroke("PAGE_DOWN"), "scrollPageDown");
    im.put(KeyStroke.getKeyStroke("UP"), "scrollLineUp");
    im.put(KeyStroke.getKeyStroke("DOWN"), "scrollLineDown");
    im.put(KeyStroke.getKeyStroke("DELETE"), "deleteSelectedSteps");
    im.put(KeyStroke.getKeyStroke("BACK_SPACE"), "deleteSelectedSteps");
    im.put(KeyStroke.getKeyStroke("ESCAPE"), "clearSelectedSteps");

    am.put(
        "scrollPageUp",
        new AbstractAction() {
          public void actionPerformed(java.awt.event.ActionEvent e) {
            if (viewMode == GridViewMode.CLIP) scrollPage(-1);
          }
        });
    am.put(
        "scrollPageDown",
        new AbstractAction() {
          public void actionPerformed(java.awt.event.ActionEvent e) {
            if (viewMode == GridViewMode.CLIP) scrollPage(1);
          }
        });
    am.put(
        "scrollLineUp",
        new AbstractAction() {
          public void actionPerformed(java.awt.event.ActionEvent e) {
            if (viewMode == GridViewMode.CLIP) scrollBy(-1);
          }
        });
    am.put(
        "scrollLineDown",
        new AbstractAction() {
          public void actionPerformed(java.awt.event.ActionEvent e) {
            if (viewMode == GridViewMode.CLIP) scrollBy(1);
          }
        });
    am.put(
        "deleteSelectedSteps",
        new AbstractAction() {
          public void actionPerformed(java.awt.event.ActionEvent e) {
            deleteSelectedStepsAction();
          }
        });
    am.put(
        "clearSelectedSteps",
        new AbstractAction() {
          public void actionPerformed(java.awt.event.ActionEvent e) {
            clearMultiSelection();
          }
        });

    // Clip cycling: [ = prev clip, ] = next clip
    im.put(KeyStroke.getKeyStroke("OPEN_BRACKET"), "prevClip");
    im.put(KeyStroke.getKeyStroke("CLOSE_BRACKET"), "nextClip");
    am.put(
        "prevClip",
        new AbstractAction() {
          public void actionPerformed(java.awt.event.ActionEvent e) {
            cycleClip(-1);
          }
        });
    am.put(
        "nextClip",
        new AbstractAction() {
          public void actionPerformed(java.awt.event.ActionEvent e) {
            cycleClip(1);
          }
        });
    boolean isAdvanced =
        org.chuck.deluge.project.PreferencesManager.getGridPanelType()
            == org.chuck.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
    if (isAdvanced) {
      this.gestureCoordinator = new DelugeGestureCoordinator(this, new DelugeGestureListener());
    }
  }

  private int focusTrack = 0;
  private Runnable onProjectChanged;
  private Runnable onClipChanged;

  public void setOnProjectChanged(Runnable r) {
    this.onProjectChanged = r;
  }

  public void setOnClipChanged(Runnable r) {
    this.onClipChanged = r;
  }

  private void fireProjectChanged() {
    if (onProjectChanged != null) onProjectChanged.run();
    refresh();
  }

  /** Blend a base color with black proportionally to velocity (0.0 = black, 1.0 = full color). */
  private static Color velocityBlend(Color base, double velocity) {
    if (velocity >= 1.0) return base;
    if (velocity <= 0.0) return new Color(0x33, 0x33, 0x33);
    int r = (int) (base.getRed() * velocity + 0x33 * (1 - velocity));
    int g = (int) (base.getGreen() * velocity + 0x33 * (1 - velocity));
    int b = (int) (base.getBlue() * velocity + 0x33 * (1 - velocity));
    return new Color(Math.min(255, r), Math.min(255, g), Math.min(255, b));
  }

  public int getFocusTrack() {
    return focusTrack;
  }

  private void showTrackContextMenu(java.awt.Component src, int x, int y, int trackIdx) {
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();
    if (trackIdx >= tracks.size()) return;
    org.chuck.deluge.model.TrackModel track = tracks.get(trackIdx);

    JPopupMenu menu = new JPopupMenu();

    JMenuItem renameItem = new JMenuItem("Rename...");
    renameItem.addActionListener(
        e -> {
          String newName = JOptionPane.showInputDialog(this, "Track name:", track.getName());
          if (newName != null && !newName.isBlank()) {
            track.setName(newName);
            fireProjectChanged();
          }
        });
    menu.add(renameItem);

    JMenuItem colorItem = new JMenuItem("Set Color...");
    colorItem.addActionListener(
        e -> {
          Color chosen = JColorChooser.showDialog(this, "Track Color", trackColors[trackIdx]);
          if (chosen != null) {
            trackColors[trackIdx] = chosen;
            track.setColourHex(
                "0x" + Integer.toHexString(chosen.getRGB() & 0xFFFFFF).toUpperCase());
            fireProjectChanged();
          }
        });
    menu.add(colorItem);

    menu.addSeparator();

    JMenuItem upItem = new JMenuItem("Move Up");
    upItem.setEnabled(trackIdx > 0);
    upItem.addActionListener(
        e -> {
          projectModel.moveTrackUp(trackIdx);
          // swap colors so they follow the track
          Color tmp = trackColors[trackIdx];
          trackColors[trackIdx] = trackColors[trackIdx - 1];
          trackColors[trackIdx - 1] = tmp;
          javax.swing.SwingUtilities.invokeLater(this::fireProjectChanged);
        });
    menu.add(upItem);

    JMenuItem downItem = new JMenuItem("Move Down");
    downItem.setEnabled(trackIdx < tracks.size() - 1);
    downItem.addActionListener(
        e -> {
          projectModel.moveTrackDown(trackIdx);
          // swap colors so they follow the track
          Color tmp = trackColors[trackIdx];
          trackColors[trackIdx] = trackColors[trackIdx + 1];
          trackColors[trackIdx + 1] = tmp;
          javax.swing.SwingUtilities.invokeLater(this::fireProjectChanged);
        });
    menu.add(downItem);

    menu.addSeparator();

    if (track instanceof org.chuck.deluge.model.KitTrackModel kitTrack) {
      JMenuItem saveKitItem = new JMenuItem("Save as Kit preset...");
      saveKitItem.addActionListener(e -> saveTrackPreset(kitTrack, false));
      menu.add(saveKitItem);
    } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synthTrack) {
      JMenuItem saveSynthItem = new JMenuItem("Save as Synth preset...");
      saveSynthItem.addActionListener(e -> saveTrackPreset(synthTrack, true));
      menu.add(saveSynthItem);
    }

    menu.addSeparator();

    // Per-row probability and velocity — operates on all steps of this row in the active clip
    JMenuItem rowProbItem = new JMenuItem("Set Row Probability...");
    rowProbItem.addActionListener(
        e -> {
          String input = JOptionPane.showInputDialog(this, "Row probability (0-100%):", 100);
          if (input != null) {
            try {
              double val = Double.parseDouble(input.trim()) / 100.0;
              val = Math.max(0, Math.min(1, val));
              int engineRow = baseTrackId + trackIdx;
              int len = bridge != null ? bridge.getTrackLength(engineRow) : stepCount;
              for (int s = 0; s < len && s < stepCount; s++) {
                bridge.setStepProbability(engineRow, s, val);
              }
              refresh();
            } catch (NumberFormatException ignored) {
            }
          }
        });
    menu.add(rowProbItem);

    JMenuItem rowVelItem = new JMenuItem("Set Row Velocity...");
    rowVelItem.addActionListener(
        e -> {
          String input = JOptionPane.showInputDialog(this, "Row velocity (0-100%):", 80);
          if (input != null) {
            try {
              double val = Double.parseDouble(input.trim()) / 100.0;
              val = Math.max(0, Math.min(1, val));
              int engineRow = baseTrackId + trackIdx;
              int len = bridge != null ? bridge.getTrackLength(engineRow) : stepCount;
              for (int s = 0; s < len && s < stepCount; s++) {
                bridge.setVelocity(engineRow, s, val);
              }
              refresh();
            } catch (NumberFormatException ignored) {
            }
          }
        });
    menu.add(rowVelItem);

    menu.addSeparator();

    JMenuItem deleteItem = new JMenuItem("Delete Track");
    deleteItem.setForeground(Color.RED);
    deleteItem.addActionListener(
        e -> {
          int confirm =
              JOptionPane.showConfirmDialog(
                  this,
                  "Delete track \"" + track.getName() + "\" and all its clips?",
                  "Delete Track",
                  JOptionPane.YES_NO_OPTION,
                  JOptionPane.WARNING_MESSAGE);
          if (confirm == JOptionPane.YES_OPTION) {
            projectModel.removeTrack(track);
            fireProjectChanged();
          }
        });
    menu.add(deleteItem);

    menu.show(src, x, y);
  }

  private void saveTrackPreset(org.chuck.deluge.model.TrackModel track, boolean isSynth) {
    java.io.File dir =
        isSynth
            ? org.chuck.deluge.project.PreferencesManager.getSynthsDir()
            : org.chuck.deluge.project.PreferencesManager.getKitsDir();
    JFileChooser chooser = new JFileChooser(dir);
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("XML preset", "xml", "XML"));
    chooser.setSelectedFile(new java.io.File(dir, track.getName() + ".xml"));
    if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
    java.io.File file = chooser.getSelectedFile();
    if (!file.getName().toLowerCase().endsWith(".xml")) {
      file = new java.io.File(file.getAbsolutePath() + ".xml");
    }
    try {
      if (isSynth) {
        org.chuck.deluge.project.KitSynthSerializer.saveSynth(
            (org.chuck.deluge.model.SynthTrackModel) track, file);
      } else {
        org.chuck.deluge.project.KitSynthSerializer.saveKit(
            (org.chuck.deluge.model.KitTrackModel) track, file);
      }
      JOptionPane.showMessageDialog(
          this,
          "Saved to " + file.getAbsolutePath(),
          "Preset Saved",
          JOptionPane.INFORMATION_MESSAGE);
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this, "Save failed:\n" + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
    }
  }

  private void showClipContextMenu(
      java.awt.Component src,
      int x,
      int y,
      org.chuck.deluge.model.TrackModel track,
      int clipIdx,
      int trackIndex) {
    JPopupMenu menu = new JPopupMenu();
    org.chuck.deluge.model.ClipModel clip = track.getClips().get(clipIdx);

    JMenuItem renameItem = new JMenuItem("Rename Clip...");
    renameItem.addActionListener(
        e -> {
          String newName = JOptionPane.showInputDialog(this, "Clip name:", clip.getName());
          if (newName != null && !newName.isBlank()) {
            clip.setName(newName);
            fireProjectChanged();
          }
        });
    menu.add(renameItem);

    JMenuItem dupeItem = new JMenuItem("Duplicate Clip");
    dupeItem.addActionListener(
        e -> {
          org.chuck.deluge.model.ClipModel copy = clip.deepCopy(clip.getName() + " copy");
          track.addClip(copy);
          fireProjectChanged();
        });
    menu.add(dupeItem);

    menu.addSeparator();

    JMenuItem deleteItem = new JMenuItem("Delete Clip");
    deleteItem.setForeground(Color.RED);
    deleteItem.addActionListener(
        e -> {
          if (track.getClips().size() <= 1) {
            JOptionPane.showMessageDialog(this, "A track must have at least one clip.");
            return;
          }
          int confirm =
              JOptionPane.showConfirmDialog(
                  this,
                  "Delete clip \"" + clip.getName() + "\"?",
                  "Delete Clip",
                  JOptionPane.YES_NO_OPTION);
          if (confirm == JOptionPane.YES_OPTION) {
            track.removeClip(clip);
            fireProjectChanged();
          }
        });
    menu.add(deleteItem);

    menu.addSeparator();

    // ── Play Mode submenu ──
    JMenu playModeMenu = new JMenu("Play Mode");
    org.chuck.deluge.model.ClipModel.PlayMode currentMode = clip.getPlayMode();

    JRadioButtonMenuItem normalItem =
        new JRadioButtonMenuItem(
            "Normal", currentMode == org.chuck.deluge.model.ClipModel.PlayMode.NORMAL);
    normalItem.addActionListener(
        e -> {
          clip.setPlayMode(org.chuck.deluge.model.ClipModel.PlayMode.NORMAL);
          if (bridge != null) bridge.setClipPlayMode(trackIndex, clipIdx, 0);
          fireProjectChanged();
        });
    playModeMenu.add(normalItem);

    JRadioButtonMenuItem loopItem =
        new JRadioButtonMenuItem(
            "Loop (green)", currentMode == org.chuck.deluge.model.ClipModel.PlayMode.LOOP);
    loopItem.addActionListener(
        e -> {
          clip.setPlayMode(org.chuck.deluge.model.ClipModel.PlayMode.LOOP);
          if (bridge != null) bridge.setClipPlayMode(trackIndex, clipIdx, 1);
          fireProjectChanged();
        });
    playModeMenu.add(loopItem);

    // Group the radio buttons so only one can be selected
    ButtonGroup playModeGroup = new ButtonGroup();
    playModeGroup.add(normalItem);
    playModeGroup.add(loopItem);

    menu.add(playModeMenu);

    menu.show(src, x, y);
  }

  public void setViewMode(GridViewMode mode) {
    this.viewMode = mode;
    if (mode == GridViewMode.AUTOMATION) {
      this.columnCount = stepCount; // no MUTE/SOLO columns
    } else {
      this.columnCount = stepCount + 2;
    }
    recomputePadSize();
    refresh();
  }

  public org.chuck.deluge.model.ProjectModel getProjectModel() {
    return projectModel;
  }

  public void setProjectModel(org.chuck.deluge.model.ProjectModel model) {

    this.projectModel = model;
    refresh();
  }

  public void setActiveClipId(int id) {
    this.activeClipId = id;
  }

  public void setBaseTrackId(int id) {
    this.baseTrackId = id;
    refresh();
  }

  public void setEditedModelTrack(int modelTrack) {
    this.editedModelTrack = modelTrack;
    scrollOffsetX = 0; // reset horizontal scroll when editing different track
  }

  /** Returns the total number of voice rows for the currently edited track. */
  public int getVoiceRowCount() {
    return voiceRowCount;
  }

  /** Set the grid size mode and recompute step/column counts. */
  public void setGridMode(org.chuck.deluge.project.PreferencesManager.GridMode mode) {
    if (this.gridMode != mode) {
      this.gridMode = mode;
      this.stepCount = mode.columns;
      this.columnCount = mode.columns + 2; // +2 for MUTE and SOLO columns
      if (viewMode == GridViewMode.AUTOMATION) {
        this.columnCount = mode.columns;
      }
      scrollOffset = 0;
      scrollOffsetX = 0;
      recomputePadSize();
      refresh();
    }
  }

  public org.chuck.deluge.project.PreferencesManager.GridMode getGridMode() {
    return gridMode;
  }

  public void setOnEditRequest(java.util.function.BiConsumer<Integer, Integer> callback) {
    this.onEditRequest = callback;
  }

  /** Reset scroll offsets when edited track changes. */
  public void resetScrollOffset() {
    scrollOffset = 0;
    scrollOffsetX = 0;
  }

  /** Compute total voice rows for the currently edited track in CLIP mode. */
  private int computeVoiceRowCount() {
    if (viewMode == GridViewMode.AUTOMATION) {
      return 8; // fixed 8 value bands (0-127 mapped to 8 rows)
    }
    if (viewMode == GridViewMode.CLIP
        && projectModel != null
        && editedModelTrack < projectModel.getTracks().size()) {
      org.chuck.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      if (t instanceof org.chuck.deluge.model.KitTrackModel kit) {
        return kit.getDrums().size();
      }
      // Synth always uses gridMode.rows as voice count
      return gridMode.rows;
    }
    // SONG / ARRANGEMENT — use gridMode.rows as the total number of voice slots
    return gridMode.rows;
  }

  /** Number of visible voice rows in the viewport (up to gridMode.rows). */
  public int getVisibleRowCount() {
    return Math.min(gridMode.rows, voiceRowCount - scrollOffset);
  }

  /** Scroll the voice row viewport by delta rows. Positive = down, negative = up. */
  public void scrollBy(int delta) {
    int maxOffset = Math.max(0, voiceRowCount - gridMode.rows);
    scrollOffset = Math.max(0, Math.min(maxOffset, scrollOffset + delta));
    refresh();
  }

  /** Scroll by one full page (gridMode.rows rows). */
  public void scrollPage(int direction) {
    scrollBy(direction * gridMode.rows);
  }

  /** Cycle to prev (dir=-1) or next (dir=1) clip on the edited track in CLIP mode. */
  public void cycleClip(int dir) {
    if (viewMode != GridViewMode.CLIP) return;
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
    org.chuck.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
    int n = t.getClips().size();
    if (n <= 1) return;
    int next = (activeClipId + dir) % n;
    if (next < 0) next = n - 1;
    activeClipId = next;
    t.setActiveClipIndex(next);
    if (onClipChanged != null) onClipChanged.run();
    refresh();
  }

  /** VU meter panel used inside voice rows. */
  private static class VUMeterPanel extends JPanel {
    private double lvl = 0.0;

    public void setLvl(double l) {
      this.lvl = l;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      java.awt.Graphics2D g2d = (java.awt.Graphics2D) g;
      g2d.setColor(new Color(0x18, 0x18, 0x1a)); // deep charcoal frame
      g2d.fillRect(0, 0, getWidth(), getHeight());

      int h = (int) (lvl * getHeight());
      if (h <= 0) return;

      // Draw standard green level (0% to 65% height)
      int greenH = Math.min(h, (int) (0.65 * getHeight()));
      g2d.setColor(new Color(0x00, 0xff, 0x66)); // neon green
      g2d.fillRect(0, getHeight() - greenH, getWidth(), greenH);

      // Draw yellow headroom (65% to 85% height)
      if (h > (int) (0.65 * getHeight())) {
        int yellowH = Math.min(h, (int) (0.85 * getHeight())) - (int) (0.65 * getHeight());
        g2d.setColor(new Color(0xff, 0xaa, 0x00)); // amber/orange
        g2d.fillRect(0, getHeight() - (int) (0.65 * getHeight()) - yellowH, getWidth(), yellowH);
      }

      // Draw red clipping (85% to 100% height)
      if (h > (int) (0.85 * getHeight())) {
        int redH = h - (int) (0.85 * getHeight());
        g2d.setColor(new Color(0xff, 0x33, 0x33)); // bright red
        g2d.fillRect(0, getHeight() - (int) (0.85 * getHeight()) - redH, getWidth(), redH);
      }
    }
  }

  private static final java.util.Map<String, float[]> waveformCache =
      new java.util.concurrent.ConcurrentHashMap<>();

  private static float[] getCachedWaveform(String path) {
    if (path == null || path.isEmpty()) return null;
    return waveformCache.computeIfAbsent(
        path,
        p -> {
          try {
            java.io.File file = new java.io.File(p);
            if (!file.exists()) return null;
            try (javax.sound.sampled.AudioInputStream ais =
                javax.sound.sampled.AudioSystem.getAudioInputStream(file)) {
              javax.sound.sampled.AudioFormat format = ais.getFormat();
              int bytesPerFrame = format.getFrameSize();
              if (bytesPerFrame == 0) return null;
              byte[] buffer = new byte[4096];
              int read;
              java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
              while ((read = ais.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
              }
              byte[] audioBytes = baos.toByteArray();
              int totalSamples = audioBytes.length / bytesPerFrame;
              if (totalSamples <= 0) return null;

              int targetPoints = 256;
              float[] points = new float[targetPoints];
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
                  short sample =
                      isBigEndian
                          ? (short) ((b1 << 8) | (b2 & 0xff))
                          : (short) ((b2 << 8) | (b1 & 0xff));
                  val = Math.abs(sample / 32768.0f);
                } else if (sampleSizeInBits == 8) {
                  int sample = audioBytes[byteIndex] & 0xff;
                  val = Math.abs((sample - 128) / 128.0f);
                }
                points[i] = val;
              }
              float[] smoothed = new float[targetPoints];
              for (int i = 0; i < targetPoints; i++) {
                float sum = 0;
                int count = 0;
                for (int w = -2; w <= 2; w++) {
                  int idx = i + w;
                  if (idx >= 0 && idx < targetPoints) {
                    sum += points[idx];
                    count++;
                  }
                }
                smoothed[i] = sum / count;
              }
              return smoothed;
            }
          } catch (Exception ex) {
            LOG.warning("Waveform parsing failed for " + p + ": " + ex.getMessage());
            return null;
          }
        });
  }

  /**
   * Build a single voice row panel. modelRow = the actual engine row index (0..voiceRowCount-1).
   */
  private JPanel buildVoiceRow(
      int modelRow,
      int visibleRow,
      int padSz,
      java.util.List<org.chuck.deluge.model.TrackModel> tracks) {
    String samplePathLoc = null;
    if (modelRow < tracks.size()) {
      org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);
      if (viewMode == GridViewMode.CLIP
          && track instanceof org.chuck.deluge.model.KitTrackModel kit) {
        java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
        int drumIdx = sounds.size() - 1 - modelRow;
        if (drumIdx >= 0 && drumIdx < sounds.size()) {
          org.chuck.deluge.model.Drum drum = sounds.get(drumIdx);
          if (drum instanceof org.chuck.deluge.model.SoundDrum soundDrum) {
            samplePathLoc = soundDrum.getSamplePath();
          }
        }
      } else if (track instanceof org.chuck.deluge.model.AudioTrackModel audioTrack
          && !audioTrack.getAudioClips().isEmpty()) {
        samplePathLoc = audioTrack.getAudioClips().get(0).getFilePath();
      }
    }
    final String targetSamplePath = samplePathLoc;

    JPanel rowPanel =
        new JPanel() {
          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (targetSamplePath == null || targetSamplePath.isEmpty()) return;
            float[] points = getCachedWaveform(targetSamplePath);
            if (points == null || points.length == 0) return;

            int startX = 0;
            int endX = getWidth();
            boolean foundPads = false;
            for (Component c : getComponents()) {
              if (c instanceof DelugePadButton pad) {
                Integer col = (Integer) pad.getClientProperty("col");
                if (col != null) {
                  if (col == 0) {
                    startX = pad.getX();
                    foundPads = true;
                  }
                  if (col == stepCount - 1) {
                    endX = pad.getX() + pad.getWidth();
                  }
                }
              }
            }

            int width = endX - startX;
            if (!foundPads || width <= 0) return;

            Graphics2D g2d = (Graphics2D) g;
            g2d.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setColor(new Color(0x00, 0xff, 0x66, 0x1a)); // transparent soft green glow

            int midY = getHeight() / 2;
            int maxAmp = (int) (getHeight() * 0.45);

            java.awt.geom.Path2D.Float path = new java.awt.geom.Path2D.Float();
            for (int i = 0; i < points.length; i++) {
              int x = startX + (int) ((i / (float) (points.length - 1)) * width);
              int yOffset = (int) (points[i] * maxAmp);
              if (i == 0) {
                path.moveTo(x, midY - yOffset);
              } else {
                path.lineTo(x, midY - yOffset);
              }
            }
            for (int i = points.length - 1; i >= 0; i--) {
              int x = startX + (int) ((i / (float) (points.length - 1)) * width);
              int yOffset = (int) (points[i] * maxAmp);
              path.lineTo(x, midY + yOffset);
            }
            path.closePath();
            g2d.fill(path);
          }
        };
    rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    rowPanel.setPreferredSize(new Dimension(3000, padSz));
    rowPanel.setMinimumSize(new Dimension(3000, padSz));
    rowPanel.setMaximumSize(new Dimension(3000, padSz));

    if (modelRow < tracks.size()) {
      String hex = tracks.get(modelRow).getColourHex();
      if (hex != null && hex.startsWith("0x")) {
        try {
          int rgb = Integer.decode(hex.substring(0, 8));
          trackColors[modelRow % trackColors.length] = new Color(rgb);
        } catch (Exception e) {
          LOG.warning("Bad color hex for track " + modelRow + ": " + e.getMessage());
        }
      }
    }

    String trackName;
    if (viewMode == GridViewMode.CLIP
        && projectModel != null
        && editedModelTrack < projectModel.getTracks().size()) {
      org.chuck.deluge.model.TrackModel rowTrack = projectModel.getTracks().get(editedModelTrack);
      if (rowTrack instanceof org.chuck.deluge.model.KitTrackModel kit) {
        java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
        trackName =
            (modelRow < sounds.size())
                ? sounds.get(sounds.size() - 1 - modelRow).getName()
                : rowTrack.getName();
      } else {
        trackName = (modelRow == 0) ? rowTrack.getName() : "-" + modelRow + "st";
      }
    } else {
      trackName =
          (modelRow < tracks.size()) ? tracks.get(modelRow).getName() : "EMPTY " + (modelRow + 1);
    }

    final int trk = visibleRow;
    final String tName = trackName;
    JLabel label = new JLabel(tName);
    int lw = Math.max(60, Math.min(140, getWidth() / 12));
    label.setPreferredSize(new Dimension(lw, 30));
    label.setMinimumSize(new Dimension(lw, 30));
    label.setMaximumSize(new Dimension(lw, 30));

    label.setTransferHandler(
        new TransferHandler() {
          @Override
          public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(
                java.awt.datatransfer.DataFlavor.javaFileListFlavor);
          }

          @SuppressWarnings("unchecked")
          @Override
          public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            try {
              java.util.List<java.io.File> files =
                  (java.util.List<java.io.File>)
                      support
                          .getTransferable()
                          .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
              if (files != null && !files.isEmpty()) {
                java.io.File soundFile = files.get(0);
                if (soundFile.getName().toLowerCase().endsWith(".wav")
                    || soundFile.getName().toLowerCase().endsWith(".aif")) {
                  hotSwapTrackSample(modelRow, visibleRow, soundFile);
                  return true;
                }
              }
            } catch (Exception ex) {
              LOG.warning("Sample drop import failed: " + ex.getMessage());
            }
            return false;
          }
        });

    label.setForeground(Color.LIGHT_GRAY);
    label.setFont(new Font("SansSerif", Font.BOLD, 10));
    label.setCursor(new Cursor(Cursor.HAND_CURSOR));
    label.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.isShiftDown()) {
              isOneShotTrack[modelRow] = !isOneShotTrack[modelRow];
              label.setText(isOneShotTrack[modelRow] ? tName + " (1SH)" : tName);
              return;
            }
            if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
              showTrackContextMenu(label, e.getX(), e.getY(), modelRow);
              return;
            }
            if (onEditRequest != null) {
              onEditRequest.accept(modelRow, 0);
            }
          }
        });

    rowPanel.add(label);

    // Config button and length badge only for modelRow < 8 (real model tracks)
    if (modelRow < tracks.size() && modelRow < 8) {
      org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);

      JButton cfgBtn = new JButton("\u2699");
      cfgBtn.setPreferredSize(new Dimension(28, 26));
      cfgBtn.setMinimumSize(new Dimension(28, 26));
      cfgBtn.setMaximumSize(new Dimension(28, 26));
      cfgBtn.setMargin(new Insets(0, 0, 0, 0));
      cfgBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
      cfgBtn.setBackground(new Color(0x33, 0x33, 0x33));
      cfgBtn.setForeground(new Color(0x00, 0xff, 0xcc));
      cfgBtn.setToolTipText("Configure track");
      cfgBtn.addActionListener(
          e -> {
            Frame owner = (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this);
            if (track instanceof org.chuck.deluge.model.KitTrackModel kitTrack) {
              new SwingKitConfigDialog(owner, kitTrack, vm, bridge).setVisible(true);
            } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synthTrack) {
              new SwingSynthConfigDialog(owner, synthTrack, vm, bridge, modelRow, projectModel)
                  .setVisible(true);
            }
          });
      rowPanel.add(Box.createHorizontalStrut(3));
      rowPanel.add(cfgBtn);

      int stepLen = (bridge != null) ? bridge.getTrackLength(modelRow) : 16;
      JLabel lenBadge = new JLabel("[" + stepLen + "]");
      lenBadge.setPreferredSize(new Dimension(36, 26));
      lenBadge.setMinimumSize(new Dimension(36, 26));
      lenBadge.setMaximumSize(new Dimension(36, 26));
      lenBadge.setFont(new Font("Monospaced", Font.BOLD, 11));
      lenBadge.setForeground(stepLen == 16 ? Color.GRAY : new Color(0xff, 0xcc, 0x00));
      lenBadge.setToolTipText("Track length (right-click to change)");
      lenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
      lenBadge.addMouseListener(
          new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
              if (javax.swing.SwingUtilities.isRightMouseButton(e) || e.getClickCount() == 2) {
                String input =
                    JOptionPane.showInputDialog(
                        SwingGridPanel.this, "Track length (1-192):", stepLen);
                if (input != null) {
                  try {
                    int newLen = Integer.parseInt(input.trim());
                    if (newLen >= 1 && newLen <= BridgeContract.STEPS) {
                      bridge.setTrackLength(modelRow, newLen);
                      refresh();
                    }
                  } catch (NumberFormatException ignored) {
                  }
                }
              }
            }
          });
      rowPanel.add(Box.createHorizontalStrut(2));
      rowPanel.add(lenBadge);
    } else {
      rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
    }

    rowPanel.add(Box.createHorizontalStrut(5));

    VUMeterPanel vu = new VUMeterPanel();
    vu.setPreferredSize(new Dimension(12, padSz));
    vu.setMaximumSize(new Dimension(12, padSz));
    rowPanel.add(vu);
    rowPanel.add(Box.createHorizontalStrut(5));

    Timer vuTimer =
        new Timer(
            33,
            ev -> {
              vuLevels[modelRow] *= 0.80;
              vu.setLvl(vuLevels[modelRow]);
            });
    vuTimer.start();

    for (int c = 0; c < columnCount; c++) {
      final int colId = c;

      JButton clipBtn;
      boolean isAdvanced =
          org.chuck.deluge.project.PreferencesManager.getGridPanelType()
              == org.chuck.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
      if (isAdvanced) {
        DelugePadButton pad = new DelugePadButton();
        pad.putClientProperty("row", visibleRow);
        pad.putClientProperty("col", c);
        clipBtn = pad;
      } else {
        clipBtn = new JButton();
      }

      clipBtn.setPreferredSize(new Dimension(padSz, padSz));
      clipBtn.setMinimumSize(new Dimension(padSz, padSz));
      clipBtn.setMaximumSize(new Dimension(padSz, padSz));
      clipBtn.setMargin(new Insets(0, 0, 0, 0));

      pads[visibleRow][c] = clipBtn;

      if (viewMode == GridViewMode.CLIP) {
        int engineR = baseTrackId + modelRow;
        double vel = bridge != null ? bridge.getVelocity(engineR, colId) : 0.8;
        double prob = bridge != null ? bridge.getStepProbability(engineR, colId) : 1.0;
        clipBtn.setText(
            "<html><font size='3'>Pi:"
                + (modelRow)
                + "<br>Ve:"
                + String.format("%.1f", vel)
                + "<br>Pr:"
                + String.format("%.1f", prob)
                + "<br>Ga:1</font></html>");
      } else if (viewMode == GridViewMode.ARRANGEMENT) {
        String tn = (modelRow < tracks.size()) ? tracks.get(modelRow).getName() : "EMPTY";
        clipBtn.setText(
            "<html><center><font size='3'>"
                + tn
                + "<br><b>Bar "
                + (c + 1)
                + "</b></font></center></html>");
      } else {
        if (modelRow < tracks.size() && c < tracks.get(modelRow).getClips().size()) {
          clipBtn.setText(
              "<html><center><font size='3'>"
                  + tracks.get(modelRow).getClips().get(c).getName()
                  + "</font></center></html>");
        } else {
          clipBtn.setText("PAD " + (c + 1));
        }
      }

      boolean hasClip = false;
      if (modelRow < tracks.size()) {
        org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);
        if (c < track.getClips().size()) {
          hasClip = true;
        }
      }

      // In CLIP mode, wrap extra columns beyond clip length back to beginning
      final int activeCol;
      if (viewMode == GridViewMode.CLIP) {
        int trackLen = 0;
        if (modelRow < tracks.size()) {
          org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);
          if (activeClipId < track.getClips().size()) {
            trackLen = track.getClips().get(activeClipId).getStepCount();
          }
        }
        if (trackLen <= 0)
          trackLen = bridge != null ? bridge.getTrackLength(baseTrackId + modelRow) : stepCount;
        if (trackLen > 0 && trackLen < stepCount) {
          activeCol = colId % trackLen; // wrap when clip is shorter than viewport
        } else if (trackLen > stepCount) {
          activeCol =
              Math.min(
                  colId + scrollOffsetX, trackLen - 1); // scroll when clip is wider than viewport
        } else {
          activeCol = colId;
        }
      } else {
        activeCol = colId;
      }

      if (colId == columnCount - 2) {
        boolean isSynthTrack = bridge != null && bridge.getTrackType(baseTrackId) == 1;
        if (viewMode == GridViewMode.CLIP && isSynthTrack) {
          // Column 17 is not applicable for Synth rows! Black it out!
          if (clipBtn instanceof DelugePadButton pad) {
            pad.setText("");
            pad.setNoteText("");
            pad.setActive(false);
            pad.setBaseColor(Color.BLACK);
            pad.setIntensity(0.0f);
          } else {
            clipBtn.setText("");
            clipBtn.setBackground(Color.BLACK);
            clipBtn.setEnabled(false);
          }
          // Do not add any action listener!
        } else {
          final int engineRow = baseTrackId + modelRow;
          boolean isMuted = bridge.getMute(engineRow);
          if (clipBtn instanceof DelugePadButton pad) {
            pad.setText("");
            pad.setNoteText("");
            pad.setActive(true);
            pad.setBaseColor(isMuted ? Color.RED : new Color(0x2a, 0x10, 0x10));
            pad.setIntensity(isMuted ? 1.0f : 0.4f);
          } else {
            clipBtn.setText("MUTE");
            clipBtn.setBackground(isMuted ? Color.RED : new Color(0x33, 0x33, 0x33));
            clipBtn.setEnabled(true);
          }
          clipBtn.addActionListener(
              e -> {
                if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                  java.util.ArrayList<Consequence> steps = new java.util.ArrayList<>();
                  for (int s = 0; s < stepCount; s++) {
                    boolean wasOn = bridge.getStep(engineRow, s);
                    if (wasOn) {
                      double v = bridge.getVelocity(engineRow, s);
                      steps.add(
                          new Consequence.StepConsequence(
                              editedModelTrack,
                              activeClipId,
                              modelRow,
                              s,
                              org.chuck.deluge.model.StepData.of(
                                  true,
                                  (float) v,
                                  org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                                  1.0f,
                                  0),
                              org.chuck.deluge.model.StepData.empty()));
                    }
                    bridge.setStep(engineRow, s, false);
                  }
                  if (!steps.isEmpty() && projectModel != null) {
                    projectModel
                        .getUndoRedoStack()
                        .push(
                            new Consequence.CompoundConsequence(
                                "Clear row " + (modelRow + 1), steps));
                  }
                  refresh();
                  return;
                }
                boolean nextMute = !bridge.getMute(engineRow);
                bridge.setMute(engineRow, nextMute);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(nextMute ? Color.RED : new Color(0x2a, 0x10, 0x10));
                  pad.setIntensity(nextMute ? 1.0f : 0.4f);
                } else {
                  clipBtn.setBackground(nextMute ? Color.RED : new Color(0x33, 0x33, 0x33));
                }
                if (SwingDelugeApp.mainInstance != null) {
                  SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                      "MUT ", (nextMute ? "ON  " : "OFF ") + "T" + (modelRow + 1));
                }
              });
        }
      } else if (colId == columnCount - 1) {
        if (viewMode == GridViewMode.CLIP) {
          // ── Clip View Audition Play Mode ──
          if (clipBtn instanceof DelugePadButton pad) {
            pad.setText("");
            pad.setNoteText("");
            pad.setActive(true);
            pad.setBaseColor(new Color(0x10, 0x2a, 0x10));
            pad.setIntensity(0.4f);
          } else {
            clipBtn.setText("PLAY");
            clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
          }

          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                private boolean isPressed = false;

                private void startAudition() {
                  if (isPressed) return;
                  isPressed = true;

                  boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
                  int pitchMidi = isSynthMode ? (((24 - 1) - modelRow) + 60) : 60;

                  // ── Update LED Display ──
                  if (SwingDelugeApp.mainInstance != null) {
                    String labelStr = "DRUM";
                    if (!isSynthMode) {
                      if (projectModel != null
                          && editedModelTrack < projectModel.getTracks().size()) {
                        org.chuck.deluge.model.TrackModel rowTrack =
                            projectModel.getTracks().get(editedModelTrack);
                        if (rowTrack instanceof org.chuck.deluge.model.KitTrackModel kit) {
                          java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
                          if (modelRow < sounds.size()) {
                            labelStr = sounds.get(sounds.size() - 1 - modelRow).getName();
                          }
                        }
                      }
                    } else {
                      labelStr = getNoteName(pitchMidi);
                    }
                    SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("AUD ", labelStr);
                  }

                  // ── Play Note ──
                  if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
                    Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                    if (fwEngineObj
                        instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
                      if (editedModelTrack < fwEngine.sounds.size()) {
                        org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                            fwEngine.sounds.get(editedModelTrack);
                        if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
                          if (modelRow < kit.drumSounds.size()) {
                            kit.triggerDrum(modelRow, 127);
                          }
                        } else if (sound
                            instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
                          synth.triggerNote(pitchMidi, 127);
                        }
                      }
                    }
                  } else {
                    vm.setGlobalInt(
                        BridgeContract.G_PREVIEW_TRACK, (long) (baseTrackId + modelRow));
                    if (isSynthMode) {
                      vm.setGlobalFloat(BridgeContract.G_PREVIEW_PITCH, (float) (pitchMidi - 60));
                    }
                    vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                  }

                  clipBtn.setBackground(Color.WHITE);
                }

                private void stopAudition() {
                  if (!isPressed) return;
                  isPressed = false;

                  boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
                  int pitchMidi = isSynthMode ? (((24 - 1) - modelRow) + 60) : 60;

                  if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
                    Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                    if (fwEngineObj
                        instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
                      if (editedModelTrack < fwEngine.sounds.size()) {
                        org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                            fwEngine.sounds.get(editedModelTrack);
                        if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
                          if (modelRow < kit.drumSounds.size()) {
                            kit.drumSounds.get(modelRow).releaseNote(60);
                          }
                        } else if (sound
                            instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
                          synth.releaseNote(pitchMidi);
                        }
                      }
                    }
                  }

                  if (clipBtn instanceof DelugePadButton pad) {
                    pad.setBackground(new Color(0x10, 0x2a, 0x10));
                  } else {
                    clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                  }
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                  if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                    startAudition();
                  }
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                  stopAudition();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                  stopAudition();
                }
              });
        } else {
          // ── Song View / Arrangement View ──
          boolean isSoloed = (soloRow == modelRow);
          if (clipBtn instanceof DelugePadButton pad) {
            pad.setText("");
            pad.setNoteText("");
            pad.setActive(true);
            pad.setBaseColor(isSoloed ? Color.GREEN : new Color(0x10, 0x2a, 0x10));
            pad.setIntensity(isSoloed ? 1.0f : 0.4f);
          } else {
            clipBtn.setText("SOLO");
            clipBtn.setBackground(isSoloed ? Color.GREEN : new Color(0x33, 0x33, 0x33));
          }

          clipBtn.addActionListener(
              e -> {
                if (viewMode == GridViewMode.SONG) {
                  if (soloRow == modelRow) {
                    soloRow = -1;
                    for (int i = 0; i < voiceRowCount; i++) {
                      bridge.setMute(baseTrackId + i, false);
                    }
                    if (SwingDelugeApp.mainInstance != null) {
                      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("SOLO", "OFF");
                    }
                  } else {
                    soloRow = modelRow;
                    for (int i = 0; i < voiceRowCount; i++) {
                      bridge.setMute(baseTrackId + i, i != modelRow);
                    }
                    if (SwingDelugeApp.mainInstance != null) {
                      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                          "SOLO", "T" + (modelRow + 1));
                    }
                  }
                  refresh();
                } else if (viewMode == GridViewMode.ARRANGEMENT) {
                  if (onEditRequest != null) {
                    onEditRequest.accept(modelRow, 0);
                  }
                }
              });
        }
      } else {
        if (clipBtn instanceof DelugePadButton pad) {
          pad.setApplicable(true);
          if (shiftHeld && visibleRow < 8 && colId < 16) {
            org.chuck.deluge.model.TrackModel genericTrack =
                (editedModelTrack < tracks.size()) ? tracks.get(editedModelTrack) : null;
            boolean applicable = true;
            if (genericTrack != null) {
              String param = SHIFT_LABELS[visibleRow][colId];
              applicable = isParamApplicable(param, visibleRow, colId, genericTrack);
            }
            pad.setApplicable(applicable);
            String tooltipText =
                getShiftShortcutTooltip(visibleRow, colId, applicable, genericTrack);
            pad.setToolTipText(tooltipText);
            pad.setInLoop(true);
            pad.setActive(true);
            pad.setBaseColor(SHIFT_COLORS[visibleRow][colId]);
            String prefix = getGroupPrefix(colId);
            String shortcutLabel = SHIFT_LABELS[visibleRow][colId];
            String noteText =
                (prefix != null && !shortcutLabel.isEmpty())
                    ? prefix + "\n" + shortcutLabel.replace(" ", "\n")
                    : shortcutLabel.replace(" ", "\n");
            pad.setNoteText(noteText);
            pad.setMuted(false);
            pad.setIntensity(1.0f);
            pad.setPlayhead(false);
            pad.setTied(false);
            pad.setText("");
            if (applicable && visibleRow == activeShiftRow && colId == activeShiftCol) {
              pad.setBorder(BorderFactory.createLineBorder(new Color(255, 215, 0), 3));
            } else {
              pad.setBorder(UIManager.getBorder("Button.border"));
            }
          } else {
            int engineRow = baseTrackId + modelRow;
            boolean isMuted = bridge != null && bridge.getMute(engineRow);
            pad.setMuted(isMuted);

            if (viewMode == GridViewMode.CLIP) {
              boolean stepState = bridge.getStep(engineRow, activeCol);
              double vel = bridge != null ? bridge.getVelocity(engineRow, activeCol) : 0.8;
              double prob = bridge != null ? bridge.getStepProbability(engineRow, activeCol) : 1.0;

              int curTrackLen = bridge != null ? bridge.getTrackLength(engineRow) : stepCount;
              boolean inLoop = activeCol < curTrackLen;
              pad.setInLoop(inLoop);
              pad.setActive(stepState);
              pad.setBaseColor(trackColors[visibleRow % trackColors.length]);
              pad.setIntensity((float) (vel * (0.2f + 0.8f * prob)));

              boolean isSelected = selectedCells.contains(modelRow + "," + activeCol);
              if (isDragSelecting) {
                int minR = Math.min(dragSelStartRow, dragSelCurrRow);
                int maxR = Math.max(dragSelStartRow, dragSelCurrRow);
                int minC = Math.min(dragSelStartCol, dragSelCurrCol);
                int maxC = Math.max(dragSelStartCol, dragSelCurrCol);
                if (visibleRow >= minR
                    && visibleRow <= maxR
                    && colId >= minC
                    && colId <= maxC
                    && colId < 16) {
                  isSelected = true;
                }
              }
              pad.setSelected(isSelected);

              boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
              int pitchMidi = isSynthMode ? (((24 - 1) - modelRow) + 60) : 60;
              if (stepState) {
                if (isSynthMode) {
                  pad.setNoteText(getNoteName(pitchMidi));
                } else {
                  pad.setNoteText(String.format("v%d", (int) (vel * 100)));
                }
                pad.setToolTipText(
                    String.format(
                        "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                            + "<b>Step %d Event</b><br>"
                            + "• Pitch: %s (MIDI %d)<br>"
                            + "• Velocity: %d%%<br>"
                            + "• Probability: %d%%<br>"
                            + "• Click to toggle OFF"
                            + "</body></html>",
                        activeCol + 1,
                        getNoteName(pitchMidi),
                        pitchMidi,
                        (int) (vel * 100),
                        (int) (prob * 100)));
              } else {
                pad.setNoteText("");
                pad.setToolTipText(
                    String.format(
                        "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                            + "<b>Empty Step %d</b><br>"
                            + "• Target Row: %d (MIDI %d)<br>"
                            + "• Click to toggle ON"
                            + "</body></html>",
                        activeCol + 1, modelRow + 1, pitchMidi));
              }
            } else {
              // SONG, ARRANGEMENT
              pad.setActive(hasClip);
              pad.setBaseColor(trackColors[visibleRow % trackColors.length]);
              pad.setIntensity(0.8f);
              if (hasClip) {
                if (modelRow < tracks.size() && c < tracks.get(modelRow).getClips().size()) {
                  org.chuck.deluge.model.TrackModel t = tracks.get(modelRow);
                  pad.setNoteText(t.getClips().get(c).getName());
                  pad.setToolTipText(
                      "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                          + "<b>Track: "
                          + t.getName()
                          + "</b><br>"
                          + "• View Mode: "
                          + viewMode
                          + "<br>"
                          + "• Status: Click to select/edit this clip!"
                          + "</body></html>");
                } else {
                  pad.setNoteText("CLIP");
                  pad.setToolTipText(
                      "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                          + "<b>Session Clip Slot</b><br>"
                          + "• View Mode: "
                          + viewMode
                          + "<br>"
                          + "• Status: Active clip populated!"
                          + "</body></html>");
                }
              } else {
                pad.setNoteText("");
                pad.setToolTipText(
                    "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                        + "<b>Empty Session Slot</b><br>"
                        + "• View Mode: "
                        + viewMode
                        + "<br>"
                        + "• Action: Click to create new clip pattern slot!"
                        + "</body></html>");
              }
            }

            if (visibleRow == clonePreviewCurrentRow && colId == clonePreviewCurrentCol) {
              pad.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xf0, 0xff), 3));
            } else {
              pad.setBorder(UIManager.getBorder("Button.border"));
            }
          }
        } else {
          clipBtn.setEnabled(true);
          if (shiftHeld && visibleRow < 8 && colId < 16) {
            org.chuck.deluge.model.TrackModel genericTrack =
                (editedModelTrack < tracks.size()) ? tracks.get(editedModelTrack) : null;
            boolean applicable = true;
            if (genericTrack != null) {
              String param = SHIFT_LABELS[visibleRow][colId];
              applicable = isParamApplicable(param, visibleRow, colId, genericTrack);
            }
            String prefix = getGroupPrefix(colId);
            String shortcutLabel = SHIFT_LABELS[visibleRow][colId];
            String text =
                (prefix != null && !shortcutLabel.isEmpty())
                    ? prefix + "<br>" + shortcutLabel.replace(" ", "<br>")
                    : shortcutLabel.replace(" ", "<br>");
            String tooltipText =
                getShiftShortcutTooltip(visibleRow, colId, applicable, genericTrack);
            clipBtn.setToolTipText(tooltipText);
            if (applicable) {
              clipBtn.setBackground(SHIFT_COLORS[visibleRow][colId]);
              clipBtn.setText(
                  "<html><center><font size='1'><b>" + text + "</b></font></center></html>");
            } else {
              clipBtn.setBackground(new Color(44, 44, 48));
              clipBtn.setText(
                  "<html><center><font size='1' color='#66666e'><b>"
                      + text
                      + "</b></font></center></html>");
              clipBtn.setEnabled(false);
            }
          } else {
            if (viewMode == GridViewMode.CLIP) {
              int engineRow = baseTrackId + modelRow;
              boolean stepState = bridge.getStep(engineRow, activeCol);
              double vel = bridge.getVelocity(engineRow, activeCol);
              double prob = bridge.getStepProbability(engineRow, activeCol);
              clipBtn.setBackground(
                  stepState
                      ? velocityBlend(trackColors[visibleRow % trackColors.length], vel)
                      : new Color(0x33, 0x33, 0x33));
              boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
              int pitchMidi = isSynthMode ? (((24 - 1) - modelRow) + 60) : 60;
              if (stepState) {
                clipBtn.setToolTipText(
                    String.format(
                        "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                            + "<b>Step %d Event</b><br>"
                            + "• Pitch: %s (MIDI %d)<br>"
                            + "• Velocity: %d%%<br>"
                            + "• Probability: %d%%<br>"
                            + "• Click to toggle OFF"
                            + "</body></html>",
                        activeCol + 1,
                        getNoteName(pitchMidi),
                        pitchMidi,
                        (int) (vel * 100),
                        (int) (prob * 100)));
              } else {
                clipBtn.setToolTipText(
                    String.format(
                        "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                            + "<b>Empty Step %d</b><br>"
                            + "• Target Row: %d (MIDI %d)<br>"
                            + "• Click to toggle ON"
                            + "</body></html>",
                        activeCol + 1, modelRow + 1, pitchMidi));
              }
            } else {
              if (hasClip) {
                clipBtn.setBackground(trackColors[visibleRow % trackColors.length]);
                if (modelRow < tracks.size() && c < tracks.get(modelRow).getClips().size()) {
                  org.chuck.deluge.model.TrackModel t = tracks.get(modelRow);
                  clipBtn.setToolTipText(
                      "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                          + "<b>Track: "
                          + t.getName()
                          + "</b><br>"
                          + "• View Mode: "
                          + viewMode
                          + "<br>"
                          + "• Status: Click to select/edit this clip!"
                          + "</body></html>");
                } else {
                  clipBtn.setToolTipText(
                      "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                          + "<b>Session Clip Slot</b><br>"
                          + "• View Mode: "
                          + viewMode
                          + "<br>"
                          + "• Status: Active clip populated!"
                          + "</body></html>");
                }
              } else {
                clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                clipBtn.setToolTipText(
                    "<html><body style='font-size: 9px; font-family: sans-serif;'>"
                        + "<b>Empty Session Slot</b><br>"
                        + "• View Mode: "
                        + viewMode
                        + "<br>"
                        + "• Action: Click to create new clip pattern slot!"
                        + "</body></html>");
              }
            }
          }
        }

        if (null != viewMode) // Click handler
        switch (viewMode) {
            case CLIP:
              if (isAdvanced && colId < columnCount - 2) {
                if (gestureCoordinator == null) {
                  gestureCoordinator =
                      new DelugeGestureCoordinator(this, new DelugeGestureListener());
                }
                java.awt.event.MouseAdapter gestureAdapter =
                    gestureCoordinator.createMouseAdapter(visibleRow, colId);
                clipBtn.addMouseListener(gestureAdapter);
                clipBtn.addMouseMotionListener(gestureAdapter);
              } else {
                clipBtn.addMouseListener(
                    new java.awt.event.MouseAdapter() {
                      @Override
                      public void mousePressed(java.awt.event.MouseEvent e) {
                        if (shiftHeld && visibleRow < 8 && colId < 16) {
                          handleShiftClick(visibleRow, colId, e.getPoint(), e.getComponent());
                          return;
                        }
                        LOG.info(
                            "[grid] mPressed bVR: modelRow="
                                + modelRow
                                + " visRow="
                                + visibleRow
                                + " colId="
                                + colId
                                + " t="
                                + e.getClickCount());
                        // Stop any preview timer from a previous press (button may have been
                        // replaced
                        // by refresh)
                        if (activeStutterTimer != null) {
                          activeStutterTimer.stop();
                          activeStutterTimer = null;
                        }
                        // Also write to debug file for offline inspection
                        try {
                          java.nio.file.Files.write(
                              java.nio.file.Paths.get("grid_debug.log"),
                              ("[grid] mPressed bVR: modelRow="
                                      + modelRow
                                      + " visRow="
                                      + visibleRow
                                      + " colId="
                                      + colId
                                      + " t="
                                      + e.getClickCount()
                                      + "\n")
                                  .getBytes(),
                              java.nio.file.StandardOpenOption.CREATE,
                              java.nio.file.StandardOpenOption.APPEND);
                        } catch (Exception ignored) {
                          LOG.fine("grid_debug.log write failed: " + ignored.getMessage());
                        }
                        if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                          int engineRow = baseTrackId + modelRow;
                          double curVel = bridge.getVelocity(engineRow, activeCol);
                          int curIt = bridge.getIterance(engineRow, activeCol);
                          int curFill = (int) (bridge.getStepFill(engineRow, activeCol) * 100);
                          StepPropertiesDialog dlg =
                              new StepPropertiesDialog(
                                  (Frame)
                                      javax.swing.SwingUtilities.getWindowAncestor(
                                          SwingGridPanel.this),
                                  (int) (curVel * 100),
                                  curIt,
                                  curFill);
                          dlg.setVisible(true);
                          if (dlg.isConfirmed()) {
                            int newVel = dlg.getVelocity();
                            int newIt = dlg.getIterance();
                            int newFill = dlg.getFill();
                            if (newVel != (int) (curVel * 100)
                                || newIt != curIt
                                || newFill != curFill) {
                              org.chuck.deluge.model.StepData oldStep = null;
                              if (projectModel != null
                                  && editedModelTrack < projectModel.getTracks().size()) {
                                org.chuck.deluge.model.TrackModel tModel =
                                    projectModel.getTracks().get(editedModelTrack);
                                if (activeClipId < tModel.getClips().size()) {
                                  oldStep =
                                      tModel
                                          .getClips()
                                          .get(activeClipId)
                                          .getStep(modelRow, activeCol);
                                }
                              }
                              bridge.setVelocity(engineRow, activeCol, newVel / 100.0);
                              bridge.setIterance(engineRow, activeCol, newIt);
                              bridge.setStepFill(engineRow, activeCol, newFill / 100.0);
                              if (projectModel != null
                                  && editedModelTrack < projectModel.getTracks().size()) {
                                org.chuck.deluge.model.TrackModel tModel =
                                    projectModel.getTracks().get(editedModelTrack);
                                if (activeClipId < tModel.getClips().size()) {
                                  org.chuck.deluge.model.ClipModel cModel =
                                      tModel.getClips().get(activeClipId);
                                  boolean st = bridge.getStep(engineRow, activeCol);
                                  double prob = bridge.getStepProbability(engineRow, activeCol);
                                  cModel.setStep(
                                      modelRow,
                                      activeCol,
                                      new org.chuck.deluge.model.StepData(
                                          st,
                                          newVel / 100.0f,
                                          0.5f,
                                          (float) prob,
                                          0,
                                          newIt,
                                          newFill / 100.0f));
                                  if (oldStep != null) {
                                    projectModel
                                        .getUndoRedoStack()
                                        .push(
                                            new Consequence.StepConsequence(
                                                editedModelTrack,
                                                activeClipId,
                                                modelRow,
                                                activeCol,
                                                oldStep,
                                                cModel.getStep(modelRow, activeCol)));
                                  }
                                }
                              }
                              refresh();
                            }
                          }
                        } else if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                          boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
                          int trackType = bridge.getTrackType(modelRow);

                          if (trackType == 2) {
                            // MIDI track
                            org.chuck.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.chuck.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                oldStep =
                                    tModel
                                        .getClips()
                                        .get(activeClipId)
                                        .getStep(modelRow, activeCol);
                              }
                            }
                            boolean st = bridge.getStep(baseTrackId + modelRow, activeCol);
                            bridge.setStep(baseTrackId + modelRow, activeCol, !st);
                            if (!st) {
                              if (finalMidiOut != null) {
                                try {
                                  finalMidiOut.sendMessage(
                                      new byte[] {(byte) 0x90, (byte) (60 + modelRow), (byte) 100});
                                } catch (Exception ex) {
                                  LOG.warning("MIDI send failed (synth grid): " + ex.getMessage());
                                }
                              }
                            }
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.chuck.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.chuck.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                double curVel =
                                    bridge.getVelocity(baseTrackId + modelRow, activeCol);
                                double curProb =
                                    bridge.getStepProbability(baseTrackId + modelRow, activeCol);
                                cModel.setStep(
                                    modelRow,
                                    activeCol,
                                    org.chuck.deluge.model.StepData.of(
                                        !st,
                                        (float) curVel,
                                        org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                                        (float) curProb,
                                        0));
                                if (oldStep != null) {
                                  projectModel
                                      .getUndoRedoStack()
                                      .push(
                                          new Consequence.StepConsequence(
                                              editedModelTrack,
                                              activeClipId,
                                              modelRow,
                                              activeCol,
                                              oldStep,
                                              cModel.getStep(modelRow, activeCol)));
                                }
                              }
                            }
                            fireProjectChanged();
                          } else if (isSynthMode) {
                            // Synth piano roll: each row = MIDI note, higher row = lower pitch.
                            // Use unique engine row per visual row for independent bridge state.
                            int engineRow = baseTrackId + modelRow;
                            // Base pitch: row 0 = highest (MIDI 83), each step down = 1 semitone
                            // Continues descending: modelRow=8 -> MIDI 75, modelRow=15 -> MIDI 68
                            int pitchMidi = ((24 - 1) - modelRow) + 60;
                            org.chuck.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.chuck.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                oldStep =
                                    tModel
                                        .getClips()
                                        .get(activeClipId)
                                        .getStep(modelRow, activeCol);
                              }
                            }
                            boolean stepState = bridge.getStep(engineRow, activeCol);
                            bridge.setStep(engineRow, activeCol, !stepState);
                            double velS = bridge.getVelocity(engineRow, activeCol);
                            clipBtn.setBackground(
                                !stepState
                                    ? velocityBlend(
                                        trackColors[visibleRow % trackColors.length], velS)
                                    : new Color(0x33, 0x33, 0x33));

                            if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
                              Object fwEngineObj =
                                  vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                              if (fwEngineObj
                                  instanceof
                                  org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
                                if (editedModelTrack < fwEngine.sounds.size()) {
                                  org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                                      fwEngine.sounds.get(editedModelTrack);
                                  if (sound
                                      instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
                                    kit.triggerDrum(modelRow, 127);
                                  } else if (sound
                                      instanceof
                                      org.chuck.deluge.firmware.engine.FirmwareSound synth) {
                                    synth.triggerNote(pitchMidi, 127);
                                  }
                                }
                              }
                            } else {
                              // Preview voice: wrap to first POW (8) engine rows since
                              // synth_preview_shred checks r < car.length (8 UGen voices)
                              int voiceSlot = baseTrackId + (modelRow % 8);
                              vm.setGlobalFloat(
                                  BridgeContract.G_PREVIEW_PITCH, (float) (pitchMidi - 60));
                              vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) voiceSlot);
                              vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                            }
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.chuck.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.chuck.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                double curVel = bridge.getVelocity(engineRow, activeCol);
                                double curProb = bridge.getStepProbability(engineRow, activeCol);
                                cModel.setStep(
                                    modelRow,
                                    activeCol,
                                    org.chuck.deluge.model.StepData.of(
                                        !stepState,
                                        (float) curVel,
                                        org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                                        (float) curProb,
                                        pitchMidi));
                                cModel.setRawNoteEvents(modelRow, null);
                                if (oldStep != null) {
                                  projectModel
                                      .getUndoRedoStack()
                                      .push(
                                          new Consequence.StepConsequence(
                                              editedModelTrack,
                                              activeClipId,
                                              modelRow,
                                              activeCol,
                                              oldStep,
                                              cModel.getStep(modelRow, activeCol)));
                                }
                              }
                            }
                            fireProjectChanged();
                          } else {
                            // Kit track
                            org.chuck.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.chuck.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                oldStep =
                                    tModel
                                        .getClips()
                                        .get(activeClipId)
                                        .getStep(modelRow, activeCol);
                              }
                            }
                            boolean stepState = bridge.getStep(baseTrackId + modelRow, activeCol);
                            bridge.setStep(baseTrackId + modelRow, activeCol, !stepState);
                            double velK = bridge.getVelocity(baseTrackId + modelRow, activeCol);
                            clipBtn.setBackground(
                                !stepState
                                    ? velocityBlend(
                                        trackColors[visibleRow % trackColors.length], velK)
                                    : new Color(0x33, 0x33, 0x33));
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.chuck.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.chuck.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                double curVel =
                                    bridge.getVelocity(baseTrackId + modelRow, activeCol);
                                double curProb =
                                    bridge.getStepProbability(baseTrackId + modelRow, activeCol);
                                cModel.setStep(
                                    modelRow,
                                    activeCol,
                                    org.chuck.deluge.model.StepData.of(
                                        !stepState,
                                        (float) curVel,
                                        org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                                        (float) curProb,
                                        0));
                                if (oldStep != null) {
                                  projectModel
                                      .getUndoRedoStack()
                                      .push(
                                          new Consequence.StepConsequence(
                                              editedModelTrack,
                                              activeClipId,
                                              modelRow,
                                              activeCol,
                                              oldStep,
                                              cModel.getStep(modelRow, activeCol)));
                                }
                              }
                            }
                            // Stop any previous preview timer before refresh (refresh replaces
                            // buttons)
                            if (activeStutterTimer != null) {
                              activeStutterTimer.stop();
                              activeStutterTimer = null;
                            }
                            if (!stepState) {
                              if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
                                // ── High-Fidelity Audition ──
                                Object fwEngineObj =
                                    vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                                if (fwEngineObj
                                    instanceof
                                    org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
                                  if (editedModelTrack < fwEngine.sounds.size()) {
                                    org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                                        fwEngine.sounds.get(editedModelTrack);
                                    if (sound
                                        instanceof
                                        org.chuck.deluge.firmware.engine.FirmwareKit kit) {
                                      kit.triggerDrum(modelRow, 127);
                                    } else if (sound
                                        instanceof
                                        org.chuck.deluge.firmware.engine.FirmwareSound synth) {
                                      int pitchMidi = ((24 - 1) - modelRow) + 60;
                                      synth.triggerNote(pitchMidi, 127);
                                    }
                                  }
                                }
                              } else {
                                // Single preview trigger — click a cell, play the sound once.
                                // The engine reads G_PREVIEW_TRACK on wake and re-triggers.
                                vm.setGlobalInt(
                                    BridgeContract.G_PREVIEW_TRACK,
                                    (long) (baseTrackId + modelRow));
                                vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                              }
                            }
                            fireProjectChanged();
                          }
                        }
                      }

                      @Override
                      public void mouseReleased(java.awt.event.MouseEvent e) {
                        if (activeStutterTimer != null) {
                          activeStutterTimer.stop();
                          activeStutterTimer = null;
                        }

                        // ── High-Fidelity Note Off ──
                        if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
                          Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                          if (fwEngineObj
                              instanceof
                              org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
                            if (editedModelTrack < fwEngine.sounds.size()) {
                              org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                                  fwEngine.sounds.get(editedModelTrack);
                              if (sound
                                  instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
                                if (modelRow < kit.drumSounds.size()) {
                                  kit.drumSounds.get(modelRow).releaseNote(60);
                                }
                              } else if (sound
                                  instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
                                int pitchMidi = ((24 - 1) - modelRow) + 60;
                                synth.releaseNote(pitchMidi);
                              }
                            }
                          }
                        }
                      }

                      @Override
                      public void mouseExited(java.awt.event.MouseEvent e) {
                        if (activeStutterTimer != null) {
                          activeStutterTimer.stop();
                          activeStutterTimer = null;
                        }

                        // ── High-Fidelity Note Off ──
                        if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
                          Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                          if (fwEngineObj
                              instanceof
                              org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
                            if (editedModelTrack < fwEngine.sounds.size()) {
                              org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                                  fwEngine.sounds.get(editedModelTrack);
                              if (sound
                                  instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
                                if (modelRow < kit.drumSounds.size()) {
                                  kit.drumSounds.get(modelRow).releaseNote(60);
                                }
                              } else if (sound
                                  instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
                                int pitchMidi = ((24 - 1) - modelRow) + 60;
                                synth.releaseNote(pitchMidi);
                              }
                            }
                          }
                        }
                      }
                    });
              }
              break;
            case SONG:
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        new TrackInspectorDialog(
                                (Frame)
                                    javax.swing.SwingUtilities.getWindowAncestor(
                                        SwingGridPanel.this),
                                modelRow,
                                tracks,
                                SwingGridPanel.this::refresh)
                            .setVisible(true);
                      }
                    }
                  });
              break;
            case ARRANGEMENT:
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        new BarAutomationDialog(
                                (Frame)
                                    javax.swing.SwingUtilities.getWindowAncestor(
                                        SwingGridPanel.this),
                                colId)
                            .setVisible(true);
                      }
                    }
                  });
              break;
            default:
              break;
          }

        if (viewMode == GridViewMode.SONG && modelRow < tracks.size() && colId < 16) {
          final int clipCol = colId;
          final int trkIdx = modelRow;
          final org.chuck.deluge.model.TrackModel songTrack = tracks.get(modelRow);
          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                  if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                    if (clipCol < songTrack.getClips().size()) {
                      showClipContextMenu(clipBtn, e.getX(), e.getY(), songTrack, clipCol, trkIdx);
                    }
                  } else {
                    if (clipCol >= songTrack.getClips().size()) {
                      String name = "CLIP " + (clipCol + 1);
                      songTrack.addClip(
                          new org.chuck.deluge.model.ClipModel(
                              name,
                              songTrack.getClips().isEmpty()
                                  ? 8
                                  : songTrack.getClips().get(0).getRowCount(),
                              16));
                      fireProjectChanged();
                    }
                  }
                }
              });
        }

        clipBtn.addActionListener(
            e -> {
              if (viewMode == GridViewMode.SONG) {
                if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) return;
                if (colId < 16 && modelRow < tracks.size()) {
                  bridge.setLaunchQueue(modelRow, colId);
                }
                clipBtn.setBackground(new Color(0xff, 0xaa, 0x00)); // amber = queued
                refresh();
              } else if (viewMode == GridViewMode.ARRANGEMENT) {
                if (clipBtn.getBackground().equals(trackColors[visibleRow % trackColors.length])) {
                  clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                } else {
                  clipBtn.setBackground(trackColors[visibleRow % trackColors.length]);
                }
              }
            });
      }

      if (c == columnCount - 2) {
        rowPanel.add(Box.createHorizontalStrut(20));
      }
      rowPanel.add(clipBtn);
      rowPanel.add(Box.createHorizontalStrut(5));
    }

    return rowPanel;
  }

  void triggerKeyboardNote(int note) {
    if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
      try {
        Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
        if (fwEngineObj instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
          if (editedModelTrack < fwEngine.sounds.size()) {
            org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                fwEngine.sounds.get(editedModelTrack);
            if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
              synth.triggerNote(note, 127);
              return;
            } else if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
              if (!kit.drumSounds.isEmpty()) {
                int drumIdx = note % kit.drumSounds.size();
                kit.triggerDrum(drumIdx, 127);
              }
              return;
            }
          }
        }
      } catch (Exception ex) {
        LOG.warning("Hi-Fi keyboard trigger failed: " + ex.getMessage());
      }
    }

    try {
      org.chuck.core.ChuckEvent noteEv =
          (org.chuck.core.ChuckEvent) vm.getGlobalObject("g_ck_noteOn");
      if (noteEv != null) {
        org.chuck.core.ChuckArray pitchArr =
            (org.chuck.core.ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
        if (pitchArr != null) {
          pitchArr.setInt(0, (long) (note - 60));
          noteEv.broadcast();
        }
      }
    } catch (Exception ex) {
      LOG.warning("Standard keyboard trigger failed: " + ex.getMessage());
    }
  }

  void triggerKeyboardNoteRelease(int note) {
    if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
      try {
        Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
        if (fwEngineObj instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
          if (editedModelTrack < fwEngine.sounds.size()) {
            org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                fwEngine.sounds.get(editedModelTrack);
            if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
              synth.releaseNote(note);
              return;
            } else if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
              if (!kit.drumSounds.isEmpty()) {
                int drumIdx = note % kit.drumSounds.size();
                kit.drumSounds.get(drumIdx).releaseNote(60);
              }
              return;
            }
          }
        }
      } catch (Exception ex) {
        LOG.warning("Hi-Fi keyboard release failed: " + ex.getMessage());
      }
    }
  }

  /** Build a fixed row (MACROS, SLIDERS, KEYBOARD) for the CLIP grid. */
  private JPanel buildFixedRow(int rowIdx, int padSz) {
    JPanel rowPanel = new JPanel();
    rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    rowPanel.setPreferredSize(new Dimension(3000, padSz));
    rowPanel.setMinimumSize(new Dimension(3000, padSz));
    rowPanel.setMaximumSize(new Dimension(3000, padSz));

    String trackName;
    if (rowIdx == 8) trackName = "MACROS";
    else if (rowIdx == 9) trackName = "SLIDERS";
    else trackName = "KEYBOARD";

    JLabel label = new JLabel(trackName);
    int lw = Math.max(60, Math.min(140, getWidth() / 12));
    label.setPreferredSize(new Dimension(lw, 30));
    label.setMinimumSize(new Dimension(lw, 30));
    label.setMaximumSize(new Dimension(lw, 30));
    label.setForeground(Color.LIGHT_GRAY);
    rowPanel.add(label);
    rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
    rowPanel.add(Box.createHorizontalStrut(5));

    VUMeterPanel vu = new VUMeterPanel();
    vu.setPreferredSize(new Dimension(12, padSz));
    vu.setMaximumSize(new Dimension(12, padSz));
    rowPanel.add(vu);
    rowPanel.add(Box.createHorizontalStrut(5));

    for (int c = 0; c < columnCount; c++) {
      final int colId = c;
      JButton clipBtn;
      boolean isAdvanced =
          org.chuck.deluge.project.PreferencesManager.getGridPanelType()
              == org.chuck.deluge.project.PreferencesManager.GridPanelType.ADVANCED;

      if (isAdvanced) {
        if (rowIdx == 8) {
          // Combined Macro Sliders!
          if (c < 16) {
            String[] allParams = {
              "LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO",
              "MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"
            };
            clipBtn = new MacroSliderButton(c, allParams[c]);
          } else {
            DelugePadButton pad = new DelugePadButton();
            pad.setEnabled(false);
            clipBtn = pad;
          }
        } else {
          // KEYBOARD row
          if (c < columnCount) {
            int note = 48 + colId;
            boolean isBlack =
                (colId % 12 == 1
                    || colId % 12 == 3
                    || colId % 12 == 6
                    || colId % 12 == 8
                    || colId % 12 == 10);

            DelugePadButton pad = new DelugePadButton();
            pad.setActive(true);
            pad.setBaseColor(isBlack ? new Color(0x18, 0x18, 0x1c) : Color.WHITE);
            pad.setNoteText(String.valueOf(note));
            pad.setFont(new Font("Monospaced", Font.BOLD, 10));

            pad.addActionListener(e -> triggerKeyboardNote(note));
            clipBtn = pad;
          } else {
            DelugePadButton pad = new DelugePadButton();
            pad.setEnabled(false);
            clipBtn = pad;
          }
        }
      } else {
        if (rowIdx == 8) {
          // Combined Macro Sliders for legacy mode too!
          if (c < 16) {
            String[] allParams = {
              "LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO",
              "MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"
            };
            clipBtn = new MacroSliderButton(c, allParams[c]);
          } else {
            clipBtn = new JButton();
            clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
            clipBtn.setEnabled(false);
          }
        } else {
          // KEYBOARD row
          if (c < columnCount) {
            int note = 48 + colId;
            boolean isBlack =
                (colId % 12 == 1
                    || colId % 12 == 3
                    || colId % 12 == 6
                    || colId % 12 == 8
                    || colId % 12 == 10);

            clipBtn = new JButton(String.valueOf(note));
            clipBtn.setBackground(isBlack ? new Color(0x33, 0x33, 0x33) : Color.WHITE);
            clipBtn.setForeground(isBlack ? Color.WHITE : Color.BLACK);
            clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 10));

            clipBtn.addActionListener(e -> triggerKeyboardNote(note));
          } else {
            clipBtn = new JButton();
            clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
            clipBtn.setEnabled(false);
          }
        }
      }

      clipBtn.setPreferredSize(new Dimension(padSz, padSz));
      clipBtn.setMinimumSize(new Dimension(padSz, padSz));
      clipBtn.setMaximumSize(new Dimension(padSz, padSz));
      clipBtn.setMargin(new Insets(0, 0, 0, 0));

      pads[rowIdx][c] = clipBtn;

      if (c == columnCount - 2) {
        rowPanel.add(Box.createHorizontalStrut(20));
      }
      rowPanel.add(clipBtn);
      rowPanel.add(Box.createHorizontalStrut(5));
    }
    return rowPanel;
  }

  /** Flash a pad cell to indicate a note-on event from the isomorphic / QWERTY keyboard. */
  public void flashIsomorphicNote(int note) {
    int r = (note - 60) / 5;
    int c = (note - 60) % 5;
    if (r >= 0 && r < gridMode.rows && c >= 0 && c < 5 && pads[r][c] != null) {
      Color orig = pads[r][c].getBackground();
      pads[r][c].setBackground(Color.WHITE);
      Timer restore = new Timer(150, ev -> pads[r][c].setBackground(orig));
      restore.setRepeats(false);
      restore.start();
    }
  }

  public void updatePlayhead(int step) {
    int trackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
    int rowsToScan = (viewMode == GridViewMode.CLIP) ? voiceRowCount : gridMode.rows;
    boolean isAdvanced =
        org.chuck.deluge.project.PreferencesManager.getGridPanelType()
            == org.chuck.deluge.project.PreferencesManager.GridPanelType.ADVANCED;

    if (step < 0) {
      if (isAdvanced) {
        for (int t = 0; t < rowsToScan; t++) {
          for (int c = 0; c < columnCount; c++) {
            if (pads[t][c] instanceof DelugePadButton pad) {
              pad.setPlayhead(false);
            }
          }
        }
      } else {
        for (int t = 0; t < rowsToScan; t++) {
          for (int c = 0; c < columnCount; c++) {
            if (pads[t][c] == null) continue;
            int engineRow = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
            int engineStep = (trackLen < stepCount) ? (c % trackLen) : c;
            boolean isTriggered = (bridge != null) && bridge.getStep(engineRow, engineStep);
            if (!isTriggered && pads[t][c].getBackground().equals(Color.WHITE)) {
              double vel = bridge.getVelocity(engineRow, engineStep);
              pads[t][c].setBackground(
                  bridge.getStep(engineRow, engineStep)
                      ? velocityBlend(trackColors[t % trackColors.length], vel)
                      : new Color(0x33, 0x33, 0x33));
            }
          }
        }
      }
      return;
    }

    // Map the engine step to a visual column — for clips wider than viewport, offset by
    // scrollOffsetX
    int stepMod;
    if (trackLen > stepCount) {
      // The engine's current step is in [0, trackLen). Map to visual column by subtracting
      // scrollOffsetX.
      stepMod = step % trackLen - scrollOffsetX;
      if (stepMod < 0) stepMod = 0;
      if (stepMod >= stepCount) stepMod = stepCount - 1;
    } else {
      stepMod = step % stepCount;
    }

    if (isAdvanced) {
      for (int t = 0; t < rowsToScan; t++) {
        for (int c = 0; c < columnCount; c++) {
          if (pads[t][c] instanceof DelugePadButton pad) {
            pad.setPlayhead(c == stepMod);
          }
        }
      }
    } else {
      for (int t = 0; t < rowsToScan; t++) {
        for (int c = 0; c < columnCount; c++) {
          if (pads[t][c] == null) continue;
          int engineRow = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
          int engineStep = (trackLen < stepCount) ? (c % trackLen) : c;
          if (c == stepMod) {
            pads[t][c].setBackground(Color.WHITE);
          } else {
            // Restore past step backgrounds if they were highlighted white and aren't triggered
            boolean isTriggered = (bridge != null) && bridge.getStep(engineRow, engineStep);
            if (!isTriggered && pads[t][c].getBackground().equals(Color.WHITE)) {
              double vel = bridge.getVelocity(engineRow, engineStep);
              pads[t][c].setBackground(
                  bridge.getStep(engineRow, engineStep)
                      ? velocityBlend(trackColors[t % trackColors.length], vel)
                      : new Color(0x33, 0x33, 0x33));
            }
          }
        }
      }
    }
  }

  public void refresh() {
    refreshInProgress = true;
    removeAll();
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();

    voiceRowCount = computeVoiceRowCount();
    LOG.info(
        "REFRESH gridMode="
            + gridMode
            + " voiceRowCount="
            + voiceRowCount
            + " gridMode.rows="
            + gridMode.rows
            + " gridMode.columns="
            + gridMode.columns
            + " columnCount="
            + columnCount
            + " scrollOffset="
            + scrollOffset
            + " viewMode="
            + viewMode);
    // Reset scroll if needed
    int maxOffset = Math.max(0, voiceRowCount - gridMode.rows);
    if (scrollOffset > maxOffset) scrollOffset = maxOffset;

    // Compute dynamic pad size: always fit gridMode.rows × gridMode.columns cells in the viewport
    int padSz = cachedPadSz;

    int savedColCount = columnCount; // saved for SONG/ARRANGEMENT section below

    if (viewMode == GridViewMode.AUTOMATION) {
      // ===== AUTOMATION MODE =====
      // Two sub-modes: OVERVIEW (param status grid) and EDITOR (per-step value band editing)
      voiceRowCount = 8;
      columnCount = stepCount;

      // ── Get active clip (final copy for lambdas) ──
      org.chuck.deluge.model.ClipModel autoClip = null;
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        org.chuck.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
        int acIdx = t.getActiveClipIndex();
        if (acIdx >= 0 && acIdx < t.getClips().size()) {
          autoClip = t.getClips().get(acIdx);
        }
      }
      final org.chuck.deluge.model.ClipModel fAutoClip = autoClip;

      // ── Header bar ──
      JPanel autoHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
      autoHeader.setBackground(new Color(0x15, 0x15, 0x15));
      autoHeader.setMaximumSize(new Dimension(3000, 32));

      JLabel autoLabel = new JLabel("AUTO");
      autoLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
      autoLabel.setForeground(new Color(0x00, 0xff, 0xcc));
      autoHeader.add(autoLabel);

      // Overview/Editor toggle
      JToggleButton overviewToggle = new JToggleButton("OVERVIEW", autoOverviewMode);
      overviewToggle.setFont(new Font("SansSerif", Font.PLAIN, 11));
      overviewToggle.setMargin(new Insets(0, 4, 0, 4));
      overviewToggle.addActionListener(
          e -> {
            autoOverviewMode = overviewToggle.isSelected();
            overviewToggle.setText(autoOverviewMode ? "OVERVIEW" : "EDITOR");
            refresh();
          });
      autoHeader.add(overviewToggle);

      if (!autoOverviewMode) {
        // Editor mode: param combo
        automationParamCombo =
            new javax.swing.JComboBox<>(org.chuck.deluge.model.AutomationParam.SYTH_PARAMS);
        automationParamCombo.setSelectedItem(selectedAutomationParam);
        automationParamCombo.addActionListener(
            e -> {
              String selected = (String) automationParamCombo.getSelectedItem();
              if (selected != null) {
                selectedAutomationParam = selected;
                refresh();
              }
            });
        automationParamCombo.setToolTipText("Select automation parameter");
        autoHeader.add(automationParamCombo);

        JButton interpBtn = new JButton("Interp");
        interpBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        interpBtn.setMargin(new Insets(0, 4, 0, 4));
        interpBtn.setToolTipText("Linear interpolate between automated steps");
        interpBtn.addActionListener(e -> interpolateAutomation(fAutoClip, selectedAutomationParam));
        autoHeader.add(interpBtn);

        JButton clearAutoBtn = new JButton("Clear");
        clearAutoBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        clearAutoBtn.setMargin(new Insets(0, 4, 0, 4));
        clearAutoBtn.addActionListener(
            e -> {
              if (fAutoClip != null) {
                fAutoClip.clearAutomation(selectedAutomationParam);
                refresh();
              }
            });
        autoHeader.add(clearAutoBtn);
      } else {
        // Overview mode: just show track context
        JButton interpAllBtn = new JButton("Interp All");
        interpAllBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        interpAllBtn.setMargin(new Insets(0, 4, 0, 4));
        interpAllBtn.setToolTipText("Interpolate all automated params");
        interpAllBtn.addActionListener(
            e -> {
              if (fAutoClip != null) {
                for (String param : fAutoClip.getAutomatedParams()) {
                  interpolateAutomation(fAutoClip, param);
                }
                refresh();
              }
            });
        autoHeader.add(interpAllBtn);

        JButton clearAllBtn = new JButton("Clear All");
        clearAllBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        clearAllBtn.setMargin(new Insets(0, 4, 0, 4));
        clearAllBtn.addActionListener(
            e -> {
              if (fAutoClip != null) {
                for (String param : fAutoClip.getAutomatedParams()) {
                  fAutoClip.clearAutomation(param);
                }
                refresh();
              }
            });
        autoHeader.add(clearAllBtn);
      }

      // Track context
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        JLabel trackLabel =
            new JLabel("" + projectModel.getTracks().get(editedModelTrack).getName());
        trackLabel.setForeground(Color.LIGHT_GRAY);
        trackLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        autoHeader.add(Box.createHorizontalStrut(10));
        autoHeader.add(trackLabel);
      }

      // Automated param count
      if (autoClip != null) {
        int autoCount = autoClip.getAutomatedParams().size();
        JLabel countLabel = new JLabel(" [" + autoCount + " auto'd]");
        countLabel.setForeground(new Color(0x88, 0xcc, 0x88));
        countLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        autoHeader.add(countLabel);
      }

      add(autoHeader);

      if (autoOverviewMode) {
        // ════════ OVERVIEW GRID ════════
        // Show all params grouped by category with lit/unlit indicator for "has automation"
        buildAutomationOverview(autoClip, padSz);
      } else {
        // ════════ DETAIL EDITOR ════════
        buildAutomationEditor(autoClip, selectedAutomationParam, padSz);
      }
    } else if (viewMode == GridViewMode.CLIP) {
      // ===== CLIP MODE: scrollable voice rows + fixed MACROS/SLIDERS/KEYBOARD =====

      // Section 0: Clip tab bar (shown when track has >1 clip)
      if (editedModelTrack < projectModel.getTracks().size()) {
        org.chuck.deluge.model.TrackModel curTrack = projectModel.getTracks().get(editedModelTrack);
        int clipCount = curTrack.getClips().size();
        if (clipCount > 1) {
          JPanel clipBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
          clipBar.setBackground(new Color(0x10, 0x10, 0x10));
          clipBar.setMaximumSize(new Dimension(3000, 26));
          JLabel clipLabel = new JLabel("Clips:");
          clipLabel.setForeground(Color.LIGHT_GRAY);
          clipLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
          clipBar.add(clipLabel);
          Color trackColor = trackColors[editedModelTrack % trackColors.length];
          for (int ci = 0; ci < clipCount; ci++) {
            org.chuck.deluge.model.ClipModel cm = curTrack.getClips().get(ci);
            String clipName =
                cm.getName() != null && !cm.getName().isBlank() ? cm.getName() : "Clip " + (ci + 1);
            JButton tab = new JButton(clipName);
            tab.setFont(new Font("Monospaced", Font.BOLD, 11));
            tab.setMargin(new Insets(1, 8, 1, 8));
            tab.setFocusPainted(false);
            if (ci == activeClipId) {
              tab.setBackground(trackColor);
              tab.setForeground(Color.BLACK);
              tab.setBorder(BorderFactory.createLineBorder(trackColor.brighter(), 2));
            } else {
              tab.setBackground(new Color(0x33, 0x33, 0x33));
              tab.setForeground(Color.LIGHT_GRAY);
              tab.setBorder(BorderFactory.createLineBorder(new Color(0x55, 0x55, 0x55), 1));
            }
            int clipIdx = ci;
            tab.addActionListener(
                e -> {
                  activeClipId = clipIdx;
                  curTrack.setActiveClipIndex(clipIdx);
                  if (onClipChanged != null) onClipChanged.run();
                  refresh();
                });
            clipBar.add(tab);
          }
          add(clipBar);
        }
      }

      // Section 1: Track info header
      if (editedModelTrack < projectModel.getTracks().size()) {
        org.chuck.deluge.model.TrackModel curTrack = projectModel.getTracks().get(editedModelTrack);
        JPanel headerRow = new JPanel(new BorderLayout(10, 0));
        headerRow.setBackground(new Color(0x15, 0x15, 0x15));
        headerRow.setMaximumSize(new Dimension(3000, 36));
        headerRow.setMinimumSize(new Dimension(100, 36));
        headerRow.setPreferredSize(new Dimension(1200, 36));
        headerRow.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));

        JLabel headerLabel =
            new JLabel(
                "Editing: "
                    + curTrack.getName()
                    + " ("
                    + voiceRowCount
                    + " voices)  ["
                    + gridMode.name().replace('_', ' ')
                    + "]");
        headerLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        headerLabel.setForeground(new Color(0x00, 0xff, 0xcc));
        headerRow.add(headerLabel, BorderLayout.WEST);

        // Helper to style buttons in the controls section
        java.util.function.Consumer<JButton> styleBtn =
            (btn) -> {
              btn.setFocusable(false);
              btn.setFont(new Font("SansSerif", Font.BOLD, 10));
              btn.setForeground(Color.WHITE);
              btn.setBackground(new Color(0x28, 0x28, 0x30));
              btn.setBorder(
                  BorderFactory.createCompoundBorder(
                      BorderFactory.createLineBorder(new Color(0x44, 0x44, 0x4c), 1),
                      BorderFactory.createEmptyBorder(2, 6, 2, 6)));
              btn.setCursor(new java.awt.Cursor(java.awt.Cursor.HAND_CURSOR));
              btn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mouseEntered(java.awt.event.MouseEvent e) {
                      if (btn.isEnabled()) btn.setBackground(new Color(0x38, 0x38, 0x42));
                    }

                    @Override
                    public void mouseExited(java.awt.event.MouseEvent e) {
                      if (btn.isEnabled()) btn.setBackground(new Color(0x28, 0x28, 0x30));
                    }
                  });
            };

        JPanel controlsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        controlsPanel.setOpaque(false);

        // Vertical scroll buttons
        if (voiceRowCount > gridMode.rows) {
          JLabel vLabel = new JLabel("VOICES:");
          vLabel.setForeground(new Color(0x88, 0x88, 0x8f));
          vLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
          controlsPanel.add(vLabel);

          JButton upBtn = new JButton("\u25B2");
          styleBtn.accept(upBtn);
          upBtn.setToolTipText("Scroll up");
          upBtn.setEnabled(scrollOffset > 0);
          upBtn.addActionListener(
              e -> {
                scrollOffset = Math.max(0, scrollOffset - 1);
                refresh();
              });
          controlsPanel.add(upBtn);

          int labelLo = scrollOffset + 1;
          int labelHi = Math.min(scrollOffset + gridMode.rows, voiceRowCount);
          JLabel rowCountLabel = new JLabel(labelLo + "-" + labelHi + " / " + voiceRowCount);
          rowCountLabel.setForeground(Color.LIGHT_GRAY);
          rowCountLabel.setFont(new Font("Monospaced", Font.BOLD, 11));
          controlsPanel.add(rowCountLabel);

          JButton downBtn = new JButton("\u25BC");
          styleBtn.accept(downBtn);
          downBtn.setToolTipText("Scroll down");
          int maxOff = voiceRowCount - gridMode.rows;
          downBtn.setEnabled(scrollOffset < maxOff);
          downBtn.addActionListener(
              e -> {
                scrollOffset = Math.min(maxOff, scrollOffset + 1);
                refresh();
              });
          controlsPanel.add(downBtn);
        } else {
          JLabel rowCountLabel = new JLabel("VOICES: " + voiceRowCount);
          rowCountLabel.setForeground(new Color(0x66, 0x66, 0x6e));
          rowCountLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
          controlsPanel.add(rowCountLabel);
        }

        // Horizontal scroll buttons
        int trackLenH = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
        if (trackLenH > stepCount) {
          controlsPanel.add(Box.createHorizontalStrut(8));
          JSeparator sep = new JSeparator(JSeparator.VERTICAL);
          sep.setPreferredSize(new Dimension(2, 14));
          sep.setForeground(new Color(0x3e, 0x3e, 0x42));
          controlsPanel.add(sep);
          controlsPanel.add(Box.createHorizontalStrut(8));

          JLabel hLabel = new JLabel("STEPS:");
          hLabel.setForeground(new Color(0x88, 0x88, 0x8f));
          hLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
          controlsPanel.add(hLabel);

          JButton leftBtn = new JButton("\u25C0");
          styleBtn.accept(leftBtn);
          leftBtn.setToolTipText("Scroll steps left");
          leftBtn.setEnabled(scrollOffsetX > 0);
          int maxOffX = trackLenH - stepCount;
          leftBtn.addActionListener(
              e -> {
                scrollOffsetX = Math.max(0, scrollOffsetX - 1);
                int maxX =
                    (bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount) - stepCount;
                if (scrollOffsetX > maxX) scrollOffsetX = maxX;
                if (scrollOffsetX < 0) scrollOffsetX = 0;
                refresh();
              });
          controlsPanel.add(leftBtn);

          JLabel stepLabel =
              new JLabel(
                  (scrollOffsetX + 1)
                      + "-"
                      + Math.min(scrollOffsetX + stepCount, trackLenH)
                      + " / "
                      + trackLenH);
          stepLabel.setForeground(Color.LIGHT_GRAY);
          stepLabel.setFont(new Font("Monospaced", Font.BOLD, 11));
          controlsPanel.add(stepLabel);

          JButton rightBtn = new JButton("\u25B6");
          styleBtn.accept(rightBtn);
          rightBtn.setToolTipText("Scroll steps right");
          rightBtn.setEnabled(scrollOffsetX < maxOffX);
          rightBtn.addActionListener(
              e -> {
                scrollOffsetX = Math.min(maxOffX, scrollOffsetX + 1);
                refresh();
              });
          controlsPanel.add(rightBtn);
        }

        headerRow.add(controlsPanel, BorderLayout.EAST);
        add(headerRow);
      }

      // Section 2: Scrollable voice rows — always show gridMode.rows slots in the viewport
      JPanel voicePanel = new JPanel();
      voicePanel.setLayout(new BoxLayout(voicePanel, BoxLayout.Y_AXIS));
      for (int v = 0; v < gridMode.rows; v++) {
        int modelRow = scrollOffset + v;
        if (modelRow >= 0 && modelRow < voiceRowCount) {
          JPanel row = buildVoiceRow(modelRow, v, padSz, tracks);
          voicePanel.add(row);
        } else {
          // Blank filler rows for viewport slots beyond actual voice count
          JPanel blankRow = new JPanel();
          blankRow.setPreferredSize(new Dimension(3000, padSz));
          blankRow.setMaximumSize(new Dimension(3000, padSz));
          blankRow.setBackground(new Color(0x22, 0x22, 0x22));
          voicePanel.add(blankRow);
        }
      }
      // Wrap voice rows in a JScrollPane when content exceeds viewport
      if (voiceRowCount > gridMode.rows) {
        JScrollPane voiceScroll = new JScrollPane(voicePanel);
        voiceScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        voiceScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        voiceScroll.setBorder(BorderFactory.createEmptyBorder());
        // Fix viewport height to show exactly gridMode.rows
        int viewH = gridMode.rows * (padSz + 2);
        voiceScroll.setPreferredSize(new Dimension(3000, viewH));
        voiceScroll.setMaximumSize(new Dimension(3000, viewH));
        add(voiceScroll);
      } else {
        add(voicePanel);
      }

      // Section 3: Fixed rows — MACROS, KEYBOARD (Combined)
      add(buildFixedRow(8, padSz));
      add(buildFixedRow(10, padSz));

    } else {
      // ===== SONG / ARRANGEMENT: gridMode.rows + 3 fixed rows (MACROS/SLIDERS/KEYBOARD) =====
      columnCount = gridMode.columns + 2; // Use grid mode column count + MUTE/SOLO
      int songVoiceRows = gridMode.rows; // always draw full viewport slots

      // ── Section bar (A-Z) for SONG mode ──
      if (viewMode == GridViewMode.SONG) {
        JPanel sectionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        sectionBar.setBackground(new Color(0x15, 0x15, 0x15));
        sectionBar.setMaximumSize(new Dimension(3000, 24));
        JLabel secLabel = new JLabel("SECTION:");
        secLabel.setForeground(Color.LIGHT_GRAY);
        secLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
        sectionBar.add(secLabel);
        java.util.List<SongSection> sections = getProjectModel().getSongSections();
        for (int i = 0; i < 26; i++) {
          String letter = String.valueOf((char) ('A' + i));
          JButton btn = new JButton(letter);
          btn.setFont(new Font("Monospaced", Font.PLAIN, 10));
          btn.setMargin(new Insets(0, 4, 0, 4));
          int sectionIdx = i;
          btn.addActionListener(e -> activateSection(sectionIdx));
          sectionBar.add(btn);
        }
        add(sectionBar);
      }

      for (int t = 0; t < songVoiceRows + 2; t++) {

        JPanel rowPanel = new JPanel();
        rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
        rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
        rowPanel.setPreferredSize(new Dimension(3000, padSz));
        rowPanel.setMinimumSize(new Dimension(3000, padSz));
        rowPanel.setMaximumSize(new Dimension(3000, padSz));

        final int currentTrack = t;
        if (t < tracks.size()) {
          String hex = tracks.get(t).getColourHex();
          if (hex != null && hex.startsWith("0x")) {
            try {
              int rgb = Integer.decode(hex.substring(0, 8)); // strip alpha if 8 chars
              trackColors[t % trackColors.length] = new Color(rgb);
            } catch (Exception e) {
              LOG.warning("Bad color hex for track " + t + ": " + e.getMessage());
            }
          }
        }
        String trackName;
        if (t < songVoiceRows
            && viewMode == GridViewMode.CLIP
            && projectModel != null
            && editedModelTrack < projectModel.getTracks().size()) {
          org.chuck.deluge.model.TrackModel rowTrack =
              projectModel.getTracks().get(editedModelTrack);
          if (rowTrack instanceof org.chuck.deluge.model.KitTrackModel kit) {
            // Kit CLIP rows show the individual sound name
            java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
            trackName =
                (t < sounds.size())
                    ? sounds.get(sounds.size() - 1 - t).getName()
                    : rowTrack.getName();
          } else {
            // Synth CLIP: row 0 shows track name, rows 1-7 show pitch
            trackName = (t == 0) ? rowTrack.getName() : "-" + t + "st";
          }
        } else {
          trackName = (t < tracks.size()) ? tracks.get(t).getName() : "EMPTY " + (t + 1);
        }
        if (t == songVoiceRows) trackName = "MACROS";
        if (t == songVoiceRows + 1) trackName = "KEYBOARD";

        final int trk = currentTrack;
        final String tName = trackName;
        JLabel label = new JLabel(tName);
        int lw = Math.max(60, Math.min(140, getWidth() / 12));
        label.setPreferredSize(new Dimension(lw, 30));
        label.setMinimumSize(new Dimension(lw, 30));
        label.setMaximumSize(new Dimension(lw, 30));

        label.setTransferHandler(
            new TransferHandler() {
              @Override
              public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(
                    java.awt.datatransfer.DataFlavor.javaFileListFlavor);
              }

              @SuppressWarnings("unchecked")
              @Override
              public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                  java.util.List<java.io.File> files =
                      (java.util.List<java.io.File>)
                          support
                              .getTransferable()
                              .getTransferData(java.awt.datatransfer.DataFlavor.javaFileListFlavor);
                  if (files != null && !files.isEmpty()) {
                    java.io.File soundFile = files.get(0);
                    if (soundFile.getName().toLowerCase().endsWith(".wav")
                        || soundFile.getName().toLowerCase().endsWith(".aif")) {
                      hotSwapTrackSample(trk, 0, soundFile);
                      return true;
                    }
                  }
                } catch (Exception ex) {
                  LOG.warning("Sample drop import failed: " + ex.getMessage());
                }
                return false;
              }
            });

        label.setForeground(Color.LIGHT_GRAY);
        label.setFont(new Font("SansSerif", Font.BOLD, 10));
        label.setCursor(new Cursor(Cursor.HAND_CURSOR));
        label.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.isShiftDown()) {
                  isOneShotTrack[trk] = !isOneShotTrack[trk];
                  label.setText(isOneShotTrack[trk] ? tName + " (1SH)" : tName);
                  return;
                }
                if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                  showTrackContextMenu(label, e.getX(), e.getY(), trk);
                  return;
                }
                if (onEditRequest != null) {
                  onEditRequest.accept(trk, 0);
                }
              }
            });

        rowPanel.add(label);

        // ⚙ config button and length badge for real tracks; blank spacer for all others
        if (t < tracks.size() && t < songVoiceRows) {
          org.chuck.deluge.model.TrackModel track = tracks.get(t);

          JButton cfgBtn = new JButton("⚙");
          cfgBtn.setPreferredSize(new Dimension(28, 26));
          cfgBtn.setMinimumSize(new Dimension(28, 26));
          cfgBtn.setMaximumSize(new Dimension(28, 26));
          cfgBtn.setMargin(new Insets(0, 0, 0, 0));
          cfgBtn.setFont(new Font("SansSerif", Font.PLAIN, 12));
          cfgBtn.setBackground(new Color(0x33, 0x33, 0x33));
          cfgBtn.setForeground(new Color(0x00, 0xff, 0xcc));
          cfgBtn.setToolTipText("Configure track");
          cfgBtn.addActionListener(
              e -> {
                Frame owner =
                    (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this);
                if (track instanceof org.chuck.deluge.model.KitTrackModel kitTrack) {
                  new SwingKitConfigDialog(owner, kitTrack, vm, bridge).setVisible(true);
                } else if (track instanceof org.chuck.deluge.model.SynthTrackModel synthTrack) {
                  new SwingSynthConfigDialog(owner, synthTrack, vm, bridge, trk, projectModel)
                      .setVisible(true);
                }
              });
          rowPanel.add(Box.createHorizontalStrut(3));
          rowPanel.add(cfgBtn);

          int stepLen = (bridge != null) ? bridge.getTrackLength(trk) : 16;
          JLabel lenBadge = new JLabel("[" + stepLen + "]");
          lenBadge.setPreferredSize(new Dimension(36, 26));
          lenBadge.setMinimumSize(new Dimension(36, 26));
          lenBadge.setMaximumSize(new Dimension(36, 26));
          lenBadge.setFont(new Font("Monospaced", Font.BOLD, 11));
          lenBadge.setForeground(stepLen == 16 ? Color.GRAY : new Color(0xff, 0xcc, 0x00));
          lenBadge.setToolTipText("Track length (right-click to change)");
          lenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
          lenBadge.addMouseListener(
              new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                  if (javax.swing.SwingUtilities.isRightMouseButton(e) || e.getClickCount() == 2) {
                    String input =
                        JOptionPane.showInputDialog(
                            SwingGridPanel.this, "Track length (1-64):", stepLen);
                    if (input != null) {
                      try {
                        int newLen = Integer.parseInt(input.trim());
                        if (newLen >= 1 && newLen <= BridgeContract.STEPS) {
                          bridge.setTrackLength(trk, newLen);
                          refresh();
                        }
                      } catch (NumberFormatException ignored) {
                      }
                    }
                  }
                }
              });
          rowPanel.add(Box.createHorizontalStrut(2));
          rowPanel.add(lenBadge);
        } else {
          // 3 + 28 + 2 + 36 = 69px spacer to keep columns aligned
          rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
        }

        rowPanel.add(Box.createHorizontalStrut(5));

        VUMeterPanel vu = new VUMeterPanel();
        vu.setPreferredSize(new Dimension(12, padSz));
        vu.setMaximumSize(new Dimension(12, padSz));
        rowPanel.add(vu);
        rowPanel.add(Box.createHorizontalStrut(5));

        Timer vuTimer =
            new Timer(
                33,
                ev -> {
                  vuLevels[trk] *= 0.80; // decay
                  vu.setLvl(vuLevels[trk]);
                });
        vuTimer.start();

        String[] allParams = {
          "LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO",
          "MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"
        };

        for (int c = 0; c < columnCount; c++) {
          final int slot = c;
          final int trkId = t;
          final int colId = c;

          boolean isAdvanced =
              org.chuck.deluge.project.PreferencesManager.getGridPanelType()
                  == org.chuck.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
          JButton clipBtn;
          int macR = songVoiceRows, keyR = songVoiceRows + 1;
          if (trkId == macR && colId < 16) {
            clipBtn = new MacroSliderButton(colId, allParams[colId]);
          } else if (trkId == keyR && colId < 18) {
            if (isAdvanced) {
              DelugePadButton pad = new DelugePadButton();
              pad.putClientProperty("row", t);
              pad.putClientProperty("col", c);
              clipBtn = pad;
            } else {
              clipBtn = new JButton();
            }
          } else {
            if (isAdvanced) {
              DelugePadButton pad = new DelugePadButton();
              pad.putClientProperty("row", t);
              pad.putClientProperty("col", c);
              clipBtn = pad;
            } else {
              clipBtn = new JButton();
            }
          }

          clipBtn.setPreferredSize(new Dimension(padSz, padSz));
          clipBtn.setMinimumSize(new Dimension(padSz, padSz));
          clipBtn.setMaximumSize(new Dimension(padSz, padSz));
          clipBtn.setMargin(new Insets(0, 0, 0, 0));

          pads[t][c] = clipBtn;

          if (t == macR) {
            if (c < 16) {
              // Handled inside MacroSliderButton!
            } else {
              clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
              clipBtn.setEnabled(false);
            }
          } else if (trkId == keyR) {
            if (colId < 18) {
              int note = 48 + colId; // starting at C3
              boolean isBlack =
                  (colId % 12 == 1
                      || colId % 12 == 3
                      || colId % 12 == 6
                      || colId % 12 == 8
                      || colId % 12 == 10);

              clipBtn.setBackground(isBlack ? new Color(0x33, 0x33, 0x33) : Color.WHITE);
              clipBtn.setForeground(isBlack ? Color.WHITE : Color.BLACK);
              clipBtn.setText(String.valueOf(note));
              clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 10));

              clipBtn.addActionListener(e -> triggerKeyboardNote(note));
            }
          } else {

            if (null == viewMode) {
              if (t < tracks.size() && c < tracks.get(t).getClips().size()) {
                clipBtn.setText(
                    "<html><center><font size='3'>"
                        + tracks.get(t).getClips().get(c).getName()
                        + "</font></center></html>");
              } else {
                clipBtn.setText("PAD " + (c + 1));
              }
            } else
              switch (viewMode) {
                case CLIP:
                  double vel = bridge != null ? bridge.getVelocity(baseTrackId + trk, colId) : 0.8;
                  double prob =
                      bridge != null ? bridge.getStepProbability(baseTrackId + trk, colId) : 1.0;
                  clipBtn.setText(
                      "<html><font size='3'>Pi:"
                          + (currentTrack)
                          + "<br>Ve:"
                          + String.format("%.1f", vel)
                          + "<br>Pr:"
                          + String.format("%.1f", prob)
                          + "<br>Ga:1</font></html>");
                  break;
                case ARRANGEMENT:
                  String tn =
                      (currentTrack < tracks.size()) ? tracks.get(currentTrack).getName() : "EMPTY";
                  clipBtn.setText(
                      "<html><center><font size='3'>"
                          + tn
                          + "<br><b>Bar "
                          + (c + 1)
                          + "</b></font></center></html>");
                  break;
                default:
                  if (t < tracks.size() && c < tracks.get(t).getClips().size()) {
                    clipBtn.setText(
                        "<html><center><font size='3'>"
                            + tracks.get(t).getClips().get(c).getName()
                            + "</font></center></html>");
                  } else {
                    clipBtn.setText("PAD " + (c + 1));
                  }
                  break;
              }
          }

          boolean hasClip = false;
          if (t < tracks.size()) {
            org.chuck.deluge.model.TrackModel track = tracks.get(t);
            if (c < track.getClips().size()) {
              hasClip = true;
            }
          }

          if (colId == 16) {
            final int engineRow = baseTrackId + trk;
            clipBtn.setText("MUTE");
            clipBtn.setBackground(
                bridge.getMute(engineRow) ? Color.RED : new Color(0x33, 0x33, 0x33));
            clipBtn.addActionListener(
                e -> {
                  if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                    // Clear Sequence row
                    java.util.ArrayList<Consequence> steps = new java.util.ArrayList<>();
                    for (int s = 0; s < stepCount; s++) {
                      boolean wasOn = bridge.getStep(engineRow, s);
                      if (wasOn) {
                        double v = bridge.getVelocity(engineRow, s);
                        steps.add(
                            new Consequence.StepConsequence(
                                editedModelTrack,
                                activeClipId,
                                trk,
                                s,
                                org.chuck.deluge.model.StepData.of(
                                    true,
                                    (float) v,
                                    org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                                    1.0f,
                                    0),
                                org.chuck.deluge.model.StepData.empty()));
                      }
                      bridge.setStep(engineRow, s, false);
                    }
                    if (!steps.isEmpty() && projectModel != null) {
                      projectModel
                          .getUndoRedoStack()
                          .push(
                              new Consequence.CompoundConsequence("Clear row " + (trk + 1), steps));
                    }
                    refresh();
                    return;
                  }
                  boolean isMuted = bridge.getMute(engineRow);
                  bridge.setMute(engineRow, !isMuted);
                  clipBtn.setBackground(!isMuted ? Color.RED : new Color(0x33, 0x33, 0x33));
                });
          } else if (colId == columnCount - 1) {
            clipBtn.setText("SOLO");
            clipBtn.setBackground(soloRow == trk ? Color.GREEN : new Color(0x33, 0x33, 0x33));

            clipBtn.addActionListener(
                e -> {
                  if (viewMode == GridViewMode.CLIP) {
                    // Audition the row sound immediately
                    vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) trk);
                    vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);

                    // Toggle solo: solo this row or clear solo
                    if (soloRow == trk) {
                      soloRow = -1;
                      // Unmute all rows
                      for (int i = 0; i < 11; i++) bridge.setMute(baseTrackId + i, false);
                    } else {
                      soloRow = trk;
                      // Mute all other rows, unmute this one
                      for (int i = 0; i < 11; i++) {
                        bridge.setMute(baseTrackId + i, i != trk);
                      }
                    }
                    refresh();
                    return;
                  }
                  if (onEditRequest != null) {
                    onEditRequest.accept(trk, 0);
                  }
                });
          } else {
            if (viewMode == GridViewMode.CLIP) {
              boolean stepState = bridge.getStep(baseTrackId + trk, c);
              double vel = bridge.getVelocity(baseTrackId + trk, c);
              clipBtn.setBackground(
                  stepState
                      ? velocityBlend(trackColors[currentTrack], vel)
                      : new Color(0x33, 0x33, 0x33));
            } else if (viewMode == GridViewMode.SONG && t < tracks.size() && colId < 16) {
              // SONG visual states: loop-green, playing (track color), queued (amber), stopped
              // (dark), empty (very dark)
              long launchQ = bridge != null ? bridge.getLaunchQueue(t) : -1L;
              long currentClip = bridge != null ? bridge.getCurrentClip(t) : 0L;
              if (launchQ == colId) {
                clipBtn.setBackground(new Color(0xff, 0xaa, 0x00)); // amber = queued
              } else if (currentClip == colId
                  && bridge != null
                  && bridge.getClipPlayMode(t, colId) == 1) {
                clipBtn.setBackground(new Color(0x00, 0xcc, 0x00)); // green = LOOP mode
              } else if (currentClip == colId) {
                clipBtn.setBackground(trackColors[t % trackColors.length]); // playing
              } else if (hasClip) {
                clipBtn.setBackground(new Color(0x33, 0x44, 0x55)); // stopped
              } else {
                clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a)); // empty
              }
            } else {
              if (hasClip) {
                clipBtn.setBackground(trackColors[currentTrack]);
              } else {
                clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
              }
            }

            if (viewMode == GridViewMode.CLIP) {
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        int engineRow = baseTrackId + trk;
                        double curVel = bridge.getVelocity(engineRow, colId);
                        int curIt = bridge.getIterance(engineRow, colId);
                        int curFill = (int) (bridge.getStepFill(engineRow, colId) * 100);
                        StepPropertiesDialog dlg =
                            new StepPropertiesDialog(
                                (Frame)
                                    javax.swing.SwingUtilities.getWindowAncestor(
                                        SwingGridPanel.this),
                                (int) (curVel * 100),
                                curIt,
                                curFill);
                        dlg.setVisible(true);
                        if (dlg.isConfirmed()) {
                          int newVel = dlg.getVelocity();
                          int newIt = dlg.getIterance();
                          int newFill = dlg.getFill();
                          if (newVel != (int) (curVel * 100)
                              || newIt != curIt
                              || newFill != curFill) {
                            org.chuck.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.chuck.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                oldStep = tModel.getClips().get(activeClipId).getStep(trk, colId);
                              }
                            }
                            bridge.setVelocity(engineRow, colId, newVel / 100.0);
                            bridge.setIterance(engineRow, colId, newIt);
                            bridge.setStepFill(engineRow, colId, newFill / 100.0);
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.chuck.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.chuck.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                boolean st = bridge.getStep(engineRow, colId);
                                double prob = bridge.getStepProbability(engineRow, colId);
                                cModel.setStep(
                                    trk,
                                    colId,
                                    new org.chuck.deluge.model.StepData(
                                        st,
                                        newVel / 100.0f,
                                        0.5f,
                                        (float) prob,
                                        0,
                                        newIt,
                                        newFill / 100.0f));
                                if (oldStep != null) {
                                  projectModel
                                      .getUndoRedoStack()
                                      .push(
                                          new Consequence.StepConsequence(
                                              editedModelTrack,
                                              activeClipId,
                                              trk,
                                              colId,
                                              oldStep,
                                              cModel.getStep(trk, colId)));
                                }
                              }
                            }
                            refresh();
                          }
                        }
                      } else if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                        boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;

                        int trackType = bridge.getTrackType(trk);
                        if (trackType == 2) {
                          org.chuck.deluge.model.StepData oldStep = null;
                          if (projectModel != null
                              && editedModelTrack < projectModel.getTracks().size()) {
                            org.chuck.deluge.model.TrackModel tModel =
                                projectModel.getTracks().get(editedModelTrack);
                            if (activeClipId < tModel.getClips().size()) {
                              oldStep = tModel.getClips().get(activeClipId).getStep(trk, colId);
                            }
                          }
                          boolean st = bridge.getStep(baseTrackId + trk, colId);
                          bridge.setStep(baseTrackId + trk, colId, !st);
                          if (!st) {
                            if (finalMidiOut != null) {
                              try {
                                finalMidiOut.sendMessage(
                                    new byte[] {(byte) 0x90, (byte) (60 + trk), (byte) 100});
                              } catch (Exception ex) {
                                LOG.warning("MIDI send failed (kit grid): " + ex.getMessage());
                              }
                            }
                          }
                          clipBtn.setBackground(
                              !st
                                  ? trackColors[6]
                                  : new Color(0x33, 0x33, 0x33)); // Blue for MIDI Track

                          // Write to model so changes survive view switches
                          if (projectModel != null
                              && editedModelTrack < projectModel.getTracks().size()) {
                            org.chuck.deluge.model.TrackModel tModel =
                                projectModel.getTracks().get(editedModelTrack);
                            if (activeClipId < tModel.getClips().size()) {
                              org.chuck.deluge.model.ClipModel cModel =
                                  tModel.getClips().get(activeClipId);
                              cModel.setStep(
                                  trk,
                                  colId,
                                  org.chuck.deluge.model.StepData.of(
                                      !st,
                                      0.8f,
                                      org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                                      1.0f,
                                      0));
                              if (oldStep != null) {
                                projectModel
                                    .getUndoRedoStack()
                                    .push(
                                        new Consequence.StepConsequence(
                                            editedModelTrack,
                                            activeClipId,
                                            trk,
                                            colId,
                                            oldStep,
                                            cModel.getStep(trk, colId)));
                              }
                            }
                          }
                        } else if (isSynthMode) {
                          // Each visual row toggles its own engine row independently (chords)
                          int engineRow = baseTrackId + trk;
                          org.chuck.deluge.model.StepData oldStep = null;
                          if (projectModel != null
                              && editedModelTrack < projectModel.getTracks().size()) {
                            org.chuck.deluge.model.TrackModel tModel =
                                projectModel.getTracks().get(editedModelTrack);
                            if (activeClipId < tModel.getClips().size()) {
                              oldStep = tModel.getClips().get(activeClipId).getStep(trk, colId);
                            }
                          }
                          boolean stepState = bridge.getStep(engineRow, colId);
                          bridge.setStep(engineRow, colId, !stepState);
                          double velS = bridge.getVelocity(engineRow, colId);
                          clipBtn.setBackground(
                              !stepState
                                  ? velocityBlend(trackColors[trk], velS)
                                  : new Color(0x33, 0x33, 0x33));

                          // Audition via engine preview (wrap to voice slot)
                          int slot = baseTrackId + (trk % 8);
                          vm.setGlobalFloat(
                              BridgeContract.G_PREVIEW_PITCH, (float) ((24 - 1) - trk));
                          vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) slot);
                          vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);

                          // Write to model so changes survive view switches
                          if (projectModel != null
                              && editedModelTrack < projectModel.getTracks().size()) {
                            org.chuck.deluge.model.TrackModel tModel =
                                projectModel.getTracks().get(editedModelTrack);
                            if (activeClipId < tModel.getClips().size()) {
                              org.chuck.deluge.model.ClipModel cModel =
                                  tModel.getClips().get(activeClipId);
                              double curVel = bridge.getVelocity(engineRow, colId);
                              double curProb = bridge.getStepProbability(engineRow, colId);
                              cModel.setStep(
                                  trk,
                                  colId,
                                  org.chuck.deluge.model.StepData.of(
                                      !stepState,
                                      (float) curVel,
                                      org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                                      (float) curProb,
                                      0));
                              if (oldStep != null) {
                                projectModel
                                    .getUndoRedoStack()
                                    .push(
                                        new Consequence.StepConsequence(
                                            editedModelTrack,
                                            activeClipId,
                                            trk,
                                            colId,
                                            oldStep,
                                            cModel.getStep(trk, colId)));
                              }
                            }
                          }
                        } else {
                          // Audition on press — no step toggle (use double-click or Edit button to
                          // toggle steps)
                          vm.setGlobalInt(
                              BridgeContract.G_PREVIEW_TRACK, (long) (baseTrackId + trk));
                          vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                        }
                      }
                    }

                    @Override
                    public void mouseReleased(java.awt.event.MouseEvent e) {
                      // Stop kit preview on release
                      vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, -1L);
                      vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                    }
                  });

            } else if (viewMode == GridViewMode.SONG) {
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        new TrackInspectorDialog(
                                (Frame)
                                    javax.swing.SwingUtilities.getWindowAncestor(
                                        SwingGridPanel.this),
                                currentTrack,
                                tracks,
                                SwingGridPanel.this::refresh)
                            .setVisible(true);
                      }
                    }
                  });
            } else if (viewMode == GridViewMode.ARRANGEMENT) {
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        new BarAutomationDialog(
                                (Frame)
                                    javax.swing.SwingUtilities.getWindowAncestor(
                                        SwingGridPanel.this),
                                slot)
                            .setVisible(true);
                      }
                    }
                  });
            }

            if (viewMode == GridViewMode.SONG && t < tracks.size() && colId < 16) {
              final int clipCol = colId;
              final int trkIdx = currentTrack;
              final org.chuck.deluge.model.TrackModel songTrack = tracks.get(t);
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        if (clipCol < songTrack.getClips().size()) {
                          showClipContextMenu(
                              clipBtn, e.getX(), e.getY(), songTrack, clipCol, trkIdx);
                        }
                      } else {
                        if (clipCol >= songTrack.getClips().size()) {
                          String name = "CLIP " + (clipCol + 1);
                          songTrack.addClip(
                              new org.chuck.deluge.model.ClipModel(
                                  name,
                                  songTrack.getClips().isEmpty()
                                      ? 8
                                      : songTrack.getClips().get(0).getRowCount(),
                                  16));
                          fireProjectChanged();
                        }
                      }
                    }
                  });
            }

            clipBtn.addActionListener(
                e -> {
                  if (viewMode == GridViewMode.SONG) {
                    if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                      return;
                    }
                    if (colId < 16 && trkId < tracks.size()) {
                      bridge.setLaunchQueue(trkId, colId);
                    }
                    clipBtn.setBackground(new Color(0xff, 0xaa, 0x00)); // amber = queued
                    refresh();
                  } else if (viewMode == GridViewMode.ARRANGEMENT) {
                    // Toggle Linear arrangement playback bar state
                    if (clipBtn.getBackground().equals(trackColors[currentTrack])) {
                      clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                    } else {
                      clipBtn.setBackground(trackColors[currentTrack]);
                    }
                  }
                });
          }

          if (c == columnCount - 2) {
            rowPanel.add(Box.createHorizontalStrut(20));
          }
          rowPanel.add(clipBtn);
          rowPanel.add(Box.createHorizontalStrut(5));
        }
        add(rowPanel);
      }
    } // end else (SONG/ARRANGEMENT)
    columnCount = savedColCount; // restore CLIP-mode columnCount

    if (viewMode == GridViewMode.CLIP) {
      add(Box.createVerticalStrut(10));
      add(new PianoRollComponent(this));
    }

    refreshInProgress = false;
    revalidate();
    repaint();

    org.rtmidijava.RtMidiOut midiOut = null;
    try {
      midiOut = org.rtmidijava.RtMidiFactory.createDefaultOut();
      if (midiOut.getPortCount() > 0) {
        midiOut.openPort(0, "DelugeOut");
      }

    } catch (Exception ex) {
      LOG.warning("MIDI out init failed: " + ex.getMessage());
    }

    final int[] lastCol = {-1};
    this.finalMidiOut = midiOut;

    // Stop old timer to prevent leaks
    if (playheadTimer != null) playheadTimer.stop();

    playheadTimer =
        new Timer(
            100,
            e -> {
              int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
              if (currentStep >= 0) {
                int activeCol = (currentStep % stepCount);
                // When horizontally scrolled, the timer maps engine columns to visible columns.
                // The background-sync loop below uses visual column indexing (pads[t][c]).
                // We need to drive bridge lookups with engine-step columns, not visual columns.
                // activeCol as computed above is fine for border highlighting — it maps the
                // engine step to the visible column where the playhead border should appear.
                int engineActiveCol;
                // For scrollOffsetX > 0: clip is wider than viewport; the engine active col
                // may fall outside the visible window. Map it:
                int trackLenTimer = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
                if (trackLenTimer > stepCount) {
                  int rawCol = currentStep % trackLenTimer;
                  engineActiveCol = rawCol;
                  int visualCol = rawCol - scrollOffsetX;
                  if (visualCol >= 0 && visualCol < stepCount) {
                    activeCol = visualCol;
                  } else {
                    activeCol = -1; // step is outside the visible window — hide border
                  }
                } else {
                  engineActiveCol = currentStep % stepCount;
                }

                int rows =
                    (viewMode == GridViewMode.AUTOMATION)
                        ? 8
                        : (viewMode == GridViewMode.CLIP) ? voiceRowCount : gridMode.rows;
                if (activeCol != lastCol[0]) {
                  lastCol[0] = activeCol;
                  if (activeCol == 0) {
                    for (int t = 0; t < rows; t++) {
                      if (isOneShotTrack[t] && currentStep >= stepCount) {
                        bridge.setMute(baseTrackId + t, true);
                      }
                    }
                  }

                  for (int t = 0; t < rows; t++) {
                    int engineRow =
                        baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
                    if (bridge.getStep(engineRow, engineActiveCol)) {
                      vuLevels[engineRow] = 1.0; // Spike VU Meter!
                      if (finalMidiOut != null) {
                        try {
                          finalMidiOut.sendMessage(
                              new byte[] {(byte) 0x90, (byte) (36 + t * 2), (byte) 100});
                        } catch (Exception ex) {
                          LOG.warning("MIDI send failed (playhead): " + ex.getMessage());
                        }
                      }
                    }
                  }

                  // Sidechain Compressor Ducking tied to first engine row
                  if (bridge.getStep(baseTrackId, engineActiveCol)) {
                    for (int t = 1; t < rows; t++) {
                      final int trackIdx = t;
                      bridge.setTrackLevel(trackIdx, 0.15); // Duck

                      Timer duckRelease =
                          new Timer(
                              120,
                              ev -> {
                                bridge.setTrackLevel(trackIdx, 0.70); // Release / Restore
                              });
                      duckRelease.setRepeats(false);
                      duckRelease.start();
                    }
                  }
                }

                if (viewMode != GridViewMode.AUTOMATION) {
                  for (int t = 0; t < rows; t++) {
                    int engineRow2 =
                        baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
                    int trackLenT = bridge != null ? bridge.getTrackLength(engineRow2) : stepCount;
                    for (int c = 0; c < stepCount; c++) {
                      if (pads[t][c] != null) {
                        if (shiftHeld) continue;
                        // Map visual column to engine column when scrolled horizontally
                        int engineCol;
                        if (trackLenT > stepCount) {
                          engineCol = Math.min(c + scrollOffsetX, trackLenT - 1);
                        } else if (trackLenT > 0 && trackLenT < stepCount) {
                          engineCol = c % trackLenT;
                        } else {
                          engineCol = c;
                        }
                        if (c == activeCol) {
                          pads[t][c].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 4));
                        } else {
                          pads[t][c].setBorder(UIManager.getBorder("Button.border"));
                        }
                        // Re-sync background from bridge so cell selection stays correct during
                        // playback
                        boolean stepActive = bridge.getStep(engineRow2, engineCol);
                        double velPb = bridge.getVelocity(engineRow2, engineCol);
                        if (pads[t][c] instanceof DelugePadButton pad) {
                          pad.setActive(stepActive);
                          pad.setIntensity((float) (velPb * 0.8f));
                          pad.setBaseColor(trackColors[t % trackColors.length]);
                        } else {
                          pads[t][c].setBackground(
                              stepActive
                                  ? velocityBlend(trackColors[t % trackColors.length], velPb)
                                  : new Color(0x33, 0x33, 0x33));
                        }
                      }
                    }
                  }
                  SwingGridPanel.this.repaint();
                } // end if (viewMode != AUTOMATION)
              }
            });
    playheadTimer.start();
  }

  // ── Automation Editor (8-value-band per-step editor) ──

  /**
   * Build the per-step value band editor for a single automation parameter. 8 rows × stepCount
   * grid, where each cell in a row represents whether the step's automation value falls within that
   * row's value band (0-15, 16-31, etc.). Click to set, shift-click to clear, drag to paint.
   */
  private void buildAutomationEditor(
      org.chuck.deluge.model.ClipModel autoClip, String param, int padSz) {
    if (param == null) param = org.chuck.deluge.model.AutomationParam.SYTH_PARAMS[0];

    // Step number header row
    JPanel stepHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    stepHeader.setBackground(new Color(0x15, 0x15, 0x15));
    stepHeader.setMaximumSize(new Dimension(3000, 20));
    int labelOffset = Math.max(60, Math.min(140, getWidth() / 12)) + 69 + 5 + 12 + 5;
    stepHeader.add(Box.createRigidArea(new Dimension(labelOffset, 20)));
    for (int c = 0; c < stepCount; c++) {
      JLabel stepNum = new JLabel(String.valueOf(c + 1), javax.swing.SwingConstants.CENTER);
      stepNum.setPreferredSize(new Dimension(padSz, 18));
      stepNum.setForeground(Color.GRAY);
      stepNum.setFont(new Font("Monospaced", Font.PLAIN, 10));
      stepHeader.add(stepNum);
    }
    add(stepHeader);

    // Label for each value band row
    String[] bandLabels = {
      "0-15", "16-31", "32-47", "48-63", "64-79", "80-95", "96-111", "112-127"
    };

    for (int r = 0; r < 8; r++) {
      final int rowIdx = r;
      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(3000, padSz));
      rowPanel.setMinimumSize(new Dimension(3000, padSz));
      rowPanel.setMaximumSize(new Dimension(3000, padSz));

      String finalParam = param;
      JLabel valLabel = new JLabel(bandLabels[r]);
      int lw = Math.max(60, Math.min(140, getWidth() / 12));
      valLabel.setPreferredSize(new Dimension(lw, 30));
      valLabel.setMinimumSize(new Dimension(lw, 30));
      valLabel.setMaximumSize(new Dimension(lw, 30));
      valLabel.setForeground(new Color(0x88, 0x88, 0x88));
      valLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
      rowPanel.add(valLabel);

      // Spacer to match config-button + len-badge area
      rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
      rowPanel.add(Box.createHorizontalStrut(5));

      VUMeterPanel vu = new VUMeterPanel();
      vu.setPreferredSize(new Dimension(12, padSz));
      vu.setMaximumSize(new Dimension(12, padSz));
      rowPanel.add(vu);
      rowPanel.add(Box.createHorizontalStrut(5));

      for (int c = 0; c < stepCount; c++) {
        final int colIdx = c;
        JButton cell = new JButton();
        cell.setPreferredSize(new Dimension(padSz, padSz));
        cell.setMinimumSize(new Dimension(padSz, padSz));
        cell.setMaximumSize(new Dimension(padSz, padSz));
        cell.setMargin(new Insets(0, 0, 0, 0));

        pads[r][c] = cell;

        // Determine if this cell is "lit" (value band matches row)
        boolean lit = false;
        float autoVal = -1f;
        if (autoClip != null) {
          autoVal = autoClip.getAutomation(finalParam, colIdx);
          if (autoVal >= 0f) {
            int band = (int) (autoVal * 127f) / 16;
            lit = (band == rowIdx);
          }
        }

        if (lit) {
          int precise = (int) (autoVal * 127f) % 16;
          int bright = 0x44 + precise * 8;
          cell.setBackground(new Color(0x00, bright, Math.min(0xcc, bright / 2 + 0x44)));
          cell.setForeground(Color.WHITE);
          cell.setText("\u25CF");
        } else {
          cell.setBackground(new Color(0x33, 0x33, 0x33));
          cell.setForeground(new Color(0x55, 0x55, 0x55));
          if (autoVal >= 0f) {
            // This param has automation but value band doesn't match this row
            cell.setText(".");
          } else {
            cell.setText("");
          }
        }

        cell.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mousePressed(java.awt.event.MouseEvent e) {
                if (projectModel == null) return;
                int tIdx = editedModelTrack;
                if (tIdx >= projectModel.getTracks().size()) return;
                org.chuck.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                org.chuck.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);

                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                  if (e.isShiftDown()) {
                    // Clear automation at this step
                    float oldVal = cM.getAutomation(finalParam, colIdx);
                    float[] arr = cM.getAutomationArray(finalParam);
                    if (arr != null && colIdx < arr.length) {
                      arr[colIdx] = -1f;
                      projectModel
                          .getUndoRedoStack()
                          .push(
                              new Consequence.AutomationConsequence(
                                  tIdx, acIdx2, finalParam, colIdx, oldVal, -1f));
                      refresh();
                    }
                  } else {
                    float oldVal = cM.getAutomation(finalParam, colIdx);
                    float val = (rowIdx * 16 + 8) / 127.0f;
                    cM.setAutomation(finalParam, colIdx, val);
                    autoDragParam = finalParam;
                    autoDragStep = colIdx;
                    autoDragOldValue = oldVal;
                    automationDragging = true;
                    refresh();
                  }
                }
              }

              @Override
              public void mouseReleased(java.awt.event.MouseEvent e) {
                if (automationDragging
                    && projectModel != null
                    && autoDragParam != null
                    && autoDragStep >= 0) {
                  int tIdx = editedModelTrack;
                  if (tIdx < projectModel.getTracks().size()) {
                    org.chuck.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                    int acIdx2 = tM.getActiveClipIndex();
                    if (acIdx2 >= 0 && acIdx2 < tM.getClips().size()) {
                      org.chuck.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);
                      float newVal = cM.getAutomation(autoDragParam, autoDragStep);
                      if (newVal != autoDragOldValue) {
                        projectModel
                            .getUndoRedoStack()
                            .push(
                                new Consequence.AutomationConsequence(
                                    tIdx,
                                    acIdx2,
                                    autoDragParam,
                                    autoDragStep,
                                    autoDragOldValue,
                                    newVal));
                      }
                    }
                  }
                  autoDragParam = null;
                  autoDragStep = -1;
                  autoDragOldValue = -1f;
                }
                automationDragging = false;
              }
            });

        cell.addMouseMotionListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mouseDragged(java.awt.event.MouseEvent e) {
                if (!automationDragging || projectModel == null) return;
                int tIdx = editedModelTrack;
                if (tIdx >= projectModel.getTracks().size()) return;
                org.chuck.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                org.chuck.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);
                float val = (rowIdx * 16 + 8) / 127.0f;
                cM.setAutomation(finalParam, colIdx, val);
                refresh();
              }
            });

        rowPanel.add(cell);
        rowPanel.add(Box.createHorizontalStrut(5));
      }
      add(rowPanel);
    }
  }

  // ── Automation Overview Grid ──

  /**
   * Build an overview grid showing all params and their automation status across steps. Each cell =
   * (step, param). Lit = has automation data, dim = no automation. Row headers show compact param
   * labels. Click to open editor for that param. Shift+click = clear automation for that param.
   */
  private void buildAutomationOverview(org.chuck.deluge.model.ClipModel autoClip, int padSz) {
    String[] allParams = org.chuck.deluge.model.AutomationParam.SYTH_PARAMS;
    int totalParams = allParams.length;

    // Visible params (with vertical scroll)
    int maxVisible = 8;
    int paramOffset = autoColScroll;
    int visibleParams = Math.min(maxVisible, totalParams - paramOffset);
    if (visibleParams <= 0) return;

    // Step header
    JPanel stepHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    stepHeader.setBackground(new Color(0x15, 0x15, 0x15));
    stepHeader.setMaximumSize(new Dimension(3000, 20));
    int labelOffset = Math.max(60, Math.min(140, getWidth() / 12));
    stepHeader.add(Box.createRigidArea(new Dimension(labelOffset + 5, 20)));
    for (int c = 0; c < stepCount; c++) {
      JLabel stepNum = new JLabel(String.valueOf(c + 1), javax.swing.SwingConstants.CENTER);
      stepNum.setPreferredSize(new Dimension(padSz, 18));
      stepNum.setForeground(Color.GRAY);
      stepNum.setFont(new Font("Monospaced", Font.PLAIN, 10));
      stepHeader.add(stepNum);
    }
    add(stepHeader);

    for (int r = 0; r < visibleParams; r++) {
      int paramIdx = paramOffset + r;
      String paramName = allParams[paramIdx];
      String label = org.chuck.deluge.model.AutomationParam.labelFor(paramName);

      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(3000, padSz));
      rowPanel.setMinimumSize(new Dimension(3000, padSz));
      rowPanel.setMaximumSize(new Dimension(3000, padSz));

      // Param label (clickable to open editor)
      JButton paramBtn = new JButton(label);
      int pw = Math.max(60, Math.min(140, getWidth() / 12));
      paramBtn.setPreferredSize(new Dimension(pw, 30));
      paramBtn.setMinimumSize(new Dimension(pw, 30));
      paramBtn.setMaximumSize(new Dimension(pw, 30));
      paramBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
      paramBtn.setFocusPainted(false);
      paramBtn.setMargin(new Insets(0, 2, 0, 2));

      boolean hasAnyAuto = autoClip != null && autoClip.hasAutomation(paramName);
      paramBtn.setBackground(
          hasAnyAuto ? new Color(0x33, 0x66, 0x33) : new Color(0x44, 0x44, 0x44));
      paramBtn.setForeground(hasAnyAuto ? new Color(0x88, 0xff, 0x88) : Color.LIGHT_GRAY);

      final String fParam = paramName;
      paramBtn.addActionListener(
          e -> {
            autoOverviewMode = false;
            selectedAutomationParam = fParam;
            refresh();
          });
      rowPanel.add(paramBtn);

      // Up/down scroll buttons
      JPanel scrollCol = new JPanel();
      scrollCol.setLayout(new BoxLayout(scrollCol, BoxLayout.Y_AXIS));
      scrollCol.setBackground(new Color(0x22, 0x22, 0x22));
      JButton upBtn = new JButton("\u25B2");
      upBtn.setFont(new Font("SansSerif", Font.PLAIN, 8));
      upBtn.setMargin(new Insets(0, 0, 0, 0));
      upBtn.setPreferredSize(new Dimension(14, padSz / 2));
      upBtn.setEnabled(paramOffset > 0);
      upBtn.addActionListener(
          e -> {
            autoColScroll = Math.max(0, autoColScroll - 1);
            refresh();
          });
      scrollCol.add(upBtn);

      JButton downBtn = new JButton("\u25BC");
      downBtn.setFont(new Font("SansSerif", Font.PLAIN, 8));
      downBtn.setMargin(new Insets(0, 0, 0, 0));
      downBtn.setPreferredSize(new Dimension(14, padSz / 2));
      downBtn.setEnabled(paramOffset + maxVisible < totalParams);
      downBtn.addActionListener(
          e -> {
            autoColScroll = Math.min(totalParams - maxVisible, autoColScroll + 1);
            refresh();
          });
      scrollCol.add(downBtn);
      rowPanel.add(scrollCol);

      rowPanel.add(Box.createHorizontalStrut(3));

      for (int c = 0; c < stepCount; c++) {
        final int colIdx = c;
        JButton cell = new JButton();
        cell.setPreferredSize(new Dimension(padSz, padSz));
        cell.setMinimumSize(new Dimension(padSz, padSz));
        cell.setMaximumSize(new Dimension(padSz, padSz));
        cell.setMargin(new Insets(0, 0, 0, 0));

        pads[r][c] = cell;

        boolean hasAuto = autoClip != null && autoClip.hasAutomation(paramName, c);

        if (hasAuto) {
          float val = autoClip.getAutomation(paramName, c);
          int bright = 0x44 + (int) (val * 0x88);
          cell.setBackground(new Color(0x00, bright, 0x33));
          cell.setForeground(Color.WHITE);
          cell.setText("\u25CF");
        } else {
          cell.setBackground(new Color(0x33, 0x33, 0x33));
          cell.setForeground(new Color(0x55, 0x55, 0x55));
          cell.setText("");
        }

        cell.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mousePressed(java.awt.event.MouseEvent e) {
                if (projectModel == null) return;
                int tIdx = editedModelTrack;
                if (tIdx >= projectModel.getTracks().size()) return;
                org.chuck.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                org.chuck.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);

                if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                  if (e.isShiftDown()) {
                    // Clear cell
                    float oldVal = cM.getAutomation(fParam, colIdx);
                    float[] arr = cM.getAutomationArray(fParam);
                    if (arr != null && colIdx < arr.length) {
                      arr[colIdx] = -1f;
                      projectModel
                          .getUndoRedoStack()
                          .push(
                              new Consequence.AutomationConsequence(
                                  tIdx, acIdx2, fParam, colIdx, oldVal, -1f));
                      refresh();
                    }
                  } else {
                    float oldVal = cM.getAutomation(fParam, colIdx);
                    // Toggle: set to 0.5 if no automation, clear if already set
                    if (cM.hasAutomation(fParam)) {
                      float[] arr = cM.getAutomationArray(fParam);
                      if (arr != null && colIdx < arr.length && arr[colIdx] >= 0f) {
                        arr[colIdx] = -1f;
                      } else if (arr != null && colIdx < arr.length) {
                        arr[colIdx] = 0.5f;
                      }
                    } else {
                      cM.setAutomation(fParam, colIdx, 0.5f);
                    }
                    float newVal = cM.getAutomation(fParam, colIdx);
                    if (newVal != oldVal && !e.isShiftDown()) {
                      // Single click — push now
                      projectModel
                          .getUndoRedoStack()
                          .push(
                              new Consequence.AutomationConsequence(
                                  tIdx, acIdx2, fParam, colIdx, oldVal, newVal));
                    }
                    // Track for drag coalescing
                    autoDragParam = fParam;
                    autoDragStep = colIdx;
                    autoDragOldValue = oldVal;
                    automationDragging = true;
                    refresh();
                  }
                }
              }

              @Override
              public void mouseReleased(java.awt.event.MouseEvent e) {
                if (automationDragging
                    && projectModel != null
                    && autoDragParam != null
                    && autoDragStep >= 0) {
                  int tIdx = editedModelTrack;
                  if (tIdx < projectModel.getTracks().size()) {
                    org.chuck.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                    int acIdx2 = tM.getActiveClipIndex();
                    if (acIdx2 >= 0 && acIdx2 < tM.getClips().size()) {
                      org.chuck.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);
                      float newVal = cM.getAutomation(autoDragParam, autoDragStep);
                      if (newVal != autoDragOldValue) {
                        projectModel
                            .getUndoRedoStack()
                            .push(
                                new Consequence.AutomationConsequence(
                                    tIdx,
                                    acIdx2,
                                    autoDragParam,
                                    autoDragStep,
                                    autoDragOldValue,
                                    newVal));
                      }
                    }
                  }
                  autoDragParam = null;
                  autoDragStep = -1;
                  autoDragOldValue = -1f;
                }
                automationDragging = false;
              }
            });

        cell.addMouseMotionListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mouseDragged(java.awt.event.MouseEvent e) {
                if (!automationDragging || projectModel == null) return;
                int tIdx = editedModelTrack;
                if (tIdx >= projectModel.getTracks().size()) return;
                org.chuck.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                org.chuck.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);
                cM.setAutomation(fParam, colIdx, 0.5f);
                refresh();
              }
            });

        rowPanel.add(cell);
        rowPanel.add(Box.createHorizontalStrut(3));
      }
      add(rowPanel);
    }

    // Scroll indicator
    if (totalParams > maxVisible) {
      JPanel scrollBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
      scrollBar.setBackground(new Color(0x1a, 0x1a, 0x1a));
      for (int i = 0; i < totalParams; i += maxVisible) {
        int pageStart = i;
        JButton dot =
            new JButton((i <= paramOffset && paramOffset < i + maxVisible) ? "\u25C9" : "\u25CB");
        dot.setFont(new Font("SansSerif", Font.PLAIN, 9));
        dot.setMargin(new Insets(0, 2, 0, 2));
        dot.setBackground(new Color(0x33, 0x33, 0x33));
        dot.setForeground(Color.LIGHT_GRAY);
        int fPage = pageStart;
        dot.addActionListener(
            e -> {
              autoColScroll = fPage;
              refresh();
            });
        scrollBar.add(dot);
      }
      add(scrollBar);
    }
  }

  // ── Automation interpolation ──

  /**
   * Linear interpolation between automated steps in a clip. Fills gaps (steps with -1) between two
   * known values. If fewer than 2 automated values exist, does nothing.
   */
  private void interpolateAutomation(org.chuck.deluge.model.ClipModel clip, String param) {
    if (clip == null || param == null) return;
    float[] arr = clip.getAutomationArray(param);
    if (arr == null) return;

    // Find first and last automated step indices
    int first = -1, last = -1;
    for (int i = 0; i < arr.length; i++) {
      if (arr[i] >= 0f) {
        if (first < 0) first = i;
        last = i;
      }
    }
    if (first < 0 || first == last) return; // 0 or 1 automated steps — nothing to interpolate

    // Walk through and interpolate between known-value pairs
    int prevIdx = first;
    for (int i = first + 1; i <= last; i++) {
      if (arr[i] >= 0f) {
        int gapLen = i - prevIdx;
        if (gapLen > 1) {
          float startVal = arr[prevIdx];
          float endVal = arr[i];
          for (int g = 1; g < gapLen; g++) {
            float t = g / (float) gapLen;
            arr[prevIdx + g] = startVal + (endVal - startVal) * t;
          }
        }
        prevIdx = i;
      }
    }
  }

  /** Activate a song section by its index, queueing clips on each track. */
  private void activateSection(int idx) {
    java.util.List<SongSection> sections = projectModel.getSongSections();
    if (idx >= sections.size()) return;
    SongSection section = sections.get(idx);
    for (int t = 0; t < projectModel.getTracks().size() && t < BridgeContract.TRACKS; t++) {
      org.chuck.deluge.model.TrackModel track = projectModel.getTracks().get(t);
      for (int c = 0; c < track.getClips().size(); c++) {
        if (section.getPatternIds().contains(track.getClips().get(c).getName())) {
          bridge.setLaunchQueue(t, c);
          break;
        }
      }
    }
    refresh();
  }

  // ── Advanced Pad Gestures & LED Pad Helpers ──

  private static org.chuck.deluge.model.StepData copiedStep = null;

  public boolean hasMultiSelection() {
    return !selectedCells.isEmpty();
  }

  public void clearMultiSelection() {
    if (!selectedCells.isEmpty()) {
      selectedCells.clear();
      refresh();
    }
  }

  private void handleStepCtrlClicked(int row, int col) {
    int mRow = getModelRow(row);
    int aCol = getActiveCol(row, col);
    String key = mRow + "," + aCol;
    if (selectedCells.contains(key)) {
      selectedCells.remove(key);
    } else {
      selectedCells.add(key);
    }
    refresh();
  }

  private void updateDragSelectionVisuals() {
    if (!isDragSelecting) return;
    int minR = Math.min(dragSelStartRow, dragSelCurrRow);
    int maxR = Math.max(dragSelStartRow, dragSelCurrRow);
    int minC = Math.min(dragSelStartCol, dragSelCurrCol);
    int maxC = Math.max(dragSelStartCol, dragSelCurrCol);

    int visibleRows = Math.min(gridMode.rows, voiceRowCount);
    for (int r = 0; r < visibleRows; r++) {
      int mRow = getModelRow(r);
      for (int c = 0; c < 16; c++) {
        JButton btn = pads[r][c];
        if (btn instanceof DelugePadButton pad) {
          int aCol = getActiveCol(r, c);
          boolean selected = selectedCells.contains(mRow + "," + aCol);
          if (r >= minR && r <= maxR && c >= minC && c <= maxC) {
            selected = true;
          }
          pad.setSelected(selected);
        }
      }
    }
  }

  private void finalizeDragSelection(
      int startRow, int startCol, int currRow, int currCol, boolean isControlOrCmd) {
    if (!isControlOrCmd) {
      selectedCells.clear();
    }
    int minR = Math.min(startRow, currRow);
    int maxR = Math.max(startRow, currRow);
    int minC = Math.min(startCol, currCol);
    int maxC = Math.max(startCol, currCol);

    for (int r = minR; r <= maxR; r++) {
      int mRow = getModelRow(r);
      for (int c = minC; c <= maxC; c++) {
        if (c >= 16) continue;
        int aCol = getActiveCol(r, c);
        String key = mRow + "," + aCol;
        if (isControlOrCmd) {
          if (selectedCells.contains(key)) {
            selectedCells.remove(key);
          } else {
            selectedCells.add(key);
          }
        } else {
          selectedCells.add(key);
        }
      }
    }
    isDragSelecting = false;
    refresh();
  }

  private void deleteSelectedStepsAction() {
    if (selectedCells.isEmpty() || bridge == null) return;

    boolean changed = false;
    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;

    for (String cellKey : new java.util.ArrayList<>(selectedCells)) {
      String[] parts = cellKey.split(",");
      if (parts.length != 2) continue;
      int modelRow = Integer.parseInt(parts[0]);
      int activeCol = Integer.parseInt(parts[1]);
      int engineRow = baseTrackId + modelRow;

      if (bridge.getStep(engineRow, activeCol)) {
        org.chuck.deluge.model.StepData oldStep = null;
        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
          org.chuck.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
          if (activeClipId < tModel.getClips().size()) {
            oldStep = tModel.getClips().get(activeClipId).getStep(modelRow, activeCol);
          }
        }

        bridge.setStep(engineRow, activeCol, false);

        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
          org.chuck.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
          if (activeClipId < tModel.getClips().size()) {
            org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
            double curVel = bridge.getVelocity(engineRow, activeCol);
            double curProb = bridge.getStepProbability(engineRow, activeCol);
            int pitch = isSynthMode ? (((24 - 1) - modelRow) + 60) : 0;
            cModel.setStep(
                modelRow,
                activeCol,
                org.chuck.deluge.model.StepData.of(
                    false,
                    (float) curVel,
                    org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                    (float) curProb,
                    pitch));

            if (oldStep != null) {
              projectModel
                  .getUndoRedoStack()
                  .push(
                      new Consequence.StepConsequence(
                          editedModelTrack,
                          activeClipId,
                          modelRow,
                          activeCol,
                          oldStep,
                          cModel.getStep(modelRow, activeCol)));
            }
          }
        }
        changed = true;
      }
    }

    if (changed) {
      fireProjectChanged();
    }
  }

  private int getModelRow(int visualRow) {
    return scrollOffset + visualRow;
  }

  private int getActiveCol(int visualRow, int visualCol) {
    int modelRow = getModelRow(visualRow);
    int activeCol = visualCol;
    if (bridge != null && projectModel != null) {
      int trackLen = 0;
      java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();
      if (modelRow < tracks.size()) {
        org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);
        if (activeClipId < track.getClips().size()) {
          trackLen = track.getClips().get(activeClipId).getStepCount();
        }
      }
      if (trackLen <= 0) trackLen = bridge.getTrackLength(baseTrackId + modelRow);
      if (trackLen > 0 && trackLen < stepCount) {
        activeCol = visualCol % trackLen;
      } else if (trackLen > stepCount) {
        activeCol = Math.min(visualCol + scrollOffsetX, trackLen - 1);
      }
    }
    return activeCol;
  }

  private class DelugeGestureListener implements DelugeGestureCoordinator.GestureListener {
    @Override
    public void onStepPressed(int row, int col) {
      handleStepPressed(row, col);
    }

    @Override
    public void onStepReleased(int row, int col) {
      handleStepReleased(row, col);
    }

    @Override
    public void onStepToggled(int row, int col) {
      handleStepToggled(row, col);
    }

    @Override
    public void onStepLongPressed(int row, int col, Point screenPos) {
      handleStepLongPressed(row, col, screenPos);
    }

    @Override
    public void onStepTied(int row, int colStart, int colEnd) {
      handleStepTied(row, colStart, colEnd);
    }

    @Override
    public void onDragPreview(int row, int colStart, int colCurrent) {
      handleDragPreview(row, colStart, colCurrent);
    }

    @Override
    public void onDragCleared() {
      handleDragCleared();
    }
  }

  private void handleStepPressed(int row, int col) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
    int trackType = bridge.getTrackType(modelRow);
    if (trackType == 2) {
      if (finalMidiOut != null) {
        try {
          finalMidiOut.sendMessage(new byte[] {(byte) 0x90, (byte) (60 + modelRow), (byte) 100});
        } catch (Exception ex) {
          LOG.warning("MIDI send failed: " + ex.getMessage());
        }
      }
    } else if (isSynthMode) {
      int pitchMidi = ((24 - 1) - modelRow) + 60;
      if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
        Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
        if (fwEngineObj instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
          if (editedModelTrack < fwEngine.sounds.size()) {
            org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                fwEngine.sounds.get(editedModelTrack);
            if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
              synth.triggerNote(pitchMidi, 127);
            }
          }
        }
      } else {
        int voiceSlot = baseTrackId + (modelRow % 8);
        vm.setGlobalFloat(BridgeContract.G_PREVIEW_PITCH, (float) (pitchMidi - 60));
        vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) voiceSlot);
        vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
      }
    } else {
      if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
        Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
        if (fwEngineObj instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
          if (editedModelTrack < fwEngine.sounds.size()) {
            org.chuck.deluge.firmware.engine.GlobalEffectable sound =
                fwEngine.sounds.get(editedModelTrack);
            if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
              kit.triggerDrum(modelRow, 127);
            }
          }
        }
      } else {
        vm.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) (baseTrackId + modelRow));
        vm.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
      }
    }
  }

  private void handleStepReleased(int row, int col) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
      Object fwEngineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine fwEngine) {
        if (editedModelTrack < fwEngine.sounds.size()) {
          org.chuck.deluge.firmware.engine.GlobalEffectable sound =
              fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
            if (modelRow < kit.drumSounds.size()) {
              kit.drumSounds.get(modelRow).releaseNote(60);
            }
          } else if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound synth) {
            int pitchMidi = ((24 - 1) - modelRow) + 60;
            synth.releaseNote(pitchMidi);
          }
        }
      }
    }
  }

  private void handleStepToggled(int row, int col) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    int activeCol = getActiveCol(row, col);
    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
    int engineRow = baseTrackId + modelRow;

    org.chuck.deluge.model.StepData oldStep = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.chuck.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        oldStep = tModel.getClips().get(activeClipId).getStep(modelRow, activeCol);
      }
    }

    boolean stepState = bridge.getStep(engineRow, activeCol);
    boolean nextState = !stepState;
    bridge.setStep(engineRow, activeCol, nextState);

    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.chuck.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
        double curVel = bridge.getVelocity(engineRow, activeCol);
        double curProb = bridge.getStepProbability(engineRow, activeCol);
        int pitch = isSynthMode ? (((24 - 1) - modelRow) + 60) : 0;
        cModel.setStep(
            modelRow,
            activeCol,
            org.chuck.deluge.model.StepData.of(
                nextState,
                (float) curVel,
                org.chuck.deluge.model.StepData.DEFAULT_CLICK_GATE,
                (float) curProb,
                pitch));
        if (oldStep != null) {
          projectModel
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      editedModelTrack,
                      activeClipId,
                      modelRow,
                      activeCol,
                      oldStep,
                      cModel.getStep(modelRow, activeCol)));
        }
      }
    }
    fireProjectChanged();
  }

  private void handleStepLongPressed(int row, int col, Point screenPos) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    int activeCol = getActiveCol(row, col);
    int engineRow = baseTrackId + modelRow;
    JPopupMenu popup = new JPopupMenu();

    JMenuItem editProps = new JMenuItem("Edit Step Properties...");
    editProps.addActionListener(e -> showStepPropertiesDialog(row, col));
    popup.add(editProps);

    JMenuItem toggleStep = new JMenuItem("Toggle Step");
    toggleStep.addActionListener(e -> handleStepToggled(row, col));
    popup.add(toggleStep);

    JMenuItem clearStep = new JMenuItem("Clear Step");
    clearStep.addActionListener(
        e -> {
          boolean wasActive = bridge.getStep(engineRow, activeCol);
          if (wasActive) {
            handleStepToggled(row, col);
          }
        });
    popup.add(clearStep);

    popup.addSeparator();

    JMenu velMenu = new JMenu("Quick Velocity");
    double[] velocities = {0.25, 0.50, 0.75, 1.00};
    for (double v : velocities) {
      JMenuItem vItem = new JMenuItem((int) (v * 100) + "%");
      vItem.addActionListener(e -> applyVelocity(row, col, v));
      velMenu.add(vItem);
    }
    popup.add(velMenu);

    JMenu probMenu = new JMenu("Quick Probability");
    double[] probabilities = {0.25, 0.50, 0.75, 1.00};
    for (double p : probabilities) {
      JMenuItem pItem = new JMenuItem((int) (p * 100) + "%");
      pItem.addActionListener(e -> saveStepProbability(row, col, p));
      probMenu.add(pItem);
    }
    popup.add(probMenu);

    JMenu gateMenu = new JMenu("Quick Gate (Duration)");
    double[] gates = {0.0625, 0.125, 0.25, 0.5, 1.0};
    String[] gateLabels = {"1/16 step", "1/8 step", "1/4 step", "1/2 step", "1 step (tied)"};
    for (int i = 0; i < gates.length; i++) {
      final double g = gates[i];
      JMenuItem gItem = new JMenuItem(gateLabels[i]);
      gItem.addActionListener(
          e -> {
            bridge.setGate(engineRow, activeCol, g);
            if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
              org.chuck.deluge.model.TrackModel tModel =
                  projectModel.getTracks().get(editedModelTrack);
              if (activeClipId < tModel.getClips().size()) {
                org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
                org.chuck.deluge.model.StepData oldStep = cModel.getStep(modelRow, activeCol);
                boolean st = bridge.getStep(engineRow, activeCol);
                double vel = bridge.getVelocity(engineRow, activeCol);
                double prob = bridge.getStepProbability(engineRow, activeCol);
                int iter = bridge.getIterance(engineRow, activeCol);
                double fill = bridge.getStepFill(engineRow, activeCol);
                boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
                int pitch = isSynthMode ? (((24 - 1) - modelRow) + 60) : 0;

                org.chuck.deluge.model.StepData newStep =
                    new org.chuck.deluge.model.StepData(
                        st, (float) vel, (float) g, (float) prob, pitch, iter, (float) fill);
                cModel.setStep(modelRow, activeCol, newStep);
                if (oldStep != null) {
                  projectModel
                      .getUndoRedoStack()
                      .push(
                          new Consequence.StepConsequence(
                              editedModelTrack,
                              activeClipId,
                              modelRow,
                              activeCol,
                              oldStep,
                              newStep));
                }
              }
            }
            refresh();
          });
      gateMenu.add(gItem);
    }
    popup.add(gateMenu);

    popup.addSeparator();

    JMenuItem copyItem = new JMenuItem("Copy Step");
    copyItem.addActionListener(
        e -> {
          boolean st = bridge.getStep(engineRow, activeCol);
          double vel = bridge.getVelocity(engineRow, activeCol);
          double gate = bridge.getGate(engineRow, activeCol);
          double prob = bridge.getStepProbability(engineRow, activeCol);
          int iter = bridge.getIterance(engineRow, activeCol);
          double fill = bridge.getStepFill(engineRow, activeCol);
          boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
          int pitch = isSynthMode ? (((24 - 1) - modelRow) + 60) : 0;
          copiedStep =
              new org.chuck.deluge.model.StepData(
                  st, (float) vel, (float) gate, (float) prob, pitch, iter, (float) fill);
        });
    popup.add(copyItem);

    JMenuItem pasteItem = new JMenuItem("Paste Step");
    pasteItem.setEnabled(copiedStep != null);
    pasteItem.addActionListener(
        e -> {
          if (copiedStep != null) {
            bridge.setStep(engineRow, activeCol, copiedStep.active());
            bridge.setVelocity(engineRow, activeCol, copiedStep.velocity());
            bridge.setGate(engineRow, activeCol, copiedStep.gate());
            bridge.setStepProbability(engineRow, activeCol, copiedStep.probability());
            bridge.setIterance(engineRow, activeCol, copiedStep.iterance());
            bridge.setStepFill(engineRow, activeCol, copiedStep.fill());

            if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
              org.chuck.deluge.model.TrackModel tModel =
                  projectModel.getTracks().get(editedModelTrack);
              if (activeClipId < tModel.getClips().size()) {
                org.chuck.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
                org.chuck.deluge.model.StepData oldStep = cModel.getStep(modelRow, activeCol);
                boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
                int pitch = isSynthMode ? (((24 - 1) - modelRow) + 60) : 0;
                org.chuck.deluge.model.StepData newStep =
                    new org.chuck.deluge.model.StepData(
                        copiedStep.active(),
                        copiedStep.velocity(),
                        copiedStep.gate(),
                        copiedStep.probability(),
                        pitch,
                        copiedStep.iterance(),
                        copiedStep.fill());
                cModel.setStep(modelRow, activeCol, newStep);
                if (oldStep != null) {
                  projectModel
                      .getUndoRedoStack()
                      .push(
                          new Consequence.StepConsequence(
                              editedModelTrack,
                              activeClipId,
                              modelRow,
                              activeCol,
                              oldStep,
                              newStep));
                }
              }
            }
            refresh();
          }
        });
    popup.add(pasteItem);

    Point localPt = new Point(screenPos);
    SwingUtilities.convertPointFromScreen(localPt, SwingGridPanel.this);
    popup.show(SwingGridPanel.this, localPt.x, localPt.y);
  }

  private void showStepPropertiesDialog(int row, int col) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    int activeCol = getActiveCol(row, col);
    int engineRow = baseTrackId + modelRow;
    double curVel = bridge.getVelocity(engineRow, activeCol);
    int curIt = bridge.getIterance(engineRow, activeCol);
    int curFill = (int) (bridge.getStepFill(engineRow, activeCol) * 100);
    StepPropertiesDialog dlg =
        new StepPropertiesDialog(
            (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
            (int) (curVel * 100),
            curIt,
            curFill);
    dlg.setVisible(true);
    if (dlg.isConfirmed()) {
      int newVel = dlg.getVelocity();
      int newIt = dlg.getIterance();
      int newFill = dlg.getFill();
      if (newVel != (int) (curVel * 100) || newIt != curIt || newFill != curFill) {
        applyStepProperties(row, col, newVel / 100.0, newIt, newFill / 100.0);
      }
    }
  }

  private void applyStepProperties(int row, int col, double vel, int iterance, double fill) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    int activeCol = getActiveCol(row, col);
    int engineRow = baseTrackId + modelRow;
    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
    int pitch = isSynthMode ? (((24 - 1) - modelRow) + 60) : 0;

    org.chuck.deluge.model.StepData oldStep = null;
    org.chuck.deluge.model.TrackModel tModel = null;
    org.chuck.deluge.model.ClipModel cModel = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
        oldStep = cModel.getStep(modelRow, activeCol);
      }
    }

    bridge.setVelocity(engineRow, activeCol, vel);
    bridge.setIterance(engineRow, activeCol, iterance);
    bridge.setStepFill(engineRow, activeCol, fill);

    if (cModel != null) {
      boolean st = bridge.getStep(engineRow, activeCol);
      double prob = bridge.getStepProbability(engineRow, activeCol);
      double gate = bridge.getGate(engineRow, activeCol);
      org.chuck.deluge.model.StepData newStep =
          new org.chuck.deluge.model.StepData(
              st, (float) vel, (float) gate, (float) prob, pitch, iterance, (float) fill);
      cModel.setStep(modelRow, activeCol, newStep);
      if (oldStep != null && projectModel != null) {
        projectModel
            .getUndoRedoStack()
            .push(
                new Consequence.StepConsequence(
                    editedModelTrack, activeClipId, modelRow, activeCol, oldStep, newStep));
      }
    }
    fireProjectChanged();
  }

  private void applyVelocity(int row, int col, double vel) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    int activeCol = getActiveCol(row, col);
    int engineRow = baseTrackId + modelRow;
    int iter = bridge.getIterance(engineRow, activeCol);
    double fill = bridge.getStepFill(engineRow, activeCol);
    applyStepProperties(row, col, vel, iter, fill);
  }

  private void saveStepProbability(int row, int col, double prob) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    int activeCol = getActiveCol(row, col);
    int engineRow = baseTrackId + modelRow;
    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
    int pitch = isSynthMode ? (((24 - 1) - modelRow) + 60) : 0;

    org.chuck.deluge.model.StepData oldStep = null;
    org.chuck.deluge.model.TrackModel tModel = null;
    org.chuck.deluge.model.ClipModel cModel = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
        oldStep = cModel.getStep(modelRow, activeCol);
      }
    }

    bridge.setStepProbability(engineRow, activeCol, prob);

    if (cModel != null) {
      boolean st = bridge.getStep(engineRow, activeCol);
      double vel = bridge.getVelocity(engineRow, activeCol);
      double gate = bridge.getGate(engineRow, activeCol);
      int iter = bridge.getIterance(engineRow, activeCol);
      double fill = bridge.getStepFill(engineRow, activeCol);
      org.chuck.deluge.model.StepData newStep =
          new org.chuck.deluge.model.StepData(
              st, (float) vel, (float) gate, (float) prob, pitch, iter, (float) fill);
      cModel.setStep(modelRow, activeCol, newStep);
      if (oldStep != null && projectModel != null) {
        projectModel
            .getUndoRedoStack()
            .push(
                new Consequence.StepConsequence(
                    editedModelTrack, activeClipId, modelRow, activeCol, oldStep, newStep));
      }
    }
    fireProjectChanged();
  }

  private void handleStepTied(int row, int colStart, int colEnd) {
    if (bridge == null) return;
    int start = Math.min(colStart, colEnd);
    int end = Math.max(colStart, colEnd);

    int startModelCol = getActiveCol(row, start);
    int endModelCol = getActiveCol(row, end);
    int modelRow = getModelRow(row);
    int engineRow = baseTrackId + modelRow;
    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;

    boolean startActive = bridge.getStep(engineRow, startModelCol);
    double vel = startActive ? bridge.getVelocity(engineRow, startModelCol) : 0.8;
    double prob = startActive ? bridge.getStepProbability(engineRow, startModelCol) : 1.0;
    int pitch = isSynthMode ? (((24 - 1) - modelRow) + 60) : 0;
    int iter = startActive ? bridge.getIterance(engineRow, startModelCol) : 0;
    double fill = startActive ? bridge.getStepFill(engineRow, startModelCol) : 0.0;

    org.chuck.deluge.model.TrackModel tModel = null;
    org.chuck.deluge.model.ClipModel cModel = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
      }
    }

    // Since we drag visually, we convert visual column coordinates to model column coordinates
    for (int c = start; c <= end; c++) {
      int activeCol = getActiveCol(row, c);
      org.chuck.deluge.model.StepData oldStep =
          (cModel != null) ? cModel.getStep(modelRow, activeCol) : null;
      double stepGate = (c == end) ? 0.5 : 1.0;

      bridge.setStep(engineRow, activeCol, true);
      bridge.setVelocity(engineRow, activeCol, vel);
      bridge.setGate(engineRow, activeCol, stepGate);
      bridge.setStepProbability(engineRow, activeCol, prob);
      bridge.setIterance(engineRow, activeCol, iter);
      bridge.setStepFill(engineRow, activeCol, fill);

      if (cModel != null) {
        org.chuck.deluge.model.StepData newStep =
            new org.chuck.deluge.model.StepData(
                true, (float) vel, (float) stepGate, (float) prob, pitch, iter, (float) fill);
        cModel.setStep(modelRow, activeCol, newStep);
        if (oldStep != null && projectModel != null) {
          projectModel
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      editedModelTrack, activeClipId, modelRow, activeCol, oldStep, newStep));
        }
      }
    }
    fireProjectChanged();
  }

  private void handleDragPreview(int row, int colStart, int colCurrent) {
    int start = Math.min(colStart, colCurrent);
    int end = Math.max(colStart, colCurrent);
    int rowsToScan = (viewMode == GridViewMode.CLIP) ? voiceRowCount : gridMode.rows;

    int visRow = -1;
    for (int t = 0; t < rowsToScan; t++) {
      if (t == row) {
        visRow = t;
        break;
      }
    }
    if (visRow == -1) return;

    for (int c = 0; c < columnCount; c++) {
      if (pads[visRow][c] instanceof DelugePadButton pad) {
        boolean tiedInModel = false;
        if (bridge != null) {
          int modelRow = getModelRow(row);
          int engineRow = baseTrackId + modelRow;
          int activeCol = getActiveCol(row, c);
          tiedInModel = bridge.getGate(engineRow, activeCol) >= 0.99;
        }
        pad.setTied(tiedInModel || (c >= start && c <= end));
      }
    }
  }

  public void handleShiftHover(int row, int col) {
    if (row < 0 || row >= 8 || col < 0 || col >= 16) return;
    String param = SHIFT_LABELS[row][col];
    if (param == null || param.isEmpty()) return;

    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
    org.chuck.deluge.model.TrackModel genericTrack = projectModel.getTracks().get(editedModelTrack);

    boolean applicable = isParamApplicable(param, row, col, genericTrack);
    if (!applicable) {
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplay("----", "----");
      }
      return;
    }

    String code = getParamShortCode(param);
    String valStr = getParamFormattedValue(param, row, col);
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplay(code, valStr);
    }
  }

  public void handleShiftHoverExit() {
    if (activeShiftParam != null) {
      String code = getParamShortCode(activeShiftParam);
      String valStr = getParamFormattedValue(activeShiftParam, activeShiftRow, activeShiftCol);
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplay(code, valStr);
      }
    } else {
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplay(null, null);
      }
    }
  }

  public void setClonePreview(int startR, int startC, int currR, int currC) {
    this.clonePreviewStartRow = startR;
    this.clonePreviewStartCol = startC;
    this.clonePreviewCurrentRow = currR;
    this.clonePreviewCurrentCol = currC;
    refresh();
  }

  public void duplicateStep(int startRow, int startCol, int targetRow, int targetCol) {
    if (bridge == null) return;
    int trackLen = bridge.getTrackLength(baseTrackId);

    int srcRow = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + startRow : startRow);
    int srcCol = (trackLen < stepCount) ? (startCol % trackLen) : (startCol + scrollOffsetX);

    int dstRow =
        baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + targetRow : targetRow);
    int dstCol = (trackLen < stepCount) ? (targetCol % trackLen) : (targetCol + scrollOffsetX);

    // Read step parameters
    boolean active = bridge.getStep(srcRow, srcCol);
    double vel = bridge.getVelocity(srcRow, srcCol);
    double gate = bridge.getGate(srcRow, srcCol);
    int pitch = bridge.getPitch(srcRow, srcCol);
    double prob = bridge.getStepProbability(srcRow, srcCol);
    double fill = bridge.getStepFill(srcRow, srcCol);
    double stepFilterVal = bridge.getStepFilter(srcRow, srcCol);
    double stepResVal = bridge.getStepRes(srcRow, srcCol);
    double stepPanVal = bridge.getStepPan(srcRow, srcCol);
    double stepDelayVal = bridge.getStepDelay(srcRow, srcCol);
    double stepReverbVal = bridge.getStepReverb(srcRow, srcCol);

    // Save to target step
    bridge.setStep(dstRow, dstCol, active);
    bridge.setVelocity(dstRow, dstCol, vel);
    bridge.setGate(dstRow, dstCol, gate);
    bridge.setPitch(dstRow, dstCol, pitch);
    bridge.setStepProbability(dstRow, dstCol, prob);
    bridge.setStepFill(dstRow, dstCol, fill);
    bridge.setStepFilter(dstRow, dstCol, stepFilterVal);
    bridge.setStepRes(dstRow, dstCol, stepResVal);
    bridge.setStepPan(dstRow, dstCol, stepPanVal);
    bridge.setStepDelay(dstRow, dstCol, stepDelayVal);
    bridge.setStepReverb(dstRow, dstCol, stepReverbVal);

    // Push Undo/Redo step copy event!
    java.util.ArrayList<Consequence> steps = new java.util.ArrayList<>();
    steps.add(
        new Consequence.StepConsequence(
            editedModelTrack,
            activeClipId,
            targetRow,
            targetCol,
            org.chuck.deluge.model.StepData.of(
                active, (float) vel, (float) gate, (float) prob, (int) fill),
            org.chuck.deluge.model.StepData.empty()));
    if (projectModel != null) {
      projectModel
          .getUndoRedoStack()
          .push(new Consequence.CompoundConsequence("Clone step to " + targetCol, steps));
    }

    fireProjectChanged();
    refresh();
  }

  public void hotSwapTrackSample(int modelRow, int visibleRow, java.io.File soundFile) {
    if (projectModel == null) return;
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();
    if (modelRow < 0 || modelRow >= tracks.size()) return;

    org.chuck.deluge.model.TrackModel track = tracks.get(modelRow);

    if (viewMode == GridViewMode.CLIP
        && track instanceof org.chuck.deluge.model.KitTrackModel kit) {
      java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
      int drumIdx = sounds.size() - 1 - visibleRow;
      if (drumIdx >= 0 && drumIdx < sounds.size()) {
        org.chuck.deluge.model.Drum drum = sounds.get(drumIdx);
        if (drum instanceof org.chuck.deluge.model.SoundDrum soundDrum) {
          soundDrum.setSamplePath(soundFile.getAbsolutePath());
        }
        if (bridge != null) {
          bridge.setSamplePath(modelRow, soundFile.getAbsolutePath());
        }
        if (SwingDelugeApp.mainInstance != null) {
          SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("SMPL", "SWAP");
        }
        fireProjectChanged();
        refresh();
      }
    } else if (track instanceof org.chuck.deluge.model.AudioTrackModel audioTrack) {
      if (audioTrack.getAudioClips().isEmpty()) {
        org.chuck.deluge.model.AudioTrackModel.AudioClip clip =
            new org.chuck.deluge.model.AudioTrackModel.AudioClip();
        clip.setFilePath(soundFile.getAbsolutePath());
        audioTrack.getAudioClips().add(clip);
      } else {
        audioTrack.getAudioClips().get(0).setFilePath(soundFile.getAbsolutePath());
      }
      if (bridge != null) {
        bridge.setSamplePath(modelRow, soundFile.getAbsolutePath());
      }
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("SMPL", "SWAP");
      }
      fireProjectChanged();
      refresh();
    }
  }

  public void handleShiftClick(int row, int col, Point localPos, Component comp) {
    if (row < 0 || row >= 8 || col < 0 || col >= 16) return;
    String param = SHIFT_LABELS[row][col];
    if (param == null || param.isEmpty()) return;

    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
    org.chuck.deluge.model.TrackModel genericTrack = projectModel.getTracks().get(editedModelTrack);

    // Check parameter applicability first
    boolean applicable = isParamApplicable(param, row, col, genericTrack);
    if (!applicable) {
      JPopupMenu tooltip = new JPopupMenu();
      tooltip.setBackground(new Color(0x2a, 0x15, 0x15));
      tooltip.setBorder(BorderFactory.createLineBorder(new Color(0xaa, 0x33, 0x33)));
      JLabel alert = new JLabel("  PARAMETER NOT APPLICABLE  ");
      alert.setForeground(new Color(0xff, 0x55, 0x55));
      alert.setFont(new Font("SansSerif", Font.BOLD, 10));
      tooltip.add(alert);
      tooltip.show(comp, localPos.x, localPos.y);
      Timer dismiss = new Timer(1500, ev -> tooltip.setVisible(false));
      dismiss.setRepeats(false);
      dismiss.start();
      return;
    }

    // Extract track references (cast to SynthTrackModel for synth-specific operations)
    org.chuck.deluge.model.SynthTrackModel track =
        (genericTrack instanceof org.chuck.deluge.model.SynthTrackModel)
            ? (org.chuck.deluge.model.SynthTrackModel) genericTrack
            : null;

    boolean isRotary =
        org.chuck.deluge.project.PreferencesManager.getShiftInteractionMode()
            == org.chuck.deluge.project.PreferencesManager.ShiftInteractionMode.ROTARY_ENCODER;
    if (isRotary) {
      this.activeShiftParam = param;
      this.activeShiftRow = row;
      this.activeShiftCol = col;
      String code = getParamShortCode(param);
      String valStr = getParamFormattedValue(param, row, col);
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplay(code, valStr);
      }
      refresh();
      return;
    }

    JPopupMenu popup = new JPopupMenu();
    popup.setBackground(new Color(0x18, 0x18, 0x1c));
    popup.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));

    JPanel wrapper = new JPanel(new BorderLayout(5, 5));
    wrapper.setBackground(new Color(0x18, 0x18, 0x1c));
    wrapper.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

    JLabel title = new JLabel(param.toUpperCase());
    title.setForeground(new Color(0x00, 0xff, 0xcc));
    title.setFont(new Font("SansSerif", Font.BOLD, 10));
    wrapper.add(title, BorderLayout.NORTH);

    int initVal = 50;
    String initialLabel = "";
    final int envIdx = (col == 9) ? 1 : 0;

    switch (param) {
      case "CUTOFF":
        if (row == 0 && track != null) { // LPF/HPF Cutoff
          float freq = (col == 8) ? track.getLpfFreq() : track.getHpfFreq();
          initVal = (int) (100.0 * Math.log(freq / 20.0) / Math.log(1000.0));
          initialLabel = String.format("%.0f Hz", freq);
        } else if (row == 1 && track != null) { // LPF/HPF Resonance
          float res = (col == 8) ? track.getLpfRes() : track.getHpfRes();
          initVal = (int) (res * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "RESONANCE":
        if (track != null) {
          float resVal = (col == 8) ? track.getLpfRes() : track.getHpfRes();
          initVal = (int) (resVal * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "ATTACK":
        if (track != null) {
          float att = track.getEnv(envIdx).attack();
          initVal = (int) (att * 10.0f);
          initialLabel = String.format("%.2f s", att);
        }
        break;
      case "DECAY":
        if (track != null) {
          float dec = track.getEnv(envIdx).decay();
          initVal = (int) (dec * 10.0f);
          initialLabel = String.format("%.2f s", dec);
        }
        break;
      case "SUSTAIN":
        if (track != null) {
          float sus = track.getEnv(envIdx).sustain();
          initVal = (int) (sus * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "RELEASE":
        if (track != null) {
          float rel = track.getEnv(envIdx).release();
          initVal = (int) (rel * 10.0f);
          initialLabel = String.format("%.2f s", rel);
        }
        break;
      case "PAN":
        if (row == 4 && col == 6) { // Master pan
          initVal = (int) (genericTrack.getPan() * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "LEVEL":
        if (row == 7 && col == 6) { // Master volume
          initVal = (int) (genericTrack.getVolume() * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        } else if (row == 7 && (col == 2 || col == 3) && track != null) { // Osc levels
          initVal = (int) (track.getOscMix() * 100.0f);
          initialLabel = String.format("%d%%", initVal);
        }
        break;
      case "GLIDE":
        initVal = (int) (track.getPortamento() * 50.0f);
        initialLabel = String.format("%.2f s", track.getPortamento());
        break;
    }

    JSlider slider = new JSlider(0, 100, Math.max(0, Math.min(100, initVal)));
    slider.setBackground(new Color(0x12, 0x12, 0x14));
    slider.setForeground(new Color(0x00, 0xff, 0xcc));
    slider.setPreferredSize(new Dimension(150, 18));
    slider.setOpaque(false);
    slider.setFocusable(false);
    slider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(slider) {
          @Override
          public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + (trackRect.height / 2) - 2;
            g2.setColor(new Color(0x66, 0x66, 0x6e));
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);
            int thumbPos = thumbRect.x + (thumbRect.width / 2);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.fillRoundRect(trackRect.x, cy, Math.max(0, thumbPos - trackRect.x), 4, 2, 2);
            g2.dispose();
          }

          @Override
          public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Color.WHITE);
            g2.fillOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.drawOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.dispose();
          }
        });
    wrapper.add(slider, BorderLayout.CENTER);

    JLabel valueLabel = new JLabel(initialLabel);
    valueLabel.setForeground(Color.LIGHT_GRAY);
    valueLabel.setFont(new Font("Monospaced", Font.PLAIN, 10));
    wrapper.add(valueLabel, BorderLayout.SOUTH);

    slider.addChangeListener(
        e -> {
          int val = slider.getValue();
          switch (param) {
            case "CUTOFF":
              if (row == 0) {
                float freq = (float) (20.0 * Math.pow(1000.0, val / 100.0));
                if (col == 8) track.setLpfFreq(freq);
                else track.setHpfFreq(freq);
                valueLabel.setText(String.format("%.0f Hz", freq));
              } else if (row == 1) {
                float res = val / 100.0f;
                if (col == 8) track.setLpfRes(res);
                else track.setHpfRes(res);
                valueLabel.setText(String.format("%d%%", val));
              }
              break;
            case "RESONANCE":
              float res = val / 100.0f;
              if (col == 8) track.setLpfRes(res);
              else track.setHpfRes(res);
              valueLabel.setText(String.format("%d%%", val));
              break;
            case "ATTACK":
              float aTime = val / 10.0f;
              var attEnv = track.getEnv(envIdx);
              track.setEnv(
                  envIdx,
                  new org.chuck.deluge.model.EnvelopeModel(
                      aTime,
                      attEnv.decay(),
                      attEnv.sustain(),
                      attEnv.release(),
                      attEnv.target(),
                      attEnv.amount()));
              valueLabel.setText(String.format("%.2f s", aTime));
              break;
            case "DECAY":
              float dTime = val / 10.0f;
              var decEnv = track.getEnv(envIdx);
              track.setEnv(
                  envIdx,
                  new org.chuck.deluge.model.EnvelopeModel(
                      decEnv.attack(),
                      dTime,
                      decEnv.sustain(),
                      decEnv.release(),
                      decEnv.target(),
                      decEnv.amount()));
              valueLabel.setText(String.format("%.2f s", dTime));
              break;
            case "SUSTAIN":
              float sLevel = val / 100.0f;
              var susEnv = track.getEnv(envIdx);
              track.setEnv(
                  envIdx,
                  new org.chuck.deluge.model.EnvelopeModel(
                      susEnv.attack(),
                      susEnv.decay(),
                      sLevel,
                      susEnv.release(),
                      susEnv.target(),
                      susEnv.amount()));
              valueLabel.setText(String.format("%d%%", val));
              break;
            case "RELEASE":
              float rTime = val / 10.0f;
              var relEnv = track.getEnv(envIdx);
              track.setEnv(
                  envIdx,
                  new org.chuck.deluge.model.EnvelopeModel(
                      relEnv.attack(),
                      relEnv.decay(),
                      relEnv.sustain(),
                      rTime,
                      relEnv.target(),
                      relEnv.amount()));
              valueLabel.setText(String.format("%.2f s", rTime));
              break;
            case "PAN":
              if (row == 4 && col == 6) {
                float p = val / 100.0f;
                genericTrack.setPan(p);
                valueLabel.setText(String.format("%d%%", val));
              }
              break;
            case "LEVEL":
              if (row == 7 && col == 6) {
                float vol = val / 100.0f;
                genericTrack.setVolume(vol);
                valueLabel.setText(String.format("%d%%", val));
              } else if (row == 7 && (col == 2 || col == 3) && track != null) {
                float mixVal = val / 100.0f;
                track.setOscMix(mixVal);
                valueLabel.setText(String.format("%d%%", val));
              }
              break;
            case "GLIDE":
              float port = val / 50.0f;
              track.setPortamento(port);
              valueLabel.setText(String.format("%.2f s", port));
              break;
          }
          fireProjectChanged();
        });

    popup.add(wrapper);
    popup.show(comp, localPos.x, localPos.y);
  }

  private String getShiftShortcutTooltip(
      int row, int col, boolean applicable, org.chuck.deluge.model.TrackModel track) {
    String paramName = SHIFT_LABELS[row][col];
    if (paramName == null || paramName.isEmpty()) {
      return null;
    }
    String prefix = getGroupPrefix(col);
    String fullParam = (prefix != null) ? prefix + " " + paramName : paramName;

    String description = getParamDescription(paramName);
    String trackTypeStr =
        (track instanceof org.chuck.deluge.model.SynthTrackModel)
            ? "Synth Track"
            : (track instanceof org.chuck.deluge.model.KitTrackModel ? "Kit Track" : "Audio Track");
    String header =
        applicable
            ? ""
            : "<font color='#ff6666'><b>[NOT APPLICABLE FOR "
                + trackTypeStr.toUpperCase()
                + "]</b></font><br>";

    return String.format(
        "<html><body style='font-size: 9px; font-family: sans-serif; width: 180px;'>"
            + "%s"
            + "<b>Shift Shortcut: %s</b><br>"
            + "• Group: %s<br>"
            + "• Parameter: <b>%s</b><br>"
            + "• Action: %s"
            + "</body></html>",
        header, fullParam, (prefix != null ? prefix : "None"), paramName, description);
  }

  private String getParamDescription(String paramName) {
    switch (paramName) {
      case "WAVE FORM":
        return "Selects waveform shape (SINE, TRIANGLE, SAW, SQUARE, WAVETABLE).";
      case "NOISE":
        return "Toggles or adjusts white noise level.";
      case "OSC SYNC":
        return "Toggles hard oscillator pitch synchronization.";
      case "DIRECTION":
        return "Adjusts sample playback direction (Forward vs Reverse).";
      case "SATURATE":
        return "Applies analog saturation distortion gain.";
      case "CUTOFF":
        return "Adjusts Lowpass Filter Cutoff frequency (20Hz - 20kHz).";
      case "RESONANCE":
        return "Adjusts Lowpass Filter Resonance Q feedback factor.";
      case "HPF CUTOFF":
        return "Adjusts Highpass Filter Cutoff frequency.";
      case "HPF RES":
        return "Adjusts Highpass Filter Resonance Q feedback.";
      case "ATTACK":
        return "Adjusts Envelope Attack duration time (seconds).";
      case "DECAY":
        return "Adjusts Envelope Decay duration time.";
      case "SUSTAIN":
        return "Adjusts Envelope Sustain level height percentage.";
      case "RELEASE":
        return "Adjusts Envelope Release duration time.";
      case "SPEED":
        return "Adjusts LFO Speed rate frequency (Hz or subdivisions).";
      case "DEPTH":
        return "Adjusts LFO Modulation depth amount.";
      case "FEEDBACK":
        return "Adjusts Delay feedback or Mod FX regeneration level.";
      case "DELAY SEND":
        return "Adjusts master Delay send bus amount.";
      case "REVERB SEND":
        return "Adjusts master Reverb send bus amount.";
      case "VOLUME":
        return "Adjusts master channel volume level (0.0 - 1.5 multiplier).";
      case "PAN":
        return "Adjusts stereo balance panning position.";
      case "PITCH":
        return "Shifts note pitches (semitones / cents).";
      case "ARPRATE":
        return "Adjusts Arpeggiator rate clock speed.";
      case "GATE":
        return "Adjusts step gate length or Arp gate duration.";
      case "VELOCITY":
        return "Adjusts key trigger strike velocity.";
      default:
        return "Selects or adjusts the " + paramName.toLowerCase() + " parameter.";
    }
  }

  private boolean isParamApplicable(
      String param, int row, int col, org.chuck.deluge.model.TrackModel track) {
    if (track == null || param == null || param.isEmpty()) return false;
    if (track instanceof org.chuck.deluge.model.SynthTrackModel) {
      return true;
    }
    // For non-synth tracks (Kit/Audio), only master track sends are applicable!
    switch (param) {
      case "LEVEL":
        return (row == 7 && col == 6);
      case "PAN":
        return (row == 4 && col == 6);
      case "SIZE":
        return (row == 0 && col == 13);
      case "RATE":
        return (row == 7 && col == 14);
      case "SDCHAIN":
        return (row == 3 && col == 15);
      default:
        return false;
    }
  }

  private String getGroupPrefix(int colId) {
    if (colId == 0) return "S1";
    if (colId == 1) return "S2";
    if (colId == 2) return "OSC1";
    if (colId == 3) return "OSC2";
    if (colId == 4) return "FM1";
    if (colId == 5) return "FM2";
    if (colId == 8) return "ENV1";
    if (colId == 9) return "ENV2";
    if (colId == 12) return "LFO1";
    if (colId == 13) return "LFO2";
    return null;
  }

  private String getParamShortCode(String param) {
    if (param == null) return "----";
    switch (param.toUpperCase()) {
      case "WAVE FORM":
        return "WAVE";
      case "INTER POLATION":
        return "INTR";
      case "BROWSE":
        return "BROW";
      case "RECORD":
        return "REC";
      case "PITCH SPEED":
        return "PTSP";
      case "SPEED":
        return "SPED";
      case "REVERSE":
        return "REV";
      case "MODE":
        return "MODE";
      case "NOISE":
        return "NOIS";
      case "OSC SYNC":
        return "SYNC";
      case "WAVETABLE":
        return "WTBL";
      case "FEED BACK":
        return "FDBK";
      case "RETRIG PHASE":
        return "RPHS";
      case "PW":
        return "PW";
      case "TYPE":
        return "TYPE";
      case "TRANS POSE":
        return "TRAN";
      case "LEVEL":
        return "LEVEL";
      case "DIRECTION":
        return "DIR";
      case "DESTI NATION":
        return "DEST";
      case "RETRIG":
        return "RTRG";
      case "SATURATE":
        return "SAT";
      case "BITCRUSH":
        return "CRSH";
      case "DECIMATE":
        return "DECI";
      case "SYNTH MODE":
        return "MODE";
      case "UNISON VOICES":
        return "UNIS";
      case "UNISON DETUNE":
        return "DETN";
      case "VOICE PRIORITY":
        return "PRIO";
      case "POLY PHONY":
        return "POLY";
      case "GLIDE":
        return "GLID";
      case "CUTOFF":
        return "CUT";
      case "RESONANCE":
        return "RES";
      case "SLOPE":
        return "SLOP";
      case "SEND":
        return "SEND";
      case "SHAPE":
        return "SHAP";
      case "ATTACK":
        return "ATK";
      case "DECAY":
        return "DECY";
      case "SUSTAIN":
        return "SUST";
      case "RELEASE":
        return "REL";
      case "VOL DUCK":
        return "DUCK";
      case "ARP MODE":
        return "AMOD";
      case "ARP OCTAVES":
        return "AOCT";
      case "ARP GATE":
        return "AGAT";
      case "ARP SYNC":
        return "ASYNC";
      case "ARP RATE":
        return "ARAT";
      case "RATE":
        return "RATE";
      case "DEPTH":
        return "DPTH";
      case "FDBACK":
        return "FDBK";
      case "OFFSET":
        return "OFST";
      case "SIZE":
        return "SIZE";
      case "DAMP":
        return "DAMP";
      case "WIDTH":
        return "WDTH";
      case "PAN":
        return "PAN";
      case "ENV 1":
        return "ENV1";
      case "ENV 2":
        return "ENV2";
      case "LFO 1":
        return "LFO1";
      case "LFO 2":
        return "LFO2";
      case "MONO/ STEREO":
        return "MSTE";
      case "AMOUNT":
        return "AMT";
      case "DIGI/ ANALOG":
        return "DIGI";
      case "SDCHAIN":
        return "SDCH";
      case "NOTE":
        return "NOTE";
      case "RANDOM":
        return "RAND";
      case "VELOCITY":
        return "VEL";
      case "AFTER TOUCH":
        return "AFTC";
      default:
        return param.length() > 4 ? param.substring(0, 4) : param;
    }
  }

  private String getParamFormattedValue(String param, int row, int col) {
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return "--";
    org.chuck.deluge.model.TrackModel genericTrack = projectModel.getTracks().get(editedModelTrack);
    org.chuck.deluge.model.SynthTrackModel track =
        (genericTrack instanceof org.chuck.deluge.model.SynthTrackModel)
            ? (org.chuck.deluge.model.SynthTrackModel) genericTrack
            : null;

    final int envIdx = (col == 9) ? 1 : 0;

    switch (param) {
      case "CUTOFF":
        if (row == 0 && track != null) {
          float freq = (col == 8) ? track.getLpfFreq() : track.getHpfFreq();
          return (freq >= 1000.0f)
              ? String.format("%.1fk", freq / 1000.0f)
              : String.format("%.0f", freq);
        } else if (row == 1 && track != null) {
          float res = (col == 8) ? track.getLpfRes() : track.getHpfRes();
          return String.format("%d%%", (int) (res * 100.0f));
        }
        break;
      case "RESONANCE":
        if (track != null) {
          float res = (col == 8) ? track.getLpfRes() : track.getHpfRes();
          return String.format("%d%%", (int) (res * 100.0f));
        }
        break;
      case "ATTACK":
        if (track != null) {
          return String.format("%.2fs", track.getEnv(envIdx).attack());
        }
        break;
      case "DECAY":
        if (track != null) {
          return String.format("%.2fs", track.getEnv(envIdx).decay());
        }
        break;
      case "SUSTAIN":
        if (track != null) {
          return String.format("%d%%", (int) (track.getEnv(envIdx).sustain() * 100.0f));
        }
        break;
      case "RELEASE":
        if (track != null) {
          return String.format("%.2fs", track.getEnv(envIdx).release());
        }
        break;
      case "PAN":
        if (row == 4 && col == 6) {
          int panVal = (int) (genericTrack.getPan() * 100.0f);
          if (panVal == 50) return "C";
          return (panVal < 50)
              ? String.format("L%d", 50 - panVal)
              : String.format("R%d", panVal - 50);
        }
        break;
      case "LEVEL":
        if (row == 7 && col == 6) {
          return String.format("%d%%", (int) (genericTrack.getVolume() * 100.0f));
        } else if (row == 7 && (col == 2 || col == 3) && track != null) {
          return String.format("%d%%", (int) (track.getOscMix() * 100.0f));
        }
        break;
      case "GLIDE":
        if (track != null) {
          return String.format("%.2fs", track.getPortamento());
        }
        break;
    }
    return "--";
  }

  public void adjustRotaryParameter(int delta) {
    if (activeShiftParam == null
        || projectModel == null
        || editedModelTrack >= projectModel.getTracks().size()) return;
    org.chuck.deluge.model.TrackModel genericTrack = projectModel.getTracks().get(editedModelTrack);
    org.chuck.deluge.model.SynthTrackModel track =
        (genericTrack instanceof org.chuck.deluge.model.SynthTrackModel)
            ? (org.chuck.deluge.model.SynthTrackModel) genericTrack
            : null;

    final int envIdx = (activeShiftCol == 9) ? 1 : 0;

    switch (activeShiftParam) {
      case "CUTOFF":
        if (activeShiftRow == 0 && track != null) {
          float freq = (activeShiftCol == 8) ? track.getLpfFreq() : track.getHpfFreq();
          freq = (float) (freq * Math.pow(1.05, delta));
          freq = Math.max(20.0f, Math.min(20000.0f, freq));
          if (activeShiftCol == 8) track.setLpfFreq(freq);
          else track.setHpfFreq(freq);
        } else if (activeShiftRow == 1 && track != null) {
          float res = (activeShiftCol == 8) ? track.getLpfRes() : track.getHpfRes();
          res = Math.max(0.0f, Math.min(1.0f, res + delta * 0.02f));
          if (activeShiftCol == 8) track.setLpfRes(res);
          else track.setHpfRes(res);
        }
        break;
      case "RESONANCE":
        if (track != null) {
          float res = (activeShiftCol == 8) ? track.getLpfRes() : track.getHpfRes();
          res = Math.max(0.0f, Math.min(1.0f, res + delta * 0.02f));
          if (activeShiftCol == 8) track.setLpfRes(res);
          else track.setHpfRes(res);
        }
        break;
      case "ATTACK":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float a = Math.max(0.0f, Math.min(10.0f, old.attack() + delta * 0.05f));
          track.setEnv(
              envIdx,
              new org.chuck.deluge.model.EnvelopeModel(
                  a, old.decay(), old.sustain(), old.release(), old.target(), old.amount()));
        }
        break;
      case "DECAY":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float d = Math.max(0.0f, Math.min(10.0f, old.decay() + delta * 0.05f));
          track.setEnv(
              envIdx,
              new org.chuck.deluge.model.EnvelopeModel(
                  old.attack(), d, old.sustain(), old.release(), old.target(), old.amount()));
        }
        break;
      case "SUSTAIN":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float s = Math.max(0.0f, Math.min(1.0f, old.sustain() + delta * 0.02f));
          track.setEnv(
              envIdx,
              new org.chuck.deluge.model.EnvelopeModel(
                  old.attack(), old.decay(), s, old.release(), old.target(), old.amount()));
        }
        break;
      case "RELEASE":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float r = Math.max(0.0f, Math.min(10.0f, old.release() + delta * 0.05f));
          track.setEnv(
              envIdx,
              new org.chuck.deluge.model.EnvelopeModel(
                  old.attack(), old.decay(), old.sustain(), r, old.target(), old.amount()));
        }
        break;
      case "PAN":
        if (activeShiftRow == 4 && activeShiftCol == 6) {
          float p = Math.max(0.0f, Math.min(1.0f, genericTrack.getPan() + delta * 0.02f));
          genericTrack.setPan(p);
        }
        break;
      case "LEVEL":
        if (activeShiftRow == 7 && activeShiftCol == 6) {
          float vol = Math.max(0.0f, genericTrack.getVolume() + delta * 0.02f);
          genericTrack.setVolume(vol);
        } else if (activeShiftRow == 7
            && (activeShiftCol == 2 || activeShiftCol == 3)
            && track != null) {
          float mixVal = Math.max(0.0f, Math.min(1.0f, track.getOscMix() + delta * 0.02f));
          track.setOscMix(mixVal);
        }
        break;
      case "GLIDE":
        if (track != null) {
          float port = Math.max(0.0f, Math.min(2.0f, track.getPortamento() + delta * 0.02f));
          track.setPortamento(port);
        }
        break;
    }

    // Update LED readout panel with new value
    String code = getParamShortCode(activeShiftParam);
    String valStr = getParamFormattedValue(activeShiftParam, activeShiftRow, activeShiftCol);
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplay(code, valStr);
    }

    fireProjectChanged();
    refresh();
  }

  private void handleDragCleared() {
    refresh();
  }

  private String getNoteName(int pitchMidi) {
    String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    int noteIdx = pitchMidi % 12;
    int octave = (pitchMidi / 12) - 1;
    return names[noteIdx] + octave;
  }

  private double getMacroValue(int col, org.chuck.deluge.model.TrackModel track) {
    if (track == null) return 0.5;
    switch (col) {
      case 0:
        return Math.max(0.0, Math.min(1.0, track.getVolume() / 1.5));
      case 1:
        return track.getPan();
      case 2:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return (st.getTranspose() + 24) / 48.0;
        }
        return 0.5;
      case 3:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return Math.max(
              0.0, Math.min(1.0, Math.log10(st.getLpfFreq() / 20.0) / Math.log10(1000.0)));
        }
        return 0.8;
      case 4:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return st.getLpfRes();
        }
        return 0.0;
      case 5:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return st.getOscMix();
        }
        return 0.5;
      case 6:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return st.getNoiseVol();
        }
        return 0.0;
      case 7:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st && st.getLfo(0) != null) {
          return st.getLfo(0).rateHz() / 20.0f;
        }
        return 0.2;
      case 8:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return st.getModFxDepth();
        }
        return 0.0;
      case 9:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return st.getDelaySend();
        }
        return 0.0;
      case 10:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return st.getReverbSend();
        }
        return 0.0;
      case 11:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return st.getStutterRate();
        }
        return 0.0;
      case 12:
        if (!track.getClips().isEmpty()) {
          org.chuck.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              if (clip.getStep(r, s).active()) return clip.getStep(r, s).probability();
            }
          }
        }
        return 1.0;
      case 13:
        if (!track.getClips().isEmpty()) {
          org.chuck.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              if (clip.getStep(r, s).active())
                return Math.max(0.0, Math.min(1.0, clip.getStep(r, s).gate() / 2.0f));
            }
          }
        }
        return 0.5;
      case 14:
        if (!track.getClips().isEmpty()) {
          org.chuck.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              if (clip.getStep(r, s).active()) return clip.getStep(r, s).velocity();
            }
          }
        }
        return 0.8;
      case 15:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          return Math.max(0.0, Math.min(1.0, st.getArp().rate() / 2.0f));
        }
        return 0.5;
      default:
        return 0.5;
    }
  }

  private void setMacroValue(int col, double v, org.chuck.deluge.model.TrackModel track) {
    if (track == null) return;
    switch (col) {
      case 0:
        track.setVolume((float) (v * 1.5));
        break;
      case 1:
        track.setPan((float) v);
        break;
      case 2:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          st.setTranspose((int) (v * 48.0 - 24.0));
        }
        break;
      case 3:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          float freq = (float) (20.0 * Math.pow(1000.0, v));
          st.setLpfFreq(freq);
        }
        break;
      case 4:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          st.setLpfRes((float) v);
        }
        break;
      case 5:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          st.setOscMix((float) v);
        }
        break;
      case 6:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          st.setNoiseVol((float) v);
        }
        break;
      case 7:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st && st.getLfo(0) != null) {
          org.chuck.deluge.model.LfoModel oldLfo = st.getLfo(0);
          st.setLfo(
              0,
              new org.chuck.deluge.model.LfoModel(
                  (float) (v * 20.0f),
                  oldLfo.waveform(),
                  oldLfo.depth(),
                  oldLfo.target(),
                  oldLfo.isLocal(),
                  oldLfo.syncLevel(),
                  oldLfo.syncType()));
        }
        break;
      case 8:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          st.setModFxDepth((float) v);
        }
        break;
      case 9:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          st.setDelaySend((float) v);
        }
        break;
      case 10:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          st.setReverbSend((float) v);
        }
        break;
      case 11:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          st.setStutterRate((float) v);
        }
        break;
      case 12:
        if (!track.getClips().isEmpty()) {
          org.chuck.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step.active()) {
                clip.setStep(
                    r,
                    s,
                    org.chuck.deluge.model.StepData.of(
                        step.active(), step.velocity(), step.gate(), (float) v, step.pitch()));
              }
            }
          }
        }
        break;
      case 13:
        if (!track.getClips().isEmpty()) {
          org.chuck.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step.active()) {
                clip.setStep(
                    r,
                    s,
                    org.chuck.deluge.model.StepData.of(
                        step.active(),
                        step.velocity(),
                        (float) (v * 2.0f),
                        step.probability(),
                        step.pitch()));
              }
            }
          }
        }
        break;
      case 14:
        if (!track.getClips().isEmpty()) {
          org.chuck.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              org.chuck.deluge.model.StepData step = clip.getStep(r, s);
              if (step.active()) {
                clip.setStep(
                    r,
                    s,
                    org.chuck.deluge.model.StepData.of(
                        step.active(), (float) v, step.gate(), step.probability(), step.pitch()));
              }
            }
          }
        }
        break;
      case 15:
        if (track instanceof org.chuck.deluge.model.SynthTrackModel st) {
          org.chuck.deluge.model.ArpModel oldArp = st.getArp();
          st.setArp(
              new org.chuck.deluge.model.ArpModel(
                  oldArp.active(),
                  oldArp.mode(),
                  (float) (v * 2.0f),
                  oldArp.octaves(),
                  oldArp.gate(),
                  oldArp.syncLevel(),
                  oldArp.noteMode(),
                  oldArp.octaveMode(),
                  oldArp.stepRepeat(),
                  oldArp.rhythmIndex(),
                  oldArp.seqLength(),
                  oldArp.octaveSpread(),
                  oldArp.gateSpread(),
                  oldArp.velSpread(),
                  oldArp.ratchetAmount(),
                  oldArp.mpeVelocity(),
                  oldArp.syncType(),
                  oldArp.noteProbability(),
                  oldArp.bassProbability(),
                  oldArp.swapProbability(),
                  oldArp.glideProbability(),
                  oldArp.reverseProbability(),
                  oldArp.chordProbability(),
                  oldArp.ratchetProbability(),
                  oldArp.chordPolyphony()));
        }
        break;
    }
  }

  public class MacroSliderButton extends JButton {
    private final int colId;
    private final String paramName;
    private boolean isSliding = false;
    private double value = 0.5;
    private String displayValueStr = "";

    public MacroSliderButton(int colId, String paramName) {
      this.colId = colId;
      this.paramName = paramName;

      setContentAreaFilled(false);
      setBorderPainted(false);
      setFocusPainted(false);
      setOpaque(false);
      setMargin(new java.awt.Insets(0, 0, 0, 0));

      updateValueFromModel();
      if (colId >= 0 && colId < MACRO_TOOLTIPS.length) {
        setToolTipText(MACRO_TOOLTIPS[colId]);
      }

      java.awt.event.MouseAdapter adapter =
          new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
              handleDrag(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
              isSliding = false;
              repaint();
              propagateValueToModel();
              SwingGridPanel.this.fireProjectChanged();
              SwingGridPanel.this.refresh();
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
              handleDrag(e);
            }

            private void handleDrag(java.awt.event.MouseEvent e) {
              double v = 1.0 - (double) e.getY() / getHeight();
              v = Math.max(0.0, Math.min(1.0, v));
              value = v;
              isSliding = true;

              propagateValueToModel();
              updateDisplayValueStr();
              repaint();

              if (SwingDelugeApp.mainInstance != null) {
                SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                    paramName.substring(0, Math.min(4, paramName.length())).toUpperCase(),
                    displayValueStr);
              }
            }
          };

      addMouseListener(adapter);
      addMouseMotionListener(adapter);
    }

    public void updateValueFromModel() {
      if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
      org.chuck.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
      value = getMacroValue(colId, track);
      updateDisplayValueStr();
    }

    private void updateDisplayValueStr() {
      switch (colId) {
        case 0:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 1:
          int panPct = (int) ((value - 0.5) * 200);
          displayValueStr =
              panPct == 0 ? "C" : (panPct < 0 ? "L" + Math.abs(panPct) : "R" + panPct);
          break;
        case 2:
          int semi = (int) (value * 48 - 24);
          displayValueStr = (semi >= 0 ? "+" : "") + semi;
          break;
        case 3:
          float freq = (float) (20.0 * Math.pow(1000.0, value));
          displayValueStr =
              freq >= 1000.0f
                  ? String.format("%.1fk", freq / 1000.0f)
                  : String.format("%.0f", freq);
          break;
        case 4:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 5:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 6:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 7:
          displayValueStr = String.format("%.1fH", value * 20.0f);
          break;
        case 8:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 9:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 10:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 11:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 12:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 13:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 14:
          displayValueStr = (int) (value * 100) + "%";
          break;
        case 15:
          displayValueStr = "SMPL";
          break;
        default:
          displayValueStr = String.format("%.2f", value);
      }
    }

    private void propagateValueToModel() {
      if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
      org.chuck.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
      setMacroValue(colId, value, track);
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

      g2.setColor(new Color(0x15, 0x15, 0x18));
      g2.fillRoundRect(xPad, yPad, rw, rh, arc, arc);

      int barH = (int) (value * rh);
      if (barH > 0) {
        GradientPaint grad =
            new GradientPaint(
                w / 2.0f,
                h - yPad,
                new Color(0x00, 0xe6, 0x76, 90),
                w / 2.0f,
                h - yPad - barH,
                new Color(0x00, 0xb0, 0xff, 140));
        g2.setPaint(grad);
        g2.fillRoundRect(xPad, h - yPad - barH, rw, barH, arc, arc);

        g2.setColor(new Color(0x00, 0xb0, 0xff, 220));
        g2.setStroke(new BasicStroke(1.5f));
        g2.drawLine(xPad + 2, h - yPad - barH, w - xPad - 2, h - yPad - barH);
      }

      g2.setColor(isSliding ? new Color(0x00, 0xb0, 0xff) : new Color(0x2d, 0x2d, 0x35));
      g2.setStroke(new BasicStroke(isSliding ? 1.5f : 1.0f));
      g2.drawRoundRect(xPad, yPad, rw, rh, arc, arc);

      g2.setFont(new Font("SansSerif", Font.BOLD, w > 65 ? 10 : 8));
      FontMetrics fm = g2.getFontMetrics();
      String activeText = isSliding ? displayValueStr : paramName;
      int tx = (w - fm.stringWidth(activeText)) / 2;
      int ty = (h + fm.getAscent() - fm.getLeading()) / 2 - 1;

      g2.setColor(new Color(0, 0, 0, 200));
      g2.drawString(activeText, tx + 1, ty + 1);

      g2.setColor(isSliding ? Color.WHITE : new Color(0xe2, 0xe2, 0xe8));
      g2.drawString(activeText, tx, ty);

      g2.dispose();
    }
  }

  public GridViewMode getViewMode() {
    return viewMode;
  }

  public int getEditedModelTrack() {
    return editedModelTrack;
  }

  public void scrollHorizontally(int cellsOffset) {
    if (bridge == null) return;
    int trackLenH = (bridge != null) ? bridge.getTrackLength(baseTrackId) : stepCount;
    if (trackLenH > stepCount) {
      int maxOffX = trackLenH - stepCount;
      int newOffset = scrollOffsetX + cellsOffset;
      if (newOffset > maxOffX) newOffset = maxOffX;
      if (newOffset < 0) newOffset = 0;
      if (newOffset != scrollOffsetX) {
        scrollOffsetX = newOffset;
        refresh();
      }
    }
  }

  public void scrollVertically(int cellsOffset) {
    if (bridge == null) return;
    int maxOffset = Math.max(0, voiceRowCount - gridMode.rows);
    int newOffset = scrollOffset + cellsOffset;
    if (newOffset > maxOffset) newOffset = maxOffset;
    if (newOffset < 0) newOffset = 0;
    if (newOffset != scrollOffset) {
      scrollOffset = newOffset;
      refresh();
    }
  }

  public void resetHorizontalScroll() {
    if (scrollOffsetX != 0) {
      scrollOffsetX = 0;
      refresh();
    }
  }

  public void resetVerticalScroll() {
    if (scrollOffset != 0) {
      scrollOffset = 0;
      refresh();
    }
  }

  public JButton[][] getPads() {
    return pads;
  }

  public int getColumnCount() {
    return columnCount;
  }

  public int getSoloRow() {
    return soloRow;
  }
}
