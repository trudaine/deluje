package org.deluge.firmware.model.note;

import org.deluge.firmware.model.iterance.Iterance;

public class PendingNoteOn {
  public NoteRow noteRow;
  public int noteRowId;
  public int sampleSyncLength;
  public int ticksLate;
  public int probability;
  public int velocity;
  public Iterance iterance;
  public int fill;

  public PendingNoteOn(
      NoteRow noteRow, int velocity, int probability, Iterance iterance, int fill) {
    this.noteRow = noteRow;
    this.velocity = velocity;
    this.probability = probability;
    this.iterance = iterance;
    this.fill = fill;
  }
}
