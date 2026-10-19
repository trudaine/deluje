package org.deluge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.deluge.project.PresetFinder;

/** The root model for a full Deluge project/song. */
public class ProjectModel implements org.deluge.firmware2.TuningProvider {
  private static final Logger LOGGER = Logger.getLogger(ProjectModel.class.getName());

  // C Song::fillModeActive (song.h:272-273), read by the sequencer's fill condition
  // (instrument_clip.cpp:878-886). Static because the C reads it through the global currentSong.
  private static volatile boolean fillModeActive = false;

  public static boolean isFillModeActive() {
    return fillModeActive;
  }

  public static void changeFillMode(boolean on) {
    fillModeActive = on;
  }

  // Undo/redo stack — cleared on new project load
  private final UndoRedoStack undoRedoStack = new UndoRedoStack(64);

  public UndoRedoStack getUndoRedoStack() {
    return undoRedoStack;
  }

  // Global Sequencer state
  private float bpm = 120.0f;
  private float swing = 0.5f;
  private int timeSigNum = 4;
  private int timeSigDenom = 4;
  private int transpose = 0;
  private float humanize = 0.0f;
  private boolean recording = false;

  private String key = "0";
  private String scale = "Major";

  private float masterVolume = 1.0f;
  private float masterPan = 0.0f;
  private float masterDelay = 0.3f;

  // ── Song-level reverb params (C song.cpp:179-188, sidechain.cpp:29-30) ──
  private float reverbRoomSize = 0.6f;
  private float reverbDampening = 0.72f;
  private float reverbWidth = 1.0f;
  private float reverbHpf = 0.0f;
  private float reverbLpf = 1.0f;
  private float reverbPan = 0.0f;
  private int reverbModel = 0;
  private float reverbCompressorAttack = 327244.0f / 2147483647.0f;
  private float reverbCompressorRelease = 936.0f / 2147483647.0f;
  // File syncLevel 5 -> internal SYNC_LEVEL_8TH (4) at default inputTickMagnitude 2.
  private int reverbCompressorSyncLevel = 5;
  private float reverbCompHpf = 0.0f;
  private float reverbCompBlend = 0.5f;
  private float reverbCompressorShape = -601295438.0f / 2147483647.0f;
  // C song.cpp:186 — getParamFromUserValue(STATIC_SIDECHAIN_VOLUME, -1) = -21474836 (< 0 = auto).
  private float reverbCompressorVolume = -21474836.0f / 2147483647.0f;

  // ── Song-level delay params ──
  private int delayPingPong = 0;
  private int delayAnalog = 0;
  private int delaySyncLevel = 6;
  private int delaySyncType = 0;

  // ── Song-level sidechain params ──
  private float sidechainAttack = 0.0f;
  private float sidechainRelease = 0.0f;
  private int sidechainSyncLevel = 0;
  private int sidechainSyncType = 0;

  // ── Song-level compressor params (C rms_feedback.cpp:22-31) ──
  private float compressorAttack = (float) (5 << 24) / 2147483647.0f;
  private float compressorRelease = (float) (5 << 24) / 2147483647.0f;
  private float compressorThreshold = 0.0f;
  private float compressorRatio = (float) (64 << 24) / 2147483647.0f;
  private float compressorHpf = 0.0f;
  private float compressorBlend = 1.0f;

  // ── Song-level stutter params (C stutterer.h:28-33, mod_controllable_audio.h:104) ──
  private boolean songStutterQuantized = true;
  private boolean songStutterReversed = false;
  private boolean songStutterPingPong = false;

  // ── SongParams values (from <songParams> element) ──
  private float songParamVolume = 0.5f;
  private float songParamPan = 0.0f;
  private float songParamReverbAmount = 0.5f;
  private float songParamDelayRate = 0.0f;
  private float songParamDelayFeedback = 0.0f;
  private float songParamSidechainShape = 0.5f;
  private float songParamSidechainVolume = 0.0f;
  private float songParamStutterRate = 0.0f;
  private float songParamSampleRateReduction = 0.0f;
  private float songParamBitCrush = 0.0f;
  private float songParamModFXRate = 0.0f;
  private float songParamModFXDepth = 0.0f;
  private float songParamModFXOffset = 0.0f;
  private float songParamModFXFeedback = 0.0f;
  private float songParamCompressorThreshold = 0.0f;
  private float songParamLpfMorph = 0.0f;
  private float songParamHpfMorph = 0.0f;
  private float songParamLpfFrequency = 20000.0f;
  private float songParamLpfResonance = 0.0f;
  private float songParamHpfFrequency = 20.0f;
  private float songParamHpfResonance = 0.0f;
  // C mod_controllable_audio.cpp:61-63 — Song globalEffectable defaults to 24dB / HPLadder / H2L.
  private FilterMode songLpfMode = FilterMode.LADDER_24;
  private FilterMode songHpfMode = FilterMode.LADDER_12;
  private int songFilterRoute = 0; // 0 = HIGH_TO_LOW, 1 = LOW_TO_HIGH, 2 = PARALLEL
  private float songParamEqBass = 0.0f;
  private float songParamEqTreble = 0.0f;
  // C mod_controllable_audio.cpp:110-111 — UNPATCHED_BASS_FREQ / UNPATCHED_TREBLE_FREQ init to 0
  // (0x00000000, unipolar 0.5f).
  private float songParamEqBassFrequency = 0.5f;
  private float songParamEqTrebleFrequency = 0.5f;
  private final Map<String, List<org.deluge.modulation.automation.ParamNode>>
      songParamAutomationCurves = new java.util.LinkedHashMap<>();
  private final Map<String, String> rawSongParamHex = new java.util.LinkedHashMap<>();
  private String modFXCurrentParam = "offset";
  private String currentFilterType = "lpf";
  // C global_effectable.cpp:47, mod_controllable_audio.cpp:69 — song-level modFXType & clipping.
  private String songModFXType = "none";
  private int songClippingAmount = 0;

  // ── Scale info ──
  private int userScale = 0;
  private int disabledPresetScales = 0;

  // ── Mode notes (scale note mask, 12 semitones) ──
  private boolean[] modeNotes = null;

  // ── Microtuning & Custom Temperaments ──
  private int octaveNumMicrotonalNotes = 12;
  private boolean isEqualTemperament = true;
  private double baseFrequencyHz = 440.0;
  private final int[] centAdjustForNotesInTemperament = new int[64];
  private final double[] customRatios = new double[64];

  // ── TuningProvider & Sequencer calculations ──
  private final transient int[] noteFrequencyTable = new int[64];
  private final transient int[] noteIntervalTable = new int[64];

  private transient int tempoSamples;
  private transient int lastSwungTickActioned = 0;
  private transient int swungTicksTilNextEvent = Integer.MAX_VALUE;
  private transient boolean inArrangerMode = false;
  private transient int swingAmount = 0; // -49 to 49
  private transient int swingInterval = 6; // SyncLevel (e.g. 1/16)
  // C song.h:481 — cached integer tempo (BPM * 100) for updateBPMFromAutomation (song.cpp:6062).
  private transient int intBpm = 12000;
  private transient org.deluge.modulation.params.ParamManager paramManager =
      new org.deluge.modulation.params.ParamManager();

  public org.deluge.modulation.params.ParamManager getSongParamManager() {
    return paramManager;
  }

  public void calculateNoteFrequencies() {
    int baseFreqQ24 = (int) Math.round((baseFrequencyHz / 440.0) * 1027294024.0);
    int maxLen =
        Math.min(
            Math.min(centAdjustForNotesInTemperament.length, noteFrequencyTable.length),
            Math.min(noteIntervalTable.length, customRatios.length));
    int count = Math.max(1, Math.min(octaveNumMicrotonalNotes, maxLen));
    int numNotesTimes100 = count * 100;
    if (isEqualTemperament) {
      for (int i = 0; i < count; i++) {
        int withCents = i * 100 + centAdjustForNotesInTemperament[i];
        double ratio = Math.pow(2.0, (double) withCents / numNotesTimes100);
        noteFrequencyTable[i] = (int) Math.round(ratio * baseFreqQ24);
        noteIntervalTable[i] = (int) Math.round(ratio * 1073741824.0);
      }
    } else {
      for (int i = 0; i < count; i++) {
        int withCents = i * 100 + centAdjustForNotesInTemperament[i];
        double ratio =
            customRatios[i] > 0.0
                ? customRatios[i]
                : Math.pow(2.0, (double) withCents / numNotesTimes100);
        noteFrequencyTable[i] = (int) Math.round(ratio * baseFreqQ24);
        noteIntervalTable[i] = (int) Math.round(ratio * 1073741824.0);
      }
    }
  }

  // --- TuningProvider Implementation ---

  @Override
  public int octaveOf(int noteCode) {
    return Math.floorDiv(noteCode, octaveNumMicrotonalNotes);
  }

  @Override
  public int noteWithinOctaveOf(int noteCode) {
    return Math.floorMod(noteCode, octaveNumMicrotonalNotes);
  }

  @Override
  public int noteIntervalRatio(int noteWithinOctave) {
    if (noteWithinOctave >= 0 && noteWithinOctave < 64) {
      return noteIntervalTable[noteWithinOctave];
    }
    return 1073741824;
  }

  @Override
  public int noteFrequencyRatio(int noteWithinOctave) {
    if (noteWithinOctave >= 0 && noteWithinOctave < 64) {
      return noteFrequencyTable[noteWithinOctave];
    }
    return 1027294024;
  }

  // --- Sequencer Getters/Setters & Tick Runner ---

  public int getTempoSamples() {
    return tempoSamples;
  }

  public void setTempoSamples(int samples) {
    this.tempoSamples = samples;
  }

  public float getTempoBPM() {
    return bpm;
  }

  public void setTempoBPM(float bpm) {
    this.bpm = bpm;
  }

  public int getSwingAmount() {
    return swingAmount;
  }

  public void setSwingAmount(int amt) {
    this.swingAmount = amt;
  }

  public int getSwingInterval() {
    return swingInterval;
  }

  public void setSwingInterval(int interval) {
    this.swingInterval = interval;
  }

  public boolean isInArrangerMode() {
    return inArrangerMode;
  }

  public void setInArrangerMode(boolean arr) {
    this.inArrangerMode = arr;
  }

  public int getSwungTicksTilNextEvent() {
    return swungTicksTilNextEvent;
  }

  public void setSwungTicksTilNextEvent(int ticks) {
    this.swungTicksTilNextEvent = ticks;
  }

  public int getLastSwungTickActioned() {
    return lastSwungTickActioned;
  }

  public void setLastSwungTickActioned(int tick) {
    this.lastSwungTickActioned = tick;
  }

  public void doTickForward(int posIncrement) {
    lastSwungTickActioned += posIncrement;
    swungTicksTilNextEvent = Integer.MAX_VALUE;

    // C arrangement.cpp:120-128, song.cpp:5822 — step global song automation with infinite loop.
    if (paramManager.mightContainAutomation()) {
      paramManager.processCurrentPos(
          posIncrement, lastSwungTickActioned, Integer.MAX_VALUE, false, false, false);
      applyAutomatedSongParams();
      swungTicksTilNextEvent = Math.min(swungTicksTilNextEvent, paramManager.ticksTilNextEvent);
    }

    for (TrackModel track : tracks) {
      ClipModel activeClip = track.getActiveClip();
      if (activeClip != null) {
        activeClip.processCurrentPos(posIncrement);
        swungTicksTilNextEvent =
            Math.min(swungTicksTilNextEvent, activeClip.getTicksTilNextEvent());
      }
    }
  }

  public List<ClipModel> getClips() {
    List<ClipModel> all = new java.util.ArrayList<>();
    for (TrackModel t : getTracks()) {
      if (t.getActiveClip() != null) {
        all.add(t.getActiveClip());
      }
    }
    return all;
  }

  /**
   * C Song::getLongestClip (song.cpp:4420-4437): longest active (or any, when {@code
   * includeInactive} is true) session clip across all tracks.
   */
  public ClipModel getLongestClip(boolean includeInactive) {
    ClipModel longestClip = null;
    for (TrackModel t : getTracks()) {
      for (ClipModel clip : t.getClips()) {
        boolean active = t.getActiveClip() == clip && clip.isActiveInSession() && !t.isMuted();
        if (includeInactive || active) {
          if (longestClip == null || clip.getLoopLength() > longestClip.getLoopLength()) {
            longestClip = clip;
          }
        }
      }
    }
    return longestClip;
  }

  /**
   * C Song::getLongestActiveClipWithMultipleOrFactorLength (song.cpp:4441-4470): finds the longest
   * active session clip whose loop length is a multiple or factor of {@code targetLength}, skipping
   * {@code LaunchStyle::FILL} clips (song.cpp:4450).
   */
  public ClipModel getLongestActiveClipWithMultipleOrFactorLength(
      int targetLength, boolean revertToAnyActiveClipIfNone, ClipModel excludeClip) {
    ClipModel foundClip = null;
    boolean foundClipIsFitting = false;
    int foundClipLength = 0;

    for (TrackModel t : getTracks()) {
      if (t.isMuted()) continue;
      ClipModel clip = t.getActiveClip();
      if (clip != null
          && clip != excludeClip
          && clip.isActiveInSession()
          && !"fill".equals(clip.getLaunchStyle())) {
        int clipLength = clip.getLoopLength();
        if (clipLength <= 0 || targetLength <= 0) continue;
        if (clipLength == targetLength
            || (clipLength > targetLength
                && Integer.remainderUnsigned(clipLength, targetLength) == 0)
            || (targetLength > clipLength
                && Integer.remainderUnsigned(targetLength, clipLength) == 0)) {
          if (!foundClipIsFitting || foundClip == null || clipLength > foundClipLength) {
            foundClip = clip;
            foundClipIsFitting = true;
            foundClipLength = clipLength;
          }
        } else if (revertToAnyActiveClipIfNone && !foundClipIsFitting) {
          foundClip = clip;
        }
      }
    }
    return foundClip;
  }

  /**
   * C Song::turnSoloingIntoJustPlaying (song.cpp:3268-3300): when clearing arming across session
   * clips, preserves {@code armState} on {@code FILL} and {@code ONCE} clips (song.cpp:3275, 3293).
   */
  public void turnSoloingIntoJustPlaying(boolean getRidOfArmingToo) {
    if (!getRidOfArmingToo) return;
    for (TrackModel t : getTracks()) {
      for (ClipModel clip : t.getClips()) {
        if (!"fill".equals(clip.getLaunchStyle()) && !"once".equals(clip.getLaunchStyle())) {
          clip.setArmState(ArmState.OFF);
        }
      }
    }
  }

  public static ProjectModel createDefaultProject() {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);

    java.io.File synthsDir = org.deluge.project.PreferencesManager.getSynthsDir();
    java.io.File kitsDir = org.deluge.project.PreferencesManager.getKitsDir();

    // 1. Load first available Synth Preset, or fallback to default Saw
    SynthTrackModel synthTrack = null;
    try {
      java.io.File firstSynthFile = PresetFinder.findFirstPreset(synthsDir);
      if (firstSynthFile != null && firstSynthFile.exists()) {
        synthTrack = org.deluge.xml.DelugeXmlParser.parseSynth(firstSynthFile);
      }
    } catch (Exception e) {
      LOGGER.warning("[ProjectModel] Could not parse first Synth preset: " + e.getMessage());
    }

    if (synthTrack == null) {
      synthTrack = new SynthTrackModel("Synth 1");
      synthTrack.setOsc1Type("SAW");
      synthTrack.setOsc2Type("NONE");
      synthTrack.setOscMix(1.0f);
      synthTrack.setLpfFreq(20000.0f);
      synthTrack.setLpfRes(0.0f);
      synthTrack.setVolume(0.5f);
    }
    if (synthTrack.getClips().isEmpty()) {
      synthTrack.addClip(new ClipModel("CLIP 1", 8, 16));
    }
    project.addTrack(synthTrack);

    return project;
  }

  public float getMasterVolume() {
    return masterVolume;
  }

  public void setMasterVolume(float vol) {
    float old = this.masterVolume;
    this.masterVolume = vol;
    if (old != this.masterVolume) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "masterVolume", old, this.masterVolume));
    }
    notifyMasterVolumeChanged(vol);
  }

  public float getMasterPan() {
    return masterPan;
  }

  public void setMasterPan(float pan) {
    float old = this.masterPan;
    this.masterPan = pan;
    if (old != this.masterPan) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "masterPan", old, this.masterPan));
    }
    notifyMasterPanChanged(pan);
  }

  public float getMasterDelay() {
    return masterDelay;
  }

  public void setMasterDelay(float del) {
    float old = this.masterDelay;
    this.masterDelay = del;
    if (old != this.masterDelay) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "masterDelay", old, this.masterDelay));
    }
    notifyDelayChanged();
  }

  // Track Models (Active in Clip mode)
  private final List<TrackModel> tracks = new ArrayList<>();

  // Song / Arranger state
  private final Map<String, PatternModel> patterns = new HashMap<>();
  private final List<SongSection> songSections = new ArrayList<>();
  private final List<ArrangerClip> arrangerTimeline = new ArrayList<>();

  // When true, the saved song boots straight into the Arranger view (C inArrangementView), so
  // arrangement (clipInstances) playback drives the song instead of all session clips at once.
  private boolean bootInArrangementView = false;

  public boolean isBootInArrangementView() {
    return bootInArrangementView;
  }

  public void setBootInArrangementView(boolean v) {
    this.bootInArrangementView = v;
  }

  // ── View & Song-State Parity Fields (C++ Parity Gaps) ──
  private boolean affectEntire = false;
  private boolean arrangementAutoScrollOn = true;
  private int inputTickMagnitude = 2;
  private int timePerTimerTick = 229;
  private int timerTickFraction = -1342177280;
  private int xScroll = 0;
  private int xZoom = 24;
  private int xScrollArrangementView = 0;
  private int xZoomArrangementView = 24;
  private int yScrollArrangementView = 0;
  private int yScrollSongView = 0;
  private int yScroll = 0;
  private int yScrollKeyboard = 0;
  private int sessionLayout = 0;
  private int songGridScrollX = 0;
  private int songGridScrollY = 0;
  private int keyboardLayout = 0;
  private int keyboardRowInterval = 0;
  private boolean inKeyMode = false;
  private int inKeyRowInterval = 0;
  private int inKeyScrollOffset = 0;
  private int drumsScrollOffset = 0;
  private int drumsZoomLevel = 0;
  private int drumsEdgeSize = 0;
  private int anyOfMelodicKitPercussion = 0;
  private int numClips = 0;

  public boolean isAffectEntire() {
    return affectEntire;
  }

  public void setAffectEntire(boolean v) {
    this.affectEntire = v;
  }

  public boolean isArrangementAutoScrollOn() {
    return arrangementAutoScrollOn;
  }

  public void setArrangementAutoScrollOn(boolean v) {
    this.arrangementAutoScrollOn = v;
  }

  public int getInputTickMagnitude() {
    return inputTickMagnitude;
  }

  public void setInputTickMagnitude(int v) {
    this.inputTickMagnitude = v;
  }

  public int getTimePerTimerTick() {
    return timePerTimerTick;
  }

  public void setTimePerTimerTick(int v) {
    this.timePerTimerTick = v;
  }

  public int getTimerTickFraction() {
    return timerTickFraction;
  }

  public void setTimerTickFraction(int v) {
    this.timerTickFraction = v;
  }

  public int getXScroll() {
    return xScroll;
  }

  public void setXScroll(int v) {
    this.xScroll = v;
  }

  public int getXZoom() {
    return xZoom;
  }

  public void setXZoom(int v) {
    this.xZoom = v;
  }

  public int getXScrollArrangementView() {
    return xScrollArrangementView;
  }

  public void setXScrollArrangementView(int v) {
    this.xScrollArrangementView = v;
  }

  public int getXZoomArrangementView() {
    return xZoomArrangementView;
  }

  public void setXZoomArrangementView(int v) {
    this.xZoomArrangementView = v;
  }

  public int getYScrollArrangementView() {
    return yScrollArrangementView;
  }

  public void setYScrollArrangementView(int v) {
    this.yScrollArrangementView = v;
  }

  public int getYScrollSongView() {
    return yScrollSongView;
  }

  public void setYScrollSongView(int v) {
    this.yScrollSongView = v;
  }

  public int getYScroll() {
    return yScroll;
  }

  public void setYScroll(int v) {
    this.yScroll = v;
  }

  public int getYScrollKeyboard() {
    return yScrollKeyboard;
  }

  public void setYScrollKeyboard(int v) {
    this.yScrollKeyboard = v;
  }

  public int getSessionLayout() {
    return sessionLayout;
  }

  public void setSessionLayout(int v) {
    this.sessionLayout = v;
  }

  public int getSongGridScrollX() {
    return songGridScrollX;
  }

  public void setSongGridScrollX(int v) {
    this.songGridScrollX = v;
  }

  public int getSongGridScrollY() {
    return songGridScrollY;
  }

  public void setSongGridScrollY(int v) {
    this.songGridScrollY = v;
  }

  public int getKeyboardLayout() {
    return keyboardLayout;
  }

  public void setKeyboardLayout(int v) {
    this.keyboardLayout = v;
  }

  public int getKeyboardRowInterval() {
    return keyboardRowInterval;
  }

  public void setKeyboardRowInterval(int v) {
    this.keyboardRowInterval = v;
  }

  public boolean isInKeyMode() {
    return inKeyMode;
  }

  public void setInKeyMode(boolean v) {
    this.inKeyMode = v;
  }

  public int getInKeyRowInterval() {
    return inKeyRowInterval;
  }

  public void setInKeyRowInterval(int v) {
    this.inKeyRowInterval = v;
  }

  public int getInKeyScrollOffset() {
    return inKeyScrollOffset;
  }

  public void setInKeyScrollOffset(int v) {
    this.inKeyScrollOffset = v;
  }

  public int getDrumsScrollOffset() {
    return drumsScrollOffset;
  }

  public void setDrumsScrollOffset(int v) {
    this.drumsScrollOffset = v;
  }

  public int getDrumsZoomLevel() {
    return drumsZoomLevel;
  }

  public void setDrumsZoomLevel(int v) {
    this.drumsZoomLevel = v;
  }

  public int getDrumsEdgeSize() {
    return drumsEdgeSize;
  }

  public void setDrumsEdgeSize(int v) {
    this.drumsEdgeSize = v;
  }

  public int getAnyOfMelodicKitPercussion() {
    return anyOfMelodicKitPercussion;
  }

  public void setAnyOfMelodicKitPercussion(int v) {
    this.anyOfMelodicKitPercussion = v;
  }

  public int getNumClips() {
    return numClips;
  }

  public void setNumClips(int v) {
    this.numClips = v;
  }

  public ProjectModel() {}

  public boolean isRecording() {
    return recording;
  }

  public void setRecording(boolean recording) {
    this.recording = recording;
  }

  public float getBpm() {
    return bpm;
  }

  public void setBpm(float bpm) {
    float old = this.bpm;
    // C song.cpp:3120, playback_handler.cpp:2265: tempoBPM > 0 up to ~20,000 BPM (no 300 BPM cap).
    this.bpm = Math.max(1.0f, Math.min(20000.0f, bpm));
    this.intBpm = (int) (this.bpm * 100.0f);
    rawSongParamHex.remove("tempo");
    paramManager.setUnpatchedValue(org.deluge.firmware2.Param.UNPATCHED_TEMPO, this.intBpm);
    org.deluge.modulation.automation.AutoParam tempoParam =
        paramManager.getAutomatedParam(org.deluge.firmware2.Param.UNPATCHED_TEMPO);
    if (tempoParam != null) {
      tempoParam.currentValue = this.intBpm;
      tempoParam.resetInterpolationIncrement();
    }
    if (old != this.bpm) {
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "bpm", old, this.bpm));
    }
    notifyBpmChanged(this.bpm);
  }

  public float getSwing() {
    return swing;
  }

  public void setSwing(float swing) {
    float old = this.swing;
    this.swing = Math.max(0.0f, Math.min(1.0f, swing));
    if (old != this.swing) {
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "swing", old, this.swing));
    }
    notifySwingChanged(this.swing);
  }

  public int getTimeSigNum() {
    return timeSigNum;
  }

  public void setTimeSigNum(int timeSigNum) {
    int old = this.timeSigNum;
    this.timeSigNum = Math.max(1, timeSigNum);
    if (old != this.timeSigNum) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "timeSigNum", (float) old, (float) this.timeSigNum));
    }
  }

  public int getTimeSigDenom() {
    return timeSigDenom;
  }

  public void setTimeSigDenom(int timeSigDenom) {
    int old = this.timeSigDenom;
    this.timeSigDenom = Math.max(1, timeSigDenom);
    if (old != this.timeSigDenom) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "timeSigDenom", (float) old, (float) this.timeSigDenom));
    }
  }

  public int getTranspose() {
    return transpose;
  }

  public void setTranspose(int transpose) {
    int old = this.transpose;
    this.transpose = transpose;
    if (old != this.transpose) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "transpose", (float) old, (float) this.transpose));
    }
    notifyTransposeChanged(transpose);
  }

  public float getHumanize() {
    return humanize;
  }

  public void setHumanize(float humanize) {
    float old = this.humanize;
    this.humanize = Math.max(0.0f, Math.min(1.0f, humanize));
    if (old != this.humanize) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "humanize", old, this.humanize));
    }
    notifyHumanizeChanged(this.humanize);
  }

  public interface ProjectListener {
    void onTrackListChanged();

    void onBpmChanged(float bpm);

    void onSwingChanged(float swing);

    void onMasterVolumeChanged(float vol);

    void onMasterPanChanged(float pan);

    void onKeyChanged(String key);

    void onScaleChanged(String scale);

    void onTransposeChanged(int transpose);

    void onHumanizeChanged(float humanize);

    void onReverbChanged();

    void onDelayChanged();

    void onSidechainChanged();

    void onCompressorChanged();

    void onSongParamsChanged();

    void onScalesChanged();
  }

  public static class ProjectAdapter implements ProjectListener {
    @Override
    public void onTrackListChanged() {}

    @Override
    public void onBpmChanged(float bpm) {}

    @Override
    public void onSwingChanged(float swing) {}

    @Override
    public void onMasterVolumeChanged(float vol) {}

    @Override
    public void onMasterPanChanged(float pan) {}

    @Override
    public void onKeyChanged(String key) {}

    @Override
    public void onScaleChanged(String scale) {}

    @Override
    public void onTransposeChanged(int transpose) {}

    @Override
    public void onHumanizeChanged(float humanize) {}

    @Override
    public void onReverbChanged() {}

    @Override
    public void onDelayChanged() {}

    @Override
    public void onSidechainChanged() {}

    @Override
    public void onCompressorChanged() {}

    @Override
    public void onSongParamsChanged() {}

    @Override
    public void onScalesChanged() {}
  }

  private final List<ProjectListener> listeners = new ArrayList<>();

  public void addProjectListener(ProjectListener l) {
    listeners.add(l);
  }

  private void notifyTrackListChanged() {
    for (ProjectListener l : listeners) {
      l.onTrackListChanged();
    }
  }

  private void notifyBpmChanged(float bpm) {
    for (ProjectListener l : listeners) {
      l.onBpmChanged(bpm);
    }
  }

  private void notifySwingChanged(float swing) {
    for (ProjectListener l : listeners) {
      l.onSwingChanged(swing);
    }
  }

  private void notifyMasterVolumeChanged(float vol) {
    for (ProjectListener l : listeners) {
      l.onMasterVolumeChanged(vol);
    }
  }

  private void notifyMasterPanChanged(float pan) {
    for (ProjectListener l : listeners) {
      l.onMasterPanChanged(pan);
    }
  }

  private void notifyKeyChanged(String key) {
    for (ProjectListener l : listeners) {
      l.onKeyChanged(key);
    }
  }

  private void notifyScaleChanged(String scale) {
    for (ProjectListener l : listeners) {
      l.onScaleChanged(scale);
    }
  }

  private void notifyTransposeChanged(int transpose) {
    for (ProjectListener l : listeners) {
      l.onTransposeChanged(transpose);
    }
  }

  private void notifyHumanizeChanged(float humanize) {
    for (ProjectListener l : listeners) {
      l.onHumanizeChanged(humanize);
    }
  }

  private void notifyReverbChanged() {
    for (ProjectListener l : listeners) {
      l.onReverbChanged();
    }
  }

  private void notifyDelayChanged() {
    for (ProjectListener l : listeners) {
      l.onDelayChanged();
    }
  }

  private void notifySidechainChanged() {
    for (ProjectListener l : listeners) {
      l.onSidechainChanged();
    }
  }

  private void notifyCompressorChanged() {
    for (ProjectListener l : listeners) {
      l.onCompressorChanged();
    }
  }

  private void notifySongParamsChanged() {
    for (ProjectListener l : listeners) {
      l.onSongParamsChanged();
    }
  }

  private void notifyScalesChanged() {
    for (ProjectListener l : listeners) {
      l.onScalesChanged();
    }
  }

  public List<TrackModel> getTracks() {
    return tracks;
  }

  public void addTrack(TrackModel track) {
    this.tracks.add(track);
    notifyTrackListChanged();
  }

  public void addTrack(int index, TrackModel track) {
    this.tracks.add(index, track);
    notifyTrackListChanged();
  }

  public void removeTrack(TrackModel track) {
    this.tracks.remove(track);
    notifyTrackListChanged();
  }

  public void moveTrackUp(int index) {
    if (index > 0 && index < tracks.size()) {
      TrackModel t = tracks.remove(index);
      tracks.add(index - 1, t);
      notifyTrackListChanged();
    }
  }

  public void moveTrackDown(int index) {
    if (index >= 0 && index < tracks.size() - 1) {
      TrackModel t = tracks.remove(index);
      tracks.add(index + 1, t);
      notifyTrackListChanged();
    }
  }

  public Map<String, PatternModel> getPatterns() {
    return patterns;
  }

  public void addPattern(PatternModel pattern) {
    this.patterns.put(pattern.getId(), pattern);
  }

  public List<SongSection> getSongSections() {
    return songSections;
  }

  public void addSongSection(SongSection section) {
    this.songSections.add(section);
  }

  public List<ArrangerClip> getArrangerTimeline() {
    return arrangerTimeline;
  }

  public void addArrangerClip(ArrangerClip clip) {
    this.arrangerTimeline.add(clip);
  }

  // ── Reverb getters/setters ──

  public float getReverbRoomSize() {
    return reverbRoomSize;
  }

  public void setReverbRoomSize(float v) {
    float old = this.reverbRoomSize;
    this.reverbRoomSize = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "reverbRoomSize", old, v));
    notifyReverbChanged();
  }

  public float getReverbDampening() {
    return reverbDampening;
  }

  public void setReverbDampening(float v) {
    float old = this.reverbDampening;
    this.reverbDampening = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "reverbDampening", old, v));
    notifyReverbChanged();
  }

  public float getReverbWidth() {
    return reverbWidth;
  }

  public void setReverbWidth(float v) {
    float old = this.reverbWidth;
    this.reverbWidth = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "reverbWidth", old, v));
    notifyReverbChanged();
  }

  public float getReverbHpf() {
    return reverbHpf;
  }

  public void setReverbHpf(float v) {
    float old = this.reverbHpf;
    this.reverbHpf = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "reverbHpf", old, v));
    notifyReverbChanged();
  }

  public float getReverbLpf() {
    return reverbLpf;
  }

  public void setReverbLpf(float v) {
    float old = this.reverbLpf;
    this.reverbLpf = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "reverbLpf", old, v));
    notifyReverbChanged();
  }

  public float getReverbPan() {
    return reverbPan;
  }

  public void setReverbPan(float v) {
    float old = this.reverbPan;
    this.reverbPan = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "reverbPan", old, v));
    notifyReverbChanged();
  }

  public int getReverbModel() {
    return reverbModel;
  }

  public void setReverbModel(int v) {
    int old = this.reverbModel;
    this.reverbModel = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "reverbModel", (float) old, (float) v));
    notifyReverbChanged();
  }

  public float getReverbCompressorAttack() {
    return reverbCompressorAttack;
  }

  public void setReverbCompressorAttack(float v) {
    float old = this.reverbCompressorAttack;
    this.reverbCompressorAttack = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "reverbCompressorAttack", old, v));
    notifyReverbChanged();
  }

  public float getReverbCompressorRelease() {
    return reverbCompressorRelease;
  }

  public void setReverbCompressorRelease(float v) {
    float old = this.reverbCompressorRelease;
    this.reverbCompressorRelease = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "reverbCompressorRelease", old, v));
    notifyReverbChanged();
  }

  public int getReverbCompressorSyncLevel() {
    return reverbCompressorSyncLevel;
  }

  public void setReverbCompressorSyncLevel(int v) {
    int old = this.reverbCompressorSyncLevel;
    this.reverbCompressorSyncLevel = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "reverbCompressorSyncLevel", (float) old, (float) v));
    notifyReverbChanged();
  }

  public float getReverbCompHpf() {
    return reverbCompHpf;
  }

  public void setReverbCompHpf(float v) {
    float old = this.reverbCompHpf;
    this.reverbCompHpf = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "reverbCompHpf", old, v));
    notifyReverbChanged();
  }

  public float getReverbCompBlend() {
    return reverbCompBlend;
  }

  public void setReverbCompBlend(float v) {
    float old = this.reverbCompBlend;
    this.reverbCompBlend = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "reverbCompBlend", old, v));
    notifyReverbChanged();
  }

  public float getReverbCompressorShape() {
    return reverbCompressorShape;
  }

  public void setReverbCompressorShape(float v) {
    float old = this.reverbCompressorShape;
    this.reverbCompressorShape = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "reverbCompressorShape", old, v));
    notifyReverbChanged();
  }

  public float getReverbCompressorVolume() {
    return reverbCompressorVolume;
  }

  public void setReverbCompressorVolume(float v) {
    float old = this.reverbCompressorVolume;
    this.reverbCompressorVolume = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "reverbCompressorVolume", old, v));
    notifyReverbChanged();
  }

  // ── Delay getters/setters ──

  public int getDelayPingPong() {
    return delayPingPong;
  }

  public void setDelayPingPong(int v) {
    int old = this.delayPingPong;
    this.delayPingPong = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "delayPingPong", (float) old, (float) v));
    notifyDelayChanged();
  }

  public int getDelayAnalog() {
    return delayAnalog;
  }

  public void setDelayAnalog(int v) {
    int old = this.delayAnalog;
    this.delayAnalog = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "delayAnalog", (float) old, (float) v));
    notifyDelayChanged();
  }

  public int getDelaySyncLevel() {
    return delaySyncLevel;
  }

  public void setDelaySyncLevel(int v) {
    int old = this.delaySyncLevel;
    this.delaySyncLevel = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "delaySyncLevel", (float) old, (float) v));
    notifyDelayChanged();
  }

  public int getDelaySyncType() {
    return delaySyncType;
  }

  public void setDelaySyncType(int v) {
    int old = this.delaySyncType;
    this.delaySyncType = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "delaySyncType", (float) old, (float) v));
    notifyDelayChanged();
  }

  // ── Sidechain getters/setters ──

  public float getSidechainAttack() {
    return sidechainAttack;
  }

  public void setSidechainAttack(float v) {
    float old = this.sidechainAttack;
    this.sidechainAttack = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "sidechainAttack", old, v));
    notifySidechainChanged();
  }

  public float getSidechainRelease() {
    return sidechainRelease;
  }

  public void setSidechainRelease(float v) {
    float old = this.sidechainRelease;
    this.sidechainRelease = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "sidechainRelease", old, v));
    notifySidechainChanged();
  }

  public int getSidechainSyncLevel() {
    return sidechainSyncLevel;
  }

  public void setSidechainSyncLevel(int v) {
    int old = this.sidechainSyncLevel;
    this.sidechainSyncLevel = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "sidechainSyncLevel", (float) old, (float) v));
    notifySidechainChanged();
  }

  public int getSidechainSyncType() {
    return sidechainSyncType;
  }

  public void setSidechainSyncType(int v) {
    int old = this.sidechainSyncType;
    this.sidechainSyncType = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "sidechainSyncType", (float) old, (float) v));
    notifySidechainChanged();
  }

  // ── Compressor getters/setters ──

  public float getCompressorAttack() {
    return compressorAttack;
  }

  public void setCompressorAttack(float v) {
    float old = this.compressorAttack;
    this.compressorAttack = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "compressorAttack", old, v));
    notifyCompressorChanged();
  }

  public float getCompressorRelease() {
    return compressorRelease;
  }

  public void setCompressorRelease(float v) {
    float old = this.compressorRelease;
    this.compressorRelease = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "compressorRelease", old, v));
    notifyCompressorChanged();
  }

  public float getCompressorThreshold() {
    return compressorThreshold;
  }

  public void setCompressorThreshold(float v) {
    float old = this.compressorThreshold;
    this.compressorThreshold = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "compressorThreshold", old, v));
    notifyCompressorChanged();
  }

  public float getCompressorRatio() {
    return compressorRatio;
  }

  public void setCompressorRatio(float v) {
    float old = this.compressorRatio;
    this.compressorRatio = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "compressorRatio", old, v));
    notifyCompressorChanged();
  }

  public float getCompressorHpf() {
    return compressorHpf;
  }

  public void setCompressorHpf(float v) {
    float old = this.compressorHpf;
    this.compressorHpf = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "compressorHpf", old, v));
    notifyCompressorChanged();
  }

  public float getCompressorBlend() {
    return compressorBlend;
  }

  public void setCompressorBlend(float v) {
    float old = this.compressorBlend;
    this.compressorBlend = v;
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "compressorBlend", old, v));
    notifyCompressorChanged();
  }

  // ── Song-level stutter getters/setters (C stutterer.h:28-33, mod_controllable_audio.cpp:481-486,
  // 760-785) ──

  public boolean isSongStutterQuantized() {
    return songStutterQuantized;
  }

  public void setSongStutterQuantized(boolean v) {
    boolean old = this.songStutterQuantized;
    this.songStutterQuantized = v;
    if (old != v) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "songStutterQuantized", old ? 1.0f : 0.0f, v ? 1.0f : 0.0f));
      notifySongParamsChanged();
    }
  }

  public boolean isSongStutterReversed() {
    return songStutterReversed;
  }

  public void setSongStutterReversed(boolean v) {
    boolean old = this.songStutterReversed;
    this.songStutterReversed = v;
    if (old != v) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "songStutterReversed", old ? 1.0f : 0.0f, v ? 1.0f : 0.0f));
      notifySongParamsChanged();
    }
  }

  public boolean isSongStutterPingPong() {
    return songStutterPingPong;
  }

  public void setSongStutterPingPong(boolean v) {
    boolean old = this.songStutterPingPong;
    this.songStutterPingPong = v;
    if (old != v) {
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "songStutterPingPong", old ? 1.0f : 0.0f, v ? 1.0f : 0.0f));
      notifySongParamsChanged();
    }
  }

  // ── SongParams getters/setters ──

  public float getSongParamVolume() {
    return songParamVolume;
  }

  public void setSongParamVolume(float v) {
    float old = this.songParamVolume;
    this.songParamVolume = v;
    rawSongParamHex.remove("volume");
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "songParamVolume", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamPan() {
    return songParamPan;
  }

  public void setSongParamPan(float v) {
    float old = this.songParamPan;
    this.songParamPan = v;
    rawSongParamHex.remove("pan");
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "songParamPan", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamReverbAmount() {
    return songParamReverbAmount;
  }

  public void setSongParamReverbAmount(float v) {
    float old = this.songParamReverbAmount;
    this.songParamReverbAmount = v;
    rawSongParamHex.remove("reverbAmount");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamReverbAmount", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamDelayRate() {
    return songParamDelayRate;
  }

  public void setSongParamDelayRate(float v) {
    float old = this.songParamDelayRate;
    this.songParamDelayRate = v;
    rawSongParamHex.remove("delayRate");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamDelayRate", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamDelayFeedback() {
    return songParamDelayFeedback;
  }

  public void setSongParamDelayFeedback(float v) {
    float old = this.songParamDelayFeedback;
    this.songParamDelayFeedback = v;
    rawSongParamHex.remove("delayFeedback");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamDelayFeedback", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamSidechainShape() {
    return songParamSidechainShape;
  }

  public void setSongParamSidechainShape(float v) {
    float old = this.songParamSidechainShape;
    this.songParamSidechainShape = v;
    rawSongParamHex.remove("sidechainCompressorShape");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamSidechainShape", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamSidechainVolume() {
    return songParamSidechainVolume;
  }

  public void setSongParamSidechainVolume(float v) {
    float old = this.songParamSidechainVolume;
    this.songParamSidechainVolume = v;
    rawSongParamHex.remove("sidechainCompressorVolume");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamSidechainVolume", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamStutterRate() {
    return songParamStutterRate;
  }

  public void setSongParamStutterRate(float v) {
    float old = this.songParamStutterRate;
    this.songParamStutterRate = v;
    rawSongParamHex.remove("stutterRate");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamStutterRate", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamSampleRateReduction() {
    return songParamSampleRateReduction;
  }

  public void setSongParamSampleRateReduction(float v) {
    float old = this.songParamSampleRateReduction;
    this.songParamSampleRateReduction = v;
    rawSongParamHex.remove("sampleRateReduction");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamSampleRateReduction", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamBitCrush() {
    return songParamBitCrush;
  }

  public void setSongParamBitCrush(float v) {
    float old = this.songParamBitCrush;
    this.songParamBitCrush = v;
    rawSongParamHex.remove("bitCrush");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamBitCrush", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamModFXRate() {
    return songParamModFXRate;
  }

  public void setSongParamModFXRate(float v) {
    float old = this.songParamModFXRate;
    this.songParamModFXRate = v;
    rawSongParamHex.remove("modFXRate");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamModFXRate", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamModFXDepth() {
    return songParamModFXDepth;
  }

  public void setSongParamModFXDepth(float v) {
    float old = this.songParamModFXDepth;
    this.songParamModFXDepth = v;
    rawSongParamHex.remove("modFXDepth");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamModFXDepth", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamModFXOffset() {
    return songParamModFXOffset;
  }

  public void setSongParamModFXOffset(float v) {
    float old = this.songParamModFXOffset;
    this.songParamModFXOffset = v;
    rawSongParamHex.remove("modFXOffset");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamModFXOffset", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamModFXFeedback() {
    return songParamModFXFeedback;
  }

  public void setSongParamModFXFeedback(float v) {
    float old = this.songParamModFXFeedback;
    this.songParamModFXFeedback = v;
    rawSongParamHex.remove("modFXFeedback");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamModFXFeedback", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamCompressorThreshold() {
    return songParamCompressorThreshold;
  }

  public void setSongParamCompressorThreshold(float v) {
    float old = this.songParamCompressorThreshold;
    this.songParamCompressorThreshold = v;
    rawSongParamHex.remove("compressorThreshold");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamCompressorThreshold", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamLpfMorph() {
    return songParamLpfMorph;
  }

  public void setSongParamLpfMorph(float v) {
    float old = this.songParamLpfMorph;
    this.songParamLpfMorph = v;
    rawSongParamHex.remove("lpfMorph");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamLpfMorph", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamHpfMorph() {
    return songParamHpfMorph;
  }

  public void setSongParamHpfMorph(float v) {
    float old = this.songParamHpfMorph;
    this.songParamHpfMorph = v;
    rawSongParamHex.remove("hpfMorph");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamHpfMorph", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamLpfFrequency() {
    return songParamLpfFrequency;
  }

  public void setSongParamLpfFrequency(float v) {
    float old = this.songParamLpfFrequency;
    this.songParamLpfFrequency = v;
    rawSongParamHex.remove("lpfFrequency");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamLpfFrequency", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamLpfResonance() {
    return songParamLpfResonance;
  }

  public void setSongParamLpfResonance(float v) {
    float old = this.songParamLpfResonance;
    this.songParamLpfResonance = v;
    rawSongParamHex.remove("lpfResonance");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamLpfResonance", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamHpfFrequency() {
    return songParamHpfFrequency;
  }

  public void setSongParamHpfFrequency(float v) {
    float old = this.songParamHpfFrequency;
    this.songParamHpfFrequency = v;
    rawSongParamHex.remove("hpfFrequency");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamHpfFrequency", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamHpfResonance() {
    return songParamHpfResonance;
  }

  public void setSongParamHpfResonance(float v) {
    float old = this.songParamHpfResonance;
    this.songParamHpfResonance = v;
    rawSongParamHex.remove("hpfResonance");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamHpfResonance", old, v));
    notifySongParamsChanged();
  }

  public FilterMode getSongLpfMode() {
    return songLpfMode;
  }

  public void setSongLpfMode(FilterMode v) {
    if (v != null && this.songLpfMode != v) {
      this.songLpfMode = v;
      notifySongParamsChanged();
    }
  }

  public FilterMode getSongHpfMode() {
    return songHpfMode;
  }

  public void setSongHpfMode(FilterMode v) {
    if (v != null && this.songHpfMode != v) {
      this.songHpfMode = v;
      notifySongParamsChanged();
    }
  }

  public int getSongFilterRoute() {
    return songFilterRoute;
  }

  public void setSongFilterRoute(int v) {
    int clamped = Math.max(0, Math.min(2, v));
    if (this.songFilterRoute != clamped) {
      this.songFilterRoute = clamped;
      notifySongParamsChanged();
    }
  }

  public float getSongParamEqBass() {
    return songParamEqBass;
  }

  public void setSongParamEqBass(float v) {
    float old = this.songParamEqBass;
    this.songParamEqBass = v;
    rawSongParamHex.remove("eqBass");
    if (old != v)
      undoRedoStack.push(new Consequence.ProjectParamConsequence(this, "songParamEqBass", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamEqTreble() {
    return songParamEqTreble;
  }

  public void setSongParamEqTreble(float v) {
    float old = this.songParamEqTreble;
    this.songParamEqTreble = v;
    rawSongParamHex.remove("eqTreble");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamEqTreble", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamEqBassFrequency() {
    return songParamEqBassFrequency;
  }

  public void setSongParamEqBassFrequency(float v) {
    float old = this.songParamEqBassFrequency;
    this.songParamEqBassFrequency = v;
    rawSongParamHex.remove("eqBassFrequency");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamEqBassFrequency", old, v));
    notifySongParamsChanged();
  }

  public float getSongParamEqTrebleFrequency() {
    return songParamEqTrebleFrequency;
  }

  public void setSongParamEqTrebleFrequency(float v) {
    float old = this.songParamEqTrebleFrequency;
    this.songParamEqTrebleFrequency = v;
    rawSongParamHex.remove("eqTrebleFrequency");
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "songParamEqTrebleFrequency", old, v));
    notifySongParamsChanged();
  }

  public Map<String, List<org.deluge.modulation.automation.ParamNode>>
      getSongParamAutomationCurves() {
    return songParamAutomationCurves;
  }

  public void setSongParamAutomationCurve(
      String param, List<org.deluge.modulation.automation.ParamNode> nodes) {
    if (nodes == null || nodes.isEmpty()) {
      songParamAutomationCurves.remove(param);
    } else {
      songParamAutomationCurves.put(param, new ArrayList<>(nodes));
    }
    rebuildSongParamManager();
  }

  /**
   * Maps a {@code <songParams>} XML attribute/tag key to its C++ {@code UnpatchedGlobal} / {@code
   * UnpatchedShared} parameter ID (C global_effectable.cpp:835-1037,
   * mod_controllable_audio.cpp:500-714, param.h:177-237).
   */
  public static int songParamIdFromKey(String key) {
    if (key == null) return -1;
    return switch (key) {
      case "reverbAmount" -> org.deluge.firmware2.Param.UNPATCHED_REVERB_SEND_AMOUNT;
      case "volume" -> org.deluge.firmware2.Param.UNPATCHED_VOLUME;
      case "pan" -> org.deluge.firmware2.Param.UNPATCHED_PAN;
      case "sidechainCompressorShape" -> org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE;
      case "sidechainCompressorVolume" -> org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_VOLUME;
      case "modFXRate" -> org.deluge.firmware2.Param.UNPATCHED_MOD_FX_RATE;
      case "modFXDepth" -> org.deluge.firmware2.Param.UNPATCHED_MOD_FX_DEPTH;
      case "modFXOffset" -> org.deluge.firmware2.Param.UNPATCHED_MOD_FX_OFFSET;
      case "modFXFeedback" -> org.deluge.firmware2.Param.UNPATCHED_MOD_FX_FEEDBACK;
      case "stutterRate" -> org.deluge.firmware2.Param.UNPATCHED_STUTTER_RATE;
      case "sampleRateReduction" -> org.deluge.firmware2.Param.UNPATCHED_SAMPLE_RATE_REDUCTION;
      case "bitCrush" -> org.deluge.firmware2.Param.UNPATCHED_BITCRUSHING;
      case "compressorThreshold" -> org.deluge.firmware2.Param.UNPATCHED_COMPRESSOR_THRESHOLD;
      case "lpfMorph" -> org.deluge.firmware2.Param.UNPATCHED_LPF_MORPH;
      case "hpfMorph" -> org.deluge.firmware2.Param.UNPATCHED_HPF_MORPH;
      case "pitchAdjust" -> org.deluge.firmware2.Param.UNPATCHED_PITCH_ADJUST;
      case "tempo" -> org.deluge.firmware2.Param.UNPATCHED_TEMPO;
      case "arpeggiatorRate" -> org.deluge.firmware2.Param.UNPATCHED_ARP_RATE;
      case "arpeggiatorGate" -> org.deluge.firmware2.Param.UNPATCHED_ARP_GATE;
      case "delayRate" -> org.deluge.firmware2.Param.UNPATCHED_DELAY_RATE;
      case "delayFeedback" -> org.deluge.firmware2.Param.UNPATCHED_DELAY_AMOUNT;
      case "lpfFrequency" -> org.deluge.firmware2.Param.UNPATCHED_LPF_FREQ;
      case "lpfResonance" -> org.deluge.firmware2.Param.UNPATCHED_LPF_RES;
      case "hpfFrequency" -> org.deluge.firmware2.Param.UNPATCHED_HPF_FREQ;
      case "hpfResonance" -> org.deluge.firmware2.Param.UNPATCHED_HPF_RES;
      case "eqBass" -> org.deluge.firmware2.Param.UNPATCHED_BASS;
      case "eqTreble" -> org.deluge.firmware2.Param.UNPATCHED_TREBLE;
      case "eqBassFrequency" -> org.deluge.firmware2.Param.UNPATCHED_BASS_FREQ;
      case "eqTrebleFrequency" -> org.deluge.firmware2.Param.UNPATCHED_TREBLE_FREQ;
      default -> -1;
    };
  }

  private int resolveSongParamBaseQ31(String key) {
    String raw = rawSongParamHex.get(key);
    if (raw != null && !raw.isBlank()) {
      return org.deluge.xml.DelugeHexMapper.hexToQ31(raw);
    }
    return switch (key) {
      case "reverbAmount" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamReverbAmount);
      case "volume" -> org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamVolume);
      case "pan" -> org.deluge.xml.DelugeHexMapper.floatToQ31(songParamPan);
      case "sidechainCompressorShape" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamSidechainShape);
      case "sidechainCompressorVolume" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamSidechainVolume);
      case "modFXRate" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamModFXRate);
      case "modFXDepth" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamModFXDepth);
      case "modFXOffset" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamModFXOffset);
      case "modFXFeedback" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamModFXFeedback);
      case "stutterRate" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamStutterRate);
      case "sampleRateReduction" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamSampleRateReduction);
      case "bitCrush" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamBitCrush);
      case "compressorThreshold" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamCompressorThreshold);
      case "lpfMorph" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamLpfMorph);
      case "hpfMorph" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamHpfMorph);
      case "tempo" -> (int) (bpm * 100.0f);
      case "delayRate" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamDelayRate);
      case "delayFeedback" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamDelayFeedback);
      case "lpfFrequency" -> org.deluge.xml.DelugeHexMapper.hzToQ31(songParamLpfFrequency);
      case "lpfResonance" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamLpfResonance);
      case "hpfFrequency" -> org.deluge.xml.DelugeHexMapper.hzToQ31(songParamHpfFrequency);
      case "hpfResonance" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamHpfResonance);
      case "eqBass" -> org.deluge.xml.DelugeHexMapper.floatToQ31(songParamEqBass);
      case "eqTreble" -> org.deluge.xml.DelugeHexMapper.floatToQ31(songParamEqTreble);
      case "eqBassFrequency" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamEqBassFrequency);
      case "eqTrebleFrequency" ->
          org.deluge.xml.DelugeHexMapper.unipolarFloatToQ31Unified(songParamEqTrebleFrequency);
      default -> 0;
    };
  }

  /**
   * Synchronizes {@link #paramManager} from {@link #songParamAutomationCurves} and base {@code
   * <songParams>} values (C song.cpp:2070-2073, 2129, 3120-3140).
   */
  public void rebuildSongParamManager() {
    paramManager.automatedParams.clear();
    paramManager.ticksTilNextEvent = 0;
    paramManager.ticksSkipped = 0;
    this.intBpm = (int) (this.bpm * 100.0f);

    String[] allKnownKeys = {
      "reverbAmount",
      "volume",
      "pan",
      "sidechainCompressorShape",
      "sidechainCompressorVolume",
      "modFXRate",
      "modFXDepth",
      "modFXOffset",
      "modFXFeedback",
      "stutterRate",
      "sampleRateReduction",
      "bitCrush",
      "compressorThreshold",
      "lpfMorph",
      "hpfMorph",
      "pitchAdjust",
      "tempo",
      "arpeggiatorRate",
      "arpeggiatorGate",
      "delayRate",
      "delayFeedback",
      "lpfFrequency",
      "lpfResonance",
      "hpfFrequency",
      "hpfResonance",
      "eqBass",
      "eqTreble",
      "eqBassFrequency",
      "eqTrebleFrequency"
    };
    for (String key : allKnownKeys) {
      int paramId = songParamIdFromKey(key);
      if (paramId >= 0) {
        paramManager.setUnpatchedValue(paramId, resolveSongParamBaseQ31(key));
      }
    }

    for (Map.Entry<String, List<org.deluge.modulation.automation.ParamNode>> entry :
        songParamAutomationCurves.entrySet()) {
      int paramId = songParamIdFromKey(entry.getKey());
      List<org.deluge.modulation.automation.ParamNode> nodes = entry.getValue();
      if (paramId < 0 || nodes == null || nodes.isEmpty()) {
        continue;
      }
      org.deluge.modulation.automation.AutoParam ap =
          new org.deluge.modulation.automation.AutoParam(paramId);
      ap.currentValue = paramManager.getUnpatchedValue(paramId);
      for (org.deluge.modulation.automation.ParamNode node : nodes) {
        ap.addNode(node.pos, node.value, node.interpolated);
      }
      paramManager.automatedParams.add(ap);
    }
  }

  /**
   * Applies automated {@link #paramManager} values back to {@link ProjectModel}, including {@code
   * Song::updateBPMFromAutomation} (C song.cpp:6062-6070). Does not push undo consequences or clear
   * {@link #rawSongParamHex}.
   */
  public void applyAutomatedSongParams() {
    boolean anySongParamChanged = false;
    for (org.deluge.modulation.automation.AutoParam ap : paramManager.automatedParams) {
      int v = ap.currentValue;
      paramManager.setUnpatchedValue(ap.paramId, v);
      switch (ap.paramId) {
        case org.deluge.firmware2.Param.UNPATCHED_TEMPO -> {
          // C song.cpp:6065-6069 — ignore 0, update BPM when currentTempo != intBPM.
          if (v != 0 && v != intBpm) {
            intBpm = v;
            this.bpm = Math.max(1.0f, Math.min(20000.0f, (float) v / 100.0f));
            notifyBpmChanged(this.bpm);
          }
        }
        case org.deluge.firmware2.Param.UNPATCHED_REVERB_SEND_AMOUNT -> {
          songParamReverbAmount = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_VOLUME -> {
          songParamVolume = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_PAN -> {
          songParamPan = org.deluge.xml.DelugeHexMapper.q31ToFloat(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE -> {
          songParamSidechainShape = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_VOLUME -> {
          songParamSidechainVolume = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_MOD_FX_RATE -> {
          songParamModFXRate = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_MOD_FX_DEPTH -> {
          songParamModFXDepth = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_MOD_FX_OFFSET -> {
          songParamModFXOffset = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_MOD_FX_FEEDBACK -> {
          songParamModFXFeedback = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_STUTTER_RATE -> {
          songParamStutterRate = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_SAMPLE_RATE_REDUCTION -> {
          songParamSampleRateReduction =
              org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_BITCRUSHING -> {
          songParamBitCrush = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_COMPRESSOR_THRESHOLD -> {
          songParamCompressorThreshold =
              org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_LPF_MORPH -> {
          songParamLpfMorph = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_HPF_MORPH -> {
          songParamHpfMorph = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_DELAY_RATE -> {
          songParamDelayRate = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_DELAY_AMOUNT -> {
          songParamDelayFeedback = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_LPF_FREQ -> {
          songParamLpfFrequency = org.deluge.xml.DelugeHexMapper.q31ToHz(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_LPF_RES -> {
          songParamLpfResonance = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_HPF_FREQ -> {
          songParamHpfFrequency = org.deluge.xml.DelugeHexMapper.q31ToHz(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_HPF_RES -> {
          songParamHpfResonance = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_BASS -> {
          songParamEqBass = org.deluge.xml.DelugeHexMapper.q31ToFloat(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_TREBLE -> {
          songParamEqTreble = org.deluge.xml.DelugeHexMapper.q31ToFloat(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_BASS_FREQ -> {
          songParamEqBassFrequency = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        case org.deluge.firmware2.Param.UNPATCHED_TREBLE_FREQ -> {
          songParamEqTrebleFrequency = org.deluge.xml.DelugeHexMapper.q31ToUnipolarFloatUnified(v);
          anySongParamChanged = true;
        }
        default -> {}
      }
    }
    if (anySongParamChanged) {
      notifySongParamsChanged();
    }
  }

  public Map<String, String> getRawSongParamHex() {
    return rawSongParamHex;
  }

  public void setRawSongParamHex(String param, String hex) {
    if (hex == null || hex.isBlank()) {
      rawSongParamHex.remove(param);
    } else {
      rawSongParamHex.put(param, hex);
    }
  }

  public String getModFXCurrentParam() {
    return modFXCurrentParam;
  }

  public void setModFXCurrentParam(String v) {
    this.modFXCurrentParam = v;
  }

  public String getCurrentFilterType() {
    return currentFilterType;
  }

  public void setCurrentFilterType(String v) {
    this.currentFilterType = v;
  }

  public String getSongModFXType() {
    return songModFXType;
  }

  public void setSongModFXType(String v) {
    this.songModFXType = (v == null || v.isBlank()) ? "none" : v;
  }

  public int getSongClippingAmount() {
    return songClippingAmount;
  }

  public void setSongClippingAmount(int v) {
    this.songClippingAmount = Math.max(0, v);
  }

  // ── Scales getters/setters ──

  public int getUserScale() {
    return userScale;
  }

  public void setUserScale(int v) {
    int old = this.userScale;
    this.userScale = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(this, "userScale", (float) old, (float) v));
    notifyScalesChanged();
  }

  public int getDisabledPresetScales() {
    return disabledPresetScales;
  }

  public void setDisabledPresetScales(int v) {
    int old = this.disabledPresetScales;
    this.disabledPresetScales = v;
    if (old != v)
      undoRedoStack.push(
          new Consequence.ProjectParamConsequence(
              this, "disabledPresetScales", (float) old, (float) v));
    notifyScalesChanged();
  }

  // ── Mode notes getters/setters ──

  public boolean[] getModeNotes() {
    return modeNotes;
  }

  public void setModeNotes(boolean[] v) {
    this.modeNotes = v;
  }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
    // No undo push: ProjectParamConsequence carries floats, so a "key"(0,0) consequence
    // consumed an undo step while reverting nothing.
    notifyKeyChanged(key);
  }

  public String getScale() {
    return scale;
  }

  public void setScale(String scale) {
    this.scale = scale;
    // No undo push (see setKey).
    notifyScaleChanged(scale);
  }

  // ── Microtuning getters/setters ──

  public int getOctaveNumMicrotonalNotes() {
    return octaveNumMicrotonalNotes;
  }

  public void setOctaveNumMicrotonalNotes(int v) {
    this.octaveNumMicrotonalNotes = v;
  }

  public boolean isEqualTemperament() {
    return isEqualTemperament;
  }

  public void setIsEqualTemperament(boolean v) {
    this.isEqualTemperament = v;
  }

  public double getBaseFrequencyHz() {
    return baseFrequencyHz;
  }

  public void setBaseFrequencyHz(double v) {
    this.baseFrequencyHz = v;
  }

  public int[] getCentAdjustForNotesInTemperament() {
    return centAdjustForNotesInTemperament;
  }

  public double[] getCustomRatios() {
    return customRatios;
  }

  /** Imports a parsed Scala scale tuning into this project model. */
  public void importScalaScale(org.deluge.model.tuning.ScalaScale scale) {
    this.octaveNumMicrotonalNotes = scale.getStepsCount();
    this.isEqualTemperament = false;
    this.baseFrequencyHz = scale.getReferenceFrequency();

    // Clear and copy ratios
    java.util.Arrays.fill(this.customRatios, 0.0);
    double[] scaleRatios = scale.getStepRatios();
    System.arraycopy(scaleRatios, 0, this.customRatios, 0, Math.min(scaleRatios.length, 64));
  }
}
