package org.deluge.modulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Param;
import org.deluge.model.ClipModel;
import org.deluge.model.ClipType;
import org.deluge.model.NoteRowModel;
import org.deluge.model.SequenceDirection;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.params.ParamManager;
import org.junit.jupiter.api.Test;

/**
 * Tests live sample-rate and tick-rate automation interpolation across {@link AutoParam}, {@link
 * ParamManager}, {@link ClipModel}, {@link FirmwareSound}, and {@link FirmwareKit} against C++
 * {@code auto_param.cpp}, {@code param_manager.cpp}, {@code sound_instrument.cpp}, and {@code
 * kit.cpp}.
 */
class AutoParamLiveInterpolationTest {

  @Test
  void setPlayPosSetsMidRampValueAndIncrementInBothDirections() {
    // C auto_param.cpp:1546-1579, 810-835 — setPlayPos mid-ramp computes currentValue via
    // getValueAtPos and sets valueIncrementPerHalfTick toward the next node in our direction.
    AutoParam ap = new AutoParam(Param.LOCAL_LPF_FREQ);
    ap.addNode(0, 0, false);
    ap.addNode(24, 480_000_000, true);

    ap.setPlayPos(12, 96, false);
    assertEquals(240_000_000, ap.currentValue);
    int expectedFwdInc = ((480_000_000 >> 1) - (240_000_000 >> 1)) / 12;
    assertEquals(expectedFwdInc, ap.valueIncrementPerHalfTick);
    assertTrue(ap.hasInterpolationIncrement());

    // Reversed at pos 12 inside [0, 24]: rightNode is pos 24 (interpolated), so our target in
    // reverse is leftNode at pos 0 (value 0), 12 ticks away.
    ap.setPlayPos(12, 96, true);
    assertEquals(240_000_000, ap.currentValue);
    int expectedRevInc = ((0 >> 1) - (240_000_000 >> 1)) / 12;
    assertEquals(expectedRevInc, ap.valueIncrementPerHalfTick);
    assertTrue(ap.valueIncrementPerHalfTick < 0);

    // ClipModel.setPos(12) calls setPosForParamManagers so starting mid-clip begins ramping.
    ClipModel clip = new ClipModel("c", 1, 4);
    clip.setType(ClipType.INSTRUMENT);
    FirmwareSound synth = new FirmwareSound();
    clip.setSound(synth);
    synth.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 0, 0, false);
    synth.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 480_000_000, 24, true);

    clip.setPos(12);
    AutoParam clipAuto = synth.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertEquals(240_000_000, clipAuto.currentValue);
    assertEquals(expectedFwdInc, clipAuto.valueIncrementPerHalfTick);
  }

  @Test
  void paramManagerWithoutSampleInterpolationAdvancesViaTickTicks() {
    // C param_manager.cpp:388-404 — when mayInterpolate is false (e.g. MIDI clips, clip.cpp:311),
    // ParamManager.processCurrentPos still sets up AutoParam's slope, forces ticksTilNextEvent=0,
    // and advances currentValue via tickTicks(ticksSkipped) each tick until the target node.
    ParamManager pm = new ParamManager();
    pm.recordParamValue(Param.LOCAL_LPF_FREQ, 0, 0, false);
    pm.recordParamValue(Param.LOCAL_LPF_FREQ, 480_000_000, 24, true);

    pm.processCurrentPos(0, 0, 96, false, false, false);
    AutoParam ap = pm.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertEquals(0, ap.currentValue);
    assertTrue(ap.hasInterpolationIncrement());
    assertEquals(0, pm.ticksTilNextEvent, "Must wake every tick when !mayInterpolate");

    for (int tick = 1; tick < 24; tick++) {
      pm.processCurrentPos(1, tick, 96, false, false, false);
      assertEquals(tick * 20_000_000, ap.currentValue, 1024);
      assertEquals(0, pm.ticksTilNextEvent);
    }

    pm.processCurrentPos(1, 24, 96, false, false, false);
    assertEquals(480_000_000, ap.currentValue);
    assertFalse(ap.hasInterpolationIncrement());
    assertEquals(72, pm.ticksTilNextEvent);
  }

  @Test
  void synthAndKitRenderOutputTicksInterpolatingParamsPerAudioBlock() {
    // C sound_instrument.cpp:113-131, kit.cpp:588-619 — renderOutput ticks interpolating
    // ParamManagers by numSamples every block while the clock and clip are active.
    FirmwareSound synth = new FirmwareSound();
    synth.currentBpm = 120.0f;
    synth.inputTickMagnitude = 2;
    synth.fw2Sound.arpeggiator.playbackClockActive = true;
    synth.shouldLimitDelayFeedback = false;
    synth.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 0, 0, false);
    synth.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 480_000_000, 24, true);
    synth.paramManager.setPlayPos(0, 96, false);
    synth.paramManager.processCurrentPos(0, 0, 96, false, false, true);

    AutoParam synthAuto = synth.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    long[] out = new long[256];
    long[] rev = new long[128];

    synth.renderOutput(out, 128, rev);
    int afterOneBlock = synthAuto.currentValue;
    assertTrue(afterOneBlock > 0, "Synth renderOutput must advance interpolating AutoParam");

    // Inactive clip (shouldLimitDelayFeedback = true) or stopped clock must NOT tick.
    synth.shouldLimitDelayFeedback = true;
    synth.renderOutput(out, 128, rev);
    assertEquals(
        afterOneBlock, synthAuto.currentValue, "Inactive clip must not tick AutoParam samples");

    synth.shouldLimitDelayFeedback = false;
    synth.fw2Sound.arpeggiator.playbackClockActive = false;
    synth.renderOutput(out, 128, rev);
    assertEquals(
        afterOneBlock, synthAuto.currentValue, "Stopped transport must not tick AutoParam samples");

    // Kit: even a silent drum (no active voices, so shouldRender(128) is false) must still have its
    // ParamManager ticked by Kit.renderOutput (kit.cpp:588-619).
    FirmwareKit kit = new FirmwareKit();
    kit.shouldLimitDelayFeedback = false;
    FirmwareSound silentDrum = kit.drumSounds.get(3);
    silentDrum.currentBpm = 120.0f;
    silentDrum.inputTickMagnitude = 2;
    silentDrum.fw2Sound.arpeggiator.playbackClockActive = true;
    silentDrum.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 0, 0, false);
    silentDrum.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 480_000_000, 24, true);
    silentDrum.paramManager.setPlayPos(0, 96, false);
    silentDrum.paramManager.processCurrentPos(0, 0, 96, false, false, true);

    assertFalse(silentDrum.shouldRender(128));
    kit.renderOutput(out, 128, rev);
    AutoParam drumAuto = silentDrum.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertEquals(
        afterOneBlock,
        drumAuto.currentValue,
        "Silent drum in active kit must advance AutoParam at the same rate as synth");
  }

  @Test
  void kitDrumRowSchedulesTicksTilNextEventAndHonorsIndependentRowLength() {
    ClipModel clip = new ClipModel("kitClip", 1, 4); // 96 ticks
    clip.setType(ClipType.INSTRUMENT);
    FirmwareKit kit = new FirmwareKit();
    clip.setSound(kit);

    // Drum 0 follows clip length (96 ticks) with an automation node at tick 10.
    FirmwareSound drum0 = kit.drumSounds.get(0);
    drum0.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 100_000_000, 0, false);
    drum0.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 500_000_000, 10, false);

    // Drum 1 has an independent NoteRowModel of length 48 ticks with a node at tick 24.
    NoteRowModel row1 = clip.getOrCreateRow(1);
    row1.setLoopLengthIfIndependent(48);
    FirmwareSound drum1 = kit.drumSounds.get(1);
    drum1.paramManager.recordParamValue(Param.LOCAL_PAN, -200_000_000, 0, false);
    drum1.paramManager.recordParamValue(Param.LOCAL_PAN, 200_000_000, 24, false);

    clip.setPos(0);
    clip.processCurrentPos(0);
    assertEquals(
        10,
        clip.getTicksTilNextEvent(),
        "Clip ticksTilNextEvent must fold drum0's automation event at tick 10");

    // Advance to tick 48: drum1's independent 48-tick row wraps back to pos 0 (-200_000_000),
    // while clip playhead is at 48.
    for (int t = 1; t <= 48; t++) {
      clip.incrementPos(1);
      clip.processCurrentPos(1);
    }
    assertEquals(
        -200_000_000,
        drum1.paramManager.getAutomatedParam(Param.LOCAL_PAN).currentValue,
        "Independent 48-tick kit row must wrap its drum ParamManager at tick 48");
    assertEquals(
        500_000_000,
        drum0.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ).currentValue,
        "Clip-synced drum0 must still hold its tick-10 value at tick 48");

    // Ping-pong on clip must flip clip-synced drum0's slope at clip boundary (96), not row1's.
    clip.setSequenceDirectionMode(SequenceDirection.PINGPONG);
    drum0.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, 500_000_000, 10, true);
    clip.setPos(0);
    clip.processCurrentPos(0);
    assertTrue(
        drum0.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ).valueIncrementPerHalfTick > 0);
  }
}
