package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Represents a Kit track containing multiple distinct drum sounds. */
public class KitTrackModel extends TrackModel {

  private final List<Drum> drums = new ArrayList<>();
  private int selectedDrumIndex = 0;
  private float pan = 0.0f;

  @Override
  public float getPan() {
    return pan;
  }

  @Override
  public void setPan(float v) {
    this.pan = Math.max(-1.0f, Math.min(1.0f, v));
  }

  /**
   * The kit clip's own arpeggiator (C InstrumentClip::arpSettings, the kit arp —
   * kit.cpp:1136-1222); null = the file had none (the C default, OFF).
   */
  private ArpModel arp;

  public ArpModel getArp() {
    return arp;
  }

  public void setArp(ArpModel arp) {
    this.arp = arp;
  }

  public KitTrackModel(String name) {
    super(name, TrackType.KIT);
    // C global_effectable.cpp:81 — UNPATCHED_VOLUME initializes to 0 ("half of the way up" ->
    // 0.5f),
    // matching GlobalEffectable.kitVolume = 0 so new kits do not jump +12 dB on song save/reload.
    setVolume(0.5f);
  }

  public int getSelectedDrumIndex() {
    return selectedDrumIndex;
  }

  public void setSelectedDrumIndex(int selectedDrumIndex) {
    this.selectedDrumIndex = selectedDrumIndex;
  }

  public List<Drum> getDrums() {
    return drums;
  }

  public void addDrum(Drum drum) {
    drums.add(drum);
  }

  /**
   * Convenience: returns the sample path of the first SoundDrum, or empty string if there are no
   * SoundDrum entries.
   */
  public String getSamplePath() {
    for (Drum d : drums) {
      if (d instanceof SoundDrum sd) {
        return sd.getSamplePath();
      }
    }
    return "";
  }
}
