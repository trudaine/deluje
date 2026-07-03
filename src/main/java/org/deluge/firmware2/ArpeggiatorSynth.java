package org.deluge.firmware2;

import java.util.ArrayList;
import org.deluge.firmware2.Arpeggiator.*;

/** Extracted ArpeggiatorSynth subclass from Arpeggiator.java. */
public class ArpeggiatorSynth extends ArpeggiatorBase {
  /** C: OrderedResizeableArray — sorted by note code */
  public final ArrayList<ArpNote> notes = new ArrayList<>();

  /** C: ResizeableArray — as-played order */
  public final ArrayList<ArpJustNoteCode> notesAsPlayed = new ArrayList<>();

  /** C: ResizeableArray — by-pattern order */
  public final ArrayList<ArpJustNoteCode> notesByPattern = new ArrayList<>();

  boolean anyPending;

  public ArpeggiatorSynth() {
    // C:76-82 — constructor (no need for ResizeableArray sizing in Java)
  }

  @Override
  public void reset() {
    notes.clear();
    notesAsPlayed.clear();
    notesByPattern.clear(); // C:119-123
  }

  @Override
  public ArpType getArpType() {
    return ArpType.SYNTH;
  }

  @Override
  public boolean hasAnyInputNotesActive() {
    return !notes.isEmpty(); // C:1428-1430
  }

  /** C: arpeggiator.cpp:1228-1247 — rearrangePatternArpNotes */
  void rearrangePatternArpNotes(Settings settings) {
    notesByPattern.clear();
    int numNotes = notes.size();
    for (int i = 0; i < numNotes; i++) {
      int notesByPatternIndex =
          Math.min(
              settings.notePattern[Math.min(i, Arpeggiator.PATTERN_MAX_BUFFER_SIZE - 1)] & 0xFF, i);
      notesByPatternIndex = Math.min(notesByPatternIndex, notesByPattern.size());
      ArpJustNoteCode entry = new ArpJustNoteCode(notes.get(i).inputCharacteristics[0]);
      notesByPattern.add(notesByPatternIndex, entry);
    }
  }

  /** C: notes.search(noteCode, GREATER_OR_EQUAL) — returns insertion index */
  protected int findNoteIndex(int noteCode) {
    for (int i = 0; i < notes.size(); i++) {
      if (notes.get(i).inputCharacteristics[0] >= noteCode) return i;
    }
    return notes.size();
  }

  /** C: arpeggiator.cpp:257-376 — noteOn */
  public void noteOn(
      Settings settings,
      int noteCode,
      int originalVelocity,
      ArpReturnInstruction instruction,
      int fromMIDIChannel,
      int[] mpeValues) {
    lastVelocity = originalVelocity; // C:259
    anyPending = true; // C:262

    int notesKey = findNoteIndex(noteCode); // C:266 — search GREATER_OR_EQUAL
    boolean noteExists = false;
    ArpNote arpNote = null;

    if (notesKey < notes.size()) {
      arpNote = notes.get(notesKey);
      if (arpNote.inputCharacteristics[0] == noteCode) {
        noteExists = true; // C:269-271
      }
    }

    // C:274-280 — if note exists and arp on, return
    if (noteExists) {
      if (settings != null && settings.mode != ArpMode.OFF) return;
    } else {
      // C:284-323 — insert new note
      arpNote = new ArpNote();
      arpNote.inputCharacteristics[0] = noteCode;
      arpNote.baseVelocity = originalVelocity;
      arpNote.velocity = originalVelocity;
      for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
        arpNote.outputMemberChannel[n] = Arpeggiator.MIDI_CHANNEL_NONE;
      }
      if (mpeValues != null) {
        for (int m = 0; m < 3; m++) {
          arpNote.mpeValues[m] = mpeValues[m];
        }
      }
      notes.add(notesKey, arpNote);
      // notesAsPlayed — always at end
      notesAsPlayed.add(new ArpJustNoteCode(noteCode));
      // notesByPattern
      rearrangePatternArpNotes(settings);
    }

    // C:328
    arpNote.inputCharacteristics[1] = fromMIDIChannel;

    // C:331-376 — if arp on
    if (settings != null && settings.mode != ArpMode.OFF) {
      if (notes.size() == 1) { // C:334
        playedFirstArpeggiatedNoteYet = false;
        gateCurrentlyActive = false;
        if (!playbackClockActive || settings.syncLevel == SyncLevel.SYNC_LEVEL_NONE) { // C:338
          switchNoteOn(settings, instruction, false);
        }
      } else {
        // C:345-347
        if (whichNoteCurrentlyOnPostArp >= notesKey) {
          whichNoteCurrentlyOnPostArp++;
        }
      }
    } else {
      // C:352-375 — no arp
      calculateRandomizerAmounts(settings);
      notesPlayedFromLockedRandomizer =
          (notesPlayedFromLockedRandomizer + 1) % Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES;
      if (isPlayNoteForCurrentStep) {
        arpNote.baseVelocity = originalVelocity;
        int velocity = calculateSpreadVelocity(originalVelocity, spreadVelocityForCurrentStep);
        arpNote.velocity = velocity;
        arpNote.noteCodeOnPostArp[0] = noteCode;
        arpNote.noteStatus[0] = ArpNoteStatus.PENDING;
        for (int n = 1; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
          arpNote.noteCodeOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
          arpNote.noteStatus[n] = ArpNoteStatus.OFF;
        }
        instruction.invertReversed = isPlayReverseForCurrentStep;
        instruction.arpNoteOn = arpNote;
      }
    }
  }

  /** C: arpeggiator.cpp:378-475 — noteOff */
  public void noteOff(Settings settings, int noteCodePreArp, ArpReturnInstruction instruction) {
    int notesKey = findNoteIndex(noteCodePreArp); // C:379
    if (notesKey < notes.size()) {
      ArpNote arpNote = notes.get(notesKey);
      if (arpNote.inputCharacteristics[0] == noteCodePreArp) { // C:383
        boolean arpOff = (settings == null || settings.mode == ArpMode.OFF);
        // C:386-421
        if (arpOff) {
          instruction.noteCodeOffPostArp[0] = noteCodePreArp;
          instruction.outputMIDIChannelOff[0] = arpNote.outputMemberChannel[0];
          arpNote.outputMemberChannel[0] = Arpeggiator.MIDI_CHANNEL_NONE;
          arpNote.noteStatus[0] = ArpNoteStatus.OFF;
          for (int n = 1; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
            instruction.noteCodeOffPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
            instruction.outputMIDIChannelOff[n] = Arpeggiator.MIDI_CHANNEL_NONE;
            arpNote.outputMemberChannel[n] = Arpeggiator.MIDI_CHANNEL_NONE;
            arpNote.noteStatus[n] = ArpNoteStatus.OFF;
          }
        } else {
          if (whichNoteCurrentlyOnPostArp == notesKey) { // C:402
            for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
              instruction.glideNoteCodeOffPostArp[n] = glideNoteCodeCurrentlyOnPostArp[n];
              instruction.glideOutputMIDIChannelOff[n] =
                  outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n];
              glideNoteCodeCurrentlyOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
              outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n] = Arpeggiator.MIDI_CHANNEL_NONE;
              instruction.noteCodeOffPostArp[n] = arpNote.noteCodeOnPostArp[n];
              instruction.outputMIDIChannelOff[n] = arpNote.outputMemberChannel[n];
              arpNote.noteCodeOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
              arpNote.outputMemberChannel[n] = Arpeggiator.MIDI_CHANNEL_NONE;
              arpNote.noteStatus[n] = ArpNoteStatus.OFF;
            }
          }
        }
        notes.remove(notesKey); // C:422

        // C:425-449 — remove from notesAsPlayed
        for (int i = 0; i < notesAsPlayed.size(); i++) {
          if (notesAsPlayed.get(i).noteCode == noteCodePreArp) {
            notesAsPlayed.remove(i);
            if (arpOff && i == notesAsPlayed.size() - 1 && i > 0) {
              // snap back to previous note (mono behavior)
              ArpJustNoteCode lastAsPlayed = notesAsPlayed.get(i - 1);
              int newNotesKey = findNoteIndex(lastAsPlayed.noteCode);
              if (newNotesKey < notes.size()) {
                ArpNote lastNote = notes.get(newNotesKey);
                lastNote.noteCodeOnPostArp[0] = lastNote.inputCharacteristics[0];
                for (int n = 1; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
                  lastNote.noteCodeOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
                  lastNote.noteStatus[n] = ArpNoteStatus.OFF;
                }
                instruction.arpNoteOn = lastNote;
              }
            }
            break;
          }
        }

        // C:452
        rearrangePatternArpNotes(settings);

        // C:454-459
        if (whichNoteCurrentlyOnPostArp >= notesKey) {
          whichNoteCurrentlyOnPostArp--;
          if (whichNoteCurrentlyOnPostArp < 0) whichNoteCurrentlyOnPostArp = 0;
        }

        // C:461-465
        if (isRatcheting && (ratchetNotesIndex >= ratchetNotesCount || !playbackClockActive)) {
          resetRatchet();
        }
      }
    }

    // C:469-474
    if (notes.isEmpty()) {
      resetBase();
      playedFirstArpeggiatedNoteYet = false;
    }
  }

  // ── handlePendingNotes override (arpeggiator.cpp:476-503) ──

  @Override
  boolean handlePendingNotes(Settings settings, ArpReturnInstruction instruction) {
    if (settings != null && settings.mode == ArpMode.OFF) {
      if (anyPending) {
        for (int i = 0; i < notes.size(); i++) {
          ArpNote arpNote = notes.get(i);
          if (arpNote.noteStatus[0] == ArpNoteStatus.PENDING) {
            if (arpNote.noteCodeOnPostArp[0] == Arpeggiator.ARP_NOTE_NONE) {
              arpNote.noteStatus[0] = ArpNoteStatus.OFF;
            } else {
              instruction.arpNoteOn = arpNote;
              arpNote.noteStatus[0] = ArpNoteStatus.PLAYING;
              return true;
            }
          }
        }
        anyPending = false;
      }
    } else {
      return super.handlePendingNotes(settings, instruction);
    }
    return false;
  }

  // ── switchNoteOn (arpeggiator.cpp:1248-1426) ──

  @Override
  protected void switchNoteOn(
      Settings settings, ArpReturnInstruction instruction, boolean isRatchet) {
    int maxSequenceLength =
        Functions.computeCurrentValueForUnsignedMenuItem(settings.sequenceLength); // C:1250
    int rhythm = Functions.computeCurrentValueForUnsignedMenuItem(settings.rhythm); // C:1251
    int numActiveNotes = notes.size(); // C:1262

    StepResult out = new StepResult();
    executeArpStep(settings, numActiveNotes, isRatchet, maxSequenceLength, rhythm, out);

    // C:1268 — clamp
    whichNoteCurrentlyOnPostArp =
        Math.max(0, Math.min(whichNoteCurrentlyOnPostArp, notes.size() - 1));

    // C:1270-1308 — select arpNote
    ArpNote arpNote;
    if (out.shouldPlayBassNote) {
      arpNote = notes.get(0); // C:1273
    } else if (out.shouldPlayRandomStep) {
      arpNote = notes.get(Arpeggiator.getRandom255() % numActiveNotes); // C:1277
    } else if (settings.noteMode == ArpNoteMode.AS_PLAYED) {
      // C:1279-1291
      ArpJustNoteCode asPlayed =
          notesAsPlayed.get(whichNoteCurrentlyOnPostArp % notesAsPlayed.size());
      int key = findNoteIndex(asPlayed.noteCode);
      arpNote =
          (key < notes.size() && notes.get(key).inputCharacteristics[0] == asPlayed.noteCode)
              ? notes.get(key)
              : notes.get(0);
    } else if (settings.noteMode == ArpNoteMode.PATTERN) {
      // C:1292-1304
      ArpJustNoteCode byPattern =
          notesByPattern.get(whichNoteCurrentlyOnPostArp % notesByPattern.size());
      int key = findNoteIndex(byPattern.noteCode);
      arpNote =
          (key < notes.size() && notes.get(key).inputCharacteristics[0] == byPattern.noteCode)
              ? notes.get(key)
              : notes.get(0);
    } else {
      arpNote = notes.get(whichNoteCurrentlyOnPostArp); // C:1307
    }

    // C:1309-1425
    if (out.shouldCarryOnRhythmNote && out.shouldPlayNote) {
      gateCurrentlyActive = true; // C:1311
      if (!isRatchet) gatePos = 0; // C:1313

      int velocity = arpNote.baseVelocity; // C:1317

      // C:1319-1335 — MPE velocity
      if (settings.mpeVelocity != ArpMpeModSource.OFF) {
        switch (settings.mpeVelocity) {
          case AFTERTOUCH:
            velocity = arpNote.mpeValues[2] >> 8;
            break;
          case MPE_Y:
            velocity = arpNote.mpeValues[1] >> 8;
            break;
        }
        if (velocity < Arpeggiator.MIN_MPE_MODULATED_VELOCITY)
          velocity = Arpeggiator.MIN_MPE_MODULATED_VELOCITY;
      }
      arpNote.baseVelocity = velocity;
      velocity = calculateSpreadVelocity(velocity, spreadVelocityForCurrentStep); // C:1338
      arpNote.velocity = velocity;

      // C:1341-1366 — note calculation
      int note;
      if (out.shouldPlayBassNote) {
        note = arpNote.inputCharacteristics[0];
      } else if (out.shouldPlayRandomStep) {
        note =
            arpNote.inputCharacteristics[0]
                + (Arpeggiator.getRandom255() % settings.numOctaves) * 12;
      } else {
        int diff = currentOctave * 12;
        if (spreadOctaveForCurrentStep != 0) {
          diff = diff + spreadOctaveForCurrentStep * 12;
        }
        note = arpNote.inputCharacteristics[0] + diff;
      }
      if (note < 0) note = 0;
      else if (note > 127) note = 127;

      arpNote.resetPostArpArrays(); // C:1368

      // C:1371-1415 — set note(s), incl. the optional chord.
      arpNote.noteCodeOnPostArp[0] = note; // the main note, whether we play a chord or not
      arpNote.noteStatus[0] = ArpNoteStatus.PENDING;
      // Now get additional notes to be played (chord). Only for in-scale notes when the scale has
      // at least 5 notes (degreeOf returns -1 for out-of-scale; default key count 1 → skipped).
      int degree = currentKey.degreeOf(note);
      if (out.shouldPlayChordNote && degree >= 0 && currentKey.modeNotes.count() >= 5) {
        int baseOffset = currentKey.modeNotes.get(degree % currentKey.modeNotes.count());
        int numAdditionalNotesInChord =
            Math.min(3, getRandomWeighted2BitsAmount(settings.chordPolyphony));
        if (numAdditionalNotesInChord > 0) {
          int[] degreeOffsets = {0, 0, 0};
          switch (numAdditionalNotesInChord) {
            case 1:
              degreeOffsets[0] = 4;
              break;
            case 2:
              degreeOffsets[0] = 2;
              degreeOffsets[1] = 4;
              break;
            case 3:
              degreeOffsets[0] = 2;
              degreeOffsets[1] = 4;
              degreeOffsets[2] = 6;
              break;
            default:
              break;
          }
          for (int n = 1; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
            if (n <= numAdditionalNotesInChord) {
              int targetOffset =
                  currentKey.modeNotes.get(
                      (degree + degreeOffsets[n - 1]) % currentKey.modeNotes.count());
              if (targetOffset <= baseOffset) {
                targetOffset += 12; // lower than the base note → add an octave
              }
              arpNote.noteCodeOnPostArp[n] = note + targetOffset - baseOffset;
              arpNote.noteStatus[n] = ArpNoteStatus.PENDING;
            }
          }
        }
      }

      instruction.invertReversed = out.shouldPlayReverseNote; // C:1417
      active_note.copyFrom(arpNote); // C:1418 — struct copy, not pointer
      instruction.arpNoteOn = active_note; // C:1419

      // C:1422-1424 — glide
      if (isPlayGlideForCurrentStep && !isRatcheting) {
        glideOnNextNoteOff = true;
      }
    }
  }
}
