package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins {@link Sound#resyncGlobalLFOs} (port of sound.cpp:2723-2802). The C realigns the phase of
 * the clock-synced global LFOs to the song clock; firmware2 has no transport port, so the clock
 * state is passed in (exactly the values {@code playbackHandler} would return). These guard the
 * branches, the LFO1/LFO3 → {@code lfoConfig[0]}/{@code lfoConfig[2]} mapping, and the fixed-point
 * phase-offset math.
 */
class LfoResyncTest {

  /** Independent re-derivation of the C phase-offset (sound.cpp:2742-2763). */
  private static int expectedOffset(
      Lfo.SyncLevel level,
      Lfo.SyncType type,
      long lastInternalTickDone,
      int timeSinceLastTick,
      int timePerInternalTick) {
    int shift = Lfo.SyncLevel.L_256TH.ordinal() - level.ordinal();
    int num = 3 << shift;
    if (type == Lfo.SyncType.TRIPLET) num = num * 2 / 3;
    else if (type == Lfo.SyncType.DOTTED) num = num * 3 / 2;
    long modulus = num & 0xFFFFL;
    long offsetTicks = Long.remainderUnsigned(lastInternalTickDone, modulus);
    if (timeSinceLastTick == 0 && offsetTicks == 0) return 0;
    int timePerPeriod = num * timePerInternalTick;
    int offsetTime = (int) (offsetTicks * (timePerInternalTick & 0xFFFFFFFFL)) + timeSinceLastTick;
    float ratio = (float) (offsetTime & 0xFFFFFFFFL) / (float) (timePerPeriod & 0xFFFFFFFFL);
    return (int) (long) (ratio * 4294967296.0f);
  }

  private Sound synced(int cfgIndex, Lfo.SyncLevel level, Lfo.SyncType type, Lfo.LfoType wave) {
    Sound s = new Sound();
    for (int i = 0; i < s.lfoConfig.length; i++) s.lfoConfig[i] = new Lfo.LfoConfig();
    s.lfoConfig[cfgIndex].syncLevel = level;
    s.lfoConfig[cfgIndex].syncType = type;
    s.lfoConfig[cfgIndex].waveType = wave;
    return s;
  }

  @Test
  void stoppedClockIsANoOp() {
    Sound s = synced(0, Lfo.SyncLevel.WHOLE, Lfo.SyncType.EVEN, Lfo.LfoType.TRIANGLE);
    s.globalLfos[0].phase = 0x12345678;
    s.timeStartedSkippingRenderingLFO = 99;
    s.resyncGlobalLFOs(); // clock inactive
    assertEquals(0x12345678, s.globalLfos[0].phase, "phase untouched when clock is stopped");
    assertEquals(99, s.timeStartedSkippingRenderingLFO, "skip-timer untouched when stopped");
  }

  @Test
  void unsyncedLfoIsSkippedEvenWithActiveClock() {
    Sound s = new Sound();
    for (int i = 0; i < s.lfoConfig.length; i++) s.lfoConfig[i] = new Lfo.LfoConfig(); // all NONE
    s.globalLfos[0].phase = 0x0BADF00D;
    s.resyncGlobalLFOs(true, 100, 50, 1000, 4242);
    assertEquals(0x0BADF00D, s.globalLfos[0].phase, "syncLevel NONE → no realignment");
  }

  @Test
  void rightAtFirstTickOnlyResetsToInitialPhase() {
    // lastInternalTickDone == 0 && timeSinceLastTick == 0 → setGlobalInitialPhase, no offset added.
    Sound s = synced(0, Lfo.SyncLevel.WHOLE, Lfo.SyncType.EVEN, Lfo.LfoType.TRIANGLE);
    s.globalLfos[0].phase = 0xDEADBEEF;
    s.resyncGlobalLFOs(true, 0, 0, 1000, 7);
    assertEquals(
        Lfo.getLfoInitialPhaseForZero(Lfo.LfoType.TRIANGLE),
        s.globalLfos[0].phase,
        "at the first tick the synced LFO just resets to its global initial phase");
    assertEquals(7, s.timeStartedSkippingRenderingLFO, "skip-timer reset to audioSampleTimer");
  }

  @Test
  void offsetMathMatchesC() {
    Lfo.SyncLevel level = Lfo.SyncLevel.L_256TH;
    Lfo.SyncType type = Lfo.SyncType.EVEN;
    long lastTick = 7;
    int timeSince = 0;
    int tpit = 1_000_000;
    Sound s = synced(0, level, type, Lfo.LfoType.TRIANGLE);
    s.resyncGlobalLFOs(true, lastTick, timeSince, tpit, 0);

    int base = Lfo.getLfoInitialPhaseForZero(Lfo.LfoType.TRIANGLE);
    int expected = base + expectedOffset(level, type, lastTick, timeSince, tpit);
    assertEquals(expected, s.globalLfos[0].phase, "synced phase = initial + clock offset");
    assertNotEquals(base, s.globalLfos[0].phase, "a non-trivial offset must have been applied");
  }

  @Test
  void tripletAndDottedDiffer() {
    long lastTick = 5;
    int timeSince = 13;
    int tpit = 500_000;
    int even = expectedOffset(Lfo.SyncLevel.WHOLE, Lfo.SyncType.EVEN, lastTick, timeSince, tpit);
    int trip = expectedOffset(Lfo.SyncLevel.WHOLE, Lfo.SyncType.TRIPLET, lastTick, timeSince, tpit);
    int dot = expectedOffset(Lfo.SyncLevel.WHOLE, Lfo.SyncType.DOTTED, lastTick, timeSince, tpit);
    assertNotEquals(even, trip, "triplet period differs from even");
    assertNotEquals(even, dot, "dotted period differs from even");

    Sound s = synced(0, Lfo.SyncLevel.WHOLE, Lfo.SyncType.TRIPLET, Lfo.LfoType.SINE);
    s.resyncGlobalLFOs(true, lastTick, timeSince, tpit, 0);
    int base = Lfo.getLfoInitialPhaseForZero(Lfo.LfoType.SINE);
    assertEquals(base + trip, s.globalLfos[0].phase, "triplet path wired through");
  }

  @Test
  void lfo3MapsToConfigIndexTwoAndGlobalLfoOne() {
    // LFO3_ID: lfoConfig[2] drives globalLfos[1]; globalLfos[0] must stay put.
    Sound s = synced(2, Lfo.SyncLevel.WHOLE, Lfo.SyncType.EVEN, Lfo.LfoType.TRIANGLE);
    s.globalLfos[0].phase = 0xCAFEBABE;
    s.resyncGlobalLFOs(true, 100, 50, 1000, 0);
    assertEquals(0xCAFEBABE, s.globalLfos[0].phase, "LFO1 untouched when only LFO3 is synced");
    int base = Lfo.getLfoInitialPhaseForZero(Lfo.LfoType.TRIANGLE);
    assertEquals(
        base + expectedOffset(Lfo.SyncLevel.WHOLE, Lfo.SyncType.EVEN, 100, 50, 1000),
        s.globalLfos[1].phase,
        "LFO3 realigned via lfoConfig[2] → globalLfos[1]");
  }
}
