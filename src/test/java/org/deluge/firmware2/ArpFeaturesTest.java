package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Behavior tests for the fw2 Synth arpeggiator features that the deleted legacy-port arp tests used
 * to cover: step repeats (arpeggiator.cpp:733-760), randomizer spreads/swap
 * (arpeggiator.cpp:822-905), and MPE-modulated velocity (arpeggiator.cpp:1319-1335).
 *
 * <p>Render harness mirrors Sound.render (Sound.java:421-426): non-synced render with gatePos
 * advancing by (phaseIncrement >> 8) * numSamples per call (arpeggiator.cpp:1507-1511).
 */
class ArpFeaturesTest {

  private static Arpeggiator.Settings baseSettings() {
    Arpeggiator.Settings settings = new Arpeggiator.Settings();
    settings.mode = Arpeggiator.ArpMode.ARP;
    settings.noteMode = Arpeggiator.ArpNoteMode.UP;
    settings.octaveMode = Arpeggiator.ArpOctaveMode.UP;
    settings.numOctaves = 1;
    settings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_NONE;
    return settings;
  }

  /** Renders until the arp emits a note-on; returns the emitted ArpNote. */
  private static Arpeggiator.ArpNote renderUntilNextNoteOn(
      Arpeggiator.Synth arp,
      Arpeggiator.Settings settings,
      Arpeggiator.ArpReturnInstruction instr) {
    for (int i = 0; i < 2000; i++) {
      instr.arpNoteOn = null;
      // gateThreshold for gate=0 as Sound.render computes it: (0 + 2^31) >> 8 = 2^23.
      arp.render(settings, instr, 16, 1 << 23, 1 << 24);
      if (instr.arpNoteOn != null) {
        // Acknowledge the note-on the way Sound.render does (Sound.java:435-441) — without
        // flipping PENDING → PLAYING, handlePendingNotes re-emits the same note every render.
        instr.arpNoteOn.noteStatus[0] = Arpeggiator.ArpNoteStatus.PLAYING;
        return instr.arpNoteOn;
      }
    }
    fail("Arpeggiator did not emit a note-on within 2000 render blocks");
    return null;
  }

  @Test
  void stepRepeatsPlayEachNoteThreeTimes() {
    Arpeggiator.Settings settings = baseSettings();
    settings.numStepRepeats = 3;

    Arpeggiator.Synth arp = new Arpeggiator.Synth();
    Arpeggiator.ArpReturnInstruction instr = new Arpeggiator.ArpReturnInstruction();
    arp.noteOn(settings, 60, 100, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);
    arp.noteOn(settings, 64, 100, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);

    assertEquals(60, renderUntilNextNoteOn(arp, settings, instr).noteCodeOnPostArp[0]);
    assertEquals(60, renderUntilNextNoteOn(arp, settings, instr).noteCodeOnPostArp[0]);
    assertEquals(60, renderUntilNextNoteOn(arp, settings, instr).noteCodeOnPostArp[0]);
    assertEquals(64, renderUntilNextNoteOn(arp, settings, instr).noteCodeOnPostArp[0]);
  }

  @Test
  void spreadsAndSwapProbabilityStayInRange() {
    Arpeggiator.Settings settings = baseSettings();
    settings.spreadVelocity = 20;
    settings.spreadGate = 100000;
    settings.spreadOctave = 2;
    settings.swapProbability = 2147483647; // ~50% of the raw uint32 range

    Arpeggiator.Synth arp = new Arpeggiator.Synth();
    Arpeggiator.ArpReturnInstruction instr = new Arpeggiator.ArpReturnInstruction();
    arp.noteOn(settings, 60, 100, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);
    arp.noteOn(settings, 64, 100, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);

    for (int step = 0; step < 8; step++) {
      Arpeggiator.ArpNote note = renderUntilNextNoteOn(arp, settings, instr);
      assertTrue(note.noteCodeOnPostArp[0] >= 0 && note.noteCodeOnPostArp[0] <= 127);
      assertTrue(
          note.velocity >= 1 && note.velocity <= 127,
          "spread velocity out of range: " + note.velocity);
    }
  }

  @Test
  void mpeAftertouchModulatesVelocity() {
    Arpeggiator.Settings settings = baseSettings();
    settings.mpeVelocity = Arpeggiator.ArpMpeModSource.AFTERTOUCH;

    Arpeggiator.Synth arp = new Arpeggiator.Synth();
    Arpeggiator.ArpReturnInstruction instr = new Arpeggiator.ArpReturnInstruction();
    // mpeValues = {X, Y, pressure}; velocity = mpeValues[2] >> 8 (arpeggiator.cpp:1323-1327).
    int[] mpeValues = {0, 0, 88 << 8};
    arp.noteOn(settings, 60, 100, instr, 3, mpeValues);

    assertEquals(88, renderUntilNextNoteOn(arp, settings, instr).velocity);
  }
}
