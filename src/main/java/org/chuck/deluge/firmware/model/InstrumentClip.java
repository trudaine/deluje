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
  public volatile List<NoteRow> noteRows = new ArrayList<>();
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

    // Real-time parameters automation for individual kit sub-drum sounds
    if (sound instanceof FirmwareKit kit) {
      boolean mayInterpolate = true;
      for (FirmwareSound drumSound : kit.drumSounds) {
        if (drumSound.paramManager.mightContainAutomation()) {
          drumSound.paramManager.processCurrentPos(
              lastProcessedPos, loopLength, currentlyPlayingReversed, false, mayInterpolate);
        }
      }
    }

    List<PendingNoteOn> pendingNoteOns = new ArrayList<>();
    List<Integer> pendingNoteOffs = new ArrayList<>();
    ticksTilNextEvent = Integer.MAX_VALUE;

    for (NoteRow noteRow : noteRows) {
      int dist =
          noteRow.processCurrentPos(
              ticksSinceLast,
              pendingNoteOns,
              pendingNoteOffs,
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

    // Process Note-Off events BEFORE triggering new Note-On events!
    for (int pitchToRelease : pendingNoteOffs) {
      releaseNote(pitchToRelease);
    }

    for (PendingNoteOn noteOn : pendingNoteOns) {
      triggerNote(noteOn);
    }
  }

  private void releaseNote(int pitch) {
    if (sound instanceof FirmwareSound) {
      ((FirmwareSound) sound).releaseNote(pitch);
    } else if (sound instanceof FirmwareKit) {
      // Drum Kit trigger notes: drumSounds lane maps modulo total size bounds
      var kit = (FirmwareKit) sound;
      if (pitch < kit.drumSounds.size()) {
        kit.drumSounds.get(pitch).releaseNote(60);
      }
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
