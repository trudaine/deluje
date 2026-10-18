package org.deluge.model;

/**
 * Manages the collection of all clips in Song Mode. Holds 8 tracks, each with 8 clip slots (A-H).
 */
public class ClipLibrary {
  private final Clip[][] clips; // [tracks][slots]

  public ClipLibrary(int tracks, int slots) {
    int safeTracks = Math.max(0, tracks);
    int safeSlots = Math.max(0, slots);
    clips = new Clip[safeTracks][safeSlots];
    for (int t = 0; t < safeTracks; t++) {
      for (int s = 0; s < safeSlots; s++) {
        clips[t][s] = new Clip();
      }
    }
  }

  public Clip getClip(int track, int slot) {
    if (track < 0 || track >= clips.length || slot < 0 || slot >= clips[track].length) {
      return null;
    }
    return clips[track][slot];
  }
}
