package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The root model for a full Deluge project/song. */
public class ProjectModel {

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

  private String key = "0";
  private String scale = "Major";

  private float masterVolume = 1.0f;
  private float masterPan = 0.0f;
  private float masterDelay = 0.3f;

  // ── Song-level reverb params ──
  private float reverbRoomSize = 0.6f;
  private float reverbDampening = 0.5f;
  private float reverbWidth = 0.5f;
  private float reverbHpf = 0.0f;
  private float reverbPan = 0.0f;
  private int reverbModel = 0;
  private float reverbCompressorAttack = 0.0f;
  private float reverbCompressorRelease = 0.0f;
  private int reverbCompressorSyncLevel = 0;
  private float reverbCompHpf = 0.0f;
  private float reverbCompBlend = 0.5f;

  // ── Song-level delay params ──
  private int delayPingPong = 0;
  private int delayAnalog = 0;
  private int delaySyncLevel = 0;
  private int delaySyncType = 0;

  // ── Song-level sidechain params ──
  private float sidechainAttack = 0.0f;
  private float sidechainRelease = 0.0f;
  private int sidechainSyncLevel = 0;
  private int sidechainSyncType = 0;

  // ── Song-level compressor params ──
  private float compressorAttack = 0.0f;
  private float compressorRelease = 0.0f;
  private float compressorThreshold = 0.0f;
  private float compressorRatio = 0.0f;

  // ── SongParams values (from <songParams> element) ──
  private float songParamVolume = 1.0f;
  private float songParamPan = 0.0f;
  private float songParamReverbAmount = 0.5f;
  private float songParamDelayRate = 0.0f;
  private float songParamDelayFeedback = 0.0f;
  private float songParamSidechainShape = 0.5f;
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
  private float songParamEqBass = 0.0f;
  private float songParamEqTreble = 0.0f;
  private float songParamEqBassFrequency = 0.0f;
  private float songParamEqTrebleFrequency = 0.0f;

  // ── Scale info ──
  private int userScale = 0;
  private int disabledPresetScales = 0;

  // ── Mode notes (scale note mask, 12 semitones) ──
  private boolean[] modeNotes = null;

  public static ProjectModel createDefaultProject() {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    return project;
  }

  public float getMasterVolume() {
    return masterVolume;
  }

  public void setMasterVolume(float vol) {
    float old = this.masterVolume;
    this.masterVolume = vol;
    if (old != this.masterVolume) {
      undoRedoStack.push(new Consequence.ProjectParamConsequence("masterVolume", old, this.masterVolume));
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
      undoRedoStack.push(new Consequence.ProjectParamConsequence("masterPan", old, this.masterPan));
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
      undoRedoStack.push(new Consequence.ProjectParamConsequence("masterDelay", old, this.masterDelay));
    }
    notifyDelayChanged();
  }

  // Track Models (Active in Clip mode)
  private final List<TrackModel> tracks = new ArrayList<>();

  // Song / Arranger state
  private final Map<String, PatternModel> patterns = new HashMap<>();
  private final List<SongSection> songSections = new ArrayList<>();
  private final List<ArrangerClip> arrangerTimeline = new ArrayList<>();

  public ProjectModel() {}

  public float getBpm() {
    return bpm;
  }

  public void setBpm(float bpm) {
    float old = this.bpm;
    this.bpm = Math.max(1.0f, Math.min(300.0f, bpm));
    if (old != this.bpm) {
      undoRedoStack.push(new Consequence.ProjectParamConsequence("bpm", old, this.bpm));
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
      undoRedoStack.push(new Consequence.ProjectParamConsequence("swing", old, this.swing));
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
      undoRedoStack.push(new Consequence.ProjectParamConsequence("timeSigNum", (float) old, (float) this.timeSigNum));
    }
  }

  public int getTimeSigDenom() {
    return timeSigDenom;
  }

  public void setTimeSigDenom(int timeSigDenom) {
    int old = this.timeSigDenom;
    this.timeSigDenom = Math.max(1, timeSigDenom);
    if (old != this.timeSigDenom) {
      undoRedoStack.push(new Consequence.ProjectParamConsequence("timeSigDenom", (float) old, (float) this.timeSigDenom));
    }
  }

  public int getTranspose() {
    return transpose;
  }

  public void setTranspose(int transpose) {
    int old = this.transpose;
    this.transpose = transpose;
    if (old != this.transpose) {
      undoRedoStack.push(new Consequence.ProjectParamConsequence("transpose", (float) old, (float) this.transpose));
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
      undoRedoStack.push(new Consequence.ProjectParamConsequence("humanize", old, this.humanize));
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

  public float getReverbRoomSize() { return reverbRoomSize; }
  public void setReverbRoomSize(float v) {
    float old = this.reverbRoomSize; this.reverbRoomSize = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbRoomSize", old, v));
    notifyReverbChanged();
  }
  public float getReverbDampening() { return reverbDampening; }
  public void setReverbDampening(float v) {
    float old = this.reverbDampening; this.reverbDampening = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbDampening", old, v));
    notifyReverbChanged();
  }
  public float getReverbWidth() { return reverbWidth; }
  public void setReverbWidth(float v) {
    float old = this.reverbWidth; this.reverbWidth = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbWidth", old, v));
    notifyReverbChanged();
  }
  public float getReverbHpf() { return reverbHpf; }
  public void setReverbHpf(float v) {
    float old = this.reverbHpf; this.reverbHpf = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbHpf", old, v));
    notifyReverbChanged();
  }
  public float getReverbPan() { return reverbPan; }
  public void setReverbPan(float v) {
    float old = this.reverbPan; this.reverbPan = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbPan", old, v));
    notifyReverbChanged();
  }
  public int getReverbModel() { return reverbModel; }
  public void setReverbModel(int v) {
    int old = this.reverbModel; this.reverbModel = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbModel", (float) old, (float) v));
    notifyReverbChanged();
  }
  public float getReverbCompressorAttack() { return reverbCompressorAttack; }
  public void setReverbCompressorAttack(float v) {
    float old = this.reverbCompressorAttack; this.reverbCompressorAttack = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbCompressorAttack", old, v));
    notifyReverbChanged();
  }
  public float getReverbCompressorRelease() { return reverbCompressorRelease; }
  public void setReverbCompressorRelease(float v) {
    float old = this.reverbCompressorRelease; this.reverbCompressorRelease = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbCompressorRelease", old, v));
    notifyReverbChanged();
  }
  public int getReverbCompressorSyncLevel() { return reverbCompressorSyncLevel; }
  public void setReverbCompressorSyncLevel(int v) {
    int old = this.reverbCompressorSyncLevel; this.reverbCompressorSyncLevel = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbCompressorSyncLevel", (float) old, (float) v));
    notifyReverbChanged();
  }
  public float getReverbCompHpf() { return reverbCompHpf; }
  public void setReverbCompHpf(float v) {
    float old = this.reverbCompHpf; this.reverbCompHpf = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbCompHpf", old, v));
    notifyReverbChanged();
  }
  public float getReverbCompBlend() { return reverbCompBlend; }
  public void setReverbCompBlend(float v) {
    float old = this.reverbCompBlend; this.reverbCompBlend = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("reverbCompBlend", old, v));
    notifyReverbChanged();
  }

  // ── Delay getters/setters ──

  public int getDelayPingPong() { return delayPingPong; }
  public void setDelayPingPong(int v) {
    int old = this.delayPingPong; this.delayPingPong = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("delayPingPong", (float) old, (float) v));
    notifyDelayChanged();
  }
  public int getDelayAnalog() { return delayAnalog; }
  public void setDelayAnalog(int v) {
    int old = this.delayAnalog; this.delayAnalog = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("delayAnalog", (float) old, (float) v));
    notifyDelayChanged();
  }
  public int getDelaySyncLevel() { return delaySyncLevel; }
  public void setDelaySyncLevel(int v) {
    int old = this.delaySyncLevel; this.delaySyncLevel = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("delaySyncLevel", (float) old, (float) v));
    notifyDelayChanged();
  }
  public int getDelaySyncType() { return delaySyncType; }
  public void setDelaySyncType(int v) {
    int old = this.delaySyncType; this.delaySyncType = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("delaySyncType", (float) old, (float) v));
    notifyDelayChanged();
  }

  // ── Sidechain getters/setters ──

  public float getSidechainAttack() { return sidechainAttack; }
  public void setSidechainAttack(float v) {
    float old = this.sidechainAttack; this.sidechainAttack = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("sidechainAttack", old, v));
    notifySidechainChanged();
  }
  public float getSidechainRelease() { return sidechainRelease; }
  public void setSidechainRelease(float v) {
    float old = this.sidechainRelease; this.sidechainRelease = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("sidechainRelease", old, v));
    notifySidechainChanged();
  }
  public int getSidechainSyncLevel() { return sidechainSyncLevel; }
  public void setSidechainSyncLevel(int v) {
    int old = this.sidechainSyncLevel; this.sidechainSyncLevel = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("sidechainSyncLevel", (float) old, (float) v));
    notifySidechainChanged();
  }
  public int getSidechainSyncType() { return sidechainSyncType; }
  public void setSidechainSyncType(int v) {
    int old = this.sidechainSyncType; this.sidechainSyncType = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("sidechainSyncType", (float) old, (float) v));
    notifySidechainChanged();
  }

  // ── Compressor getters/setters ──

  public float getCompressorAttack() { return compressorAttack; }
  public void setCompressorAttack(float v) {
    float old = this.compressorAttack; this.compressorAttack = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("compressorAttack", old, v));
    notifyCompressorChanged();
  }
  public float getCompressorRelease() { return compressorRelease; }
  public void setCompressorRelease(float v) {
    float old = this.compressorRelease; this.compressorRelease = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("compressorRelease", old, v));
    notifyCompressorChanged();
  }
  public float getCompressorThreshold() { return compressorThreshold; }
  public void setCompressorThreshold(float v) {
    float old = this.compressorThreshold; this.compressorThreshold = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("compressorThreshold", old, v));
    notifyCompressorChanged();
  }
  public float getCompressorRatio() { return compressorRatio; }
  public void setCompressorRatio(float v) {
    float old = this.compressorRatio; this.compressorRatio = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("compressorRatio", old, v));
    notifyCompressorChanged();
  }

  // ── SongParams getters/setters ──

  public float getSongParamVolume() { return songParamVolume; }
  public void setSongParamVolume(float v) {
    float old = this.songParamVolume; this.songParamVolume = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamVolume", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamPan() { return songParamPan; }
  public void setSongParamPan(float v) {
    float old = this.songParamPan; this.songParamPan = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamPan", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamReverbAmount() { return songParamReverbAmount; }
  public void setSongParamReverbAmount(float v) {
    float old = this.songParamReverbAmount; this.songParamReverbAmount = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamReverbAmount", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamDelayRate() { return songParamDelayRate; }
  public void setSongParamDelayRate(float v) {
    float old = this.songParamDelayRate; this.songParamDelayRate = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamDelayRate", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamDelayFeedback() { return songParamDelayFeedback; }
  public void setSongParamDelayFeedback(float v) {
    float old = this.songParamDelayFeedback; this.songParamDelayFeedback = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamDelayFeedback", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamSidechainShape() { return songParamSidechainShape; }
  public void setSongParamSidechainShape(float v) {
    float old = this.songParamSidechainShape; this.songParamSidechainShape = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamSidechainShape", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamStutterRate() { return songParamStutterRate; }
  public void setSongParamStutterRate(float v) {
    float old = this.songParamStutterRate; this.songParamStutterRate = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamStutterRate", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamSampleRateReduction() { return songParamSampleRateReduction; }
  public void setSongParamSampleRateReduction(float v) {
    float old = this.songParamSampleRateReduction; this.songParamSampleRateReduction = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamSampleRateReduction", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamBitCrush() { return songParamBitCrush; }
  public void setSongParamBitCrush(float v) {
    float old = this.songParamBitCrush; this.songParamBitCrush = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamBitCrush", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamModFXRate() { return songParamModFXRate; }
  public void setSongParamModFXRate(float v) {
    float old = this.songParamModFXRate; this.songParamModFXRate = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamModFXRate", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamModFXDepth() { return songParamModFXDepth; }
  public void setSongParamModFXDepth(float v) {
    float old = this.songParamModFXDepth; this.songParamModFXDepth = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamModFXDepth", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamModFXOffset() { return songParamModFXOffset; }
  public void setSongParamModFXOffset(float v) {
    float old = this.songParamModFXOffset; this.songParamModFXOffset = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamModFXOffset", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamModFXFeedback() { return songParamModFXFeedback; }
  public void setSongParamModFXFeedback(float v) {
    float old = this.songParamModFXFeedback; this.songParamModFXFeedback = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamModFXFeedback", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamCompressorThreshold() { return songParamCompressorThreshold; }
  public void setSongParamCompressorThreshold(float v) {
    float old = this.songParamCompressorThreshold; this.songParamCompressorThreshold = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamCompressorThreshold", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamLpfMorph() { return songParamLpfMorph; }
  public void setSongParamLpfMorph(float v) {
    float old = this.songParamLpfMorph; this.songParamLpfMorph = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamLpfMorph", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamHpfMorph() { return songParamHpfMorph; }
  public void setSongParamHpfMorph(float v) {
    float old = this.songParamHpfMorph; this.songParamHpfMorph = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamHpfMorph", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamLpfFrequency() { return songParamLpfFrequency; }
  public void setSongParamLpfFrequency(float v) {
    float old = this.songParamLpfFrequency; this.songParamLpfFrequency = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamLpfFrequency", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamLpfResonance() { return songParamLpfResonance; }
  public void setSongParamLpfResonance(float v) {
    float old = this.songParamLpfResonance; this.songParamLpfResonance = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamLpfResonance", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamHpfFrequency() { return songParamHpfFrequency; }
  public void setSongParamHpfFrequency(float v) {
    float old = this.songParamHpfFrequency; this.songParamHpfFrequency = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamHpfFrequency", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamHpfResonance() { return songParamHpfResonance; }
  public void setSongParamHpfResonance(float v) {
    float old = this.songParamHpfResonance; this.songParamHpfResonance = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamHpfResonance", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamEqBass() { return songParamEqBass; }
  public void setSongParamEqBass(float v) {
    float old = this.songParamEqBass; this.songParamEqBass = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamEqBass", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamEqTreble() { return songParamEqTreble; }
  public void setSongParamEqTreble(float v) {
    float old = this.songParamEqTreble; this.songParamEqTreble = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamEqTreble", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamEqBassFrequency() { return songParamEqBassFrequency; }
  public void setSongParamEqBassFrequency(float v) {
    float old = this.songParamEqBassFrequency; this.songParamEqBassFrequency = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamEqBassFrequency", old, v));
    notifySongParamsChanged();
  }
  public float getSongParamEqTrebleFrequency() { return songParamEqTrebleFrequency; }
  public void setSongParamEqTrebleFrequency(float v) {
    float old = this.songParamEqTrebleFrequency; this.songParamEqTrebleFrequency = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("songParamEqTrebleFrequency", old, v));
    notifySongParamsChanged();
  }

  // ── Scales getters/setters ──

  public int getUserScale() { return userScale; }
  public void setUserScale(int v) {
    int old = this.userScale; this.userScale = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("userScale", (float) old, (float) v));
    notifyScalesChanged();
  }
  public int getDisabledPresetScales() { return disabledPresetScales; }
  public void setDisabledPresetScales(int v) {
    int old = this.disabledPresetScales; this.disabledPresetScales = v;
    if (old != v) undoRedoStack.push(new Consequence.ProjectParamConsequence("disabledPresetScales", (float) old, (float) v));
    notifyScalesChanged();
  }

  // ── Mode notes getters/setters ──

  public boolean[] getModeNotes() { return modeNotes; }
  public void setModeNotes(boolean[] v) { this.modeNotes = v; }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    String old = this.key;
    this.key = key;
    if (!old.equals(key)) {
      undoRedoStack.push(new Consequence.ProjectParamConsequence("key", 0, 0));
    }
    notifyKeyChanged(key);
  }

  public String getScale() {
    return scale;
  }

  public void setScale(String scale) {
    String old = this.scale;
    this.scale = scale;
    if (!old.equals(scale)) {
      undoRedoStack.push(new Consequence.ProjectParamConsequence("scale", 0, 0));
    }
    notifyScaleChanged(scale);
  }
}
