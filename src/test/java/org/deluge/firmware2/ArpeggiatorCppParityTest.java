package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Arpeggiator.ArpMode;
import org.deluge.firmware2.Arpeggiator.ArpNoteStatus;
import org.deluge.firmware2.Arpeggiator.ArpReturnInstruction;
import org.deluge.firmware2.Arpeggiator.Settings;
import org.deluge.firmware2.Arpeggiator.SyncLevel;
import org.deluge.firmware2.Arpeggiator.SyncType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies C++ firmware parity for {@link ArpeggiatorBase}, {@link ArpeggiatorSynth}, {@link
 * ArpeggiatorForDrum}, and {@link Arpeggiator.Settings} against {@code
 * ../DelugeFirmware/src/deluge/modulation/arpeggiator.cpp}.
 */
class ArpeggiatorCppParityTest {

  @BeforeEach
  void resetPrng() {
    Functions.resetNoiseSeed();
  }

  private static int expectedTriangleSample(int r1, int r2) {
    return ((r1 & 0xFF) - (r2 & 0xFF)) << 23;
  }

  @Test
  void randomWeighted2BitsAndBipolarProbabilityUseUnsignedShiftAndQ31Mult() {
    ArpeggiatorSynth arp = new ArpeggiatorSynth();

    // C arpeggiator.cpp:914-919: getRandomWeighted2BitsAmount(uint32_t value) uses value >> 1
    // (unsigned) and q31_mult (multiply_32x32_rshift32(...) << 1).
    Functions.resetNoiseSeed();
    int r1 = Arpeggiator.getRandom255();
    int r2 = Arpeggiator.getRandom255();
    int tri = expectedTriangleSample(r1, r2);
    int fullScaleValue = 0xFFFFFFFF;
    int expectedQ31 = Functions.multiply_32x32_rshift32(tri, fullScaleValue >>> 1) << 1;
    int expectedWeighted = Math.abs(Functions.multiply_32x32_rshift32(expectedQ31, 5));

    Functions.resetNoiseSeed();
    assertEquals(expectedWeighted, arp.getRandomWeighted2BitsAmount(fullScaleValue));

    // Across 64 random draws at full amount (0xFFFFFFFF) and 75% amount (0xC0000000), weighted
    // 2-bit amounts must reach non-zero ratchet/chord/octave-spread steps.
    int nonZeroFull = 0;
    int nonZeroThreeQuarter = 0;
    for (int i = 0; i < 64; i++) {
      int wFull = arp.getRandomWeighted2BitsAmount(0xFFFFFFFF);
      int wThreeQuarter = arp.getRandomWeighted2BitsAmount(0xC0000000);
      assertTrue(wFull >= 0 && wFull <= 4, "weighted amount out of range: " + wFull);
      assertTrue(
          wThreeQuarter >= 0 && wThreeQuarter <= 4,
          "weighted amount out of range: " + wThreeQuarter);
      if (wFull > 0) nonZeroFull++;
      if (wThreeQuarter > 0) nonZeroThreeQuarter++;
    }
    assertTrue(nonZeroFull > 0, "full-scale weighted amount must produce non-zero steps");
    assertTrue(nonZeroThreeQuarter > 0, "75% unsigned weighted amount must produce non-zero steps");

    // C arpeggiator.cpp:901-913: getRandomBipolarProbabilityAmount(uint32_t value).
    Functions.resetNoiseSeed();
    int b1 = Arpeggiator.getRandom255();
    int b2 = Arpeggiator.getRandom255();
    int triBipolar = expectedTriangleSample(b1, b2);
    int expectedBipolarQ31 = Functions.multiply_32x32_rshift32(triBipolar, 0x80000000 >>> 1) << 1;
    int expectedBipolar =
        Math.max(-127, Math.min(127, Functions.multiply_32x32_rshift32(expectedBipolarQ31, 255)));

    Functions.resetNoiseSeed();
    assertEquals((byte) expectedBipolar, arp.getRandomBipolarProbabilityAmount(0x80000000));
  }

  @Test
  void synthNoteOffMonoSnapBackChecksMostRecentlyPlayedNoteBeforeRemove() {
    Settings settings = new Settings();
    settings.mode = ArpMode.OFF;

    ArpeggiatorSynth arp = new ArpeggiatorSynth();
    ArpReturnInstruction instr = new ArpReturnInstruction();

    arp.noteOn(settings, 60, 100, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);
    arp.noteOn(settings, 64, 100, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);
    arp.noteOn(settings, 67, 100, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);

    // Releasing the middle note (64, not the last-played 67) must NOT trigger mono snap-back.
    instr.reset();
    arp.noteOff(settings, 64, instr);
    assertNull(
        instr.arpNoteOn,
        "releasing a middle held note must not snap back when a newer note is still held");

    // Releasing the most recently played note (67) must snap back to the previous held note (60).
    instr.reset();
    arp.noteOff(settings, 67, instr);
    assertNotNull(instr.arpNoteOn, "releasing the latest held note must snap back in arp-off mode");
    assertEquals(60, instr.arpNoteOn.noteCodeOnPostArp[0]);
    for (int n = 1; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
      assertEquals(Arpeggiator.ARP_NOTE_NONE, instr.arpNoteOn.noteCodeOnPostArp[n]);
      assertEquals(ArpNoteStatus.OFF, instr.arpNoteOn.noteStatus[n]);
    }
  }

  @Test
  void resetClearsPendingAndPostArpArraysOnSynthAndDrum() {
    Settings settings = new Settings();
    settings.mode = ArpMode.ARP;
    settings.syncLevel = SyncLevel.SYNC_LEVEL_NONE;

    ArpeggiatorSynth synthArp = new ArpeggiatorSynth();
    ArpReturnInstruction instr = new ArpReturnInstruction();
    synthArp.noteOn(settings, 60, 100, instr, 2, null);

    assertTrue(synthArp.anyPending);
    assertTrue(synthArp.active_note.isPending());
    assertTrue(synthArp.active_note.velocity > 0);

    // C arpeggiator.cpp:122-129
    synthArp.reset();
    assertFalse(synthArp.anyPending, "Synth reset() must clear anyPending");
    assertFalse(synthArp.active_note.isPending(), "Synth reset() must clear active_note status");
    assertEquals(0, synthArp.active_note.velocity, "Synth reset() must zero active_note.velocity");
    for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
      assertEquals(Arpeggiator.ARP_NOTE_NONE, synthArp.active_note.noteCodeOnPostArp[n]);
      assertEquals(Arpeggiator.MIDI_CHANNEL_NONE, synthArp.active_note.outputMemberChannel[n]);
      assertEquals(ArpNoteStatus.OFF, synthArp.active_note.noteStatus[n]);
    }

    ArpeggiatorForDrum drumArp = new ArpeggiatorForDrum();
    instr.reset();
    drumArp.noteOn(settings, 36, 110, instr, 1, null);
    assertTrue(drumArp.active_note.isPending());
    assertTrue(drumArp.active_note.velocity > 0);

    // C arpeggiator.cpp:132-135
    drumArp.reset();
    assertFalse(drumArp.active_note.isPending(), "Drum reset() must clear active_note status");
    assertEquals(0, drumArp.active_note.velocity, "Drum reset() must zero active_note.velocity");
    for (int n = 0; n < Arpeggiator.ARP_MAX_INSTRUCTION_NOTES; n++) {
      assertEquals(Arpeggiator.ARP_NOTE_NONE, drumArp.active_note.noteCodeOnPostArp[n]);
      assertEquals(Arpeggiator.MIDI_CHANNEL_NONE, drumArp.active_note.outputMemberChannel[n]);
      assertEquals(ArpNoteStatus.OFF, drumArp.active_note.noteStatus[n]);
    }
  }

  @Test
  void handlePendingNotesTreatsNullSettingsAsArpOff() {
    Settings settings = new Settings();
    settings.mode = ArpMode.OFF;

    ArpeggiatorSynth arp = new ArpeggiatorSynth();
    ArpReturnInstruction instr = new ArpReturnInstruction();
    arp.noteOn(settings, 62, 95, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);

    // C arpeggiator.cpp:487, arpeggiator.h:247-249 — arpIsOff(nullptr) is true, so pending notes
    // in `notes` must be found even when settings is null.
    instr.reset();
    assertTrue(arp.handlePendingNotes(null, instr));
    assertNotNull(instr.arpNoteOn);
    assertEquals(62, instr.arpNoteOn.noteCodeOnPostArp[0]);
  }

  @Test
  void renderAndDoTickForwardUseUnsignedPhaseIncrementGatePosAndClipPos() {
    Settings settings = new Settings();
    settings.mode = ArpMode.ARP;
    settings.syncLevel = SyncLevel.SYNC_LEVEL_16TH;
    settings.syncType = SyncType.EVEN;

    ArpeggiatorSynth arp = new ArpeggiatorSynth();
    arp.playbackClockActive = true;
    ArpReturnInstruction instr = new ArpReturnInstruction();

    arp.noteOn(settings, 60, 100, instr, Arpeggiator.MIDI_CHANNEL_NONE, null);
    // Start first synced note at clipCurrentPos == 0.
    arp.doTickForward(settings, instr, 0, false);
    if (instr.arpNoteOn != null) {
      instr.arpNoteOn.noteStatus[0] = ArpNoteStatus.PLAYING;
    }

    // C arpeggiator.cpp:1544 — phaseIncrement is uint32_t, so >> 8 is unsigned >>> 8.
    int largeUnsignedPhaseInc = 0x80000100;
    instr.reset();
    arp.render(settings, instr, 1, 0xFFFFFFFF, largeUnsignedPhaseInc);
    assertEquals(
        largeUnsignedPhaseInc >>> 8,
        arp.gatePos,
        "gatePos must advance by unsigned (phaseIncrement >>> 8) * numSamples");

    // C arpeggiator.cpp:1525 — gatePos is uint32_t; values >= 0x80000000 must still trigger gate
    // off instead of comparing as negative signed ints.
    arp.gatePos = 0x80000000;
    arp.gateCurrentlyActive = true;
    arp.active_note.noteCodeOnPostArp[0] = 60;
    arp.active_note.noteStatus[0] = ArpNoteStatus.PLAYING;
    instr.reset();
    arp.render(settings, instr, 0, 1 << 23, 0);
    assertFalse(arp.gateCurrentlyActive, "unsigned gatePos >= 0x80000000 must cross gateThreshold");
    assertEquals(60, instr.noteCodeOffPostArp[0]);

    // C arpeggiator.cpp:1550, 1572 — clipCurrentPos is uint32_t, so % ticksPerPeriod is unsigned.
    int unsignedClipPos = 0x80000005;
    int ticksPerPeriod = 3 << (9 - settings.syncLevel.ordinal()); // 48
    int expectedRemainder = Integer.remainderUnsigned(unsignedClipPos, ticksPerPeriod);
    int expectedTicksTilNext = ticksPerPeriod - expectedRemainder;

    instr.reset();
    int actualTicksTilNext = arp.doTickForward(settings, instr, unsignedClipPos, false);
    assertEquals(expectedTicksTilNext, actualTicksTilNext);

    // C arpeggiator.cpp:1592-1613 — Settings.getPhaseIncrement uses uint32_t shifts and divisions.
    settings.syncType = SyncType.TRIPLET;
    int unsignedTickInverse = 0x80000000;
    int shifted = unsignedTickInverse >>> (9 - settings.syncLevel.ordinal());
    assertEquals(
        Integer.divideUnsigned(shifted * 3, 2), settings.getPhaseIncrement(0, unsignedTickInverse));
  }
}
