package org.deluge.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Guards {@link MidiTrackModel}'s gold-knob MIDI-CC-assignment state (C: {@code
 * MIDIInstrument::modKnobCCAssignments}, midi_instrument.h:113) — the mapping used when a gold knob
 * is pressed on a MIDI track to pick a CC number, and the running 0-127 value sent on each
 * subsequent turn.
 */
public class MidiTrackModelModKnobCcTest {

  @Test
  void defaultsToNoCcAssigned() {
    MidiTrackModel mt = new MidiTrackModel("Test MIDI");
    for (int mode = 0; mode < 8; mode++) {
      for (int knob = 0; knob < 2; knob++) {
        assertEquals(MidiTrackModel.CC_NUMBER_NONE, mt.getModKnobCc(mode, knob));
      }
    }
  }

  @Test
  void setModKnobCcWrapsLikeTheCsMod124() {
    MidiTrackModel mt = new MidiTrackModel("Test MIDI");
    mt.setModKnobCc(0, 0, 123);
    assertEquals(123, mt.getModKnobCc(0, 0));
    mt.setModKnobCc(0, 0, 124); // wraps to 0
    assertEquals(0, mt.getModKnobCc(0, 0));
    mt.setModKnobCc(0, 0, -1); // wraps to 123
    assertEquals(123, mt.getModKnobCc(0, 0));
  }

  @Test
  void modeAndKnobIndexAreIndependent() {
    MidiTrackModel mt = new MidiTrackModel("Test MIDI");
    mt.setModKnobCc(0, 0, 74);
    mt.setModKnobCc(0, 1, 71);
    mt.setModKnobCc(1, 0, 1);
    assertEquals(74, mt.getModKnobCc(0, 0));
    assertEquals(71, mt.getModKnobCc(0, 1));
    assertEquals(1, mt.getModKnobCc(1, 0));
    assertEquals(MidiTrackModel.CC_NUMBER_NONE, mt.getModKnobCc(1, 1));
  }

  @Test
  void ccValueClampsToMidiRange() {
    MidiTrackModel mt = new MidiTrackModel("Test MIDI");
    mt.setModKnobCcValue(0, 0, 200);
    assertEquals(127, mt.getModKnobCcValue(0, 0));
    mt.setModKnobCcValue(0, 0, -50);
    assertEquals(0, mt.getModKnobCcValue(0, 0));
  }
}
