package org.deluge.midi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** C midi_takeover.cpp:58-61, 94-160 — SCALE mode works in knob positions -64..+64. */
class MidiTakeoverKnobSpaceTest {

  @Test
  void cc127IsKnobMaximum() {
    assertEquals(64, MidiTakeover.ccToKnobPos(127));
    assertEquals(62, MidiTakeover.ccToKnobPos(126));
    assertEquals(-64, MidiTakeover.ccToKnobPos(0));
    assertEquals(127, MidiTakeover.knobPosToCc(64));
    assertEquals(0, MidiTakeover.knobPosToCc(-64));
  }

  @Test
  void reachingCc127WithoutPickupLeavesTheKnobAlone() {
    // C: prev 126 → knob 62, new 127 → knob 64; midiKnobMaxPosDelta = 0, so the scale branch
    // does nothing (:133) and the Deluge knob (CC 10 → -54) stays put.
    MidiTakeover t = new MidiTakeover();
    t.setMode(MidiTakeover.Mode.SCALE);
    t.process(7, 126, 10); // first turn seeds previous
    assertEquals(-1, t.process(7, 127, 10));
  }

  @Test
  void scalesTowardsTheRunwayInKnobSpace() {
    // C: prev CC 20 (knob -44) → CC 40 (knob -24), Deluge CC 80 (knob 16):
    // 16 + (64 - 16) * (20 / 88) = 26.909 → std::round 27 → CC 91.
    MidiTakeover t = new MidiTakeover();
    t.setMode(MidiTakeover.Mode.SCALE);
    t.process(3, 20, 80);
    assertEquals(91, t.process(3, 40, 80));
  }
}
