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
    // Legacy entry point: C major, C root.
    return getRowPitch(
        modelRow,
        isSynth,
        scaleModeEnabled,
        foldMode,
        foldedPitches,
        Scales.ScaleType.MAJOR.getIntervals(),
        0);
  }

  /** As {@link #getRowPitch}, but with the song's actual scale intervals and root pitch class. */
  public static int getRowPitch(
      int modelRow,
      boolean isSynth,
      boolean scaleModeEnabled,
      boolean foldMode,
      List<Integer> foldedPitches,
      int[] modeNotes,
      int rootPitchClass) {
    if (isSynth) {
      if (foldMode && foldedPitches != null && !foldedPitches.isEmpty()) {
        if (modelRow >= 0 && modelRow < foldedPitches.size()) {
          return foldedPitches.get(modelRow);
        }
      }
      return scaleModeEnabled
          ? getDiatonicPitch(modelRow, modeNotes, rootPitchClass)
          : getChromaticPitch(modelRow);
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
    // Legacy entry point: assumes C major (root 0). Prefer the overload with the song's actual
    // scale/root so non-major keys map correctly.
    return getDiatonicPitch(modelRow, Scales.ScaleType.MAJOR.getIntervals(), 0);
  }

  /**
   * Faithful port of {@code Song::getYNoteFromYVisual} (song.cpp) for scale (in-key) mode: {@code
   * pitch = modeNotes[within] + octave*12 + rootNote}, where {@code within}/{@code octave} come
   * from the scale-degree coordinate and the scale has {@code modeNotes.length} notes per octave.
   * Replaces the previous hardcoded 7-note C-major table, which mapped the wrong pitches for any
   * minor/modal/pentatonic scale or non-C root. Anchored so C major with a C root reproduces the
   * legacy chromatic anchor (row 67 -> C4 = MIDI 60), keeping existing C-major songs unchanged.
   *
   * @param modeNotes the scale's semitone offsets within an octave (e.g. major {0,2,4,5,7,9,11})
   * @param rootPitchClass the song key's root pitch class (0 = C .. 11 = B)
   */
  public static int getDiatonicPitch(int modelRow, int[] modeNotes, int rootPitchClass) {
    int count = modeNotes.length;
    int degree = 5 * count + (67 - modelRow); // scale-degree coordinate (row 67 -> degree 5*count)
    int within = Math.floorMod(degree, count);
    int octave = Math.floorDiv(degree, count);
    int pitch = modeNotes[within] + octave * 12 + rootPitchClass;
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

  /** Maps a musical key name (e.g., "C", "C#", "DF") to its MIDI note offset (0-11). */
  public static int getKeyMidiOffset(String key) {
    if (key == null) return 0;
    switch (key.toUpperCase().trim()) {
      case "C":
        return 0;
      case "C#":
      case "DF": // D flat (F used for flat)
        return 1;
      case "D":
        return 2;
      case "D#":
      case "EF": // E flat
        return 3;
      case "E":
        return 4;
      case "F":
        return 5;
      case "F#":
      case "GF": // G flat
        return 6;
      case "G":
        return 7;
      case "G#":
      case "AF": // A flat
        return 8;
      case "A":
        return 9;
      case "A#":
      case "BF": // B flat
        return 10;
      case "B":
        return 11;
      default:
        return 0;
    }
  }

  /** Resolves a scale name to a {@link Scales.ScaleType}. */
  public static Scales.ScaleType scaleTypeFromName(String scale) {
    if (scale == null) return Scales.ScaleType.MAJOR;
    String s = scale.trim();
    if (s.equalsIgnoreCase("Pentatonic") || s.equalsIgnoreCase("Pentatonic Major")) {
      return Scales.ScaleType.MAJOR_PENTATONIC;
    }
    if (s.equalsIgnoreCase("Pentatonic Minor")) {
      return Scales.ScaleType.MINOR_PENTATONIC;
    }
    for (Scales.ScaleType t : Scales.ScaleType.values()) {
      if (t.getName().equalsIgnoreCase(s)) {
        return t;
      }
    }
    return Scales.ScaleType.MAJOR;
  }

  /** Checks if a MIDI note is in the specified key and scale. */
  public static boolean isNoteInScale(int note, String key, String scale) {
    if (scale == null) return true;
    return Scales.isNoteInScale(note, getKeyMidiOffset(key), scaleTypeFromName(scale));
  }

  /** Checks if a MIDI note is the root note of the specified key. */
  public static boolean isRootNote(int note, String key) {
    int rootOffset = getKeyMidiOffset(key);
    int diff = note - rootOffset;
    return (diff % 12) == 0;
  }

  /**
   * Calculates a hue-shifted color hue for a given pitch, based on a base HSB hue and a color
   * offset. This implements the Deluge's iconic row-based color-shifting.
   *
   * @param pitchMidi the MIDI pitch
   * @param baseHue the base track hue (0.0 - 1.0)
   * @param colourOffset the track's color offset
   * @return the calculated hue for the note (0.0 - 1.0)
   */
  public static float calculateNoteHue(int pitchMidi, float baseHue, int colourOffset) {
    float hueShift = ((pitchMidi + colourOffset) * -8.0f / 3.0f) / 192.0f;
    float noteHue = (baseHue + hueShift) % 1.0f;
    if (noteHue < 0) noteHue += 1.0f;
    return noteHue;
  }

  /** Maps a MIDI pitch (0-127) to a human-readable note name (e.g. "C4", "D#5"). */
  public static String getNoteName(int pitchMidi) {
    if (pitchMidi < 0) return "---";
    String[] names = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};
    int noteIdx = pitchMidi % 12;
    int octave = (pitchMidi / 12) - 1;
    return names[noteIdx] + octave;
  }
}
