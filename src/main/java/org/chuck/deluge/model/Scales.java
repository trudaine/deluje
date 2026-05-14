package org.chuck.deluge.model;

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
}
