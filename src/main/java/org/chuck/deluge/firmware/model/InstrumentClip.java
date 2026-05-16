package org.chuck.deluge.firmware.model;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.engine.FirmwareKit;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.engine.GlobalEffectable;
import org.chuck.deluge.firmware.model.note.NoteRow;
import org.chuck.deluge.firmware.model.note.PendingNoteOn;

/**
 * Port of the Deluge's InstrumentClip class. Handles sequencing for a specific instrument (Synth or
 * Kit).
 */
public class InstrumentClip extends Clip {
  public GlobalEffectable sound; // Can be a FirmwareSound (Synth) or FirmwareKit
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
    for (NoteRow noteRow : noteRows) {
      noteRow.resumePlayback(lastProcessedPos, loopLength, mayMakeSound);
    }
  }

  @Override
  public void expectNoFurtherTicks(boolean actuallySoundChange) {
    if (actuallySoundChange && sound != null) {
      if (sound instanceof FirmwareSound) ((FirmwareSound) sound).noteOffAll();
      else if (sound instanceof FirmwareKit) {
        for (FirmwareSound drum : ((FirmwareKit) sound).drumSounds) drum.noteOffAll();
      }
    }
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

    int ticksTilEnd = currentlyPlayingReversed ? lastProcessedPos : (loopLength - lastProcessedPos);
    if (ticksTilEnd <= 0) ticksTilEnd = loopLength;
    if (ticksTilEnd < ticksTilNextEvent) ticksTilNextEvent = ticksTilEnd;

    for (PendingNoteOn noteOn : pendingNoteOns) {
      triggerNote(noteOn);
    }
  }

  private void triggerNote(PendingNoteOn noteOn) {
    if (sound instanceof FirmwareSound) {
      ((FirmwareSound) sound).triggerNote(noteOn.noteRow.y, noteOn.velocity);
    } else if (sound instanceof FirmwareKit) {
      ((FirmwareKit) sound).triggerDrum(noteOn.noteRow.y, noteOn.velocity);
    }
  }
}
