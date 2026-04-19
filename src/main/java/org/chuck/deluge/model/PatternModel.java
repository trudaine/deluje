package org.chuck.deluge.model;

import java.util.HashMap;
import java.util.Map;

/** Represents a named pattern (a snapshot of sequences across tracks) for clip launching. */
public class PatternModel {
  private String id;
  private String name;
  private final Map<Integer, TrackModel> trackOverrides = new HashMap<>();

  public PatternModel(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public Map<Integer, TrackModel> getTrackOverrides() {
    return trackOverrides;
  }

  public void setTrackOverride(int trackIndex, TrackModel track) {
    trackOverrides.put(trackIndex, track);
  }
}
