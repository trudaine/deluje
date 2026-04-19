package org.chuck.deluge.ui.popover;

import java.util.HashSet;
import java.util.Set;

/** Utility to filter MIDI pitches based on scale and root. */
public class ScaleFilter {

  public enum Scale {
    CHROMATIC(new int[] {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}),
    MAJOR(new int[] {0, 2, 4, 5, 7, 9, 11}),
    MINOR(new int[] {0, 2, 3, 5, 7, 8, 10}),
    DORIAN(new int[] {0, 2, 3, 5, 7, 9, 10}),
    MIXOLYDIAN(new int[] {0, 2, 4, 5, 7, 9, 10}),
    PENTATONIC_MAJOR(new int[] {0, 2, 4, 7, 9}),
    PENTATONIC_MINOR(new int[] {0, 3, 5, 7, 10});

    private final int[] intervals;

    Scale(int[] intervals) {
      this.intervals = intervals;
    }

    public int[] getIntervals() {
      return intervals;
    }
  }

  /** Returns true if the given midiNote belongs to the scale with given root. */
  public static boolean isNoteInScale(int midiNote, int rootMidi, Scale scale) {
    int noteInOctave = (midiNote - rootMidi) % 12;
    if (noteInOctave < 0) noteInOctave += 12;

    for (int interval : scale.getIntervals()) {
      if (noteInOctave == interval) return true;
    }
    return false;
  }

  /** Returns the set of note names for a scale. */
  public static Set<Integer> getScaleIntervals(Scale scale) {
    Set<Integer> set = new HashSet<>();
    for (int i : scale.getIntervals()) set.add(i);
    return set;
  }
}
