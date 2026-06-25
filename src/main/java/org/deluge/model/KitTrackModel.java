package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Represents a Kit track containing multiple distinct drum sounds. */
public class KitTrackModel extends TrackModel {

  private final List<Drum> drums = new ArrayList<>();
  private int selectedDrumIndex = 0;

  public KitTrackModel(String name) {
    super(name, TrackType.KIT);
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
