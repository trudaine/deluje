package org.deluge.firmware2;

import java.util.Arrays;
import org.deluge.firmware2.Arpeggiator.ArpMode;
import org.deluge.firmware2.Arpeggiator.ArpNote;
import org.deluge.firmware2.Arpeggiator.ArpNoteMode;
import org.deluge.firmware2.Arpeggiator.ArpNoteStatus;
import org.deluge.firmware2.Arpeggiator.ArpOctaveMode;
import org.deluge.firmware2.Arpeggiator.ArpReturnInstruction;
import org.deluge.firmware2.Arpeggiator.ArpType;
import org.deluge.firmware2.Arpeggiator.Settings;
import org.deluge.firmware2.Arpeggiator.SyncLevel;
import org.deluge.firmware2.Arpeggiator.SyncType;

/** Extracted Base class for Arpeggiator to decompose Arpeggiator.java. */
public abstract class ArpeggiatorBase {
  // Setup rhythm patterns static reference
  protected static final Arpeggiator.ArpRhythm[] arpRhythmPatterns = Arpeggiator.arpRhythmPatterns;

  public ArpNote active_note = new ArpNote();
  public final int[] glideNoteCodeCurrentlyOnPostArp =
      new int[Arpeggiator.ARP_MAX_INSTRUCTION_NOTES];
  public final int[] outputMIDIChannelForGlideNoteCurrentlyOnPostArp =
      new int[Arpeggiator.ARP_MAX_INSTRUCTION_NOTES];
  public int gatePos;
  public int lastVelocity;

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

  public MusicalKey currentKey = new MusicalKey();
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

  public ArpeggiatorBase() {
    Arrays.fill(glideNoteCodeCurrentlyOnPostArp, Arpeggiator.ARP_NOTE_NONE);
    Arrays.fill(outputMIDIChannelForGlideNoteCurrentlyOnPostArp, Arpeggiator.MIDI_CHANNEL_NONE);
  }

  public abstract boolean hasAnyInputNotesActive();

  public abstract void reset();

  public abstract ArpType getArpType();

  protected abstract void switchNoteOn(
      Settings settings, ArpReturnInstruction instruction, boolean isRatchet);

  void resetRatchet() {
    isRatcheting = false;
    ratchetNotesMultiplier = 0;
    ratchetNotesCount = 0;
    ratchetNotesIndex = 0;
  }

  void resetBase() {
    resetRatchet();
    notesPlayedFromRhythm = 0;
    lastNormalNotePlayedFromRhythm = 0;
    stepRepeatIndex = 0;
    glideOnNextNoteOff = false;
    active_note = new ArpNote();
  }

  void switchAnyNoteOff(ArpReturnInstruction instruction) {
    if (gateCurrentlyActive) {
      for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
        instruction.glideNoteCodeOffPostArp[n] = glideNoteCodeCurrentlyOnPostArp[n];
        instruction.glideOutputMIDIChannelOff[n] =
            outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n];
        glideNoteCodeCurrentlyOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
        outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n] = Arpeggiator.MIDI_CHANNEL_NONE;
      }
      if (glideOnNextNoteOff) {
        for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
          glideNoteCodeCurrentlyOnPostArp[n] = active_note.noteCodeOnPostArp[n];
          outputMIDIChannelForGlideNoteCurrentlyOnPostArp[n] = active_note.outputMemberChannel[n];
          active_note.noteStatus[n] = ArpNoteStatus.OFF;
          active_note.noteCodeOnPostArp[n] = Arpeggiator.ARP_NOTE_NONE;
          active_note.outputMemberChannel[n] = Arpeggiator.MIDI_CHANNEL_NONE;
        }
        glideOnNextNoteOff = false;
      } else {
        for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
          instruction.noteCodeOffPostArp[n] = active_note.noteCodeOnPostArp[n];
          instruction.outputMIDIChannelOff[n] = active_note.outputMemberChannel[n];
          active_note.noteStatus[n] = ArpNoteStatus.OFF;
        }
      }
      gateCurrentlyActive = false;
    }
  }

  void maybeSetupNewRatchet(Settings settings) {
    isRatcheting =
        isPlayRatchetForCurrentStep
            && settings.ratchetAmount > 0
            && !(settings.syncType == SyncType.EVEN
                && settings.syncLevel == SyncLevel.SYNC_LEVEL_256TH);
    if (isRatcheting) {
      ratchetNotesMultiplier = getRandomWeighted2BitsAmount(settings.ratchetAmount);
      ratchetNotesCount = 1 << ratchetNotesMultiplier;
      if (settings.syncLevel == SyncLevel.SYNC_LEVEL_128TH) {
        ratchetNotesMultiplier = 1;
        ratchetNotesCount = 2;
      } else if (settings.syncLevel == SyncLevel.SYNC_LEVEL_64TH) {
        ratchetNotesMultiplier = Math.max(2, ratchetNotesMultiplier);
        ratchetNotesCount = Math.max(4, ratchetNotesCount);
      }
      if (ratchetNotesMultiplier == 0) {
        isRatcheting = false;
        ratchetNotesCount = 0;
      }
    } else {
      ratchetNotesMultiplier = 0;
      ratchetNotesCount = 0;
    }
    ratchetNotesIndex = 0;
  }

  int calculateSpreadVelocity(int velocity, int spreadVelocityForCurrentStep) {
    if (spreadVelocityForCurrentStep == 0) return velocity;
    int signedVelocity = velocity;
    int diff;
    if (spreadVelocityForCurrentStep < 0) {
      diff =
          -(Functions.multiply_32x32_rshift32(
                  (-spreadVelocityForCurrentStep) << 24, signedVelocity - 1)
              << 1);
    } else {
      diff =
          Functions.multiply_32x32_rshift32(
                  spreadVelocityForCurrentStep << 24, 127 - signedVelocity)
              << 1;
    }
    signedVelocity = signedVelocity + diff;
    if (signedVelocity < 1) signedVelocity = 1;
    else if (signedVelocity > 127) signedVelocity = 127;
    return signedVelocity;
  }

  boolean evaluateRhythm(int rhythm, boolean isRatchet) {
    if (rhythm < 0 || rhythm >= arpRhythmPatterns.length) {
      return true;
    }
    int rhythmPatternIndex = isRatchet ? lastNormalNotePlayedFromRhythm : notesPlayedFromRhythm;
    int numberOfRhythmSteps = arpRhythmPatterns[rhythm].length;
    rhythmPatternIndex = rhythmPatternIndex % numberOfRhythmSteps;
    return arpRhythmPatterns[rhythm].steps[rhythmPatternIndex];
  }

  boolean evaluateNoteProbability(boolean isRatchet) {
    return isRatchet ? lastNormalNotePlayedFromNoteProbability : isPlayNoteForCurrentStep;
  }

  boolean evaluateBassProbability(boolean isRatchet) {
    return isRatchet ? lastNormalNotePlayedFromBassProbability : isPlayBassForCurrentStep;
  }

  boolean evaluateSwapProbability(boolean isRatchet) {
    return isRatchet ? lastNormalNotePlayedFromSwapProbability : isPlayRandomStepForCurrentStep;
  }

  boolean evaluateReverseProbability(boolean isRatchet) {
    return isRatchet ? lastNormalNotePlayedFromReverseProbability : isPlayReverseForCurrentStep;
  }

  boolean evaluateChordProbability(boolean isRatchet) {
    return isRatchet ? lastNormalNotePlayedFromChordProbability : isPlayChordForCurrentStep;
  }

  static class StepResult {
    boolean shouldCarryOnRhythmNote;
    boolean shouldPlayNote;
    boolean shouldPlayBassNote;
    boolean shouldPlayRandomStep;
    boolean shouldPlayReverseNote;
    boolean shouldPlayChordNote;
  }

  void executeArpStep(
      Settings settings,
      int numActiveNotes,
      boolean isRatchet,
      int maxSequenceLength,
      int rhythm,
      StepResult out) {
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

    out.shouldCarryOnRhythmNote = evaluateRhythm(rhythm, isRatchet);
    if (out.shouldCarryOnRhythmNote && !isRatchet) {
      calculateRandomizerAmounts(settings);
    }
    out.shouldPlayNote = evaluateNoteProbability(isRatchet);
    out.shouldPlayBassNote = evaluateBassProbability(isRatchet);
    out.shouldPlayRandomStep = evaluateSwapProbability(isRatchet);
    out.shouldPlayReverseNote = evaluateReverseProbability(isRatchet);
    out.shouldPlayChordNote = evaluateChordProbability(isRatchet);

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

  void increasePatternIndexes(int numStepRepeats) {
    randomNotesPlayedFromOctave++;
    notesPlayedFromLockedRandomizer =
        (notesPlayedFromLockedRandomizer + 1) % Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES;
    stepRepeatIndex++;
    if (stepRepeatIndex >= numStepRepeats) {
      stepRepeatIndex = 0;
    }
  }

  void increaseSequenceIndexes(int maxSequenceLength, int rhythm) {
    if (maxSequenceLength > 0) {
      notesPlayedFromSequence++;
    }
    int len =
        (rhythm >= 0 && rhythm < arpRhythmPatterns.length) ? arpRhythmPatterns[rhythm].length : 1;
    notesPlayedFromRhythm = (notesPlayedFromRhythm + 1) % len;
  }

  boolean getRandomProbabilityResult(int value) {
    if (value == 0) return false;
    if (value == 0xFFFFFFFF) return true;
    int randomChance = Arpeggiator.getRandom255() | (Arpeggiator.getRandom255() << 8);
    return (value >>> 16) >= randomChance;
  }

  byte getRandomBipolarProbabilityAmount(int value) {
    if (value == 0) return 0;
    int randValue =
        Functions.multiply_32x32_rshift32(
            Functions.multiply_32x32_rshift32(sampleTriangleDistribution(), value >> 1), 255);
    if (randValue < -127) return -127;
    if (randValue > 127) return 127;
    return (byte) randValue;
  }

  int getRandomWeighted2BitsAmount(int value) {
    if (value == 0) return 0;
    return Math.abs(
        Functions.multiply_32x32_rshift32(
            Functions.multiply_32x32_rshift32(sampleTriangleDistribution(), value >> 1), 5));
  }

  private int sampleTriangleDistribution() {
    int r1 = Arpeggiator.getRandom255() & 0xFF;
    int r2 = Arpeggiator.getRandom255() & 0xFF;
    return (r1 - r2) << 23;
  }

  void calculateRandomizerAmounts(Settings settings) {
    if (settings.randomizerLock) {
      int N = Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES;
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

  void calculateNextNoteAndOrOctave(Settings settings, int numActiveNotes) {
    if (stepRepeatIndex > 0) return;

    if (settings.noteMode == ArpNoteMode.RANDOM && settings.octaveMode == ArpOctaveMode.RANDOM) {
      whichNoteCurrentlyOnPostArp = Arpeggiator.getRandom255() % numActiveNotes;
      currentOctave = Arpeggiator.getRandom255() % settings.numOctaves;
      currentOctaveDirection = 1;
      randomNotesPlayedFromOctave = 0;
      currentDirection = 1;
      return;
    }

    int numOctaves = settings.numOctaves;
    ArpOctaveMode octaveMode = settings.octaveMode;
    ArpNoteMode noteMode = settings.noteMode;

    boolean goesReverseOnlyThisStep = false;
    boolean changeOctave = false;
    boolean changingOctaveDirection = false;

    if (noteMode == ArpNoteMode.RANDOM) {
      whichNoteCurrentlyOnPostArp = Arpeggiator.getRandom255() % numActiveNotes;
      if (randomNotesPlayedFromOctave >= numActiveNotes) {
        changeOctave = true;
      }
    } else {
      if (noteMode == ArpNoteMode.WALK1
          || noteMode == ArpNoteMode.WALK2
          || noteMode == ArpNoteMode.WALK3) {
        int backwardsLimit = 64, stayLimit = 128;
        if (noteMode == ArpNoteMode.WALK3) {
          backwardsLimit = 51;
          stayLimit = 102;
        } else if (noteMode == ArpNoteMode.WALK1) {
          backwardsLimit = 77;
          stayLimit = 154;
        }
        int dice = Arpeggiator.getRandom255();
        if (dice < backwardsLimit) {
          goesReverseOnlyThisStep = true;
          noteMode = ArpNoteMode.DOWN;
          if (octaveMode == ArpOctaveMode.UP) octaveMode = ArpOctaveMode.DOWN;
          else if (octaveMode == ArpOctaveMode.DOWN) octaveMode = ArpOctaveMode.UP;
          currentDirection = -currentDirection;
          currentOctaveDirection = -currentOctaveDirection;
        } else if (dice < stayLimit) {
          return;
        }
      }

      whichNoteCurrentlyOnPostArp += currentDirection;

      if (whichNoteCurrentlyOnPostArp >= numActiveNotes) {
        changingOctaveDirection =
            (currentOctave >= numOctaves - 1
                && noteMode != ArpNoteMode.UP_DOWN
                && noteMode != ArpNoteMode.RANDOM
                && octaveMode == ArpOctaveMode.ALTERNATE);
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
        changingOctaveDirection =
            (currentOctave <= 0
                && noteMode != ArpNoteMode.UP_DOWN
                && noteMode != ArpNoteMode.RANDOM
                && octaveMode == ArpOctaveMode.ALTERNATE);
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

    if (changingOctaveDirection) {
      currentOctaveDirection = (currentOctaveDirection == -1) ? 1 : -1;
    }
    if (changeOctave) {
      randomNotesPlayedFromOctave = 0;
      if (numOctaves == 1) {
        currentOctave = 0;
        currentOctaveDirection = 1;
      } else if (octaveMode == ArpOctaveMode.RANDOM) {
        currentOctave = Arpeggiator.getRandom255() % numOctaves;
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
    if (goesReverseOnlyThisStep) {
      currentDirection = -currentDirection;
      currentOctaveDirection = -currentOctaveDirection;
    }
  }

  void setInitialNoteAndOctave(Settings settings, int numActiveNotes) {
    if (settings.noteMode == ArpNoteMode.RANDOM) {
      whichNoteCurrentlyOnPostArp = Arpeggiator.getRandom255() % numActiveNotes;
      currentDirection = 1;
    } else if (settings.noteMode == ArpNoteMode.DOWN) {
      whichNoteCurrentlyOnPostArp = numActiveNotes - 1;
      currentDirection = -1;
    } else {
      whichNoteCurrentlyOnPostArp = 0;
      currentDirection = 1;
    }
    if (settings.octaveMode == ArpOctaveMode.RANDOM) {
      currentOctave = Arpeggiator.getRandom255() % settings.numOctaves;
      currentOctaveDirection = 1;
    } else if (settings.octaveMode == ArpOctaveMode.DOWN
        || (settings.octaveMode == ArpOctaveMode.ALTERNATE
            && settings.noteMode == ArpNoteMode.DOWN)) {
      currentOctave = settings.numOctaves - 1;
      currentOctaveDirection = -1;
    } else {
      currentOctave = 0;
      currentOctaveDirection = 1;
    }
  }

  boolean handlePendingNotes(Settings settings, ArpReturnInstruction instruction) {
    if (active_note.isPending()) {
      instruction.arpNoteOn = active_note;
      return true;
    }
    instruction.arpNoteOn = null;
    return false;
  }

  public void render(
      Settings settings,
      ArpReturnInstruction instruction,
      int numSamples,
      int gateThreshold,
      int phaseIncrement) {
    if (handlePendingNotes(settings, instruction)) return;
    if (settings.mode == ArpMode.OFF || !hasAnyInputNotesActive()) return;

    int maxGate = 1 << 24;
    // arpeggiator.cpp:1457 — gateThreshold arrives FULL-SCALE (uint32, knob + 2^31); this >> 8
    // is the only shift (the caller must not pre-shift). Logical: it's a uint32 in C.
    int gateThresholdSmall = gateThreshold >>> 8;

    if (spreadGateForCurrentStep != 0) {
      int signedGateThreshold = gateThresholdSmall;
      int diff;
      if (spreadGateForCurrentStep < 0) {
        diff =
            -(Functions.multiply_32x32_rshift32(
                    (-spreadGateForCurrentStep) << 24, signedGateThreshold)
                << 1);
      } else {
        diff =
            Functions.multiply_32x32_rshift32(
                    spreadGateForCurrentStep << 24, maxGate - signedGateThreshold)
                << 1;
      }
      signedGateThreshold = signedGateThreshold + diff;
      if (signedGateThreshold < 0) signedGateThreshold = 0;
      else if (signedGateThreshold >= maxGate) signedGateThreshold = maxGate - 1;
      gateThresholdSmall = signedGateThreshold;
    }

    if (isRatcheting) {
      gateThresholdSmall = gateThresholdSmall >> ratchetNotesMultiplier;
    }
    int maxGateForRatchet = maxGate >> ratchetNotesMultiplier;

    boolean syncedNow = settings.syncLevel != SyncLevel.SYNC_LEVEL_NONE && playbackClockActive;

    if (gatePos >= ratchetNotesIndex * maxGateForRatchet + gateThresholdSmall) {
      switchAnyNoteOff(instruction);
      if (isRatcheting
          && ratchetNotesIndex < ratchetNotesCount - 1
          && gatePos >= (ratchetNotesIndex + 1) * maxGateForRatchet) {
        switchNoteOn(settings, instruction, true);
      } else if (!syncedNow && gatePos >= maxGate) {
        switchNoteOn(settings, instruction, false);
      }
    }

    if (!syncedNow) {
      gatePos &= (maxGate - 1);
    }
    gatePos += (phaseIncrement >> 8) * numSamples;
  }

  public int doTickForward(
      Settings settings,
      ArpReturnInstruction instruction,
      int clipCurrentPos,
      boolean currentlyPlayingReversed) {
    if (clipCurrentPos == 0) {
      notesPlayedFromLockedRandomizer = 0;
    }
    if (handlePendingNotes(settings, instruction)) {
      return 0;
    }
    if (settings.mode == ArpMode.OFF || settings.syncLevel == SyncLevel.SYNC_LEVEL_NONE) {
      return 2147483647;
    }
    int ticksPerPeriod = 3 << (9 - settings.syncLevel.ordinal());
    if (settings.syncType == SyncType.TRIPLET) {
      ticksPerPeriod = ticksPerPeriod * 2 / 3;
    } else if (settings.syncType == SyncType.DOTTED) {
      ticksPerPeriod = ticksPerPeriod * 3 / 2;
    }

    int howFarIntoPeriod = clipCurrentPos % ticksPerPeriod;

    if (howFarIntoPeriod == 0) {
      if (hasAnyInputNotesActive()) {
        switchAnyNoteOff(instruction);
        switchNoteOn(settings, instruction, false);
        instruction.sampleSyncLengthOn = ticksPerPeriod;
      }
      howFarIntoPeriod = ticksPerPeriod;
    } else {
      if (!currentlyPlayingReversed) {
        howFarIntoPeriod = ticksPerPeriod - howFarIntoPeriod;
      }
    }
    return howFarIntoPeriod;
  }
}
