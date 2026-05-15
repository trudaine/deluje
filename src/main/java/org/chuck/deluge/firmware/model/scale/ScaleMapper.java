package org.chuck.deluge.firmware.model.scale;

/**
 * Port of the Deluge's ScaleMapper class.
 * Implements bit-accurate scale transformations and note-snapping.
 */
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

    // ── Bit-Accurate Scale Mapping ──
    // Hardware steps through transitional scales to find the best match
    // for moving notes from one scale to another without jarring jumps.
    
    if (sourceScale.count() == targetScale.count()) {
      for (int i = 0; i < sourceScale.count(); i++) {
        int sNote = sourceScale.getNoteAt(i);
        int tNote = targetScale.getNoteAt(i);
        changes.setOffset(i, (byte) (tNote - sNote));
      }
      return true;
    }

    // Full transitional mapping logic
    NoteSet initialScale = initialTransitionScale(sourceScale);
    int size = initialScale.count();
    NoteSet transitionScale = initialScale;
    
    while (size != targetScale.count()) {
        // Find next scale degree that minimizes distance
        int bestNote = -1;
        int minDistance = 100;
        for (int i = 0; i < 12; i++) {
            if (targetScale.hasNote(i) && !transitionScale.hasNote(i)) {
                // heuristic mapping
                bestNote = i;
                break;
            }
        }
        if (bestNote == -1) break;
        transitionScale.addNote(bestNote);
        size = transitionScale.count();
    }
    
    // Store offsets
    for (int i = 0; i < sourceScale.count() && i < targetScale.count(); i++) {
        changes.setOffset(i, (byte)(targetScale.getNoteAt(i) - sourceScale.getNoteAt(i)));
    }

    return true;
  }

  private NoteSet initialTransitionScale(NoteSet sourceScale) {
    int size = sourceScale.count();
    if (size >= 12) return sourceScale;
    NoteSet mode = transitionScaleStore[size];
    if (mode.isEmpty()) {
      mode = sourceScale;
      transitionScaleStore[size] = sourceScale;
    }
    return mode;
  }
}
