package org.deluge.ui;

import java.awt.*;
import java.util.logging.Logger;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.Consequence;
import org.deluge.model.SongSection;

/** Unified 18x8 Grid Panel handling both sequence matrix and clip launch arrangements. */
public class SwingGridPanel extends JPanel {
  private static final Logger LOG = Logger.getLogger(SwingGridPanel.class.getName());
  private final BridgeContract bridge;

  private org.deluge.model.ProjectModel projectModel;
  private java.util.function.BiConsumer<Integer, Integer> onEditRequest;
  private static final int MAX_GRID_ROWS = 128;
  private static final int MAX_GRID_COLS = 26; // max columns: 24 steps + MUTE + SOLO
  private JButton[][] pads = new JButton[MAX_GRID_ROWS][MAX_GRID_COLS];
  private org.rtmidijava.RtMidiOut finalMidiOut;
  private double[] vuLevels = new double[MAX_GRID_ROWS];
  private Timer activeStutterTimer;
  private int auditionMidiNote = -1;
  private org.deluge.engine.FirmwareSound auditionSynth = null;
  public static volatile boolean isLiveRecordModeActive = false;
  private int currentPlayheadStep = -1;
  private boolean[] isOneShotTrack = new boolean[MAX_GRID_ROWS];
  private int activeClipId = 0;
  private int baseTrackId = 0;
  private int editedModelTrack = 0; // model track index currently being edited in CLIP mode
  public static int lockArmedTrack = -1;
  public static int lockArmedStep = -1;

  // Isomorphic keyboard (KEYPLAY) layout, mirroring DelugeFirmware defaults:
  //   noteFromCoords(x, y) = scrollOffset + x + y * rowInterval   (isomorphic.h:44)
  // with rowInterval = kDefaultIsometricRowInterval = 5 and the default
  //   scrollOffset = 60 - (kDisplayHeight >> 2) * 5 = 60 - (8>>2)*5 = 50  (state_data.h:26-29).
  // So the bottom-left pad is MIDI 50 (D), each column +1 semitone, each row up +5.
  private static final int KEYPLAY_BASE_NOTE = 50;
  private static final int KEYPLAY_ROW_INTERVAL = 5;

  /** Isomorphic note for a grid pad: trk is the row from the top (0..7), colId the column. */
  private static int keyplayNote(int trk, int colId) {
    return KEYPLAY_BASE_NOTE + colId + (7 - trk) * KEYPLAY_ROW_INTERVAL;
  }

  /**
   * Drum index for a KEYPLAY pad on a kit track. Mirrors DelugeFirmware's velocity-drums layout at
   * the default zoom (edge_size 1×1): note = x + y*pads_per_row + offset, with y measured from the
   * bottom and pads_per_row = kDisplayWidth = 16 (velocity_drums.cpp:50). The isomorphic layout is
   * instrument-only on hardware (isomorphic.h:39 supportsKit()==false), so kits use this grid.
   */
  private static int keyplayDrumIndex(int trk, int colId) {
    return colId + (7 - trk) * 16;
  }

  /** True when the track currently being edited is a Kit (KEYPLAY uses the drum grid for kits). */
  private boolean isEditedTrackKit() {
    return projectModel != null
        && editedModelTrack < projectModel.getTracks().size()
        && projectModel.getTracks().get(editedModelTrack) instanceof org.deluge.model.KitTrackModel;
  }

  /** Number of drums in the edited kit track, or 0 if not a kit. */
  private int editedKitDrumCount() {
    if (!isEditedTrackKit()) return 0;
    return ((org.deluge.model.KitTrackModel) projectModel.getTracks().get(editedModelTrack))
        .getDrums()
        .size();
  }

  private int soloRow = -1; // -1 = no solo
  private Timer playheadTimer; // single timer for playhead updates, avoids leaks
  private boolean wasSequencerPlaying; // edge-detect stop to flush MIDI notes
  private final java.util.Map<Integer, VUMeterPanel> voiceVuMeters =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final java.util.List<JButton> pageButtons = new java.util.ArrayList<>();
  private final java.util.Map<Integer, VUMeterPanel> trackVuMeters =
      new java.util.concurrent.ConcurrentHashMap<>();
  private Timer globalVuTimer;
  private int scrollOffset = 67; // vertical scroll offset for voice rows in CLIP mode (C4 at top)
  private JScrollBar vertScrollBar;
  private JScrollBar horizScrollBar;
  private int scrollOffsetX = 0; // horizontal scroll offset for step columns in CLIP mode
  private boolean playheadFollowMode = true;
  private boolean isScrollingProgrammatically = false;
  private double preciseScrollAccumulatorX = 0.0;
  private double preciseScrollAccumulatorY = 0.0;
  private int voiceRowCount = 8; // total number of voice rows for current track
  private org.deluge.project.PreferencesManager.GridMode gridMode =
      org.deluge.project.PreferencesManager.getGridMode();
  private int stepCount = 16; // steps per row, derived from gridMode
  int columnCount = 18; // stepCount + 2 (MUTE + SOLO), derived from gridMode

  private int lastColumnCount = -1;
  private int lastVoiceRowCount = -1;
  private GridViewMode lastViewMode = null;
  private org.deluge.project.PreferencesManager.GridMode lastGridMode = null;
  private int lastScrollOffset = -1;
  private int lastScrollOffsetX = -1;
  private int lastPadSz =
      -1; // forces a structural rebuild when the cell size changes (resize/zoom)

  private boolean foldMode = false;
  private final java.util.List<Integer> foldedPitches = new java.util.ArrayList<>();

  public int getRowPitch(int modelRow) {
    boolean isSynth = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      isSynth = t instanceof org.deluge.model.SynthTrackModel;
    }
    if (isSynth && viewMode == GridViewMode.CLIP) {
      if (foldMode && !foldedPitches.isEmpty()) {
        if (modelRow >= 0 && modelRow < foldedPitches.size()) {
          return foldedPitches.get(modelRow);
        }
      }
      return 127 - modelRow;
    }
    return 60; // fallback
  }

  private int getClipRowIndex(
      org.deluge.model.ClipModel cModel, int modelRow, boolean createIfMissing) {
    int pitchMidi = getRowPitch(modelRow);
    for (int r = 0; r < cModel.getRowCount(); r++) {
      if (cModel.getRowYNote(r) == pitchMidi) {
        return r;
      }
    }
    if (createIfMissing) {
      int newRowIdx = cModel.getRowCount();
      cModel.setRowCount(newRowIdx + 1);
      cModel.setRowYNote(newRowIdx, pitchMidi);
      return newRowIdx;
    }
    return -1;
  }

  public org.deluge.model.StepData getClipStep(
      org.deluge.model.ClipModel cModel, int modelRow, int col) {
    int r = getClipRowIndex(cModel, modelRow, false);
    if (r >= 0) {
      return cModel.getStep(r, col);
    }
    return org.deluge.model.StepData.empty();
  }

  public void setClipStep(
      org.deluge.model.ClipModel cModel, int modelRow, int col, org.deluge.model.StepData data) {
    int r = getClipRowIndex(cModel, modelRow, true);
    cModel.setStep(r, col, data);
  }

  private void updateFoldedPitches() {
    foldedPitches.clear();
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
    if (!(t instanceof org.deluge.model.SynthTrackModel synthTrack)) return;
    java.util.Set<Integer> pitchSet = new java.util.TreeSet<>();
    if (activeClipId >= 0 && activeClipId < synthTrack.getClips().size()) {
      org.deluge.model.ClipModel clip = synthTrack.getClips().get(activeClipId);
      for (int r = 0; r < clip.getRowCount(); r++) {
        int yNote = clip.getRowYNote(r);
        boolean hasNotes = false;
        for (int s = 0; s < clip.getStepCount(); s++) {
          org.deluge.model.StepData step = clip.getStep(r, s);
          if (step != null && step.active()) {
            hasNotes = true;
            break;
          }
        }
        if (hasNotes && yNote >= 0 && yNote < 128) {
          pitchSet.add(yNote);
        }
      }
    }
    for (int pitch : pitchSet) {
      foldedPitches.add(0, pitch);
    }
  }

  public enum GridViewMode {
    CLIP,
    SONG,
    ARRANGEMENT,
    AUTOMATION,
    KEYPLAY
  }

  private GridViewMode viewMode = GridViewMode.SONG;

  private String selectedAutomationParam = org.deluge.model.AutomationParam.SYTH_PARAMS[0];
  private javax.swing.JComboBox<String> automationParamCombo;
  private boolean automationDragging = false;

  // Arranger Timeline active drag/move gesture state fields
  private org.deluge.model.ArrangerClip dragArrangerClip = null;
  private int dragArrangerStartTicks = -1;
  private int dragArrangerDurationTicks = -1;
  private boolean isResizingArranger = false;
  private int dragArrangerStartCol = -1;

  private boolean shiftHeld = false;
  private boolean tabHeld = false;

  public void setTabHeld(boolean held) {
    this.tabHeld = held;
  }

  public boolean isTabHeld() {
    return tabHeld;
  }

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
  private JPanel voicePanel;

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

  public boolean isPlayheadFollowMode() {
    return playheadFollowMode;
  }

  public int getCurrentPlayheadStep() {
    return currentPlayheadStep;
  }

  public void setPlayheadFollowMode(boolean v) {
    this.playheadFollowMode = v;
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
  private static final Color COLOR_GRAY = new Color(140, 140, 145); // Neutral medium gray

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
      "DIRECTI ON",
      "",
      "SATURAT E",
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
      "INTER POLA TION",
      "INTER POLA TION",
      "WAVETA BLE",
      "WAVETA BLE",
      "DESTI NATION",
      "DESTI NATION",
      "BITCRUS H",
      "",
      "RESONA NCE",
      "RESONA NCE",
      "BASS GAIN",
      "TREBLE GAIN",
      "DEPTH",
      "DAMP",
      "ENV 1",
      "ENV 2"
    },
    {
      "BROWSE",
      "BROWSE",
      "FEED BACK",
      "FEED BACK",
      "FEED BACK",
      "FEED BACK",
      "DECI MATE",
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
      "PTICH SPEED",
      "PTICH SPEED",
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
      "LEVEL",
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
      COLOR_PINK, COLOR_PINK, COLOR_ORANGE, COLOR_PINK,
      COLOR_YELLOW, COLOR_WHITE, COLOR_SOFT_GREEN, COLOR_WHITE,
      COLOR_SOFT_GREEN, COLOR_SOFT_GREEN, COLOR_SOFT_GREEN, COLOR_SOFT_GREEN,
      COLOR_SOFT_GREEN, COLOR_SOFT_GREEN, COLOR_WHITE, COLOR_WHITE
    },
    {
      COLOR_PINK, COLOR_PINK, COLOR_ORANGE, COLOR_ORANGE,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_SOFT_GREEN, COLOR_WHITE,
      COLOR_SOFT_GREEN, COLOR_SOFT_GREEN, COLOR_SOFT_GREEN, COLOR_SOFT_GREEN,
      COLOR_SOFT_GREEN, COLOR_SOFT_GREEN, COLOR_YELLOW, COLOR_YELLOW
    },
    {
      COLOR_PINK, COLOR_PINK, COLOR_ORANGE, COLOR_ORANGE,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_SOFT_GREEN, COLOR_WHITE,
      COLOR_SOFT_GREEN, COLOR_WHITE, COLOR_PINK, COLOR_WHITE,
      COLOR_SOFT_GREEN, COLOR_SOFT_GREEN, COLOR_SOFT_BLUE, COLOR_SOFT_BLUE
    },
    {
      COLOR_PINK, COLOR_PINK, COLOR_PINK, COLOR_PINK,
      COLOR_WHITE, COLOR_WHITE, COLOR_SOFT_BLUE, COLOR_SOFT_BLUE,
      COLOR_WHITE, COLOR_WHITE, COLOR_PINK, COLOR_YELLOW,
      COLOR_SOFT_GREEN, COLOR_SOFT_GREEN, COLOR_PINK, COLOR_ORANGE
    },
    {
      COLOR_PINK, COLOR_PINK, COLOR_ORANGE, COLOR_ORANGE,
      COLOR_WHITE, COLOR_WHITE, COLOR_GRAY, COLOR_SOFT_BLUE,
      COLOR_ORANGE, COLOR_ORANGE, COLOR_PINK, COLOR_YELLOW,
      COLOR_SOFT_GREEN, COLOR_WHITE, COLOR_PINK, COLOR_ORANGE
    },
    {
      COLOR_PINK, COLOR_PINK, COLOR_ORANGE, COLOR_ORANGE,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_GRAY, COLOR_SOFT_BLUE,
      COLOR_ORANGE, COLOR_ORANGE, COLOR_PINK, COLOR_YELLOW,
      COLOR_SOFT_BLUE, COLOR_SOFT_BLUE, COLOR_PINK, COLOR_ORANGE
    },
    {
      COLOR_PINK, COLOR_PINK, COLOR_ORANGE, COLOR_ORANGE,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_GRAY, COLOR_SOFT_BLUE,
      COLOR_ORANGE, COLOR_ORANGE, COLOR_PINK, COLOR_YELLOW,
      COLOR_SOFT_BLUE, COLOR_SOFT_BLUE, COLOR_SOFT_BLUE, COLOR_ORANGE
    },
    {
      COLOR_PINK, COLOR_PINK, COLOR_ORANGE, COLOR_ORANGE,
      COLOR_YELLOW, COLOR_YELLOW, COLOR_GRAY, COLOR_SOFT_BLUE,
      COLOR_ORANGE, COLOR_ORANGE, COLOR_PINK, COLOR_YELLOW,
      COLOR_SOFT_BLUE, COLOR_SOFT_BLUE, COLOR_PINK, COLOR_ORANGE
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

  /**
   * The grid never needs horizontal pixel-scrolling — long clips page via {@code scrollOffsetX}, so
   * the content is always exactly {@code getGridWidth(cachedPadSz, label)} wide. Cap the reported
   * width to that so a stray wide child can't inflate the panel and pop a useless horizontal
   * scrollbar (and so the right sidebar column can never be scrolled out of view).
   */
  @Override
  public Dimension getPreferredSize() {
    Dimension d = super.getPreferredSize();
    int contentW = getGridWidth(cachedPadSz, currentLabelWidth());
    if (contentW > 0) {
      d = new Dimension(Math.min(d.width, contentW), d.height);
    }
    return d;
  }

  private java.awt.Component listeningViewport;

  /**
   * Width of the row-label column, derived from the VIEWPORT width (not this panel's getWidth()).
   * recomputePadSize budgets the cell width against the viewport, so the row builders must use the
   * same basis — otherwise on a narrow window the rows compute a wider label, getGridWidth
   * overshoots the viewport, and the right (sidebar) column clips.
   */
  private int currentLabelWidth() {
    int vpW = 0;
    java.awt.Container p = getParent();
    while (p != null) {
      if (p instanceof javax.swing.JViewport vp) {
        vpW = vp.getWidth();
        break;
      }
      p = p.getParent();
    }
    int w = (vpW > 0) ? vpW : (getWidth() > 0 ? getWidth() : 1200);
    return Math.max(60, Math.min(140, w / 12));
  }

  /**
   * When this grid is added to the scroll pane, listen to the enclosing JViewport's resize. The
   * grid panel keeps its (preferred) size inside the viewport, so resizing the WINDOW changes the
   * viewport but not this panel — our own componentResized never fires. Listening to the viewport
   * is what makes the cell size reflow when the main window is resized.
   */
  @Override
  public void addNotify() {
    super.addNotify();
    java.awt.Container vp =
        (java.awt.Container)
            javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport.class, this);
    if (vp != null && vp != listeningViewport) {
      listeningViewport = vp;
      vp.addComponentListener(
          new java.awt.event.ComponentAdapter() {
            private int lastW = -1, lastH = -1;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
              int w = vp.getWidth(), h = vp.getHeight();
              if (w != lastW || h != lastH) {
                lastW = w;
                lastH = h;
                if (recomputePadSize() && !refreshInProgress) {
                  refresh();
                }
              }
            }
          });
    }
  }

  /**
   * Recompute cachedPadSz from the current viewport size. Called on resize and once during boot
   * (before the frame is shown) so the grid is built at its final cell size in a single pass.
   * Returns true if the cell size changed.
   */
  boolean recomputePadSize() {
    // Block recursive recompute triggered by revalidate() during refresh() — the inflated
    // 3000-wide preferred sizes cause getWidth() to balloon and padSz to grow on each cycle.
    if (refreshInProgress) return false;
    // Resolve visible viewport bounds dynamically from parent JViewport to prevent clipping
    int vpW = 0;
    int vpH = 0;
    Container p = getParent();
    while (p != null) {
      if (p instanceof javax.swing.JViewport vp) {
        vpW = vp.getWidth();
        vpH = vp.getHeight();
        break;
      }
      p = p.getParent();
    }
    int w = (vpW > 0) ? vpW : (getWidth() > 0 ? getWidth() : 1200);
    int h = (vpH > 0) ? vpH : (getHeight() > 0 ? getHeight() : 600);
    // Fill the ENTIRE draw area: use the full viewport (no 1600x700 budget clamp, no max-size cap),
    // so square cells grow to the largest size where the whole grid still fits both ways. Whichever
    // axis is limiting fills exactly; the other keeps its slack (left-aligned horizontally, gap at
    // bottom vertically) — squares can't fill both unless the aspect ratio happens to match. Works
    // for every GridMode because everything below is expressed in terms of gridMode.rows/columns.
    int availWidth = w;
    int availHeight = h;
    int labelWidth = Math.max(60, Math.min(140, availWidth / 12));
    // Match getGridWidth()'s footprint so the grid never overshoots the viewport width (which would
    // pop a thin horizontal scrollbar): label + VU(17) + per-column (padSz+5) +
    // struts/buffer(~109).
    int widthOverhead = labelWidth + 130;
    int widthLimitedPadSz = (availWidth - widthOverhead) / columnCount - 5;
    // Vertical budget: the fixed bars (track header, scroll row, optional clip/page bars, top
    // margin ≈ 115px) plus the inter-row gaps don't scale with the cell; everything else does. The
    // padSz-proportional row count is gridMode.rows (main grid) + 1.1 (MACROS row) + 0.6 (KEYBOARD
    // row) = gridMode.rows + 1.7 — see rebuildUIComponents (macroHeight=padSz*1.1,
    // keyboardHeight=padSz*0.6). Solving availHeight = K*padSz + overhead guarantees no clipping.
    int totalGapsHeight = (gridMode.rows - 1) * 5;
    // Base = the fixed CLIP chrome measured empirically (track header + clip/page/scroll bars + top
    // margin ≈ 152px; was under-counted at 115, which clipped the bottom keyboard row in tall modes
    // like 16x16/24x16). +4 leaves a small safety margin so the grid never clips.
    int overhead = 156 + totalGapsHeight;
    int remainingHeight = availHeight - overhead;
    double divisor = gridMode.rows + 1.7;
    int heightLimitedPadSz = (int) Math.floor(remainingHeight / divisor);

    int padSz = Math.min(widthLimitedPadSz, heightLimitedPadSz);
    // Never clip: shrink freely on tiny windows (clipping wouldn't help anyway), no upper cap so it
    // fills large windows. Floor of 6 only avoids a degenerate zero/negative cell.
    int newSz = Math.max(6, padSz);
    if (newSz != cachedPadSz) {
      cachedPadSz = newSz;
      return true;
    }
    return false;
  }

  public SwingGridPanel(final BridgeContract bridge) {
    this.bridge = bridge;

    this.projectModel = new org.deluge.model.ProjectModel();

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

          // Grid Mode Zooming: Alt/Cmd + Wheel cycles through all grid resolution modes
          // sequentially
          if (e.isAltDown() || e.isMetaDown()) {
            org.deluge.project.PreferencesManager.GridMode[] modes =
                org.deluge.project.PreferencesManager.GridMode.values();
            org.deluge.project.PreferencesManager.GridMode currentMode =
                org.deluge.project.PreferencesManager.getGridMode();
            int currentIdx = 0;
            for (int i = 0; i < modes.length; i++) {
              if (modes[i] == currentMode) {
                currentIdx = i;
                break;
              }
            }
            int nextIdx = currentIdx + (rotation > 0 ? 1 : -1);
            if (nextIdx >= 0 && nextIdx < modes.length) {
              org.deluge.project.PreferencesManager.GridMode nextMode = modes[nextIdx];
              org.deluge.project.PreferencesManager.setGridMode(nextMode);
              setGridMode(nextMode);
              refresh();
              if (SwingDelugeApp.mainInstance != null) {
                SwingDelugeApp.mainInstance.recalcWrapperSize();
              }
            }
            return;
          }

          double preciseRotation = e.getPreciseWheelRotation();
          System.out.println(
              "[TRACE grid] mouseWheelMoved: rotation="
                  + rotation
                  + " precise="
                  + preciseRotation
                  + " shiftHeld="
                  + e.isShiftDown());
          if (e.isShiftDown()) {
            preciseScrollAccumulatorX += preciseRotation;
            int cellsToScroll = (int) preciseScrollAccumulatorX;
            if (cellsToScroll != 0) {
              scrollHorizontally(cellsToScroll);
              preciseScrollAccumulatorX -= cellsToScroll;
            }
          } else {
            preciseScrollAccumulatorY += preciseRotation;
            int cellsToScroll = (int) preciseScrollAccumulatorY;
            if (cellsToScroll != 0) {
              scrollVertically(cellsToScroll);
              preciseScrollAccumulatorY -= cellsToScroll;
            }
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
        org.deluge.project.PreferencesManager.getGridPanelType()
            == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
    if (isAdvanced) {
      this.gestureCoordinator = new DelugeGestureCoordinator(this, new DelugeGestureListener());
    }
  }

  @Override
  public void removeNotify() {
    if (playheadTimer != null) {
      playheadTimer.stop();
    }
    if (activeStutterTimer != null) {
      activeStutterTimer.stop();
    }
    if (globalVuTimer != null) {
      globalVuTimer.stop();
    }
    voiceVuMeters.clear();
    trackVuMeters.clear();
    super.removeNotify();
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

  void fireProjectChanged() {
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

  private Color getPadDefaultBg(int col) {
    boolean triplet = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId >= 0 && activeClipId < t.getClips().size()) {
        triplet = t.getClips().get(activeClipId).isTripletMode();
      }
    }
    int interval = triplet ? 3 : 4;
    if (col % interval == 0) {
      return new Color(0x3d, 0x3d, 0x42); // slightly lighter slate gray for start of beat steps!
    }
    return new Color(
        0x27, 0x27, 0x2b); // deeper, beautiful dark charcoal for standard subdivisions!
  }

  private Color getThemeColor(
      org.deluge.project.PreferencesManager.GridColorTheme theme,
      Color trackColor,
      boolean active,
      boolean inScale,
      boolean isRoot,
      int rowIdx) {
    Color base = trackColor != null ? trackColor : Color.GREEN;
    switch (theme) {
      case NEON:
        if (active) {
          float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
          return Color.getHSBColor(hsb[0], 1.0f, 1.0f);
        } else if (isRoot) {
          return new Color(0xff, 0x00, 0x7f); // hot neon pink root
        } else if (inScale) {
          float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
          return Color.getHSBColor(hsb[0], 1.0f, 0.25f);
        } else {
          return new Color(0x18, 0x10, 0x22); // deep dark purple-gray
        }
      case MONOCHROME:
        if (active) {
          return Color.WHITE;
        } else if (isRoot) {
          return new Color(0xaa, 0xaa, 0xaa); // medium gray root
        } else if (inScale) {
          return new Color(0x3e, 0x3e, 0x3e); // dark gray in-scale
        } else {
          return new Color(0x15, 0x15, 0x15); // very dark gray out-of-scale
        }
      case STEEL:
        if (active) {
          return new Color(0x00, 0xb0, 0xff); // electric slate blue
        } else if (isRoot) {
          return new Color(0xff, 0xab, 0x40); // bright copper orange root
        } else if (inScale) {
          return new Color(0x00, 0x3b, 0x5c); // dark steel-blue in-scale
        } else {
          return new Color(0x1e, 0x22, 0x27); // dark metal out-of-scale
        }
      case HARDWARE:
      default:
        if (active) {
          return base;
        } else if (isRoot) {
          return new Color(0x00, 0xd2, 0xff); // neon cyan root
        } else if (inScale) {
          return base; // DelugePadButton dims it automatically
        } else {
          return new Color(0x1d, 0x1d, 0x22); // titanium gray out-of-scale
        }
    }
  }

  public int getFocusTrack() {
    return focusTrack;
  }

  private void showTrackContextMenu(java.awt.Component src, int x, int y, int trackIdx) {
    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();
    if (trackIdx >= tracks.size()) return;
    org.deluge.model.TrackModel track = tracks.get(trackIdx);

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

    JMenuItem inspectItem = new JMenuItem("Track Inspector...");
    inspectItem.setToolTipText(
        "Open advanced preset switching, mixer channel controls, and FM operator mappings");
    inspectItem.addActionListener(
        e -> {
          new TrackInspectorDialog(
                  (Frame) javax.swing.SwingUtilities.getWindowAncestor(this),
                  trackIdx,
                  tracks,
                  () -> {
                    fireProjectChanged();
                    refresh();
                  })
              .setVisible(true);
        });
    menu.add(inspectItem);

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

    // ── Grid Color Theme Sub-menu ──
    javax.swing.JMenu themeMenu = new javax.swing.JMenu("Grid Color Theme");
    for (org.deluge.project.PreferencesManager.GridColorTheme theme :
        org.deluge.project.PreferencesManager.GridColorTheme.values()) {
      javax.swing.JRadioButtonMenuItem item =
          new javax.swing.JRadioButtonMenuItem(
              theme.name(), theme == org.deluge.project.PreferencesManager.getGridColorTheme());
      item.addActionListener(
          evt -> {
            org.deluge.project.PreferencesManager.setGridColorTheme(theme);
            refresh();
          });
      themeMenu.add(item);
    }
    menu.add(themeMenu);

    menu.addSeparator();

    if (track instanceof org.deluge.model.KitTrackModel kitTrack) {
      JMenuItem saveKitItem = new JMenuItem("Save as Kit preset...");
      saveKitItem.addActionListener(e -> saveTrackPreset(kitTrack, false));
      menu.add(saveKitItem);
    } else if (track instanceof org.deluge.model.SynthTrackModel synthTrack) {
      JMenuItem saveSynthItem = new JMenuItem("Save as Synth preset...");
      saveSynthItem.addActionListener(e -> saveTrackPreset(synthTrack, true));
      menu.add(saveSynthItem);

      JMenuItem toMidiItem = new JMenuItem("Convert to MIDI Track");
      toMidiItem.addActionListener(e -> convertTrackToMidi(trackIdx));
      menu.add(toMidiItem);
    } else if (track instanceof org.deluge.model.MidiTrackModel) {
      JMenuItem toSynthItem = new JMenuItem("Convert to Synth Track");
      toSynthItem.addActionListener(e -> convertTrackToSynth(trackIdx));
      menu.add(toSynthItem);
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

  private void saveTrackPreset(org.deluge.model.TrackModel track, boolean isSynth) {
    java.io.File dir =
        isSynth
            ? org.deluge.project.PreferencesManager.getSynthsDir()
            : org.deluge.project.PreferencesManager.getKitsDir();
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
        org.deluge.project.KitSynthSerializer.saveSynth(
            (org.deluge.model.SynthTrackModel) track, file);
      } else {
        org.deluge.project.KitSynthSerializer.saveKit((org.deluge.model.KitTrackModel) track, file);
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
      org.deluge.model.TrackModel track,
      int clipIdx,
      int trackIndex) {
    JPopupMenu menu = new JPopupMenu();
    org.deluge.model.ClipModel clip = track.getClips().get(clipIdx);

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
          org.deluge.model.ClipModel copy = clip.deepCopy(clip.getName() + " copy");
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
    org.deluge.model.ClipModel.PlayMode currentMode = clip.getPlayMode();

    JRadioButtonMenuItem normalItem =
        new JRadioButtonMenuItem(
            "Normal", currentMode == org.deluge.model.ClipModel.PlayMode.NORMAL);
    normalItem.addActionListener(
        e -> {
          clip.setPlayMode(org.deluge.model.ClipModel.PlayMode.NORMAL);
          if (bridge != null) bridge.setClipPlayMode(trackIndex, clipIdx, 0);
          fireProjectChanged();
        });
    playModeMenu.add(normalItem);

    JRadioButtonMenuItem loopItem =
        new JRadioButtonMenuItem(
            "Loop (green)", currentMode == org.deluge.model.ClipModel.PlayMode.LOOP);
    loopItem.addActionListener(
        e -> {
          clip.setPlayMode(org.deluge.model.ClipModel.PlayMode.LOOP);
          if (bridge != null) bridge.setClipPlayMode(trackIndex, clipIdx, 1);
          fireProjectChanged();
        });
    playModeMenu.add(loopItem);

    // Group the radio buttons so only one can be selected
    ButtonGroup playModeGroup = new ButtonGroup();
    playModeGroup.add(normalItem);
    playModeGroup.add(loopItem);

    menu.add(playModeMenu);

    // ── Play Direction submenu ──
    JMenu playDirMenu = new JMenu("Play Direction");
    org.deluge.model.ClipModel.PlayDirection currentDir = clip.getPlayDirection();

    JRadioButtonMenuItem forwardItem =
        new JRadioButtonMenuItem(
            "Forward", currentDir == org.deluge.model.ClipModel.PlayDirection.FORWARD);
    forwardItem.addActionListener(
        e -> {
          clip.setPlayDirection(org.deluge.model.ClipModel.PlayDirection.FORWARD);
          if (bridge != null) bridge.setClipPlayDirection(trackIndex, clipIdx, 0);
          fireProjectChanged();
        });
    playDirMenu.add(forwardItem);

    JRadioButtonMenuItem reverseItem =
        new JRadioButtonMenuItem(
            "Reverse", currentDir == org.deluge.model.ClipModel.PlayDirection.REVERSE);
    reverseItem.addActionListener(
        e -> {
          clip.setPlayDirection(org.deluge.model.ClipModel.PlayDirection.REVERSE);
          if (bridge != null) bridge.setClipPlayDirection(trackIndex, clipIdx, 1);
          fireProjectChanged();
        });
    playDirMenu.add(reverseItem);

    JRadioButtonMenuItem pingPongItem =
        new JRadioButtonMenuItem(
            "Ping-Pong", currentDir == org.deluge.model.ClipModel.PlayDirection.PING_PONG);
    pingPongItem.addActionListener(
        e -> {
          clip.setPlayDirection(org.deluge.model.ClipModel.PlayDirection.PING_PONG);
          if (bridge != null) bridge.setClipPlayDirection(trackIndex, clipIdx, 2);
          fireProjectChanged();
        });
    playDirMenu.add(pingPongItem);

    JRadioButtonMenuItem randomItem =
        new JRadioButtonMenuItem(
            "Random", currentDir == org.deluge.model.ClipModel.PlayDirection.RANDOM);
    randomItem.addActionListener(
        e -> {
          clip.setPlayDirection(org.deluge.model.ClipModel.PlayDirection.RANDOM);
          if (bridge != null) bridge.setClipPlayDirection(trackIndex, clipIdx, 3);
          fireProjectChanged();
        });
    playDirMenu.add(randomItem);

    ButtonGroup playDirGroup = new ButtonGroup();
    playDirGroup.add(forwardItem);
    playDirGroup.add(reverseItem);
    playDirGroup.add(pingPongItem);
    playDirGroup.add(randomItem);

    menu.add(playDirMenu);

    menu.show(src, x, y);
  }

  public void setViewMode(GridViewMode mode) {
    this.viewMode = mode;
    if (mode == GridViewMode.AUTOMATION) {
      this.columnCount = stepCount;
    } else {
      this.columnCount = stepCount + 2;
    }
    recomputePadSize();
    refresh();
  }

  public boolean isMuteColumn(int colId) {
    if (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT) {
      return (columnCount > stepCount) && (colId == columnCount - 2);
    }
    if (viewMode == GridViewMode.CLIP) {
      return colId == columnCount - 2;
    }
    return false;
  }

  public boolean isSoloColumn(int colId) {
    if (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT) {
      return (columnCount > stepCount) && (colId == columnCount - 1);
    }
    if (viewMode == GridViewMode.CLIP) {
      return colId == columnCount - 1;
    }
    return false;
  }

  public boolean isStepColumn(int colId) {
    return !isMuteColumn(colId) && !isSoloColumn(colId);
  }

  public void setTrackMuteWithCapture(int trackIndex, boolean mute) {
    if (bridge == null) return;
    bridge.setMute(trackIndex, mute);

    // Live Capture Integration
    if (SwingDelugeApp.mainInstance != null) {
      ArrangerPlaybackScheduler scheduler = SwingDelugeApp.mainInstance.getArrangerScheduler();
      if (scheduler != null && scheduler.isCaptureActive()) {
        if (mute) {
          scheduler.notifyClipStopped(trackIndex);
        } else {
          // Find the active clip for this track to relaunch it in capture
          if (projectModel != null && trackIndex < projectModel.getTracks().size()) {
            org.deluge.model.TrackModel track = projectModel.getTracks().get(trackIndex);
            int activeClipIdx = track.getActiveClipIndex();
            if (activeClipIdx >= 0 && activeClipIdx < track.getClips().size()) {
              scheduler.notifyClipLaunched(trackIndex, track.getClips().get(activeClipIdx));
            }
          }
        }
      }
    }
  }

  public boolean isAutoOverviewMode() {
    return autoOverviewMode;
  }

  public void setAutoOverviewMode(boolean overview) {
    this.autoOverviewMode = overview;
  }

  public org.deluge.model.ProjectModel getProjectModel() {
    return projectModel;
  }

  public void setProjectModel(org.deluge.model.ProjectModel model) {
    boolean modelChanged = (this.projectModel != model);
    this.projectModel = model;
    if (modelChanged) {
      resetScrollOffset();
      // Force full rebuild: the clip row/step count may have changed,
      // and refresh()'s structureChanged check can miss it.
      lastColumnCount = 0;
      lastVoiceRowCount = 0;
    }
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
  public void setGridMode(org.deluge.project.PreferencesManager.GridMode mode) {
    if (this.gridMode != mode) {
      this.gridMode = mode;
      this.stepCount = mode.columns;
      this.columnCount = mode.columns + 2; // +2 for MUTE and SOLO columns
      if (viewMode == GridViewMode.AUTOMATION) {
        this.columnCount = mode.columns;
      }
      resetScrollOffset();
      recomputePadSize(); // resize cells for the new row/column count before rebuilding
      refresh();
    }
  }

  public org.deluge.project.PreferencesManager.GridMode getGridMode() {
    return gridMode;
  }

  public void setOnEditRequest(java.util.function.BiConsumer<Integer, Integer> callback) {
    this.onEditRequest = callback;
  }

  /** Reset scroll offsets when edited track changes. */
  public void resetScrollOffset() {
    boolean isSynth = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      isSynth = t instanceof org.deluge.model.SynthTrackModel;
    }
    if (isSynth) {
      if (foldMode) {
        updateFoldedPitches();
        scrollOffset = 0;
      } else {
        updateFoldedPitches();
        if (!foldedPitches.isEmpty()) {
          // Center on the middle active pitch
          int midPitch = foldedPitches.get(foldedPitches.size() / 2);
          scrollOffset = 124 - midPitch;
        } else {
          // Default to centering on C4 (midi 60)
          scrollOffset = 67;
        }
        // Restrict scrollOffset to prevent octave 13 ghost zone (restrict to octaves 1 to 8)
        scrollOffset = Math.max(19, Math.min(107, scrollOffset));
      }
    } else {
      scrollOffset = 0;
    }
    scrollOffsetX = 0;
  }

  private void clearActionListeners(JButton btn) {
    if (btn == null) return;
    for (java.awt.event.ActionListener al : btn.getActionListeners()) {
      btn.removeActionListener(al);
    }
  }

  private static class KeyboardMouseAdapter extends java.awt.event.MouseAdapter {
    private final int note;
    private final SwingGridPanel panel;

    KeyboardMouseAdapter(SwingGridPanel panel, int note) {
      this.panel = panel;
      this.note = note;
    }

    @Override
    public void mousePressed(java.awt.event.MouseEvent e) {
      if (lockArmedTrack == panel.editedModelTrack
          && lockArmedStep != -1
          && panel.projectModel != null) {
        int trkIndex = panel.scrollOffset - note;
        if (trkIndex >= 0 && trkIndex < panel.voiceRowCount) {
          int engineRow = panel.baseTrackId + trkIndex;
          boolean st = panel.bridge.getStep(engineRow, lockArmedStep);
          panel.bridge.setStep(engineRow, lockArmedStep, !st);

          org.deluge.model.TrackModel tModel = panel.projectModel.getTracks().get(lockArmedTrack);
          if (panel.activeClipId < tModel.getClips().size()) {
            org.deluge.model.ClipModel cModel = tModel.getClips().get(panel.activeClipId);
            cModel.setStep(
                trkIndex,
                lockArmedStep,
                new org.deluge.model.StepData(!st, 0.8f, 0.5f, 1.0f, 0, 1, 1.0f));
          }
          panel.fireProjectChanged();
        }
      }
      panel.triggerKeyboardNote(note);
    }

    @Override
    public void mouseReleased(java.awt.event.MouseEvent e) {
      panel.triggerKeyboardNoteRelease(note);
    }
  }

  private void clearKeyboardMouseListeners(JButton btn) {
    if (btn == null) return;
    for (java.awt.event.MouseListener ml : btn.getMouseListeners()) {
      if (ml instanceof KeyboardMouseAdapter) {
        btn.removeMouseListener(ml);
      }
    }
  }

  private int getActiveStepCol(int row, int col) {
    int trackLen = 0;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < track.getClips().size()) {
        trackLen = track.getClips().get(activeClipId).getStepCount();
      }
    }
    if (trackLen <= 0) {
      trackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
    }
    if (trackLen > 0 && trackLen < stepCount) {
      return col % trackLen;
    } else if (trackLen > stepCount) {
      return Math.min(col + scrollOffsetX, trackLen - 1);
    } else {
      return col;
    }
  }

  private org.deluge.model.StepData getModelStep(int row, int col) {
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        return tModel.getClips().get(activeClipId).getStep(row, col);
      }
    }
    return null;
  }

  private void updateModelStep(
      int row,
      int col,
      boolean state,
      float vel,
      float prob,
      int iterance,
      float fill,
      org.deluge.model.StepData oldStep) {
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        org.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
        int originalPitch = oldStep != null ? oldStep.pitch() : 60;
        float originalGate =
            oldStep != null ? oldStep.gate() : org.deluge.model.StepData.DEFAULT_CLICK_GATE;
        cModel.setStep(
            row,
            col,
            new org.deluge.model.StepData(
                state, vel, originalGate, prob, originalPitch, iterance, fill));
        if (oldStep != null) {
          projectModel
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      editedModelTrack, activeClipId, row, col, oldStep, cModel.getStep(row, col)));
        }
      }
    }
    fireProjectChanged();
  }

  private boolean handleStepPressModifiers(
      int targetTrackId, int row, int col, java.awt.event.MouseEvent e) {
    final int engineRow = targetTrackId + row;
    final int activeCol = (viewMode == GridViewMode.CLIP) ? getActiveStepCol(row, col) : col;

    // 0. Shift + Click ➔ Parameter Lock Arming Toggle!
    if (e.isShiftDown() && viewMode == GridViewMode.CLIP) {
      if (lockArmedTrack == editedModelTrack && lockArmedStep == activeCol) {
        lockArmedTrack = -1;
        lockArmedStep = -1;
      } else {
        lockArmedTrack = editedModelTrack;
        lockArmedStep = activeCol;
      }
      refresh();
      return true;
    }

    // 1. Alt + Click ➔ Directly open Step Properties Dialog!
    if (e.isAltDown()) {
      double curVel = bridge.getVelocity(engineRow, activeCol);
      int curIt = bridge.getIterance(engineRow, activeCol);
      int curFill = (int) (bridge.getStepFill(engineRow, activeCol) * 100);

      StepPropertiesDialog dlg =
          new StepPropertiesDialog(
              (java.awt.Frame) javax.swing.SwingUtilities.getWindowAncestor(this),
              (int) (curVel * 100),
              curIt,
              curFill);
      dlg.setVisible(true);
      if (dlg.isConfirmed()) {
        int newVel = dlg.getVelocity();
        int newIt = dlg.getIterance();
        int newFill = dlg.getFill();

        org.deluge.model.StepData oldStep = getModelStep(row, activeCol);

        bridge.setVelocity(engineRow, activeCol, newVel / 100.0);
        bridge.setIterance(engineRow, activeCol, newIt);
        bridge.setStepFill(engineRow, activeCol, newFill / 100.0);

        updateModelStep(
            row,
            activeCol,
            bridge.getStep(engineRow, activeCol),
            newVel / 100.0f,
            (float) bridge.getStepProbability(engineRow, activeCol),
            newIt,
            newFill / 100.0f,
            oldStep);
        refresh();
      }
      return true;
    }

    // 2. Cmd + Click (or Ctrl + Click!) ➔ Cycle Probability: 100% -> 75% -> 50% -> 25% -> 100%
    if (e.isControlDown() || e.isMetaDown()) {
      double curProb = bridge.getStepProbability(engineRow, activeCol);
      double newProb = 1.0;
      if (curProb >= 0.9) newProb = 0.75;
      else if (curProb >= 0.7) newProb = 0.5;
      else if (curProb >= 0.4) newProb = 0.25;
      else newProb = 1.0;

      org.deluge.model.StepData oldStep = getModelStep(row, activeCol);
      bridge.setStepProbability(engineRow, activeCol, newProb);

      boolean stepOn = bridge.getStep(engineRow, activeCol);
      if (!stepOn) {
        bridge.setStep(engineRow, activeCol, true);
        stepOn = true;
      }
      double curVel = bridge.getVelocity(engineRow, activeCol);
      int curIt = bridge.getIterance(engineRow, activeCol);
      double curFill = bridge.getStepFill(engineRow, activeCol);

      updateModelStep(
          row, activeCol, stepOn, (float) curVel, (float) newProb, curIt, (float) curFill, oldStep);

      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
            "PROB", (int) (newProb * 100) + "% ");
      }
      refresh();
      return true;
    }

    // 3. Tab + Click ➔ Cycle Step Velocity: 100% -> 75% -> 50% -> 25% -> 100%
    if (tabHeld) {
      double curVel = bridge.getVelocity(engineRow, activeCol);
      double newVel = 1.0;
      if (curVel >= 0.9) newVel = 0.75;
      else if (curVel >= 0.7) newVel = 0.5;
      else if (curVel >= 0.4) newVel = 0.25;
      else newVel = 1.0;

      org.deluge.model.StepData oldStep = getModelStep(row, activeCol);
      bridge.setVelocity(engineRow, activeCol, newVel);

      boolean stepOn = bridge.getStep(engineRow, activeCol);
      if (!stepOn) {
        bridge.setStep(engineRow, activeCol, true);
        stepOn = true;
      }
      double curProb = bridge.getStepProbability(engineRow, activeCol);
      int curIt = bridge.getIterance(engineRow, activeCol);
      double curFill = bridge.getStepFill(engineRow, activeCol);

      updateModelStep(
          row, activeCol, stepOn, (float) newVel, (float) curProb, curIt, (float) curFill, oldStep);

      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
            "VEL ", (int) (newVel * 100) + "% ");
      }
      refresh();
      return true;
    }

    return false;
  }

  public int getGridWidth(int padSz, int lw) {
    int width = lw + 69 + 5;
    if (viewMode != GridViewMode.AUTOMATION) {
      width += 17; // VU (12) + strut (5)
    }
    width += columnCount * (padSz + 5);
    if (viewMode != GridViewMode.AUTOMATION && columnCount > 16) {
      width += 20; // separator strut
    }
    width += 15; // trailing buffer
    return width;
  }

  /** Compute total voice rows for the currently edited track in CLIP mode. */
  private int computeVoiceRowCount() {
    if (viewMode == GridViewMode.AUTOMATION) {
      return 8; // fixed 8 value bands (0-127 mapped to 8 rows)
    }
    if (viewMode == GridViewMode.CLIP
        && projectModel != null
        && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      if (t instanceof org.deluge.model.KitTrackModel kit) {
        return kit.getDrums().size();
      }
      // Synth uses a full 84-note pitch layout (C2 to B8) or folded pitches
      if (foldMode) {
        updateFoldedPitches();
        if (!foldedPitches.isEmpty()) {
          return foldedPitches.size();
        }
      }
      return 128;
    }
    // SONG / ARRANGEMENT — dynamically compute scrollable slots based on track count + 2 empty
    // buffer rows
    int trackCount = projectModel != null ? projectModel.getTracks().size() : 0;
    return Math.max(gridMode.rows, trackCount + 2);
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
    org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
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

  public static int getDiatonicPitch(int modelRow) {
    int baseDegree = 28; // C4
    int degree = baseDegree + (67 - modelRow);
    int octave = Math.floorDiv(degree, 7);
    int rem = Math.floorMod(degree, 7);
    int[] majorScaleOffsets = new int[] {0, 2, 4, 5, 7, 9, 11};
    return ((octave + 1) * 12) + majorScaleOffsets[rem];
  }

  private JPopupMenu createMutePopupMenu(int rowToSolo) {
    JPopupMenu mutePopup = new JPopupMenu();
    mutePopup.setBackground(new Color(0x18, 0x18, 0x1a));
    mutePopup.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x34), 1));

    JMenuItem muteOthersItem = new JMenuItem("Mute Others (Solo)");
    muteOthersItem.setBackground(new Color(0x18, 0x18, 0x1a));
    muteOthersItem.setForeground(new Color(0xdd, 0xdd, 0xde));
    muteOthersItem.setFont(new Font("SansSerif", Font.PLAIN, 11));
    muteOthersItem.addActionListener(
        evt -> {
          if (bridge != null) {
            java.util.List<org.deluge.model.TrackModel> tracks =
                projectModel != null ? projectModel.getTracks() : java.util.Collections.emptyList();
            if (viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION) {
              for (int i = 0; i < tracks.size(); i++) {
                setTrackMuteWithCapture(i, i != editedModelTrack);
              }
              soloRow = editedModelTrack;
            } else {
              for (int i = 0; i < voiceRowCount; i++) {
                setTrackMuteWithCapture(baseTrackId + i, i != rowToSolo);
              }
              soloRow = rowToSolo;
            }
            refresh();
          }
        });

    JMenuItem unmuteAllItem = new JMenuItem("Unmute All");
    unmuteAllItem.setBackground(new Color(0x18, 0x18, 0x1a));
    unmuteAllItem.setForeground(new Color(0xdd, 0xdd, 0xde));
    unmuteAllItem.setFont(new Font("SansSerif", Font.PLAIN, 11));
    unmuteAllItem.addActionListener(
        evt -> {
          if (bridge != null) {
            java.util.List<org.deluge.model.TrackModel> tracks =
                projectModel != null ? projectModel.getTracks() : java.util.Collections.emptyList();
            if (viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION) {
              for (int i = 0; i < tracks.size(); i++) {
                setTrackMuteWithCapture(i, false);
              }
            } else {
              for (int i = 0; i < voiceRowCount; i++) {
                setTrackMuteWithCapture(baseTrackId + i, false);
              }
            }
            soloRow = -1;
            refresh();
          }
        });

    mutePopup.add(muteOthersItem);
    mutePopup.add(unmuteAllItem);
    return mutePopup;
  }

  private void updatePageBarHighlights() {
    int currentPageIndex = scrollOffsetX / 16;
    for (int i = 0; i < pageButtons.size(); i++) {
      JButton pageBtn = pageButtons.get(i);
      if (i == currentPageIndex) {
        pageBtn.setForeground(new Color(0x00, 0xff, 0xcc));
        pageBtn.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xff, 0xcc), 1));
      } else {
        pageBtn.setForeground(Color.GRAY);
        pageBtn.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
      }
    }
  }

  /**
   * Build a single voice row panel. modelRow = the actual engine row index (0..voiceRowCount-1).
   */
  private JPanel buildVoiceRow(
      int visualRowIndex,
      int visibleRow,
      int padSz,
      java.util.List<org.deluge.model.TrackModel> tracks) {
    boolean isSynth = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      isSynth =
          projectModel.getTracks().get(editedModelTrack)
              instanceof org.deluge.model.SynthTrackModel;
    }
    final int modelRow =
        (isSynth && viewMode == GridViewMode.CLIP)
            ? (127 - getRowPitch(visualRowIndex))
            : visualRowIndex;
    boolean isSongOrArr = (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT);
    boolean isUnusedTrackRow = isSongOrArr && (modelRow >= tracks.size());
    String samplePathLoc = null;
    if (modelRow < tracks.size()) {
      org.deluge.model.TrackModel track = tracks.get(modelRow);
      if (viewMode == GridViewMode.CLIP && track instanceof org.deluge.model.KitTrackModel kit) {
        java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
        int drumIdx = sounds.size() - 1 - modelRow;
        if (drumIdx >= 0 && drumIdx < sounds.size()) {
          org.deluge.model.Drum drum = sounds.get(drumIdx);
          if (drum instanceof org.deluge.model.SoundDrum soundDrum) {
            samplePathLoc = soundDrum.getSamplePath();
          }
        }
      } else if (track instanceof org.deluge.model.AudioTrackModel audioTrack
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
    int lw = currentLabelWidth();
    int rowW = getGridWidth(padSz, lw);
    rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    rowPanel.setPreferredSize(new Dimension(rowW, padSz));
    rowPanel.setMinimumSize(new Dimension(rowW, padSz));
    rowPanel.setMaximumSize(new Dimension(rowW, padSz));
    rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

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
      org.deluge.model.TrackModel rowTrack = projectModel.getTracks().get(editedModelTrack);
      if (rowTrack instanceof org.deluge.model.KitTrackModel kit) {
        java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
        trackName =
            (modelRow < sounds.size())
                ? sounds.get(sounds.size() - 1 - modelRow).getName()
                : rowTrack.getName();
      } else {
        int pitchMidi = ((128 - 1) - modelRow) + 0;
        trackName = getNoteName(pitchMidi);
      }
    } else {
      trackName =
          (modelRow < tracks.size()) ? tracks.get(modelRow).getName() : "EMPTY " + (modelRow + 1);
    }

    final int trk = visibleRow;
    final String tName = trackName;
    JLabel label = new JLabel(tName);
    lw = currentLabelWidth();
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

    // Dynamic drum voice direct-access config button (Clip mode kit slots)
    if (viewMode == GridViewMode.CLIP
        && projectModel != null
        && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel activeTrack = projectModel.getTracks().get(editedModelTrack);
      if (activeTrack instanceof org.deluge.model.KitTrackModel kitTrack) {
        java.util.List<org.deluge.model.Drum> drumsList = kitTrack.getDrums();
        int soundIndex = drumsList.size() - 1 - modelRow;
        if (soundIndex >= 0 && soundIndex < drumsList.size()) {
          JButton drumCfgBtn = new JButton("⚙");
          drumCfgBtn.setPreferredSize(new Dimension(20, 20));
          drumCfgBtn.setMinimumSize(new Dimension(20, 20));
          drumCfgBtn.setMaximumSize(new Dimension(20, 20));
          drumCfgBtn.setMargin(new Insets(0, 0, 0, 0));
          drumCfgBtn.setFont(new Font("SansSerif", Font.PLAIN, 10));
          drumCfgBtn.setForeground(new Color(0x00, 0xff, 0xcc));
          drumCfgBtn.setOpaque(false);
          drumCfgBtn.setContentAreaFilled(false);
          drumCfgBtn.setFocusPainted(false);
          drumCfgBtn.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xff, 0xcc), 1));
          drumCfgBtn.setFocusable(false);
          drumCfgBtn.setToolTipText("Open full settings editor for drum slot: " + tName);
          drumCfgBtn.addActionListener(
              e -> {
                Window owner = SwingUtilities.getWindowAncestor(rowPanel);
                SwingKitConfigDialog dialog =
                    new SwingKitConfigDialog((Frame) owner, kitTrack, bridge, editedModelTrack);
                dialog.setSelectedTab(soundIndex);
                dialog.setVisible(true);
              });
          rowPanel.add(Box.createHorizontalStrut(3));
          rowPanel.add(drumCfgBtn);
        }
      }
    }

    // Config button and length badge only for modelRow < 8 (real model tracks)
    if (modelRow < tracks.size() && modelRow < 8) {
      org.deluge.model.TrackModel track = tracks.get(modelRow);

      // Per-track \u2699 Configure now lives in the fixed inspector strip above the grid
      // (SwingDelugeApp.buildTrackInspectorStrip); the in-grid duplicate was removed.

      int stepLen = (bridge != null) ? bridge.getTrackLength(modelRow) : 16;
      JLabel lenBadge = new JLabel("[" + stepLen + "]");
      lenBadge.setPreferredSize(new Dimension(48, 26));
      lenBadge.setMinimumSize(new Dimension(48, 26));
      lenBadge.setMaximumSize(new Dimension(48, 26));
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
      // 21 + 48(lenBadge) = 69px to match the fixed-row spacer (removed in-grid ⚙ filled this).
      rowPanel.add(Box.createHorizontalStrut(21));
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

    voiceVuMeters.put(modelRow, vu);

    for (int c = 0; c < columnCount; c++) {
      final int colId = c;

      JButton clipBtn;
      boolean isAdvanced =
          org.deluge.project.PreferencesManager.getGridPanelType()
              == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
      if (isAdvanced) {
        DelugePadButton pad = new DelugePadButton();
        pad.putClientProperty("row", visibleRow);
        pad.putClientProperty("col", c);
        clipBtn = pad;
      } else {
        clipBtn = new JButton();
        clipBtn.setFocusable(false);
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
        org.deluge.model.TrackModel track = tracks.get(modelRow);
        if (c < track.getClips().size()) {
          hasClip = true;
        }
      }

      // In CLIP mode, wrap extra columns beyond clip length back to beginning
      final int activeCol;
      if (viewMode == GridViewMode.CLIP) {
        int trackLen = 0;
        if (modelRow < tracks.size()) {
          org.deluge.model.TrackModel track = tracks.get(modelRow);
          if (activeClipId < track.getClips().size()) {
            trackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
          }
        }
        if (trackLen <= 0)
          trackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
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

      if (isUnusedTrackRow) {
        Color darkBg = new Color(0x15, 0x15, 0x15);
        clipBtn.setBackground(darkBg);
        clipBtn.setForeground(Color.GRAY);
        clipBtn.setText("");
        if (clipBtn instanceof DelugePadButton pad) {
          pad.setBaseColor(darkBg);
          pad.setTextColorOverride(Color.GRAY);
          pad.setDrawCenterCircle(false);
          pad.setIntensity(0.0f);
          pad.setActive(false);
          pad.setNoteText("");
          pad.setMuted(false);
          pad.setPlayhead(false);
          pad.setSelected(false);
          pad.setInLoop(true);
        }
        clipBtn.setComponentPopupMenu(null);
        clipBtn.setToolTipText(null);
        // Remove all listeners to prevent clicks/auditions
        for (java.awt.event.ActionListener al : clipBtn.getActionListeners()) {
          clipBtn.removeActionListener(al);
        }
        for (java.awt.event.MouseListener ml : clipBtn.getMouseListeners()) {
          clipBtn.removeMouseListener(ml);
        }
      } else if (isMuteColumn(colId)) {
        final int trackToMute =
            (viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION)
                ? editedModelTrack
                : (baseTrackId + modelRow);
        final int engineRow = baseTrackId + modelRow;
        boolean isMuted = bridge != null && bridge.getMute(trackToMute);
        Color muteBg = isMuted ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
        clipBtn.setText(isMuted ? "UNMUTE" : "MUTE");
        clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
        clipBtn.setBackground(muteBg);
        clipBtn.setForeground(Color.BLACK);

        if (clipBtn instanceof DelugePadButton pad) {
          pad.setBaseColor(muteBg);
          pad.setTextColorOverride(Color.BLACK);
          pad.setDrawCenterCircle(false);
          pad.setIntensity(1.0f);
          pad.setActive(true);
          pad.setNoteText(isMuted ? "UNMUTE" : "MUTE");
        }

        JPopupMenu mutePopup = createMutePopupMenu(modelRow);
        clipBtn.setComponentPopupMenu(mutePopup);

        clipBtn.setToolTipText(
            "Clip View: Row "
                + (visibleRow + 1)
                + " Mute / Unmute (Right-Click for Options / Shift-Click to Clear Steps)");
        javax.swing.ToolTipManager.sharedInstance().registerComponent(clipBtn);

        clipBtn.addActionListener(
            e -> {
              if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                for (int s = 0; s < stepCount; s++) {
                  if (bridge != null) bridge.setStep(engineRow, s, false);
                }
                refresh();
                return;
              }
              boolean nextMute = bridge != null && !bridge.getMute(trackToMute);
              setTrackMuteWithCapture(trackToMute, nextMute);
              Color nextBg = nextMute ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
              clipBtn.setBackground(nextBg);
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(nextBg);
                pad.setIntensity(1.0f);
                pad.setActive(true);
                pad.setTextColorOverride(Color.BLACK);
              }
              if (SwingDelugeApp.mainInstance != null) {
                SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                    "MUT ", (nextMute ? "ON  " : "OFF ") + "T" + (modelRow + 1));
              }
              refresh();
            });
      } else if (isSoloColumn(colId)) {
        if (viewMode == GridViewMode.CLIP) {
          isSynth = false;
          if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
            org.deluge.model.TrackModel tm = projectModel.getTracks().get(editedModelTrack);
            isSynth = tm instanceof org.deluge.model.SynthTrackModel;
          }
          boolean isOctaveC = isSynth && ((127 - modelRow) % 12 == 0);

          String nName;
          if (isSynth) {
            int midiPitch = getDiatonicPitch(modelRow);
            String[] noteNames =
                new String[] {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
            nName = noteNames[Math.max(0, midiPitch) % 12] + ((midiPitch / 12) - 1);
          } else if (projectModel != null
              && editedModelTrack < projectModel.getTracks().size()
              && projectModel.getTracks().get(editedModelTrack)
                  instanceof org.deluge.model.KitTrackModel kit) {
            nName =
                (modelRow < kit.getDrums().size())
                    ? kit.getDrums().get(modelRow).getName()
                    : ("PAD " + (modelRow + 1));
            if (nName.toLowerCase().endsWith(".wav") || nName.toLowerCase().endsWith(".aif")) {
              nName = nName.substring(0, nName.lastIndexOf('.'));
            }
          } else {
            nName = "ROW " + (modelRow + 1);
          }

          clipBtn.setText(nName);
          clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));

          Color cellBg;
          Color cellFg;
          int rootPitch = isSynth ? getDiatonicPitch(modelRow) : -1;

          if (rootPitch == 60) {
            cellBg = new Color(0x7b, 0x68, 0xee); // Purplish for root pitch C4
            cellFg = Color.WHITE;
          } else if (rootPitch == 48) {
            cellBg = Color.RED; // Red for lower octave root pitch C3
            cellFg = Color.WHITE;
          } else if (modelRow == 0) {
            cellBg =
                new Color(
                    0xff, 0xb3,
                    0x00); // Bright Amber Gold for absolute first top cell of instrument
            cellFg = Color.BLACK;
          } else if (modelRow == voiceRowCount - 1) {
            cellBg =
                new Color(
                    0x7b, 0x68,
                    0xee); // Distinct Soft Purple for absolute last bottom cell of instrument
            cellFg = Color.WHITE;
          } else if (isOctaveC) {
            cellBg = new Color(0xb0, 0xe2, 0xff); // Soft octave teal/blue
            cellFg = Color.BLACK;
          } else {
            cellBg = new Color(0x55, 0x55, 0x5a); // Highly visible active Slate Grey
            cellFg = Color.WHITE;
          }
          clipBtn.setBackground(cellBg);
          clipBtn.setForeground(cellFg);

          if (clipBtn instanceof DelugePadButton pad) {
            pad.setBaseColor(cellBg);
            pad.setTextColorOverride(cellFg);
            pad.setDrawCenterCircle(false);
            pad.setIntensity(1.0f);
            pad.setActive(true);
            pad.setNoteText(nName);
          }

          clipBtn.setToolTipText(
              "Clip View: Audition / Preview Row " + (visibleRow + 1) + " Note (" + nName + ")");
          javax.swing.ToolTipManager.sharedInstance().registerComponent(clipBtn);

          final String finalNoteName = nName;
          clipBtn.addMouseListener(
              new java.awt.event.MouseAdapter() {
                private boolean isPressed = false;

                private void startAudition() {
                  if (isPressed) return;
                  isPressed = true;

                  boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
                  int pitchMidi = isSynthMode ? (((128 - 1) - modelRow) + 0) : 60;

                  if (SwingDelugeApp.mainInstance != null) {
                    SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                        "AUD ", finalNoteName);
                  }

                  Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                  if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                    if (editedModelTrack < fwEngine.sounds.size() && !isSequencerPlaying()) {
                      org.deluge.firmware2.GlobalEffectable sound =
                          fwEngine.sounds.get(editedModelTrack);
                      if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                        if (modelRow < kit.drumSounds.size()) {
                          kit.triggerDrum(modelRow, 127);
                        }
                      } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                        stopAuditionIfNeeded();
                        auditionMidiNote = pitchMidi;
                        auditionSynth = synth;
                        synth.triggerNote(pitchMidi, 127);
                      }
                    }
                  }
                }

                private void stopAudition() {
                  if (!isPressed) return;
                  isPressed = false;

                  boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
                  int pitchMidi = isSynthMode ? (((128 - 1) - modelRow) + 0) : 60;

                  Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                  if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                    if (editedModelTrack < fwEngine.sounds.size()) {
                      org.deluge.firmware2.GlobalEffectable sound =
                          fwEngine.sounds.get(editedModelTrack);
                      if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                        if (modelRow < kit.drumSounds.size()) {
                          kit.drumSounds.get(modelRow).releaseNote(60);
                        }
                      } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                        synth.releaseNote(pitchMidi);
                      }
                    }
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
                      setTrackMuteWithCapture(baseTrackId + i, false);
                    }
                    if (SwingDelugeApp.mainInstance != null) {
                      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("SOLO", "OFF");
                    }
                  } else {
                    soloRow = modelRow;
                    for (int i = 0; i < voiceRowCount; i++) {
                      setTrackMuteWithCapture(baseTrackId + i, i != modelRow);
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
            org.deluge.model.TrackModel genericTrack =
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
              double[] outVelProb = {0.8, 1.0};
              boolean stepState = isStepActiveOrSpanned(modelRow, activeCol, outVelProb);
              double vel = outVelProb[0];
              double prob = outVelProb[1];

              int curTrackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
              boolean inLoop = activeCol < curTrackLen;
              pad.setInLoop(inLoop);
              pad.setActive(stepState);
              pad.setBaseColor(trackColors[modelRow % trackColors.length]);
              pad.setIntensity((float) (vel * (0.2f + 0.8f * prob)));
              pad.setTied(isStepTied(modelRow, activeCol));

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
              int pitchMidi = isSynthMode ? (((128 - 1) - modelRow) + 0) : 60;
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
              pad.setBaseColor(trackColors[modelRow % trackColors.length]);
              pad.setIntensity(0.8f);
              if (hasClip) {
                if (modelRow < tracks.size() && c < tracks.get(modelRow).getClips().size()) {
                  org.deluge.model.TrackModel t = tracks.get(modelRow);
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
            org.deluge.model.TrackModel genericTrack =
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
                      ? velocityBlend(trackColors[modelRow % trackColors.length], vel)
                      : getPadDefaultBg(activeCol));
              boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
              int pitchMidi = isSynthMode ? (((128 - 1) - modelRow) + 0) : 60;
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
                clipBtn.setBackground(trackColors[modelRow % trackColors.length]);
                if (modelRow < tracks.size() && c < tracks.get(modelRow).getClips().size()) {
                  org.deluge.model.TrackModel t = tracks.get(modelRow);
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
              if (isAdvanced && isStepColumn(colId)) {
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
                        if (shiftHeld && visibleRow < 8 && isStepColumn(colId)) {
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
                              org.deluge.model.StepData oldStep = null;
                              if (projectModel != null
                                  && editedModelTrack < projectModel.getTracks().size()) {
                                org.deluge.model.TrackModel tModel =
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
                                org.deluge.model.TrackModel tModel =
                                    projectModel.getTracks().get(editedModelTrack);
                                if (activeClipId < tModel.getClips().size()) {
                                  org.deluge.model.ClipModel cModel =
                                      tModel.getClips().get(activeClipId);
                                  boolean st = bridge.getStep(engineRow, activeCol);
                                  double prob = bridge.getStepProbability(engineRow, activeCol);
                                  cModel.setStep(
                                      modelRow,
                                      activeCol,
                                      new org.deluge.model.StepData(
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
                          if (handleStepPressModifiers(baseTrackId, modelRow, colId, e)) return;
                          boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
                          int trackType = bridge.getTrackType(modelRow);

                          if (trackType == 2) {
                            // MIDI track
                            org.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
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
                                sendMidiNote(60 + modelRow, 100, 250); // preview
                              }
                            }
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                double curVel =
                                    bridge.getVelocity(baseTrackId + modelRow, activeCol);
                                double curProb =
                                    bridge.getStepProbability(baseTrackId + modelRow, activeCol);
                                cModel.setStep(
                                    modelRow,
                                    activeCol,
                                    org.deluge.model.StepData.of(
                                        !st,
                                        (float) curVel,
                                        org.deluge.model.StepData.DEFAULT_CLICK_GATE,
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
                            int pitchMidi = ((128 - 1) - modelRow) + 0;
                            org.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
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
                                        trackColors[modelRow % trackColors.length], velS)
                                    : getPadDefaultBg(activeCol));

                            Object fwEngineObj =
                                bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                            if (fwEngineObj
                                instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                              if (editedModelTrack < fwEngine.sounds.size()
                                  && !isSequencerPlaying()) {
                                org.deluge.firmware2.GlobalEffectable sound =
                                    fwEngine.sounds.get(editedModelTrack);
                                if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                                  kit.triggerDrum(modelRow, 127);
                                } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                                  stopAuditionIfNeeded();
                                  auditionMidiNote = pitchMidi;
                                  auditionSynth = synth;
                                  synth.triggerNote(pitchMidi, 127);
                                }
                              }
                            }

                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                double curVel = bridge.getVelocity(engineRow, activeCol);
                                double curProb = bridge.getStepProbability(engineRow, activeCol);
                                cModel.setStep(
                                    modelRow,
                                    activeCol,
                                    org.deluge.model.StepData.of(
                                        !stepState,
                                        (float) curVel,
                                        org.deluge.model.StepData.DEFAULT_CLICK_GATE,
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
                            org.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
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
                                        trackColors[modelRow % trackColors.length], velK)
                                    : getPadDefaultBg(activeCol));
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                double curVel =
                                    bridge.getVelocity(baseTrackId + modelRow, activeCol);
                                double curProb =
                                    bridge.getStepProbability(baseTrackId + modelRow, activeCol);
                                cModel.setStep(
                                    modelRow,
                                    activeCol,
                                    org.deluge.model.StepData.of(
                                        !stepState,
                                        (float) curVel,
                                        org.deluge.model.StepData.DEFAULT_CLICK_GATE,
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

                              // ── High-Fidelity Audition ──
                              Object fwEngineObj =
                                  bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                              if (fwEngineObj
                                  instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                                if (editedModelTrack < fwEngine.sounds.size()
                                    && !isSequencerPlaying()) {
                                  org.deluge.firmware2.GlobalEffectable sound =
                                      fwEngine.sounds.get(editedModelTrack);
                                  if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                                    kit.triggerDrum(modelRow, 127);
                                  } else if (sound
                                      instanceof org.deluge.engine.FirmwareSound synth) {
                                    int pitchMidi = ((128 - 1) - modelRow) + 0;
                                    stopAuditionIfNeeded();
                                    auditionMidiNote = pitchMidi;
                                    auditionSynth = synth;
                                    synth.triggerNote(pitchMidi, 127);
                                  }
                                }
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

                        Object fwEngineObj =
                            bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                        if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                          if (editedModelTrack < fwEngine.sounds.size()) {
                            org.deluge.firmware2.GlobalEffectable sound =
                                fwEngine.sounds.get(editedModelTrack);
                            if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                              if (modelRow < kit.drumSounds.size()) {
                                kit.drumSounds.get(modelRow).releaseNote(60);
                              }
                            } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                              int pitchMidi = ((128 - 1) - modelRow) + 0;
                              synth.releaseNote(pitchMidi);
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

                        Object fwEngineObj =
                            bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                        if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                          if (editedModelTrack < fwEngine.sounds.size()) {
                            org.deluge.firmware2.GlobalEffectable sound =
                                fwEngine.sounds.get(editedModelTrack);
                            if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                              if (modelRow < kit.drumSounds.size()) {
                                kit.drumSounds.get(modelRow).releaseNote(60);
                              }
                            } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                              int pitchMidi = ((128 - 1) - modelRow) + 0;
                              synth.releaseNote(pitchMidi);
                            }
                          }
                        }
                      }
                    });
              }
              break;
            case SONG:
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
          final org.deluge.model.TrackModel songTrack = tracks.get(modelRow);
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
                          new org.deluge.model.ClipModel(
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
                  if (SwingDelugeApp.mainInstance != null
                      && SwingDelugeApp.mainInstance.getArrangerScheduler() != null) {
                    org.deluge.model.TrackModel targetTrack = tracks.get(modelRow);
                    if (colId >= 0 && colId < targetTrack.getClips().size()) {
                      SwingDelugeApp.mainInstance
                          .getArrangerScheduler()
                          .notifyClipLaunched(modelRow, targetTrack.getClips().get(colId));
                    } else {
                      SwingDelugeApp.mainInstance
                          .getArrangerScheduler()
                          .notifyClipStopped(modelRow);
                    }
                  }
                }
                clipBtn.setBackground(new Color(0xff, 0xaa, 0x00)); // amber = queued
                refresh();
              } else if (viewMode == GridViewMode.ARRANGEMENT) {
                if (clipBtn.getBackground().equals(trackColors[modelRow % trackColors.length])) {
                  clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                } else {
                  clipBtn.setBackground(trackColors[modelRow % trackColors.length]);
                }
              }
            });
      }

      if (isMuteColumn(c)) {
        rowPanel.add(Box.createHorizontalStrut(20));
      }
      rowPanel.add(clipBtn);
      rowPanel.add(Box.createHorizontalStrut(5));
    }

    return rowPanel;
  }

  void triggerKeyboardNote(int note) {
    if (isLiveRecordModeActive
        && currentPlayheadStep >= 0
        && bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L) {
      int modelRow = 127 - note;
      int col = currentPlayheadStep % stepCount;
      // A synth clip is a 128-row piano roll (row = 127 - pitch, see buildVoiceRow pitchMidi),
      // so gate on the full pitch range — NOT voiceRowCount, which is the number of rows visible
      // in the current view (8 in KEYPLAY) and would reject every isomorphic note.
      if (modelRow >= 0 && modelRow < 128 && col >= 0 && col < stepCount) {
        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
          org.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
          if (track instanceof org.deluge.model.SynthTrackModel synthTrack) {
            int activeClipIdx = synthTrack.getActiveClipIndex();
            if (activeClipIdx >= 0 && activeClipIdx < synthTrack.getClips().size()) {
              org.deluge.model.ClipModel clip = synthTrack.getClips().get(activeClipIdx);
              clip.setStep(modelRow, col, org.deluge.model.StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
              int engineRow = baseTrackId + modelRow;
              if (bridge != null) {
                bridge.setStep(engineRow, col, true);
                bridge.setVelocity(engineRow, col, 1.0f);
              }
              SwingUtilities.invokeLater(() -> refresh());
            }
          }
        }
      }
    }

    try {
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
        if (editedModelTrack < fwEngine.sounds.size()) {
          org.deluge.firmware2.GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof org.deluge.engine.FirmwareSound synth) {
            synth.triggerNote(note, 127);
            return;
          } else if (sound instanceof org.deluge.engine.FirmwareKit kit) {
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

    try {
      org.deluge.shadow.core.ChuckEvent noteEv =
          (org.deluge.shadow.core.ChuckEvent) bridge.getGlobalObject("g_ck_noteOn");
      if (noteEv != null) {
        org.deluge.shadow.core.ChuckArray pitchArr =
            (org.deluge.shadow.core.ChuckArray) bridge.getGlobalObject(BridgeContract.G_PITCH);
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

    try {
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
        if (editedModelTrack < fwEngine.sounds.size()) {
          org.deluge.firmware2.GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof org.deluge.engine.FirmwareSound synth) {
            synth.releaseNote(note);
            return;
          } else if (sound instanceof org.deluge.engine.FirmwareKit kit) {
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

  /**
   * Trigger a specific drum by its kit index (used by KEYPLAY on kit tracks, which follow the
   * velocity-drums grid layout rather than the instrument-only isomorphic note layout).
   */
  void triggerKeyboardDrum(int drumIdx) {
    try {
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine
          && editedModelTrack < fwEngine.sounds.size()
          && fwEngine.sounds.get(editedModelTrack) instanceof org.deluge.engine.FirmwareKit kit) {
        if (drumIdx >= 0 && drumIdx < kit.drumSounds.size()) {
          kit.triggerDrum(drumIdx, 127);
        }
      }
    } catch (Exception ex) {
      LOG.warning("Hi-Fi drum trigger failed: " + ex.getMessage());
    }
  }

  void releaseKeyboardDrum(int drumIdx) {
    try {
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine
          && editedModelTrack < fwEngine.sounds.size()
          && fwEngine.sounds.get(editedModelTrack) instanceof org.deluge.engine.FirmwareKit kit) {
        if (drumIdx >= 0 && drumIdx < kit.drumSounds.size()) {
          kit.drumSounds.get(drumIdx).releaseNote(60);
        }
      }
    } catch (Exception ex) {
      LOG.warning("Hi-Fi drum release failed: " + ex.getMessage());
    }
  }

  /** Build a fixed row (MACROS, SLIDERS, KEYBOARD) for the CLIP grid. */
  private JPanel buildFixedRow(String type, int rowIdx, int padSz, int rowHeight) {
    JPanel rowPanel = new JPanel();
    rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
    rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
    int lw = currentLabelWidth();
    int rowW = getGridWidth(padSz, lw);
    rowPanel.setPreferredSize(new Dimension(rowW, rowHeight));
    rowPanel.setMinimumSize(new Dimension(rowW, rowHeight));
    rowPanel.setMaximumSize(new Dimension(rowW, rowHeight));
    rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

    JLabel label = new JLabel(type);
    lw = currentLabelWidth();
    label.setPreferredSize(new Dimension(lw, 30));
    label.setMinimumSize(new Dimension(lw, 30));
    label.setMaximumSize(new Dimension(lw, 30));
    label.setForeground(Color.LIGHT_GRAY);
    rowPanel.add(label);
    rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
    rowPanel.add(Box.createHorizontalStrut(5));

    VUMeterPanel vu = new VUMeterPanel();
    vu.setPreferredSize(new Dimension(12, rowHeight));
    vu.setMaximumSize(new Dimension(12, rowHeight));
    rowPanel.add(vu);
    rowPanel.add(Box.createHorizontalStrut(5));

    boolean isMacros = "MACROS".equals(type);

    for (int c = 0; c < columnCount; c++) {
      final int colId = c;
      JButton clipBtn;
      boolean isAdvanced =
          org.deluge.project.PreferencesManager.getGridPanelType()
              == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;

      if (isAdvanced) {
        if (isMacros) {
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
            pad.setNoteText(getNoteName(note));
            pad.setFont(
                new Font("SansSerif", Font.BOLD, rowHeight < 35 ? 9 : (padSz > 70 ? 14 : 10)));

            pad.addMouseListener(new KeyboardMouseAdapter(this, note));
            clipBtn = pad;
          } else {
            DelugePadButton pad = new DelugePadButton();
            pad.setEnabled(false);
            clipBtn = pad;
          }
        }
      } else {
        if (isMacros) {
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

            clipBtn = new JButton(getNoteName(note));
            clipBtn.setBackground(isBlack ? new Color(0x33, 0x33, 0x33) : Color.WHITE);
            clipBtn.setForeground(isBlack ? Color.WHITE : Color.BLACK);
            clipBtn.setFont(
                new Font("SansSerif", Font.BOLD, rowHeight < 35 ? 9 : (padSz > 70 ? 14 : 10)));

            clipBtn.addMouseListener(new KeyboardMouseAdapter(this, note));
          } else {
            clipBtn = new JButton();
            clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
            clipBtn.setEnabled(false);
          }
        }
      }

      clipBtn.setPreferredSize(new Dimension(padSz, rowHeight));
      clipBtn.setMinimumSize(new Dimension(padSz, rowHeight));
      clipBtn.setMaximumSize(new Dimension(padSz, rowHeight));
      clipBtn.setMargin(new Insets(0, 0, 0, 0));

      pads[rowIdx][c] = clipBtn;

      if (isMuteColumn(c)) {
        rowPanel.add(Box.createHorizontalStrut(20));
      }
      rowPanel.add(clipBtn);
      rowPanel.add(Box.createHorizontalStrut(5));
    }
    return rowPanel;
  }

  /** Flash a pad cell to indicate a note-on event from the isomorphic / QWERTY keyboard. */
  public void flashIsomorphicNote(int note) {
    if (viewMode != GridViewMode.CLIP || voicePanel == null) return;
    boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;

    int modelRow;
    if (isSynthMode) {
      modelRow = (128 - 1) - (note - 0);
    } else {
      modelRow = note % voiceRowCount;
    }

    int visibleRowIdx = modelRow - scrollOffset;
    if (visibleRowIdx >= 0 && visibleRowIdx < gridMode.rows) {
      try {
        Component rowComp = voicePanel.getComponent(visibleRowIdx);
        if (rowComp instanceof JPanel rowPanel) {
          // 1. Flash the row header label
          Component labelComp = rowPanel.getComponent(0);
          Color origColor = labelComp.getForeground();
          Font origFont = labelComp.getFont();
          labelComp.setForeground(new Color(0x00, 0xff, 0xcc));
          labelComp.setFont(origFont.deriveFont(Font.BOLD, origFont.getSize() + 1.5f));

          // 2. Set row glow highlight for sequencer step pads
          for (Component c : rowPanel.getComponents()) {
            if (c instanceof DelugePadButton pad) {
              Integer col = (Integer) pad.getClientProperty("col");
              if (col != null && col < 16) {
                pad.setRowGlow(true);
              }
            }
          }

          Timer restore =
              new Timer(
                  200,
                  ev -> {
                    labelComp.setForeground(origColor);
                    labelComp.setFont(origFont);
                    for (Component c : rowPanel.getComponents()) {
                      if (c instanceof DelugePadButton pad) {
                        pad.setRowGlow(false);
                      }
                    }
                  });
          restore.setRepeats(false);
          restore.start();
        }
      } catch (Exception ex) {
        // ignore out of bounds
      }
    }
  }

  public void updatePlayhead(int step) {
    this.currentPlayheadStep = step;
    int trackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
    int rowsToScan = (viewMode == GridViewMode.CLIP) ? voiceRowCount : gridMode.rows;
    boolean isAdvanced =
        org.deluge.project.PreferencesManager.getGridPanelType()
            == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;

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
          for (int c = 0; c < stepCount; c++) {
            if (pads[t][c] == null) continue;
            int engineRow = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
            int engineStep = (trackLen < stepCount) ? (c % trackLen) : (c + scrollOffsetX);
            boolean isTriggered = (bridge != null) && bridge.getStep(engineRow, engineStep);
            if (!isTriggered && pads[t][c].getBackground().equals(Color.WHITE)) {
              double vel = bridge.getVelocity(engineRow, engineStep);
              pads[t][c].setBackground(
                  bridge.getStep(engineRow, engineStep)
                      ? velocityBlend(trackColors[t % trackColors.length], vel)
                      : getPadDefaultBg(c + scrollOffsetX));
            }
          }
        }
      }
      return;
    }

    // Playhead Follow Auto-Scrolling Mode!
    if (step >= 0 && trackLen > stepCount) {
      int targetPageOffset = ((step % trackLen) / stepCount) * stepCount;
      if (targetPageOffset != scrollOffsetX) {
        final int fTargetPage = targetPageOffset;
        javax.swing.SwingUtilities.invokeLater(
            () -> {
              if (horizScrollBar != null && horizScrollBar.isEnabled()) {
                isScrollingProgrammatically = true;
                try {
                  horizScrollBar.setValue(fTargetPage);
                } finally {
                  isScrollingProgrammatically = false;
                }
              }
            });
      }
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
        for (int c = 0; c < stepCount; c++) {
          if (pads[t][c] == null) continue;
          int engineRow = baseTrackId + (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
          int engineStep = (trackLen < stepCount) ? (c % trackLen) : (c + scrollOffsetX);
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
                      : getPadDefaultBg(c + scrollOffsetX));
            }
          }
        }
      }
    }
  }

  /**
   * True while the sequencer is running. Edit-feedback auditions are suppressed during playback:
   * the sequencer plays the tapped step itself, so a redundant audition note clashes with / stacks
   * on the running sequence (and rings until the next tap) — that is the "garbage when adding cells
   * during playback" symptom.
   */
  private boolean isSequencerPlaying() {
    return bridge != null && bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L;
  }

  /**
   * Sends a MIDI note-on (channel 1) and schedules the matching note-off after {@code gateMs}. The
   * grid previews and the playhead used to emit note-ons with no note-offs, so any connected MIDI
   * device accumulated stuck notes. Pairing every on with a guaranteed off prevents that.
   */
  private void sendMidiNote(int note, int velocity, int gateMs) {
    org.rtmidijava.RtMidiOut out = finalMidiOut;
    if (out == null || note < 0 || note > 127) return;
    try {
      out.sendMessage(new byte[] {(byte) 0x90, (byte) note, (byte) velocity});
    } catch (Exception ex) {
      LOG.warning("MIDI note-on failed: " + ex.getMessage());
      return;
    }
    javax.swing.Timer off =
        new javax.swing.Timer(
            Math.max(1, gateMs),
            e -> {
              org.rtmidijava.RtMidiOut o = finalMidiOut;
              if (o == null) return;
              try {
                o.sendMessage(new byte[] {(byte) 0x80, (byte) note, (byte) 0});
              } catch (Exception ex) {
                LOG.warning("MIDI note-off failed: " + ex.getMessage());
              }
            });
    off.setRepeats(false);
    off.start();
  }

  /** Panic release: All-Notes-Off (CC123) on every channel — sent on stop / device change. */
  void allMidiNotesOff() {
    org.rtmidijava.RtMidiOut out = finalMidiOut;
    if (out == null) return;
    try {
      for (int ch = 0; ch < 16; ch++) {
        out.sendMessage(new byte[] {(byte) (0xB0 | ch), (byte) 123, (byte) 0});
      }
    } catch (Exception ex) {
      LOG.warning("MIDI all-notes-off failed: " + ex.getMessage());
    }
  }

  private void stopAuditionIfNeeded() {
    if (auditionMidiNote != -1 && auditionSynth != null) {
      try {
        auditionSynth.releaseNote(auditionMidiNote);
      } catch (Exception ignored) {
      }
      auditionMidiNote = -1;
      auditionSynth = null;
    }
  }

  /**
   * Force a full UI rebuild even when the grid structure is unchanged. refresh() skips rebuilding
   * track headers when column/row count is the same, so a preset swap (which changes only a track's
   * name/sound, not the structure) would otherwise leave the old name displayed.
   */
  public void forceRebuild() {
    lastColumnCount = -1; // invalidate the structural cache so refresh() rebuilds headers + names
    refresh();
  }

  public void refresh() {
    stopAuditionIfNeeded();
    refreshInProgress = true;

    // 1. Initial metrics setup to check if structural parameters changed
    voiceRowCount = computeVoiceRowCount();
    if ((viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION)
        && projectModel != null
        && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId >= 0 && activeClipId < t.getClips().size()) {
        org.deluge.model.ClipModel activeClip = t.getClips().get(activeClipId);
        this.stepCount = activeClip.isTripletMode() ? 12 : gridMode.columns;
      } else {
        this.stepCount = gridMode.columns;
      }
    } else {
      this.stepCount = gridMode.columns;
    }
    if (viewMode == GridViewMode.AUTOMATION) {
      this.columnCount = this.stepCount;
    } else {
      this.columnCount = this.stepCount + 2;
    }

    boolean structureChanged =
        (columnCount != lastColumnCount
            || voiceRowCount != lastVoiceRowCount
            || viewMode != lastViewMode
            || gridMode != lastGridMode
            || scrollOffset != lastScrollOffset
            || scrollOffsetX != lastScrollOffsetX
            || cachedPadSz != lastPadSz
            || getComponentCount() == 0);

    if (structureChanged) {
      lastColumnCount = columnCount;
      lastVoiceRowCount = voiceRowCount;
      lastViewMode = viewMode;
      lastGridMode = gridMode;
      lastScrollOffset = scrollOffset;
      lastScrollOffsetX = scrollOffsetX;
      lastPadSz = cachedPadSz;

      rebuildUIComponents();
    } else {
      refreshInPlace();
    }

    refreshInProgress = false;
  }

  private void refreshInPlace() {
    if (projectModel == null) return;
    updatePageBarHighlights();

    // Update scrollbar visual position and dynamic note-range tooltip
    if (vertScrollBar != null) {
      refreshInProgress = true;
      vertScrollBar.setValue(scrollOffset);
      refreshInProgress = false;
      updateScrollBarTooltip();
    }

    // 1. Sync song parameters
    if (bridge != null) {
      try {
        Object fwHandlerObj = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
        if (fwHandlerObj instanceof org.deluge.playback.PlaybackHandler fwHandler) {
          org.deluge.model.ProjectModel project = fwHandler.getProject();
          if (project != null) {
            for (org.deluge.model.TrackModel trackModel : project.getTracks()) {
              for (org.deluge.model.ClipModel clipModel : trackModel.getClips()) {
                int stepTicks = clipModel.isTripletMode() ? 32 : 24;
                clipModel.setLoopLength(clipModel.getStepCount() * stepTicks);
              }
            }
          }
        }
      } catch (Exception ex) {
        LOG.warning(
            "Real-time audio engine low-latency notes sync failed in-place: " + ex.getMessage());
      }
    }

    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();

    // 2. Update visual button properties in place
    if (viewMode == GridViewMode.CLIP) {
      int curTrackLen = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
      boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;

      for (int v = 0; v < gridMode.rows; v++) {
        int modelRow = scrollOffset + v;
        if (modelRow >= 0 && modelRow < voiceRowCount) {
          int engineR = baseTrackId + modelRow;
          boolean isMuted = bridge != null && bridge.getMute(engineR);

          for (int c = 0; c < columnCount; c++) {
            JButton clipBtn = pads[v][c];
            if (clipBtn == null) continue;

            if (isMuteColumn(c)) {
              // Mute button
              Color muteBg = isMuted ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
              clipBtn.setBackground(muteBg);
              clipBtn.setText(isMuted ? "UNMUTE" : "MUTE");
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(muteBg);
                pad.setMuted(isMuted);
                pad.setNoteText(isMuted ? "UNMUTE" : "MUTE");
              }
            } else if (isSoloColumn(c)) {
              // Audition / Row Label button - dynamically update text on scroll!
              boolean isSynth = false;
              if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                org.deluge.model.TrackModel tm = projectModel.getTracks().get(editedModelTrack);
                isSynth = tm instanceof org.deluge.model.SynthTrackModel;
              }
              String nName;
              if (isSynth) {
                int midiPitch = getDiatonicPitch(modelRow);
                String[] noteNames =
                    new String[] {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
                nName = noteNames[Math.max(0, midiPitch) % 12] + ((midiPitch / 12) - 1);
              } else if (projectModel != null
                  && editedModelTrack < projectModel.getTracks().size()
                  && projectModel.getTracks().get(editedModelTrack)
                      instanceof org.deluge.model.KitTrackModel kit) {
                nName =
                    (modelRow < kit.getDrums().size())
                        ? kit.getDrums().get(modelRow).getName()
                        : ("PAD " + (modelRow + 1));
                if (nName.toLowerCase().endsWith(".wav") || nName.toLowerCase().endsWith(".aif")) {
                  nName = nName.substring(0, nName.lastIndexOf('.'));
                }
              } else {
                nName = "ROW " + (modelRow + 1);
              }
              clipBtn.setText(nName);
            } else {
              // Normal step pads
              int activeCol;
              if (curTrackLen > 0 && curTrackLen < stepCount) {
                activeCol = c % curTrackLen;
              } else if (curTrackLen > stepCount) {
                activeCol = Math.min(c + scrollOffsetX, curTrackLen - 1);
              } else {
                activeCol = c;
              }

              double[] outVelProb = {0.8, 1.0};
              boolean stepState = isStepActiveOrSpanned(modelRow, activeCol, outVelProb);
              double vel = outVelProb[0];
              double prob = outVelProb[1];
              boolean inLoop = activeCol < curTrackLen;

              if (clipBtn instanceof DelugePadButton pad) {
                org.deluge.project.PreferencesManager.GridColorTheme theme =
                    org.deluge.project.PreferencesManager.getGridColorTheme();
                Color trackColor = trackColors[modelRow % trackColors.length];
                boolean inScale = true;
                boolean isRoot = false;

                if (isSynthMode) {
                  int pitchMidi = 127 - modelRow;
                  int keyOffset = getKeyMidiOffset(projectModel.getKey());
                  isRoot = (pitchMidi % 12 == keyOffset);
                  inScale =
                      org.deluge.model.Scales.isNoteInScale(
                          pitchMidi, keyOffset, scaleTypeFromName(projectModel.getScale()));
                }

                Color cellBaseColor =
                    getThemeColor(theme, trackColor, stepState, inScale, isRoot, modelRow);
                pad.setBaseColor(cellBaseColor);
                pad.setApplicable(inScale || !isSynthMode);
                pad.setTheme(theme);
                pad.setBeatMarker((c + scrollOffsetX) % 4 == 0);
                pad.setScaleRoot(isRoot);
                pad.setScaleNote(inScale);

                pad.setMuted(isMuted);
                pad.setInLoop(inLoop);
                pad.setActive(stepState);
                pad.setIntensity((float) (vel * (0.2f + 0.8f * prob)));
                pad.setTied(isStepTied(modelRow, activeCol));
                if (stepState) {
                  if (isSynthMode) {
                    int pitchMidi = 127 - modelRow;
                    pad.setNoteText(getNoteName(pitchMidi));
                  } else {
                    pad.setNoteText(String.format("v%d", (int) (vel * 100)));
                  }
                } else {
                  pad.setNoteText("");
                }
              } else {
                clipBtn.setBackground(
                    stepState
                        ? velocityBlend(trackColors[modelRow % trackColors.length], vel)
                        : getPadDefaultBg(activeCol));
              }
            }
          }
        }
      }
    } else if (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT) {
      for (int v = 0; v < gridMode.rows; v++) {
        int modelRow = scrollOffset + v;
        if (modelRow < voiceRowCount) {
          int engineRow = baseTrackId + modelRow;
          boolean isMuted = bridge != null && bridge.getMute(engineRow);
          boolean isSoloed = (soloRow == modelRow);

          for (int c = 0; c < columnCount; c++) {
            JButton clipBtn = pads[v][c];
            if (clipBtn == null) continue;

            if (modelRow >= tracks.size()) {
              Color darkBg = new Color(0x15, 0x15, 0x15);
              clipBtn.setBackground(darkBg);
              clipBtn.setForeground(Color.GRAY);
              clipBtn.setText("");
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(darkBg);
                pad.setTextColorOverride(Color.GRAY);
                pad.setDrawCenterCircle(false);
                pad.setIntensity(0.0f);
                pad.setActive(false);
                pad.setNoteText("");
                pad.setMuted(false);
                pad.setPlayhead(false);
                pad.setSelected(false);
                pad.setInLoop(true);
              }
              clipBtn.setComponentPopupMenu(null);
              clipBtn.setToolTipText(null);
            } else if (isMuteColumn(c)) {
              Color muteBg = isMuted ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
              clipBtn.setBackground(muteBg);
              clipBtn.setText(isMuted ? "UNMUTE" : "MUTE");
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(muteBg);
                pad.setIntensity(1.0f);
                pad.setActive(true);
                pad.setNoteText(isMuted ? "UNMUTE" : "MUTE");
              }
            } else if (isSoloColumn(c)) {
              Color labelBg;
              Color labelFg;
              if (soloRow == modelRow) {
                labelBg = Color.GREEN;
                labelFg = Color.BLACK;
              } else if (modelRow == 0) {
                labelBg = new Color(0xff, 0xb3, 0x00);
                labelFg = Color.BLACK;
              } else if (modelRow == voiceRowCount - 1) {
                labelBg = new Color(0x7b, 0x68, 0xee);
                labelFg = Color.WHITE;
              } else {
                labelBg = new Color(0x55, 0x55, 0x5a);
                labelFg = Color.WHITE;
              }
              clipBtn.setBackground(labelBg);
              clipBtn.setForeground(labelFg);

              String nName;
              if (viewMode == GridViewMode.SONG) {
                if (modelRow < tracks.size()) {
                  nName = tracks.get(modelRow).getName();
                } else {
                  nName = "+";
                }
              } else {
                nName = "ROW " + (modelRow + 1);
              }
              clipBtn.setText(nName);

              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(labelBg);
                pad.setTextColorOverride(labelFg);
                pad.setIntensity(1.0f);
                pad.setActive(true);
                pad.setNoteText(nName);
              }
            } else {
              boolean hasClip = false;
              if (modelRow < tracks.size()) {
                org.deluge.model.TrackModel track = tracks.get(modelRow);
                if (c < track.getClips().size()) {
                  hasClip = true;
                }
              }
              if (clipBtn instanceof DelugePadButton pad) {
                org.deluge.project.PreferencesManager.GridColorTheme theme =
                    org.deluge.project.PreferencesManager.getGridColorTheme();
                pad.setBaseColor(trackColors[modelRow % trackColors.length]);
                pad.setTheme(theme);
                pad.setActive(hasClip);
                pad.setMuted(isMuted);
                pad.setScaleRoot(false);
                pad.setScaleNote(false);
                pad.setBeatMarker(
                    viewMode == GridViewMode.ARRANGEMENT && ((c + scrollOffsetX) % 4 == 0));
              } else {
                if (hasClip) {
                  clipBtn.setBackground(trackColors[modelRow % trackColors.length]);
                } else {
                  clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                }
              }
            }
          }
        }
      }
    } else if (viewMode == GridViewMode.KEYPLAY) {
      boolean kitTrack = isEditedTrackKit();
      Color trackColor = trackColors[editedModelTrack % trackColors.length];
      org.deluge.project.PreferencesManager.GridColorTheme theme =
          org.deluge.project.PreferencesManager.getGridColorTheme();

      for (int v = 0; v < gridMode.rows; v++) {
        for (int c = 0; c < columnCount; c++) {
          JButton clipBtn = pads[v][c];
          if (clipBtn == null) continue;

          if (isMuteColumn(c) || isSoloColumn(c)) {
            clipBtn.setVisible(false);
            clipBtn.setEnabled(false);
          } else if (c < 16) {
            if (kitTrack) {
              int drumIdx = keyplayDrumIndex(v, c);
              boolean drumPlayable = drumIdx < editedKitDrumCount();
              boolean isPlaying =
                  bridge != null
                      && bridge.getGlobalInt(BridgeContract.G_PREVIEW_TRACK)
                          == (long) (baseTrackId + (v % 8));

              if (clipBtn instanceof DelugePadButton pad) {
                pad.setActive(drumPlayable);
                pad.setBaseColor(drumPlayable ? trackColor : new Color(0x1a, 0x1a, 0x1e));
                pad.setApplicable(drumPlayable);
                pad.setIntensity(isPlaying ? 1.0f : 0.4f);
              } else {
                clipBtn.setBackground(drumPlayable ? trackColor : new Color(0x22, 0x22, 0x24));
              }
            } else {
              int note = keyplayNote(v, c);
              int keyOffset = getKeyMidiOffset(projectModel.getKey());
              boolean isRoot = (note % 12 == keyOffset);
              boolean inScale =
                  org.deluge.model.Scales.isNoteInScale(
                      note, keyOffset, scaleTypeFromName(projectModel.getScale()));

              boolean isPlaying = false;
              Color cellBaseColor = getThemeColor(theme, trackColor, isPlaying, inScale, isRoot, v);

              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(cellBaseColor);
                pad.setApplicable(inScale);
                pad.setTheme(theme);
                pad.setBeatMarker(false);
                pad.setScaleRoot(isRoot);
                pad.setScaleNote(inScale);
                pad.setActive(isPlaying || isRoot || inScale);
                pad.setIntensity(isPlaying ? 1.0f : (isRoot ? 0.6f : 0.3f));
              } else {
                clipBtn.setBackground(cellBaseColor);
              }
            }
          }
        }
      }
    }

    repaint();
  }

  private void rebuildUIComponents() {
    stopAuditionIfNeeded();
    refreshInProgress = true;
    removeAll();
    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();

    voiceRowCount = computeVoiceRowCount();
    LOG.fine(
        () ->
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
    // Resolve dynamic active clip steps count limits (Triplet 12-step vs Straight 16-step!)
    if ((viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION)
        && projectModel != null
        && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      try {
        String tBanner = t.getType().name();
        String tTitle = t.getName();
        String tMetric = "TRANSPOSE " + projectModel.getTranspose();
        org.deluge.hid.FirmwareDisplay.get()
            .getVirtualOLED()
            .drawTrackScreen(tBanner, tTitle, tMetric);
      } catch (Throwable th) {
        // Shield
      }
      if (activeClipId >= 0 && activeClipId < t.getClips().size()) {
        org.deluge.model.ClipModel activeClip = t.getClips().get(activeClipId);
        this.stepCount = activeClip.isTripletMode() ? 12 : gridMode.columns;
      } else {
        this.stepCount = gridMode.columns;
      }
    } else {
      this.stepCount = gridMode.columns;
    }
    this.columnCount = this.stepCount + 2;

    // Stop old VU timer and clear visual registers maps to prevent Swing leaks!
    voiceVuMeters.clear();
    trackVuMeters.clear();
    if (globalVuTimer != null) {
      globalVuTimer.stop();
    }
    globalVuTimer =
        new Timer(
            33,
            ev -> {
              voiceVuMeters.forEach(
                  (r, vu) -> {
                    if (r >= 0 && r < vuLevels.length) {
                      vuLevels[r] *= 0.80;
                      vu.setLvl(vuLevels[r]);
                    }
                  });
              trackVuMeters.forEach(
                  (t, vu) -> {
                    if (t >= 0 && t < vuLevels.length) {
                      vuLevels[t] *= 0.80;
                      vu.setLvl(vuLevels[t]);
                    }
                  });
            });
    globalVuTimer.start();

    // Real-time pure timing audio engine notes hot-swap!
    if (bridge != null && projectModel != null) {
      try {
        Object fwHandlerObj = bridge.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
        if (fwHandlerObj instanceof org.deluge.playback.PlaybackHandler fwHandler) {
          org.deluge.model.ProjectModel project = fwHandler.getProject();
          if (project != null) {
            // Live update global parameters
            project.setTempoBPM(projectModel.getBpm());
            project.setSwingAmount(
                Math.max(-49, Math.min(49, (int) ((projectModel.getSwing() - 0.5f) * 100.0))));

            // Loop and ensure loop length
            for (org.deluge.model.TrackModel trackModel : project.getTracks()) {
              for (org.deluge.model.ClipModel clipModel : trackModel.getClips()) {
                int stepTicks = clipModel.isTripletMode() ? 32 : 24;
                clipModel.setLoopLength(clipModel.getStepCount() * stepTicks);
              }
            }
          } else {
            // Initial setup song compilation: compiles DSP engines in place and registers
            org.deluge.engine.FirmwareFactory.createSong(projectModel);
            fwHandler.setProject(projectModel);
          }
        }
      } catch (Exception ex) {
        LOG.warning("Real-time audio engine low-latency notes sync failed: " + ex.getMessage());
      }
    }

    // Reset scroll if needed
    int maxOffset = Math.max(0, voiceRowCount - gridMode.rows);
    if (scrollOffset > maxOffset) scrollOffset = maxOffset;

    // Compute dynamic pad size: always fit gridMode.rows × gridMode.columns cells in the viewport
    int padSz = cachedPadSz;
    int lw = currentLabelWidth();
    int rowW = getGridWidth(padSz, lw);

    int savedColCount = columnCount; // saved for SONG/ARRANGEMENT section below

    if (viewMode == GridViewMode.AUTOMATION) {
      // ===== AUTOMATION MODE =====
      // Two sub-modes: OVERVIEW (param status grid) and EDITOR (per-step value band editing)
      voiceRowCount = 8;
      columnCount = stepCount;

      // ── Get active clip (final copy for lambdas) ──
      org.deluge.model.ClipModel autoClip = null;
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
        int acIdx = t.getActiveClipIndex();
        if (acIdx >= 0 && acIdx < t.getClips().size()) {
          autoClip = t.getClips().get(acIdx);
        }
      }
      final org.deluge.model.ClipModel fAutoClip = autoClip;

      // ── Header bar ──
      JPanel autoHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
      autoHeader.setBackground(new Color(0x15, 0x15, 0x15));
      autoHeader.setMaximumSize(new Dimension(rowW, 32));
      autoHeader.setAlignmentX(Component.LEFT_ALIGNMENT);

      int topLw = currentLabelWidth();
      int leftOffset = autoOverviewMode ? (topLw + 17) : (topLw + 91);
      autoHeader.add(Box.createRigidArea(new Dimension(leftOffset, 1)));

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
            new javax.swing.JComboBox<>(org.deluge.model.AutomationParam.SYTH_PARAMS);
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
        org.deluge.model.TrackModel curTrack = projectModel.getTracks().get(editedModelTrack);
        int clipCount = curTrack.getClips().size();
        if (clipCount > 1) {
          JPanel clipBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
          clipBar.setBackground(new Color(0x10, 0x10, 0x10));
          clipBar.setMaximumSize(new Dimension(rowW, 26));
          JLabel clipLabel = new JLabel("Clips:");
          clipLabel.setForeground(Color.LIGHT_GRAY);
          clipLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
          clipBar.add(clipLabel);
          Color trackColor = trackColors[editedModelTrack % trackColors.length];
          for (int ci = 0; ci < clipCount; ci++) {
            org.deluge.model.ClipModel cm = curTrack.getClips().get(ci);
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
                  if (SwingDelugeApp.mainInstance != null
                      && SwingDelugeApp.mainInstance.getArrangerScheduler() != null) {
                    if (clipIdx >= 0 && clipIdx < curTrack.getClips().size()) {
                      SwingDelugeApp.mainInstance
                          .getArrangerScheduler()
                          .notifyClipLaunched(editedModelTrack, curTrack.getClips().get(clipIdx));
                    } else {
                      SwingDelugeApp.mainInstance
                          .getArrangerScheduler()
                          .notifyClipStopped(editedModelTrack);
                    }
                  }
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
        org.deluge.model.TrackModel curTrack = projectModel.getTracks().get(editedModelTrack);
        JPanel headerRow = new JPanel(new BorderLayout(10, 0));
        headerRow.setBackground(new Color(0x15, 0x15, 0x15));
        headerRow.setMaximumSize(new Dimension(rowW, 36));
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
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

        // Simple numeric info labels inside controlsPanel (No clunky arrow text buttons!)
        JLabel vLabel =
            new JLabel(
                "VOICES: "
                    + (scrollOffset + 1)
                    + "-"
                    + Math.min(scrollOffset + gridMode.rows, voiceRowCount)
                    + " / "
                    + voiceRowCount);
        vLabel.setForeground(Color.LIGHT_GRAY);
        vLabel.setFont(new Font("Monospaced", Font.BOLD, 11));
        controlsPanel.add(vLabel);

        int trackLenH = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
        controlsPanel.add(Box.createHorizontalStrut(8));
        JSeparator sep = new JSeparator(JSeparator.VERTICAL);
        sep.setPreferredSize(new Dimension(2, 14));
        sep.setForeground(new Color(0x3e, 0x3e, 0x42));
        controlsPanel.add(sep);
        controlsPanel.add(Box.createHorizontalStrut(8));

        JLabel hLabel =
            new JLabel(
                "STEPS: "
                    + (scrollOffsetX + 1)
                    + "-"
                    + Math.min(scrollOffsetX + stepCount, trackLenH)
                    + " / "
                    + trackLenH);
        hLabel.setForeground(Color.LIGHT_GRAY);
        hLabel.setFont(new Font("Monospaced", Font.BOLD, 11));
        controlsPanel.add(hLabel);

        headerRow.add(controlsPanel, BorderLayout.EAST);
        add(headerRow);
      }

      // Section 2: Scrollable voice rows — always show gridMode.rows slots in the viewport
      voicePanel = new JPanel();
      voicePanel.setBackground(new Color(0x15, 0x15, 0x15));
      voicePanel.setOpaque(true);
      voicePanel.setLayout(new BoxLayout(voicePanel, BoxLayout.Y_AXIS));
      for (int v = 0; v < gridMode.rows; v++) {
        int modelRow = scrollOffset + v;
        if (modelRow >= 0 && modelRow < voiceRowCount) {
          JPanel row = buildVoiceRow(modelRow, v, padSz, tracks);
          voicePanel.add(row);
        } else {
          // Blank filler rows for viewport slots beyond actual voice count
          JPanel blankRow = new JPanel();
          blankRow.setPreferredSize(new Dimension(rowW, padSz));
          blankRow.setMaximumSize(new Dimension(rowW, padSz));
          blankRow.setBackground(new Color(0x22, 0x22, 0x22));
          voicePanel.add(blankRow);
        }
      }
      JPanel voiceWrapper = new JPanel(new BorderLayout());
      voiceWrapper.setBackground(new Color(0x15, 0x15, 0x15));
      boolean isSynthTrack = false;
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        isSynthTrack =
            projectModel.getTracks().get(editedModelTrack)
                instanceof org.deluge.model.SynthTrackModel;
      }
      boolean showNavPanel =
          (voiceRowCount > gridMode.rows) || (viewMode == GridViewMode.CLIP && isSynthTrack);

      int viewH = gridMode.rows * (padSz + 5) - 5;
      int wrapperW = rowW + (showNavPanel ? 32 : 0);
      voiceWrapper.setPreferredSize(new Dimension(wrapperW, viewH));
      voiceWrapper.setMaximumSize(new Dimension(wrapperW, viewH));
      voiceWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
      voiceWrapper.add(voicePanel, BorderLayout.CENTER);

      if (showNavPanel) {
        if (vertScrollBar == null) {
          vertScrollBar = new JScrollBar(JScrollBar.VERTICAL);
          vertScrollBar.setBackground(new Color(0x15, 0x15, 0x18));
          vertScrollBar.setForeground(new Color(0x00, 0xff, 0xcc));
          vertScrollBar.setPreferredSize(new Dimension(14, 200));

          vertScrollBar.setUI(
              new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override
                protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
                  g.setColor(new Color(0x1a, 0x1a, 0x1c)); // matching deep panel background
                  g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);

                  // Draw a sleek, thin center off-white path line (2px wide)
                  g.setColor(new Color(0xdd, 0xdd, 0xe0, 80));
                  int midX = trackBounds.x + trackBounds.width / 2;
                  g.fillRect(midX - 1, trackBounds.y, 2, trackBounds.height);
                }

                @Override
                protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                  // Draw a clean, solid white active segment thumb
                  g.setColor(Color.WHITE);
                  g.fillRoundRect(
                      thumbBounds.x + 3,
                      thumbBounds.y + 1,
                      thumbBounds.width - 6,
                      thumbBounds.height - 2,
                      4,
                      4);
                }

                @Override
                protected JButton createDecreaseButton(int orientation) {
                  return createZeroButton();
                }

                @Override
                protected JButton createIncreaseButton(int orientation) {
                  return createZeroButton();
                }

                private JButton createZeroButton() {
                  JButton b = new JButton();
                  b.setPreferredSize(new Dimension(0, 0));
                  b.setMinimumSize(new Dimension(0, 0));
                  b.setMaximumSize(new Dimension(0, 0));
                  return b;
                }
              });

          vertScrollBar.addAdjustmentListener(
              e -> {
                if (refreshInProgress) return;
                int val = e.getValue();
                if (val != scrollOffset) {
                  scrollOffset = val;
                  System.out.println(
                      "[TRACE grid] vertScrollBar adjust scrollOffset=" + scrollOffset);
                  refresh();
                }
              });
        }
        // Wrap the vertical scrollbar with sleek Page Up/Down buttons to scroll by whole pages (8
        // rows / 1 octave)
        JPanel pageNavPanel = new JPanel(new BorderLayout(0, 4));
        pageNavPanel.setBackground(new Color(0x15, 0x15, 0x18));

        JButton pgUpBtn = new JButton("▲");
        pgUpBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        pgUpBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
        pgUpBtn.setForeground(new Color(0x00, 0xff, 0xcc)); // glowing active cyan
        pgUpBtn.setBackground(new Color(0x1f, 0x1f, 0x24));
        pgUpBtn.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
        pgUpBtn.setFocusable(false);
        pgUpBtn.setToolTipText("Page Up / Octave Up (Shift 8 rows up)");
        pgUpBtn.addActionListener(e -> scrollVertically(-gridMode.rows));

        JButton pgDnBtn = new JButton("▼");
        pgDnBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        pgDnBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
        pgDnBtn.setForeground(new Color(0x00, 0xff, 0xcc));
        pgDnBtn.setBackground(new Color(0x1f, 0x1f, 0x24));
        pgDnBtn.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
        pgDnBtn.setFocusable(false);
        pgDnBtn.setToolTipText("Page Down / Octave Down (Shift 8 rows down)");
        pgDnBtn.addActionListener(e -> scrollVertically(gridMode.rows));

        boolean showScrollControls = voiceRowCount > gridMode.rows;
        pgUpBtn.setVisible(showScrollControls);
        vertScrollBar.setVisible(showScrollControls);

        pageNavPanel.add(pgUpBtn, BorderLayout.NORTH);
        pageNavPanel.add(vertScrollBar, BorderLayout.CENTER);

        if (viewMode == GridViewMode.CLIP && isSynthTrack) {
          int gridRows = showScrollControls ? 2 : 1;
          JPanel southPanel = new JPanel(new java.awt.GridLayout(gridRows, 1, 0, 4));
          southPanel.setBackground(new Color(0x15, 0x15, 0x18));
          if (showScrollControls) {
            southPanel.add(pgDnBtn);
          }

          JButton foldBtn = new JButton(foldMode ? "UNFLD" : "FOLD");
          foldBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
          foldBtn.setFont(new Font("SansSerif", Font.BOLD, 8));
          foldBtn.setForeground(foldMode ? Color.BLACK : new Color(0x00, 0xff, 0xcc));
          foldBtn.setBackground(
              foldMode ? new Color(0x00, 0xff, 0xcc) : new Color(0x1f, 0x1f, 0x24));
          foldBtn.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
          foldBtn.setFocusable(false);
          foldBtn.setToolTipText("Fold Mode (Only show rows containing notes)");
          foldBtn.addActionListener(
              evt -> {
                foldMode = !foldMode;
                resetScrollOffset();
                refresh();
              });
          southPanel.add(foldBtn);
          pageNavPanel.add(southPanel, BorderLayout.SOUTH);
        } else {
          pgDnBtn.setVisible(showScrollControls);
          pageNavPanel.add(pgDnBtn, BorderLayout.SOUTH);
        }

        vertScrollBar.setValues(scrollOffset, gridMode.rows, 0, voiceRowCount);
        updateScrollBarTooltip();
        voiceWrapper.add(pageNavPanel, BorderLayout.EAST);
      }

      add(voiceWrapper);

      // Page Selection Bar in CLIP mode
      if (viewMode == GridViewMode.CLIP) {
        int trackLen = (bridge != null) ? bridge.getTrackLength(baseTrackId) : stepCount;
        int numPages = Math.max(1, (trackLen + 15) / 16);
        if (numPages > 1) {
          JPanel pageBar = new JPanel();
          pageBar.setLayout(new BoxLayout(pageBar, BoxLayout.X_AXIS));
          pageBar.setBackground(new Color(0x15, 0x15, 0x15));
          pageBar.setPreferredSize(new Dimension(rowW, 20));
          pageBar.setMinimumSize(new Dimension(100, 20));
          pageBar.setMaximumSize(new Dimension(rowW, 20));
          pageBar.setAlignmentX(Component.LEFT_ALIGNMENT);

          lw = currentLabelWidth();
          int leftSpacing = lw + 69;
          pageBar.add(Box.createRigidArea(new Dimension(leftSpacing, 10)));

          JLabel pageLabel = new JLabel("PAGE ");
          pageLabel.setFont(new Font("Monospaced", Font.BOLD, 10));
          pageLabel.setForeground(Color.GRAY);
          pageBar.add(pageLabel);

          int currentPageIndex = scrollOffsetX / 16;
          pageButtons.clear();
          for (int i = 0; i < numPages; i++) {
            final int pageIdx = i;
            JButton pageBtn = new JButton(String.valueOf(i + 1));
            pageBtn.setPreferredSize(new Dimension(22, 18));
            pageBtn.setMinimumSize(new Dimension(22, 18));
            pageBtn.setMaximumSize(new Dimension(22, 18));
            pageBtn.setFocusable(false);
            pageBtn.setFont(new Font("Monospaced", Font.BOLD, 9));
            pageBtn.setMargin(new Insets(0, 0, 0, 0));
            pageBtn.setOpaque(false);
            pageBtn.setContentAreaFilled(false);
            pageBtn.setFocusPainted(false);

            if (i == currentPageIndex) {
              pageBtn.setForeground(new Color(0x00, 0xff, 0xcc));
              pageBtn.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xff, 0xcc), 1));
            } else {
              pageBtn.setForeground(Color.GRAY);
              pageBtn.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
            }

            pageBtn.addActionListener(
                e -> {
                  scrollOffsetX = pageIdx * 16;
                  if (horizScrollBar != null) {
                    horizScrollBar.setValue(scrollOffsetX);
                  }
                  refresh();
                });
            pageButtons.add(pageBtn);
            pageBar.add(pageBtn);
            pageBar.add(Box.createRigidArea(new Dimension(4, 10)));
          }
          add(pageBar);
        }
      }

      // ── Interactive Horizontal Scrollbar aligned under step columns ──
      if (viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION) {
        JPanel scrollRow = new JPanel();
        scrollRow.setLayout(new BoxLayout(scrollRow, BoxLayout.X_AXIS));
        scrollRow.setBackground(new Color(0x15, 0x15, 0x15));
        scrollRow.setPreferredSize(new Dimension(rowW, 26));
        scrollRow.setMinimumSize(new Dimension(100, 26));
        scrollRow.setMaximumSize(new Dimension(rowW, 26));
        scrollRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        scrollRow.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));

        // Left spacer matching header labels and action buttons (pixel-perfect dynamic matching!)
        lw = currentLabelWidth();
        int leftSpacing;
        if (viewMode == GridViewMode.AUTOMATION) {
          leftSpacing = autoOverviewMode ? (lw + 17) : (lw + 91);
        } else {
          leftSpacing = lw + 69;
        }

        // Add Synth Config direct-access button for active Carrier Synth track
        if (viewMode == GridViewMode.CLIP
            && projectModel != null
            && editedModelTrack < projectModel.getTracks().size()
            && projectModel.getTracks().get(editedModelTrack)
                instanceof org.deluge.model.SynthTrackModel) {
          int remainingSpacer = leftSpacing - 110;
          if (remainingSpacer > 0) {
            scrollRow.add(Box.createRigidArea(new Dimension(remainingSpacer, 10)));
          }
          JButton synthCfgBtn = new JButton("⚙ SYNTH CONFIG");
          synthCfgBtn.setPreferredSize(new Dimension(110, 20));
          synthCfgBtn.setMinimumSize(new Dimension(110, 20));
          synthCfgBtn.setMaximumSize(new Dimension(110, 20));
          synthCfgBtn.setMargin(new Insets(0, 2, 0, 2));
          synthCfgBtn.setFont(new Font("SansSerif", Font.BOLD, 9));
          synthCfgBtn.setForeground(new Color(0x00, 0xff, 0xcc));
          synthCfgBtn.setOpaque(false);
          synthCfgBtn.setContentAreaFilled(false);
          synthCfgBtn.setFocusPainted(false);
          synthCfgBtn.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xff, 0xcc), 1));
          synthCfgBtn.setFocusable(false);
          synthCfgBtn.setToolTipText(
              "Open full synthesizer parameters dashboard (Envelopes, LFOs, Arp, FM matrix)");

          synthCfgBtn.addActionListener(
              e -> {
                Window owner = SwingUtilities.getWindowAncestor(synthCfgBtn);
                org.deluge.model.TrackModel activeTrack =
                    projectModel.getTracks().get(editedModelTrack);
                if (activeTrack instanceof org.deluge.model.SynthTrackModel synthTrack) {
                  new SwingSynthConfigDialog(
                          (Frame) owner, synthTrack, bridge, editedModelTrack, projectModel)
                      .setVisible(true);
                }
              });
          scrollRow.add(synthCfgBtn);
        } else {
          scrollRow.add(Box.createRigidArea(new Dimension(leftSpacing, 10)));
        }

        // Center scrollbar aligned to step columns width
        int trackLenH = (bridge != null) ? bridge.getTrackLength(baseTrackId) : stepCount;
        if (horizScrollBar == null) {
          horizScrollBar = new JScrollBar(JScrollBar.HORIZONTAL);
          horizScrollBar.setBackground(new Color(0x15, 0x15, 0x18));
          horizScrollBar.setForeground(new Color(0x00, 0xff, 0xcc));

          horizScrollBar.setUI(
              new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override
                protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
                  g.setColor(new Color(0x1a, 0x1a, 0x1c)); // matching deep panel background
                  g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);

                  // Draw a sleek, thin horizontal center off-white path line (2px wide)
                  g.setColor(new Color(0xdd, 0xdd, 0xe0, 80));
                  int midY = trackBounds.y + trackBounds.height / 2;
                  g.fillRect(trackBounds.x, midY - 2, trackBounds.width, 4);
                }

                @Override
                protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                  if (!horizScrollBar.isEnabled()) {
                    g.setColor(new Color(0x55, 0x55, 0x5a, 80)); // Dimmed disabled gray
                  } else {
                    g.setColor(Color.WHITE); // Solid white active segment
                  }
                  g.fillRoundRect(
                      thumbBounds.x + 1,
                      thumbBounds.y + 3,
                      thumbBounds.width - 2,
                      thumbBounds.height - 6,
                      4,
                      4);
                }

                @Override
                protected JButton createDecreaseButton(int orientation) {
                  return createZeroButton();
                }

                @Override
                protected JButton createIncreaseButton(int orientation) {
                  return createZeroButton();
                }

                private JButton createZeroButton() {
                  JButton b = new JButton();
                  b.setPreferredSize(new Dimension(0, 0));
                  b.setMinimumSize(new Dimension(0, 0));
                  b.setMaximumSize(new Dimension(0, 0));
                  return b;
                }
              });

          horizScrollBar.addAdjustmentListener(
              e -> {
                if (refreshInProgress) return;
                int val = e.getValue();
                if (val != scrollOffsetX) {
                  scrollOffsetX = val;
                  if (!isScrollingProgrammatically) {
                    playheadFollowMode = false;
                  }
                  System.out.println(
                      "[TRACE grid] horizScrollBar adjust scrollOffsetX=" + scrollOffsetX);
                  refresh();
                }
              });
        }
        // Resolve active clip and triplet mode
        org.deluge.model.TrackModel curTrack = null;
        if (projectModel != null
            && editedModelTrack >= 0
            && editedModelTrack < projectModel.getTracks().size()) {
          curTrack = projectModel.getTracks().get(editedModelTrack);
        }

        org.deluge.model.ClipModel activeClip = null;
        if (curTrack != null && activeClipId >= 0 && activeClipId < curTrack.getClips().size()) {
          activeClip = curTrack.getClips().get(activeClipId);
        }

        boolean activeTrip = activeClip != null && activeClip.isTripletMode();

        // Bottom play rate step speed resolution zoom JComboBox selector
        double currentRes = (bridge != null) ? bridge.getStepResolution() : 0.25;
        String[] rateLabels;
        double[] rateValues;
        if (activeTrip) {
          rateLabels =
              new String[] {"1 Bar", "1/2T", "1/4T", "1/8T", "1/16T", "1/32T", "1/64T", "1/128T"};
          rateValues =
              new double[] {
                4.0,
                4.0 / 3.0,
                2.0 / 3.0,
                1.0 / 3.0,
                0.5 / 3.0,
                0.25 / 3.0,
                0.125 / 3.0,
                0.0625 / 3.0
              };
        } else {
          rateLabels = new String[] {"1 Bar", "1/2", "1/4", "1/8", "1/16", "1/32", "1/64", "1/128"};
          rateValues = new double[] {4.0, 2.0, 1.0, 0.5, 0.25, 0.125, 0.0625, 0.03125};
        }

        int currentRateIdx = activeTrip ? 3 : 4; // default 1/8T for triplets, 1/16 for straight
        for (int i = 0; i < rateValues.length; i++) {
          if (Math.abs(rateValues[i] - currentRes) < 0.0001) {
            currentRateIdx = i;
            break;
          }
        }

        JComboBox<String> rateCombo = new JComboBox<>(rateLabels);
        rateCombo.setSelectedIndex(currentRateIdx);
        rateCombo.setFont(new Font("Monospaced", Font.BOLD, 10));
        rateCombo.setPreferredSize(new Dimension(90, 22));
        rateCombo.setMinimumSize(new Dimension(90, 22));
        rateCombo.setMaximumSize(new Dimension(90, 22));
        rateCombo.setForeground(new Color(0xff, 0xcc, 0x00));
        rateCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
        rateCombo.setFocusable(false);
        rateCombo.setToolTipText(
            "Change sequence play step rate resolution speed (Alt-Hold dial equivalent)");

        rateCombo.addActionListener(
            e -> {
              int idx = rateCombo.getSelectedIndex();
              if (idx >= 0 && idx < rateValues.length) {
                double newVal = rateValues[idx];
                if (bridge != null) {
                  bridge.setStepResolution(newVal);
                }
                if (SwingDelugeApp.mainInstance != null) {
                  SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                      "RATE", rateLabels[idx] + " ");
                }
                refresh();
              }
            });

        // Bottom loop step length badge controller
        int clipSteps = (bridge != null) ? bridge.getTrackLength(baseTrackId) : stepCount;
        JLabel bottomLenBadge = new JLabel("[" + clipSteps + "]");
        bottomLenBadge.setPreferredSize(new Dimension(48, 24));
        bottomLenBadge.setMinimumSize(new Dimension(48, 24));
        bottomLenBadge.setMaximumSize(new Dimension(48, 24));
        bottomLenBadge.setFont(new Font("Monospaced", Font.BOLD, 11));
        bottomLenBadge.setForeground(clipSteps == 16 ? Color.GRAY : new Color(0xff, 0xcc, 0x00));
        bottomLenBadge.setCursor(new Cursor(Cursor.HAND_CURSOR));
        bottomLenBadge.setToolTipText("Track step length (click/right-click to change)");
        bottomLenBadge.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mouseClicked(java.awt.event.MouseEvent e) {
                String input =
                    JOptionPane.showInputDialog(
                        SwingGridPanel.this, "Track step length (1-192):", clipSteps);
                if (input != null) {
                  try {
                    int newLen = Integer.parseInt(input.trim());
                    if (newLen >= 1 && newLen <= 192) {
                      if (projectModel != null
                          && editedModelTrack < projectModel.getTracks().size()) {
                        org.deluge.model.TrackModel track =
                            projectModel.getTracks().get(editedModelTrack);
                        int activeClipIdx = track.getActiveClipIndex();
                        if (activeClipIdx >= 0 && activeClipIdx < track.getClips().size()) {
                          org.deluge.model.ClipModel clip = track.getClips().get(activeClipIdx);
                          clip.setStepCount(newLen);
                        }
                      }
                      if (bridge != null) {
                        bridge.setTrackLength(baseTrackId, newLen);
                      }
                      refresh();
                    }
                  } catch (NumberFormatException ignored) {
                  }
                }
              }
            });
        horizScrollBar.setEnabled(true);
        int colsWidth = stepCount * (padSz + 5) - 5;
        horizScrollBar.setPreferredSize(new Dimension(colsWidth, 12));
        horizScrollBar.setMinimumSize(new Dimension(100, 12));
        horizScrollBar.setMaximumSize(new Dimension(colsWidth, 12));
        isScrollingProgrammatically = true;
        try {
          horizScrollBar.setValues(scrollOffsetX, stepCount, 0, Math.max(stepCount, trackLenH));
        } finally {
          isScrollingProgrammatically = false;
        }

        // Add scrollbar FIRST (pixel-perfect columns bounds alignment!)
        scrollRow.add(horizScrollBar);

        // Triplet divisions JToggleButton [3] (Triplet 12-step vs Straight 16-step toggle!)
        JButton tripletBtn = new JButton("3");
        tripletBtn.setPreferredSize(new Dimension(22, 22));
        tripletBtn.setMinimumSize(new Dimension(22, 22));
        tripletBtn.setMaximumSize(new Dimension(22, 22));
        tripletBtn.setFocusable(false);
        tripletBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
        tripletBtn.setMargin(new Insets(0, 0, 0, 0));

        tripletBtn.setEnabled(activeClip != null);
        tripletBtn.setOpaque(false);
        tripletBtn.setContentAreaFilled(false);
        tripletBtn.setFocusPainted(false);
        if (activeTrip) {
          tripletBtn.setForeground(new Color(0xff, 0xb3, 0x00)); // Glowing gold!
          tripletBtn.setBorder(BorderFactory.createLineBorder(new Color(0xff, 0xb3, 0x00), 1));
          tripletBtn.setToolTipText(
              "Triplet grid active (12-step/triplets). Click to return to straight 16-step grid.");
        } else {
          tripletBtn.setForeground(Color.GRAY); // Inactive gray!
          tripletBtn.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
          tripletBtn.setToolTipText(
              "Triplet grid inactive (straight 16-step). Click to activate 12-step triplet grid!");
        }

        final org.deluge.model.ClipModel fActiveClip = activeClip;
        tripletBtn.addActionListener(
            ev -> {
              if (fActiveClip != null) {
                boolean nextTrip = !fActiveClip.isTripletMode();
                fActiveClip.setTripletMode(nextTrip);
                fActiveClip.setStepCount(nextTrip ? 12 : 16);

                if (bridge != null) {
                  bridge.setTrackLength(baseTrackId, nextTrip ? 12 : 16);
                  bridge.setStepResolution(nextTrip ? (1.0 / 3.0) : 0.25);
                }

                refresh();
              }
            });

        // Add the zoom/resolution options to its right!
        scrollRow.add(Box.createRigidArea(new Dimension(15, 10)));
        scrollRow.add(rateCombo);
        scrollRow.add(Box.createRigidArea(new Dimension(6, 10)));
        scrollRow.add(tripletBtn);
        scrollRow.add(Box.createRigidArea(new Dimension(10, 10)));
        scrollRow.add(bottomLenBadge);

        // Right spacer matching columns 17/18 mute/solo panel (2 * padSz + 22)
        int rightSpacing = (viewMode == GridViewMode.AUTOMATION) ? 10 : (2 * padSz + 22);
        scrollRow.add(Box.createRigidArea(new Dimension(rightSpacing, 10)));

        add(scrollRow);
      }

      // Section 3: Fixed rows — MACROS, KEYBOARD (Combined).
      // Macro vertical sliders and keyboard keys now scale fluidly in perfect proportion to the
      // voice pads!
      int macroRowIdx = gridMode.rows;
      int keyboardRowIdx = gridMode.rows + 2;
      int macroHeight = (int) (padSz * 1.1);
      int keyboardHeight = (int) (padSz * 0.6);
      add(buildFixedRow("MACROS", macroRowIdx, padSz, Math.max(28, macroHeight)));
      add(buildFixedRow("KEYBOARD", keyboardRowIdx, padSz, Math.max(16, keyboardHeight)));

    } else {
      // ===== SONG / ARRANGEMENT: gridMode.rows + 3 fixed rows (MACROS/SLIDERS/KEYBOARD) =====
      columnCount = gridMode.columns + 2; // Use grid mode column count + MUTE/SOLO
      int songVoiceRows = gridMode.rows; // always draw full viewport slots

      // ── Section bar (A-Z) for SONG mode ──
      if (viewMode == GridViewMode.SONG) {
        java.util.List<SongSection> sections = getProjectModel().getSongSections();
        if (sections != null && !sections.isEmpty()) {
          JPanel sectionBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
          sectionBar.setBackground(new Color(0x15, 0x15, 0x15));
          sectionBar.setMaximumSize(new Dimension(rowW, 24));
          sectionBar.setAlignmentX(Component.LEFT_ALIGNMENT);
          JLabel secLabel = new JLabel("SECTION:");
          secLabel.setForeground(Color.LIGHT_GRAY);
          secLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
          sectionBar.add(secLabel);
          for (int i = 0; i < sections.size(); i++) {
            SongSection sec = sections.get(i);
            JButton btn = new JButton(sec.getId());
            btn.setFont(new Font("Monospaced", Font.BOLD, 10));
            btn.setMargin(new Insets(0, 6, 0, 6));
            btn.setBackground(new Color(0x2d, 0x3d, 0x2d));
            btn.setForeground(Color.WHITE);
            btn.setFocusable(false);
            btn.setToolTipText(
                "Activate/Launch Song Section "
                    + sec.getId()
                    + " (contains "
                    + sec.getPatternIds().size()
                    + " clips)");

            // Wire to dynamic Quick Help status bar!
            SwingSynthConfigDialog.attachHoverHelp(
                btn,
                "<b>LAUNCH SECTION "
                    + sec.getId()
                    + ":</b> Queues and launches all clips and pattern tracks associated with Section "
                    + sec.getId()
                    + " in perfect beat synchronization!");

            int sectionIdx = i;
            btn.addActionListener(e -> activateSection(sectionIdx));
            sectionBar.add(btn);
          }
          add(sectionBar);
        }
      }

      voicePanel = new JPanel();
      voicePanel.setBackground(new Color(0x15, 0x15, 0x15));
      voicePanel.setOpaque(true);
      voicePanel.setLayout(new BoxLayout(voicePanel, BoxLayout.Y_AXIS));

      for (int t = 0; t < songVoiceRows + 2; t++) {

        JPanel rowPanel = new JPanel();
        rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
        rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
        rowPanel.setPreferredSize(new Dimension(rowW, padSz));
        rowPanel.setMinimumSize(new Dimension(rowW, padSz));
        rowPanel.setMaximumSize(new Dimension(rowW, padSz));
        rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        final int currentTrack = (t < songVoiceRows) ? (scrollOffset + t) : t;
        if (t < songVoiceRows && currentTrack < tracks.size()) {
          String hex = tracks.get(currentTrack).getColourHex();
          if (hex != null && hex.startsWith("0x")) {
            try {
              int rgb = Integer.decode(hex.substring(0, 8)); // strip alpha if 8 chars
              trackColors[currentTrack % trackColors.length] = new Color(rgb);
            } catch (Exception e) {
              LOG.warning("Bad color hex for track " + currentTrack + ": " + e.getMessage());
            }
          }
        }
        String trackName;
        if (t < songVoiceRows
            && viewMode == GridViewMode.CLIP
            && projectModel != null
            && editedModelTrack < projectModel.getTracks().size()) {
          org.deluge.model.TrackModel rowTrack = projectModel.getTracks().get(editedModelTrack);
          if (rowTrack instanceof org.deluge.model.KitTrackModel kit) {
            // Kit CLIP rows show the individual sound name
            java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
            trackName =
                (t < sounds.size())
                    ? sounds.get(sounds.size() - 1 - t).getName()
                    : rowTrack.getName();
          } else {
            // Synth CLIP: row 0 shows track name, rows 1-7 show pitch
            trackName = (t == 0) ? rowTrack.getName() : "-" + t + "st";
          }
        } else {
          trackName =
              (t < songVoiceRows && currentTrack < tracks.size())
                  ? tracks.get(currentTrack).getName()
                  : "EMPTY " + (currentTrack + 1);
        }
        if (t == songVoiceRows) trackName = "MACROS";
        if (t == songVoiceRows + 1) trackName = "KEYBOARD";

        final int trk = currentTrack;
        final String tName = trackName;
        JLabel label = new JLabel(tName);
        lw = currentLabelWidth();
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
        if (t < songVoiceRows && currentTrack < tracks.size()) {
          org.deluge.model.TrackModel track = tracks.get(currentTrack);

          // Per-track ⚙ Configure now lives in the fixed inspector strip above the grid
          // (SwingDelugeApp.buildTrackInspectorStrip) so controls stay out of the scrolling
          // grid; the in-grid duplicate was removed.

          int stepLen = (bridge != null) ? bridge.getTrackLength(trk) : 16;
          JLabel lenBadge = new JLabel("[" + stepLen + "]");
          lenBadge.setPreferredSize(new Dimension(48, 26));
          lenBadge.setMinimumSize(new Dimension(48, 26));
          lenBadge.setMaximumSize(new Dimension(48, 26));
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
          // 21 + 48(lenBadge) = 69px to match the MACROS/KEYBOARD fixed-row spacer below
          // (the removed in-grid ⚙ Configure used to fill this; alignment must stay at 69).
          rowPanel.add(Box.createHorizontalStrut(21));
          rowPanel.add(lenBadge);
        } else {
          // 69px spacer to keep columns aligned with the real-track header above
          rowPanel.add(Box.createRigidArea(new Dimension(69, 1)));
        }

        rowPanel.add(Box.createHorizontalStrut(5));

        VUMeterPanel vu = new VUMeterPanel();
        vu.setPreferredSize(new Dimension(12, padSz));
        vu.setMaximumSize(new Dimension(12, padSz));
        rowPanel.add(vu);
        rowPanel.add(Box.createHorizontalStrut(5));

        trackVuMeters.put(trk, vu);

        String[] allParams = {
          "LEVEL", "PAN", "PITCH", "FILTER", "RESONANCE", "OSC1", "OSC2", "LFO",
          "MOD FX", "DELAY", "REVERB", "STUTTER", "PROBABILITY", "GATE", "VELOCITY", "SAMPLE"
        };

        for (int c = 0; c < columnCount; c++) {
          final int slot = c;
          final int trkId = t;
          final int colId = c;

          boolean isAdvanced =
              org.deluge.project.PreferencesManager.getGridPanelType()
                  == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
          JButton clipBtn;
          int macR = songVoiceRows, keyR = songVoiceRows + 1;
          if (colId >= 16) {
            DelugePadButton pad = new DelugePadButton();
            pad.putClientProperty("row", t);
            pad.putClientProperty("col", c);
            pad.setDrawCenterCircle(false);
            clipBtn = pad;
          } else if (trkId == macR && colId < 16) {
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

          if (viewMode == GridViewMode.CLIP
              && lockArmedTrack == editedModelTrack
              && lockArmedStep == (c + scrollOffsetX)) {
            clipBtn.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
          } else {
            clipBtn.setBorder(UIManager.getBorder("Button.border"));
          }

          if (t >= songVoiceRows && (isMuteColumn(colId) || isSoloColumn(colId))) {
            clipBtn.setVisible(false);
            clipBtn.setEnabled(false);
          } else if (t == macR) {
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
              clipBtn.setText(getNoteName(note));
              clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 10));

              clearActionListeners(clipBtn);
              clearKeyboardMouseListeners(clipBtn);
              clipBtn.addMouseListener(new KeyboardMouseAdapter(this, note));
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
                case KEYPLAY:
                  if (colId < 16) {
                    if (isEditedTrackKit()) {
                      int drumIdx = keyplayDrumIndex(trk, colId);
                      clipBtn.setText(drumIdx < editedKitDrumCount() ? ("D" + (drumIdx + 1)) : "");
                    } else {
                      clipBtn.setText(getNoteName(keyplayNote(trk, colId)));
                    }
                  } else {
                    clipBtn.setText("");
                  }
                  break;
                case CLIP:
                  if (colId < 16) {
                    double vel =
                        bridge != null ? bridge.getVelocity(baseTrackId + trk, colId) : 0.8;
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
                  } else {
                    clipBtn.setText("");
                  }
                  break;
                case ARRANGEMENT:
                  org.deluge.model.ArrangerClip placement = getArrangerClipAt(currentTrack, c);
                  if (placement != null && placement.clip() != null) {
                    clipBtn.setText(
                        "<html><center><font size='3'><b>"
                            + placement.clip().getName()
                            + "</b><br>Bar "
                            + (c + 1)
                            + "</font></center></html>");
                    if (clipBtn instanceof DelugePadButton pad) {
                      pad.setBaseColor(trackColors[currentTrack % trackColors.length]);
                      pad.setIntensity(1.0f);
                      pad.setActive(true);
                    } else {
                      clipBtn.setBackground(trackColors[currentTrack % trackColors.length]);
                      clipBtn.setForeground(Color.BLACK);
                    }
                  } else {
                    clipBtn.setText(
                        "<html><center><font color='#555555' size='3'>Bar "
                            + (c + 1)
                            + "</font></center></html>");
                    if (clipBtn instanceof DelugePadButton pad) {
                      pad.setBaseColor(new Color(0x1e, 0x1e, 0x22));
                      pad.setIntensity(0.2f);
                      pad.setActive(false);
                    } else {
                      clipBtn.setBackground(new Color(0x22, 0x22, 0x24));
                      clipBtn.setForeground(Color.GRAY);
                    }
                  }
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
            org.deluge.model.TrackModel track = tracks.get(t);
            if (c < track.getClips().size()) {
              hasClip = true;
            }
          }

          if (isMuteColumn(colId)) {
            final int engineRow =
                (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT)
                    ? trk
                    : (baseTrackId + trk);
            if (viewMode == GridViewMode.SONG && t >= tracks.size()) {
              clipBtn.setText("");
              clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
              clipBtn.setEnabled(false);
              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(new Color(0x1a, 0x1a, 0x1a));
                pad.setActive(false);
                pad.setNoteText("");
                pad.setIntensity(0.2f);
              }
            } else {
              boolean isClipSynth = (viewMode == GridViewMode.CLIP) && (!isEditedTrackKit());
              if (isClipSynth) {
                clipBtn.setEnabled(false);
                clipBtn.setText("");
                clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0x1a, 0x1a, 0x1a));
                  pad.setActive(false);
                  pad.setNoteText("");
                  pad.setIntensity(0.2f);
                }
              } else {
                clipBtn.setEnabled(true);
                boolean curMute = bridge.getMute(engineRow);
                Color muteBg =
                    curMute
                        ? new Color(0xff, 0xd7, 0x00)
                        : Color.WHITE; // Yellow when muted, Pure Snow White when active
                clipBtn.setText(curMute ? "UNMUTE" : "MUTE");
                clipBtn.setBackground(muteBg);
                clipBtn.setForeground(Color.BLACK);
                clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(muteBg);
                  pad.setTextColorOverride(Color.BLACK);
                  pad.setDrawCenterCircle(false);
                  pad.setIntensity(1.0f);
                  pad.setActive(true);
                  pad.setNoteText(curMute ? "UNMUTE" : "MUTE");
                }
              }
            }
            if (viewMode == null) {
              clipBtn.setToolTipText("Row " + (t + 1) + " Mute Toggle");
            } else
              switch (viewMode) {
                case CLIP ->
                    clipBtn.setToolTipText(
                        "Clip View: Row "
                            + (t + 1)
                            + " Mute / Unmute (Shift-Click to Clear Steps)");
                case SONG ->
                    clipBtn.setToolTipText("Song View: Track " + (t + 1) + " Full Track Mute");
                case ARRANGEMENT ->
                    clipBtn.setToolTipText("Arrangement View: Lane " + (t + 1) + " Mute");
                case AUTOMATION ->
                    clipBtn.setToolTipText("Automation View: Parameter Lane " + (t + 1) + " Mute");
                default -> clipBtn.setToolTipText("Performance View: Live Stutter / Mute Punch");
              }
            javax.swing.ToolTipManager.sharedInstance().registerComponent(clipBtn);

            if (clipBtn.isEnabled()) {
              JPopupMenu mutePopup = createMutePopupMenu(engineRow);
              clipBtn.setComponentPopupMenu(mutePopup);
            }

            clearActionListeners(clipBtn);
            clipBtn.addActionListener(
                e -> {
                  if (viewMode == GridViewMode.SONG) {
                    if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                      // Shift+Click in SONG mode: Toggle Launch of active clip! (swapped)
                      if (trkId < tracks.size()) {
                        org.deluge.model.TrackModel track = tracks.get(trkId);
                        int activeClipIdx = track.getActiveClipIndex();
                        if (activeClipIdx >= 0 && activeClipIdx < track.getClips().size()) {
                          long currentClip = bridge.getCurrentClip(trkId);
                          boolean isPlaying =
                              (currentClip == activeClipIdx) && (!bridge.getMute(trkId));
                          if (isPlaying) {
                            bridge.setLaunchQueue(trkId, -1);
                            if (SwingDelugeApp.mainInstance != null
                                && SwingDelugeApp.mainInstance.getArrangerScheduler() != null) {
                              SwingDelugeApp.mainInstance
                                  .getArrangerScheduler()
                                  .notifyClipStopped(trkId);
                            }
                          } else {
                            bridge.setLaunchQueue(trkId, activeClipIdx);
                            if (SwingDelugeApp.mainInstance != null
                                && SwingDelugeApp.mainInstance.getArrangerScheduler() != null) {
                              SwingDelugeApp.mainInstance
                                  .getArrangerScheduler()
                                  .notifyClipLaunched(trkId, track.getClips().get(activeClipIdx));
                            }
                          }
                        }
                      }
                    } else {
                      // Left-Click in SONG mode: Toggle Mute (intuitive, standard!)
                      boolean isMuted = bridge.getMute(engineRow);
                      setTrackMuteWithCapture(engineRow, !isMuted);
                      Color nextBg = (!isMuted) ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
                      clipBtn.setBackground(nextBg);
                      if (clipBtn instanceof DelugePadButton pad) {
                        pad.setBaseColor(nextBg);
                      }
                    }
                    refresh();
                    return;
                  }

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
                                org.deluge.model.StepData.of(
                                    true,
                                    (float) v,
                                    org.deluge.model.StepData.DEFAULT_CLICK_GATE,
                                    1.0f,
                                    0),
                                org.deluge.model.StepData.empty()));
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

                  if (viewMode == GridViewMode.CLIP && !isEditedTrackKit()) {
                    return;
                  }
                  boolean isMuted = bridge.getMute(engineRow);
                  boolean nextMute = !isMuted;
                  setTrackMuteWithCapture(engineRow, nextMute);
                  Color nextBg = nextMute ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
                  clipBtn.setBackground(nextBg);
                  if (clipBtn instanceof DelugePadButton pad) {
                    pad.setBaseColor(nextBg);
                    pad.setIntensity(1.0f);
                    pad.setActive(true);
                    pad.setTextColorOverride(Color.BLACK);
                  }
                });
          } else if (isSoloColumn(colId)) {
            if (viewMode == GridViewMode.SONG) {
              if (currentTrack < tracks.size()) {
                String sLaunch = tracks.get(currentTrack).getName();
                clipBtn.setText(sLaunch);
                clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
                clipBtn.setBackground(trackColors[currentTrack % trackColors.length]);
                clipBtn.setForeground(Color.BLACK);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(trackColors[currentTrack % trackColors.length]);
                  pad.setTextColorOverride(Color.BLACK);
                  pad.setDrawCenterCircle(false);
                  pad.setIntensity(1.0f);
                  pad.setActive(true);
                  pad.setNoteText(sLaunch);
                }
              } else {
                clipBtn.setText("+");
                clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 14 : 11));
                clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
                clipBtn.setForeground(Color.DARK_GRAY);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0x1a, 0x1a, 0x1a));
                  pad.setTextColorOverride(Color.DARK_GRAY);
                  pad.setDrawCenterCircle(false);
                  pad.setIntensity(0.2f);
                  pad.setActive(false);
                  pad.setNoteText("+");
                }
              }
            } else {
              boolean isSynth = false;
              if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                org.deluge.model.TrackModel tm = projectModel.getTracks().get(editedModelTrack);
                isSynth = tm instanceof org.deluge.model.SynthTrackModel;
              }
              boolean isOctaveC = isSynth && ((127 - (scrollOffset + t)) % 12 == 0);

              String nName;
              if (isSynth) {
                int midiPitch = (128 - 1) - (scrollOffset + t);
                String[] noteNames =
                    new String[] {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
                nName = noteNames[Math.max(0, midiPitch) % 12] + ((midiPitch / 12) - 1);
              } else if (projectModel != null
                  && editedModelTrack < projectModel.getTracks().size()
                  && projectModel.getTracks().get(editedModelTrack)
                      instanceof org.deluge.model.KitTrackModel kit) {
                nName =
                    (t < kit.getDrums().size())
                        ? kit.getDrums().get(t).getName()
                        : ("PAD " + (t + 1));
                if (nName.toLowerCase().endsWith(".wav") || nName.toLowerCase().endsWith(".aif")) {
                  nName = nName.substring(0, nName.lastIndexOf('.'));
                }
              } else {
                nName = "ROW " + (t + 1);
              }
              clipBtn.setText(nName);
              clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));

              Color cellBg;
              Color cellFg;
              if (soloRow == t) {
                cellBg = Color.GREEN;
                cellFg = Color.BLACK;
              } else if (t == 0) {
                cellBg = new Color(0xff, 0xb3, 0x00); // Bright Amber Gold for first top cell
                cellFg = Color.BLACK;
              } else if (t == 7 || t == voiceRowCount - 1) {
                cellBg = new Color(0x7b, 0x68, 0xee); // Distinct Soft Purple for last bottom cell
                cellFg = Color.WHITE;
              } else if (isOctaveC) {
                cellBg = new Color(0xb0, 0xe2, 0xff); // Soft octave teal/blue
                cellFg = Color.BLACK;
              } else {
                cellBg = new Color(0x55, 0x55, 0x5a); // Highly visible active Slate Grey
                cellFg = Color.WHITE;
              }
              clipBtn.setBackground(cellBg);
              clipBtn.setForeground(cellFg);

              if (clipBtn instanceof DelugePadButton pad) {
                pad.setBaseColor(cellBg);
                pad.setTextColorOverride(cellFg);
                pad.setDrawCenterCircle(false);
                pad.setIntensity(1.0f);
                pad.setActive(true);
                pad.setNoteText(nName);
              }
            }
            if (viewMode == null) {
              clipBtn.setToolTipText("Audition Row " + (t + 1));
            } else
              switch (viewMode) {
                case CLIP ->
                    clipBtn.setToolTipText(
                        "Clip View: Audition / Preview Row "
                            + (t + 1)
                            + " Note ("
                            + clipBtn.getText()
                            + ")");
                case SONG ->
                    clipBtn.setToolTipText("Song View: Launch Track " + (t + 1) + " Primary Clip");
                case ARRANGEMENT ->
                    clipBtn.setToolTipText("Arrangement View: Cue Bar / Section Marker " + (t + 1));
                case AUTOMATION ->
                    clipBtn.setToolTipText(
                        "Automation View: Cue Parameter " + (t + 1) + " Automation");
                default ->
                    clipBtn.setToolTipText("Performance View: Recall Macro Snapshot " + (t + 1));
              }
            javax.swing.ToolTipManager.sharedInstance().registerComponent(clipBtn);

            clearActionListeners(clipBtn);
            clipBtn.addActionListener(
                e -> {
                  if (viewMode == GridViewMode.CLIP) {
                    // Audition the row sound immediately in Hi-Fi/Pure mode

                    try {
                      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                        if (editedModelTrack < fwEngine.sounds.size() && !isSequencerPlaying()) {
                          org.deluge.firmware2.GlobalEffectable sound =
                              fwEngine.sounds.get(editedModelTrack);
                          if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                            if (trk < kit.drumSounds.size()) kit.triggerDrum(trk, 127);
                          } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                            boolean isSynthModeLocal =
                                bridge != null && bridge.getTrackType(baseTrackId) == 1;
                            int pitchMidi = isSynthModeLocal ? (((128 - 1) - trk) + 0) : 60;
                            stopAuditionIfNeeded();
                            auditionMidiNote = pitchMidi;
                            auditionSynth = synth;
                            synth.triggerNote(pitchMidi, 127);
                          }
                        }
                      }
                    } catch (Exception ignored) {
                    }

                    // Toggle solo: solo this row or clear solo
                    if (soloRow == trk) {
                      soloRow = -1;
                      // Unmute all rows
                      for (int i = 0; i < 11; i++) setTrackMuteWithCapture(baseTrackId + i, false);
                    } else {
                      soloRow = trk;
                      // Mute all other rows, unmute this one
                      for (int i = 0; i < 11; i++) {
                        setTrackMuteWithCapture(baseTrackId + i, i != trk);
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
            if (viewMode == GridViewMode.KEYPLAY) {
              if (isEditedTrackKit()) {
                // Kit: velocity-drums grid. Pads backing a real drum are lit in the track colour;
                // pads beyond the last drum are dark (hardware: note > highestClipNote -> black).
                int drumIdx = keyplayDrumIndex(trk, colId);
                boolean hasDrum = drumIdx < editedKitDrumCount();
                Color padColor =
                    hasDrum
                        ? trackColors[t % trackColors.length].darker()
                        : new Color(0x16, 0x16, 0x16);
                clipBtn.setBackground(padColor);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(padColor);
                  pad.setIntensity(hasDrum ? 0.6f : 0.15f);
                  pad.setActive(hasDrum);
                  pad.setNoteText(hasDrum ? ("D" + (drumIdx + 1)) : "");
                }
              } else {
                int note = keyplayNote(trk, colId);
                boolean isRoot = isRootNote(note);
                boolean inScale = isNoteInScale(note);

                Color padColor = new Color(0x22, 0x22, 0x22);
                if (isRoot) {
                  padColor = new Color(0x00, 0xbb, 0xff);
                } else if (inScale) {
                  padColor = trackColors[t % trackColors.length].darker();
                }

                clipBtn.setBackground(padColor);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(padColor);
                  pad.setIntensity(inScale ? (isRoot ? 1.0f : 0.6f) : 0.2f);
                  pad.setActive(inScale);
                  pad.setNoteText(getNoteName(note));
                }
              }
            } else if (viewMode == GridViewMode.CLIP) {
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
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0xff, 0xaa, 0x00));
                  pad.setIntensity(1.0f);
                  pad.setActive(true);
                }
              } else if (currentClip == colId
                  && bridge != null
                  && bridge.getClipPlayMode(t, colId) == 1) {
                clipBtn.setBackground(new Color(0x00, 0xcc, 0x00)); // green = LOOP mode
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0x00, 0xcc, 0x00));
                  pad.setIntensity(1.0f);
                  pad.setActive(true);
                }
              } else if (currentClip == colId) {
                clipBtn.setBackground(trackColors[t % trackColors.length]); // playing
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(trackColors[t % trackColors.length]);
                  pad.setIntensity(1.0f);
                  pad.setActive(true);
                }
              } else if (hasClip) {
                clipBtn.setBackground(new Color(0x33, 0x44, 0x55)); // stopped
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0x33, 0x44, 0x55));
                  pad.setIntensity(0.5f);
                  pad.setActive(true);
                }
              } else {
                clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a)); // empty slot
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0x1a, 0x1a, 0x1a));
                  pad.setIntensity(0.2f);
                  pad.setActive(false);
                }
              }
            } else {
              if (hasClip) {
                clipBtn.setBackground(trackColors[currentTrack]);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(trackColors[currentTrack]);
                  pad.setIntensity(0.8f);
                  pad.setActive(true);
                }
              } else {
                clipBtn.setBackground(new Color(0x1a, 0x1a, 0x1a));
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0x1a, 0x1a, 0x1a));
                  pad.setIntensity(0.2f);
                  pad.setActive(false);
                  pad.setNoteText("");
                }
              }
            }

            if (viewMode == GridViewMode.KEYPLAY && colId < 16) {
              boolean kitTrack = isEditedTrackKit();
              int note = keyplayNote(trk, colId);
              int drumIdx = keyplayDrumIndex(trk, colId);
              boolean drumPlayable = kitTrack && drumIdx < editedKitDrumCount();
              clearActionListeners(clipBtn);
              clearKeyboardMouseListeners(clipBtn);
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (kitTrack) {
                        if (!drumPlayable) return; // empty drum slot: inert pad
                        triggerKeyboardDrum(drumIdx);
                      } else {
                        triggerKeyboardNote(note);
                      }
                      clipBtn.setBackground(new Color(0x00, 0xff, 0x66));
                      if (clipBtn instanceof DelugePadButton pad) {
                        pad.setBaseColor(new Color(0x00, 0xff, 0x66));
                        pad.setIntensity(1.0f);
                      }
                    }

                    @Override
                    public void mouseReleased(java.awt.event.MouseEvent e) {
                      if (kitTrack) {
                        if (drumPlayable) releaseKeyboardDrum(drumIdx);
                      } else {
                        triggerKeyboardNoteRelease(note);
                      }
                      refresh();
                    }
                  });
            } else if (viewMode == GridViewMode.CLIP) {
              if (isAdvanced && isStepColumn(colId)) {
                if (gestureCoordinator == null) {
                  gestureCoordinator =
                      new DelugeGestureCoordinator(this, new DelugeGestureListener());
                }
                java.awt.event.MouseAdapter gestureAdapter =
                    gestureCoordinator.createMouseAdapter(trk, colId);
                clipBtn.addMouseListener(gestureAdapter);
                clipBtn.addMouseMotionListener(gestureAdapter);
              } else {
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
                              org.deluge.model.StepData oldStep = null;
                              if (projectModel != null
                                  && editedModelTrack < projectModel.getTracks().size()) {
                                org.deluge.model.TrackModel tModel =
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
                                org.deluge.model.TrackModel tModel =
                                    projectModel.getTracks().get(editedModelTrack);
                                if (activeClipId < tModel.getClips().size()) {
                                  org.deluge.model.ClipModel cModel =
                                      tModel.getClips().get(activeClipId);
                                  boolean st = bridge.getStep(engineRow, colId);
                                  double prob = bridge.getStepProbability(engineRow, colId);
                                  cModel.setStep(
                                      trk,
                                      colId,
                                      new org.deluge.model.StepData(
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
                            org.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                oldStep = tModel.getClips().get(activeClipId).getStep(trk, colId);
                              }
                            }
                            boolean st = bridge.getStep(baseTrackId + trk, colId);
                            bridge.setStep(baseTrackId + trk, colId, !st);
                            if (!st) {
                              if (finalMidiOut != null) {
                                sendMidiNote(60 + trk, 100, 250); // preview
                              }
                            }
                            clipBtn.setBackground(
                                !st
                                    ? trackColors[6]
                                    : new Color(0x33, 0x33, 0x33)); // Blue for MIDI Track

                            // Write to model so changes survive view switches
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                cModel.setStep(
                                    trk,
                                    colId,
                                    org.deluge.model.StepData.of(
                                        !st,
                                        0.8f,
                                        org.deluge.model.StepData.DEFAULT_CLICK_GATE,
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
                            org.deluge.model.StepData oldStep = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
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
                            int midiPitch = (128 - 1) - trk;
                            bridge.setGlobalFloat(
                                BridgeContract.G_PREVIEW_PITCH, (float) midiPitch);
                            bridge.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, (long) slot);
                            bridge.broadcastGlobalEvent(BridgeContract.E_PREVIEW);
                            try {
                              String[] noteNames =
                                  new String[] {
                                    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
                                  };
                              String nName =
                                  noteNames[Math.max(0, midiPitch) % 12] + ((midiPitch / 12) - 1);
                              org.deluge.hid.FirmwareDisplay.get()
                                  .getVirtualOLED()
                                  .setNoteOverride(nName);
                            } catch (Throwable th) {
                              // Shield
                            }

                            // Write to model so changes survive view switches
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                org.deluge.model.ClipModel cModel =
                                    tModel.getClips().get(activeClipId);
                                double curVel = bridge.getVelocity(engineRow, colId);
                                double curProb = bridge.getStepProbability(engineRow, colId);
                                cModel.setStep(
                                    trk,
                                    colId,
                                    org.deluge.model.StepData.of(
                                        !stepState,
                                        (float) curVel,
                                        org.deluge.model.StepData.DEFAULT_CLICK_GATE,
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
                            // Audition on press — no step toggle in Hi-Fi/Pure mode

                            try {
                              Object fwEngineObj =
                                  bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                              if (fwEngineObj
                                  instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                                if (editedModelTrack < fwEngine.sounds.size()
                                    && !isSequencerPlaying()) {
                                  org.deluge.firmware2.GlobalEffectable sound =
                                      fwEngine.sounds.get(editedModelTrack);
                                  if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                                    if (trk < kit.drumSounds.size()) kit.triggerDrum(trk, 127);
                                  } else if (sound
                                      instanceof org.deluge.engine.FirmwareSound synth) {
                                    boolean isSynthModeLocal =
                                        bridge != null && bridge.getTrackType(baseTrackId) == 1;
                                    int pitchMidi = isSynthModeLocal ? (((128 - 1) - trk) + 0) : 60;
                                    stopAuditionIfNeeded();
                                    auditionMidiNote = pitchMidi;
                                    auditionSynth = synth;
                                    synth.triggerNote(pitchMidi, 127);
                                  }
                                }
                              }
                            } catch (Exception ignored) {
                            }
                          }
                        }
                      }

                      @Override
                      public void mouseReleased(java.awt.event.MouseEvent e) {
                        // Stop kit preview on release
                        bridge.setGlobalInt(BridgeContract.G_PREVIEW_TRACK, -1L);
                        bridge.broadcastGlobalEvent(BridgeContract.E_PREVIEW);

                        try {
                          Object fwEngineObj =
                              bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
                          if (fwEngineObj
                              instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
                            if (editedModelTrack < fwEngine.sounds.size()) {
                              org.deluge.firmware2.GlobalEffectable sound =
                                  fwEngine.sounds.get(editedModelTrack);
                              if (sound instanceof org.deluge.engine.FirmwareKit kit) {
                                if (trk < kit.drumSounds.size()) {
                                  kit.drumSounds.get(trk).releaseNote(60);
                                }
                              } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
                                boolean isSynthModeLocal =
                                    bridge != null && bridge.getTrackType(baseTrackId) == 1;
                                int pitchMidi = isSynthModeLocal ? (((128 - 1) - trk) + 0) : 60;
                                synth.releaseNote(pitchMidi);
                              }
                            }
                          }
                        } catch (Exception ignored) {
                        }

                        try {
                          org.deluge.hid.FirmwareDisplay.get().getVirtualOLED().clearNoteOverride();
                        } catch (Throwable th) {
                          // Shield
                        }
                      }
                    });
              }
            } else if (viewMode == GridViewMode.SONG) {
              // Handled by showClipContextMenu in cell mouse listener setup
            } else if (viewMode == GridViewMode.ARRANGEMENT) {
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        org.deluge.model.ArrangerClip placement =
                            getArrangerClipAt(currentTrack, colId);
                        if (placement != null) {
                          projectModel.getArrangerTimeline().remove(placement);
                          fireProjectChanged();
                          refresh();
                        }
                        return;
                      }

                      org.deluge.model.ArrangerClip placement =
                          getArrangerClipAt(currentTrack, colId);
                      if (e.getClickCount() == 2) {
                        if (placement != null) {
                          projectModel.getArrangerTimeline().remove(placement);
                          fireProjectChanged();
                          refresh();
                        }
                        return;
                      }

                      if (placement != null) {
                        dragArrangerClip = placement;
                        dragArrangerStartTicks = placement.startTicks();
                        dragArrangerDurationTicks = placement.durationTicks();
                        dragArrangerStartCol = colId;
                        isResizingArranger = e.isShiftDown();
                      } else {
                        showArrangerClipSelectionPopup(clipBtn, currentTrack, colId);
                      }
                    }

                    @Override
                    public void mouseReleased(java.awt.event.MouseEvent e) {
                      dragArrangerClip = null;
                      dragArrangerStartTicks = -1;
                      dragArrangerDurationTicks = -1;
                      isResizingArranger = false;
                      dragArrangerStartCol = -1;
                      fireProjectChanged();
                      refresh();
                    }
                  });

              clipBtn.addMouseMotionListener(
                  new java.awt.event.MouseMotionAdapter() {
                    @Override
                    public void mouseDragged(java.awt.event.MouseEvent e) {
                      if (dragArrangerClip == null || projectModel == null) return;

                      Point pt =
                          javax.swing.SwingUtilities.convertPoint(
                              e.getComponent(), e.getPoint(), SwingGridPanel.this);
                      int currCol = colId;
                      Component under = getComponentAt(pt);
                      if (under instanceof JPanel rowPanel) {
                        Component deepest =
                            rowPanel.getComponentAt(
                                new Point(pt.x - rowPanel.getX(), pt.y - rowPanel.getY()));
                        if (deepest instanceof javax.swing.JComponent jc) {
                          Integer col = (Integer) jc.getClientProperty("col");
                          if (col != null) currCol = col;
                        }
                      }

                      int colDiff = currCol - dragArrangerStartCol;
                      if (colDiff != 0) {
                        if (isResizingArranger) {
                          int newDurationTicks =
                              Math.max(96, dragArrangerDurationTicks + colDiff * 96);
                          projectModel.getArrangerTimeline().remove(dragArrangerClip);
                          org.deluge.model.ArrangerClip updated =
                              new org.deluge.model.ArrangerClip(
                                  currentTrack,
                                  dragArrangerClip.clip(),
                                  dragArrangerClip.startTicks(),
                                  newDurationTicks);
                          projectModel.addArrangerClip(updated);
                          dragArrangerClip = updated;
                          dragArrangerStartCol = currCol;
                          dragArrangerDurationTicks = newDurationTicks;
                          refresh();
                        } else {
                          int newStartTicks = Math.max(0, dragArrangerStartTicks + colDiff * 96);
                          projectModel.getArrangerTimeline().remove(dragArrangerClip);
                          org.deluge.model.ArrangerClip updated =
                              new org.deluge.model.ArrangerClip(
                                  currentTrack,
                                  dragArrangerClip.clip(),
                                  newStartTicks,
                                  dragArrangerClip.durationTicks());
                          projectModel.addArrangerClip(updated);
                          dragArrangerClip = updated;
                          dragArrangerStartCol = currCol;
                          dragArrangerStartTicks = newStartTicks;
                          refresh();
                        }
                      }
                    }
                  });
            }

            if (viewMode == GridViewMode.SONG && colId < 16) {
              if (t < tracks.size()) {
                final int clipCol = colId;
                final int trkIdx = currentTrack;
                final org.deluge.model.TrackModel songTrack = tracks.get(t);
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
                                new org.deluge.model.ClipModel(
                                    name,
                                    songTrack.getClips().isEmpty()
                                        ? 8
                                        : songTrack.getClips().get(0).getRowCount(),
                                    16));
                            fireProjectChanged();
                          } else {
                            if (SwingDelugeApp.mainInstance != null) {
                              SwingDelugeApp.mainInstance.switchToTrackEdit(trkIdx, clipCol);
                            }
                          }
                        }
                      }
                    });
              } else {
                final int clickRow = t;
                final int clickCol = colId;
                clipBtn.addMouseListener(
                    new java.awt.event.MouseAdapter() {
                      @Override
                      public void mousePressed(java.awt.event.MouseEvent e) {
                        if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                          showCreateTrackMenu(clipBtn, e.getX(), e.getY(), clickRow, clickCol);
                        }
                      }
                    });
              }
            } else if (viewMode == GridViewMode.SONG
                && isSoloColumn(colId)
                && t < songVoiceRows
                && currentTrack >= tracks.size()) {
              final int clickRow = currentTrack;
              final int clickCol = 0;
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                        showCreateTrackMenu(clipBtn, e.getX(), e.getY(), clickRow, clickCol);
                      }
                    }
                  });
            } else if (viewMode == GridViewMode.SONG
                && isSoloColumn(colId)
                && t < songVoiceRows
                && currentTrack < tracks.size()) {
              final int clickRow = currentTrack;
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isLeftMouseButton(e)) {
                        if (SwingDelugeApp.mainInstance != null) {
                          SwingDelugeApp.mainInstance.switchToTrackEdit(clickRow, 0);
                        }
                      }
                    }
                  });
            }

            clearActionListeners(clipBtn);
            clipBtn.addActionListener(
                e -> {
                  if (viewMode == GridViewMode.SONG) {
                    if ((e.getModifiers() & java.awt.event.ActionEvent.SHIFT_MASK) != 0) {
                      return;
                    }
                    if (colId < 16 && trkId < tracks.size()) {
                      bridge.setLaunchQueue(trkId, colId);
                      if (SwingDelugeApp.mainInstance != null
                          && SwingDelugeApp.mainInstance.getArrangerScheduler() != null) {
                        org.deluge.model.TrackModel targetTrack = tracks.get(trkId);
                        if (colId >= 0 && colId < targetTrack.getClips().size()) {
                          SwingDelugeApp.mainInstance
                              .getArrangerScheduler()
                              .notifyClipLaunched(trkId, targetTrack.getClips().get(colId));
                        } else {
                          SwingDelugeApp.mainInstance
                              .getArrangerScheduler()
                              .notifyClipStopped(trkId);
                        }
                      }
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

          if (isMuteColumn(c)) {
            rowPanel.add(Box.createHorizontalStrut(20));
          }
          boolean isSongOrArr =
              (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT);
          boolean isUnusedTrack =
              isSongOrArr && (currentTrack >= tracks.size()) && (t < songVoiceRows);
          if (isUnusedTrack) {
            Color darkBg = new Color(0x15, 0x15, 0x15);
            clipBtn.setBackground(darkBg);
            clipBtn.setForeground(Color.GRAY);
            clipBtn.setText("");
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setBaseColor(darkBg);
              pad.setTextColorOverride(Color.GRAY);
              pad.setDrawCenterCircle(false);
              pad.setIntensity(0.0f);
              pad.setActive(false);
              pad.setNoteText("");
              pad.setMuted(false);
              pad.setPlayhead(false);
              pad.setSelected(false);
              pad.setInLoop(true);
            }
            clipBtn.setComponentPopupMenu(null);
            clipBtn.setToolTipText(null);
            for (java.awt.event.ActionListener al : clipBtn.getActionListeners()) {
              clipBtn.removeActionListener(al);
            }
            for (java.awt.event.MouseListener ml : clipBtn.getMouseListeners()) {
              clipBtn.removeMouseListener(ml);
            }
          }

          if (t >= songVoiceRows && (isMuteColumn(colId) || isSoloColumn(colId))) {
            clipBtn.setVisible(false);
            clipBtn.setEnabled(false);
            clipBtn.setText("");
            if (clipBtn instanceof DelugePadButton pad) {
              pad.setActive(false);
              pad.setBaseColor(new Color(0x15, 0x15, 0x15));
              pad.setIntensity(0.0f);
              pad.setNoteText("");
            }
            for (java.awt.event.ActionListener al : clipBtn.getActionListeners()) {
              clipBtn.removeActionListener(al);
            }
            for (java.awt.event.MouseListener ml : clipBtn.getMouseListeners()) {
              clipBtn.removeMouseListener(ml);
            }
          }

          rowPanel.add(clipBtn);
          rowPanel.add(Box.createHorizontalStrut(5));
        }
        voicePanel.add(rowPanel);
      }

      JPanel voiceWrapper = new JPanel(new BorderLayout());
      voiceWrapper.setBackground(new Color(0x15, 0x15, 0x15));
      boolean showNavPanel = voiceRowCount > gridMode.rows;
      int viewH = gridMode.rows * (padSz + 5) - 5;
      int wrapperW = rowW + (showNavPanel ? 32 : 0);
      voiceWrapper.setPreferredSize(new Dimension(wrapperW, viewH));
      voiceWrapper.setMaximumSize(new Dimension(wrapperW, viewH));
      voiceWrapper.setAlignmentX(Component.LEFT_ALIGNMENT);
      voiceWrapper.add(voicePanel, BorderLayout.CENTER);

      if (showNavPanel) {
        if (vertScrollBar == null) {
          vertScrollBar = new JScrollBar(JScrollBar.VERTICAL);
          vertScrollBar.setBackground(new Color(0x15, 0x15, 0x18));
          vertScrollBar.setForeground(new Color(0x00, 0xff, 0xcc));
          vertScrollBar.setPreferredSize(new Dimension(14, 200));

          vertScrollBar.setUI(
              new javax.swing.plaf.basic.BasicScrollBarUI() {
                @Override
                protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
                  g.setColor(new Color(0x1a, 0x1a, 0x1c));
                  g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);

                  g.setColor(new Color(0xdd, 0xdd, 0xe0, 80));
                  int midX = trackBounds.x + trackBounds.width / 2;
                  g.fillRect(midX - 1, trackBounds.y, 2, trackBounds.height);
                }

                @Override
                protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                  g.setColor(Color.WHITE);
                  g.fillRoundRect(
                      thumbBounds.x + 3,
                      thumbBounds.y + 1,
                      thumbBounds.width - 6,
                      thumbBounds.height - 2,
                      4,
                      4);
                }

                @Override
                protected JButton createDecreaseButton(int orientation) {
                  return createZeroButton();
                }

                @Override
                protected JButton createIncreaseButton(int orientation) {
                  return createZeroButton();
                }

                private JButton createZeroButton() {
                  JButton b = new JButton();
                  b.setPreferredSize(new Dimension(0, 0));
                  b.setMinimumSize(new Dimension(0, 0));
                  b.setMaximumSize(new Dimension(0, 0));
                  return b;
                }
              });

          vertScrollBar.addAdjustmentListener(
              e -> {
                if (refreshInProgress) return;
                int val = e.getValue();
                if (val != scrollOffset) {
                  scrollOffset = val;
                  System.out.println(
                      "[TRACE grid] vertScrollBar adjust scrollOffset=" + scrollOffset);
                  refresh();
                }
              });
        }

        JPanel pageNavPanel = new JPanel(new BorderLayout(0, 4));
        pageNavPanel.setBackground(new Color(0x15, 0x15, 0x18));

        JButton pgUpBtn = new JButton("▲");
        pgUpBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        pgUpBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
        pgUpBtn.setForeground(new Color(0x00, 0xff, 0xcc));
        pgUpBtn.setBackground(new Color(0x1f, 0x1f, 0x24));
        pgUpBtn.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
        pgUpBtn.setFocusable(false);
        pgUpBtn.setToolTipText("Page Up (Shift 8 rows up)");
        pgUpBtn.addActionListener(e -> scrollVertically(-gridMode.rows));

        JButton pgDnBtn = new JButton("▼");
        pgDnBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        pgDnBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
        pgDnBtn.setForeground(new Color(0x00, 0xff, 0xcc));
        pgDnBtn.setBackground(new Color(0x1f, 0x1f, 0x24));
        pgDnBtn.setBorder(BorderFactory.createEmptyBorder(6, 2, 6, 2));
        pgDnBtn.setFocusable(false);
        pgDnBtn.setToolTipText("Page Down (Shift 8 rows down)");
        pgDnBtn.addActionListener(e -> scrollVertically(gridMode.rows));

        boolean showScrollControls = voiceRowCount > gridMode.rows;
        pgUpBtn.setVisible(showScrollControls);
        pgDnBtn.setVisible(showScrollControls);
        vertScrollBar.setVisible(showScrollControls);

        pageNavPanel.add(pgUpBtn, BorderLayout.NORTH);
        pageNavPanel.add(vertScrollBar, BorderLayout.CENTER);
        pageNavPanel.add(pgDnBtn, BorderLayout.SOUTH);

        vertScrollBar.setValues(scrollOffset, gridMode.rows, 0, voiceRowCount);
        updateScrollBarTooltip();
        voiceWrapper.add(pageNavPanel, BorderLayout.EAST);
      }

      add(voiceWrapper);
    } // end else (SONG/ARRANGEMENT)
    columnCount = savedColCount; // restore CLIP-mode columnCount

    if (viewMode == GridViewMode.CLIP) {
      Component strut = Box.createVerticalStrut(3);
      ((JComponent) strut).setAlignmentX(Component.LEFT_ALIGNMENT);
      add(strut);

      PianoRollComponent pianoRoll = new PianoRollComponent(this);
      pianoRoll.setAlignmentX(Component.LEFT_ALIGNMENT);
      add(pianoRoll);

      Component glue = Box.createVerticalGlue();
      ((JComponent) glue).setAlignmentX(Component.LEFT_ALIGNMENT);
      add(glue);
    }

    // DEBUG: dump every grid cell state after rebuild
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId >= 0 && activeClipId < t.getClips().size()) {
        org.deluge.model.ClipModel cm = t.getClips().get(activeClipId);
        System.out.println(
            "[GRID-DUMP] rows="
                + cm.getRowCount()
                + " steps="
                + cm.getStepCount()
                + " voiceRowCount="
                + voiceRowCount);
        for (int r = 0; r < cm.getRowCount(); r++) {
          int yNote = cm.getRowYNote(r);
          for (int s = 0; s < cm.getStepCount(); s++) {
            org.deluge.model.StepData sd = cm.getStep(r, s);
            if (sd.active()) {
              System.out.printf(
                  "[GRID-DUMP] row=%d col=%d yNote=%d ACTIVE pitch=%d vel=%.2f%n",
                  r, s, yNote, sd.pitch(), sd.velocity());
            }
          }
        }
        int total = 0;
        for (int r = 0; r < cm.getRowCount(); r++) {
          for (int s = 0; s < cm.getStepCount(); s++) {
            if (cm.getStep(r, s).active()) total++;
          }
        }
        System.out.println("[GRID-DUMP] total active cells: " + total);
      }
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
              // On the playing->stopped edge, release any MIDI notes left hanging on the device.
              boolean playingNow = isSequencerPlaying();
              if (wasSequencerPlaying && !playingNow) {
                allMidiNotesOff();
              }
              wasSequencerPlaying = playingNow;
              int currentStep = (int) bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP);
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
                  if (playheadFollowMode
                      && bridge != null
                      && bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L) {
                    int playheadPage = (rawCol / stepCount) * stepCount;
                    if (scrollOffsetX != playheadPage) {
                      scrollOffsetX = playheadPage;
                      if (horizScrollBar != null && horizScrollBar.isEnabled()) {
                        isScrollingProgrammatically = true;
                        horizScrollBar.setValue(scrollOffsetX);
                        isScrollingProgrammatically = false;
                      }
                      refresh();
                    }
                  }
                  int visualCol = rawCol - scrollOffsetX;
                  if (visualCol >= 0 && visualCol < stepCount) {
                    activeCol = visualCol;
                  } else {
                    activeCol = -1; // step is outside the visible window — hide border
                  }
                } else {
                  engineActiveCol = currentStep % stepCount;
                }

                boolean isAdvanced =
                    org.deluge.project.PreferencesManager.getGridPanelType()
                        == org.deluge.project.PreferencesManager.GridPanelType.ADVANCED;
                int rows =
                    (viewMode == GridViewMode.AUTOMATION)
                        ? 8
                        : (viewMode == GridViewMode.CLIP && isAdvanced)
                            ? voiceRowCount
                            : gridMode.rows;
                if (activeCol != lastCol[0]) {
                  lastCol[0] = activeCol;
                  if (activeCol == 0) {
                    for (int t = 0; t < rows; t++) {
                      int trkIdx = (viewMode == GridViewMode.AUTOMATION) ? t : (scrollOffset + t);
                      if (trkIdx < isOneShotTrack.length
                          && isOneShotTrack[trkIdx]
                          && currentStep >= stepCount) {
                        setTrackMuteWithCapture(baseTrackId + trkIdx, true);
                      }
                    }
                  }

                  for (int t = 0; t < rows; t++) {
                    int engineRow =
                        baseTrackId
                            + ((viewMode == GridViewMode.AUTOMATION) ? t : (scrollOffset + t));
                    if (bridge.getStep(engineRow, engineActiveCol)) {
                      if (engineRow < vuLevels.length) {
                        vuLevels[engineRow] = 1.0; // Spike VU Meter!
                      }
                      if (finalMidiOut != null) {
                        // Playhead step out: short gate so the note releases before the next step.
                        sendMidiNote(36 + t * 2, 100, 120);
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
                    int trackLenT = bridge != null ? bridge.getTrackLength(baseTrackId) : stepCount;
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
                        int modelRow =
                            (viewMode == GridViewMode.AUTOMATION) ? t : (scrollOffset + t);
                        boolean isSongOrArr =
                            (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT);
                        boolean isUnused = isSongOrArr && (modelRow >= tracks.size());
                        if (isUnused) {
                          Color darkBg = new Color(0x15, 0x15, 0x15);
                          pads[t][c].setBorder(UIManager.getBorder("Button.border"));
                          if (pads[t][c] instanceof DelugePadButton pad) {
                            pad.setActive(false);
                            pad.setBaseColor(darkBg);
                            pad.setIntensity(0.0f);
                          } else {
                            pads[t][c].setBackground(darkBg);
                          }
                          continue;
                        }
                        double[] outVelProb = {0.8, 1.0};
                        boolean stepActive = isStepActiveOrSpanned(modelRow, engineCol, outVelProb);
                        double velPb = outVelProb[0];
                        if (pads[t][c] instanceof DelugePadButton pad) {
                          pad.setActive(stepActive);
                          pad.setIntensity((float) (velPb * 0.8f));
                          pad.setBaseColor(trackColors[modelRow % trackColors.length]);
                          pad.setTied(isStepTied(modelRow, engineCol));
                        } else {
                          pads[t][c].setBackground(
                              stepActive
                                  ? velocityBlend(trackColors[modelRow % trackColors.length], velPb)
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
  private void buildAutomationEditor(org.deluge.model.ClipModel autoClip, String param, int padSz) {
    if (param == null) param = org.deluge.model.AutomationParam.SYTH_PARAMS[0];

    // Step number header row (pixel-perfect BoxLayout X_AXIS alignment!)
    JPanel stepHeader = new JPanel();
    stepHeader.setLayout(new BoxLayout(stepHeader, BoxLayout.X_AXIS));
    stepHeader.setBackground(new Color(0x15, 0x15, 0x15));
    stepHeader.setMaximumSize(new Dimension(3000, 20));
    int topLw = currentLabelWidth();
    stepHeader.add(Box.createRigidArea(new Dimension(topLw + 91, 20)));
    for (int c = 0; c < stepCount; c++) {
      JLabel stepNum = new JLabel(String.valueOf(c + 1), javax.swing.SwingConstants.CENTER);
      stepNum.setPreferredSize(new Dimension(padSz, 18));
      stepNum.setMinimumSize(new Dimension(padSz, 18));
      stepNum.setMaximumSize(new Dimension(padSz, 18));
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
      int lw = currentLabelWidth();
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
        DelugePadButton cell = new DelugePadButton();
        cell.setPreferredSize(new Dimension(padSz, padSz));
        cell.setMinimumSize(new Dimension(padSz, padSz));
        cell.setMaximumSize(new Dimension(padSz, padSz));
        cell.setMargin(new Insets(0, 0, 0, 0));
        cell.setDrawCenterCircle(false);

        cell.putClientProperty("row", r);
        cell.putClientProperty("col", c);
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
          int bright = 0x55 + precise * 8;
          cell.setBaseColor(new Color(0x00, bright, Math.min(0xcc, bright / 2 + 0x44)));
          cell.setIntensity(1.0f);
          cell.setActive(true);
          cell.setNoteText("\u25CF");
        } else {
          cell.setBaseColor(new Color(0x1d, 0x1d, 0x22));
          cell.setIntensity(0.15f);
          cell.setActive(false);
          if (autoVal >= 0f) {
            cell.setNoteText(".");
          } else {
            cell.setNoteText("");
          }
        }

        cell.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mousePressed(java.awt.event.MouseEvent e) {
                if (projectModel == null) return;
                int tIdx = editedModelTrack;
                if (tIdx >= projectModel.getTracks().size()) return;
                org.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                org.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);

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
                    org.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                    int acIdx2 = tM.getActiveClipIndex();
                    if (acIdx2 >= 0 && acIdx2 < tM.getClips().size()) {
                      org.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);
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
                org.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                org.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);

                Point pt =
                    javax.swing.SwingUtilities.convertPoint(
                        e.getComponent(), e.getPoint(), SwingGridPanel.this);
                Component under = getComponentAt(pt);
                if (under instanceof JPanel rowPanel) {
                  Component deepest =
                      rowPanel.getComponentAt(
                          new Point(pt.x - rowPanel.getX(), pt.y - rowPanel.getY()));
                  if (deepest instanceof javax.swing.JComponent jc) {
                    Integer rProp = (Integer) jc.getClientProperty("row");
                    Integer cProp = (Integer) jc.getClientProperty("col");
                    if (rProp != null && cProp != null && cProp < stepCount && rProp < 8) {
                      float val = (rProp * 16 + 8) / 127.0f;
                      cM.setAutomation(finalParam, cProp, val);
                      refresh();
                    }
                  }
                }
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
  private void buildAutomationOverview(org.deluge.model.ClipModel autoClip, int padSz) {
    String[] allParams = org.deluge.model.AutomationParam.SYTH_PARAMS;
    int totalParams = allParams.length;

    // Visible params (with vertical scroll)
    int maxVisible = 8;
    int paramOffset = autoColScroll;
    int visibleParams = Math.min(maxVisible, totalParams - paramOffset);
    if (visibleParams <= 0) return;

    // Step header (pixel-perfect BoxLayout X_AXIS alignment!)
    JPanel stepHeader = new JPanel();
    stepHeader.setLayout(new BoxLayout(stepHeader, BoxLayout.X_AXIS));
    stepHeader.setBackground(new Color(0x15, 0x15, 0x15));
    stepHeader.setMaximumSize(new Dimension(3000, 20));
    int topPw = currentLabelWidth();
    stepHeader.add(Box.createRigidArea(new Dimension(topPw + 17, 20)));
    for (int c = 0; c < stepCount; c++) {
      JLabel stepNum = new JLabel(String.valueOf(c + 1), javax.swing.SwingConstants.CENTER);
      stepNum.setPreferredSize(new Dimension(padSz, 18));
      stepNum.setMinimumSize(new Dimension(padSz, 18));
      stepNum.setMaximumSize(new Dimension(padSz, 18));
      stepNum.setForeground(Color.GRAY);
      stepNum.setFont(new Font("Monospaced", Font.PLAIN, 10));
      stepHeader.add(stepNum);
    }
    add(stepHeader);

    for (int r = 0; r < visibleParams; r++) {
      int paramIdx = paramOffset + r;
      String paramName = allParams[paramIdx];
      String label = org.deluge.model.AutomationParam.labelFor(paramName);

      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
      rowPanel.setPreferredSize(new Dimension(3000, padSz));
      rowPanel.setMinimumSize(new Dimension(3000, padSz));
      rowPanel.setMaximumSize(new Dimension(3000, padSz));

      // Param label (clickable to open editor)
      JButton paramBtn = new JButton(label);
      int pw = currentLabelWidth();
      paramBtn.setPreferredSize(new Dimension(pw, 30));
      paramBtn.setMinimumSize(new Dimension(pw, 30));
      paramBtn.setMaximumSize(new Dimension(pw, 30));
      paramBtn.setMargin(new Insets(0, 2, 0, 2));

      boolean hasAnyAuto = autoClip != null && autoClip.hasAutomation(paramName);
      Color paramBg = hasAnyAuto ? new Color(0x1a, 0x4d, 0x1a) : new Color(0x2d, 0x2d, 0x32);
      Color paramFg = hasAnyAuto ? new Color(0x88, 0xff, 0x88) : Color.LIGHT_GRAY;
      styleSystemButton(paramBtn, paramBg, paramFg, 10);
      paramBtn.setToolTipText(
          "Parameter: Click to edit automation steps / Shift-Click to clear parameter automation");

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
      upBtn.setMargin(new Insets(0, 0, 0, 0));
      upBtn.setPreferredSize(new Dimension(14, padSz / 2));
      upBtn.setEnabled(paramOffset > 0);
      styleSystemButton(upBtn, new Color(0x2d, 0x2d, 0x32), Color.LIGHT_GRAY, 7);
      upBtn.setToolTipText("Scroll parameter list up");
      upBtn.addActionListener(
          e -> {
            autoColScroll = Math.max(0, autoColScroll - 1);
            refresh();
          });
      scrollCol.add(upBtn);

      JButton downBtn = new JButton("\u25BC");
      downBtn.setMargin(new Insets(0, 0, 0, 0));
      downBtn.setPreferredSize(new Dimension(14, padSz / 2));
      downBtn.setEnabled(paramOffset + maxVisible < totalParams);
      styleSystemButton(downBtn, new Color(0x2d, 0x2d, 0x32), Color.LIGHT_GRAY, 7);
      downBtn.setToolTipText("Scroll parameter list down");
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
        DelugePadButton cell = new DelugePadButton();
        cell.setPreferredSize(new Dimension(padSz, padSz));
        cell.setMinimumSize(new Dimension(padSz, padSz));
        cell.setMaximumSize(new Dimension(padSz, padSz));
        cell.setMargin(new Insets(0, 0, 0, 0));
        cell.setDrawCenterCircle(false);

        pads[r][c] = cell;

        boolean hasAuto = autoClip != null && autoClip.hasAutomation(paramName, c);

        if (hasAuto) {
          float val = autoClip.getAutomation(paramName, c);
          int bright = 0x55 + (int) (val * 0xaa);
          cell.setBaseColor(new Color(0x00, bright, 0x66));
          cell.setIntensity(1.0f);
          cell.setActive(true);
          cell.setNoteText("\u25CF");
        } else {
          cell.setBaseColor(new Color(0x1d, 0x1d, 0x22));
          cell.setIntensity(0.15f);
          cell.setActive(false);
          cell.setNoteText("");
        }

        cell.addMouseListener(
            new java.awt.event.MouseAdapter() {
              @Override
              public void mousePressed(java.awt.event.MouseEvent e) {
                if (projectModel == null) return;
                int tIdx = editedModelTrack;
                if (tIdx >= projectModel.getTracks().size()) return;
                org.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                org.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);

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
                    org.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                    int acIdx2 = tM.getActiveClipIndex();
                    if (acIdx2 >= 0 && acIdx2 < tM.getClips().size()) {
                      org.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);
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
                org.deluge.model.TrackModel tM = projectModel.getTracks().get(tIdx);
                int acIdx2 = tM.getActiveClipIndex();
                if (acIdx2 < 0 || acIdx2 >= tM.getClips().size()) return;
                org.deluge.model.ClipModel cM = tM.getClips().get(acIdx2);
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
      JPanel scrollBar = new JPanel();
      scrollBar.setLayout(new BoxLayout(scrollBar, BoxLayout.X_AXIS));
      scrollBar.setBackground(new Color(0x1a, 0x1a, 0x1a));
      scrollBar.setMaximumSize(new Dimension(3000, 24));

      int pw = currentLabelWidth();
      scrollBar.add(Box.createRigidArea(new Dimension(pw + 17, 24)));

      for (int i = 0; i < totalParams; i += maxVisible) {
        int pageStart = i;
        boolean isActivePage = (i <= paramOffset && paramOffset < i + maxVisible);

        String firstParamName = allParams[i];
        String lastParamName = allParams[Math.min(totalParams - 1, i + maxVisible - 1)];
        String pageLabel =
            org.deluge.model.AutomationParam.labelFor(firstParamName)
                + "-"
                + org.deluge.model.AutomationParam.labelFor(lastParamName);

        JButton dot = new JButton(pageLabel);
        dot.setPreferredSize(new Dimension(90, 22));
        dot.setMinimumSize(new Dimension(90, 22));
        dot.setMaximumSize(new Dimension(90, 22));
        dot.setMargin(new Insets(0, 0, 0, 0));

        Color dotBg = isActivePage ? new Color(0x00, 0x99, 0x66) : new Color(0x2d, 0x2d, 0x32);
        Color dotFg = isActivePage ? Color.WHITE : Color.LIGHT_GRAY;
        styleSystemButton(dot, dotBg, dotFg, 9);
        dot.setToolTipText(
            "Go to parameter page "
                + (i / maxVisible + 1)
                + " (params: "
                + pageLabel.replace("-", " to ")
                + ")");

        int fPage = pageStart;
        dot.addActionListener(
            e -> {
              autoColScroll = fPage;
              refresh();
            });
        scrollBar.add(dot);
        scrollBar.add(Box.createHorizontalStrut(6));
      }
      add(scrollBar);
    }
  }

  // ── Automation interpolation ──

  /**
   * Linear interpolation between automated steps in a clip. Fills gaps (steps with -1) between two
   * known values. If fewer than 2 automated values exist, does nothing.
   */
  private void interpolateAutomation(org.deluge.model.ClipModel clip, String param) {
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
      org.deluge.model.TrackModel track = projectModel.getTracks().get(t);
      for (int c = 0; c < track.getClips().size(); c++) {
        if (section.getPatternIds().contains(track.getClips().get(c).getName())) {
          bridge.setLaunchQueue(t, c);
          if (SwingDelugeApp.mainInstance != null
              && SwingDelugeApp.mainInstance.getArrangerScheduler() != null) {
            SwingDelugeApp.mainInstance
                .getArrangerScheduler()
                .notifyClipLaunched(t, track.getClips().get(c));
          }
          break;
        }
      }
    }
    refresh();
  }

  // ── Advanced Pad Gestures & LED Pad Helpers ──

  private static org.deluge.model.StepData copiedStep = null;

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
        org.deluge.model.StepData oldStep = null;
        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
          org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
          if (activeClipId < tModel.getClips().size()) {
            oldStep = tModel.getClips().get(activeClipId).getStep(modelRow, activeCol);
          }
        }

        bridge.setStep(engineRow, activeCol, false);

        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
          org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
          if (activeClipId < tModel.getClips().size()) {
            org.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
            double curVel = bridge.getVelocity(engineRow, activeCol);
            double curProb = bridge.getStepProbability(engineRow, activeCol);
            int pitch = isSynthMode ? (((128 - 1) - modelRow) + 0) : 0;
            cModel.setStep(
                modelRow,
                activeCol,
                org.deluge.model.StepData.of(
                    false,
                    (float) curVel,
                    org.deluge.model.StepData.DEFAULT_CLICK_GATE,
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

  int getModelRow(int visualRow) {
    return scrollOffset + visualRow;
  }

  private int getActiveCol(int visualRow, int visualCol) {
    int modelRow = getModelRow(visualRow);
    int activeCol = visualCol;
    if (bridge != null && projectModel != null) {
      int trackLen = 0;
      java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();
      if (modelRow < tracks.size()) {
        org.deluge.model.TrackModel track = tracks.get(modelRow);
        if (activeClipId < track.getClips().size()) {
          trackLen = track.getClips().get(activeClipId).getStepCount();
        }
      }
      if (trackLen <= 0) trackLen = bridge.getTrackLength(baseTrackId);
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

  void handleStepPressed(int row, int col) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);
    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
    int trackType = bridge.getTrackType(modelRow);
    if (trackType == 2) {
      if (finalMidiOut != null) {
        sendMidiNote(60 + modelRow, 100, 250); // preview
      }
    } else if (isSynthMode) {
      int pitchMidi = ((128 - 1) - modelRow) + 0;

      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
        if (editedModelTrack < fwEngine.sounds.size() && !isSequencerPlaying()) {
          org.deluge.firmware2.GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof org.deluge.engine.FirmwareSound synth) {
            synth.triggerNote(pitchMidi, 127);
          }
        }
      }

    } else {

      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
        if (editedModelTrack < fwEngine.sounds.size() && !isSequencerPlaying()) {
          org.deluge.firmware2.GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
          if (sound instanceof org.deluge.engine.FirmwareKit kit) {
            kit.triggerDrum(modelRow, 127);
          }
        }
      }
    }
  }

  private void handleStepReleased(int row, int col) {
    if (bridge == null) return;
    int modelRow = getModelRow(row);

    Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
      if (editedModelTrack < fwEngine.sounds.size()) {
        org.deluge.firmware2.GlobalEffectable sound = fwEngine.sounds.get(editedModelTrack);
        if (sound instanceof org.deluge.engine.FirmwareKit kit) {
          if (modelRow < kit.drumSounds.size()) {
            kit.drumSounds.get(modelRow).releaseNote(60);
          }
        } else if (sound instanceof org.deluge.engine.FirmwareSound synth) {
          int pitchMidi = ((128 - 1) - modelRow) + 0;
          synth.releaseNote(pitchMidi);
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

    org.deluge.model.StepData oldStep = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        oldStep = tModel.getClips().get(activeClipId).getStep(modelRow, activeCol);
      }
    }

    boolean stepState = bridge.getStep(engineRow, activeCol);
    boolean nextState = !stepState;
    bridge.setStep(engineRow, activeCol, nextState);

    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        org.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
        double curVel = bridge.getVelocity(engineRow, activeCol);
        double curProb = bridge.getStepProbability(engineRow, activeCol);
        int pitch = isSynthMode ? (((128 - 1) - modelRow) + 0) : 0;
        cModel.setStep(
            modelRow,
            activeCol,
            org.deluge.model.StepData.of(
                nextState,
                (float) curVel,
                org.deluge.model.StepData.DEFAULT_CLICK_GATE,
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
    boolean synthModeActive = bridge.getTrackType(baseTrackId) == 1;
    JPopupMenu popup = new JPopupMenu();

    JMenuItem editProps = new JMenuItem("Edit Step Properties...");
    editProps.addActionListener(e -> showStepPropertiesDialog(row, col));
    popup.add(editProps);

    JMenuItem toggleStep = new JMenuItem("Toggle Step");
    toggleStep.addActionListener(e -> handleStepToggled(row, col));
    popup.add(toggleStep);

    if (synthModeActive) {
      JMenuItem pianoRollItem = new JMenuItem("Open Piano Roll Editor...");
      pianoRollItem.addActionListener(
          ev -> {
            SwingPianoRollDialog dlg =
                new SwingPianoRollDialog(
                    (Frame) javax.swing.SwingUtilities.getWindowAncestor(SwingGridPanel.this),
                    SwingGridPanel.this,
                    editedModelTrack,
                    activeClipId,
                    projectModel,
                    bridge);
            dlg.setVisible(true);
          });
      popup.add(pianoRollItem);
    }

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
              org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
              if (activeClipId < tModel.getClips().size()) {
                org.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
                org.deluge.model.StepData oldStep = cModel.getStep(modelRow, activeCol);
                boolean st = bridge.getStep(engineRow, activeCol);
                double vel = bridge.getVelocity(engineRow, activeCol);
                double prob = bridge.getStepProbability(engineRow, activeCol);
                int iter = bridge.getIterance(engineRow, activeCol);
                double fill = bridge.getStepFill(engineRow, activeCol);
                boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
                int pitch = isSynthMode ? (((128 - 1) - modelRow) + 0) : 0;

                org.deluge.model.StepData newStep =
                    new org.deluge.model.StepData(
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
          int pitch = isSynthMode ? (((128 - 1) - modelRow) + 0) : 0;
          copiedStep =
              new org.deluge.model.StepData(
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
              org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
              if (activeClipId < tModel.getClips().size()) {
                org.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
                org.deluge.model.StepData oldStep = cModel.getStep(modelRow, activeCol);
                boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;
                int pitch = isSynthMode ? (((128 - 1) - modelRow) + 0) : 0;
                org.deluge.model.StepData newStep =
                    new org.deluge.model.StepData(
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
    int pitch = isSynthMode ? (((128 - 1) - modelRow) + 0) : 0;

    org.deluge.model.StepData oldStep = null;
    org.deluge.model.TrackModel tModel = null;
    org.deluge.model.ClipModel cModel = null;
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
      org.deluge.model.StepData newStep =
          new org.deluge.model.StepData(
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
    int pitch = isSynthMode ? (((128 - 1) - modelRow) + 0) : 0;

    org.deluge.model.StepData oldStep = null;
    org.deluge.model.TrackModel tModel = null;
    org.deluge.model.ClipModel cModel = null;
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
      org.deluge.model.StepData newStep =
          new org.deluge.model.StepData(
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

  void handleStepTied(int row, int colStart, int colEnd) {
    if (bridge == null) return;
    int start = Math.min(colStart, colEnd);
    int end = Math.max(colStart, colEnd);

    int startModelCol = getActiveCol(row, start);
    int modelRow = getModelRow(row);
    int engineRow = baseTrackId + modelRow;
    boolean isSynthMode = bridge.getTrackType(baseTrackId) == 1;

    boolean startActive = bridge.getStep(engineRow, startModelCol);
    double vel = startActive ? bridge.getVelocity(engineRow, startModelCol) : 0.8;
    double prob = startActive ? bridge.getStepProbability(engineRow, startModelCol) : 1.0;
    int pitch = isSynthMode ? (((128 - 1) - modelRow) + 0) : 0;
    int iter = startActive ? bridge.getIterance(engineRow, startModelCol) : 0;
    double fill = startActive ? bridge.getStepFill(engineRow, startModelCol) : 0.0;

    org.deluge.model.TrackModel tModel = null;
    org.deluge.model.ClipModel cModel = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
      }
    }

    float totalGate = (end - start) + 0.9f;

    bridge.setStep(engineRow, startModelCol, true);
    bridge.setVelocity(engineRow, startModelCol, vel);
    bridge.setGate(engineRow, startModelCol, (double) totalGate);
    bridge.setStepProbability(engineRow, startModelCol, prob);
    bridge.setIterance(engineRow, startModelCol, iter);
    bridge.setStepFill(engineRow, startModelCol, fill);

    if (cModel != null) {
      org.deluge.model.StepData oldStart = cModel.getStep(modelRow, startModelCol);
      org.deluge.model.StepData newStart =
          new org.deluge.model.StepData(
              true, (float) vel, totalGate, (float) prob, pitch, iter, (float) fill);
      cModel.setStep(modelRow, startModelCol, newStart);
      if (oldStart != null && projectModel != null) {
        projectModel
            .getUndoRedoStack()
            .push(
                new Consequence.StepConsequence(
                    editedModelTrack, activeClipId, modelRow, startModelCol, oldStart, newStart));
      }
    }

    for (int c = start + 1; c <= end; c++) {
      int activeCol = getActiveCol(row, c);
      bridge.setStep(engineRow, activeCol, false);
      bridge.setGate(engineRow, activeCol, 0.0);

      if (cModel != null) {
        org.deluge.model.StepData oldStep = cModel.getStep(modelRow, activeCol);
        org.deluge.model.StepData newStep =
            new org.deluge.model.StepData(false, 0.8f, 0.0f, 1.0f, 0, 0, 0.0f);
        cModel.setStep(modelRow, activeCol, newStep);
        if (oldStep != null && projectModel != null && oldStep.active()) {
          projectModel
              .getUndoRedoStack()
              .push(
                  new Consequence.StepConsequence(
                      editedModelTrack, activeClipId, modelRow, activeCol, oldStep, newStep));
        }
      }
    }

    fireProjectChanged();
    refresh();
  }

  private boolean isStepActiveOrSpanned(int modelRow, int activeCol, double[] outVelProb) {
    org.deluge.model.TrackModel tModel = null;
    org.deluge.model.ClipModel cModel = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
      }
    }

    if (cModel != null) {
      for (int s = activeCol; s >= 0; s--) {
        org.deluge.model.StepData step = cModel.getStep(modelRow, s);
        if (step != null && step.active()) {
          float gateVal = step.gate();
          if (s + gateVal > activeCol + 0.05f) {
            if (outVelProb != null && outVelProb.length >= 2) {
              outVelProb[0] = step.velocity();
              outVelProb[1] = step.probability();
            }
            return true;
          }
          break;
        }
      }
    } else if (bridge != null) {
      int engineRow = baseTrackId + modelRow;
      for (int s = activeCol; s >= 0; s--) {
        if (bridge.getStep(engineRow, s)) {
          double gateVal = bridge.getGate(engineRow, s);
          if (s + gateVal > activeCol + 0.05f) {
            if (outVelProb != null && outVelProb.length >= 2) {
              outVelProb[0] = bridge.getVelocity(engineRow, s);
              outVelProb[1] = bridge.getStepProbability(engineRow, s);
            }
            return true;
          }
          break;
        }
      }
    }
    return false;
  }

  private boolean isStepTied(int modelRow, int activeCol) {
    org.deluge.model.TrackModel tModel = null;
    org.deluge.model.ClipModel cModel = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
      }
    }

    if (cModel != null) {
      for (int s = activeCol; s >= 0; s--) {
        org.deluge.model.StepData step = cModel.getStep(modelRow, s);
        if (step != null && step.active()) {
          float gateVal = step.gate();
          return (s + gateVal >= activeCol + 1.0f - 0.05f);
        }
      }
    } else if (bridge != null) {
      int engineRow = baseTrackId + modelRow;
      for (int s = activeCol; s >= 0; s--) {
        if (bridge.getStep(engineRow, s)) {
          double gateVal = bridge.getGate(engineRow, s);
          return (s + gateVal >= activeCol + 1.0f - 0.05f);
        }
      }
    }
    return false;
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
    org.deluge.model.TrackModel genericTrack = projectModel.getTracks().get(editedModelTrack);

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
            org.deluge.model.StepData.of(
                active, (float) vel, (float) gate, (float) prob, (int) fill),
            org.deluge.model.StepData.empty()));
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
    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();
    if (modelRow < 0 || modelRow >= tracks.size()) return;

    org.deluge.model.TrackModel track = tracks.get(modelRow);

    if (viewMode == GridViewMode.CLIP && track instanceof org.deluge.model.KitTrackModel kit) {
      java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
      int drumIdx = sounds.size() - 1 - visibleRow;
      if (drumIdx >= 0 && drumIdx < sounds.size()) {
        org.deluge.model.Drum drum = sounds.get(drumIdx);
        if (drum instanceof org.deluge.model.SoundDrum soundDrum) {
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
    } else if (track instanceof org.deluge.model.AudioTrackModel audioTrack) {
      if (audioTrack.getAudioClips().isEmpty()) {
        org.deluge.model.AudioTrackModel.AudioClip clip =
            new org.deluge.model.AudioTrackModel.AudioClip();
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
    org.deluge.model.TrackModel genericTrack = projectModel.getTracks().get(editedModelTrack);

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
    org.deluge.model.SynthTrackModel track =
        (genericTrack instanceof org.deluge.model.SynthTrackModel)
            ? (org.deluge.model.SynthTrackModel) genericTrack
            : null;

    boolean isRotary =
        org.deluge.project.PreferencesManager.getShiftInteractionMode()
            == org.deluge.project.PreferencesManager.ShiftInteractionMode.ROTARY_ENCODER;
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
                  new org.deluge.model.EnvelopeModel(
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
                  new org.deluge.model.EnvelopeModel(
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
                  new org.deluge.model.EnvelopeModel(
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
                  new org.deluge.model.EnvelopeModel(
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
      int row, int col, boolean applicable, org.deluge.model.TrackModel track) {
    String paramName = SHIFT_LABELS[row][col];
    if (paramName == null || paramName.isEmpty()) {
      return null;
    }
    String prefix = getGroupPrefix(col);
    String fullParam = (prefix != null) ? prefix + " " + paramName : paramName;

    String description = getParamDescription(paramName);
    String trackTypeStr =
        (track instanceof org.deluge.model.SynthTrackModel)
            ? "Synth Track"
            : (track instanceof org.deluge.model.KitTrackModel ? "Kit Track" : "Audio Track");
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
      String param, int row, int col, org.deluge.model.TrackModel track) {
    if (track == null || param == null || param.isEmpty()) return false;
    if (track instanceof org.deluge.model.SynthTrackModel) {
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
    org.deluge.model.TrackModel genericTrack = projectModel.getTracks().get(editedModelTrack);
    org.deluge.model.SynthTrackModel track =
        (genericTrack instanceof org.deluge.model.SynthTrackModel)
            ? (org.deluge.model.SynthTrackModel) genericTrack
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
    org.deluge.model.TrackModel genericTrack = projectModel.getTracks().get(editedModelTrack);
    org.deluge.model.SynthTrackModel track =
        (genericTrack instanceof org.deluge.model.SynthTrackModel)
            ? (org.deluge.model.SynthTrackModel) genericTrack
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
              new org.deluge.model.EnvelopeModel(
                  a, old.decay(), old.sustain(), old.release(), old.target(), old.amount()));
        }
        break;
      case "DECAY":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float d = Math.max(0.0f, Math.min(10.0f, old.decay() + delta * 0.05f));
          track.setEnv(
              envIdx,
              new org.deluge.model.EnvelopeModel(
                  old.attack(), d, old.sustain(), old.release(), old.target(), old.amount()));
        }
        break;
      case "SUSTAIN":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float s = Math.max(0.0f, Math.min(1.0f, old.sustain() + delta * 0.02f));
          track.setEnv(
              envIdx,
              new org.deluge.model.EnvelopeModel(
                  old.attack(), old.decay(), s, old.release(), old.target(), old.amount()));
        }
        break;
      case "RELEASE":
        if (track != null) {
          var old = track.getEnv(envIdx);
          float r = Math.max(0.0f, Math.min(10.0f, old.release() + delta * 0.05f));
          track.setEnv(
              envIdx,
              new org.deluge.model.EnvelopeModel(
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

  private static int getKeyMidiOffset(String key) {
    if (key == null) return 0;
    switch (key.toUpperCase().trim()) {
      case "C":
        return 0;
      case "C#":
      case "DF":
        return 1;
      case "D":
        return 2;
      case "D#":
      case "EF":
        return 3;
      case "E":
        return 4;
      case "F":
        return 5;
      case "F#":
      case "GF":
        return 6;
      case "G":
        return 7;
      case "G#":
      case "AF":
        return 8;
      case "A":
        return 9;
      case "A#":
      case "BF":
        return 10;
      case "B":
        return 11;
      default:
        return 0;
    }
  }

  /** Resolve the project's scale name to a {@link Scales.ScaleType}, defaulting to Major. */
  private static org.deluge.model.Scales.ScaleType scaleTypeFromName(String scale) {
    if (scale == null) return org.deluge.model.Scales.ScaleType.MAJOR;
    String s = scale.trim();
    // Accept the model's canonical names plus a few common aliases.
    if (s.equalsIgnoreCase("Pentatonic") || s.equalsIgnoreCase("Pentatonic Major")) {
      return org.deluge.model.Scales.ScaleType.MAJOR_PENTATONIC;
    }
    if (s.equalsIgnoreCase("Pentatonic Minor")) {
      return org.deluge.model.Scales.ScaleType.MINOR_PENTATONIC;
    }
    for (org.deluge.model.Scales.ScaleType t : org.deluge.model.Scales.ScaleType.values()) {
      if (t.getName().equalsIgnoreCase(s)) {
        return t;
      }
    }
    return org.deluge.model.Scales.ScaleType.MAJOR;
  }

  public boolean isNoteInScale(int note) {
    if (projectModel == null) return true;
    String scale = projectModel.getScale();
    if (scale == null) return true;
    return org.deluge.model.Scales.isNoteInScale(
        note, getKeyMidiOffset(projectModel.getKey()), scaleTypeFromName(scale));
  }

  public boolean isRootNote(int note) {
    if (projectModel == null) return false;
    int rootOffset = getKeyMidiOffset(projectModel.getKey());
    int diff = note - rootOffset;
    return (diff % 12) == 0;
  }

  private double getMacroValue(int col, org.deluge.model.TrackModel track) {
    if (track == null) return 0.5;
    switch (col) {
      case 0:
        return Math.max(0.0, Math.min(1.0, track.getVolume() / 1.5));
      case 1:
        return track.getPan();
      case 2:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return (st.getTranspose() + 24) / 48.0;
        }
        return 0.5;
      case 3:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return Math.max(
              0.0, Math.min(1.0, Math.log10(st.getLpfFreq() / 20.0) / Math.log10(1000.0)));
        }
        return 0.8;
      case 4:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return st.getLpfRes();
        }
        return 0.0;
      case 5:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return st.getOscMix();
        }
        return 0.5;
      case 6:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return st.getNoiseVol();
        }
        return 0.0;
      case 7:
        if (track instanceof org.deluge.model.SynthTrackModel st && st.getLfo(0) != null) {
          return st.getLfo(0).rateHz() / 20.0f;
        }
        return 0.2;
      case 8:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return st.getModFxDepth();
        }
        return 0.0;
      case 9:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return st.getDelaySend();
        }
        return 0.0;
      case 10:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return st.getReverbSend();
        }
        return 0.0;
      case 11:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return st.getStutterRate();
        }
        return 0.0;
      case 12:
        if (!track.getClips().isEmpty()) {
          org.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              if (clip.getStep(r, s).active()) return clip.getStep(r, s).probability();
            }
          }
        }
        return 1.0;
      case 13:
        if (!track.getClips().isEmpty()) {
          org.deluge.model.ClipModel clip = track.getClips().get(0);
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
          org.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              if (clip.getStep(r, s).active()) return clip.getStep(r, s).velocity();
            }
          }
        }
        return 0.8;
      case 15:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          return Math.max(0.0, Math.min(1.0, st.getArp().rate() / 2.0f));
        }
        return 0.5;
      default:
        return 0.5;
    }
  }

  private void setMacroValue(int col, double v, org.deluge.model.TrackModel track) {
    if (track == null) return;
    switch (col) {
      case 0:
        track.setVolume((float) (v * 1.5));
        break;
      case 1:
        track.setPan((float) v);
        break;
      case 2:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          st.setTranspose((int) (v * 48.0 - 24.0));
        }
        break;
      case 3:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          float freq = (float) (20.0 * Math.pow(1000.0, v));
          st.setLpfFreq(freq);
        }
        break;
      case 4:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          st.setLpfRes((float) v);
        }
        break;
      case 5:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          st.setOscMix((float) v);
        }
        break;
      case 6:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          st.setNoiseVol((float) v);
        }
        break;
      case 7:
        if (track instanceof org.deluge.model.SynthTrackModel st && st.getLfo(0) != null) {
          org.deluge.model.LfoModel oldLfo = st.getLfo(0);
          st.setLfo(
              0,
              new org.deluge.model.LfoModel(
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
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          st.setModFxDepth((float) v);
        }
        break;
      case 9:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          st.setDelaySend((float) v);
        }
        break;
      case 10:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          st.setReverbSend((float) v);
        }
        break;
      case 11:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          st.setStutterRate((float) v);
        }
        break;
      case 12:
        if (!track.getClips().isEmpty()) {
          org.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              org.deluge.model.StepData step = clip.getStep(r, s);
              if (step.active()) {
                clip.setStep(
                    r,
                    s,
                    org.deluge.model.StepData.of(
                        step.active(), step.velocity(), step.gate(), (float) v, step.pitch()));
              }
            }
          }
        }
        break;
      case 13:
        if (!track.getClips().isEmpty()) {
          org.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              org.deluge.model.StepData step = clip.getStep(r, s);
              if (step.active()) {
                clip.setStep(
                    r,
                    s,
                    org.deluge.model.StepData.of(
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
          org.deluge.model.ClipModel clip = track.getClips().get(0);
          for (int r = 0; r < clip.getRowCount(); r++) {
            for (int s = 0; s < clip.getStepCount(); s++) {
              org.deluge.model.StepData step = clip.getStep(r, s);
              if (step.active()) {
                clip.setStep(
                    r,
                    s,
                    org.deluge.model.StepData.of(
                        step.active(), (float) v, step.gate(), step.probability(), step.pitch()));
              }
            }
          }
        }
        break;
      case 15:
        if (track instanceof org.deluge.model.SynthTrackModel st) {
          org.deluge.model.ArpModel oldArp = st.getArp();
          st.setArp(
              new org.deluge.model.ArpModel(
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
      org.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
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
      org.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
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

  // ── Clip note copy/paste (Deluge X_ENC+LEARN copy / SHIFT paste) ──
  // Static so notes copied in one view/track can be pasted into another. StepData is an immutable
  // record, so snapshotting references is a safe deep copy.
  private static org.deluge.model.StepData[][] noteClipboard;

  /** The clip currently being edited (edited track + active clip index), or null. */
  private org.deluge.model.ClipModel getEditedActiveClip() {
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) {
      return null;
    }
    org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
    if (activeClipId < 0 || activeClipId >= t.getClips().size()) {
      return null;
    }
    return t.getClips().get(activeClipId);
  }

  /** Snapshot the edited clip's note grid into the shared clipboard. */
  public boolean copyClipNotes() {
    org.deluge.model.ClipModel clip = getEditedActiveClip();
    if (clip == null) {
      return false;
    }
    int rows = clip.getRowCount();
    int cols = clip.getStepCount();
    org.deluge.model.StepData[][] snap = new org.deluge.model.StepData[rows][cols];
    for (int r = 0; r < rows; r++) {
      for (int c = 0; c < cols; c++) {
        snap[r][c] = clip.getStep(r, c);
      }
    }
    noteClipboard = snap;
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("COPY", rows + "x" + cols);
    }
    return true;
  }

  /** Paste the clipboard into the edited clip (model + per-cell engine sync), then refresh. */
  public boolean pasteClipNotes() {
    org.deluge.model.StepData[][] clip = noteClipboard;
    org.deluge.model.ClipModel target = getEditedActiveClip();
    if (clip == null || target == null) {
      return false;
    }
    for (int r = 0; r < clip.length; r++) {
      org.deluge.model.StepData[] rowArr = clip[r];
      if (rowArr == null) {
        continue;
      }
      for (int c = 0; c < rowArr.length; c++) {
        org.deluge.model.StepData sd = rowArr[c];
        if (sd == null) {
          continue;
        }
        target.setStep(r, c, sd);
        if (bridge != null) {
          int engineRow = baseTrackId + r;
          bridge.setStep(engineRow, c, sd.active());
          bridge.setVelocity(engineRow, c, sd.velocity());
          bridge.setGate(engineRow, c, sd.gate());
          bridge.setStepProbability(engineRow, c, sd.probability());
          bridge.setStepFill(engineRow, c, sd.fill());
        }
      }
    }
    if (SwingDelugeApp.mainInstance != null) {
      SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient("PASTE", "OK");
    }
    fireProjectChanged();
    refresh();
    return true;
  }

  /**
   * Convert the track at {@code trackIdx} to a MIDI track (Deluge MIDI button), preserving its
   * clips. No-op if it is not currently a Synth track. Returns true if a conversion happened.
   */
  public boolean convertTrackToMidi(int trackIdx) {
    if (projectModel == null || trackIdx < 0 || trackIdx >= projectModel.getTracks().size()) {
      return false;
    }
    if (!(projectModel.getTracks().get(trackIdx)
        instanceof org.deluge.model.SynthTrackModel synthTrack)) {
      return false;
    }
    org.deluge.model.MidiTrackModel midiTrk =
        new org.deluge.model.MidiTrackModel(synthTrack.getName());
    midiTrk.setColourHex("0x0000FF");
    for (org.deluge.model.ClipModel cm : synthTrack.getClips()) {
      midiTrk.addClip(cm);
    }
    projectModel.getTracks().set(trackIdx, midiTrk);
    trackColors[trackIdx % trackColors.length] = new Color(0x33, 0x33, 0xff);
    fireProjectChanged();
    return true;
  }

  /**
   * Convert the track at {@code trackIdx} to a Synth track (Deluge SYNTH button), preserving its
   * clips. No-op if it is not currently a MIDI track. Returns true if a conversion happened.
   */
  public boolean convertTrackToSynth(int trackIdx) {
    if (projectModel == null || trackIdx < 0 || trackIdx >= projectModel.getTracks().size()) {
      return false;
    }
    if (!(projectModel.getTracks().get(trackIdx)
        instanceof org.deluge.model.MidiTrackModel midiTrack)) {
      return false;
    }
    org.deluge.model.SynthTrackModel synthTrk =
        new org.deluge.model.SynthTrackModel(midiTrack.getName());
    synthTrk.setColourHex("0x00FF00");
    for (org.deluge.model.ClipModel cm : midiTrack.getClips()) {
      synthTrk.addClip(cm);
    }
    projectModel.getTracks().set(trackIdx, synthTrk);
    trackColors[trackIdx % trackColors.length] = new Color(0x33, 0xff, 0x33);
    fireProjectChanged();
    return true;
  }

  public void scrollHorizontally(int cellsOffset) {
    System.out.println(
        "[TRACE grid] scrollHorizontally called: offset=" + cellsOffset + " viewMode=" + viewMode);
    if (bridge == null) {
      System.out.println("[TRACE grid] scrollHorizontally ignored: bridge is null!");
      return;
    }
    int trackLenH = (bridge != null) ? bridge.getTrackLength(baseTrackId) : stepCount;
    System.out.println(
        "[TRACE grid] scrollHorizontally: trackLenH="
            + trackLenH
            + " stepCount="
            + stepCount
            + " currentOffsetX="
            + scrollOffsetX);
    if (trackLenH > stepCount) {
      int maxOffX = trackLenH - stepCount;
      int newOffset = scrollOffsetX + cellsOffset;
      if (newOffset > maxOffX) newOffset = maxOffX;
      if (newOffset < 0) newOffset = 0;
      System.out.println(
          "[TRACE grid] scrollHorizontally newOffset=" + newOffset + " maxOffX=" + maxOffX);
      if (newOffset != scrollOffsetX) {
        scrollOffsetX = newOffset;
        System.out.println(
            "[TRACE grid] scrollHorizontally executing refresh at offset=" + scrollOffsetX);
        refresh();
      }
    } else {
      System.out.println(
          "[TRACE grid] scrollHorizontally ignored: trackLenH <= stepCount (no steps to scroll!)");
    }
  }

  /**
   * Adjust the play rate step speed resolution (horizontal zoom) of the active clip. Clockwise
   * rotation (positive delta) zooms in (shows shorter step values, e.g. 1/16 -> 1/32).
   * Counter-clockwise rotation (negative delta) zooms out (shows longer step values, e.g. 1/16 ->
   * 1/8). Matches the Synthstrom Deluge hardware Shift + X_ENC zoom gesture.
   */
  public void adjustZoomResolution(int delta) {
    if (bridge == null) return;
    double currentRes = bridge.getStepResolution();
    org.deluge.model.TrackModel curTrack = null;
    if (projectModel != null
        && editedModelTrack >= 0
        && editedModelTrack < projectModel.getTracks().size()) {
      curTrack = projectModel.getTracks().get(editedModelTrack);
    }
    org.deluge.model.ClipModel activeClip = null;
    if (curTrack != null && activeClipId >= 0 && activeClipId < curTrack.getClips().size()) {
      activeClip = curTrack.getClips().get(activeClipId);
    }
    boolean activeTrip = activeClip != null && activeClip.isTripletMode();
    double[] rateValues;
    String[] rateLabels;
    if (activeTrip) {
      rateLabels =
          new String[] {"1 Bar", "1/2T", "1/4T", "1/8T", "1/16T", "1/32T", "1/64T", "1/128T"};
      rateValues =
          new double[] {
            4.0, 4.0 / 3.0, 2.0 / 3.0, 1.0 / 3.0, 0.5 / 3.0, 0.25 / 3.0, 0.125 / 3.0, 0.0625 / 3.0
          };
    } else {
      rateLabels = new String[] {"1 Bar", "1/2", "1/4", "1/8", "1/16", "1/32", "1/64", "1/128"};
      rateValues = new double[] {4.0, 2.0, 1.0, 0.5, 0.25, 0.125, 0.0625, 0.03125};
    }
    int currentRateIdx = activeTrip ? 3 : 4;
    for (int i = 0; i < rateValues.length; i++) {
      if (Math.abs(rateValues[i] - currentRes) < 0.0001) {
        currentRateIdx = i;
        break;
      }
    }
    int newIdx = currentRateIdx + delta;
    if (newIdx < 0) newIdx = 0;
    if (newIdx >= rateValues.length) newIdx = rateValues.length - 1;
    if (newIdx != currentRateIdx) {
      double newVal = rateValues[newIdx];
      bridge.setStepResolution(newVal);
      if (SwingDelugeApp.mainInstance != null) {
        SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
            "RATE", rateLabels[newIdx] + " ");
      }
      refresh();
    }
  }

  public int getScrollOffsetX() {
    return scrollOffsetX;
  }

  public int getStepCount() {
    return stepCount;
  }

  public void scrollVertically(int cellsOffset) {
    System.out.println(
        "[TRACE grid] scrollVertically called: offset=" + cellsOffset + " viewMode=" + viewMode);
    if (bridge == null) {
      System.out.println("[TRACE grid] scrollVertically ignored: bridge is null!");
      return;
    }
    int maxOffset = Math.max(0, voiceRowCount - gridMode.rows);
    int newOffset = scrollOffset + cellsOffset;
    System.out.println(
        "[TRACE grid] scrollVertically: voiceRowCount="
            + voiceRowCount
            + " rowsInView="
            + gridMode.rows
            + " currentOffset="
            + scrollOffset
            + " maxOffset="
            + maxOffset);
    if (newOffset > maxOffset) newOffset = maxOffset;
    if (newOffset < 0) newOffset = 0;
    System.out.println("[TRACE grid] scrollVertically newOffset=" + newOffset);
    if (newOffset != scrollOffset) {
      scrollOffset = newOffset;
      System.out.println(
          "[TRACE grid] scrollVertically executing refresh at offset=" + scrollOffset);
      refresh();
    } else {
      System.out.println(
          "[TRACE grid] scrollVertically ignored: no offset change or scroll locked!");
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

  private org.deluge.model.ArrangerClip getArrangerClipAt(int trackIndex, int col) {
    if (projectModel == null) return null;
    int queryTicks = col * 96;
    for (org.deluge.model.ArrangerClip placement : projectModel.getArrangerTimeline()) {
      if (placement.trackIndex() == trackIndex) {
        if (queryTicks >= placement.startTicks()
            && queryTicks < placement.startTicks() + placement.durationTicks()) {
          return placement;
        }
      }
    }
    return null;
  }

  private void showArrangerClipSelectionPopup(
      Component invoker, final int trackIdx, final int col) {
    if (projectModel == null || trackIdx >= projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel track = projectModel.getTracks().get(trackIdx);

    JPopupMenu menu = new JPopupMenu();
    menu.setBackground(new Color(0x1e, 0x1e, 0x22));
    menu.setBorder(BorderFactory.createLineBorder(new Color(0x3e, 0x3e, 0x42), 1));

    JMenuItem createNew = new JMenuItem("Create New Pattern Clip (1 bar)");
    createNew.setForeground(new Color(0x00, 0xff, 0xcc));
    createNew.setBackground(new Color(0x1e, 0x1e, 0x22));
    createNew.addActionListener(
        e -> {
          int clipCount = track.getClips().size();
          org.deluge.model.ClipModel newClip =
              new org.deluge.model.ClipModel("CLIP " + (clipCount + 1), 8, 16);
          track.addClip(newClip);
          projectModel.addArrangerClip(
              new org.deluge.model.ArrangerClip(trackIdx, newClip, col * 96, 96));
          fireProjectChanged();
          refresh();
        });
    menu.add(createNew);

    if (!track.getClips().isEmpty()) {
      menu.addSeparator();
      for (int i = 0; i < track.getClips().size(); i++) {
        final org.deluge.model.ClipModel clip = track.getClips().get(i);
        String name =
            clip.getName() != null && !clip.getName().isBlank()
                ? clip.getName()
                : "Pattern Clip " + (i + 1);
        JMenuItem item = new JMenuItem("Place: " + name);
        item.setForeground(Color.WHITE);
        item.setBackground(new Color(0x1e, 0x1e, 0x22));
        item.addActionListener(
            e -> {
              projectModel.addArrangerClip(
                  new org.deluge.model.ArrangerClip(trackIdx, clip, col * 96, 96));
              fireProjectChanged();
              refresh();
            });
        menu.add(item);
      }
    }

    menu.show(invoker, 0, invoker.getHeight());
  }

  private void showCreateTrackMenu(
      java.awt.Component src, int x, int y, final int row, final int col) {
    JPopupMenu menu = new JPopupMenu();
    menu.setBackground(new Color(0x1e, 0x1e, 0x22));
    menu.setBorder(BorderFactory.createLineBorder(new Color(0x3e, 0x3e, 0x42), 1));

    JMenuItem createSynth = new JMenuItem("Create SYNTH Track");
    createSynth.setForeground(Color.WHITE);
    createSynth.setBackground(new Color(0x1e, 0x1e, 0x22));
    createSynth.addActionListener(
        ev -> {
          createTrackAndNavigate(row, col, "SYNTH");
        });

    JMenuItem createKit = new JMenuItem("Create KIT Track");
    createKit.setForeground(Color.WHITE);
    createKit.setBackground(new Color(0x1e, 0x1e, 0x22));
    createKit.addActionListener(
        ev -> {
          createTrackAndNavigate(row, col, "KIT");
        });

    JMenuItem createAudio = new JMenuItem("Create AUDIO Track");
    createAudio.setForeground(Color.WHITE);
    createAudio.setBackground(new Color(0x1e, 0x1e, 0x22));
    createAudio.addActionListener(
        ev -> {
          createTrackAndNavigate(row, col, "AUDIO");
        });

    menu.add(createSynth);
    menu.add(createKit);
    menu.add(createAudio);
    menu.show(src, x, y);
  }

  private void createTrackAndNavigate(int row, int col, String type) {
    if (projectModel == null) return;
    String name =
        JOptionPane.showInputDialog(
            this, type + " track name:", type + " " + (projectModel.getTracks().size() + 1));
    if (name == null || name.isBlank()) return;

    org.deluge.model.TrackModel newTrack = null;
    org.deluge.model.ClipModel newClip = new org.deluge.model.ClipModel("CLIP " + (col + 1), 8, 16);

    switch (type) {
      case "SYNTH" -> {
        org.deluge.model.SynthTrackModel synth = new org.deluge.model.SynthTrackModel(name);
        synth.addClip(newClip);
        newTrack = synth;
      }
      case "KIT" -> {
        org.deluge.model.KitTrackModel kit = new org.deluge.model.KitTrackModel(name);
        kit.addClip(newClip);
        newTrack = kit;
      }
      case "AUDIO" -> {
        org.deluge.model.AudioTrackModel audio = new org.deluge.model.AudioTrackModel(name);
        newClip = new org.deluge.model.ClipModel("CLIP " + (col + 1), 1, 16);
        audio.addClip(newClip);
        newTrack = audio;
      }
    }

    if (newTrack != null) {
      projectModel.addTrack(newTrack);
      int trackIdx = projectModel.getTracks().size() - 1;

      if (projectModel.getUndoRedoStack() != null) {
        projectModel
            .getUndoRedoStack()
            .push(
                new Consequence.TrackStructureConsequence(
                    Consequence.TrackStructureConsequence.ADD,
                    trackIdx,
                    newTrack,
                    "Add " + type.toLowerCase() + " track"));
      }

      fireProjectChanged();
      refresh();

      if (onEditRequest != null) {
        onEditRequest.accept(trackIdx, 0);
      }
    }
  }

  private void styleSystemButton(JButton btn, Color bg, Color fg, int fontSize) {
    btn.setContentAreaFilled(false);
    btn.setOpaque(true);
    btn.setFocusPainted(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, fontSize));
    btn.setBorder(BorderFactory.createLineBorder(bg.brighter(), 1));
  }

  private void updateScrollBarTooltip() {
    if (vertScrollBar != null && viewMode == GridViewMode.CLIP) {
      try {
        int lowestModelRow = scrollOffset + gridMode.rows - 1;
        int highestModelRow = scrollOffset;

        lowestModelRow = Math.max(0, Math.min(lowestModelRow, voiceRowCount - 1));
        highestModelRow = Math.max(0, Math.min(highestModelRow, voiceRowCount - 1));

        int lowPitch = getDiatonicPitch(lowestModelRow);
        String lowNote = getNoteName(lowPitch);

        int highPitch = getDiatonicPitch(highestModelRow);
        String highNote = getNoteName(highPitch);

        vertScrollBar.setToolTipText(
            "Scroll Pitches (Showing: " + lowNote + " to " + highNote + ")");
      } catch (Throwable t) {
        vertScrollBar.setToolTipText("Scroll Pitches");
      }
    } else if (vertScrollBar != null) {
      vertScrollBar.setToolTipText("Scroll Pitches");
    }
  }
}
