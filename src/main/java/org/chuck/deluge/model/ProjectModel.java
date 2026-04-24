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

  public static ProjectModel createDefaultProject() {
      ProjectModel project = new ProjectModel();
      project.setBpm(120.0f);
      
      KitTrackModel defaultKit = new KitTrackModel("KIT 1");
      ClipModel clip1 = new ClipModel("CLIP 1", 8, 16);
      defaultKit.addClip(clip1);
      project.addTrack(defaultKit);

      SynthTrackModel defaultSynth = new SynthTrackModel("SYNTH 1");
      ClipModel clip2 = new ClipModel("CLIP 1", 8, 16);
      defaultSynth.addClip(clip2);
      project.addTrack(defaultSynth);

      return project;
  }

  public float getMasterVolume() { return masterVolume; }
  public void setMasterVolume(float vol) { this.masterVolume = vol; }

  public float getMasterReverb() { return masterReverb; }
  public void setMasterReverb(float rev) { this.masterReverb = rev; }

  public float getMasterDelay() { return masterDelay; }
  public void setMasterDelay(float del) { this.masterDelay = del; }

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
