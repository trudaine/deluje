package org.deluge.ui;

import java.awt.*;
import java.util.logging.Logger;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.Consequence;
import org.deluge.model.KeyplayKeyboard;
import org.deluge.model.ScaleMapper;
import org.deluge.model.SongSection;

/** Unified 18x8 Grid Panel handling both sequence matrix and clip launch arrangements. */
public class SwingGridPanel extends JPanel implements GridScrollController.GridContext {
  private static final Logger LOG = Logger.getLogger(SwingGridPanel.class.getName());
  private final BridgeContract bridge;

  private org.deluge.model.ProjectModel projectModel;
  private java.util.function.BiConsumer<Integer, Integer> onEditRequest;
  private static final int MAX_GRID_ROWS = 128;
  private static final int MAX_GRID_COLS = 26; // max columns: 24 steps + MUTE + SOLO
  private JButton[][] pads = new JButton[MAX_GRID_ROWS][MAX_GRID_COLS];
  private org.rtmidijava.RtMidiOut finalMidiOut;
  // VU meters and animation decay are now managed by GridVuManager
  private final GridVuManager vuManager = new GridVuManager();
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
  private final java.util.List<JButton> pageButtons = new java.util.ArrayList<>();
  // voiceVuMeters, trackVuMeters, and globalVuTimer moved to GridVuManager
  private int scrollOffset = 67; // vertical scroll offset for voice rows in CLIP mode (C4 at top)
  private int scrollOffsetX = 0; // horizontal scroll offset for step columns in CLIP mode
  private boolean playheadFollowMode = true;
  private JScrollBar vertScrollBar;
  private JScrollBar horizScrollBar;
  private final GridScrollController scrollController;
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
  private boolean scaleModeEnabled =
      true; // default to true (diatonic scale mode) like the real Deluge!
  private final java.util.List<Integer> foldedPitches = new java.util.ArrayList<>();

  public boolean isScaleModeEnabled() {
    return scaleModeEnabled;
  }

  public void setScaleModeEnabled(boolean enabled) {
    this.scaleModeEnabled = enabled;
    refresh();
  }

  public int getRowPitch(int modelRow) {
    boolean isPitched = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      // MIDI_OUT (and CV) clips are pitched piano-rolls too (InstrumentClip in the C), not a single
      // fixed pitch — previously only synth got real per-row pitches, so MIDI rows all mapped to
      // C4 (and thus rendered a single colour despite the per-pitch colouring fix).
      isPitched =
          t instanceof org.deluge.model.SynthTrackModel
              || t instanceof org.deluge.model.MidiTrackModel;
    }
    if (isPitched && viewMode == GridViewMode.CLIP) {
      return ScaleMapper.getRowPitch(
          modelRow,
          true,
          scaleModeEnabled,
          foldMode,
          foldedPitches,
          songScaleIntervals(),
          songRootPitchClass());
    }
    return 60; // fallback
  }

  /** The active song scale's semitone intervals (defaults to major). */
  private int[] songScaleIntervals() {
    String scale = projectModel != null ? projectModel.getScale() : null;
    return ScaleMapper.scaleTypeFromName(scale).getIntervals();
  }

  /** The active song key's root pitch class (0=C..11=B), from the numeric rootNote. */
  private int songRootPitchClass() {
    if (projectModel == null) return 0;
    try {
      return Math.floorMod(Integer.parseInt(projectModel.getKey().trim()), 12);
    } catch (NumberFormatException | NullPointerException e) {
      return 0;
    }
  }

  private Color getAuditionPadBgColor(int modelRow, boolean isSynth, int visualRowIndex) {
    if (soloRow == visualRowIndex) {
      return Color.GREEN;
    }
    if (!isSynth) {
      return new Color(0x55, 0x55, 0x5a); // Slate Grey for kits/midi
    }
    int pitch = getRowPitch(modelRow);
    if (pitch % 12 == 0) {
      return getGridNoteColor(modelRow); // Dynamic root octave Cs use the row's hue-shifted color!
    } else {
      return scaleModeEnabled
          ? new Color(0x55, 0x55, 0x5a)
          : Color.BLACK; // Slate Grey in diatonic, Black in chromatic!
    }
  }

  private Color getAuditionPadFgColor(int modelRow, boolean isSynth, int visualRowIndex) {
    if (soloRow == visualRowIndex) {
      return Color.BLACK;
    }
    if (!isSynth) {
      return Color.WHITE;
    }
    int pitch = getRowPitch(modelRow);
    if (pitch % 12 == 0) {
      // Lit root octave pads: use high contrast text based on background brightness
      Color bg = getGridNoteColor(modelRow);
      double brightness =
          (0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue()) / 255.0;
      return brightness > 0.5 ? Color.BLACK : Color.WHITE;
    } else {
      // Non-octave pads
      return scaleModeEnabled ? Color.WHITE : new Color(0x77, 0x77, 0x7a);
    }
  }

  public int getRowFromPitch(int pitch) {
    boolean isPitched = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      // Mirror getRowPitch: MIDI (and CV) are pitched piano-rolls, so the inverse mapping must use
      // the same path, otherwise MIDI note lookups (placement/hit-testing) desync from the display.
      isPitched =
          t instanceof org.deluge.model.SynthTrackModel
              || t instanceof org.deluge.model.MidiTrackModel;
    }
    if (isPitched && viewMode == GridViewMode.CLIP) {
      return ScaleMapper.getRowFromPitch(pitch, true, scaleModeEnabled, foldMode, foldedPitches);
    }
    return 127 - pitch;
  }

  public int getClipRowIndex(
      org.deluge.model.ClipModel cModel, int modelRow, boolean createIfMissing) {
    boolean isSynth = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      isSynth = t instanceof org.deluge.model.SynthTrackModel;
    }
    if (!isSynth) {
      if (modelRow >= cModel.getRowCount()) {
        if (createIfMissing) {
          cModel.setRowCount(modelRow + 1);
          cModel.setRowYNote(modelRow, modelRow);
          return modelRow;
        }
        return -1;
      }
      if (cModel.getRowYNote(modelRow) != modelRow) {
        cModel.setRowYNote(modelRow, modelRow);
      }
      return modelRow;
    }

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

  public int getActiveClipId() {
    return activeClipId;
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

  public void updateFoldedPitches() {
    foldedPitches.clear();
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
    if (!(t instanceof org.deluge.model.SynthTrackModel synthTrack)) return;
    if (activeClipId >= 0 && activeClipId < synthTrack.getClips().size()) {
      org.deluge.model.ClipModel clip = synthTrack.getClips().get(activeClipId);
      foldedPitches.addAll(ScaleMapper.calculateFoldedPitches(clip));
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

  // Automation is now managed by AutomationEditorController
  private final AutomationEditorController automationController =
      new AutomationEditorController(this, this::refresh);
  private javax.swing.JComboBox<String> automationParamCombo;

  // Arranger Timeline interaction is now managed by ArrangerTimelineController
  private final ArrangerTimelineController arrangerController =
      new ArrangerTimelineController(this, this::fireProjectChanged, this::refresh);

  private boolean shiftHeld = false;
  private boolean tabHeld = false;

  // Clip Editor is now managed by ClipEditorController
  private final ClipEditorController clipController =
      new ClipEditorController(this, this::refresh, this::fireProjectChanged);

  public void setTabHeld(boolean held) {
    this.tabHeld = held;
  }

  public boolean isTabHeld() {
    return tabHeld;
  }

  // activeShiftParam and clonePreview states are now managed by ClipEditorController

  // Multi-cell step selection state variables
  private final java.util.Set<String> selectedCells = new java.util.HashSet<>();
  private int dragSelStartRow = -1;
  private int dragSelStartCol = -1;
  private int dragSelCurrRow = -1;
  private int dragSelCurrCol = -1;
  private boolean isDragSelecting = false;
  private JPanel voicePanel;

  public int getActiveShiftRow() {
    return clipController.getActiveShiftRow();
  }

  public int getActiveShiftCol() {
    return clipController.getActiveShiftCol();
  }

  public String getActiveShiftParam() {
    return clipController.getActiveShiftParam();
  }

  public void setShiftHeld(boolean held) {
    if (this.shiftHeld != held) {
      this.shiftHeld = held;
      if (!held) {
        clipController.resetActiveShiftParam();
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

  static final String[][] SHIFT_LABELS = {
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

  static final Color[][] SHIFT_COLORS = {
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

  // gestureCoordinator is now managed by ClipEditorController

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
  int currentLabelWidth() {
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
    this.scrollController = new GridScrollController(this, this::refresh);

    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

    addMouseWheelListener(
        e -> {
          if (shiftHeld && clipController.getActiveShiftParam() != null) {
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

          scrollController.handleMouseWheel(e);
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
    // gestureCoordinator is now initialized inside ClipEditorController
  }

  @Override
  public void removeNotify() {
    if (playheadTimer != null) {
      playheadTimer.stop();
    }
    if (activeStutterTimer != null) {
      activeStutterTimer.stop();
    }
    vuManager.shutdown();
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

  public void fireProjectChanged() {
    if (onProjectChanged != null) onProjectChanged.run();
    refresh();
  }

  /** Blend a base color with black proportionally to velocity (0.0 = black, 1.0 = full color). */
  static Color velocityBlend(Color base, double velocity) {
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

  Color getStepPadDefaultBg(int modelRow, int col) {
    boolean isSynth = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      isSynth = t instanceof org.deluge.model.SynthTrackModel;
    }
    if (isSynth && viewMode == GridViewMode.CLIP && !scaleModeEnabled) {
      int pitch = getRowPitch(modelRow);
      if (ScaleMapper.isAccidental(pitch)) {
        return Color.BLACK; // Accidental rows are completely black/unlit!
      }
    }
    return getPadDefaultBg(col);
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
          return Color.BLACK; // Out-of-scale notes are completely black/unlit on hardware!
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
    return automationController.isOverviewMode();
  }

  public void setAutoOverviewMode(boolean overview) {
    automationController.setOverviewMode(overview);
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

  /**
   * The Deluge session/song colour of a track row (session_view.cpp:3356): fromHue of the output's
   * stored colour (0-191), auto-assigning a rotating hue when that's 0 (unset). A literal 0xRRGGBB
   * hex is honoured directly. Computed at pad-build time so the pads actually get the right colour
   * (the old trackColors[] path was only updated during paintComponent, after the pads were built).
   */
  private Color getTrackColour(int modelRow) {
    java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();
    if (modelRow >= tracks.size()) {
      return trackColors[modelRow % trackColors.length];
    }
    org.deluge.model.TrackModel t = tracks.get(modelRow);
    // The Deluge colours each session-clip pad from the CLIP's colourOffset:
    // fromHue(colourOffset * -8/3) (instrument_clip.cpp:1235). This is the real pad hue seen on
    // hardware (e.g. TR-808 offset -60 -> purple), so prefer it over the instrument colour.
    if (!t.getClips().isEmpty()) {
      return DelugeColour.clipColour(t.getColourOffset());
    }
    String hex = t.getColourHex();
    if (hex != null && hex.startsWith("0x")) {
      try {
        return new Color(Integer.decode(hex.substring(0, 8)));
      } catch (NumberFormatException ignore) {
        // fall through to auto-assign
      }
    }
    int stored = 0;
    try {
      if (hex != null && !hex.isBlank()) stored = Integer.parseInt(hex.trim());
    } catch (NumberFormatException ignore) {
      // not numeric -> auto-assign
    }
    return DelugeColour.sessionColour(stored, modelRow);
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
    scrollController.resetScrollOffset();
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
                      projectModel,
                      editedModelTrack,
                      activeClipId,
                      row,
                      col,
                      oldStep,
                      cModel.getStep(row, col)));
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
    scrollController.scrollBy(delta);
  }

  /** Scroll by one full page (gridMode.rows rows). */
  public void scrollPage(int direction) {
    scrollController.scrollPage(direction);
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

  // VUMeterPanel moved to its own class file VUMeterPanel.java.

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
            ? (127 - getRowPitch(scrollOffset + visibleRow))
            : getModelRow(visibleRow);
    boolean isSongOrArr = (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT);
    // With the bottom-up SONG/ARR ordering, display rows above the tracks map to negative model
    // rows; treat those (and rows past the track count) as empty slots.
    boolean isUnusedTrackRow = isSongOrArr && (modelRow < 0 || modelRow >= tracks.size());
    String samplePathLoc = null;
    if (modelRow < tracks.size()) {
      org.deluge.model.TrackModel track = tracks.get(modelRow);
      if (viewMode == GridViewMode.CLIP && track instanceof org.deluge.model.KitTrackModel kit) {
        java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
        int drumIdx = modelRow;
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
      // Colour the row the way the Deluge session view does (session_view.cpp:3356): fromHue of the
      // output's stored colour (0-191), and when that's 0 (unset) a rotating auto-assigned hue so
      // each track is distinct. A literal 0xRRGGBB hex (if ever stored) is still honoured directly.
      org.deluge.model.TrackModel t = tracks.get(modelRow);
      String hex = t.getColourHex();
      if (hex != null && hex.startsWith("0x")) {
        try {
          trackColors[modelRow % trackColors.length] =
              new Color(Integer.decode(hex.substring(0, 8)));
        } catch (Exception e) {
          LOG.warning("Bad color hex for track " + modelRow + ": " + e.getMessage());
        }
      } else {
        int stored = 0;
        try {
          if (hex != null && !hex.isBlank()) stored = Integer.parseInt(hex.trim());
        } catch (NumberFormatException ignore) {
          // not numeric — leave as auto-assign
        }
        trackColors[modelRow % trackColors.length] = DelugeColour.sessionColour(stored, modelRow);
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
            (modelRow < sounds.size() && modelRow >= 0)
                ? sounds.get(modelRow).getName()
                : rowTrack.getName();
      } else {
        int pitchMidi = getRowPitch(modelRow);
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
        int soundIndex = modelRow;
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

    vuManager.registerVoiceVu(modelRow, vu);

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
        clipBtn = new CleanJButton();
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
                ? (isEditedTrackKit() ? (baseTrackId + modelRow) : editedModelTrack)
                : (baseTrackId + modelRow);
        final int engineRow = baseTrackId + modelRow;
        boolean isMuted = bridge != null && bridge.getMute(trackToMute);

        Color muteBg;
        Color muteFg;
        if (viewMode == GridViewMode.CLIP && isEditedTrackKit()) {
          Color rowColor = getGridNoteColor(modelRow);
          muteBg = isMuted ? new Color(0x33, 0x11, 0x11) : rowColor;
          muteFg = isMuted ? Color.LIGHT_GRAY : Color.BLACK;
        } else {
          muteBg = isMuted ? new Color(0xff, 0xd7, 0x00) : Color.WHITE;
          muteFg = Color.BLACK;
        }

        clipBtn.setText(isMuted ? "UNMUTE" : "MUTE");
        clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
        clipBtn.setBackground(muteBg);
        clipBtn.setForeground(muteFg);

        if (clipBtn instanceof DelugePadButton pad) {
          pad.setBaseColor(muteBg);
          pad.setTextColorOverride(muteFg);
          pad.setDrawCenterCircle(false);
          pad.setIntensity(isMuted ? 0.4f : 1.0f);
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
          boolean isOctaveC = isSynth && (getRowPitch(modelRow) % 12 == 0);

          String nName;
          if (isSynth) {
            int midiPitch = getRowPitch(modelRow);
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

          Color cellBg = getAuditionPadBgColor(modelRow, isSynth, visibleRow);
          Color cellFg = getAuditionPadFgColor(modelRow, isSynth, visibleRow);
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
                  int pitchMidi = isSynthMode ? getRowPitch(modelRow) : 60;

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
                  int pitchMidi = isSynthMode ? getRowPitch(modelRow) : 60;

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
              applicable = clipController.isParamApplicable(param, visibleRow, colId, genericTrack);
            }
            pad.setApplicable(applicable);
            String tooltipText =
                clipController.getShiftShortcutTooltip(visibleRow, colId, applicable, genericTrack);
            pad.setToolTipText(tooltipText);
            pad.setInLoop(true);
            pad.setActive(true);
            pad.setBaseColor(SHIFT_COLORS[visibleRow][colId]);
            String prefix = clipController.getGroupPrefix(colId);
            String shortcutLabel = SHIFT_LABELS[visibleRow][colId];
            String noteText =
                (prefix != null && !shortcutLabel.isEmpty())
                    ? prefix + "\n" + shortcutLabel.replace(" ", "\n")
                    : shortcutLabel.replace(" ", "\n");
            pad.setNoteText(noteText);

            pad.setMuted(false);
            pad.setIntensity(1.0f);
            pad.setPlayhead(false);
            pad.setTail(false);
            pad.setText("");
            if (applicable
                && visibleRow == clipController.getActiveShiftRow()
                && colId == clipController.getActiveShiftCol()) {
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
              Color padColor = getGridNoteColor(modelRow);
              boolean isNudged = false;
              if (stepState) {
                org.deluge.model.ClipModel cModel = null;
                if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                  org.deluge.model.TrackModel tModel =
                      projectModel.getTracks().get(editedModelTrack);
                  if (activeClipId < tModel.getClips().size()) {
                    cModel = tModel.getClips().get(activeClipId);
                  }
                }
                if (cModel != null) {
                  org.deluge.model.StepData sd = getClipStep(cModel, modelRow, activeCol);
                  if (sd != null && sd.active()) {
                    if (sd.fill() > 0.0f) {
                      padColor = new Color(0x00, 0xd2, 0xff);
                    } else if (sd.nudge() > 0.0f) {
                      isNudged = true;
                    }
                  }
                } else if (bridge != null) {
                  if (bridge.getStepFill(engineRow, activeCol) > 0.0) {
                    isNudged = true;
                  }
                }
              }
              pad.setBaseColor(padColor);
              pad.setBlur(isNudged);
              pad.setIntensity((float) (vel * (0.2f + 0.8f * prob)));
              pad.setTail(isStepTied(modelRow, activeCol) && !stepState);

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
              int pitchMidi = isSynthMode ? getRowPitch(modelRow) : 60;
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
              pad.setBaseColor(getTrackColour(modelRow));
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

            if (visibleRow == clipController.getClonePreviewCurrentRow()
                && colId == clipController.getClonePreviewCurrentCol()) {
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
              applicable = clipController.isParamApplicable(param, visibleRow, colId, genericTrack);
            }
            String prefix = clipController.getGroupPrefix(colId);
            String shortcutLabel = SHIFT_LABELS[visibleRow][colId];
            String text =
                (prefix != null && !shortcutLabel.isEmpty())
                    ? prefix + "<br>" + shortcutLabel.replace(" ", "<br>")
                    : shortcutLabel.replace(" ", "<br>");
            String tooltipText =
                clipController.getShiftShortcutTooltip(visibleRow, colId, applicable, genericTrack);
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
                      ? getGridNoteColor(modelRow, (float) vel)
                      : getStepPadDefaultBg(modelRow, activeCol));
              boolean isSynthMode = bridge != null && bridge.getTrackType(baseTrackId) == 1;
              int pitchMidi = isSynthMode ? getRowPitch(modelRow) : 60;
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
                clipBtn.setBackground(getTrackColour(modelRow));
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
              if (isStepColumn(colId)) {
                final int vr = visibleRow;
                final int vc = colId;
                clipBtn.addMouseWheelListener(
                    new java.awt.event.MouseWheelListener() {
                      @Override
                      public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                        handlePadMouseWheel(vr, vc, e);
                      }
                    });
              }
              clipController.attachListeners(clipBtn, modelRow, visibleRow, colId);
              break;
            case SONG:
              break;
            case ARRANGEMENT:
              clipBtn.addMouseListener(
                  new java.awt.event.MouseAdapter() {
                    @Override
                    public void mousePressed(java.awt.event.MouseEvent e) {
                      if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                        JPopupMenu menu = new JPopupMenu();
                        menu.setBackground(new Color(0x1e, 0x1e, 0x22));
                        menu.setBorder(BorderFactory.createLineBorder(new Color(0x3e, 0x3e, 0x42), 1));
                        JMenuItem editAuto = new JMenuItem("Edit Bar Automation...");
                        editAuto.setForeground(Color.WHITE);
                        editAuto.setBackground(new Color(0x1e, 0x1e, 0x22));
                        editAuto.addActionListener(ev -> {
                          new BarAutomationDialog(
                                  (Frame)
                                      javax.swing.SwingUtilities.getWindowAncestor(
                                          SwingGridPanel.this),
                                  colId)
                              .setVisible(true);
                        });
                        menu.add(editAuto);
                        menu.show(clipBtn, e.getX(), e.getY());
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
                    } else {
                      JPopupMenu emptyClipMenu = new JPopupMenu();
                      emptyClipMenu.setBackground(new Color(0x1e, 0x1e, 0x22));
                      emptyClipMenu.setBorder(BorderFactory.createLineBorder(new Color(0x3e, 0x3e, 0x42), 1));
                      JMenuItem createItem = new JMenuItem("Create New Clip Pattern");
                      createItem.setForeground(Color.WHITE);
                      createItem.setBackground(new Color(0x1e, 0x1e, 0x22));
                      createItem.addActionListener(ev -> {
                        String name = "CLIP " + (clipCol + 1);
                        songTrack.addClip(
                            new org.deluge.model.ClipModel(
                                name,
                                songTrack.getClips().isEmpty()
                                    ? 8
                                    : songTrack.getClips().get(0).getRowCount(),
                                16));
                        fireProjectChanged();
                      });
                      emptyClipMenu.add(createItem);
                      emptyClipMenu.show(clipBtn, e.getX(), e.getY());
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
                  clipBtn.setBackground(getTrackColour(modelRow));
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
            clipBtn = new CleanJButton();
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

            clipBtn = new CleanJButton(getNoteName(note));
            clipBtn.setBackground(isBlack ? new Color(0x33, 0x33, 0x33) : Color.WHITE);
            clipBtn.setForeground(isBlack ? Color.WHITE : Color.BLACK);
            clipBtn.setFont(
                new Font("SansSerif", Font.BOLD, rowHeight < 35 ? 9 : (padSz > 70 ? 14 : 10)));

            clipBtn.addMouseListener(new KeyboardMouseAdapter(this, note));
          } else {
            clipBtn = new CleanJButton();
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
      modelRow = getRowFromPitch(note);
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
                      : getStepPadDefaultBg(
                          viewMode == GridViewMode.CLIP ? scrollOffset + t : t, c + scrollOffsetX));
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
            () -> scrollController.scrollHorizontallyToPage(fTargetPage));
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
                      : getStepPadDefaultBg(
                          viewMode == GridViewMode.CLIP ? scrollOffset + t : t, c + scrollOffsetX));
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
  boolean isSequencerPlaying() {
    return bridge != null && bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L;
  }

  /**
   * Sends a MIDI note-on (channel 1) and schedules the matching note-off after {@code gateMs}. The
   * grid previews and the playhead used to emit note-ons with no note-offs, so any connected MIDI
   * device accumulated stuck notes. Pairing every on with a guaranteed off prevents that.
   */
  void sendMidiNote(int note, int velocity, int gateMs) {
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

  void stopAuditionIfNeeded() {
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

    // Reset or clamp scrollOffset based on track type and view mode
    if (viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION) {
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
        boolean isPianoRoll =
            t instanceof org.deluge.model.SynthTrackModel
                || t instanceof org.deluge.model.MidiTrackModel;
        if (!isPianoRoll) {
          int drumCount =
              (t instanceof org.deluge.model.KitTrackModel kit) ? kit.getDrums().size() : 8;
          scrollOffset =
              Math.max(0, Math.min(scrollOffset, Math.max(0, drumCount - gridMode.rows)));
        } else if (scrollOffset < 0 || scrollOffset > 127) {
          scrollOffset = 67; // reset to default pitch scroll if invalid
        }
      } else {
        scrollOffset = 0;
      }
    } else {
      // In SONG/ARRANGEMENT mode, scrollOffset is track scroll, clamp it to valid tracks range
      // (including buffer slots)
      int totalRows = computeVoiceRowCount();
      scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, totalRows - gridMode.rows)));
    }

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
    scrollController.syncScrollBarValues();

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
        int modelRow = getModelRow(v);
        if (modelRow >= 0 && modelRow < voiceRowCount) {
          int engineROffset = getEngineRowOffset(modelRow);
          int engineR = baseTrackId + engineROffset;
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
              if (viewMode == null) {
                clipBtn.setToolTipText("Row " + (v + 1) + " Mute Toggle");
              } else {
                switch (viewMode) {
                  case CLIP ->
                      clipBtn.setToolTipText(
                          "Clip View: Row "
                              + (v + 1)
                              + " Mute / Unmute (Right-Click for Options / Shift-Click to Clear Steps)");
                  case SONG ->
                      clipBtn.setToolTipText("Song View: Track " + (v + 1) + " Full Track Mute");
                  case ARRANGEMENT ->
                      clipBtn.setToolTipText("Arrangement View: Lane " + (v + 1) + " Mute");
                  case AUTOMATION ->
                      clipBtn.setToolTipText("Automation View: Parameter Lane " + (v + 1) + " Mute");
                  default -> clipBtn.setToolTipText("Performance View: Live Stutter / Mute Punch");
                }
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
                int midiPitch = getRowPitch(modelRow);
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
              if (viewMode == GridViewMode.CLIP) {
                Color labelBg = getAuditionPadBgColor(modelRow, isSynth, v);
                Color labelFg = getAuditionPadFgColor(modelRow, isSynth, v);
                clipBtn.setBackground(labelBg);
                clipBtn.setForeground(labelFg);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(labelBg);
                  pad.setTextColorOverride(labelFg);
                  pad.setActive(true);
                  pad.setIntensity(1.0f);
                }
              }
              if (viewMode == null) {
                clipBtn.setToolTipText("Audition Row " + (v + 1));
              } else {
                switch (viewMode) {
                  case CLIP ->
                      clipBtn.setToolTipText(
                          "Clip View: Audition / Preview Row "
                              + (v + 1)
                              + " Note ("
                              + nName
                              + ")");
                  case SONG ->
                      clipBtn.setToolTipText("Song View: Launch Track " + (v + 1) + " Primary Clip");
                  case ARRANGEMENT ->
                      clipBtn.setToolTipText("Arrangement View: Cue Bar / Section Marker " + (v + 1));
                  case AUTOMATION ->
                      clipBtn.setToolTipText(
                          "Automation View: Cue Parameter " + (v + 1) + " Automation");
                  default ->
                      clipBtn.setToolTipText("Performance View: Recall Macro Snapshot " + (v + 1));
                }
              }
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
                Color trackColor = getGridNoteColor(modelRow);
                boolean inScale = true;
                boolean isRoot = false;

                if (isSynthMode) {
                  int pitchMidi = getRowPitch(modelRow);
                  isRoot = ScaleMapper.isRootNote(pitchMidi, projectModel.getKey());
                  inScale =
                      ScaleMapper.isNoteInScale(
                          pitchMidi, projectModel.getKey(), projectModel.getScale());
                }

                Color cellBaseColor =
                    getThemeColor(theme, trackColor, stepState, inScale, isRoot, modelRow);
                boolean isNudged = false;
                if (stepState) {
                  org.deluge.model.ClipModel cModel = null;
                  if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
                    org.deluge.model.TrackModel tModel =
                        projectModel.getTracks().get(editedModelTrack);
                    if (activeClipId < tModel.getClips().size()) {
                      cModel = tModel.getClips().get(activeClipId);
                    }
                  }
                  if (cModel != null) {
                    org.deluge.model.StepData sd = getClipStep(cModel, modelRow, activeCol);
                    if (sd != null && sd.active()) {
                      if (sd.fill() > 0.0f) {
                        cellBaseColor = new Color(0x00, 0xd2, 0xff);
                      } else if (sd.nudge() > 0.0f) {
                        isNudged = true;
                      }
                    }
                  } else if (bridge != null) {
                    if (bridge.getStepFill(engineR, activeCol) > 0.0) {
                      isNudged = true;
                    }
                  }
                }
                pad.setBaseColor(cellBaseColor);
                pad.setBlur(isNudged);
                pad.setApplicable(inScale || !isSynthMode);
                pad.setTheme(theme);
                pad.setBeatMarker((c + scrollOffsetX) % 4 == 0);
                pad.setScaleRoot(isRoot);
                pad.setScaleNote(inScale);

                pad.setMuted(isMuted);
                pad.setInLoop(inLoop);
                pad.setActive(stepState);
                pad.setIntensity((float) (vel * (0.2f + 0.8f * prob)));
                pad.setTail(isStepTied(modelRow, activeCol) && !stepState);
                if (stepState) {
                  if (isSynthMode) {
                    int pitchMidi = getRowPitch(modelRow);
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
                        ? getGridNoteColor(modelRow, (float) vel)
                        : getStepPadDefaultBg(modelRow, activeCol));
              }
            }
          }
        }
      }
    } else if (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT) {
      for (int v = 0; v < gridMode.rows; v++) {
        // Bottom-up SONG/ARR order, shared with the row-header builder (songDisplayRows) so the pad
        // recolour agrees with the left labels; -1 marks the empty rows above the tracks.
        int modelRow = songRowIndex(v);
        if (modelRow >= 0 && modelRow < voiceRowCount) {
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
                pad.setTail(false);
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
              // Deluge col-18 is the SECTION pad: defaultClipSectionColours[clip->section], black
              // when the row has no clip (session_view.cpp drawSectionSquare). We keep Java's name
              // label on top for readability but colour the pad by section to match hardware.
              Color labelBg;
              Color labelFg;
              boolean rowHasClip =
                  modelRow >= 0
                      && modelRow < tracks.size()
                      && !tracks.get(modelRow).getClips().isEmpty();
              if (soloRow == modelRow) {
                labelBg = Color.GREEN;
                labelFg = Color.BLACK;
              } else if (rowHasClip) {
                labelBg =
                    DelugeColour.sectionColour(tracks.get(modelRow).getClips().get(0).getSection());
                double b =
                    (0.299 * labelBg.getRed()
                            + 0.587 * labelBg.getGreen()
                            + 0.114 * labelBg.getBlue())
                        / 255.0;
                labelFg = b > 0.5 ? Color.BLACK : Color.WHITE;
              } else {
                labelBg = Color.BLACK;
                labelFg = Color.GRAY;
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
              // Arranger clip instances are coloured by their clip's SECTION
              // (defaultClipSectionColours[clip->section], clip_instance.cpp:31-37), NOT the clip's
              // own colourOffset. Null keeps the default getTrackColour for the SONG/other paths.
              Color arrCellColour = null;
              if (modelRow < tracks.size()) {
                org.deluge.model.TrackModel track = tracks.get(modelRow);
                if (viewMode == GridViewMode.SONG && !track.getClips().isEmpty()) {
                  // Deluge session view draws the clip's step PATTERN across the row
                  // (renderAsSingleRow): light column c if the clip has a note at that step in ANY
                  // note row, in the clip's colour — so an active clip fills many cells, not just
                  // one.
                  org.deluge.model.ClipModel clip = track.getClips().get(0);
                  int step = c + scrollOffsetX;
                  if (step < clip.getStepCount()) {
                    for (int r = 0; r < clip.getRowCount(); r++) {
                      org.deluge.model.StepData sd = clip.getStep(r, step);
                      if (sd != null && sd.active()) {
                        hasClip = true;
                        break;
                      }
                    }
                  }
                } else if (viewMode == GridViewMode.ARRANGEMENT) {
                  // Deluge arranger clip-instance bar (arranger_view.cpp renderRow, ~2356): the
                  // HEAD square (where the instance starts) is the full section colour; body
                  // squares
                  // are dimmed, with a brighter marker at every clip loopLength boundary showing
                  // the
                  // repeats: loop-start = colour.dim(3), mid-loop = colour.forBlur().dim(3).
                  int col = c + scrollOffsetX;
                  org.deluge.model.ArrangerClip ac =
                      arrangerController.getArrangerClipAt(modelRow, col);
                  hasClip = ac != null;
                  if (ac != null) {
                    int section = ac.clip() != null ? ac.clip().getSection() : -1;
                    Color base = DelugeColour.sectionColour(section);
                    int colTick = arrangerController.arrangerTickForColumn(col);
                    int colTicks = arrangerController.arrangerTicksPerColumn();
                    boolean isHead =
                        ac.startTicks() >= colTick && ac.startTicks() < colTick + colTicks;
                    if (ac.clip() == null) {
                      arrCellColour = DelugeColour.dim(base, 4); // arrangement-only preview
                    } else if (isHead) {
                      arrCellColour = base;
                    } else {
                      int loopLen = ac.clip().getLoopLength();
                      int rel = colTick - ac.startTicks();
                      boolean loopStart = loopLen > 0 && (rel % loopLen == 0);
                      arrCellColour =
                          loopStart
                              ? DelugeColour.dim(base, 3)
                              : DelugeColour.dim(DelugePadButton.getBlurColor(base), 3);
                    }
                  }
                } else if (c < track.getClips().size()) {
                  hasClip = true;
                }
              }
              Color cellColour = arrCellColour != null ? arrCellColour : getTrackColour(modelRow);
              if (clipBtn instanceof DelugePadButton pad) {
                org.deluge.project.PreferencesManager.GridColorTheme theme =
                    org.deluge.project.PreferencesManager.getGridColorTheme();
                pad.setBaseColor(cellColour);
                pad.setTheme(theme);
                pad.setActive(hasClip);
                pad.setMuted(isMuted);
                pad.setScaleRoot(false);
                pad.setScaleNote(false);
                // The arranger's dim(3)/blur shading IS the final LED value from the C; don't let
                // the pad's velocity-intensity (0.8) darken it again (double-dim -> near-black).
                if (viewMode == GridViewMode.ARRANGEMENT) {
                  pad.setIntensity(1.0f);
                }
                pad.setBeatMarker(
                    viewMode == GridViewMode.ARRANGEMENT && ((c + scrollOffsetX) % 4 == 0));
              } else {
                if (hasClip) {
                  clipBtn.setBackground(cellColour);
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
      Color trackColor = getTrackBaseColor();
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
              int drumIdx = KeyplayKeyboard.getDrumIndex(v, c);
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
              int note = KeyplayKeyboard.getNote(v, c);
              boolean isRoot = ScaleMapper.isRootNote(note, projectModel.getKey());
              boolean inScale =
                  ScaleMapper.isNoteInScale(note, projectModel.getKey(), projectModel.getScale());

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
    vuManager.clear();
    vuManager.startTimer();

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
      int leftOffset = automationController.isOverviewMode() ? (topLw + 17) : (topLw + 91);
      autoHeader.add(Box.createRigidArea(new Dimension(leftOffset, 1)));

      JLabel autoLabel = new JLabel("AUTO");
      autoLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
      autoLabel.setForeground(new Color(0x00, 0xff, 0xcc));
      autoHeader.add(autoLabel);

      // Overview/Editor toggle
      JToggleButton overviewToggle =
          new JToggleButton("OVERVIEW", automationController.isOverviewMode());
      overviewToggle.setFont(new Font("SansSerif", Font.PLAIN, 11));
      overviewToggle.setMargin(new Insets(0, 4, 0, 4));
      overviewToggle.addActionListener(
          e -> {
            automationController.setOverviewMode(overviewToggle.isSelected());
            overviewToggle.setText(automationController.isOverviewMode() ? "OVERVIEW" : "EDITOR");
            refresh();
          });
      autoHeader.add(overviewToggle);

      if (!automationController.isOverviewMode()) {
        // Editor mode: param combo
        automationParamCombo =
            new javax.swing.JComboBox<>(org.deluge.model.AutomationParam.SYTH_PARAMS);
        automationParamCombo.setSelectedItem(automationController.getSelectedParam());
        automationParamCombo.addActionListener(
            e -> {
              String selected = (String) automationParamCombo.getSelectedItem();
              if (selected != null) {
                automationController.setSelectedParam(selected);
                refresh();
              }
            });
        automationParamCombo.setToolTipText("Select automation parameter");
        autoHeader.add(automationParamCombo);

        JButton interpBtn = new JButton("Interp");
        interpBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        interpBtn.setMargin(new Insets(0, 4, 0, 4));
        interpBtn.setToolTipText("Linear interpolate between automated steps");
        interpBtn.addActionListener(
            e ->
                automationController.interpolateAutomation(
                    fAutoClip, automationController.getSelectedParam()));
        autoHeader.add(interpBtn);

        JButton clearAutoBtn = new JButton("Clear");
        clearAutoBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
        clearAutoBtn.setMargin(new Insets(0, 4, 0, 4));
        clearAutoBtn.addActionListener(
            e -> {
              if (fAutoClip != null) {
                fAutoClip.clearAutomation(automationController.getSelectedParam());
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
                  automationController.interpolateAutomation(fAutoClip, param);
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

      automationController.buildAutomationView(this, autoClip, padSz);
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
          Color trackColor = getTrackBaseColor();
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
      org.deluge.model.TrackModel rowTrack = null;
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        rowTrack = projectModel.getTracks().get(editedModelTrack);
      }
      for (int v = 0; v < gridMode.rows; v++) {
        int modelRow = getModelRow(v);
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
        this.vertScrollBar = scrollController.getVerticalScrollBar();
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
                  JScrollBar horizBar = scrollController.getHorizontalScrollBar();
                  if (horizBar != null) {
                    horizBar.setValue(scrollOffsetX);
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
          leftSpacing = automationController.isOverviewMode() ? (lw + 17) : (lw + 91);
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
        this.horizScrollBar = scrollController.getHorizontalScrollBar();
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
                fActiveClip.rebuildNotesFromGrid(); // Re-align note models to new tick boundaries!

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

      // Single source of SONG/ARR row order (bottom-up: last track at the top). The loop reads a
      // GridRow per visual row instead of computing a track index, so labels, colours and pads all
      // agree and empty rows are just null-track entries (no reversal math, no negative indexing).
      final boolean songArr =
          (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT);
      final java.util.List<GridRow> songRows = songArr ? songDisplayRows(songVoiceRows) : null;

      for (int t = 0; t < songVoiceRows + 2; t++) {

        JPanel rowPanel = new JPanel();
        rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
        rowPanel.setBackground(new Color(0x22, 0x22, 0x22));
        rowPanel.setPreferredSize(new Dimension(rowW, padSz));
        rowPanel.setMinimumSize(new Dimension(rowW, padSz));
        rowPanel.setMaximumSize(new Dimension(rowW, padSz));
        rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rowPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Resolve this display row from the ordered model (SONG/ARR) or the legacy top-down index.
        final int currentTrack;
        final org.deluge.model.TrackModel dispTrack;
        if (songArr && t < songVoiceRows) {
          GridRow gr = songRows.get(t);
          dispTrack = gr.track(); // null when empty
          // Keep the index NON-negative so the many "< tracks.size()" guards downstream skip empty
          // rows exactly as before (empties map out-of-range, never to a negative index).
          currentTrack = (dispTrack != null) ? gr.modelIndex() : (tracks.size() + t);
        } else {
          currentTrack = (t < songVoiceRows) ? (scrollOffset + t) : t;
          dispTrack =
              (currentTrack >= 0 && currentTrack < tracks.size()) ? tracks.get(currentTrack) : null;
        }
        if (dispTrack != null) {
          String hex = dispTrack.getColourHex();
          if (hex != null && hex.startsWith("0x")) {
            try {
              int rgb = Integer.decode(hex.substring(0, 8)); // strip alpha if 8 chars
              trackColors[Math.floorMod(currentTrack, trackColors.length)] = new Color(rgb);
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
          trackName = (dispTrack != null) ? dispTrack.getName() : ("EMPTY " + (t + 1));
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
        if (t < songVoiceRows && dispTrack != null) {
          org.deluge.model.TrackModel track = dispTrack;

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

        vuManager.registerTrackVu(trk, vu);

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
              clipBtn = new CleanJButton();
            }
          } else {
            if (isAdvanced) {
              DelugePadButton pad = new DelugePadButton();
              pad.putClientProperty("row", t);
              pad.putClientProperty("col", c);
              clipBtn = pad;
            } else {
              clipBtn = new CleanJButton();
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

          boolean isUnusedSongRow =
              (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT)
                  && currentTrack >= tracks.size();
          if (isUnusedSongRow) {
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
            clipBtn.setEnabled(false);
            clearActionListeners(clipBtn);
            for (java.awt.event.MouseListener ml : clipBtn.getMouseListeners()) {
              clipBtn.removeMouseListener(ml);
            }
            for (java.awt.event.MouseMotionListener mml : clipBtn.getMouseMotionListeners()) {
              clipBtn.removeMouseMotionListener(mml);
            }
            for (java.awt.event.MouseWheelListener mwl : clipBtn.getMouseWheelListeners()) {
              clipBtn.removeMouseWheelListener(mwl);
            }
            continue; // Skip the rest of styling and listener attachments!
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
              if (dispTrack != null && c < dispTrack.getClips().size()) {
                clipBtn.setText(
                    "<html><center><font size='3'>"
                        + dispTrack.getClips().get(c).getName()
                        + "</font></center></html>");
              } else {
                clipBtn.setText("PAD " + (c + 1));
              }
            } else
              switch (viewMode) {
                case KEYPLAY:
                  if (colId < 16) {
                    if (isEditedTrackKit()) {
                      int drumIdx = KeyplayKeyboard.getDrumIndex(trk, colId);
                      clipBtn.setText(drumIdx < editedKitDrumCount() ? ("D" + (drumIdx + 1)) : "");
                    } else {
                      clipBtn.setText(getNoteName(KeyplayKeyboard.getNote(trk, colId)));
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
                  org.deluge.model.ArrangerClip placement =
                      arrangerController.getArrangerClipAt(currentTrack, c);
                  if (placement != null && placement.clip() != null) {
                    clipBtn.setText(
                        "<html><center><font size='3'><b>"
                            + placement.clip().getName()
                            + "</b><br>Bar "
                            + (c + 1)
                            + "</font></center></html>");
                    if (clipBtn instanceof DelugePadButton pad) {
                      pad.setBaseColor(
                          trackColors[Math.floorMod(currentTrack, trackColors.length)]);
                      pad.setIntensity(1.0f);
                      pad.setActive(true);
                    } else {
                      clipBtn.setBackground(
                          trackColors[Math.floorMod(currentTrack, trackColors.length)]);
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
                  if (dispTrack != null && c < dispTrack.getClips().size()) {
                    clipBtn.setText(
                        "<html><center><font size='3'>"
                            + dispTrack.getClips().get(c).getName()
                            + "</font></center></html>");
                  } else {
                    clipBtn.setText("PAD " + (c + 1));
                  }
                  break;
              }
          }

          boolean hasClip = false;
          if (dispTrack != null) {
            if (c < dispTrack.getClips().size()) {
              hasClip = true;
            }
          }

          if (isMuteColumn(colId)) {
            final int engineRow =
                (viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT)
                    ? trk
                    : (baseTrackId + trk);
            if ((viewMode == GridViewMode.SONG || viewMode == GridViewMode.ARRANGEMENT)
                && currentTrack >= tracks.size()) {
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
                boolean curMute;
                if (viewMode == GridViewMode.ARRANGEMENT) {
                  curMute = tracks.get(t).isMutedInArrangement();
                } else {
                  curMute = bridge.getMute(engineRow);
                }
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
                  if (viewMode == GridViewMode.ARRANGEMENT) {
                    if (trkId < tracks.size()) {
                      org.deluge.model.TrackModel track = tracks.get(trkId);
                      boolean isMuted = track.isMutedInArrangement();
                      track.setMutedInArrangement(!isMuted);
                      if (SwingDelugeApp.mainInstance != null) {
                        SwingDelugeApp.mainInstance.syncHighFidelityEngine(projectModel);
                      }
                    }
                    refresh();
                    return;
                  }
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
                                projectModel,
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
            if (viewMode == GridViewMode.ARRANGEMENT) {
              if (currentTrack < tracks.size()) {
                org.deluge.model.TrackModel track = tracks.get(currentTrack);
                boolean isSoloed = track.isSoloingInArrangement();
                Color soloBg = isSoloed ? new Color(0x00, 0xe6, 0x76) : new Color(0x2d, 0x37, 0x48);
                clipBtn.setText(isSoloed ? "SOLOED" : "SOLO");
                clipBtn.setBackground(soloBg);
                clipBtn.setForeground(isSoloed ? Color.BLACK : Color.LIGHT_GRAY);
                clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 10 : 8));
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(soloBg);
                  pad.setTextColorOverride(isSoloed ? Color.BLACK : Color.LIGHT_GRAY);
                  pad.setDrawCenterCircle(false);
                  pad.setIntensity(isSoloed ? 1.0f : 0.4f);
                  pad.setActive(true);
                  pad.setNoteText(isSoloed ? "SOLOED" : "SOLO");
                }

                clearActionListeners(clipBtn);
                clipBtn.addActionListener(
                    e -> {
                      track.setSoloingInArrangement(!isSoloed);
                      if (SwingDelugeApp.mainInstance != null) {
                        SwingDelugeApp.mainInstance.syncHighFidelityEngine(projectModel);
                      }
                      refresh();
                    });
              } else {
                clipBtn.setText("");
                clipBtn.setBackground(new Color(0x15, 0x15, 0x15));
                clipBtn.setEnabled(false);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setActive(false);
                }
              }
            } else if (viewMode == GridViewMode.SONG) {
              if (currentTrack < tracks.size()) {
                String sLaunch = tracks.get(currentTrack).getName();
                clipBtn.setText(sLaunch);
                clipBtn.setFont(new Font("SansSerif", Font.BOLD, padSz > 70 ? 11 : 9));
                clipBtn.setBackground(trackColors[Math.floorMod(currentTrack, trackColors.length)]);
                clipBtn.setForeground(Color.BLACK);
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(trackColors[Math.floorMod(currentTrack, trackColors.length)]);
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
              boolean isOctaveC = isSynth && (getRowPitch(scrollOffset + t) % 12 == 0);

              String nName;
              if (isSynth) {
                int midiPitch = getRowPitch(scrollOffset + t);
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

              Color cellBg = getAuditionPadBgColor(scrollOffset + t, isSynth, t);
              Color cellFg = getAuditionPadFgColor(scrollOffset + t, isSynth, t);
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
                            int pitchMidi = isSynthModeLocal ? getRowPitch(trk) : 60;
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
                int drumIdx = KeyplayKeyboard.getDrumIndex(trk, colId);
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
                int note = KeyplayKeyboard.getNote(trk, colId);
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
            } else if (viewMode == GridViewMode.SONG
                && currentTrack < tracks.size()
                && colId < 16) {
              // SONG visual states: loop-green, playing (track color), queued (amber), stopped
              // (dark), empty (very dark)
              long launchQ = bridge != null ? bridge.getLaunchQueue(currentTrack) : -1L;
              long currentClip = bridge != null ? bridge.getCurrentClip(currentTrack) : 0L;
              if (launchQ == colId) {
                clipBtn.setBackground(new Color(0xff, 0xaa, 0x00)); // amber = queued
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0xff, 0xaa, 0x00));
                  pad.setIntensity(1.0f);
                  pad.setActive(true);
                }
              } else if (currentClip == colId
                  && bridge != null
                  && bridge.getClipPlayMode(currentTrack, colId) == 1) {
                clipBtn.setBackground(new Color(0x00, 0xcc, 0x00)); // green = LOOP mode
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(new Color(0x00, 0xcc, 0x00));
                  pad.setIntensity(1.0f);
                  pad.setActive(true);
                }
              } else if (currentClip == colId) {
                clipBtn.setBackground(
                    trackColors[Math.floorMod(currentTrack, trackColors.length)]); // playing
                if (clipBtn instanceof DelugePadButton pad) {
                  pad.setBaseColor(trackColors[Math.floorMod(currentTrack, trackColors.length)]);
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
              int note = KeyplayKeyboard.getNote(trk, colId);
              int drumIdx = KeyplayKeyboard.getDrumIndex(trk, colId);
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
              clipController.attachListeners(clipBtn, trk, t, colId);
            } else if (viewMode == GridViewMode.SONG) {
              // Handled by showClipContextMenu in cell mouse listener setup
            } else if (viewMode == GridViewMode.ARRANGEMENT) {
              arrangerController.attachListeners(clipBtn, currentTrack, colId);
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
        this.vertScrollBar = scrollController.getVerticalScrollBar();
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

    // Revalidate and repaint
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
                  scrollController.updatePlayheadFollow(rawCol);
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
                      vuManager.spikeVu(engineRow);
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
                    int visualModelRow = (viewMode == GridViewMode.CLIP ? scrollOffset + t : t);
                    int engineRowOffset = getEngineRowOffset(visualModelRow);
                    int engineRow2 = baseTrackId + engineRowOffset;
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
                          pads[t][c].setText("");
                          if (pads[t][c] instanceof DelugePadButton pad) {
                            pad.setActive(false);
                            pad.setBaseColor(darkBg);
                            pad.setIntensity(0.0f);
                            pad.setNoteText("");
                          } else {
                            pads[t][c].setBackground(darkBg);
                          }
                          continue;
                        }
                        double[] outVelProb = {0.8, 1.0};
                        boolean stepActive =
                            isStepActiveOrSpanned(visualModelRow, engineCol, outVelProb);
                        double velPb = outVelProb[0];
                        if (pads[t][c] instanceof DelugePadButton pad) {
                          pad.setActive(stepActive);
                          pad.setIntensity((float) (velPb * 0.8f));
                          Color padColor = getGridNoteColor(visualModelRow);
                          boolean isNudged = false;
                          if (stepActive) {
                            org.deluge.model.ClipModel cModel = null;
                            if (projectModel != null
                                && editedModelTrack < projectModel.getTracks().size()) {
                              org.deluge.model.TrackModel tModel =
                                  projectModel.getTracks().get(editedModelTrack);
                              if (activeClipId < tModel.getClips().size()) {
                                cModel = tModel.getClips().get(activeClipId);
                              }
                            }
                            if (cModel != null) {
                              org.deluge.model.StepData sd =
                                  getClipStep(cModel, visualModelRow, engineCol);
                              if (sd != null && sd.active()) {
                                if (sd.fill() > 0.0f) {
                                  padColor = new Color(0x00, 0xd2, 0xff);
                                } else if (sd.nudge() > 0.0f) {
                                  isNudged = true;
                                }
                              }
                            } else if (bridge != null) {
                              if (bridge.getStepFill(engineRow2, engineCol) > 0.0) {
                                isNudged = true;
                              }
                            }
                          }
                          pad.setBaseColor(padColor);
                          pad.setBlur(isNudged);
                          pad.setTail(isStepTied(visualModelRow, engineCol) && !stepActive);
                        } else {
                          pads[t][c].setBackground(
                              stepActive
                                  ? getGridNoteColor(visualModelRow, (float) velPb)
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
    scrollController.updateScrollBarValues();
  }

  // ── Automation Editor (8-value-band per-step editor) ──

  /**
   * Build the per-step value band editor for a single automation parameter. 8 rows × stepCount
   * grid, where each cell in a row represents whether the step's automation value falls within that
   * row's value band (0-15, 16-31, etc.). Click to set, shift-click to clear, drag to paint.
   */
  private void buildAutomationEditor(org.deluge.model.ClipModel autoClip, String param, int padSz) {
    // Automation editor, overview, and interpolation methods moved to
    // AutomationEditorController.java
  }

  // ── Automation Overview Grid ──

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
            oldStep = getClipStep(tModel.getClips().get(activeClipId), modelRow, activeCol);
          }
        }

        bridge.setStep(engineRow, activeCol, false);

        if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
          org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
          if (activeClipId < tModel.getClips().size()) {
            org.deluge.model.ClipModel cModel = tModel.getClips().get(activeClipId);
            double curVel = bridge.getVelocity(engineRow, activeCol);
            double curProb = bridge.getStepProbability(engineRow, activeCol);
            int pitch = isSynthMode ? getRowPitch(modelRow) : 0;
            setClipStep(
                cModel,
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
                          projectModel,
                          editedModelTrack,
                          activeClipId,
                          modelRow,
                          activeCol,
                          oldStep,
                          getClipStep(cModel, modelRow, activeCol)));
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

  /**
   * One SONG/ARRANGEMENT display row, top-to-bottom. {@code track == null} marks an empty padding
   * row; {@code modelIndex} is the real model/engine track index (-1 when empty).
   */
  private record GridRow(org.deluge.model.TrackModel track, int modelIndex, String label) {}

  /**
   * The SONG/ARRANGEMENT grid rows in DISPLAY order — the single source of row ordering. The Deluge
   * session/arranger grid is bottom-up (session_view.cpp getClipOnScreen/renderRow: pad-row 0 is
   * the BOTTOM), so the last track renders at the TOP. The renderer iterates this list by visual
   * row and reads {@code track}/{@code modelIndex} directly — no per-site reversal arithmetic and
   * no negative-index guards, since empty rows are simply null-track entries.
   */
  private java.util.List<GridRow> songDisplayRows(int rowCount) {
    java.util.List<org.deluge.model.TrackModel> t =
        projectModel != null ? projectModel.getTracks() : java.util.List.of();
    java.util.List<GridRow> rows = new java.util.ArrayList<>(rowCount);
    for (int v = 0; v < rowCount; v++) {
      int idx = songRowIndex(v);
      if (idx >= 0) {
        rows.add(new GridRow(t.get(idx), idx, t.get(idx).getName()));
      } else {
        rows.add(new GridRow(null, -1, "EMPTY " + (v + 1)));
      }
    }
    return rows;
  }

  /**
   * Model/engine track index for a SONG/ARRANGEMENT display row (bottom-up: display row 0 = last
   * track), or -1 for the empty rows above the tracks. The single ordering primitive shared by the
   * row builder ({@link #songDisplayRows}) and the in-place pad recolour, so labels and pads agree.
   */
  private int songRowIndex(int visualRow) {
    int n = projectModel != null ? projectModel.getTracks().size() : 0;
    int idx = n - 1 - (scrollOffset + visualRow);
    return (idx >= 0 && idx < n) ? idx : -1;
  }

  int getModelRow(int visualRow) {
    boolean editedIsKit =
        projectModel != null
            && editedModelTrack < projectModel.getTracks().size()
            && projectModel.getTracks().get(editedModelTrack)
                instanceof org.deluge.model.KitTrackModel;
    int kitDrumCount =
        editedIsKit
            ? ((org.deluge.model.KitTrackModel) projectModel.getTracks().get(editedModelTrack))
                .getDrums()
                .size()
            : 0;
    int trackCount = projectModel != null ? projectModel.getTracks().size() : 0;
    return GridRowMapper.modelRow(
        viewMode, scrollOffset, visualRow, editedIsKit, kitDrumCount, trackCount);
  }

  int getEngineRowOffset(int visualModelRow) {
    boolean isSynth = false;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      isSynth =
          projectModel.getTracks().get(editedModelTrack)
              instanceof org.deluge.model.SynthTrackModel;
    }
    if (isSynth && viewMode == GridViewMode.CLIP) {
      return 127 - getRowPitch(visualModelRow);
    }
    return visualModelRow;
  }

  int getActiveCol(int visualRow, int visualCol) {
    int activeCol = visualCol;
    if (bridge != null && projectModel != null) {
      int trackLen = 0;
      if (viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION) {
        if (editedModelTrack < projectModel.getTracks().size()) {
          org.deluge.model.TrackModel track = projectModel.getTracks().get(editedModelTrack);
          if (activeClipId < track.getClips().size()) {
            trackLen = track.getClips().get(activeClipId).getStepCount();
          }
        }
      } else {
        int modelRow = getModelRow(visualRow);
        java.util.List<org.deluge.model.TrackModel> tracks = projectModel.getTracks();
        if (modelRow < tracks.size()) {
          org.deluge.model.TrackModel track = tracks.get(modelRow);
          if (activeClipId < track.getClips().size()) {
            trackLen = track.getClips().get(activeClipId).getStepCount();
          }
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

  // Advanced gesture handlers, duplicateStep, hotSwapTrackSample, handleShiftClick,
  // and all shift-shortcut helpers have been moved to ClipEditorController.java.

  public BridgeContract getBridge() {
    return bridge;
  }

  public int getBaseTrackId() {
    return baseTrackId;
  }

  public int getScrollOffset() {
    return scrollOffset;
  }

  public void setScrollOffset(int val) {
    this.scrollOffset = val;
    refresh();
  }

  boolean isStepActive(int modelRow, int activeCol) {
    org.deluge.model.TrackModel tModel = null;
    org.deluge.model.ClipModel cModel = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      tModel = projectModel.getTracks().get(editedModelTrack);
      if (activeClipId < tModel.getClips().size()) {
        cModel = tModel.getClips().get(activeClipId);
      }
    }
    if (cModel != null) {
      org.deluge.model.StepData step = getClipStep(cModel, modelRow, activeCol);
      return (step != null && step.active());
    } else if (bridge != null) {
      int engineRow = baseTrackId + getEngineRowOffset(modelRow);
      return bridge.getStep(engineRow, activeCol);
    }
    return false;
  }

  boolean isStepActiveOrSpanned(int modelRow, int activeCol, double[] outVelProb) {
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
        org.deluge.model.StepData step = getClipStep(cModel, modelRow, s);
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
      int engineRow = baseTrackId + getEngineRowOffset(modelRow);
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

  boolean isStepTied(int modelRow, int activeCol) {
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
        org.deluge.model.StepData step = getClipStep(cModel, modelRow, s);
        if (step != null && step.active()) {
          float gateVal = step.gate();
          return (s + gateVal >= activeCol + 1.0f - 0.05f);
        }
      }
    } else if (bridge != null) {
      int engineRow = baseTrackId + getEngineRowOffset(modelRow);
      for (int s = activeCol; s >= 0; s--) {
        if (bridge.getStep(engineRow, s)) {
          double gateVal = bridge.getGate(engineRow, s);
          return (s + gateVal >= activeCol + 1.0f - 0.05f);
        }
      }
    }
    return false;
  }

  public org.rtmidijava.RtMidiOut getMidiOut() {
    return finalMidiOut;
  }

  public void duplicateStep(int startRow, int startCol, int targetRow, int targetCol) {
    clipController.duplicateStep(startRow, startCol, targetRow, targetCol);
  }

  void handleStepPressed(int row, int col) {
    clipController.handleStepPressed(row, col);
  }

  void handleStepToggled(int row, int col) {
    clipController.handleStepToggled(row, col);
  }

  void handleStepTied(int row, int colStart, int colEnd) {
    clipController.handleStepTied(row, colStart, colEnd);
  }

  public int getScrollOffsetX() {
    return scrollOffsetX;
  }

  public Color[] getTrackColors() {
    return trackColors;
  }

  public JButton[][] getPads() {
    return pads;
  }

  public int getGridModeRows() {
    return gridMode.rows;
  }

  public GridViewMode getViewMode() {
    return viewMode;
  }

  public int getColumnCount() {
    return columnCount;
  }

  public void startAudition(org.deluge.engine.FirmwareSound synth, int pitchMidi, int velocity) {
    stopAuditionIfNeeded();
    auditionMidiNote = pitchMidi;
    auditionSynth = synth;
    synth.triggerNote(pitchMidi, velocity);
  }

  public void adjustRotaryParameter(int delta) {
    clipController.adjustRotaryParameter(delta);
  }

  public void hotSwapTrackSample(int modelRow, int visibleRow, java.io.File soundFile) {
    clipController.hotSwapTrackSample(modelRow, visibleRow, soundFile);
  }

  public void handleShiftClick(int row, int col, Point localPos, Component comp) {
    clipController.handleShiftClick(row, col, localPos, comp);
  }

  public void handleShiftHover(int row, int col) {
    clipController.handleShiftHover(row, col);
  }

  public void handleShiftHoverExit() {
    clipController.handleShiftHoverExit();
  }

  public void setClonePreview(int startR, int startC, int currR, int currC) {
    clipController.setClonePreview(startR, startC, currR, currC);
  }

  public String getNoteName(int pitchMidi) {
    return ScaleMapper.getNoteName(pitchMidi);
  }

  private Color getTrackBaseColor() {
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      String hex = t.getColourHex();
      if (hex != null && hex.startsWith("0x")) {
        try {
          long rgbVal = Long.parseLong(hex.substring(2), 16);
          return new Color((int) rgbVal);
        } catch (Exception e) {
          // fallback
        }
      }
    }
    return trackColors[editedModelTrack % trackColors.length];
  }

  public Color getGridNoteColor(int modelRow) {
    return getGridNoteColor(modelRow, 1.0f);
  }

  public Color getGridNoteColor(int modelRow, float velocity) {
    Color trackColor = getTrackBaseColor();
    if (viewMode == GridViewMode.CLIP || viewMode == GridViewMode.AUTOMATION) {
      boolean isPitched = false;
      int colourOffset = 0;
      if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
        org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
        // Both synth AND MIDI (and CV) clips are pitched piano-rolls (InstrumentClip) and get the
        // per-pitch rainbow on hardware; only synth did here, so MIDI rows rendered mono-colour.
        isPitched =
            t instanceof org.deluge.model.SynthTrackModel
                || t instanceof org.deluge.model.MidiTrackModel;
        colourOffset = t.getColourOffset();
      }
      if (isPitched) {
        // Faithful to InstrumentClip::getMainColourFromY (instrument_clip.cpp:1235):
        // RGB::fromHue((yNote + colourOffset + noteRowColourOffset) * -8/3). The Deluge uses its
        // own
        // sine-based fromHue palette per pitch, NOT an HSB hue-shift of the track colour (a Java
        // invention). Per-note-row colourOffset is not tracked per row here (0 on fresh rows).
        int pitchMidi = getRowPitch(modelRow);
        Color noteColor = DelugeColour.fromHue((pitchMidi + colourOffset) * -8 / 3);
        return velocityBlend(noteColor, velocity);
      }
    }
    return velocityBlend(trackColor, velocity);
  }

  public void adjustTrackColorOffset(int delta) {
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      int newOffset = t.getColourOffset() + (delta * 3);
      newOffset = newOffset % 192;
      if (newOffset < 0) newOffset += 192;
      t.setColourOffset(newOffset);
      refresh();
    }
  }

  public boolean isNoteInScale(int note) {
    if (projectModel == null) return true;
    return ScaleMapper.isNoteInScale(note, projectModel.getKey(), projectModel.getScale());
  }

  public boolean isRootNote(int note) {
    if (projectModel == null) return false;
    return ScaleMapper.isRootNote(note, projectModel.getKey());
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
          return st.getStutter().getStutterRate();
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
          st.getStutter().setStutterRate((float) v);
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
          st.setArp(oldArp.toBuilder().rate((float) (v * 2.0f)).build());
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

  public int getEditedModelTrack() {
    return editedModelTrack;
  }

  // ── Clip note copy/paste (Deluge X_ENC+LEARN copy / SHIFT paste) ──
  // Static so notes copied in one view/track can be pasted into another. StepData is an immutable
  // record, so snapshotting references is a safe deep copy.
  private static org.deluge.model.StepData[][] noteClipboard;

  /** The clip currently being edited (edited track + active clip index), or null. */
  public org.deluge.model.ClipModel getEditedActiveClip() {
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
          int pitch = target.getRowYNote(r);
          int modelRow = getRowFromPitch(pitch);
          if (modelRow >= 0) {
            int engineRow = baseTrackId + modelRow;
            bridge.setStep(engineRow, c, sd.active());
            bridge.setVelocity(engineRow, c, sd.velocity());
            bridge.setGate(engineRow, c, sd.gate());
            bridge.setStepProbability(engineRow, c, sd.probability());
            bridge.setStepFill(engineRow, c, sd.fill());
          }
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
    scrollController.scrollHorizontally(cellsOffset);
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

  public int getStepCount() {
    return stepCount;
  }

  public void scrollVertically(int cellsOffset) {
    scrollController.scrollVertically(cellsOffset);
  }

  public void resetHorizontalScroll() {
    scrollController.resetHorizontalScroll();
  }

  public void resetVerticalScroll() {
    scrollController.resetVerticalScroll();
  }

  public int getSoloRow() {
    return soloRow;
  }

  public void setPad(int r, int c, JButton pad) {
    if (r >= 0 && r < MAX_GRID_ROWS && c >= 0 && c < MAX_GRID_COLS) {
      pads[r][c] = pad;
    }
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
                    projectModel,
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

  void styleSystemButton(JButton btn, Color bg, Color fg, int fontSize) {
    btn.setContentAreaFilled(false);
    btn.setOpaque(true);
    btn.setFocusPainted(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, fontSize));
    btn.setBorder(BorderFactory.createLineBorder(bg.brighter(), 1));
  }

  public JButton getPadButton(int visibleRow, int col) {
    if (visibleRow >= 0 && visibleRow < 32 && col >= 0 && col < 32) {
      return pads[visibleRow][col];
    }
    return null;
  }

  public void handlePadMouseWheel(int visibleRow, int visualCol, java.awt.event.MouseWheelEvent e) {
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
    org.deluge.model.ClipModel cModel = tModel.getActiveClip();
    if (cModel == null) return;

    int modelRow = getModelRow(visibleRow);
    int activeCol = getActiveCol(visibleRow, visualCol);

    org.deluge.model.StepData sd = getClipStep(cModel, modelRow, activeCol);
    if (!sd.active()) {
      return; // Gestural pad adjustments only apply to active (programmed) notes!
    }

    int rotation = e.getWheelRotation();
    int dir = -rotation; // Scroll up = positive change, scroll down = negative change (magnitude
    // preserved)

    org.deluge.model.StepData updated = null;
    String oledParam = "";
    String oledValue = "";

    if (isShiftHeld()) {
      // Shift held = Adjust note probability (0% to 100%, 5% increments)
      float newProb = Math.max(0.0f, Math.min(1.0f, sd.probability() + dir * 0.05f));
      newProb = Math.round(newProb * 100.0f) / 100.0f;
      updated =
          new org.deluge.model.StepData(
              true,
              sd.velocity(),
              sd.gate(),
              newProb,
              sd.pitch(),
              sd.iterance(),
              sd.fill(),
              sd.nudge());
      oledParam = "PROB";
      oledValue = (int) (newProb * 100) + "%";
    } else if (e.isAltDown()) {
      // Alt held = Adjust note gate/length (0.125 to 64.0 steps, 0.25 step increments)
      float newGate = Math.max(0.125f, Math.min(64.0f, sd.gate() + dir * 0.25f));
      updated =
          new org.deluge.model.StepData(
              true,
              sd.velocity(),
              newGate,
              sd.probability(),
              sd.pitch(),
              sd.iterance(),
              sd.fill(),
              sd.nudge());
      oledParam = "GATE";
      oledValue = String.format("%.2f", newGate);
    } else if (e.isControlDown()) {
      // Ctrl held = Transpose note pitch (up/down by semitones)
      int newPitch = Math.max(0, Math.min(127, sd.pitch() + dir));
      int oldPitch = sd.pitch();
      if (newPitch != oldPitch) {
        int oldClipRow = getClipRowIndex(cModel, modelRow, false);
        if (oldClipRow >= 0) {
          cModel.setStep(oldClipRow, activeCol, org.deluge.model.StepData.empty());
          if (bridge != null) {
            int oldEngineRow = baseTrackId + modelRow;
            bridge.setStep(oldEngineRow, activeCol, false); // Clear old step in bridge!
          }
        }
        int newModelRow = getRowFromPitch(newPitch);
        if (newModelRow >= 0) {
          updated =
              new org.deluge.model.StepData(
                  true,
                  sd.velocity(),
                  sd.gate(),
                  sd.probability(),
                  newPitch,
                  sd.iterance(),
                  sd.fill(),
                  sd.nudge());
          modelRow =
              newModelRow; // Update modelRow reference for subsequent setClipStep/bridge sync
        }
      }
      oledParam = "PITCH";
      oledValue = String.valueOf(newPitch);
    } else {
      // No modifiers = Adjust note velocity (0.0 to 1.0, 0.05 increments, displayed as 0..127)
      float newVel = Math.max(0.0f, Math.min(1.0f, sd.velocity() + dir * 0.05f));
      newVel = Math.round(newVel * 100.0f) / 100.0f;
      updated =
          new org.deluge.model.StepData(
              true,
              newVel,
              sd.gate(),
              sd.probability(),
              sd.pitch(),
              sd.iterance(),
              sd.fill(),
              sd.nudge());
      oledParam = "VEL";
      oledValue = String.valueOf((int) (newVel * 127));
    }

    if (updated != null) {
      setClipStep(cModel, modelRow, activeCol, updated);

      // Sync with real-time ChucK audio engine
      if (bridge != null) {
        int engineRow = baseTrackId + modelRow;
        bridge.setStep(engineRow, activeCol, updated.active());
        bridge.setVelocity(engineRow, activeCol, updated.velocity());
        bridge.setGate(engineRow, activeCol, updated.gate());
        bridge.setStepProbability(engineRow, activeCol, updated.probability());
      }

      // Display transient parameter change on OLED readout
      if (SwingDelugeApp.mainInstance != null && SwingDelugeApp.mainInstance.getTopBar() != null) {
        SwingDelugeApp.mainInstance
            .getTopBar()
            .getParamReadout()
            .printTransient(oledParam, oledValue);
      }

      fireProjectChanged();
      refresh();
    }
  }

  public void transposeTrack(int semitones) {
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
    org.deluge.model.ClipModel cModel = tModel.getActiveClip();
    if (cModel == null) return;

    // 1. Collect active steps with transposed pitches and resolved modelRows
    java.util.List<TransposedStep> list = new java.util.ArrayList<>();
    int rows = cModel.getRowCount();
    int steps = cModel.getStepCount();

    for (int r = 0; r < rows; r++) {
      int pitchMidi = cModel.getRowYNote(r);
      if (pitchMidi < 0) continue;

      for (int s = 0; s < steps; s++) {
        org.deluge.model.StepData sd = cModel.getStep(r, s);
        if (sd.active()) {
          int newPitch = sd.pitch() + semitones;
          // Map new pitch back to a grid modelRow across the full 128 MIDI range
          int targetModelRow = -1;
          for (int mr = 0; mr < 128; mr++) {
            if (getRowPitch(mr) == newPitch) {
              targetModelRow = mr;
              break;
            }
          }

          if (targetModelRow >= 0) {
            org.deluge.model.StepData transposed =
                new org.deluge.model.StepData(
                    true,
                    sd.velocity(),
                    sd.gate(),
                    sd.probability(),
                    newPitch,
                    sd.iterance(),
                    sd.fill(),
                    sd.nudge());
            list.add(new TransposedStep(targetModelRow, s, transposed));
          }
        }
      }
    }

    // 2. Clear all current steps in the bridge matching this clip's pitches
    int baseRow = baseTrackId;
    for (int r = 0; r < rows; r++) {
      int pitchMidi = cModel.getRowYNote(r);
      if (pitchMidi >= 0) {
        int mr = -1;
        for (int m = 0; m < 128; m++) {
          if (getRowPitch(m) == pitchMidi) {
            mr = m;
            break;
          }
        }
        if (mr >= 0 && bridge != null) {
          int engineRow = baseRow + mr;
          for (int s = 0; s < steps; s++) {
            bridge.setStep(engineRow, s, false);
          }
        }
      }
    }
    // Wipe the clip rows completely
    cModel.setRowCount(0);

    // 3. Write transposed steps back using setClipStep (which creates and maps rows correctly!)
    for (TransposedStep ts : list) {
      setClipStep(cModel, ts.modelRow, ts.step, ts.data);
      if (bridge != null) {
        int engineRow = baseRow + ts.modelRow;
        bridge.setStep(engineRow, ts.step, true);
        bridge.setVelocity(engineRow, ts.step, ts.data.velocity());
        bridge.setGate(engineRow, ts.step, ts.data.gate());
        bridge.setStepProbability(engineRow, ts.step, ts.data.probability());
        bridge.setIterance(engineRow, ts.step, ts.data.iterance());
        bridge.setStepFill(engineRow, ts.step, ts.data.nudge());
      }
    }

    fireProjectChanged();
    refresh();
  }

  public void duplicateTrackContent() {
    if (projectModel == null || editedModelTrack >= projectModel.getTracks().size()) return;
    org.deluge.model.TrackModel tModel = projectModel.getTracks().get(editedModelTrack);
    org.deluge.model.ClipModel cModel = tModel.getActiveClip();
    if (cModel == null) return;

    int originalSteps = cModel.getStepCount();
    int newSteps = originalSteps * 2;
    if (newSteps > 128) return; // Limit to maximum 128 steps

    cModel.setStepCount(newSteps);
    if (bridge != null) {
      bridge.setTrackLength(baseTrackId, newSteps);
    }

    // Copy steps to the second half, using correct pitch modelRow matching for the bridge!
    int rows = cModel.getRowCount();
    int baseRow = baseTrackId;
    for (int r = 0; r < rows; r++) {
      int pitchMidi = cModel.getRowYNote(r);
      if (pitchMidi < 0) continue;

      int modelRow = -1;
      for (int m = 0; m < 128; m++) {
        if (getRowPitch(m) == pitchMidi) {
          modelRow = m;
          break;
        }
      }

      if (modelRow >= 0) {
        int engineRow = baseRow + modelRow;
        for (int s = 0; s < originalSteps; s++) {
          org.deluge.model.StepData sd = cModel.getStep(r, s);
          if (sd.active()) {
            int targetStep = s + originalSteps;
            cModel.setStep(r, targetStep, sd);
            if (bridge != null) {
              bridge.setStep(engineRow, targetStep, true);
              bridge.setVelocity(engineRow, targetStep, sd.velocity());
              bridge.setGate(engineRow, targetStep, sd.gate());
              bridge.setStepProbability(engineRow, targetStep, sd.probability());
              bridge.setIterance(engineRow, targetStep, sd.iterance());
              bridge.setStepFill(engineRow, targetStep, sd.nudge());
            }
          }
        }
      }
    }

    fireProjectChanged();
    refresh();
  }

  private static record TransposedStep(int modelRow, int step, org.deluge.model.StepData data) {}

  // ── GridScrollController.GridContext Implementation ──
  @Override
  public void setScrollOffsetX(int val) {
    this.scrollOffsetX = val;
  }

  @Override
  public boolean isSynthTrack() {
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      return projectModel.getTracks().get(editedModelTrack)
          instanceof org.deluge.model.SynthTrackModel;
    }
    return false;
  }

  @Override
  public int getRowsInView() {
    return this.gridMode.rows;
  }

  @Override
  public int getTrackLength() {
    return (bridge != null) ? bridge.getTrackLength(baseTrackId) : stepCount;
  }

  @Override
  public java.util.List<Integer> getFoldedPitches() {
    return this.foldedPitches;
  }

  @Override
  public boolean isFoldMode() {
    return this.foldMode;
  }

  @Override
  public boolean isRefreshInProgress() {
    return this.refreshInProgress;
  }
}
