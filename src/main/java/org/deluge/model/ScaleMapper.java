package org.deluge.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure musical logic utility for mapping grid rows to MIDI pitches, handling diatonic/chromatic
 * scales, and calculating folded pitch layouts. This class contains no GUI dependencies and is
 * fully unit-testable.
 */
public final class ScaleMapper {
  private ScaleMapper() {}

  /**
   * Calculates the MIDI pitch for a given grid row.
   *
   * @param modelRow the row index from the top (0 is the highest pitch)
   * @param isSynth true if the track is a synthesizer track (otherwise pitch mapping doesn't apply)
   * @param scaleModeEnabled true for diatonic (major scale) mode, false for chromatic mode
   * @param foldMode true if fold mode is active (only showing rows with active notes)
   * @param foldedPitches the list of active pitches in fold mode
   * @return the MIDI pitch (0-127), or 60 (C4) as a fallback
   */
  public static int getRowPitch(
      int modelRow,
      boolean isSynth,
      boolean scaleModeEnabled,
      boolean foldMode,
      List<Integer> foldedPitches) {
    if (isSynth) {
      if (foldMode && foldedPitches != null && !foldedPitches.isEmpty()) {
        if (modelRow >= 0 && modelRow < foldedPitches.size()) {
          return foldedPitches.get(modelRow);
        }
      }
      return scaleModeEnabled ? getDiatonicPitch(modelRow) : getChromaticPitch(modelRow);
    }
    return 60; // fallback
  }

  /**
   * Calculates the grid row index for a given MIDI pitch.
   *
   * @param pitch the MIDI pitch (0-127)
   * @param isSynth true if the track is a synthesizer track
   * @param scaleModeEnabled true for diatonic mode, false for chromatic mode
   * @param foldMode true if fold mode is active
   * @param foldedPitches the list of active pitches in fold mode
   * @return the row index (0-127), or -1 if not found (in fold mode), or the chromatic row fallback
   */
  public static int getRowFromPitch(
      int pitch,
      boolean isSynth,
      boolean scaleModeEnabled,
      boolean foldMode,
      List<Integer> foldedPitches) {
    if (isSynth) {
      if (foldMode && foldedPitches != null && !foldedPitches.isEmpty()) {
        for (int i = 0; i < foldedPitches.size(); i++) {
          if (foldedPitches.get(i) == pitch) return i;
        }
        return -1;
      }
      for (int r = 0; r < 128; r++) {
        if (getRowPitch(r, true, scaleModeEnabled, foldMode, foldedPitches) == pitch) return r;
      }
    }
    return 127 - pitch;
  }

  /**
   * Maps a model row to a diatonic (major scale) pitch. In the Deluge, row 67 is C4 (MIDI 60) by
   * default.
   */
  public static int getDiatonicPitch(int modelRow) {
    int baseDegree = 28; // C4 (4th octave in diatonic degrees, 28 = 4 * 7)
    int degree = baseDegree + (67 - modelRow);
    int octave = Math.floorDiv(degree, 7);
    int rem = Math.floorMod(degree, 7);
    int[] majorScaleOffsets = new int[] {0, 2, 4, 5, 7, 9, 11};
    int pitch = ((octave + 1) * 12) + majorScaleOffsets[rem];
    return Math.max(0, Math.min(pitch, 127));
  }

  /** Maps a model row to a chromatic pitch. In the Deluge, row 67 is C4 (MIDI 60) by default. */
  public static int getChromaticPitch(int modelRow) {
    return Math.max(0, Math.min(127, 60 + (67 - modelRow)));
  }

  /** Determines if a MIDI pitch is an accidental (black key on a piano). */
  public static boolean isAccidental(int pitch) {
    int rem = Math.floorMod(pitch, 12);
    return rem == 1 || rem == 3 || rem == 6 || rem == 8 || rem == 10; // C#, D#, F#, G#, A#
  }

  /**
   * Scans a clip to find all pitches that have active notes, returning them sorted from highest to
   * lowest pitch (suitable for grid row mapping).
   *
   * @param clip the clip to scan
   * @return a list of active pitches, sorted descending
   */
  public static List<Integer> calculateFoldedPitches(ClipModel clip) {
    List<Integer> folded = new ArrayList<>();
    if (clip == null) return folded;

    Set<Integer> pitchSet = new TreeSet<>();
    for (int r = 0; r < clip.getRowCount(); r++) {
      int yNote = clip.getRowYNote(r);
      boolean hasNotes = false;
      for (int s = 0; s < clip.getStepCount(); s++) {
        StepData step = clip.getStep(r, s);
        if (step != null && step.active()) {
          hasNotes = true;
          break;
        }
      }
      if (hasNotes && yNote >= 0 && yNote < 128) {
        pitchSet.add(yNote);
      }
    }
    // TreeSet is sorted ascending. We want descending (highest pitch at the top of the grid, i.e.
    // index 0)
    for (int pitch : pitchSet) {
      folded.add(0, pitch);
    }
    return folded;
  }
}
