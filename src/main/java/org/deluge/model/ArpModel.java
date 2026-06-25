package org.deluge.model;

/** Represents the arpeggiator configuration for a synth track. */
public record ArpModel(
    boolean active,
    String mode, // UP, DOWN, UP_DOWN, RANDOM, WALK
    float rate,
    int octaves,
    float gate, // 0.0-1.0 note-on duty cycle
    int syncLevel, // 0=off, 1-12 = note division (1=whole, 2=half, etc.)
    String noteMode,
    String octaveMode,
    int stepRepeat,
    int rhythmIndex,
    int seqLength,
    float octaveSpread,
    float gateSpread,
    float velSpread,
    int ratchetAmount,
    int mpeVelocity,
    int syncType,
    float noteProbability,
    float bassProbability,
    float swapProbability,
    float glideProbability,
    float reverseProbability,
    float chordProbability,
    float ratchetProbability,
    int chordPolyphony,
    String notePattern,
    int chordType,

    // New Arp / Randomizer fields
    int numOctaves,
    int kitArp,
    int randomizerLock,
    int lastLockedNoteProb,
    String lockedNoteProbArray,
    int lastLockedBassProb,
    String lockedBassProbArray,
    int lastLockedSwapProb,
    String lockedSwapProbArray,
    int lastLockedGlideProb,
    String lockedGlideProbArray,
    int lastLockedReverseProb,
    String lockedReverseProbArray,
    int lastLockedChordProb,
    String lockedChordProbArray,
    int lastLockedRatchetProb,
    String lockedRatchetProbArray,
    int lastLockedVelocitySpread,
    String lockedVelocitySpreadArray,
    int lastLockedGateSpread,
    String lockedGateSpreadArray,
    int lastLockedOctaveSpread,
    String lockedOctaveSpreadArray) {

  public static ArpModel defaultConfig() {
    return new ArpModel(
        false,
        "UP",
        1.0f,
        1,
        0.5f,
        0,
        "UP",
        "UP",
        1,
        0,
        8,
        0.0f,
        0.0f,
        0.0f,
        0,
        0,
        0,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0.0f,
        0,
        "00000000000000000000000000000000",
        0,
        // New fields
        2, // numOctaves
        0, // kitArp
        0, // randomizerLock
        0, // lastLockedNoteProb
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000",
        0,
        "00000000000000000000000000000000");
  }

  public Builder toBuilder() {
    return new Builder(this);
  }

  public static final class Builder {
    private boolean active;
    private String mode;
    private float rate;
    private int octaves;
    private float gate;
    private int syncLevel;
    private String noteMode;
    private String octaveMode;
    private int stepRepeat;
    private int rhythmIndex;
    private int seqLength;
    private float octaveSpread;
    private float gateSpread;
    private float velSpread;
    private int ratchetAmount;
    private int mpeVelocity;
    private int syncType;
    private float noteProbability;
    private float bassProbability;
    private float swapProbability;
    private float glideProbability;
    private float reverseProbability;
    private float chordProbability;
    private float ratchetProbability;
    private int chordPolyphony;
    private String notePattern;
    private int chordType;

    private int numOctaves;
    private int kitArp;
    private int randomizerLock;
    private int lastLockedNoteProb;
    private String lockedNoteProbArray;
    private int lastLockedBassProb;
    private String lockedBassProbArray;
    private int lastLockedSwapProb;
    private String lockedSwapProbArray;
    private int lastLockedGlideProb;
    private String lockedGlideProbArray;
    private int lastLockedReverseProb;
    private String lockedReverseProbArray;
    private int lastLockedChordProb;
    private String lockedChordProbArray;
    private int lastLockedRatchetProb;
    private String lockedRatchetProbArray;
    private int lastLockedVelocitySpread;
    private String lockedVelocitySpreadArray;
    private int lastLockedGateSpread;
    private String lockedGateSpreadArray;
    private int lastLockedOctaveSpread;
    private String lockedOctaveSpreadArray;

    private Builder(ArpModel m) {
      this.active = m.active;
      this.mode = m.mode;
      this.rate = m.rate;
      this.octaves = m.octaves;
      this.gate = m.gate;
      this.syncLevel = m.syncLevel;
      this.noteMode = m.noteMode;
      this.octaveMode = m.octaveMode;
      this.stepRepeat = m.stepRepeat;
      this.rhythmIndex = m.rhythmIndex;
      this.seqLength = m.seqLength;
      this.octaveSpread = m.octaveSpread;
      this.gateSpread = m.gateSpread;
      this.velSpread = m.velSpread;
      this.ratchetAmount = m.ratchetAmount;
      this.mpeVelocity = m.mpeVelocity;
      this.syncType = m.syncType;
      this.noteProbability = m.noteProbability;
      this.bassProbability = m.bassProbability;
      this.swapProbability = m.swapProbability;
      this.glideProbability = m.glideProbability;
      this.reverseProbability = m.reverseProbability;
      this.chordProbability = m.chordProbability;
      this.ratchetProbability = m.ratchetProbability;
      this.chordPolyphony = m.chordPolyphony;
      this.notePattern = m.notePattern;
      this.chordType = m.chordType;

      this.numOctaves = m.numOctaves;
      this.kitArp = m.kitArp;
      this.randomizerLock = m.randomizerLock;
      this.lastLockedNoteProb = m.lastLockedNoteProb;
      this.lockedNoteProbArray = m.lockedNoteProbArray;
      this.lastLockedBassProb = m.lastLockedBassProb;
      this.lockedBassProbArray = m.lockedBassProbArray;
      this.lastLockedSwapProb = m.lastLockedSwapProb;
      this.lockedSwapProbArray = m.lockedSwapProbArray;
      this.lastLockedGlideProb = m.lastLockedGlideProb;
      this.lockedGlideProbArray = m.lockedGlideProbArray;
      this.lastLockedReverseProb = m.lastLockedReverseProb;
      this.lockedReverseProbArray = m.lockedReverseProbArray;
      this.lastLockedChordProb = m.lastLockedChordProb;
      this.lockedChordProbArray = m.lockedChordProbArray;
      this.lastLockedRatchetProb = m.lastLockedRatchetProb;
      this.lockedRatchetProbArray = m.lockedRatchetProbArray;
      this.lastLockedVelocitySpread = m.lastLockedVelocitySpread;
      this.lockedVelocitySpreadArray = m.lockedVelocitySpreadArray;
      this.lastLockedGateSpread = m.lastLockedGateSpread;
      this.lockedGateSpreadArray = m.lockedGateSpreadArray;
      this.lastLockedOctaveSpread = m.lastLockedOctaveSpread;
      this.lockedOctaveSpreadArray = m.lockedOctaveSpreadArray;
    }

    public Builder active(boolean v) {
      this.active = v;
      return this;
    }

    public Builder mode(String v) {
      this.mode = v;
      return this;
    }

    public Builder rate(float v) {
      this.rate = v;
      return this;
    }

    public Builder octaves(int v) {
      this.octaves = v;
      return this;
    }

    public Builder gate(float v) {
      this.gate = v;
      return this;
    }

    public Builder syncLevel(int v) {
      this.syncLevel = v;
      return this;
    }

    public Builder noteMode(String v) {
      this.noteMode = v;
      return this;
    }

    public Builder octaveMode(String v) {
      this.octaveMode = v;
      return this;
    }

    public Builder stepRepeat(int v) {
      this.stepRepeat = v;
      return this;
    }

    public Builder rhythmIndex(int v) {
      this.rhythmIndex = v;
      return this;
    }

    public Builder seqLength(int v) {
      this.seqLength = v;
      return this;
    }

    public Builder octaveSpread(float v) {
      this.octaveSpread = v;
      return this;
    }

    public Builder gateSpread(float v) {
      this.gateSpread = v;
      return this;
    }

    public Builder velSpread(float v) {
      this.velSpread = v;
      return this;
    }

    public Builder ratchetAmount(int v) {
      this.ratchetAmount = v;
      return this;
    }

    public Builder mpeVelocity(int v) {
      this.mpeVelocity = v;
      return this;
    }

    public Builder syncType(int v) {
      this.syncType = v;
      return this;
    }

    public Builder noteProbability(float v) {
      this.noteProbability = v;
      return this;
    }

    public Builder bassProbability(float v) {
      this.bassProbability = v;
      return this;
    }

    public Builder swapProbability(float v) {
      this.swapProbability = v;
      return this;
    }

    public Builder glideProbability(float v) {
      this.glideProbability = v;
      return this;
    }

    public Builder reverseProbability(float v) {
      this.reverseProbability = v;
      return this;
    }

    public Builder chordProbability(float v) {
      this.chordProbability = v;
      return this;
    }

    public Builder ratchetProbability(float v) {
      this.ratchetProbability = v;
      return this;
    }

    public Builder chordPolyphony(int v) {
      this.chordPolyphony = v;
      return this;
    }

    public Builder notePattern(String v) {
      this.notePattern = v;
      return this;
    }

    public Builder chordType(int v) {
      this.chordType = v;
      return this;
    }

    public Builder numOctaves(int v) {
      this.numOctaves = v;
      return this;
    }

    public Builder kitArp(int v) {
      this.kitArp = v;
      return this;
    }

    public Builder randomizerLock(int v) {
      this.randomizerLock = v;
      return this;
    }

    public Builder lastLockedNoteProb(int v) {
      this.lastLockedNoteProb = v;
      return this;
    }

    public Builder lockedNoteProbArray(String v) {
      this.lockedNoteProbArray = v;
      return this;
    }

    public Builder lastLockedBassProb(int v) {
      this.lastLockedBassProb = v;
      return this;
    }

    public Builder lockedBassProbArray(String v) {
      this.lockedBassProbArray = v;
      return this;
    }

    public Builder lastLockedSwapProb(int v) {
      this.lastLockedSwapProb = v;
      return this;
    }

    public Builder lockedSwapProbArray(String v) {
      this.lockedSwapProbArray = v;
      return this;
    }

    public Builder lastLockedGlideProb(int v) {
      this.lastLockedGlideProb = v;
      return this;
    }

    public Builder lockedGlideProbArray(String v) {
      this.lockedGlideProbArray = v;
      return this;
    }

    public Builder lastLockedReverseProb(int v) {
      this.lastLockedReverseProb = v;
      return this;
    }

    public Builder lockedReverseProbArray(String v) {
      this.lockedReverseProbArray = v;
      return this;
    }

    public Builder lastLockedChordProb(int v) {
      this.lastLockedChordProb = v;
      return this;
    }

    public Builder lockedChordProbArray(String v) {
      this.lockedChordProbArray = v;
      return this;
    }

    public Builder lastLockedRatchetProb(int v) {
      this.lastLockedRatchetProb = v;
      return this;
    }

    public Builder lockedRatchetProbArray(String v) {
      this.lockedRatchetProbArray = v;
      return this;
    }

    public Builder lastLockedVelocitySpread(int v) {
      this.lastLockedVelocitySpread = v;
      return this;
    }

    public Builder lockedVelocitySpreadArray(String v) {
      this.lockedVelocitySpreadArray = v;
      return this;
    }

    public Builder lastLockedGateSpread(int v) {
      this.lastLockedGateSpread = v;
      return this;
    }

    public Builder lockedGateSpreadArray(String v) {
      this.lockedGateSpreadArray = v;
      return this;
    }

    public Builder lastLockedOctaveSpread(int v) {
      this.lastLockedOctaveSpread = v;
      return this;
    }

    public Builder lockedOctaveSpreadArray(String v) {
      this.lockedOctaveSpreadArray = v;
      return this;
    }

    public ArpModel build() {
      return new ArpModel(
          active,
          mode,
          rate,
          octaves,
          gate,
          syncLevel,
          noteMode,
          octaveMode,
          stepRepeat,
          rhythmIndex,
          seqLength,
          octaveSpread,
          gateSpread,
          velSpread,
          ratchetAmount,
          mpeVelocity,
          syncType,
          noteProbability,
          bassProbability,
          swapProbability,
          glideProbability,
          reverseProbability,
          chordProbability,
          ratchetProbability,
          chordPolyphony,
          notePattern,
          chordType,
          numOctaves,
          kitArp,
          randomizerLock,
          lastLockedNoteProb,
          lockedNoteProbArray,
          lastLockedBassProb,
          lockedBassProbArray,
          lastLockedSwapProb,
          lockedSwapProbArray,
          lastLockedGlideProb,
          lockedGlideProbArray,
          lastLockedReverseProb,
          lockedReverseProbArray,
          lastLockedChordProb,
          lockedChordProbArray,
          lastLockedRatchetProb,
          lockedRatchetProbArray,
          lastLockedVelocitySpread,
          lockedVelocitySpreadArray,
          lastLockedGateSpread,
          lockedGateSpreadArray,
          lastLockedOctaveSpread,
          lockedOctaveSpreadArray);
    }
  }
}
