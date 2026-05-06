package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The root model for a full Deluge project/song. */
public class ProjectModel {

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
  private float masterReverb = 0.3f;
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
    this.masterVolume = vol;
  }

  public float getMasterReverb() {
    return masterReverb;
  }

  public void setMasterReverb(float rev) {
    this.masterReverb = rev;
  }

  public float getMasterDelay() {
    return masterDelay;
  }

  public void setMasterDelay(float del) {
    this.masterDelay = del;
  }

  private java.util.function.Consumer<Float> onBpmChanged;

  public void setOnBpmChanged(java.util.function.Consumer<Float> callback) {
    this.onBpmChanged = callback;
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
    this.bpm = Math.max(1.0f, Math.min(300.0f, bpm));
    if (onBpmChanged != null) {
      onBpmChanged.accept(this.bpm);
    }
  }

  public float getSwing() {
    return swing;
  }

  public void setSwing(float swing) {
    this.swing = Math.max(0.0f, Math.min(1.0f, swing));
  }

  public int getTimeSigNum() {
    return timeSigNum;
  }

  public void setTimeSigNum(int timeSigNum) {
    this.timeSigNum = Math.max(1, timeSigNum);
  }

  public int getTimeSigDenom() {
    return timeSigDenom;
  }

  public void setTimeSigDenom(int timeSigDenom) {
    this.timeSigDenom = Math.max(1, timeSigDenom);
  }

  public int getTranspose() {
    return transpose;
  }

  public void setTranspose(int transpose) {
    this.transpose = transpose;
  }

  public float getHumanize() {
    return humanize;
  }

  public void setHumanize(float humanize) {
    this.humanize = Math.max(0.0f, Math.min(1.0f, humanize));
  }

  public interface ProjectListener {
    void onTrackListChanged();

    void onBpmChanged(float bpm);
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

  public List<TrackModel> getTracks() {
    return tracks;
  }

  public void addTrack(TrackModel track) {
    this.tracks.add(track);
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
  public void setReverbRoomSize(float v) { this.reverbRoomSize = v; }
  public float getReverbDampening() { return reverbDampening; }
  public void setReverbDampening(float v) { this.reverbDampening = v; }
  public float getReverbWidth() { return reverbWidth; }
  public void setReverbWidth(float v) { this.reverbWidth = v; }
  public float getReverbHpf() { return reverbHpf; }
  public void setReverbHpf(float v) { this.reverbHpf = v; }
  public float getReverbPan() { return reverbPan; }
  public void setReverbPan(float v) { this.reverbPan = v; }
  public int getReverbModel() { return reverbModel; }
  public void setReverbModel(int v) { this.reverbModel = v; }
  public float getReverbCompressorAttack() { return reverbCompressorAttack; }
  public void setReverbCompressorAttack(float v) { this.reverbCompressorAttack = v; }
  public float getReverbCompressorRelease() { return reverbCompressorRelease; }
  public void setReverbCompressorRelease(float v) { this.reverbCompressorRelease = v; }
  public int getReverbCompressorSyncLevel() { return reverbCompressorSyncLevel; }
  public void setReverbCompressorSyncLevel(int v) { this.reverbCompressorSyncLevel = v; }
  public float getReverbCompHpf() { return reverbCompHpf; }
  public void setReverbCompHpf(float v) { this.reverbCompHpf = v; }
  public float getReverbCompBlend() { return reverbCompBlend; }
  public void setReverbCompBlend(float v) { this.reverbCompBlend = v; }

  // ── Delay getters/setters ──

  public int getDelayPingPong() { return delayPingPong; }
  public void setDelayPingPong(int v) { this.delayPingPong = v; }
  public int getDelayAnalog() { return delayAnalog; }
  public void setDelayAnalog(int v) { this.delayAnalog = v; }
  public int getDelaySyncLevel() { return delaySyncLevel; }
  public void setDelaySyncLevel(int v) { this.delaySyncLevel = v; }
  public int getDelaySyncType() { return delaySyncType; }
  public void setDelaySyncType(int v) { this.delaySyncType = v; }

  // ── Sidechain getters/setters ──

  public float getSidechainAttack() { return sidechainAttack; }
  public void setSidechainAttack(float v) { this.sidechainAttack = v; }
  public float getSidechainRelease() { return sidechainRelease; }
  public void setSidechainRelease(float v) { this.sidechainRelease = v; }
  public int getSidechainSyncLevel() { return sidechainSyncLevel; }
  public void setSidechainSyncLevel(int v) { this.sidechainSyncLevel = v; }
  public int getSidechainSyncType() { return sidechainSyncType; }
  public void setSidechainSyncType(int v) { this.sidechainSyncType = v; }

  // ── Compressor getters/setters ──

  public float getCompressorAttack() { return compressorAttack; }
  public void setCompressorAttack(float v) { this.compressorAttack = v; }
  public float getCompressorRelease() { return compressorRelease; }
  public void setCompressorRelease(float v) { this.compressorRelease = v; }
  public float getCompressorThreshold() { return compressorThreshold; }
  public void setCompressorThreshold(float v) { this.compressorThreshold = v; }
  public float getCompressorRatio() { return compressorRatio; }
  public void setCompressorRatio(float v) { this.compressorRatio = v; }

  // ── SongParams getters/setters ──

  public float getSongParamVolume() { return songParamVolume; }
  public void setSongParamVolume(float v) { this.songParamVolume = v; }
  public float getSongParamPan() { return songParamPan; }
  public void setSongParamPan(float v) { this.songParamPan = v; }
  public float getSongParamReverbAmount() { return songParamReverbAmount; }
  public void setSongParamReverbAmount(float v) { this.songParamReverbAmount = v; }
  public float getSongParamDelayRate() { return songParamDelayRate; }
  public void setSongParamDelayRate(float v) { this.songParamDelayRate = v; }
  public float getSongParamDelayFeedback() { return songParamDelayFeedback; }
  public void setSongParamDelayFeedback(float v) { this.songParamDelayFeedback = v; }
  public float getSongParamSidechainShape() { return songParamSidechainShape; }
  public void setSongParamSidechainShape(float v) { this.songParamSidechainShape = v; }
  public float getSongParamStutterRate() { return songParamStutterRate; }
  public void setSongParamStutterRate(float v) { this.songParamStutterRate = v; }
  public float getSongParamSampleRateReduction() { return songParamSampleRateReduction; }
  public void setSongParamSampleRateReduction(float v) { this.songParamSampleRateReduction = v; }
  public float getSongParamBitCrush() { return songParamBitCrush; }
  public void setSongParamBitCrush(float v) { this.songParamBitCrush = v; }
  public float getSongParamModFXRate() { return songParamModFXRate; }
  public void setSongParamModFXRate(float v) { this.songParamModFXRate = v; }
  public float getSongParamModFXDepth() { return songParamModFXDepth; }
  public void setSongParamModFXDepth(float v) { this.songParamModFXDepth = v; }
  public float getSongParamModFXOffset() { return songParamModFXOffset; }
  public void setSongParamModFXOffset(float v) { this.songParamModFXOffset = v; }
  public float getSongParamModFXFeedback() { return songParamModFXFeedback; }
  public void setSongParamModFXFeedback(float v) { this.songParamModFXFeedback = v; }
  public float getSongParamCompressorThreshold() { return songParamCompressorThreshold; }
  public void setSongParamCompressorThreshold(float v) { this.songParamCompressorThreshold = v; }
  public float getSongParamLpfMorph() { return songParamLpfMorph; }
  public void setSongParamLpfMorph(float v) { this.songParamLpfMorph = v; }
  public float getSongParamHpfMorph() { return songParamHpfMorph; }
  public void setSongParamHpfMorph(float v) { this.songParamHpfMorph = v; }
  public float getSongParamLpfFrequency() { return songParamLpfFrequency; }
  public void setSongParamLpfFrequency(float v) { this.songParamLpfFrequency = v; }
  public float getSongParamLpfResonance() { return songParamLpfResonance; }
  public void setSongParamLpfResonance(float v) { this.songParamLpfResonance = v; }
  public float getSongParamHpfFrequency() { return songParamHpfFrequency; }
  public void setSongParamHpfFrequency(float v) { this.songParamHpfFrequency = v; }
  public float getSongParamHpfResonance() { return songParamHpfResonance; }
  public void setSongParamHpfResonance(float v) { this.songParamHpfResonance = v; }
  public float getSongParamEqBass() { return songParamEqBass; }
  public void setSongParamEqBass(float v) { this.songParamEqBass = v; }
  public float getSongParamEqTreble() { return songParamEqTreble; }
  public void setSongParamEqTreble(float v) { this.songParamEqTreble = v; }
  public float getSongParamEqBassFrequency() { return songParamEqBassFrequency; }
  public void setSongParamEqBassFrequency(float v) { this.songParamEqBassFrequency = v; }
  public float getSongParamEqTrebleFrequency() { return songParamEqTrebleFrequency; }
  public void setSongParamEqTrebleFrequency(float v) { this.songParamEqTrebleFrequency = v; }

  // ── Scales getters/setters ──

  public int getUserScale() { return userScale; }
  public void setUserScale(int v) { this.userScale = v; }
  public int getDisabledPresetScales() { return disabledPresetScales; }
  public void setDisabledPresetScales(int v) { this.disabledPresetScales = v; }

  // ── Mode notes getters/setters ──

  public boolean[] getModeNotes() { return modeNotes; }
  public void setModeNotes(boolean[] v) { this.modeNotes = v; }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
  }

  public String getScale() {
    return scale;
  }

  public void setScale(String scale) {
    this.scale = scale;
  }
}
