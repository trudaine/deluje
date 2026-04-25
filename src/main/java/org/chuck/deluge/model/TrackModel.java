package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Base abstract model for a Sequencer Track (Kit or Synth). */
public abstract class TrackModel {
  private String name;
  private final TrackType type;
  private boolean muted = false;
  private float volume = 1.0f;
  private float pan = 0.5f;

  private final List<ClipModel> clips = new ArrayList<>();
  private int activeClipIndex = 0;
  private String colourHex = "0x00FFCC00"; // Default Cyan

  public String getColourHex() {
    return colourHex;
  }

  public void setColourHex(String colourHex) {
    this.colourHex = colourHex;
  }

  public TrackModel(String name, TrackType type) {
    this.name = name;
    this.type = type;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public TrackType getType() {
    return type;
  }

  public boolean isMuted() {
    return muted;
  }

  public void setMuted(boolean muted) {
    this.muted = muted;
  }

  public float getVolume() {
    return volume;
  }

  public void setVolume(float volume) {
    this.volume = Math.max(0.0f, volume);
  }

  public float getPan() {
    return pan;
  }

  public void setPan(float pan) {
    this.pan = Math.max(0.0f, Math.min(1.0f, pan));
  }

  public List<ClipModel> getClips() {
    return clips;
  }

  public void addClip(ClipModel clip) {
    clips.add(clip);
  }

  public void removeClip(ClipModel clip) {
    clips.remove(clip);
  }

  public int getActiveClipIndex() {
    return activeClipIndex;
  }

  public void setActiveClipIndex(int activeClipIndex) {
    if (activeClipIndex >= 0 && activeClipIndex < clips.size()) {
      this.activeClipIndex = activeClipIndex;
    }
  }

  public ClipModel getActiveClip() {
    if (activeClipIndex >= 0 && activeClipIndex < clips.size()) {
      return clips.get(activeClipIndex);
    }
    return null;
  }
}
