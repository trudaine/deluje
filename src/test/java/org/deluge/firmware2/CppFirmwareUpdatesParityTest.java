package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.Arpeggiator.ArpNote;
import org.deluge.firmware2.Arpeggiator.ArpNoteStatus;
import org.deluge.firmware2.Arpeggiator.ArpReturnInstruction;
import org.deluge.firmware2.Arpeggiator.Settings;
import org.deluge.firmware2.Arpeggiator.SyncLevel;
import org.deluge.firmware2.Lfo.LfoConfig;
import org.deluge.firmware2.Lfo.LfoType;
import org.deluge.modulation.patch.PatchSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validates recent C++ Deluge firmware parity updates ported to Java: - Fixed fastPythag magnitude
 * calculation (#5098, C util/fast_pythag.cpp) - Exp-param patch cable sums and wave-index
 * saturation instead of wrapping (#5099, C patcher.cpp:164-175, 263-265) - S&H / random LFO state
 * retention across clock resyncs (#5039, C lfo.cpp:27-36) - Kit sample stretch length retention in
 * arpeggiator (#5074, C arpeggiator.cpp:218, 885, 1474, 1584) - Tick interpolation rate limiting at
 * ultra-high tempos (#5120, C tick_interpolation.h:20-37)
 */
public class CppFirmwareUpdatesParityTest {

  @Test
  @DisplayName("fastPythag matches C++ fast_pythag_tests.cpp test suite")
  public void testFastPythagAgainstCppSpec() {
    // C fast_pythag_tests.cpp:20-25 (TEST(FastPythagTests, zeroAndAxes))
    assertEquals(0, Functions.fastPythag(0, 0));
    assertEquals(12345, Functions.fastPythag(12345, 0));
    assertEquals(12345, Functions.fastPythag(0, -12345));
    assertEquals(5, Functions.fastPythag(3, 4));

    // C fast_pythag_tests.cpp:27-30 (TEST(FastPythagTests, isAMagnitudeNotTheLargerComponent))
    int x = 1 << 20;
    int y = 1 << 20;
    double expected = Math.sqrt(2.0) * (1 << 20);
    double actual = Functions.fastPythag(x, y);
    assertEquals(1.0, actual / expected, 0.002);

    // C fast_pythag_tests.cpp:46-50 (TEST(FastPythagTests, extremesSaturate))
    assertEquals(Integer.MAX_VALUE, Functions.fastPythag(Integer.MIN_VALUE, Integer.MIN_VALUE));
    assertEquals(Integer.MAX_VALUE, Functions.fastPythag(Integer.MAX_VALUE, Integer.MAX_VALUE));
    assertEquals(Integer.MAX_VALUE, Functions.fastPythag(Integer.MIN_VALUE, 0));
    assertEquals(Integer.MAX_VALUE, Functions.fastPythag(0, Integer.MIN_VALUE));
  }

  @Test
  @DisplayName("Exp patch cables saturate instead of wrapping past INT_MIN (#5099)")
  public void testExpPatchCableSaturation() {
    Patcher.Destination dest = new Patcher.Destination(Param.LOCAL_LPF_FREQ);
    for (int i = 0; i < 5; i++) {
      Patcher.PatchCable cable = new Patcher.PatchCable();
      cable.source = PatchSource.LFO_LOCAL_1.ordinal();
      cable.amount = 0x7FFFFFFF; // Full strength
      cable.polarity = Patcher.PatchCable.BIPOLAR;
      dest.cables.add(cable);
    }

    int[] sources = new int[PatchSource.kNumPatchSources];
    sources[PatchSource.LFO_LOCAL_1.ordinal()] = -0x7FFFFFFF; // Full negative LFO

    // Baseline neutral knob value with heavy negative modulation
    int knob = Integer.MIN_VALUE;
    int combined = Patcher.combineCablesExp(dest, knob, sources);
    // With wrapping addition, INT_MIN + negative would wrap to a positive value near INT_MAX.
    // With add_saturate, it remains clamped at INT_MIN.
    assertEquals(Integer.MIN_VALUE, combined);

    // Test patchCombineExpStep helper in Functions
    int stepResult = Functions.patchCombineExpStep(Integer.MIN_VALUE, 0x7FFFFFFF, -0x7FFFFFFF);
    assertEquals(Integer.MIN_VALUE, stepResult);
  }

  @Test
  @DisplayName("Wave index parameters saturate instead of wrapping when doubled (#5099)")
  public void testWaveIndexSaturation() {
    Patcher.Destination dest = new Patcher.Destination(Param.LOCAL_OSC_A_WAVE_INDEX);
    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = PatchSource.LFO_LOCAL_1.ordinal();
    cable.amount = 0x7FFFFFFF;
    cable.polarity = Patcher.PatchCable.BIPOLAR;
    dest.cables.add(cable);

    int[] sources = new int[PatchSource.kNumPatchSources];
    sources[PatchSource.LFO_LOCAL_1.ordinal()] = 0x60000000;

    int result = Patcher.combineCablesExp(dest, 0, sources);
    assertTrue(result > 0);
    // Doubling a large positive number must saturate to 0x7FFFFFFE rather than overflow negative
    int largePos = 0x50000000;
    assertEquals(0x7FFFFFFE, Functions.lshiftAndSaturate(largePos, 1));
  }

  @Test
  @DisplayName("Global S&H / random LFOs survive periodic clock resyncs (#5039)")
  public void testLfoGlobalResyncPreservesState() {
    Lfo lfo = new Lfo();
    LfoConfig config = new LfoConfig(LfoType.SAMPLE_AND_HOLD);
    lfo.holdValue = 123456;
    lfo.target = 654321;
    lfo.speed = 42;
    lfo.phase = 123;

    // Periodic clock resync (resetState = false) must preserve hold/speed/target
    lfo.setGlobalInitialPhase(config, false);
    assertEquals(0, lfo.phase);
    assertEquals(123456, lfo.holdValue);
    assertEquals(654321, lfo.target);
    assertEquals(42, lfo.speed);

    // Initial playback start (resetState = true) must clear them
    lfo.setGlobalInitialPhase(config, true);
    assertEquals(0, lfo.phase);
    assertEquals(0, lfo.holdValue);
    assertEquals(0, lfo.target);
    assertEquals(0, lfo.speed);
  }

  @Test
  @DisplayName("ArpNote retains sampleSyncLength and propagates across pending notes (#5074)")
  public void testArpNoteSampleSyncLength() {
    ArpNote note = new ArpNote();
    note.sampleSyncLength = 384;
    note.velocity = 100;
    note.noteStatus[0] = ArpNoteStatus.PENDING;
    note.noteCodeOnPostArp[0] = 60;

    ArpNote copy = new ArpNote();
    copy.copyFrom(note);
    assertEquals(384, copy.sampleSyncLength);

    ArpeggiatorBase arp = new ArpeggiatorForDrum();
    arp.active_note.copyFrom(note);

    ArpReturnInstruction instruction = new ArpReturnInstruction();
    Settings settings = new Settings();
    settings.syncLevel = SyncLevel.SYNC_LEVEL_16TH;

    boolean handled = arp.handlePendingNotes(settings, instruction);
    assertTrue(handled);
    assertNotNull(instruction.arpNoteOn);
    assertEquals(384, instruction.sampleSyncLengthOn);
  }

  @Test
  @DisplayName("Tick interpolation rate limiting matches C++ tick_interpolation_tests.cpp (#5120)")
  public void testTickInterpolationRateLimiting() {
    // Normal tempos: 0 (update every tick)
    assertEquals(0, org.deluge.modulation.params.ParamManager.ticksTilNextTickInterpolation(230));
    assertEquals(0, org.deluge.modulation.params.ParamManager.ticksTilNextTickInterpolation(44));

    // Fast tempos: spaced out so (ticks * samplesPerTick) >= 44
    int ticksAt3 = org.deluge.modulation.params.ParamManager.ticksTilNextTickInterpolation(3);
    assertTrue(ticksAt3 * 3 >= 44);
    assertTrue((ticksAt3 - 1) * 3 < 44);

    // Sub-sample / 0 ticks
    assertEquals(44, org.deluge.modulation.params.ParamManager.ticksTilNextTickInterpolation(0));
    assertEquals(44, org.deluge.modulation.params.ParamManager.ticksTilNextTickInterpolation(1));
  }
}
