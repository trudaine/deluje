package org.chuck.deluge.firmware2;

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
  public enum ArpType { SYNTH, DRUM, KIT }

  /** C: ArpPreset (arpeggiator.h:73) */
  public enum ArpPreset { OFF, UP, DOWN, BOTH, RANDOM, WALK, CUSTOM }

  /** C: ArpMode */
  public enum ArpMode { OFF, ARP }

  /** C: ArpOctaveMode */
  public enum ArpOctaveMode { UP, DOWN, UP_DOWN, ALTERNATE, RANDOM }

  /** C: ArpNoteMode */
  public enum ArpNoteMode { UP, DOWN, UP_DOWN, AS_PLAYED, RANDOM, WALK1, WALK2, WALK3, PATTERN }

  /** C: SyncLevel — ordinal values match C enum (0=none, 1=whole... 9=256th). */
  public enum SyncLevel {
    SYNC_LEVEL_NONE,    // 0 — no sync
    SYNC_LEVEL_WHOLE,   // 1
    SYNC_LEVEL_2ND,     // 2
    SYNC_LEVEL_4TH,     // 3
    SYNC_LEVEL_8TH,     // 4
    SYNC_LEVEL_16TH,    // 5
    SYNC_LEVEL_32ND,    // 6
    SYNC_LEVEL_64TH,    // 7
    SYNC_LEVEL_128TH,   // 8
    SYNC_LEVEL_256TH    // 9
  }

  /** C: SyncType */
  public enum SyncType { EVEN, TRIPLET, DOTTED }

  /** C: ArpMpeModSource */
  public enum ArpMpeModSource { OFF, AFTERTOUCH, MPE_Y }

  /** C: ArpNoteStatus (arpeggiator.h:149-153) */
  public enum ArpNoteStatus { OFF, PENDING, PLAYING }

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
      System.arraycopy(src.outputMemberChannel, 0, this.outputMemberChannel, 0, ARP_MAX_INSTRUCTION_NOTES);
      System.arraycopy(src.noteCodeOnPostArp, 0, this.noteCodeOnPostArp, 0, ARP_MAX_INSTRUCTION_NOTES);
    }
  }

  // ── ArpJustNoteCode (arpeggiator.h:181-183) ──

  public static class ArpJustNoteCode {
    public int noteCode;
    public ArpJustNoteCode(int noteCode) { this.noteCode = noteCode; }
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
      else if (octaveMode == ArpOctaveMode.DOWN && noteMode == ArpNoteMode.DOWN) preset = ArpPreset.DOWN;
      else if (octaveMode == ArpOctaveMode.ALTERNATE && noteMode == ArpNoteMode.UP) preset = ArpPreset.BOTH;
      else if (octaveMode == ArpOctaveMode.RANDOM && noteMode == ArpNoteMode.RANDOM) preset = ArpPreset.RANDOM;
      else if (octaveMode == ArpOctaveMode.ALTERNATE && noteMode == ArpNoteMode.WALK2) preset = ArpPreset.WALK;
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

  public abstract static class Base {
    // C: arpeggiator.h:237, 239-241
    public ArpNote active_note = new ArpNote();
    public final int[] glideNoteCodeCurrentlyOnPostArp = new int[ARP_MAX_INSTRUCTION_NOTES];
    public final int[] outputMIDIChannelForGlideNoteCurrentlyOnPostArp = new int[ARP_MAX_INSTRUCTION_NOTES];
    public int gatePos;
    public int lastVelocity;

    // C: arpeggiator.h:270-330 — internal state
    boolean gateCurrentlyActive;
    boolean playedFirstArpeggiatedNoteYet;
    int notesPlayedFromSequence;
    int randomNotesPlayedFromOctave;
    int whichNoteCurrentlyOnPostArp;
    int currentOctave;
    int currentDirection = 1;
    int currentOctaveDirection = 1;
    int notesPlayedFromRhythm;
    int lastNormalNotePlayedFromRhythm;
    int notesPlayedFromLockedRandomizer;

    /**
     * C: currentSong->key — the song's musical key, used for the chord path. Defaults to a single
     * mode note (count 1 < 5 → chord path skipped), matching the C when no scale is set. Wire this
     * from the song to enable arp chords.
     */
    public MusicalKey currentKey = new MusicalKey();

    /**
     * C: playbackHandler.isEitherClockActive() — whether a transport/sequencer clock is running.
     * Defaults to false (no transport): the C with no clock is non-synced, which is the standalone
     * arp's behavior. Set true (with a syncLevel) to drive tempo-synced arp from a running clock.
     */
    public boolean playbackClockActive = false;

    boolean lastNormalNotePlayedFromNoteProbability = true;
    boolean lastNormalNotePlayedFromBassProbability;
    boolean lastNormalNotePlayedFromSwapProbability;
    boolean lastNormalNotePlayedFromReverseProbability;
    boolean lastNormalNotePlayedFromChordProbability;
    int stepRepeatIndex;
    int ratchetNotesIndex;
    int ratchetNotesMultiplier;
    int ratchetNotesCount;
    boolean isRatcheting;
    boolean glideOnNextNoteOff;
    // Randomizer values
    boolean isPlayNoteForCurrentStep = true;
    boolean isPlayBassForCurrentStep;
    boolean isPlayRandomStepForCurrentStep;
    boolean isPlayReverseForCurrentStep;
    boolean isPlayChordForCurrentStep;
    boolean isPlayRatchetForCurrentStep;
    boolean isPlayGlideForCurrentStep;
    int spreadVelocityForCurrentStep;
    int spreadGateForCurrentStep;
    int spreadOctaveForCurrentStep;
    boolean resetLockedRandomizerValuesNextTime;

    public Base() {
      Arrays.fill(glideNoteCodeCurrentlyOnPostArp, ARP_NOTE_NONE);
    }

    // ── Abstract methods from C ──

    /** C: arpeggiator.h:234 — hasAnyInputNotesActive */
    public abstract boolean hasAnyInputNotesActive();

    /** C: arpeggiator.h:235 — reset */
    public abstract void reset();

    /** C: arpeggiator.h:236 — getArpType */
    public abstract ArpType getArpType();

    /** C: arpeggiator.h:264 — switchNoteOn */
    protected abstract void switchNoteOn(Settings settings, ArpReturnInstruction instruction, boolean isRatchet);

    // ── resetRatchet (arpeggiator.cpp:130-136) ──

    void resetRatchet() {
      isRatcheting = false;
      ratchetNotesMultiplier = 0;
      ratchetNotesCount = 0;
      ratchetNotesIndex = 0;
    }

    // ── resetBase (arpeggiator.cpp:137-148) ──

    void resetBase() {
      resetRatchet();
      notesPlayedFromRhythm = 0;
      lastNormalNotePlayedFromRhythm = 0;
      stepRepeatIndex = 0;
      glideOnNextNoteOff = false;
      active_note = new ArpNote();
    }

    // ── switchAnyNoteOff (arpeggiator.cpp:505-542) ──

    void switchAnyNoteOff(ArpReturnInstruction instruction) {
      // C: 506
      if (gateCurrentlyActive) {
        // C: 508-515 — schedule glide notes
        for (int n = 0; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
          instruction.glideNoteCodeOffPostArp[n] = glideNoteCodeCurrentlyOnPostArp[n];
          instruction.glideOutputMIDIChannelOff[n] = outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n];
          glideNoteCodeCurrentlyOnPostArp[n] = ARP_NOTE_NONE;
          outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n] = MIDI_CHANNEL_NONE;
        }
        // C: 516-529
        if (glideOnNextNoteOff) {
          for (int n = 0; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
            glideNoteCodeCurrentlyOnPostArp[n] = active_note.noteCodeOnPostArp[n];
            outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n] = active_note.outputMemberChannel[n];
            active_note.noteStatus[n] = ArpNoteStatus.OFF;
            active_note.noteCodeOnPostArp[n] = ARP_NOTE_NONE;
            active_note.outputMemberChannel[n] = MIDI_CHANNEL_NONE;
          }
          glideOnNextNoteOff = false;
        } else {
          // C: 531-538
          for (int n = 0; n < ARP_MAX_INSTRUCTION_NOTES; n++) {
            instruction.noteCodeOffPostArp[n] = active_note.noteCodeOnPostArp[n];
            instruction.outputMIDIChannelOff[n] = active_note.outputMemberChannel[n];
            active_note.noteStatus[n] = ArpNoteStatus.OFF;
          }
        }
        gateCurrentlyActive = false; // C:540
      }
    }

    // ── maybeSetupNewRatchet (arpeggiator.cpp:544-574) ──

    void maybeSetupNewRatchet(Settings settings) {
      // C: 546-549
      isRatcheting = isPlayRatchetForCurrentStep
          && settings.ratchetAmount > 0
          && !(settings.syncType == SyncType.EVEN && settings.syncLevel == SyncLevel.SYNC_LEVEL_256TH);
      if (isRatcheting) {
        // C: 551
        ratchetNotesMultiplier = getRandomWeighted2BitsAmount(settings.ratchetAmount);
        ratchetNotesCount = 1 << ratchetNotesMultiplier;
        // C: 553-557
        if (settings.syncLevel == SyncLevel.SYNC_LEVEL_128TH) {
          ratchetNotesMultiplier = 1;
          ratchetNotesCount = 2;
        } else if (settings.syncLevel == SyncLevel.SYNC_LEVEL_64TH) {
          // C: 558-561
          ratchetNotesMultiplier = Math.max(2, ratchetNotesMultiplier);
          ratchetNotesCount = Math.max(4, ratchetNotesCount);
        }
        // C: 563-567
        if (ratchetNotesMultiplier == 0) {
          isRatcheting = false;
          ratchetNotesCount = 0;
        }
      } else {
        // C: 570-571
        ratchetNotesMultiplier = 0;
        ratchetNotesCount = 0;
      }
      ratchetNotesIndex = 0; // C:573
    }

    // ── calculateSpreadVelocity (arpeggiator.cpp:576-601) ──

    int calculateSpreadVelocity(int velocity, int spreadVelocityForCurrentStep) {
      if (spreadVelocityForCurrentStep == 0) return velocity; // C:577-579
      int signedVelocity = velocity; // C:581
      int diff;
      // C:583-589
      if (spreadVelocityForCurrentStep < 0) {
        diff = -(Functions.multiply_32x32_rshift32((-spreadVelocityForCurrentStep) << 24, signedVelocity - 1) << 1);
      } else {
        diff = Functions.multiply_32x32_rshift32(spreadVelocityForCurrentStep << 24, 127 - signedVelocity) << 1;
      }
      signedVelocity = signedVelocity + diff; // C:591
      if (signedVelocity < 1) signedVelocity = 1; // C:593-594
      else if (signedVelocity > 127) signedVelocity = 127; // C:595-596
      return signedVelocity; // C:600
    }

    // ── evaluateRhythm (arpeggiator.cpp:604-610) ──

    boolean evaluateRhythm(int rhythm, boolean isRatchet) {
      // arpeggiator.cpp:604-610. (The C maps settings.rhythm via computeCurrentValueForUnsignedMenuItem
      // to [0, kMaxPresetArpRhythm]; fw2 uses settings.rhythm raw, so guard out-of-range → play-all.)
      if (rhythm < 0 || rhythm >= arpRhythmPatterns.length) {
        return true;
      }
      // If a ratchet, use the last normal-note index; otherwise the new rhythm index. (C:606)
      int rhythmPatternIndex = isRatchet ? lastNormalNotePlayedFromRhythm : notesPlayedFromRhythm;
      int numberOfRhythmSteps = arpRhythmPatterns[rhythm].length; // C:607
      rhythmPatternIndex = rhythmPatternIndex % numberOfRhythmSteps; // C:608 — normalize
      return arpRhythmPatterns[rhythm].steps[rhythmPatternIndex]; // C:609
    }

    // ── evaluateNoteProbability (arpeggiator.cpp:613-616) ──

    boolean evaluateNoteProbability(boolean isRatchet) {
      return isRatchet ? lastNormalNotePlayedFromNoteProbability : isPlayNoteForCurrentStep;
    }

    // ── evaluateBassProbability (arpeggiator.cpp:619-622) ──

    boolean evaluateBassProbability(boolean isRatchet) {
      return isRatchet ? lastNormalNotePlayedFromBassProbability : isPlayBassForCurrentStep;
    }

    // ── evaluateSwapProbability (arpeggiator.cpp:624-627) ──

    boolean evaluateSwapProbability(boolean isRatchet) {
      return isRatchet ? lastNormalNotePlayedFromSwapProbability : isPlayRandomStepForCurrentStep;
    }

    // ── evaluateReverseProbability (arpeggiator.cpp:630-633) ──

    boolean evaluateReverseProbability(boolean isRatchet) {
      return isRatchet ? lastNormalNotePlayedFromReverseProbability : isPlayReverseForCurrentStep;
    }

    // ── evaluateChordProbability (arpeggiator.cpp:636-639) ──

    boolean evaluateChordProbability(boolean isRatchet) {
      return isRatchet ? lastNormalNotePlayedFromChordProbability : isPlayChordForCurrentStep;
    }

    // ── executeArpStep (arpeggiator.cpp:642-731) ──

    static class StepResult {
      boolean shouldCarryOnRhythmNote;
      boolean shouldPlayNote;
      boolean shouldPlayBassNote;
      boolean shouldPlayRandomStep;
      boolean shouldPlayReverseNote;
      boolean shouldPlayChordNote;
    }

    void executeArpStep(Settings settings, int numActiveNotes, boolean isRatchet,
                        int maxSequenceLength, int rhythm, StepResult out) {
      // C: 648-667 — restart logic
      if (settings.flagForceArpRestart) {
        playedFirstArpeggiatedNoteYet = false;
        settings.flagForceArpRestart = false;
      }
      if (!isRatchet
          && (!playedFirstArpeggiatedNoteYet
              || (maxSequenceLength > 0 && notesPlayedFromSequence >= maxSequenceLength))) {
        playedFirstArpeggiatedNoteYet = false;
        notesPlayedFromSequence = 0;
        notesPlayedFromRhythm = 0;
        lastNormalNotePlayedFromRhythm = 0;
        notesPlayedFromLockedRandomizer = 0;
        randomNotesPlayedFromOctave = 0;
        stepRepeatIndex = 0;
        whichNoteCurrentlyOnPostArp = 0;
        glideOnNextNoteOff = false;
      }

      // C: 670-679 — probabilities
      out.shouldCarryOnRhythmNote = evaluateRhythm(rhythm, isRatchet);
      if (out.shouldCarryOnRhythmNote && !isRatchet) {
        calculateRandomizerAmounts(settings);
      }
      out.shouldPlayNote = evaluateNoteProbability(isRatchet);
      out.shouldPlayBassNote = evaluateBassProbability(isRatchet);
      out.shouldPlayRandomStep = evaluateSwapProbability(isRatchet);
      out.shouldPlayReverseNote = evaluateReverseProbability(isRatchet);
      out.shouldPlayChordNote = evaluateChordProbability(isRatchet);

      // C: 681-730
      if (isRatchet) {
        ratchetNotesIndex++;
      } else {
        if (out.shouldCarryOnRhythmNote) {
          maybeSetupNewRatchet(settings);
          if (!playedFirstArpeggiatedNoteYet) {
            setInitialNoteAndOctave(settings, numActiveNotes);
          } else {
            calculateNextNoteAndOrOctave(settings, numActiveNotes);
          }
          increasePatternIndexes(settings.numStepRepeats);
          playedFirstArpeggiatedNoteYet = true;
          lastNormalNotePlayedFromRhythm = notesPlayedFromRhythm;
          lastNormalNotePlayedFromNoteProbability = out.shouldPlayNote;
          lastNormalNotePlayedFromBassProbability = out.shouldPlayBassNote;
          lastNormalNotePlayedFromSwapProbability = out.shouldPlayRandomStep;
          lastNormalNotePlayedFromReverseProbability = out.shouldPlayReverseNote;
          lastNormalNotePlayedFromChordProbability = out.shouldPlayChordNote;
        }
        increaseSequenceIndexes(maxSequenceLength, rhythm);
      }
    }

    // ── increasePatternIndexes (arpeggiator.cpp:734-746) ──

    void increasePatternIndexes(int numStepRepeats) {
      randomNotesPlayedFromOctave++;
      notesPlayedFromLockedRandomizer = (notesPlayedFromLockedRandomizer + 1) % RANDOMIZER_LOCK_MAX_SAVED_VALUES;
      stepRepeatIndex++;
      if (stepRepeatIndex >= numStepRepeats) {
        stepRepeatIndex = 0;
      }
    }

    // ── increaseSequenceIndexes (arpeggiator.cpp:749-756) ──

    void increaseSequenceIndexes(int maxSequenceLength, int rhythm) {
      // arpeggiator.cpp:749-756
      if (maxSequenceLength > 0) {
        notesPlayedFromSequence++;
      }
      // C:755 — wrap by the pattern length (guard out-of-range as in evaluateRhythm: len 1).
      int len =
          (rhythm >= 0 && rhythm < arpRhythmPatterns.length) ? arpRhythmPatterns[rhythm].length : 1;
      notesPlayedFromRhythm = (notesPlayedFromRhythm + 1) % len;
    }

    // ── getRandomProbabilityResult (arpeggiator.cpp:861-870) ──

    boolean getRandomProbabilityResult(int value) {
      if (value == 0) return false;
      if (value == 0xFFFFFFFF) return true;
      int randomChance = getRandom255() | (getRandom255() << 8);
      return (value >>> 16) >= randomChance;
    }

    // ── getRandomBipolarProbabilityAmount (arpeggiator.cpp:872-884) ──

    byte getRandomBipolarProbabilityAmount(int value) {
      if (value == 0) return 0;
      int randValue = Functions.multiply_32x32_rshift32(
          Functions.multiply_32x32_rshift32(sampleTriangleDistribution(), value >> 1), 255);
      if (randValue < -127) return -127;
      if (randValue > 127) return 127;
      return (byte) randValue;
    }

    // ── getRandomWeighted2BitsAmount (arpeggiator.cpp:885-890) ──

    int getRandomWeighted2BitsAmount(int value) {
      if (value == 0) return 0;
      return Math.abs(Functions.multiply_32x32_rshift32(
          Functions.multiply_32x32_rshift32(sampleTriangleDistribution(), value >> 1), 5));
    }

    /** C: sampleTriangleDistribution — produce a triangle-distributed random Q31 value. */
    private int sampleTriangleDistribution() {
      int r1 = getRandom255() & 0xFF;
      int r2 = getRandom255() & 0xFF;
      return (r1 - r2) << 23;
    }

    // ── calculateRandomizerAmounts (arpeggiator.cpp:892-1011) ──

    void calculateRandomizerAmounts(Settings settings) {
      if (settings.randomizerLock) {
        // C:894-963 — store generated values in arrays so the sequence can repeat. Regenerate a
        // block whenever a reset is pending or that probability param changed. (int8_t = byte.)
        int N = RANDOMIZER_LOCK_MAX_SAVED_VALUES;
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedNoteProbabilityParameterValue != settings.noteProbability) {
          for (int i = 0; i < N; i++) {
            settings.lockedNoteProbabilityValues[i] =
                (byte) (getRandomProbabilityResult(settings.noteProbability) ? 1 : 0);
          }
          settings.lastLockedNoteProbabilityParameterValue = settings.noteProbability;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedBassProbabilityParameterValue != settings.bassProbability) {
          for (int i = 0; i < N; i++) {
            settings.lockedBassProbabilityValues[i] =
                (byte) (getRandomProbabilityResult(settings.bassProbability) ? 1 : 0);
          }
          settings.lastLockedBassProbabilityParameterValue = settings.bassProbability;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedSwapProbabilityParameterValue != settings.swapProbability) {
          for (int i = 0; i < N; i++) {
            settings.lockedSwapProbabilityValues[i] =
                (byte) (getRandomProbabilityResult(settings.swapProbability) ? 1 : 0);
          }
          settings.lastLockedSwapProbabilityParameterValue = settings.swapProbability;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedGlideProbabilityParameterValue != settings.glideProbability) {
          for (int i = 0; i < N; i++) {
            settings.lockedGlideProbabilityValues[i] =
                (byte) (getRandomProbabilityResult(settings.glideProbability) ? 1 : 0);
          }
          settings.lastLockedGlideProbabilityParameterValue = settings.glideProbability;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedReverseProbabilityParameterValue != settings.reverseProbability) {
          for (int i = 0; i < N; i++) {
            settings.lockedReverseProbabilityValues[i] =
                (byte) (getRandomProbabilityResult(settings.reverseProbability) ? 1 : 0);
          }
          settings.lastLockedReverseProbabilityParameterValue = settings.reverseProbability;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedChordProbabilityParameterValue != settings.chordProbability) {
          for (int i = 0; i < N; i++) {
            settings.lockedChordProbabilityValues[i] =
                (byte) (getRandomProbabilityResult(settings.chordProbability) ? 1 : 0);
          }
          settings.lastLockedChordProbabilityParameterValue = settings.chordProbability;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedRatchetProbabilityParameterValue != settings.ratchetProbability) {
          for (int i = 0; i < N; i++) {
            settings.lockedRatchetProbabilityValues[i] =
                (byte) (getRandomProbabilityResult(settings.ratchetProbability) ? 1 : 0);
          }
          settings.lastLockedRatchetProbabilityParameterValue = settings.ratchetProbability;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedSpreadVelocityParameterValue != settings.spreadVelocity) {
          for (int i = 0; i < N; i++) {
            settings.lockedSpreadVelocityValues[i] =
                (byte) getRandomBipolarProbabilityAmount(settings.spreadVelocity);
          }
          settings.lastLockedSpreadVelocityParameterValue = settings.spreadVelocity;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedSpreadGateParameterValue != settings.spreadGate) {
          for (int i = 0; i < N; i++) {
            settings.lockedSpreadGateValues[i] =
                (byte) getRandomBipolarProbabilityAmount(settings.spreadGate);
          }
          settings.lastLockedSpreadGateParameterValue = settings.spreadGate;
        }
        if (resetLockedRandomizerValuesNextTime
            || settings.lastLockedSpreadOctaveParameterValue != settings.spreadOctave) {
          for (int i = 0; i < N; i++) {
            settings.lockedSpreadOctaveValues[i] =
                (byte) getRandomWeighted2BitsAmount(settings.spreadOctave);
          }
          settings.lastLockedSpreadOctaveParameterValue = settings.spreadOctave;
        }
        resetLockedRandomizerValuesNextTime = false;

        // C:967-994 — read back the locked value for this step (int8_t widens with sign).
        int idx = notesPlayedFromLockedRandomizer % N;
        isPlayNoteForCurrentStep = settings.lockedNoteProbabilityValues[idx] != 0;
        isPlayBassForCurrentStep = settings.lockedBassProbabilityValues[idx] != 0;
        isPlayRandomStepForCurrentStep = settings.lockedSwapProbabilityValues[idx] != 0;
        isPlayGlideForCurrentStep = settings.lockedGlideProbabilityValues[idx] != 0;
        isPlayReverseForCurrentStep = settings.lockedReverseProbabilityValues[idx] != 0;
        isPlayChordForCurrentStep = settings.lockedChordProbabilityValues[idx] != 0;
        isPlayRatchetForCurrentStep = settings.lockedRatchetProbabilityValues[idx] != 0;
        spreadVelocityForCurrentStep = settings.lockedSpreadVelocityValues[idx];
        spreadGateForCurrentStep = settings.lockedSpreadGateValues[idx];
        spreadOctaveForCurrentStep = settings.lockedSpreadOctaveValues[idx];
      } else {
        // C:996-1009 — live: generate new randomized values each note. Reset locked values.
        isPlayNoteForCurrentStep = getRandomProbabilityResult(settings.noteProbability);
        isPlayBassForCurrentStep = getRandomProbabilityResult(settings.bassProbability);
        isPlayRandomStepForCurrentStep = getRandomProbabilityResult(settings.swapProbability);
        isPlayGlideForCurrentStep = getRandomProbabilityResult(settings.glideProbability);
        isPlayReverseForCurrentStep = getRandomProbabilityResult(settings.reverseProbability);
        isPlayChordForCurrentStep = getRandomProbabilityResult(settings.chordProbability);
        isPlayRatchetForCurrentStep = getRandomProbabilityResult(settings.ratchetProbability);
        spreadVelocityForCurrentStep = getRandomBipolarProbabilityAmount(settings.spreadVelocity);
        spreadGateForCurrentStep = getRandomBipolarProbabilityAmount(settings.spreadGate);
        spreadOctaveForCurrentStep = getRandomWeighted2BitsAmount(settings.spreadOctave);
        resetLockedRandomizerValuesNextTime = true;
      }
    }

    // ── calculateNextNoteAndOrOctave (arpeggiator.cpp:1013-1188) ──

    void calculateNextNoteAndOrOctave(Settings settings, int numActiveNotes) {
      // C: 1014-1019 — step repeat check
      if (stepRepeatIndex > 0) return;

      // C: 1022-1030 — FULL-RANDOM
      if (settings.noteMode == ArpNoteMode.RANDOM && settings.octaveMode == ArpOctaveMode.RANDOM) {
        whichNoteCurrentlyOnPostArp = getRandom255() % numActiveNotes;
        currentOctave = getRandom255() % settings.numOctaves;
        currentOctaveDirection = 1;
        randomNotesPlayedFromOctave = 0;
        currentDirection = 1;
        return;
      }

      // C: 1034-1187 — regular modes
      int numOctaves = settings.numOctaves;
      ArpOctaveMode octaveMode = settings.octaveMode;
      ArpNoteMode noteMode = settings.noteMode;

      boolean goesReverseOnlyThisStep = false;
      boolean changeOctave = false;
      boolean changingOctaveDirection = false;

      // NOTE — C:1042
      if (noteMode == ArpNoteMode.RANDOM) {
        whichNoteCurrentlyOnPostArp = getRandom255() % numActiveNotes;
        if (randomNotesPlayedFromOctave >= numActiveNotes) {
          changeOctave = true;
        }
      } else {
        // C:1051 — WALK modes
        if (noteMode == ArpNoteMode.WALK1 || noteMode == ArpNoteMode.WALK2 || noteMode == ArpNoteMode.WALK3) {
          int backwardsLimit = 64, stayLimit = 128;
          if (noteMode == ArpNoteMode.WALK3) {
            backwardsLimit = 51; stayLimit = 102;
          } else if (noteMode == ArpNoteMode.WALK1) {
            backwardsLimit = 77; stayLimit = 154;
          }
          int dice = getRandom255();
          if (dice < backwardsLimit) {
            goesReverseOnlyThisStep = true;
            noteMode = ArpNoteMode.DOWN;
            if (octaveMode == ArpOctaveMode.UP) octaveMode = ArpOctaveMode.DOWN;
            else if (octaveMode == ArpOctaveMode.DOWN) octaveMode = ArpOctaveMode.UP;
            currentDirection = -currentDirection;
            currentOctaveDirection = -currentOctaveDirection;
          } else if (dice < stayLimit) {
            return; // stay on same note
          }
        }

        // C:1089
        whichNoteCurrentlyOnPostArp += currentDirection;

        // C:1092-1130
        if (whichNoteCurrentlyOnPostArp >= numActiveNotes) {
          changingOctaveDirection = (currentOctave >= numOctaves - 1 && noteMode != ArpNoteMode.UP_DOWN
              && noteMode != ArpNoteMode.RANDOM && octaveMode == ArpOctaveMode.ALTERNATE);
          if (changingOctaveDirection) {
            currentDirection = -1;
            whichNoteCurrentlyOnPostArp = numActiveNotes - 2;
          } else if (noteMode == ArpNoteMode.UP_DOWN) {
            currentDirection = -1;
            whichNoteCurrentlyOnPostArp = numActiveNotes - 1;
          } else {
            whichNoteCurrentlyOnPostArp = 0;
            changeOctave = true;
          }
        } else if (whichNoteCurrentlyOnPostArp < 0) {
          changingOctaveDirection = (currentOctave <= 0 && noteMode != ArpNoteMode.UP_DOWN
              && noteMode != ArpNoteMode.RANDOM && octaveMode == ArpOctaveMode.ALTERNATE);
          if (changingOctaveDirection) {
            currentDirection = 1;
            whichNoteCurrentlyOnPostArp = 1;
          } else if (noteMode == ArpNoteMode.UP_DOWN) {
            whichNoteCurrentlyOnPostArp = 0;
            currentDirection = 1;
            changeOctave = true;
          } else {
            whichNoteCurrentlyOnPostArp = numActiveNotes - 1;
            changeOctave = true;
          }
        }
      }

      // OCTAVE — C:1134-1187
      if (changingOctaveDirection) {
        currentOctaveDirection = (currentOctaveDirection == -1) ? 1 : -1;
      }
      if (changeOctave) {
        randomNotesPlayedFromOctave = 0;
        if (numOctaves == 1) {
          currentOctave = 0;
          currentOctaveDirection = 1;
        } else if (octaveMode == ArpOctaveMode.RANDOM) {
          currentOctave = getRandom255() % numOctaves;
          currentOctaveDirection = 1;
        } else if (octaveMode == ArpOctaveMode.UP_DOWN || octaveMode == ArpOctaveMode.ALTERNATE) {
          currentOctave += currentOctaveDirection;
          if (currentOctave > numOctaves - 1) {
            currentOctaveDirection = -1;
            if (octaveMode == ArpOctaveMode.ALTERNATE) currentOctave = numOctaves - 2;
            else currentOctave = numOctaves - 1;
          } else if (currentOctave < 0) {
            currentOctaveDirection = 1;
            if (octaveMode == ArpOctaveMode.ALTERNATE) currentOctave = 1;
            else currentOctave = 0;
          }
        } else {
          currentOctaveDirection = (octaveMode == ArpOctaveMode.DOWN) ? -1 : 1;
          currentOctave += currentOctaveDirection;
          if (currentOctave >= numOctaves) currentOctave = 0;
          else if (currentOctave < 0) currentOctave = numOctaves - 1;
        }
      }
      // C:1182-1186
      if (goesReverseOnlyThisStep) {
        currentDirection = -currentDirection;
        currentOctaveDirection = -currentOctaveDirection;
      }
    }

    // ── setInitialNoteAndOctave (arpeggiator.cpp:1191-1226) ──

    void setInitialNoteAndOctave(Settings settings, int numActiveNotes) {
      // NOTE — C:1193
      if (settings.noteMode == ArpNoteMode.RANDOM) {
        whichNoteCurrentlyOnPostArp = getRandom255() % numActiveNotes;
        currentDirection = 1;
      } else if (settings.noteMode == ArpNoteMode.DOWN) {
        whichNoteCurrentlyOnPostArp = numActiveNotes - 1;
        currentDirection = -1;
      } else {
        whichNoteCurrentlyOnPostArp = 0;
        currentDirection = 1;
      }
      // OCTAVE — C:1210
      if (settings.octaveMode == ArpOctaveMode.RANDOM) {
        currentOctave = getRandom255() % settings.numOctaves;
        currentOctaveDirection = 1;
      } else if (settings.octaveMode == ArpOctaveMode.DOWN
          || (settings.octaveMode == ArpOctaveMode.ALTERNATE && settings.noteMode == ArpNoteMode.DOWN)) {
        currentOctave = settings.numOctaves - 1;
        currentOctaveDirection = -1;
      } else {
        currentOctave = 0;
        currentOctaveDirection = 1;
      }
    }

    // ── handlePendingNotes (arpeggiator.cpp:1436-1443) ──

    boolean handlePendingNotes(Settings settings, ArpReturnInstruction instruction) {
      if (active_note.isPending()) {
        instruction.arpNoteOn = active_note;
        return true;
      }
      instruction.arpNoteOn = null;
      return false;
    }

    // ── render (arpeggiator.cpp:1446-1512) ──

    /**
     * C: arpeggiator.cpp:1446-1512 — main render method. Advances the gate counter, switches
     * notes on/off based on gate threshold and phase increment.
     */
    public void render(Settings settings, ArpReturnInstruction instruction,
                       int numSamples, int gateThreshold, int phaseIncrement) {
      // C:1448-1453
      if (handlePendingNotes(settings, instruction)) return;
      if (settings.mode == ArpMode.OFF || !hasAnyInputNotesActive()) return;

      int maxGate = 1 << 24; // C:1455
      int gateThresholdSmall = gateThreshold >> 8; // C:1457

      // C:1459-1481 — spread gate
      if (spreadGateForCurrentStep != 0) {
        int signedGateThreshold = gateThresholdSmall;
        int diff;
        if (spreadGateForCurrentStep < 0) {
          diff = -(Functions.multiply_32x32_rshift32((-spreadGateForCurrentStep) << 24, signedGateThreshold) << 1);
        } else {
          diff = Functions.multiply_32x32_rshift32(spreadGateForCurrentStep << 24, maxGate - signedGateThreshold) << 1;
        }
        signedGateThreshold = signedGateThreshold + diff;
        if (signedGateThreshold < 0) signedGateThreshold = 0;
        else if (signedGateThreshold >= maxGate) signedGateThreshold = maxGate - 1;
        gateThresholdSmall = signedGateThreshold;
      }

      // C:1483-1487 — ratchet gate shortening
      if (isRatcheting) {
        gateThresholdSmall = gateThresholdSmall >> ratchetNotesMultiplier;
      }
      int maxGateForRatchet = maxGate >> ratchetNotesMultiplier; // C:1487

      // C:1489 — syncedNow = (settings->syncLevel && playbackHandler.isEitherClockActive())
      boolean syncedNow = settings.syncLevel != SyncLevel.SYNC_LEVEL_NONE && playbackClockActive;

      // C:1492-1505 — gatePos check
      if (gatePos >= ratchetNotesIndex * maxGateForRatchet + gateThresholdSmall) {
        switchAnyNoteOff(instruction);
        if (isRatcheting && ratchetNotesIndex < ratchetNotesCount - 1
            && gatePos >= (ratchetNotesIndex + 1) * maxGateForRatchet) {
          switchNoteOn(settings, instruction, true);
        } else if (!syncedNow && gatePos >= maxGate) {
          switchNoteOn(settings, instruction, false);
        }
      }

      // C:1507-1511
      if (!syncedNow) {
        gatePos &= (maxGate - 1);
      }
      gatePos += (phaseIncrement >> 8) * numSamples;
    }

    // ── doTickForward (arpeggiator.cpp:1516-1557) ──

    /** C: arpeggiator.cpp:1516-1557. Returns num ticks til next arp event. */
    public int doTickForward(Settings settings, ArpReturnInstruction instruction,
                             int clipCurrentPos, boolean currentlyPlayingReversed) {
      if (clipCurrentPos == 0) {
        notesPlayedFromLockedRandomizer = 0; // C:1519
      }
      if (handlePendingNotes(settings, instruction)) {
        return 0; // C:1523
      }
      if (settings.mode == ArpMode.OFF || settings.syncLevel == SyncLevel.SYNC_LEVEL_NONE) {
        return 2147483647; // C:1528
      }
      int ticksPerPeriod = 3 << (9 - settings.syncLevel.ordinal()); // C:1530
      if (settings.syncType == SyncType.TRIPLET) {
        ticksPerPeriod = ticksPerPeriod * 2 / 3; // C:1532-1533
      } else if (settings.syncType == SyncType.DOTTED) {
        ticksPerPeriod = ticksPerPeriod * 3 / 2; // C:1535-1536
      }

      int howFarIntoPeriod = clipCurrentPos % ticksPerPeriod; // C:1539

      if (howFarIntoPeriod == 0) {
        if (hasAnyInputNotesActive()) {
          switchAnyNoteOff(instruction);
          switchNoteOn(settings, instruction, false);
          instruction.sampleSyncLengthOn = ticksPerPeriod; // C:1546
        }
        howFarIntoPeriod = ticksPerPeriod;
      } else {
        if (!currentlyPlayingReversed) {
          howFarIntoPeriod = ticksPerPeriod - howFarIntoPeriod; // C:1552
        }
      }
      return howFarIntoPeriod; // C:1555
    }
  }

  // ── ArpeggiatorForDrum (arpeggiator.h:332-348, arpeggiator.cpp:72-216, etc.) ──

  public static class ForDrum extends Base {
    public int noteForDrum;
    public boolean invertReversedFromKitArp;

    public ForDrum() {
      active_note.velocity = 0; // C:73
    }

    @Override public void reset() {
      active_note.velocity = 0; // C:126-128
    }

    @Override public ArpType getArpType() { return ArpType.DRUM; }

    @Override public boolean hasAnyInputNotesActive() {
      return active_note.velocity != 0; // C:1432-1434
    }

    /** C: arpeggiator.cpp:150-216 — noteOn */
    public void noteOn(Settings settings, int noteCode, int originalVelocity,
                       ArpReturnInstruction instruction, int fromMIDIChannel, int[] mpeValues) {
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
        notesPlayedFromLockedRandomizer = (notesPlayedFromLockedRandomizer + 1) % RANDOMIZER_LOCK_MAX_SAVED_VALUES;
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
          instruction.invertReversed = invertReversedFromKitArp ? !isPlayReverseForCurrentStep : isPlayReverseForCurrentStep;
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
          instruction.glideOutputMIDIChannelOff[n] = outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n];
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
    protected void switchNoteOn(Settings settings, ArpReturnInstruction instruction, boolean isRatchet) {
      int maxSequenceLength = Functions.computeCurrentValueForUnsignedMenuItem(settings.sequenceLength); // C:761
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
        instruction.invertReversed = invertReversedFromKitArp ? !out.shouldPlayReverseNote : out.shouldPlayReverseNote;
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

    @Override public void reset() {
      notes.clear();
      notesAsPlayed.clear();
      notesByPattern.clear(); // C:119-123
    }

    @Override public ArpType getArpType() { return ArpType.SYNTH; }

    @Override public boolean hasAnyInputNotesActive() {
      return !notes.isEmpty(); // C:1428-1430
    }

    /** C: arpeggiator.cpp:1228-1247 — rearrangePatterntArpNotes */
    void rearrangePatternArpNotes(Settings settings) {
      notesByPattern.clear();
      int numNotes = notes.size();
      for (int i = 0; i < numNotes; i++) {
        int notesByPatternIndex = Math.min(settings.notePattern[Math.min(i, PATTERN_MAX_BUFFER_SIZE - 1)] & 0xFF, i);
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

    public void noteOn(Settings settings, int noteCode, int originalVelocity,
                       ArpReturnInstruction instruction, int fromMIDIChannel, int[] mpeValues) {
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
        notesPlayedFromLockedRandomizer = (notesPlayedFromLockedRandomizer + 1) % RANDOMIZER_LOCK_MAX_SAVED_VALUES;
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
                instruction.glideOutputMIDIChannelOff[n] = outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n];
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
    protected void switchNoteOn(Settings settings, ArpReturnInstruction instruction, boolean isRatchet) {
      int maxSequenceLength = Functions.computeCurrentValueForUnsignedMenuItem(settings.sequenceLength); // C:1250
      int rhythm = Functions.computeCurrentValueForUnsignedMenuItem(settings.rhythm); // C:1251
      int numActiveNotes = notes.size(); // C:1262

      StepResult out = new StepResult();
      executeArpStep(settings, numActiveNotes, isRatchet, maxSequenceLength, rhythm, out);

      // C:1268 — clamp
      whichNoteCurrentlyOnPostArp = Math.max(0, Math.min(whichNoteCurrentlyOnPostArp, notes.size() - 1));

      // C:1270-1308 — select arpNote
      ArpNote arpNote;
      if (out.shouldPlayBassNote) {
        arpNote = notes.get(0); // C:1273
      } else if (out.shouldPlayRandomStep) {
        arpNote = notes.get(getRandom255() % numActiveNotes); // C:1277
      } else if (settings.noteMode == ArpNoteMode.AS_PLAYED) {
        // C:1279-1291
        ArpJustNoteCode asPlayed = notesAsPlayed.get(whichNoteCurrentlyOnPostArp % notesAsPlayed.size());
        int key = findNoteIndex(asPlayed.noteCode);
        arpNote = (key < notes.size() && notes.get(key).inputCharacteristics[0] == asPlayed.noteCode)
            ? notes.get(key) : notes.get(0);
      } else if (settings.noteMode == ArpNoteMode.PATTERN) {
        // C:1292-1304
        ArpJustNoteCode byPattern = notesByPattern.get(whichNoteCurrentlyOnPostArp % notesByPattern.size());
        int key = findNoteIndex(byPattern.noteCode);
        arpNote = (key < notes.size() && notes.get(key).inputCharacteristics[0] == byPattern.noteCode)
            ? notes.get(key) : notes.get(0);
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
