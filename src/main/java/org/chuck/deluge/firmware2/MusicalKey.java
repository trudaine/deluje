package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code model/scale/musical_key.h}/{@code .cpp} plus the subset of {@code
 * model/scale/note_set} the arpeggiator chord path uses. A {@link NoteSet} is a 12-bit semitone
 * set; {@link MusicalKey} is that set plus a root note.
 */
public class MusicalKey {

  /** Faithful port of NoteSet (note_set.h / note_set.cpp) — a 12-semitone bit set. */
  public static final class NoteSet {
    int bits;

    /** note_set.h — add a semitone. */
    public void add(int note) {
      bits |= (1 << note);
    }

    /** note_set.h — membership. */
    public boolean has(int note) {
      return (bits & (1 << note)) != 0;
    }

    /** note_set — number of notes (popcount). */
    public int count() {
      return Integer.bitCount(bits & 0xffff);
    }

    /** note_set.cpp:53-64 — degreeOf. */
    public int degreeOf(int note) {
      if (has(note)) {
        int mask = ~(0xffff << note); // notes below `note`
        int under = bits & mask;
        return Integer.bitCount(under & 0xffff); // popcount
      }
      return -1;
    }

    /** note_set.cpp:24-42 — operator[](index): the note at the given scale degree, or -1. */
    public int get(int index) {
      int wip = bits & 0xffff;
      int note = -1;
      int n = -1;
      while (index > n++) {
        if (wip == 0) {
          return -1; // the desired degree does not exist
        }
        int step = Integer.numberOfTrailingZeros(wip) + 1; // countr_zero + 1
        wip >>>= step;
        note += step;
      }
      return note;
    }
  }

  public final NoteSet modeNotes = new NoteSet();
  public int rootNote;

  /** musical_key.cpp:6-9 — default key: a single mode note at the root. */
  public MusicalKey() {
    modeNotes.add(0);
    rootNote = 0;
  }

  /** musical_key.cpp:11-13 — semitone offset from root: mod(noteCode - rootNote, 12). */
  public int intervalOf(int noteCode) {
    int x = noteCode - rootNote;
    return ((x % 12) + 12) % 12; // positive modulo (C util mod)
  }

  /** musical_key.cpp:15-17 — degree of the noteCode within the mode. */
  public int degreeOf(int noteCode) {
    return modeNotes.degreeOf(intervalOf(noteCode));
  }
}
