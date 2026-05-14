package org.chuck.deluge.firmware.model.consequence;

import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.note.Note;
import org.chuck.deluge.firmware.model.note.NoteRow;

public class ConsequenceNoteExistence extends Consequence {
  public enum Type {
    ADDED,
    REMOVED
  }

  private final InstrumentClip clip;
  private final int noteRowId;
  private final int pos;
  private final int length;
  private final byte velocity;
  private final Type type;

  public ConsequenceNoteExistence(InstrumentClip clip, int noteRowId, Note note, Type type) {
    this.clip = clip;
    this.noteRowId = noteRowId;
    this.pos = note.pos;
    this.length = note.length;
    this.velocity = note.velocity;
    this.type = type;
  }

  @Override
  public void execute() {
    NoteRow row = clip.noteRows.get(noteRowId);
    if (type == Type.ADDED) {
      row.attemptNoteAdd(pos, length, velocity, 100, null, 0);
    } else {
      row.notes.removeIf(n -> n.pos == pos);
    }
  }
}
