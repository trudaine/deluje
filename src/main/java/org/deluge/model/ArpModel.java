package org.deluge.model;

/** Represents the arpeggiator configuration for a synth track. */
public record ArpModel(
    boolean active,
    String mode, // UP, DOWN, UP_DOWN, RANDOM, WALK
    float rate,
    int octaves,
    float gate, // 0.0-1.0 note-on duty cycle
    int syncLevel, // 0=off, 1-12 = note division (1=whole, 2=half, 4=quarter, 8=eighth, etc.)
    String noteMode, // UP, DOWN, UPDN, RAND, WLK1, WLK2, WLK3, PLAY, PATT
    String octaveMode, // UP, DOWN, UPDN, ALT, RAND
    int stepRepeat, // 1-8 repeat each step N times
    int rhythmIndex, // 0-49 selects from ~50 rhythm patterns
    int seqLength, // 1-16 steps in pattern
    float octaveSpread, // 0.0-1.0 randomization of octave
    float gateSpread, // 0.0-1.0 randomization of gate
    float velSpread, // 0.0-1.0 randomization of velocity
    int ratchetAmount, // 0-4 sub-divisions per step
    int mpeVelocity, // 0=off, 1=on (MPE velocity tracking)
    int syncType, // 0=rate-based, 1=note-sync, etc. (firmware ArpSyncType enum)
    float noteProbability, // 0.0-1.0 probability a note fires (firmware UNPATCHED_NOTE_PROBABILITY)
    float bassProbability, // 0.0-1.0 probability bass note fires (UNPATCHED_ARP_BASS_PROBABILITY)
    float swapProbability, // 0.0-1.0 probability note order swaps (UNPATCHED_ARP_SWAP_PROBABILITY)
    float glideProbability, // 0.0-1.0 probability glide between notes
    // (UNPATCHED_ARP_GLIDE_PROBABILITY)
    float reverseProbability, // 0.0-1.0 probability direction reverses
    // (UNPATCHED_REVERSE_PROBABILITY)
    float chordProbability, // 0.0-1.0 probability chord triggers (UNPATCHED_ARP_CHORD_PROBABILITY)
    float
        ratchetProbability, // 0.0-1.0 probability ratchet fires (UNPATCHED_ARP_RATCHET_PROBABILITY)
    int chordPolyphony // 0=off, 1-8 voices in chord (UNPATCHED_ARP_CHORD_POLYPHONY)
    ) {

  public static ArpModel defaultConfig() {
    return new ArpModel(
        false, "UP", 1.0f, 1, 0.5f, 0, "UP", "UP", 1, 0, 8, 0.0f, 0.0f, 0.0f, 0, 0, 0, 0.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0);
  }

  /** Copy-with builder (records have no withers) — used by the model-backed ARP panel. */
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

    private Builder(ArpModel m) {
      active = m.active;
      mode = m.mode;
      rate = m.rate;
      octaves = m.octaves;
      gate = m.gate;
      syncLevel = m.syncLevel;
      noteMode = m.noteMode;
      octaveMode = m.octaveMode;
      stepRepeat = m.stepRepeat;
      rhythmIndex = m.rhythmIndex;
      seqLength = m.seqLength;
      octaveSpread = m.octaveSpread;
      gateSpread = m.gateSpread;
      velSpread = m.velSpread;
      ratchetAmount = m.ratchetAmount;
      mpeVelocity = m.mpeVelocity;
      syncType = m.syncType;
      noteProbability = m.noteProbability;
      bassProbability = m.bassProbability;
      swapProbability = m.swapProbability;
      glideProbability = m.glideProbability;
      reverseProbability = m.reverseProbability;
      chordProbability = m.chordProbability;
      ratchetProbability = m.ratchetProbability;
      chordPolyphony = m.chordPolyphony;
    }

    public Builder active(boolean v) {
      active = v;
      return this;
    }

    public Builder mode(String v) {
      mode = v;
      return this;
    }

    public Builder rate(float v) {
      rate = v;
      return this;
    }

    public Builder octaves(int v) {
      octaves = v;
      return this;
    }

    public Builder gate(float v) {
      gate = v;
      return this;
    }

    public Builder syncLevel(int v) {
      syncLevel = v;
      return this;
    }

    public Builder noteMode(String v) {
      noteMode = v;
      return this;
    }

    public Builder octaveMode(String v) {
      octaveMode = v;
      return this;
    }

    public Builder stepRepeat(int v) {
      stepRepeat = v;
      return this;
    }

    public Builder rhythmIndex(int v) {
      rhythmIndex = v;
      return this;
    }

    public Builder seqLength(int v) {
      seqLength = v;
      return this;
    }

    public Builder octaveSpread(float v) {
      octaveSpread = v;
      return this;
    }

    public Builder gateSpread(float v) {
      gateSpread = v;
      return this;
    }

    public Builder velSpread(float v) {
      velSpread = v;
      return this;
    }

    public Builder ratchetAmount(int v) {
      ratchetAmount = v;
      return this;
    }

    public Builder mpeVelocity(int v) {
      mpeVelocity = v;
      return this;
    }

    public Builder syncType(int v) {
      syncType = v;
      return this;
    }

    public Builder noteProbability(float v) {
      noteProbability = v;
      return this;
    }

    public Builder chordProbability(float v) {
      chordProbability = v;
      return this;
    }

    public Builder chordPolyphony(int v) {
      chordPolyphony = v;
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
          chordPolyphony);
    }
  }
}
