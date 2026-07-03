package org.deluge.firmware2;

import org.deluge.firmware2.Arpeggiator.*;

/**
 * Extracted ArpeggiatorForDrum subclass from Arpeggiator.java.
 */
public class ArpeggiatorForDrum extends ArpeggiatorBase {
  public int noteForDrum;
  public boolean invertReversedFromKitArp;

  public ArpeggiatorForDrum() {
    active_note.velocity = 0; // C:73
  }

  @Override
  public void reset() {
    active_note.velocity = 0; // C:126-128
  }

  @Override
  public ArpType getArpType() {
    return ArpType.DRUM;
  }

  @Override
  public boolean hasAnyInputNotesActive() {
    return active_note.velocity != 0; // C:1432-1434
  }

  /** C: arpeggiator.cpp:150-216 — noteOn */
  public void noteOn(
      Settings settings,
      int noteCode,
      int originalVelocity,
      ArpReturnInstruction instruction,
      int fromMIDIChannel,
      int[] mpeValues) {
    lastVelocity = originalVelocity; // C:152
    noteForDrum = noteCode; // C:153
    boolean wasActiveBefore = active_note.velocity != 0; // C:155
    active_note.inputCharacteristics[0] = noteCode; // C:157 — NOTE
    active_note.inputCharacteristics[1] = fromMIDIChannel; // C:158 — CHANNEL
    active_note.baseVelocity = originalVelocity; // C:159
    active_note.velocity = originalVelocity; // C:160

    // C:164-166
    for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
      active_note.outputMemberChannel[n] = Arpeggiator.MIDI_CHANNEL_NONE;
    }
    // C:168-170
    if (mpeValues != null) {
      for (int m = 0; m < 3; m++) {
        active_note.mpeValues[m] = mpeValues[m];
      }
    }

    // C:173 — if arpeggiator active
    if (settings != null && settings.mode != ArpMode.OFF) {
      if (!wasActiveBefore) { // C:176
        playedFirstArpeggiatedNoteYet = false;
        gateCurrentlyActive = false;
        if (!playbackClockActive || settings.syncLevel == SyncLevel.SYNC_LEVEL_NONE) { // C:180
          switchNoteOn(settings, instruction, false);
        }
      }
    } else {
      // C:189-215 — no arp, just trigger note
      calculateRandomizerAmounts(settings);
      notesPlayedFromLockedRandomizer =
          (notesPlayedFromLockedRandomizer + 1) % Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES;
      if (isPlayNoteForCurrentStep) {
        active_note.baseVelocity = originalVelocity;
        int velocity = calculateSpreadVelocity(originalVelocity, spreadVelocityForCurrentStep);
        active_note.velocity = velocity;
        active_note.noteCodeOnPostArp[0] = noteCode;
        active_note.noteStatus[0] = ArpNoteStatus.PENDING;
        for (int n = 1; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
          active_note.noteCodeOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
          active_note.outputMemberChannel[n] = Arpeggiator.MIDI_CHANNEL_NONE;
          active_note.noteStatus[n] = ArpNoteStatus.OFF;
        }
        instruction.invertReversed =
            invertReversedFromKitArp ? !isPlayReverseForCurrentStep : isPlayReverseForCurrentStep;
        instruction.arpNoteOn = active_note;
      }
    }
  }

  /** C: arpeggiator.cpp:218-253 — noteOff */
  public void noteOff(Settings settings, int noteCodePreArp, ArpReturnInstruction instruction) {
    if (settings == null || settings.mode == ArpMode.OFF) {
      instruction.noteCodeOffPostArp[0] = noteCodePreArp;
      instruction.outputMIDIChannelOff[0] = active_note.outputMemberChannel[0];
      active_note.noteCodeOnPostArp[0] = Arpeggiator.ARP_NOTE_NONE;
      active_note.outputMemberChannel[0] = Arpeggiator.MIDI_CHANNEL_NONE;
      active_note.noteStatus[0] = ArpNoteStatus.OFF;
      for (int n = 1; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
        instruction.noteCodeOffPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
        instruction.outputMIDIChannelOff[n] = Arpeggiator.MIDI_CHANNEL_NONE;
      }
    } else {
      for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
        instruction.glideNoteCodeOffPostArp[n] = glideNoteCodeCurrentlyOnPostArp[n];
        instruction.glideOutputMIDIChannelOff[n] =
            outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n];
        glideNoteCodeCurrentlyOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
        outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n] = Arpeggiator.MIDI_CHANNEL_NONE;
        instruction.noteCodeOffPostArp[n] = active_note.noteCodeOnPostArp[n];
        instruction.outputMIDIChannelOff[n] = active_note.outputMemberChannel[n];
      }
    }
    active_note.resetPostArpArrays();
    active_note.velocity = 0; // C:252
  }

  // ── switchNoteOn (arpeggiator.cpp:758-859) ──

  @Override
  protected void switchNoteOn(
      Settings settings, ArpReturnInstruction instruction, boolean isRatchet) {
    int maxSequenceLength =
        Functions.computeCurrentValueForUnsignedMenuItem(settings.sequenceLength); // C:761
    int rhythm = Functions.computeCurrentValueForUnsignedMenuItem(settings.rhythm); // C:762
    int numActiveNotes = Arpeggiator.chordTypeNoteCount[settings.chordTypeIndex]; // C:773

    StepResult out = new StepResult();
    executeArpStep(settings, numActiveNotes, isRatchet, maxSequenceLength, rhythm, out);

    if (out.shouldCarryOnRhythmNote && out.shouldPlayNote) {
      gateCurrentlyActive = true; // C:780
      if (!isRatchet) gatePos = 0; // C:782-784

      int velocity = active_note.baseVelocity; // C:786

      // C:788-810 — MPE velocity
      if (settings.mpeVelocity != ArpMpeModSource.OFF) {
        switch (settings.mpeVelocity) {
          case AFTERTOUCH:
            velocity = active_note.mpeValues[2] >> 8;
            break;
          case MPE_Y:
            velocity = active_note.mpeValues[1] >> 8;
            break;
        }
        if (velocity < Arpeggiator.MIN_MPE_MODULATED_VELOCITY) velocity = Arpeggiator.MIN_MPE_MODULATED_VELOCITY;
      }
      active_note.baseVelocity = velocity;
      velocity = calculateSpreadVelocity(velocity, spreadVelocityForCurrentStep);
      active_note.velocity = velocity;

      // C:812-841 — note calculation
      int note;
      if (out.shouldPlayBassNote) {
        note = noteForDrum; // C:814-816
      } else if (out.shouldPlayRandomStep) {
        // C:818-823 — random chord note
        note =
            noteForDrum
                + Arpeggiator.chordTypeSemitoneOffsets[settings.chordTypeIndex][
                    (Arpeggiator.getRandom255() % numActiveNotes) % Arpeggiator.MAX_CHORD_NOTES]
                + (Arpeggiator.getRandom255() % settings.numOctaves) * 12;
      } else {
        // C:825-834 — normal pattern step
        int diff = currentOctave * 12;
        if (spreadOctaveForCurrentStep != 0) {
          diff = diff + spreadOctaveForCurrentStep * 12;
        }
        note =
            noteForDrum
                + Arpeggiator.chordTypeSemitoneOffsets[settings.chordTypeIndex][
                    whichNoteCurrentlyOnPostArp % Arpeggiator.MAX_CHORD_NOTES]
                + diff;
      }
      if (note < 0) note = 0;
      else if (note > 127) note = 127;

      // C:844-849 — set note
      active_note.noteCodeOnPostArp[0] = note;
      active_note.noteStatus[0] = ArpNoteStatus.PENDING;
      for (int n = 1; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
        active_note.noteStatus[n] = ArpNoteStatus.OFF;
        active_note.noteCodeOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
      }
      instruction.invertReversed =
          invertReversedFromKitArp ? !out.shouldPlayReverseNote : out.shouldPlayReverseNote;
      instruction.arpNoteOn = active_note;

      // C:855-857
      if (isPlayGlideForCurrentStep && !isRatcheting) {
        glideOnNextNoteOff = true;
      }
    }
  }
}
