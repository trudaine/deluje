package org.chuck.deluge.model;

/**
 * Manages the collection of all clips in Song Mode.
 * Holds 8 tracks, each with 8 clip slots (A-H).
 */
public class ClipLibrary {
  private final Clip[][] clips; // [tracks][slots]

  public ClipLibrary(int tracks, int slots) {
    clips = new Clip[tracks][slots];
    for (int t = 0; t < tracks; t++) {
      for (int s = 0; s < slots; s++) {
        clips[t][s] = new Clip();
      }
    }
  }

  public Clip getClip(int track, int slot) {
    if (track < 0 || track >= clips.length || slot < 0 || slot >= clips[0].length) {
      return null;
    }
    return clips[track][slot];
  }
}
