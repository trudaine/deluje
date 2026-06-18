package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Chord definitions and scale-aware chord construction for the Chord Keyboard (CORK/CORL).
 *
 * <p>Provides chord type definitions with interval patterns, scale-degree-to-note mapping, and
 * chord library entries matching the Deluge firmware c1.3.0 chord keyboard spec.
 */
public class ChordModel {

  // ── Chord types ──

  /** A chord type with a name, quality, and interval pattern (semitones from root). */
  public record ChordType(String name, String quality, int[] intervals) {}

  /** Quality labels used for color coding in the CORK layout. */
  public enum Quality {
    MAJOR,
    MINOR,
    DOMINANT,
    DIMINISHED,
    AUGMENTED,
    OTHER
  }

  /** The standard chord types supported by the chord keyboard. */
  public static final ChordType[] CHORD_TYPES = {
    new ChordType("M", "Major", new int[] {0, 4, 7}),
    new ChordType("-", "Minor", new int[] {0, 3, 7}),
    new ChordType("DIM", "Diminished", new int[] {0, 3, 6}),
    new ChordType("AUG", "Augmented", new int[] {0, 4, 8}),
    new ChordType("SUS2", "Suspended 2", new int[] {0, 2, 7}),
    new ChordType("SUS4", "Suspended 4", new int[] {0, 5, 7}),
    new ChordType("7", "Dominant 7", new int[] {0, 4, 7, 10}),
    new ChordType("M7", "Major 7", new int[] {0, 4, 7, 11}),
    new ChordType("-7", "Minor 7", new int[] {0, 3, 7, 10}),
    new ChordType("DIM7", "Diminished 7", new int[] {0, 3, 6, 9}),
    new ChordType("M7#5", "Augmented Major 7", new int[] {0, 4, 8, 11}),
    new ChordType("7b5", "Dominant 7b5", new int[] {0, 4, 6, 10}),
    new ChordType("-7b5", "Minor 7b5", new int[] {0, 3, 6, 10}),
    new ChordType("7#9", "Dominant 7#9", new int[] {0, 4, 7, 10, 15}),
    new ChordType("7b9", "Dominant 7b9", new int[] {0, 4, 7, 10, 13}),
    new ChordType("9", "Dominant 9", new int[] {0, 4, 7, 10, 14}),
    new ChordType("M9", "Major 9", new int[] {0, 4, 7, 11, 14}),
    new ChordType("-9", "Minor 9", new int[] {0, 3, 7, 10, 14}),
    new ChordType("6", "Major 6", new int[] {0, 4, 7, 9}),
    new ChordType("-6", "Minor 6", new int[] {0, 3, 7, 9}),
    new ChordType("add9", "Add 9", new int[] {0, 4, 7, 14}),
    new ChordType("-add9", "Minor Add 9", new int[] {0, 3, 7, 14}),
    new ChordType("7#11", "Lydian Dominant", new int[] {0, 4, 7, 10, 18}),
    new ChordType("M7#11", "Lydian", new int[] {0, 4, 7, 11, 18}),
    new ChordType("7sus4", "Dominant 7 Sus 4", new int[] {0, 5, 7, 10}),
    new ChordType("13", "Dominant 13", new int[] {0, 4, 7, 10, 14, 21}),
  };

  /** Get the quality for a chord type based on its intervals relative to a scale degree. */
  public static Quality getQuality(ChordType type) {
    if (intervalsContain(type.intervals(), 3, 6)) return Quality.DIMINISHED;
    if (intervalsContain(type.intervals(), 4, 8)) return Quality.AUGMENTED;
    if (intervalsContain(type.intervals(), 4, 11)) return Quality.MAJOR;
    if (intervalsContain(type.intervals(), 4, 10)) return Quality.DOMINANT;
    if (intervalsContain(type.intervals(), 3, 7)) return Quality.MINOR;
    return Quality.OTHER;
  }

  private static boolean intervalsContain(int[] intervals, int a, int b) {
    boolean hasA = false, hasB = false;
    for (int i : intervals) {
      if (i == a) hasA = true;
      if (i == b) hasB = true;
    }
    return hasA && hasB;
  }

  // ── CORK Column Mode: harmonically similar chords per scale degree ──

  /**
   * For a given scale degree (0-indexed, 0=tonic), return the chord types that are harmonically
   * appropriate. This models the firmware's column mode where each column contains chord
   * substitutions for that scale degree.
   */
  public static ChordType[] chordsForScaleDegree(int degree) {
    // Scale degrees: I=0, ii=1, iii=2, IV=3, V=4, vi=5, vii=6
    return switch (degree % 7) {
      case 0 ->
          new ChordType[] {
            CHORD_TYPES[0], // M
            CHORD_TYPES[7], // M7
            CHORD_TYPES[8], // M9
            CHORD_TYPES[17], // 6
            CHORD_TYPES[21], // add9
            CHORD_TYPES[22], // M7#11
          };
      case 1 ->
          new ChordType[] {
            CHORD_TYPES[1], // -
            CHORD_TYPES[8], // -7
            CHORD_TYPES[17], // -6
            CHORD_TYPES[19], // -add9
            CHORD_TYPES[9], // -7b5 (*)
          };
      case 2 ->
          new ChordType[] {
            CHORD_TYPES[1], // -
            CHORD_TYPES[8], // -7
            CHORD_TYPES[3], // AUG (*)
          };
      case 3 ->
          new ChordType[] {
            CHORD_TYPES[0], // M
            CHORD_TYPES[7], // M7
            CHORD_TYPES[17], // 6
            CHORD_TYPES[22], // M7#11
          };
      case 4 ->
          new ChordType[] {
            CHORD_TYPES[6], // 7
            CHORD_TYPES[11], // 7b5
            CHORD_TYPES[13], // 7#9
            CHORD_TYPES[14], // 7b9
            CHORD_TYPES[15], // 9
            CHORD_TYPES[13], // 13
            CHORD_TYPES[24], // 7sus4
            CHORD_TYPES[21], // 7#11
          };
      case 5 ->
          new ChordType[] {
            CHORD_TYPES[1], // -
            CHORD_TYPES[8], // -7
            CHORD_TYPES[18], // -6
            CHORD_TYPES[15], // -9
          };
      case 6 ->
          new ChordType[] {
            CHORD_TYPES[2], // DIM
            CHORD_TYPES[9], // -7b5
            CHORD_TYPES[10], // DIM7
          };
      default -> new ChordType[] {CHORD_TYPES[0]};
    };
  }

  // ── CORK Row Mode: interval spread per row ──

  /**
   * Row mode intervals as specified in firmware: each row starts from the next scale degree, and
   * columns contain specific intervals (in scale steps, not semitones) from that row's root.
   */
  public static final int[][] ROW_MODE_INTERVALS = {
    // Scale-step intervals from row root: 0=root, 1=2nd, 2=3rd, etc.
    // Semitones will be derived by looking up the actual scale interval.
    {
      0, 4, 2, 6, 4, 2, 1, 5, 7, 4, 6, 2, 1
    }, // Row 0: I, 5th, 3rd+8ve, 7th+8ve, 5th+8ve, 3rd+16ve, 2nd+16ve, 6th+8ve, 8ve, 5th, 7th,
    // 3rd-8ve, 2nd
    // (scale step offsets mapped from firmware spec)
  };

  // ── CORL Chord Library ──

  /** Chord library row index → chord type mapping. Row 0=root, 1=M, 2=-, 3=DIM, etc. */
  public static final ChordType[] CHORD_LIBRARY_ROWS = {
    null, // Row 0: root note (single note)
    CHORD_TYPES[0], // Row 1: M (major triad)
    CHORD_TYPES[1], // Row 2: - (minor triad)
    CHORD_TYPES[2], // Row 3: DIM
    CHORD_TYPES[3], // Row 4: AUG
    CHORD_TYPES[17], // Row 5: 6
    CHORD_TYPES[18], // Row 6: -6
    CHORD_TYPES[6], // Row 7: 7
    CHORD_TYPES[8], // Row 8: -7
    CHORD_TYPES[7], // Row 9: M7
    CHORD_TYPES[4], // Row 10: SUS2
    CHORD_TYPES[5], // Row 11: SUS4
    CHORD_TYPES[9], // Row 12: -7b5
    CHORD_TYPES[10], // Row 13: DIM7
    CHORD_TYPES[13], // Row 14: 7#9
    CHORD_TYPES[15], // Row 15: 9
    CHORD_TYPES[16], // Row 16: M9
    CHORD_TYPES[11], // Row 17: 7b5
    CHORD_TYPES[20], // Row 18: 7#11
    CHORD_TYPES[24], // Row 19: 7sus4
    CHORD_TYPES[14], // Row 20: 7b9
    CHORD_TYPES[12], // Row 21: M7#5
    CHORD_TYPES[19], // Row 22: -add9
    CHORD_TYPES[22], // Row 23: M7#11
    CHORD_TYPES[21], // Row 24: add9
    CHORD_TYPES[23], // Row 25: 13
  };

  // ── Utility: build note list from chord type + root ──

  /** Build the list of MIDI note numbers for a chord given a root note and chord type. */
  public static List<Integer> buildChord(int rootMidiNote, ChordType type) {
    List<Integer> notes = new ArrayList<>(type.intervals().length);
    for (int interval : type.intervals()) {
      int octave = interval / 12;
      int semitone = interval % 12;
      notes.add(rootMidiNote + octave * 12 + semitone);
    }
    return notes;
  }

  /** Build the list of MIDI note numbers for a chord rooted on a scale degree in a given key. */
  public static List<Integer> buildChordForScaleDegree(
      int rootKey, Scales.ScaleType scale, int degree, ChordType type) {
    int[] scaleIntervals = scale.getIntervals();
    int degreeInterval = scaleIntervals[degree % scaleIntervals.length];
    int octaveShift = (degree / scaleIntervals.length) * 12;
    int rootNote = rootKey + degreeInterval + octaveShift;
    // Clamp to MIDI range (36-96 is typical playable range)
    while (rootNote < 36) rootNote += 12;
    while (rootNote > 96) rootNote -= 12;
    return buildChord(rootNote, type);
  }

  /** Get the note name for a MIDI note number (e.g., 60 → "C"). */
  public static String noteName(int midiNote) {
    return Scales.KEY_NAMES[midiNote % 12];
  }

  /** Format a chord name for display (e.g., rootNote=60, type=CM → "C M"). */
  public static String formatChordName(int rootNote, ChordType type) {
    return noteName(rootNote) + type.name();
  }
}
