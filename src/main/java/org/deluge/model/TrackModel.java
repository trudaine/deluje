package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Base abstract model for a Sequencer Track (Kit or Synth). */
public abstract class TrackModel {
  private String name;
  private final TrackType type;
  private boolean muted = false;
  private boolean mutedInArrangement = false;
  private boolean soloingInArrangement = false;
  private float volume = 1.0f;
  private float pan = 0.5f;
  private int defaultVelocity = 64;

  private final List<ClipModel> clips = new ArrayList<>();
  private int activeClipIndex = 0;
  private String colourHex = "0x00FFCC00"; // Default Cyan
  private int colourOffset = 0;

  public String getColourHex() {
    return colourHex;
  }

  public void setColourHex(String colourHex) {
    this.colourHex = colourHex;
  }

  public int getColourOffset() {
    return colourOffset;
  }

  public void setColourOffset(int colourOffset) {
    this.colourOffset = colourOffset;
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

  public boolean isMutedInArrangement() {
    return mutedInArrangement;
  }

  public void setMutedInArrangement(boolean mutedInArrangement) {
    this.mutedInArrangement = mutedInArrangement;
  }

  public boolean isSoloingInArrangement() {
    return soloingInArrangement;
  }

  public void setSoloingInArrangement(boolean soloingInArrangement) {
    this.soloingInArrangement = soloingInArrangement;
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
    if (clip != null) {
      clip.setOwnerTrack(this);
    }
    clips.add(clip);
  }

  public void removeClip(ClipModel clip) {
    int idx = clips.indexOf(clip);
    if (idx < 0) return;
    clip.setOwnerTrack(null);
    clips.remove(idx);
    if (clips.isEmpty()) {
      activeClipIndex = 0;
    } else if (activeClipIndex > idx) {
      activeClipIndex--;
    } else if (activeClipIndex >= clips.size()) {
      activeClipIndex = clips.size() - 1;
    }
  }

  public int getActiveClipIndex() {
    return activeClipIndex;
  }

  public void setActiveClipIndex(int activeClipIndex) {
    if (activeClipIndex >= 0 && activeClipIndex < clips.size()) {
      boolean changed = this.activeClipIndex != activeClipIndex;
      this.activeClipIndex = activeClipIndex;
      // C kit.cpp:159-162, 562-580, param_set.cpp:580-583 — re-apply the newly active clip's
      // per-drum overrides and pitch-bend ranges to the live instrument when switching session
      // clips.
      if (changed) {
        ClipModel nextActive = clips.get(activeClipIndex);
        if (nextActive != null && nextActive.getSound() != null) {
          nextActive.setSound(nextActive.getSound());
        }
      }
    }
  }

  public ClipModel getActiveClip() {
    if (activeClipIndex >= 0 && activeClipIndex < clips.size()) {
      return clips.get(activeClipIndex);
    }
    return null;
  }

  public int getDefaultVelocity() {
    return defaultVelocity;
  }

  public void setDefaultVelocity(int defaultVelocity) {
    this.defaultVelocity = Math.max(1, Math.min(127, defaultVelocity));
  }

  private int clippingAmount = 0;

  public int getClippingAmount() {
    return clippingAmount;
  }

  public void setClippingAmount(int clippingAmount) {
    this.clippingAmount = clippingAmount;
  }
}
