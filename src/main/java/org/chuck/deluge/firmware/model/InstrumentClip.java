package org.chuck.deluge.firmware.model;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.model.note.NoteRow;
import org.chuck.deluge.firmware.model.note.PendingNoteOn;

public class InstrumentClip extends Clip {
  public FirmwareSound sound;
  public List<NoteRow> noteRows = new ArrayList<>();
  public int lastProcessedPos = 0;
  public int ticksTilNextEvent = Integer.MAX_VALUE;

  public InstrumentClip() {
    super(ClipType.INSTRUMENT);
  }

  @Override
  public int getMaxLength() {
    return Integer.MAX_VALUE;
  }

  @Override
  public void resumePlayback(boolean mayMakeSound) {
    // stub
  }

  @Override
  public void expectNoFurtherTicks(boolean actuallySoundChange) {
    // stub
  }

  @Override
  public boolean isPlayingAutomationNow() {
    return false;
  }

  @Override
  public boolean backtrackingCouldLoopBackToEnd() {
    return false;
  }

  @Override
  public void processCurrentPos(int ticksSinceLast) {
    super.processCurrentPos(ticksSinceLast);

    List<PendingNoteOn> pendingNoteOns = new ArrayList<>();
    ticksTilNextEvent = Integer.MAX_VALUE;

    for (NoteRow noteRow : noteRows) {
      int dist =
          noteRow.processCurrentPos(
              ticksSinceLast,
              pendingNoteOns,
              lastProcessedPos,
              loopLength,
              currentlyPlayingReversed);
      if (dist < ticksTilNextEvent) {
        ticksTilNextEvent = dist;
      }
    }

    // At least make sure we come back at the end of this Clip
    int ticksTilEnd = currentlyPlayingReversed ? lastProcessedPos : (loopLength - lastProcessedPos);
    if (ticksTilEnd <= 0) ticksTilEnd = loopLength;

    if (ticksTilEnd < ticksTilNextEvent) {
      ticksTilNextEvent = ticksTilEnd;
    }

    // Trigger notes in Java sound engine...
    for (PendingNoteOn noteOn : pendingNoteOns) {
      triggerNote(noteOn);
    }
  }

  private void triggerNote(PendingNoteOn noteOn) {
    // System.out.println("Triggering note: " + noteOn.noteRow.y + " vel: " + noteOn.velocity);
  }
}
