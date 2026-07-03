package org.deluge.firmware2;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Faithful line-by-line port of {@code modulation/arpeggiator.cpp} + {@code arpeggiator.h}.
 *
 * <p>Covers ArpeggiatorSettings, ArpNote, ArpReturnInstruction, ArpeggiatorBase, Arpeggiator
 * (synth, {@link Synth}), ArpeggiatorForDrum ({@link ForDrum}) and ArpeggiatorForKit ({@link Kit}).
 *
 * <p>C references: arpeggiator.cpp:1-1990, arpeggiator.h:1-381.
 */
public class Arpeggiator {

  // ── Constants (arpeggiator.h:36-39, arpeggiator.cpp:30) ──
  public static final int RANDOMIZER_LOCK_MAX_SAVED_VALUES = 16;
  public static final int ARP_MAX_INSTRUCTION_NOTES = 4;
  public static final int PATTERN_MAX_BUFFER_SIZE = 16;
  public static final int ARP_NOTE_NONE = 32767;
  public static final int MIN_MPE_MODULATED_VELOCITY = 10;
  public static final int MIDI_CHANNEL_NONE = 255;
  static final int MAX_CHORD_NOTES = 4; // lookuptables.h:140
  static final int MAX_CHORD_TYPES = 9; // lookuptables.h:139 (NO_CHORD + 8 types)

  /** C: getRandom255() = CONG >> 24 — returns random value in range 0-255. */
  static int getRandom255() {
    return Functions.getNoise() >>> 24;
  }

  // ── Enums (arpeggiator.h:42-46, 149-153) ──

  /** C: arpeggiator.h:42-46 */
  public enum ArpType {
    SYNTH,
    DRUM,
    KIT
  }

  /** C: ArpPreset (arpeggiator.h:73) */
  public enum ArpPreset {
    OFF,
    UP,
    DOWN,
    BOTH,
    RANDOM,
    WALK,
    CUSTOM
  }

  /** C: ArpMode */
  public enum ArpMode {
    OFF,
    ARP
  }

  /** C: ArpOctaveMode */
  public enum ArpOctaveMode {
    UP,
    DOWN,
    UP_DOWN,
    ALTERNATE,
    RANDOM
  }

  /** C: ArpNoteMode */
  public enum ArpNoteMode {
    UP,
    DOWN,
    UP_DOWN,
    AS_PLAYED,
    RANDOM,
    WALK1,
    WALK2,
    WALK3,
    PATTERN
  }

  /** C: SyncLevel — ordinal values match C enum (0=none, 1=whole... 9=256th). */
  public enum SyncLevel {
    SYNC_LEVEL_NONE, // 0 — no sync
    SYNC_LEVEL_WHOLE, // 1
    SYNC_LEVEL_2ND, // 2
    SYNC_LEVEL_4TH, // 3
    SYNC_LEVEL_8TH, // 4
    SYNC_LEVEL_16TH, // 5
    SYNC_LEVEL_32ND, // 6
    SYNC_LEVEL_64TH, // 7
    SYNC_LEVEL_128TH, // 8
    SYNC_LEVEL_256TH // 9
  }

  /** C: SyncType */
  public enum SyncType {
    EVEN,
    TRIPLET,
    DOTTED
  }

  /** C: ArpMpeModSource */
  public enum ArpMpeModSource {
    OFF,
    AFTERTOUCH,
    MPE_Y
  }

  /** C: ArpNoteStatus (arpeggiator.h:149-153) */
  public enum ArpNoteStatus {
    OFF,
    PENDING,
    PLAYING
  }

  // ── ArpNote (arpeggiator.h:155-179) ──

  public static class ArpNote {
    /** C: inputCharacteristics[2] — [NOTE, CHANNEL] */
    public final int[] inputCharacteristics = {0, MIDI_CHANNEL_NONE};

    /** C: mpeValues[kNumExpressionDimensions=3] */
    public final int[] mpeValues = new int[3];

    public int velocity;
    public int baseVelocity;

    /** C: noteStatus[ARP_MAX_INSTRUCTION_NOTES] */
    public final ArpNoteStatus[] noteStatus = new ArpNoteStatus[ARP_MAX_INSTRUCTION_NOTES];

    /** C: outputMemberChannel[ARP_MAX_INSTRUCTION_NOTES] */
    public final int[] outputMemberChannel = new int[ARP_MAX_INSTRUCTION_NOTES];

    /** C: noteCodeOnPostArp[ARP_MAX_INSTRUCTION_NOTES] */
    public final int[] noteCodeOnPostArp = new int[ARP_MAX_INSTRUCTION_NOTES];

    public ArpNote() {
      Arrays.fill(outputMemberChannel, MIDI_CHANNEL_NONE);
      Arrays.fill(noteCodeOnPostArp, ARP_NOTE_NONE);
      Arrays.fill(noteStatus, ArpNoteStatus.OFF);
    }

    /** C: arpeggiator.h:161-163 — isPending() */
    public boolean isPending() {
      for (ArpNoteStatus s : noteStatus) {
        if (s == ArpNoteStatus.PENDING) return true;
      }
      return false;
    }

    /** C: arpeggiator.h:164-168 — resetPostArpArrays() */
    public void resetPostArpArrays() {
      Arrays.fill(outputMemberChannel, MIDI_CHANNEL_NONE);
      Arrays.fill(noteCodeOnPostArp, ARP_NOTE_NONE);
      Arrays.fill(noteStatus, ArpNoteStatus.OFF);
    }

    /** C: struct copy — active_note = *arpNote (value copy, not pointer). */
    public void copyFrom(ArpNote src) {
      this.inputCharacteristics[0] = src.inputCharacteristics[0];
      this.inputCharacteristics[1] = src.inputCharacteristics[1];
      System.arraycopy(src.mpeValues, 0, this.mpeValues, 0, 3);
      this.velocity = src.velocity;
      this.baseVelocity = src.baseVelocity;
      System.arraycopy(src.noteStatus, 0, this.noteStatus, 0, ARP_MAX_INSTRUCTION_NOTES);
      System.arraycopy(
          src.outputMemberChannel, 0, this.outputMemberChannel, 0, ARP_MAX_INSTRUCTION_NOTES);
      System.arraycopy(
          src.noteCodeOnPostArp, 0, this.noteCodeOnPostArp, 0, ARP_MAX_INSTRUCTION_NOTES);
    }
  }

  // ── ArpJustNoteCode (arpeggiator.h:181-183) ──

  public static class ArpJustNoteCode {
    public int noteCode;

    public ArpJustNoteCode(int noteCode) {
      this.noteCode = noteCode;
    }
  }

  // ── ArpReturnInstruction (arpeggiator.h:185-213) ──

  public static class ArpReturnInstruction {
    public int sampleSyncLengthOn;
    public boolean invertReversed;
    public ArpNote arpNoteOn;
    public final int[] outputMIDIChannelOff = new int[ARP_MAX_INSTRUCTION_NOTES];
    public final int[] noteCodeOffPostArp = new int[ARP_MAX_INSTRUCTION_NOTES];
    public final int[] glideOutputMIDIChannelOff = new int[ARP_MAX_INSTRUCTION_NOTES];
    public final int[] glideNoteCodeOffPostArp = new int[ARP_MAX_INSTRUCTION_NOTES];

    public ArpReturnInstruction() {
      Arrays.fill(outputMIDIChannelOff, MIDI_CHANNEL_NONE);
      Arrays.fill(noteCodeOffPostArp, ARP_NOTE_NONE);
      Arrays.fill(glideOutputMIDIChannelOff, MIDI_CHANNEL_NONE);
      Arrays.fill(glideNoteCodeOffPostArp, ARP_NOTE_NONE);
    }
  }

  // ── ArpeggiatorSettings (arpeggiator.h:48-148) ──

  public static class Settings {
    public ArpPreset preset = ArpPreset.OFF;
    public ArpMode mode = ArpMode.OFF;
    public boolean includeInKitArp = true;
    public ArpOctaveMode octaveMode = ArpOctaveMode.UP;
    public ArpNoteMode noteMode = ArpNoteMode.UP;
    public int numOctaves = 2;
    public int numStepRepeats = 1;
    public int chordTypeIndex;
    public SyncLevel syncLevel = SyncLevel.SYNC_LEVEL_16TH;
    public SyncType syncType = SyncType.EVEN;
    public boolean randomizerLock;
    public ArpMpeModSource mpeVelocity = ArpMpeModSource.OFF;

    // Spread last lock values
    public int lastLockedNoteProbabilityParameterValue;
    public int lastLockedBassProbabilityParameterValue;
    public int lastLockedSwapProbabilityParameterValue;
    public int lastLockedGlideProbabilityParameterValue;
    public int lastLockedReverseProbabilityParameterValue;
    public int lastLockedChordProbabilityParameterValue;
    public int lastLockedRatchetProbabilityParameterValue;
    public int lastLockedSpreadVelocityParameterValue;
    public int lastLockedSpreadGateParameterValue;
    public int lastLockedSpreadOctaveParameterValue;

    // Locked arrays
    public final byte[] lockedNoteProbabilityValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedBassProbabilityValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedSwapProbabilityValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedGlideProbabilityValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedReverseProbabilityValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedChordProbabilityValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedRatchetProbabilityValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedSpreadVelocityValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedSpreadGateValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    public final byte[] lockedSpreadOctaveValues = new byte[RANDOMIZER_LOCK_MAX_SAVED_VALUES];

    /** C: arpeggiator.h:126 — notePattern[PATTERN_MAX_BUFFER_SIZE] */
    public final byte[] notePattern = new byte[PATTERN_MAX_BUFFER_SIZE];

    public boolean flagForceArpRestart;

    // Automatable params (arpeggiator.h:132-147)
    public int rate; // C: int32_t
    public int gate; // C: int32_t
    public int rhythm;
    public int sequenceLength;
    public int chordPolyphony;
    public int ratchetAmount;
    public int noteProbability = 0xFFFFFFFF; // C: 4294967295u
    public int bassProbability;
    public int swapProbability;
    public int glideProbability;
    public int reverseProbability;
    public int chordProbability;
    public int ratchetProbability;
    public int spreadVelocity;
    public int spreadGate;
    public int spreadOctave;

    /** C: arpeggiator.cpp:40-70 — constructor */
    public Settings() {
      generateNewNotePattern();
    }

    /** C: arpeggiator.cpp:1559-1580 — getPhaseIncrement */
    public int getPhaseIncrement(int arpRate) {
      int phaseIncrement;
      if (syncLevel == SyncLevel.SYNC_LEVEL_NONE) { // syncLevel == 0
        // C: arpeggiator.cpp:1562 — arpRate >> 5
        phaseIncrement = arpRate >> 5;
      } else {
        // C: 1565-1570
        int rightShiftAmount = 9 - syncLevel.ordinal();
        if (rightShiftAmount < 0) rightShiftAmount = 0;
        phaseIncrement = 1 << 20; // C proxy for playbackHandler.getTimePerInternalTickInverse()
        phaseIncrement >>= rightShiftAmount;
        if (syncType == SyncType.TRIPLET) {
          phaseIncrement = phaseIncrement * 3 / 2;
        } else if (syncType == SyncType.DOTTED) {
          phaseIncrement = phaseIncrement * 2 / 3;
        }
      }
      return phaseIncrement;
    }

    /** C: arpeggiator.cpp:1905-1909 — generateNewNotePattern */
    public void generateNewNotePattern() {
      for (int i = 0; i < PATTERN_MAX_BUFFER_SIZE; i++) {
        notePattern[i] = (byte) (getRandom255() % (i + 1));
      }
    }

    /** C: arpeggiator.cpp:1911-1933 — updatePresetFromCurrentSettings */
    public void updatePresetFromCurrentSettings() {
      if (mode == ArpMode.OFF) preset = ArpPreset.OFF;
      else if (octaveMode == ArpOctaveMode.UP && noteMode == ArpNoteMode.UP) preset = ArpPreset.UP;
      else if (octaveMode == ArpOctaveMode.DOWN && noteMode == ArpNoteMode.DOWN)
        preset = ArpPreset.DOWN;
      else if (octaveMode == ArpOctaveMode.ALTERNATE && noteMode == ArpNoteMode.UP)
        preset = ArpPreset.BOTH;
      else if (octaveMode == ArpOctaveMode.RANDOM && noteMode == ArpNoteMode.RANDOM)
        preset = ArpPreset.RANDOM;
      else if (octaveMode == ArpOctaveMode.ALTERNATE && noteMode == ArpNoteMode.WALK2)
        preset = ArpPreset.WALK;
      else preset = ArpPreset.CUSTOM;
    }

    /** C: arpeggiator.cpp:1952-1989 — updateSettingsFromCurrentPreset */
    public void updateSettingsFromCurrentPreset() {
      switch (preset) {
        case OFF:
          mode = ArpMode.OFF;
          break;
        case UP:
          mode = ArpMode.ARP;
          octaveMode = ArpOctaveMode.UP;
          noteMode = ArpNoteMode.UP;
          break;
        case DOWN:
          mode = ArpMode.ARP;
          octaveMode = ArpOctaveMode.DOWN;
          noteMode = ArpNoteMode.DOWN;
          break;
        case BOTH:
          mode = ArpMode.ARP;
          octaveMode = ArpOctaveMode.ALTERNATE;
          noteMode = ArpNoteMode.UP;
          break;
        case RANDOM:
          mode = ArpMode.ARP;
          octaveMode = ArpOctaveMode.RANDOM;
          noteMode = ArpNoteMode.RANDOM;
          break;
        case WALK:
          mode = ArpMode.ARP;
          octaveMode = ArpOctaveMode.ALTERNATE;
          noteMode = ArpNoteMode.WALK2;
          break;
        case CUSTOM:
          mode = ArpMode.ARP;
          octaveMode = ArpOctaveMode.UP;
          noteMode = ArpNoteMode.UP;
          break;
      }
    }
  }

  // ── Arp rhythm patterns (arpeggiator_rhythms.h:8-11, 82-134) ──
  // Faithful transcription of the C arpRhythmPatterns[kMaxPresetArpRhythm+1] table (51 entries,
  // index 0 = "None"/play-all). steps[] = whether each step plays a note (true) or is silent.
  static final class ArpRhythm {
    final int length; // number of steps to use (1..6)
    final boolean[] steps; // 6 steps: play a note (true) or a silence (false)

    ArpRhythm(int length, boolean[] steps) {
      this.length = length;
      this.steps = steps;
    }
  }

  // ── Chord-type tables (lookuptables.cpp:518-541) ──

  /** chordTypeSemitoneOffsets[MAX_CHORD_TYPES][MAX_CHORD_NOTES] (lookuptables.cpp:518). */
  static final int[][] chordTypeSemitoneOffsets = {
    {0, 0, 0, 0}, // NO_CHORD
    {0, 7, 0, 0}, // FIFTH
    {0, 2, 7, 0}, // SUS2
    {0, 3, 7, 0}, // MINOR
    {0, 4, 7, 0}, // MAJOR
    {0, 5, 7, 0}, // SUS4
    {0, 3, 7, 10}, // MINOR7
    {0, 4, 7, 10}, // DOMINANT7
    {0, 4, 7, 11}, // MAJOR7
  };

  /** chordTypeNoteCount[MAX_CHORD_TYPES] (lookuptables.cpp:531). */
  static final int[] chordTypeNoteCount = {1, 2, 3, 3, 3, 3, 4, 4, 4};

  static final ArpRhythm[] arpRhythmPatterns =
      new ArpRhythm[] {
        new ArpRhythm(1, new boolean[] {true, true, true, true, true, true}),
        new ArpRhythm(3, new boolean[] {true, false, false, true, true, true}),
        new ArpRhythm(3, new boolean[] {true, true, false, true, true, true}),
        new ArpRhythm(3, new boolean[] {true, false, true, true, true, true}),
        new ArpRhythm(4, new boolean[] {true, false, true, true, true, true}),
        new ArpRhythm(4, new boolean[] {true, true, false, false, true, true}),
        new ArpRhythm(4, new boolean[] {true, true, true, false, true, true}),
        new ArpRhythm(4, new boolean[] {true, false, false, true, true, true}),
        new ArpRhythm(4, new boolean[] {true, true, false, true, true, true}),
        new ArpRhythm(5, new boolean[] {true, false, false, false, false, true}),
        new ArpRhythm(5, new boolean[] {true, false, true, true, true, true}),
        new ArpRhythm(5, new boolean[] {true, true, false, false, false, true}),
        new ArpRhythm(5, new boolean[] {true, true, true, true, false, true}),
        new ArpRhythm(5, new boolean[] {true, false, false, false, true, true}),
        new ArpRhythm(5, new boolean[] {true, true, false, true, true, true}),
        new ArpRhythm(5, new boolean[] {true, false, true, false, false, true}),
        new ArpRhythm(5, new boolean[] {true, true, true, false, true, true}),
        new ArpRhythm(5, new boolean[] {true, false, false, true, false, true}),
        new ArpRhythm(5, new boolean[] {true, false, false, true, true, true}),
        new ArpRhythm(5, new boolean[] {true, true, true, false, false, true}),
        new ArpRhythm(5, new boolean[] {true, true, false, false, true, true}),
        new ArpRhythm(5, new boolean[] {true, false, true, true, false, true}),
        new ArpRhythm(5, new boolean[] {true, true, false, true, false, true}),
        new ArpRhythm(5, new boolean[] {true, false, true, false, true, true}),
        new ArpRhythm(6, new boolean[] {true, false, false, false, false, false}),
        new ArpRhythm(6, new boolean[] {true, false, true, true, true, true}),
        new ArpRhythm(6, new boolean[] {true, true, false, false, false, false}),
        new ArpRhythm(6, new boolean[] {true, true, true, true, true, false}),
        new ArpRhythm(6, new boolean[] {true, false, false, false, false, true}),
        new ArpRhythm(6, new boolean[] {true, true, false, true, true, true}),
        new ArpRhythm(6, new boolean[] {true, false, true, false, false, false}),
        new ArpRhythm(6, new boolean[] {true, true, true, true, false, true}),
        new ArpRhythm(6, new boolean[] {true, false, false, false, true, false}),
        new ArpRhythm(6, new boolean[] {true, true, true, false, true, true}),
        new ArpRhythm(6, new boolean[] {true, false, false, true, true, true}),
        new ArpRhythm(6, new boolean[] {true, true, true, false, false, false}),
        new ArpRhythm(6, new boolean[] {true, true, true, true, false, false}),
        new ArpRhythm(6, new boolean[] {true, false, false, false, true, true}),
        new ArpRhythm(6, new boolean[] {true, true, false, false, true, true}),
        new ArpRhythm(6, new boolean[] {true, false, true, true, false, false}),
        new ArpRhythm(6, new boolean[] {true, true, true, false, false, true}),
        new ArpRhythm(6, new boolean[] {true, false, false, true, true, false}),
        new ArpRhythm(6, new boolean[] {true, false, true, false, true, true}),
        new ArpRhythm(6, new boolean[] {true, true, false, true, false, false}),
        new ArpRhythm(6, new boolean[] {true, true, true, false, true, false}),
        new ArpRhythm(6, new boolean[] {true, false, false, true, false, true}),
        new ArpRhythm(6, new boolean[] {true, false, true, true, true, false}),
        new ArpRhythm(6, new boolean[] {true, true, false, false, false, true}),
        new ArpRhythm(6, new boolean[] {true, true, false, false, true, false}),
        new ArpRhythm(6, new boolean[] {true, false, true, false, false, true}),
        new ArpRhythm(6, new boolean[] {true, true, false, true, false, true}),
      };

  // ── ArpeggiatorBase (arpeggiator.h:215-330) ──

  public abstract static class Base extends ArpeggiatorBase {

  }

  // ── ArpeggiatorForDrum (arpeggiator.h:332-348, arpeggiator.cpp:72-216, etc.) ──

  public static class ForDrum extends Base {
    public int noteForDrum;
    public boolean invertReversedFromKitArp;

    public ForDrum() {
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
      for (int n = 0; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
        active_note.outputMemberChannel[n] = MIDI_CHANNEL_NONE;
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
            (notesPlayedFromLockedRandomizer + 1) % RANDOMIZER_LOCK_MAX_SAVED_VALUES;
        if (isPlayNoteForCurrentStep) {
          active_note.baseVelocity = originalVelocity;
          int velocity = calculateSpreadVelocity(originalVelocity, spreadVelocityForCurrentStep);
          active_note.velocity = velocity;
          active_note.noteCodeOnPostArp[0] = noteCode;
          active_note.noteStatus[0] = ArpNoteStatus.PENDING;
          for (int n = 1; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
            active_note.noteCodeOnPostArp[n] = ARP_NOTE_NONE;
            active_note.outputMemberChannel[n] = MIDI_CHANNEL_NONE;
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
        active_note.noteCodeOnPostArp[0] = ARP_NOTE_NONE;
        active_note.outputMemberChannel[0] = MIDI_CHANNEL_NONE;
        active_note.noteStatus[0] = ArpNoteStatus.OFF;
        for (int n = 1; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
          instruction.noteCodeOffPostArp[n] = ARP_NOTE_NONE;
          instruction.outputMIDIChannelOff[n] = MIDI_CHANNEL_NONE;
        }
      } else {
        for (int n = 0; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
          instruction.glideNoteCodeOffPostArp[n] = glideNoteCodeCurrentlyOnPostArp[n];
          instruction.glideOutputMIDIChannelOff[n] =
              outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n];
          glideNoteCodeCurrentlyOnPostArp[n] = ARP_NOTE_NONE;
          outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n] = MIDI_CHANNEL_NONE;
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
      int numActiveNotes = chordTypeNoteCount[settings.chordTypeIndex]; // C:773

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
          if (velocity < MIN_MPE_MODULATED_VELOCITY) velocity = MIN_MPE_MODULATED_VELOCITY;
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
                  + chordTypeSemitoneOffsets[settings.chordTypeIndex][
                      (getRandom255() % numActiveNotes) % MAX_CHORD_NOTES]
                  + (getRandom255() % settings.numOctaves) * 12;
        } else {
          // C:825-834 — normal pattern step
          int diff = currentOctave * 12;
          if (spreadOctaveForCurrentStep != 0) {
            diff = diff + spreadOctaveForCurrentStep * 12;
          }
          note =
              noteForDrum
                  + chordTypeSemitoneOffsets[settings.chordTypeIndex][
                      whichNoteCurrentlyOnPostArp % MAX_CHORD_NOTES]
                  + diff;
        }
        if (note < 0) note = 0;
        else if (note > 127) note = 127;

        // C:844-849 — set note
        active_note.noteCodeOnPostArp[0] = note;
        active_note.noteStatus[0] = ArpNoteStatus.PENDING;
        for (int n = 1; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
          active_note.noteStatus[n] = ArpNoteStatus.OFF;
          active_note.noteCodeOnPostArp[n] = ARP_NOTE_NONE;
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

  // ── Arpeggiator (synth) (arpeggiator.h:350-373, arpeggiator.cpp:76-503, etc.) ──

  public static class Synth extends Base {
    /** C: OrderedResizeableArray — sorted by note code */
    public final ArrayList<ArpNote> notes = new ArrayList<>();

    /** C: ResizeableArray — as-played order */
    public final ArrayList<ArpJustNoteCode> notesAsPlayed = new ArrayList<>();

    /** C: ResizeableArray — by-pattern order */
    public final ArrayList<ArpJustNoteCode> notesByPattern = new ArrayList<>();

    boolean anyPending;

    public Synth() {
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

    /** C: arpeggiator.cpp:1228-1247 — rearrangePatterntArpNotes */
    void rearrangePatternArpNotes(Settings settings) {
      notesByPattern.clear();
      int numNotes = notes.size();
      for (int i = 0; i < numNotes; i++) {
        int notesByPatternIndex =
            Math.min(settings.notePattern[Math.min(i, PATTERN_MAX_BUFFER_SIZE - 1)] & 0xFF, i);
        notesByPatternIndex = Math.min(notesByPatternIndex, notesByPattern.size());
        ArpJustNoteCode entry = new ArpJustNoteCode(notes.get(i).inputCharacteristics[0]);
        notesByPattern.add(notesByPatternIndex, entry);
      }
    }

    // ── findNote / insertNote (helpers to replace OrderedResizeableArray::search/insertAtIndex) ──

    /** C: notes.search(noteCode, GREATER_OR_EQUAL) — returns insertion index */
    int findNoteIndex(int noteCode) { // package-private so the Kit subclass can reuse it
      for (int i = 0; i < notes.size(); i++) {
        if (notes.get(i).inputCharacteristics[0] >= noteCode) return i;
      }
      return notes.size();
    }

    // ── noteOn (arpeggiator.cpp:257-376) ──

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
        for (int n = 0; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
          arpNote.outputMemberChannel[n] = MIDI_CHANNEL_NONE;
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
            (notesPlayedFromLockedRandomizer + 1) % RANDOMIZER_LOCK_MAX_SAVED_VALUES;
        if (isPlayNoteForCurrentStep) {
          arpNote.baseVelocity = originalVelocity;
          int velocity = calculateSpreadVelocity(originalVelocity, spreadVelocityForCurrentStep);
          arpNote.velocity = velocity;
          arpNote.noteCodeOnPostArp[0] = noteCode;
          arpNote.noteStatus[0] = ArpNoteStatus.PENDING;
          for (int n = 1; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
            arpNote.noteCodeOnPostArp[n] = ARP_NOTE_NONE;
            arpNote.noteStatus[n] = ArpNoteStatus.OFF;
          }
          instruction.invertReversed = isPlayReverseForCurrentStep;
          instruction.arpNoteOn = arpNote;
        }
      }
    }

    // ── noteOff (arpeggiator.cpp:378-475) ──

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
            arpNote.outputMemberChannel[0] = MIDI_CHANNEL_NONE;
            arpNote.noteStatus[0] = ArpNoteStatus.OFF;
            for (int n = 1; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
              instruction.noteCodeOffPostArp[n] = ARP_NOTE_NONE;
              instruction.outputMIDIChannelOff[n] = MIDI_CHANNEL_NONE;
              arpNote.outputMemberChannel[n] = MIDI_CHANNEL_NONE;
              arpNote.noteStatus[n] = ArpNoteStatus.OFF;
            }
          } else {
            if (whichNoteCurrentlyOnPostArp == notesKey) { // C:402
              for (int n = 0; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
                instruction.glideNoteCodeOffPostArp[n] = glideNoteCodeCurrentlyOnPostArp[n];
                instruction.glideOutputMIDIChannelOff[n] =
                    outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n];
                glideNoteCodeCurrentlyOnPostArp[n] = ARP_NOTE_NONE;
                outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n] = MIDI_CHANNEL_NONE;
                instruction.noteCodeOffPostArp[n] = arpNote.noteCodeOnPostArp[n];
                instruction.outputMIDIChannelOff[n] = arpNote.outputMemberChannel[n];
                arpNote.noteCodeOnPostArp[n] = ARP_NOTE_NONE;
                arpNote.outputMemberChannel[n] = MIDI_CHANNEL_NONE;
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
                  for (int n = 1; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
                    lastNote.noteCodeOnPostArp[n] = ARP_NOTE_NONE;
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
              if (arpNote.noteCodeOnPostArp[0] == ARP_NOTE_NONE) {
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
        arpNote = notes.get(getRandom255() % numActiveNotes); // C:1277
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
          if (velocity < MIN_MPE_MODULATED_VELOCITY) velocity = MIN_MPE_MODULATED_VELOCITY;
        }
        arpNote.baseVelocity = velocity;
        velocity = calculateSpreadVelocity(velocity, spreadVelocityForCurrentStep); // C:1338
        arpNote.velocity = velocity;

        // C:1341-1366 — note calculation
        int note;
        if (out.shouldPlayBassNote) {
          note = arpNote.inputCharacteristics[0];
        } else if (out.shouldPlayRandomStep) {
          note = arpNote.inputCharacteristics[0] + (getRandom255() % settings.numOctaves) * 12;
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
            for (int n = 1; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
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

  // ── ArpeggiatorForKit (arpeggiator.h:376-382, arpeggiator.cpp:84-116) ──

  /** Kit arp: the synth note-list arp with KIT type + drum-index maintenance. */
  public static class Kit extends Synth {
    @Override
    public ArpType getArpType() {
      return ArpType.KIT;
    }

    /** C: ArpeggiatorForKit::removeDrumIndex (arpeggiator.cpp:84-116). */
    public void removeDrumIndex(Settings arpSettings, int drumIndex) {
      int n = findNoteIndex(drumIndex); // notes.search(drumIndex, GREATER_OR_EQUAL)
      int numNotes = notes.size();
      if (n < numNotes) {
        // Delete drumIndex from the notes array.
        notes.remove(n);
        for (int i = 0; i < notesAsPlayed.size(); i++) {
          if (notesAsPlayed.get(i).noteCode == drumIndex) {
            notesAsPlayed.remove(i);
            break;
          }
        }
        // Now shift all the arpeggiator drumIndexes at/after n down by one.
        numNotes = notes.size();
        for (int i = n; i < numNotes; i++) {
          notes.get(i).inputCharacteristics[0] = notes.get(i).inputCharacteristics[0] - 1;
        }
        for (int i = 0; i < notesAsPlayed.size(); i++) {
          if (notesAsPlayed.get(i).noteCode > drumIndex) {
            notesAsPlayed.get(i).noteCode = notesAsPlayed.get(i).noteCode - 1;
          }
        }
        rearrangePatternArpNotes(arpSettings);
      }
    }
  }
}
