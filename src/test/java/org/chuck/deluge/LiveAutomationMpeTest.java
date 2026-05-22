package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.engine.FirmwareVoice;
import org.chuck.deluge.firmware.modulation.Arpeggiator;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

public class LiveAutomationMpeTest {

  @Test
  void testVoiceMpeSourcesAndPitchBend() {
    SynthTrackModel model = new SynthTrackModel();
    FirmwareSound sound = new FirmwareSound();

    // Trigger MPE note-on on MIDI channel 3
    sound.triggerNote(60, 100, 3);
    assertFalse(sound.voices.isEmpty());

    FirmwareVoice voice = sound.voices.get(0);
    assertEquals(3, voice.midiChannel);
    assertEquals(8192, voice.mpePitchBend);
    assertEquals(0, voice.mpePressure);
    assertEquals(64, voice.mpeTimbre);

    // Apply MPE bend/timbre/pressure updates to channel 3
    sound.mpePitchBend(3, 10000); // Sharp pitch bend
    sound.mpePressure(3, 80);     // Timbre pressure
    sound.mpeTimbre(3, 110);      // Filter slide

    assertEquals(10000, voice.mpePitchBend);
    assertEquals(80, voice.mpePressure);
    assertEquals(110, voice.mpeTimbre);

    // Render 1 block to compute active sources register slot values
    int[] dummyBuffer = new int[128];
    voice.render(dummyBuffer, 128, 100, 200);

    // Verify aftertouch and Y performer sources are updated
    assertEquals(80 * 16909320, voice.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);
    assertEquals(110 * 16909320, voice.sourceValues[PatchSource.Y.ordinal()]);

    // Release note on MPE channel 3
    sound.releaseNote(60, 3);
    // Envelope goes to release stage, voice remains allocated but key state is off
  }

  @Test
  void testArpeggiatorStepRepeats() {
    Arpeggiator.Settings settings = new Arpeggiator.Settings();
    settings.mode = Arpeggiator.ArpMode.UP;
    settings.numStepRepeats = 3; // Repeat each note 3 times!

    Arpeggiator arp = new Arpeggiator(settings);
    arp.noteOn(60, 100);
    arp.noteOn(64, 100);

    Arpeggiator.ReturnInstruction instr = new Arpeggiator.ReturnInstruction();

    // Force step triggers and check repetitions
    // Render first step
    arp.render(instr, 1, 1 << 24); // will advance step
    assertEquals(60, instr.noteCode);

    // Second render step (repeat 2)
    instr.noteOn = false;
    arp.render(instr, 1, 1 << 24);
    assertEquals(60, instr.noteCode);

    // Third render step (repeat 3)
    instr.noteOn = false;
    arp.render(instr, 1, 1 << 24);
    assertEquals(60, instr.noteCode);

    // Fourth render step (advance to next note 64!)
    instr.noteOn = false;
    arp.render(instr, 1, 1 << 24);
    assertEquals(64, instr.noteCode);
  }
}
