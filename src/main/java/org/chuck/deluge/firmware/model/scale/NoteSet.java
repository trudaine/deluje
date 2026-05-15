package org.chuck.deluge.firmware.model.scale;

public class NoteSet {
  public static final int size = 12;
  private int bits;

  public NoteSet() {
    this.bits = 0;
  }

  public NoteSet(int bits) {
    this.bits = bits & 0xFFF;
  }

  public void add(int note) {
    bits |= (1 << (note % 12));
    bits &= 0xFFF;
  }

  public void addNote(int note) { add(note); }

  public void remove(int note) {
    bits &= ~(1 << (note % 12));
    bits &= 0xFFF;
  }

  public boolean has(int note) {
    return (bits & (1 << (note % 12))) != 0;
  }

  public boolean hasNote(int note) { return has(note); }

  public int count() {
    return Integer.bitCount(bits);
  }

  public boolean isEmpty() {
    return bits == 0;
  }

  public boolean isSubsetOf(NoteSet other) {
    return (other.bits & this.bits) == this.bits;
  }

  public int toBits() {
    return bits;
  }

  public int getNoteAt(int index) {
    int count = 0;
    for (int i = 0; i < 12; i++) {
      if (has(i)) {
        if (count == index) return i;
        count++;
      }
    }
    return -1;
  }

  public int degreeOf(int note) {
    if (!has(note)) return -1;
    int count = 0;
    for (int i = 0; i < (note % 12); i++) {
      if (has(i)) count++;
    }
    return count;
  }

  public int scaleSize() {
    return count();
  }
}
