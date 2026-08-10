package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Utility for musical scale calculations and note folding. */
public class Scales {

  /**
   * ScaleType ordinals must match {@code parseScaleIndex} in SwingDelugeApp: 0=Major, 1=Minor,
   * 2=Harmonic Minor, 3=Melodic Minor, 4=Dorian, 5=Phrygian, 6=Lydian, 7=Mixolydian, 8=Locrian,
   * 9=Whole Tone, 10=Whole Half Dim, 11=Half Whole Dim, 12=Maj Pent, 13=Min Pent, 14=Chromatic.
   */
  public enum ScaleType {
    MAJOR("Major", new int[] {0, 2, 4, 5, 7, 9, 11}),
    MINOR("Minor", new int[] {0, 2, 3, 5, 7, 8, 10}),
    HARMONIC_MINOR("Harmonic Minor", new int[] {0, 2, 3, 5, 7, 8, 11}),
    MELODIC_MINOR("Melodic Minor", new int[] {0, 2, 3, 5, 7, 9, 11}),
    DORIAN("Dorian", new int[] {0, 2, 3, 5, 7, 9, 10}),
    PHRYGIAN("Phrygian", new int[] {0, 1, 3, 5, 7, 8, 10}),
    LYDIAN("Lydian", new int[] {0, 2, 4, 6, 7, 9, 11}),
    MIXOLYDIAN("Mixolydian", new int[] {0, 2, 4, 5, 7, 9, 10}),
    LOCRIAN("Locrian", new int[] {0, 1, 3, 5, 6, 8, 10}),
    WHOLE_TONE("Whole Tone", new int[] {0, 2, 4, 6, 8, 10}),
    WHOLE_HALF_DIM("Whole Half Dim", new int[] {0, 2, 3, 5, 6, 8, 9, 11}),
    HALF_WHOLE_DIM("Half Whole Dim", new int[] {0, 1, 3, 4, 6, 7, 9, 10}),
    MAJOR_PENTATONIC("Maj Pent", new int[] {0, 2, 4, 7, 9}),
    MINOR_PENTATONIC("Min Pent", new int[] {0, 3, 5, 7, 10}),
    CHROMATIC("Chromatic", new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11});

    private final String name;
    private final int[] intervals;

    ScaleType(String name, int[] intervals) {
      this.name = name;
      this.intervals = intervals;
    }

    public String getName() {
      return name;
    }

    public int[] getIntervals() {
      return intervals;
    }
  }

  public static final String[] KEY_NAMES = {
    "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
  };

  /** Check if a MIDI note is in the specified scale and key. */
  public static boolean isNoteInScale(int midiNote, int rootKey, ScaleType type) {
    if (type == ScaleType.CHROMATIC) return true;
    int noteInOctave = (midiNote - rootKey) % 12;
    if (noteInOctave < 0) noteInOctave += 12;
    for (int interval : type.getIntervals()) {
      if (noteInOctave == interval) return true;
    }
    return false;
  }

  /** Get a list of notes that are in the scale, within a certain range. */
  public static List<Integer> getInScaleNotes(
      int rootKey, ScaleType type, int startNote, int count) {
    List<Integer> notes = new ArrayList<>();
    int current = startNote;
    while (notes.size() < count && current < 128) {
      if (isNoteInScale(current, rootKey, type)) {
        notes.add(current);
      }
      current++;
    }
    return notes;
  }

  /**
   * Returns the scale degree (0-based index) of a pitch interval relative to the scale root, or -1
   * if the interval is out of scale.
   *
   * @see "C++ NoteSet::degreeOf in note_set.cpp:61-66"
   */
  public static int degreeOf(int[] modeNotes, int interval) {
    if (modeNotes == null) return -1;
    int normalized = Math.floorMod(interval, 12);
    for (int i = 0; i < modeNotes.length; i++) {
      if (modeNotes[i] == normalized) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns the number of scale notes in modeNotes strictly lower than the given interval (0-11).
   * Unlike degreeOf(), an interval absent from modeNotes returns the degree index it would occupy
   * if added (i.e., the degree of the note immediately above it).
   *
   * @see "C++ NoteSet::degreesBelow in note_set.cpp:53-60"
   */
  public static int degreesBelow(int[] modeNotes, int interval) {
    if (modeNotes == null) return 0;
    int normalized = Math.floorMod(interval, 12);
    int count = 0;
    for (int note : modeNotes) {
      if (note < normalized) {
        count++;
      }
    }
    return count;
  }

  /**
   * Precomputes the 12-entry semitone shift lookup table for vertical note row transposition,
   * matching hardware scale transposition behavior.
   *
   * <p>The C reads the scale from {@code MusicalKey::modeNotes}, a NoteSet that is always populated
   * (its constructor adds note 0), so it has no empty case to handle. A null or empty {@code
   * modeNotes} here falls back to the chromatic scale rather than indexing into nothing.
   *
   * @param modeNotes semitone intervals of the current scale (e.g. [0, 2, 4, 5, 7, 9, 11]); null or
   *     empty is treated as chromatic
   * @param change direction/amount of degree or semitone change (+1/-1, or
   *     +numModeNotes/-numModeNotes for octave)
   * @param isScaleMode true if scale mode is enabled, false for chromatic semitone steps
   * @return 12-entry array mapping pitch class interval (0-11) to semitone shift
   * @see "C++ InstrumentClip::nudgeNotesVertically in instrument_clip.cpp:1362-1388"
   */
  public static int[] computeTransposeShiftTable(int[] modeNotes, int change, boolean isScaleMode) {
    int[] shiftForInterval = new int[12];
    int[] intervals =
        (modeNotes != null && modeNotes.length > 0)
            ? modeNotes
            : ScaleType.CHROMATIC.getIntervals();
    int numModeNotes = intervals.length;

    if (!isScaleMode || Math.abs(change) == numModeNotes) {
      int fixedShift = isScaleMode ? ((change > 0) ? 12 : -12) : change;
      java.util.Arrays.fill(shiftForInterval, fixedShift);
    } else {
      for (int interval = 0; interval < 12; interval++) {
        int degree = degreeOf(intervals, interval);
        int newDegree;
        if (degree >= 0) {
          newDegree = degree + change;
        } else {
          int below = degreesBelow(intervals, interval);
          newDegree = (change > 0) ? (below + change - 1) : (below + change);
        }
        int wrappedDegree = Math.floorMod(newDegree, numModeNotes);
        int octaves = (newDegree - wrappedDegree) / numModeNotes;
        shiftForInterval[interval] = intervals[wrappedDegree] + 12 * octaves - interval;
      }
    }
    return shiftForInterval;
  }

  /**
   * Checks if a new transposed pitch stays within the playable MIDI range (0 to 127).
   *
   * <p>This is a <em>simplification</em> of the C's gate, not a transcription of it. {@code
   * InstrumentClip::isScrollWithinRange} is output-type aware: for a SYNTH it adds {@code
   * getMinOscTranspose()}/{@code getMaxOscTranspose()} to the destination before comparing (so a
   * patch whose oscillators transpose up rejects moves this accepts), for CV it converts to a
   * voltage, and every branch additionally requires the destination to be beyond the clip's current
   * {@code getTopYNote()}/{@code getBottomYNote()} — which lets an already-out-of-range clip be
   * nudged back inward. None of that is modelled here; we have neither per-output osc transpose
   * limits nor a CV voltage table at this layer.
   *
   * @see "C++ InstrumentClip::isScrollWithinRange in instrument_clip.cpp:3637-3682"
   */
  public static boolean isPitchWithinPlayableRange(int pitch) {
    return pitch >= 0 && pitch <= 127;
  }
}
