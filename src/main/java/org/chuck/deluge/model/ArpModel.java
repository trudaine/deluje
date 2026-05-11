package org.chuck.deluge.model;

/** Represents the arpeggiator configuration for a synth track. */
public record ArpModel(
    boolean active,
    String mode,        // UP, DOWN, UP_DOWN, RANDOM, WALK
    float rate,
    int octaves,
    float gate,         // 0.0-1.0 note-on duty cycle
    int syncLevel,      // 0=off, 1-12 = note division (1=whole, 2=half, 4=quarter, 8=eighth, etc.)
    String noteMode,    // UP, DOWN, UPDN, RAND, WLK1, WLK2, WLK3, PLAY, PATT
    String octaveMode,  // UP, DOWN, UPDN, ALT, RAND
    int stepRepeat,     // 1-8 repeat each step N times
    int rhythmIndex,    // 0-49 selects from ~50 rhythm patterns
    int seqLength,      // 1-16 steps in pattern
    float octaveSpread, // 0.0-1.0 randomization of octave
    float gateSpread,   // 0.0-1.0 randomization of gate
    float velSpread,    // 0.0-1.0 randomization of velocity
    int ratchetAmount,  // 0-4 sub-divisions per step
    int mpeVelocity,    // 0=off, 1=on (MPE velocity tracking)
    int syncType        // 0=rate-based, 1=note-sync, etc. (firmware ArpSyncType enum)
) {

  public static ArpModel defaultConfig() {
    return new ArpModel(false, "UP", 1.0f, 1, 0.5f,
        0, "UP", "UP", 1, 0, 8,
        0.0f, 0.0f, 0.0f, 0, 0, 0);
  }
}
