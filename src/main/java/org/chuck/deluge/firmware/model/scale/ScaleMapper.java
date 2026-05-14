package org.chuck.deluge.firmware.model.scale;

public class ScaleMapper {
  private NoteSet lastTransitionNotes = new NoteSet();
  private final NoteSet[] transitionScaleStore = new NoteSet[12];

  public ScaleMapper() {
    for (int i = 0; i < 12; i++) transitionScaleStore[i] = new NoteSet();
  }

  public boolean computeChangeFrom(
      NoteSet notes, NoteSet sourceScale, NoteSet targetScale, ScaleChange changes) {
    if (notes.scaleSize() > targetScale.scaleSize() || !notes.isSubsetOf(sourceScale)) {
      return false; // oops SM01
    }
    changes.source = sourceScale;
    changes.target = targetScale;

    // Simplified: direct mapping if sizes match
    if (sourceScale.count() == targetScale.count()) {
      for (int i = 0; i < sourceScale.count(); i++) {
        int sNote = sourceScale.getNoteAt(i);
        int tNote = targetScale.getNoteAt(i);
        changes.setOffset(i, (byte) (tNote - sNote));
      }
      return true;
    }

    return false; // stub for complex transitions
  }
}
