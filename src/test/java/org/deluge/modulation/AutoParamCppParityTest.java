package org.deluge.modulation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Functions;
import org.deluge.firmware2.Param;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.params.ParamManager;
import org.deluge.modulation.patch.PatchCable;
import org.junit.jupiter.api.Test;

/**
 * C++ parity tests for {@link AutoParam}, {@link ParamManager}, and {@link PatchCable} against
 * {@code auto_param.cpp}, {@code param_manager.cpp}, and {@code patch_cable.h}.
 */
class AutoParamCppParityTest {

  @Test
  void addNodeUpdatesExistingPositionInPlace() {
    // C auto_param.cpp:1070-1095 (AutoParam::setNodeAtPos) — writing at an existing pos updates the
    // node in place instead of appending a duplicate node.
    ParamManager pm = new ParamManager();
    pm.recordParamValue(Param.LOCAL_LPF_FREQ, 100_000_000, 24);
    pm.recordParamValue(Param.LOCAL_LPF_FREQ, 700_000_000, 24, true);

    AutoParam ap = pm.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertEquals(1, ap.nodes.size(), "Re-recording at same pos must not duplicate nodes");
    assertEquals(700_000_000, ap.nodes.get(0).value);
    assertTrue(ap.nodes.get(0).interpolated);

    int dist = ap.processCurrentPos(24, 96, false, false, true);
    assertEquals(700_000_000, ap.currentValue);
    assertEquals(96, dist);
  }

  @Test
  void processCurrentPosHandlesInfiniteArrangementLoopLength() {
    // C song.cpp:5822 — Song::getLoopLength() returns 2147483647; Arrangement passes loopLength=0.
    // Past nodes must wrap by Integer.MAX_VALUE rather than yielding negative distances.
    AutoParam ap = new AutoParam(Param.LOCAL_LPF_FREQ);
    ap.addNode(0, 100_000_000);
    ap.addNode(96, 500_000_000);

    int distAt24 = ap.processCurrentPos(24, 0, false, false, true);
    assertEquals(72, distAt24, "At pos 24 with loopLength=0, next node is at pos 96 (72 ticks)");

    int distAt120 = ap.processCurrentPos(120, 0, false, false, true);
    assertTrue(
        distAt120 > 1_000_000_000,
        "Past all arrangement nodes, distance must stay positive near Integer.MAX_VALUE");
  }

  @Test
  void reversePlaybackStepsToLeftNodeValueAndUsesReachedNodeInterpolationFlag() {
    // C auto_param.cpp:439, 466-476 — crossing a non-interpolated node in reverse steps to
    // nodeToLeft.value.
    AutoParam stepped = new AutoParam(Param.LOCAL_LPF_FREQ);
    stepped.addNode(0, 100_000_000, false);
    stepped.addNode(24, 500_000_000, false);

    stepped.processCurrentPos(24, 96, true, false, true);
    assertEquals(
        100_000_000,
        stepped.currentValue,
        "Crossing stepped node at pos 24 in reverse must jump to nodeToLeft.value (pos 0)");
    assertEquals(0, stepped.valueIncrementPerHalfTick);

    // C auto_param.cpp:747 — segment [0, 24] stores its interpolated flag on the right endpoint
    // (pos 24). Entering [0, 24] in reverse from pos 24 must check node(pos=24).interpolated.
    AutoParam slopedLeft = new AutoParam(Param.LOCAL_LPF_FREQ);
    slopedLeft.addNode(0, 100_000_000, false);
    slopedLeft.addNode(24, 580_000_000, true);

    slopedLeft.processCurrentPos(24, 96, true, false, true);
    assertEquals(
        580_000_000,
        slopedLeft.currentValue,
        "Entering interpolated segment at pos 24 in reverse starts at nodeJustReached.value");
    assertTrue(
        slopedLeft.valueIncrementPerHalfTick < 0,
        "Entering interpolated segment [0, 24] in reverse must set negative increment");
  }

  @Test
  void interpolationIncrementAndTickSamplesMatchCppFormulaWithoutOverflow() {
    // C auto_param.cpp:817, 865 — halfDistance = (next.value >> 1) - (currentValue >> 1);
    // valueIncrementPerHalfTick = halfDistance / ticksTilNextNode.
    // A 3e9 span (-1.5e9 to +1.5e9) overflows signed 32-bit subtraction if not shifted first.
    AutoParam ap = new AutoParam(Param.LOCAL_PAN);
    ap.addNode(0, -1_500_000_000, false);
    ap.addNode(24, 1_500_000_000, true);

    ap.processCurrentPos(0, 96, false, false, true);
    assertEquals(-1_500_000_000, ap.currentValue);
    int expectedHalfDist = (1_500_000_000 >> 1) - (-1_500_000_000 >> 1);
    assertEquals(expectedHalfDist / 24, ap.valueIncrementPerHalfTick);

    // C auto_param.cpp:974-995 — tickTicks(12) advances by 24 half-ticks.
    assertTrue(ap.tickTicks(12));
    assertEquals(0, ap.currentValue);

    // C auto_param.cpp:948-960 — tickSamples scales by timePerTimerTickInverse and 6 * numSamples.
    int timePerTickInverse = 1 << 23; // ~459 samples per timer tick at 120 BPM
    int before = ap.currentValue;
    ap.tickSamples(128, timePerTickInverse);
    int expectedStep =
        Functions.multiply_32x32_rshift32_rounded(ap.valueIncrementPerHalfTick, timePerTickInverse);
    int expectedDelta = (int) ((long) expectedStep * 6L * 128L);
    assertEquals(before + expectedDelta, ap.currentValue);

    // Saturation at Integer.MAX_VALUE resets valueIncrementPerHalfTick to 0
    // (auto_param.cpp:928-934).
    ap.tickTicks(100);
    assertEquals(Integer.MAX_VALUE, ap.currentValue);
    assertEquals(0, ap.valueIncrementPerHalfTick);
  }

  @Test
  void getValueAtInterpolatesAndFallsBackToCurrentValue() {
    AutoParam unautomated = new AutoParam(Param.LOCAL_LPF_FREQ);
    unautomated.currentValue = 123_456_789;
    assertEquals(123_456_789, unautomated.getValueAt(10));

    AutoParam sloped = new AutoParam(Param.LOCAL_LPF_FREQ);
    sloped.addNode(0, 0, false);
    sloped.addNode(24, 240_000_000, true);
    assertEquals(120_000_000, sloped.getValueAt(12, 96, false));
  }

  @Test
  void paramManagerPingpongResetsTicksTilNextEventAndPatchCableReadsAutomatedAmount() {
    ParamManager pm = new ParamManager();
    pm.recordParamValue(Param.LOCAL_LPF_FREQ, 0, 0, false);
    pm.recordParamValue(Param.LOCAL_LPF_FREQ, 480_000_000, 24, true);
    pm.processCurrentPos(0, 96, false, false, true);

    AutoParam ap = pm.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    int incBefore = ap.valueIncrementPerHalfTick;
    assertTrue(incBefore > 0);
    assertEquals(24, pm.ticksTilNextEvent);

    // C param_manager.cpp:597-606 — ping-pong flips increment sign and zeroes ticksTilNextEvent.
    pm.notifyPingpongOccurred();
    assertEquals(-incBefore, ap.valueIncrementPerHalfTick);
    assertEquals(0, pm.ticksTilNextEvent);

    // C patch_cable.h:72 — PatchCable reads automated AutoParam value when automated.
    PatchCable cable = new PatchCable();
    cable.amount = 100_000_000;
    assertEquals(100_000_000, cable.getAmount());
    cable.param.addNode(0, 300_000_000);
    cable.param.processCurrentPos(0, 96, false, false, false);
    assertEquals(300_000_000, cable.getAmount());
  }
}
