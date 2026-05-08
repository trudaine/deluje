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
  private float masterPan = 0.0f;
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
    notifyMasterVolumeChanged(vol);
  }

  public float getMasterPan() {
    return masterPan;
  }

  public void setMasterPan(float pan) {
    this.masterPan = pan;
    notifyMasterPanChanged(pan);
  }

  public float getMasterReverb() {
    return masterReverb;
  }

  public void setMasterReverb(float rev) {
    this.masterReverb = rev;
    notifyReverbChanged();
  }

  public float getMasterDelay() {
    return masterDelay;
  }

  public void setMasterDelay(float del) {
    this.masterDelay = del;
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
    this.bpm = Math.max(1.0f, Math.min(300.0f, bpm));
    notifyBpmChanged(this.bpm);
  }

  public float getSwing() {
    return swing;
  }

  public void setSwing(float swing) {
    this.swing = Math.max(0.0f, Math.min(1.0f, swing));
    notifySwingChanged(this.swing);
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
    notifyTransposeChanged(transpose);
  }

  public float getHumanize() {
    return humanize;
  }

  public void setHumanize(float humanize) {
    this.humanize = Math.max(0.0f, Math.min(1.0f, humanize));
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
  public void setReverbRoomSize(float v) { this.reverbRoomSize = v; notifyReverbChanged(); }
  public float getReverbDampening() { return reverbDampening; }
  public void setReverbDampening(float v) { this.reverbDampening = v; notifyReverbChanged(); }
  public float getReverbWidth() { return reverbWidth; }
  public void setReverbWidth(float v) { this.reverbWidth = v; notifyReverbChanged(); }
  public float getReverbHpf() { return reverbHpf; }
  public void setReverbHpf(float v) { this.reverbHpf = v; notifyReverbChanged(); }
  public float getReverbPan() { return reverbPan; }
  public void setReverbPan(float v) { this.reverbPan = v; notifyReverbChanged(); }
  public int getReverbModel() { return reverbModel; }
  public void setReverbModel(int v) { this.reverbModel = v; notifyReverbChanged(); }
  public float getReverbCompressorAttack() { return reverbCompressorAttack; }
  public void setReverbCompressorAttack(float v) { this.reverbCompressorAttack = v; notifyReverbChanged(); }
  public float getReverbCompressorRelease() { return reverbCompressorRelease; }
  public void setReverbCompressorRelease(float v) { this.reverbCompressorRelease = v; notifyReverbChanged(); }
  public int getReverbCompressorSyncLevel() { return reverbCompressorSyncLevel; }
  public void setReverbCompressorSyncLevel(int v) { this.reverbCompressorSyncLevel = v; notifyReverbChanged(); }
  public float getReverbCompHpf() { return reverbCompHpf; }
  public void setReverbCompHpf(float v) { this.reverbCompHpf = v; notifyReverbChanged(); }
  public float getReverbCompBlend() { return reverbCompBlend; }
  public void setReverbCompBlend(float v) { this.reverbCompBlend = v; notifyReverbChanged(); }

  // ── Delay getters/setters ──

  public int getDelayPingPong() { return delayPingPong; }
  public void setDelayPingPong(int v) { this.delayPingPong = v; notifyDelayChanged(); }
  public int getDelayAnalog() { return delayAnalog; }
  public void setDelayAnalog(int v) { this.delayAnalog = v; notifyDelayChanged(); }
  public int getDelaySyncLevel() { return delaySyncLevel; }
  public void setDelaySyncLevel(int v) { this.delaySyncLevel = v; notifyDelayChanged(); }
  public int getDelaySyncType() { return delaySyncType; }
  public void setDelaySyncType(int v) { this.delaySyncType = v; notifyDelayChanged(); }

  // ── Sidechain getters/setters ──

  public float getSidechainAttack() { return sidechainAttack; }
  public void setSidechainAttack(float v) { this.sidechainAttack = v; notifySidechainChanged(); }
  public float getSidechainRelease() { return sidechainRelease; }
  public void setSidechainRelease(float v) { this.sidechainRelease = v; notifySidechainChanged(); }
  public int getSidechainSyncLevel() { return sidechainSyncLevel; }
  public void setSidechainSyncLevel(int v) { this.sidechainSyncLevel = v; notifySidechainChanged(); }
  public int getSidechainSyncType() { return sidechainSyncType; }
  public void setSidechainSyncType(int v) { this.sidechainSyncType = v; notifySidechainChanged(); }

  // ── Compressor getters/setters ──

  public float getCompressorAttack() { return compressorAttack; }
  public void setCompressorAttack(float v) { this.compressorAttack = v; notifyCompressorChanged(); }
  public float getCompressorRelease() { return compressorRelease; }
  public void setCompressorRelease(float v) { this.compressorRelease = v; notifyCompressorChanged(); }
  public float getCompressorThreshold() { return compressorThreshold; }
  public void setCompressorThreshold(float v) { this.compressorThreshold = v; notifyCompressorChanged(); }
  public float getCompressorRatio() { return compressorRatio; }
  public void setCompressorRatio(float v) { this.compressorRatio = v; notifyCompressorChanged(); }

  // ── SongParams getters/setters ──

  public float getSongParamVolume() { return songParamVolume; }
  public void setSongParamVolume(float v) { this.songParamVolume = v; notifySongParamsChanged(); }
  public float getSongParamPan() { return songParamPan; }
  public void setSongParamPan(float v) { this.songParamPan = v; notifySongParamsChanged(); }
  public float getSongParamReverbAmount() { return songParamReverbAmount; }
  public void setSongParamReverbAmount(float v) { this.songParamReverbAmount = v; notifySongParamsChanged(); }
  public float getSongParamDelayRate() { return songParamDelayRate; }
  public void setSongParamDelayRate(float v) { this.songParamDelayRate = v; notifySongParamsChanged(); }
  public float getSongParamDelayFeedback() { return songParamDelayFeedback; }
  public void setSongParamDelayFeedback(float v) { this.songParamDelayFeedback = v; notifySongParamsChanged(); }
  public float getSongParamSidechainShape() { return songParamSidechainShape; }
  public void setSongParamSidechainShape(float v) { this.songParamSidechainShape = v; notifySongParamsChanged(); }
  public float getSongParamStutterRate() { return songParamStutterRate; }
  public void setSongParamStutterRate(float v) { this.songParamStutterRate = v; notifySongParamsChanged(); }
  public float getSongParamSampleRateReduction() { return songParamSampleRateReduction; }
  public void setSongParamSampleRateReduction(float v) { this.songParamSampleRateReduction = v; notifySongParamsChanged(); }
  public float getSongParamBitCrush() { return songParamBitCrush; }
  public void setSongParamBitCrush(float v) { this.songParamBitCrush = v; notifySongParamsChanged(); }
  public float getSongParamModFXRate() { return songParamModFXRate; }
  public void setSongParamModFXRate(float v) { this.songParamModFXRate = v; notifySongParamsChanged(); }
  public float getSongParamModFXDepth() { return songParamModFXDepth; }
  public void setSongParamModFXDepth(float v) { this.songParamModFXDepth = v; notifySongParamsChanged(); }
  public float getSongParamModFXOffset() { return songParamModFXOffset; }
  public void setSongParamModFXOffset(float v) { this.songParamModFXOffset = v; notifySongParamsChanged(); }
  public float getSongParamModFXFeedback() { return songParamModFXFeedback; }
  public void setSongParamModFXFeedback(float v) { this.songParamModFXFeedback = v; notifySongParamsChanged(); }
  public float getSongParamCompressorThreshold() { return songParamCompressorThreshold; }
  public void setSongParamCompressorThreshold(float v) { this.songParamCompressorThreshold = v; notifySongParamsChanged(); }
  public float getSongParamLpfMorph() { return songParamLpfMorph; }
  public void setSongParamLpfMorph(float v) { this.songParamLpfMorph = v; notifySongParamsChanged(); }
  public float getSongParamHpfMorph() { return songParamHpfMorph; }
  public void setSongParamHpfMorph(float v) { this.songParamHpfMorph = v; notifySongParamsChanged(); }
  public float getSongParamLpfFrequency() { return songParamLpfFrequency; }
  public void setSongParamLpfFrequency(float v) { this.songParamLpfFrequency = v; notifySongParamsChanged(); }
  public float getSongParamLpfResonance() { return songParamLpfResonance; }
  public void setSongParamLpfResonance(float v) { this.songParamLpfResonance = v; notifySongParamsChanged(); }
  public float getSongParamHpfFrequency() { return songParamHpfFrequency; }
  public void setSongParamHpfFrequency(float v) { this.songParamHpfFrequency = v; notifySongParamsChanged(); }
  public float getSongParamHpfResonance() { return songParamHpfResonance; }
  public void setSongParamHpfResonance(float v) { this.songParamHpfResonance = v; notifySongParamsChanged(); }
  public float getSongParamEqBass() { return songParamEqBass; }
  public void setSongParamEqBass(float v) { this.songParamEqBass = v; notifySongParamsChanged(); }
  public float getSongParamEqTreble() { return songParamEqTreble; }
  public void setSongParamEqTreble(float v) { this.songParamEqTreble = v; notifySongParamsChanged(); }
  public float getSongParamEqBassFrequency() { return songParamEqBassFrequency; }
  public void setSongParamEqBassFrequency(float v) { this.songParamEqBassFrequency = v; notifySongParamsChanged(); }
  public float getSongParamEqTrebleFrequency() { return songParamEqTrebleFrequency; }
  public void setSongParamEqTrebleFrequency(float v) { this.songParamEqTrebleFrequency = v; notifySongParamsChanged(); }

  // ── Scales getters/setters ──

  public int getUserScale() { return userScale; }
  public void setUserScale(int v) { this.userScale = v; notifyScalesChanged(); }
  public int getDisabledPresetScales() { return disabledPresetScales; }
  public void setDisabledPresetScales(int v) { this.disabledPresetScales = v; notifyScalesChanged(); }

  // ── Mode notes getters/setters ──

  public boolean[] getModeNotes() { return modeNotes; }
  public void setModeNotes(boolean[] v) { this.modeNotes = v; }

  public String getKey() {
    return key;
  }

  public void setKey(String key) {
    this.key = key;
    notifyKeyChanged(key);
  }

  public String getScale() {
    return scale;
  }

  public void setScale(String scale) {
    this.scale = scale;
    notifyScaleChanged(scale);
  }
}
