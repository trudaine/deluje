package org.chuck.deluge.firmware.model.scale;

public class ScaleChange {
  public NoteSet source = new NoteSet();
  public NoteSet target = new NoteSet();
  private final byte[] degreeOffset = new byte[12];

  public byte getOffset(int degree) {
    return degreeOffset[degree];
  }

  public void setOffset(int degree, byte offset) {
    degreeOffset[degree] = offset;
  }

  public NoteSet applyTo(NoteSet notes) {
    NoteSet result = new NoteSet();
    for (int i = 0; i < 12; i++) {
      if (notes.has(i)) {
        int degree = source.degreeOf(i);
        if (degree != -1) {
          result.add(i + degreeOffset[degree]);
        }
      }
    }
    return result;
  }
}
