package org.deluge.firmware2;

/**
 * Faithful port of {@code model/scale/musical_key.h}/{@code .cpp} plus the subset of {@code
 * model/scale/note_set} the arpeggiator chord path uses. A {@link NoteSet} is a 12-bit semitone
 * set; {@link MusicalKey} is that set plus a root note.
 */
public class MusicalKey {

  /** Faithful port of NoteSet (note_set.h / note_set.cpp) — a 12-semitone bit set. */
  public static final class NoteSet {
    public static final int SIZE = 12; // C note_set.h:104

    int bits;

    /** C note_set.h:23 — default constructor for an empty NoteSet. */
    public NoteSet() {
      this.bits = 0;
    }

    /** C note_set.h:25 — construct NoteSet from uint16_t bitfield returned by toBits(). */
    public NoteSet(int bits) {
      this.bits = bits & 0xffff;
    }

    /** C note_set.cpp:4-8 — construct NoteSet from semitone notes. */
    public static NoteSet of(int... notes) {
      NoteSet set = new NoteSet();
      for (int note : notes) {
        set.add(note);
      }
      return set;
    }

    /** C note_set.h:31 — add a semitone, masked to 12 bits (0xfff). */
    public void add(int note) {
      if (note >= 0 && note < 16) {
        bits = 0xfff & (bits | (1 << note));
      }
    }

    /** C note_set.h:34 — remove a semitone, masked to 12 bits (0xfff). */
    public void remove(int note) {
      if (note >= 0 && note < 16) {
        bits = 0xfff & (bits & ~(1 << note));
      }
    }

    /** C note_set.h:37 — membership check: (bits >> note) & 1. */
    public boolean has(int note) {
      return note >= 0 && note < 16 && ((bits >>> note) & 1) != 0;
    }

    /** C note_set.cpp:10-22 — addUntrusted: constrain note to strictly increasing and < 12. */
    public void addUntrusted(int note) {
      int uNote = note & 0xff;
      if (count() > 0 && uNote <= highest()) {
        uNote = highest() + 1;
      }
      if (uNote > 11) {
        uNote = 11;
      }
      add(uNote);
    }

    /** C note_set.h:79 — number of notes (popcount). */
    public int count() {
      return Integer.bitCount(bits & 0xffff);
    }

    /** C note_set.cpp:53-59 — degreesBelow: number of notes strictly below `note`. */
    public int degreesBelow(int note) {
      int uNote = note & 0xff;
      int mask = uNote >= 16 ? 0xffff : (~(0xffff << uNote) & 0xffff);
      int under = bits & mask;
      return Integer.bitCount(under & 0xffff);
    }

    /** C note_set.cpp:61-68 — degreeOf. */
    public int degreeOf(int note) {
      if (has(note)) {
        return degreesBelow(note);
      }
      return -1;
    }

    /** C note_set.cpp:24-42 — operator[](index): the note at the given scale degree, or -1. */
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

    /** C note_set.h:63 — mark all 12 semitones present. */
    public void fill() {
      bits = 0xfff;
    }

    /** C note_set.h:66 — remove all semitones. */
    public void clear() {
      bits = 0;
    }

    /** C note_set.h:68 — true if empty. */
    public boolean isEmpty() {
      return bits == 0;
    }

    /**
     * C note_set.h:71 — highest note added (15 - countl_zero(uint16_t(bits))); returns -1 when
     * empty.
     */
    public int highest() {
      return 31 - Integer.numberOfLeadingZeros(bits & 0xffff);
    }

    /** C note_set.cpp:44-51 — highest note in this NoteSet not present in `other`, or -1. */
    public int highestNotIn(NoteSet other) {
      for (int i = highest(); i >= 0; i--) {
        if (has(i) && !other.has(i)) {
          return i;
        }
      }
      return -1;
    }

    /** C note_set.cpp:202-206 — scaleSize: count of notes when root (0) is included. */
    public int scaleSize() {
      NoteSet tmp = new NoteSet(this.bits);
      tmp.add(0);
      return tmp.count();
    }

    /** C note_set.h:88 — true if this NoteSet is a subset of `other`. */
    public boolean isSubsetOf(NoteSet other) {
      return (other.bits & bits) == bits;
    }

    /** C note_set.cpp:70-74 — operator|: union of two NoteSets. */
    public NoteSet union(NoteSet other) {
      return new NoteSet(other.bits | this.bits);
    }

    /** C note_set.cpp:76-100 — majorness: positive is major, negative is minor. */
    public int majorness() {
      int majorness = 0;
      if (has(4)) {
        majorness++;
      }
      if (has(3)) {
        majorness--;
      }
      if (majorness == 0) {
        if (has(1)) {
          majorness--;
        }
        if (has(8)) {
          majorness--;
        }
        if (has(9)) {
          majorness++;
        }
      }
      return majorness;
    }

    /** C note_set.cpp:102-126 — addMajorDependentModeNotes. */
    public void addMajorDependentModeNotes(
        int i, boolean preferHigher, NoteSet notesWithinOctavePresent) {
      if (notesWithinOctavePresent.has(i)) {
        if (notesWithinOctavePresent.has(i + 1)) {
          add(i);
          add(i + 1);
        } else {
          add(i);
        }
      } else {
        if (notesWithinOctavePresent.has(i + 1) || preferHigher) {
          add(i + 1);
        } else {
          add(i);
        }
      }
    }

    /** C note_set.cpp:129-149 — modulateByOffset: rotate 12-bit semitones up by `offset`. */
    public NoteSet modulateByOffset(int offset) {
      NoteSet newSet = new NoteSet(this.bits);
      int normOffset = (offset & 0xff) % 12;
      if (normOffset == 0) {
        return newSet;
      }
      int newBits = bits & 0x0fff;
      int wrapAroundBits = (newBits >>> (12 - normOffset)) & ((1 << normOffset) - 1);
      newBits = ((newBits << normOffset) & 0x0fff) | wrapAroundBits;
      newSet.bits = (bits & 0xf000) | newBits;
      return newSet;
    }

    /** C note_set.cpp:151-200 — toImpliedScale. */
    public NoteSet toImpliedScale() {
      boolean moreMajor = majorness() >= 0;

      NoteSet scale = new NoteSet();
      scale.add(0);

      // 2nd
      scale.addMajorDependentModeNotes(1, true, this);

      // 3rd
      scale.addMajorDependentModeNotes(3, moreMajor, this);

      // 4th, 5th
      if (has(5)) {
        scale.add(5);
        if (has(6)) {
          scale.add(6);
          if (has(7)) {
            scale.add(7);
          }
        } else {
          scale.add(7);
        }
      } else {
        if (has(6)) {
          if (has(7) || moreMajor) {
            scale.add(6);
            scale.add(7);
          } else {
            scale.add(5);
            scale.add(6);
          }
        } else {
          scale.add(5);
          scale.add(7);
        }
      }

      // 6th
      scale.addMajorDependentModeNotes(8, moreMajor, this);

      // 7th
      scale.addMajorDependentModeNotes(10, moreMajor, this);

      return scale;
    }

    /** C note_set.h:103 — uint16_t bitfield for serialization. */
    public int toBits() {
      return bits & 0xffff;
    }

    @Override
    public boolean equals(Object o) {
      return this == o || (o instanceof NoteSet other && this.bits == other.bits);
    }

    @Override
    public int hashCode() {
      return bits;
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
