package org.chuck.deluge.firmware.model.note;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.model.iterance.Iterance;
import org.chuck.deluge.firmware.modulation.params.ParamManager;

public class NoteRow {
  public int y;
  public boolean muted;
  public boolean mutedBeforeStemExport;
  public boolean exportStem;

  public int loopLengthIfIndependent;
  public int lastProcessedPosIfIndependent;
  public int repeatCountIfIndependent;
  public boolean currentlyPlayingReversedIfIndependent;

  public List<Note> notes = new ArrayList<>();
  public ParamManager paramManager = new ParamManager();

  public int probabilityValue;
  public Iterance iteranceValue = new Iterance();
  public int fillValue;

  public boolean sequenced;
  public int ignoreNoteOnsBefore_;

  public NoteRow(int y) {
    this.y = y;
    this.muted = false;
    this.loopLengthIfIndependent = 0;
    this.sequenced = true;
  }

  public int getNoteCode() {
    return y;
  }

  public boolean hasNoNotes() {
    return notes.isEmpty();
  }

  public int getNumNotes() {
    return notes.size();
  }

  public int attemptNoteAdd(
      int pos, int length, int velocity, int probability, Iterance iterance, int fill) {
    Note note = new Note();
    note.pos = pos;
    note.length = length;
    note.setVelocity(velocity);
    note.setProbability(probability);
    note.setIterance(iterance);
    note.setFill(fill);
    notes.add(note);
    return 0;
  }

  public int processCurrentPos(
      int ticksSinceLast,
      List<PendingNoteOn> pendingNoteOns,
      int clipLastProcessedPos,
      int clipLoopLength,
      boolean clipCurrentlyPlayingReversed) {

    int effectiveLength = (loopLengthIfIndependent > 0) ? loopLengthIfIndependent : clipLoopLength;
    boolean playingReversedNow =
        (loopLengthIfIndependent > 0)
            ? currentlyPlayingReversedIfIndependent
            : clipCurrentlyPlayingReversed;
    boolean didPingpong = false;

    if (loopLengthIfIndependent > 0) {
      if (playingReversedNow) {
        if (lastProcessedPosIfIndependent < 0) {
          lastProcessedPosIfIndependent += effectiveLength;
        }
        if (lastProcessedPosIfIndependent == 0) {
          repeatCountIfIndependent++;
          // Assume we check direction here, for now just wrap
          lastProcessedPosIfIndependent = 0;
        }
      } else {
        int ticksTilEnd = effectiveLength - lastProcessedPosIfIndependent;
        if (ticksTilEnd <= 0) {
          lastProcessedPosIfIndependent -= effectiveLength;
          repeatCountIfIndependent++;
        }
      }
    }

    int currentPos =
        (loopLengthIfIndependent > 0) ? lastProcessedPosIfIndependent : clipLastProcessedPos;

    if (paramManager.mightContainAutomation()) {
      paramManager.processCurrentPos(
          currentPos, effectiveLength, playingReversedNow, didPingpong, true);
    }

    if (muted) return Integer.MAX_VALUE;

    int ticksTilNextNoteEvent = Integer.MAX_VALUE;

    // Find notes that are triggered at exactly currentPos
    for (Note note : notes) {
      if (note.pos == currentPos) {
        if (currentPos >= ignoreNoteOnsBefore_) {
          pendingNoteOns.add(
              new PendingNoteOn(
                  this,
                  note.getVelocity(),
                  note.getProbability(),
                  note.getIterance(),
                  note.getFill()));
        }
      }

      // Calculate distance to next note for ticksTilNextEvent
      int dist = note.pos - currentPos;
      if (playingReversedNow) dist = -dist;
      if (dist <= 0) dist += effectiveLength;
      if (dist < ticksTilNextNoteEvent) {
        ticksTilNextNoteEvent = dist;
      }
    }

    return Math.min(ticksTilNextNoteEvent, paramManager.ticksTilNextEvent);
  }
}
