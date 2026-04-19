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

  public List<TrackModel> getTracks() {
    return tracks;
  }

  public void addTrack(TrackModel track) {
    this.tracks.add(track);
  }

  public void removeTrack(TrackModel track) {
    this.tracks.remove(track);
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
}
