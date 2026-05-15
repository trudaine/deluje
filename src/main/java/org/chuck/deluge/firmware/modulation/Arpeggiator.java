package org.chuck.deluge.firmware.modulation;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.model.SyncLevel;
import org.chuck.deluge.firmware.model.SyncType;
import org.chuck.deluge.firmware.util.FirmwareUtils;

public class Arpeggiator {

  public enum ArpMode {
    OFF,
    UP,
    DOWN,
    UPDOWN,
    RANDOM,
    PATTERN
  }

  public enum ArpOctaveMode {
    UP,
    DOWN,
    UPDOWN
  }

  public static class Settings {
    public ArpMode mode = ArpMode.OFF;
    public ArpOctaveMode octaveMode = ArpOctaveMode.UP;
    public int numOctaves = 1;
    public int numStepRepeats = 1;
    public SyncLevel syncLevel = SyncLevel.SYNC_LEVEL_16TH;
    public SyncType syncType = SyncType.SYNC_TYPE_EVEN;
    public int rate = 0;
    public int gate = 2147483647 / 2; // 50%

    public int rhythm = 0;
    public int noteProbability = 0xFFFFFFFF;
    public int bassProbability = 0;
    public int swapProbability = 0;
    public int glideProbability = 0;
    public int reverseProbability = 0;
    public int chordProbability = 0;
    public int ratchetProbability = 0;
    public int ratchetAmount = 0;

    public boolean randomizerLock = false;
  }

  public static class ArpNote {
    public int noteCode;
    public int velocity;
    public boolean active;
  }

  public static class ReturnInstruction {
    public boolean noteOn;
    public int noteCode;
    public int velocity;
    public boolean noteOff;
    public int noteOffCode;
  }

  private static class ArpRhythm {
    public int length;
    public boolean[] steps;

    public ArpRhythm(int length, boolean[] steps) {
      this.length = length;
      this.steps = steps;
    }
  }

  private static final ArpRhythm[] arpRhythmPatterns =
      new ArpRhythm[] {
        new ArpRhythm(1, new boolean[] {true, true, true, true, true, true}), // 0
        new ArpRhythm(3, new boolean[] {true, false, false, true, true, true}), // 1
        new ArpRhythm(3, new boolean[] {true, true, false, true, true, true}), // 2
        new ArpRhythm(3, new boolean[] {true, false, true, true, true, true}), // 3
        new ArpRhythm(4, new boolean[] {true, false, true, true, true, true}), // 4
        new ArpRhythm(4, new boolean[] {true, true, false, false, true, true}), // 5
        new ArpRhythm(4, new boolean[] {true, true, true, false, true, true}), // 6
        new ArpRhythm(4, new boolean[] {true, false, false, true, true, true}), // 7
        new ArpRhythm(4, new boolean[] {true, true, false, true, true, true}), // 8
        // Add more rhythms as needed...
      };

  private final List<ArpNote> inputNotes = new ArrayList<>();
  private final Settings settings;
  private int gatePos = 0;
  private int whichNoteIndex = -1;
  private int currentOctave = 0;
  private int octaveDirection = 1;

  public static final int PATTERN_MAX_BUFFER_SIZE = 128;
  public int[] notePattern = new int[PATTERN_MAX_BUFFER_SIZE];

  public void generateNewNotePattern() {
    java.util.Random rand = new java.util.Random();
    for (int i = 0; i < PATTERN_MAX_BUFFER_SIZE; i++) {
      notePattern[i] = rand.nextInt(i + 1);
    }
  }

  // Rhythm state
  private int notesPlayedFromRhythm = 0;
  private int lastNormalNotePlayedFromRhythm = 0;

  // Ratcheting state
  private int ratchetNotesIndex = 0;
  private int ratchetNotesMultiplier = 0;
  private int ratchetNotesCount = 0;
  private boolean isRatcheting = false;

  // Probabilities state
  private boolean isPlayNoteForCurrentStep = true;
  private boolean isPlayBassForCurrentStep = false;
  private boolean lastNormalNotePlayedFromNoteProbability = true;
  private boolean lastNormalNotePlayedFromBassProbability = false;

  public Arpeggiator(Settings settings) {
    this.settings = settings;
    generateNewNotePattern();
  }

  public void noteOn(int note, int vel) {
    ArpNote an = new ArpNote();
    an.noteCode = note;
    an.velocity = vel;
    an.active = true;
    inputNotes.add(an);
    inputNotes.sort((a, b) -> Integer.compare(a.noteCode, b.noteCode));
  }

  public void noteOff(int note) {
    inputNotes.removeIf(an -> an.noteCode == note);
    if (inputNotes.isEmpty()) {
      reset();
    }
  }

  public void reset() {
    gatePos = 0;
    whichNoteIndex = -1;
    currentOctave = 0;
    octaveDirection = 1;
    notesPlayedFromRhythm = 0;
    isRatcheting = false;
    ratchetNotesCount = 0;
    ratchetNotesIndex = 0;
  }

  public void render(ReturnInstruction instr, int numSamples, int phaseIncrement) {
    if (settings.mode == ArpMode.OFF || inputNotes.isEmpty()) return;

    int maxGate = 1 << 24;
    int gateThresholdSmall = (settings.gate >>> 8);

    if (isRatcheting) {
      gateThresholdSmall >>= ratchetNotesMultiplier;
    }
    int maxGateForRatchet = maxGate >> ratchetNotesMultiplier;

    int prevGatePos = gatePos;
    gatePos += (phaseIncrement >> 8) * numSamples;

    if (gatePos >= ratchetNotesIndex * maxGateForRatchet + gateThresholdSmall) {
      instr.noteOff = true;

      if (isRatcheting
          && ratchetNotesIndex < ratchetNotesCount - 1
          && gatePos >= (ratchetNotesIndex + 1) * maxGateForRatchet) {
        switchNoteOn(instr, true);
      } else if (gatePos >= maxGate) {
        switchNoteOn(instr, false);
      }
    }

    gatePos &= (maxGate - 1);
  }

  private void switchNoteOn(ReturnInstruction instr, boolean isRatchet) {
    if (!isRatchet) {
      boolean shouldCarryOnRhythmNote = evaluateRhythm(settings.rhythm, isRatchet);
      isPlayNoteForCurrentStep = evaluateNoteProbability();
      isPlayBassForCurrentStep = evaluateBassProbability();

      if (shouldCarryOnRhythmNote) {
        maybeSetupNewRatchet();
      } else {
        notesPlayedFromRhythm++;
        return; // Silence
      }
      notesPlayedFromRhythm++;
    } else {
      ratchetNotesIndex++;
    }

    if (isPlayNoteForCurrentStep) {
      advanceArp(instr, isPlayBassForCurrentStep);
    }
  }

  private boolean evaluateRhythm(int rhythm, boolean isRatchet) {
    if (rhythm == 0 || rhythm >= arpRhythmPatterns.length) return true;
    int rhythmPatternIndex = isRatchet ? lastNormalNotePlayedFromRhythm : notesPlayedFromRhythm;
    ArpRhythm r = arpRhythmPatterns[rhythm];
    return r.steps[rhythmPatternIndex % r.length];
  }

  private boolean evaluateNoteProbability() {
    return FirmwareUtils.getNoise() < settings.noteProbability;
  }

  private boolean evaluateBassProbability() {
    return FirmwareUtils.getNoise() < settings.bassProbability;
  }

  private void maybeSetupNewRatchet() {
    isRatcheting = settings.ratchetAmount > 0 && FirmwareUtils.getNoise() < settings.ratchetProbability;
    if (isRatcheting) {
      // ── Bit-Accurate Ratchet Math ──
      // Weighted 2-bit amount: 0-3 (translates to 1x, 2x, 4x, 8x)
      int rand = (int)(Math.random() * 65535);
      if (rand < (settings.ratchetAmount >> 16)) {
          ratchetNotesMultiplier = (int)(Math.random() * 3) + 1; // 1, 2, or 3
      } else {
          ratchetNotesMultiplier = 0;
      }
      
      if (settings.syncLevel == SyncLevel.SYNC_LEVEL_128TH) {
          ratchetNotesMultiplier = 1;
      } else if (settings.syncLevel == SyncLevel.SYNC_LEVEL_64TH) {
          ratchetNotesMultiplier = Math.max(2, ratchetNotesMultiplier);
      }
      
      if (ratchetNotesMultiplier == 0) {
          isRatcheting = false;
          ratchetNotesCount = 0;
      } else {
          ratchetNotesCount = 1 << ratchetNotesMultiplier;
      }
      ratchetNotesIndex = 0;
    } else {
      isRatcheting = false;
      ratchetNotesCount = 0;
      ratchetNotesMultiplier = 0;
    }
  }

  private void advanceArp(ReturnInstruction instr, boolean playBass) {
    if (inputNotes.isEmpty()) return;

    whichNoteIndex++;
    if (whichNoteIndex >= inputNotes.size()) {
      whichNoteIndex = 0;
      currentOctave += octaveDirection;
      if (currentOctave >= settings.numOctaves || currentOctave < 0) {
        if (settings.octaveMode == ArpOctaveMode.UPDOWN) {
          octaveDirection = -octaveDirection;
          currentOctave += 2 * octaveDirection;
        } else {
          currentOctave = 0;
        }
      }
    }

    ArpNote next = inputNotes.get(playBass ? 0 : whichNoteIndex);
    instr.noteOn = true;
    instr.noteCode = next.noteCode + (currentOctave * 12);
    instr.velocity = next.velocity;
  }
}
