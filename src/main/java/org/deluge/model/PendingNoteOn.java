package org.deluge.model;

/** Struct representing a pending note trigger event. Migrated to use the unified NoteRowModel. */
public class PendingNoteOn {
  public NoteRowModel noteRow;
  public int noteRowId;
  public int sampleSyncLength;
  public int ticksLate;
  public int probability;
  public int velocity;
  public Iterance iterance;
  public int fill;

  public PendingNoteOn(
      NoteRowModel noteRow, int velocity, int probability, Iterance iterance, int fill) {
    this.noteRow = noteRow;
    this.velocity = velocity;
    this.probability = probability;
    this.iterance = iterance;
    this.fill = fill;
  }
}
