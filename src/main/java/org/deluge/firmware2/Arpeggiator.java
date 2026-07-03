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

  public abstract static class Base extends ArpeggiatorBase {}

  // ── ArpeggiatorForDrum (arpeggiator.h:332-348, arpeggiator.cpp:72-216, etc.) ──

  public static class ForDrum extends ArpeggiatorForDrum {
  }

  // ── Arpeggiator (synth) (arpeggiator.h:350-373, arpeggiator.cpp:76-503, etc.) ──

  public static class Synth extends ArpeggiatorSynth {
  }

  public static class Kit extends ArpeggiatorKit {
  }
}
