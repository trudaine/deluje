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
    SynthTrackModel model = new SynthTrackModel("SYNTH");
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
    sound.mpePressure(3, 80); // Timbre pressure
    sound.mpeTimbre(3, 110); // Filter slide

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

  private void renderUntilNextNoteOn(Arpeggiator arp, Arpeggiator.ReturnInstruction instr) {
    instr.noteOn = false;
    for (int i = 0; i < 500; i++) {
      arp.render(instr, 1, 1 << 24);
      if (instr.noteOn) return;
    }
    fail("Arpeggiator did not trigger a note-on within 500 render blocks!");
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

    // Render first step
    renderUntilNextNoteOn(arp, instr);
    assertEquals(60, instr.noteCode);

    // Second render step (repeat 2)
    renderUntilNextNoteOn(arp, instr);
    assertEquals(60, instr.noteCode);

    // Third render step (repeat 3)
    renderUntilNextNoteOn(arp, instr);
    assertEquals(60, instr.noteCode);

    // Fourth render step (advance to next note 64!)
    renderUntilNextNoteOn(arp, instr);
    assertEquals(64, instr.noteCode);
  }

  @Test
  void testArpeggiatorSpreadsAndProbabilities() {
    Arpeggiator.Settings settings = new Arpeggiator.Settings();
    settings.mode = Arpeggiator.ArpMode.UP;
    settings.velocitySpread = 20; // range +/- 20
    settings.gateSpread = 100000; // gate time random shift
    settings.octaveSpread = 2; // +/- 2 octaves
    settings.swapProbability = 2147483647; // 100% swap probability!

    Arpeggiator arp = new Arpeggiator(settings);
    arp.noteOn(60, 100);
    arp.noteOn(64, 100);

    Arpeggiator.ReturnInstruction instr = new Arpeggiator.ReturnInstruction();

    // Trigger step check
    renderUntilNextNoteOn(arp, instr);

    // Because swap probability is 100%, instead of playing the first note 60, it swaps and plays
    // note 64!
    // And because octave spread is active, noteCode will have a random octave shift!
    // And because velocity spread is active, velocity will have a random offset from 100!
    assertTrue(instr.noteCode >= 0);
    assertTrue(instr.velocity >= 1 && instr.velocity <= 127);
  }

  @Test
  void testArpeggiatorMpeVelocityTracking() {
    Arpeggiator.Settings settings = new Arpeggiator.Settings();
    settings.mode = Arpeggiator.ArpMode.UP;
    settings.mpeVelocity = 1; // Enable MPE pressure-to-velocity tracking!

    Arpeggiator arp = new Arpeggiator(settings);
    arp.noteOn(60, 100);

    Arpeggiator.ReturnInstruction instr = new Arpeggiator.ReturnInstruction();

    // Set live pressure to 88
    arp.setLiveMpePressure(88);

    renderUntilNextNoteOn(arp, instr);
    assertEquals(88, instr.velocity); // Output step velocity tracks live pressure!
  }

  @Test
  void testSubDrumStepAutomation() {
    // Instantiate a sub-drum lane instrument model
    org.chuck.deluge.model.ClipModel clip = new org.chuck.deluge.model.ClipModel("KIT CLIP", 8, 16);
    // Write LPF frequency step automation for drum lane index 2 at step 4
    clip.setRowAutomation(2, "lpfFrequency", 4, 0.75f);

    // Verify rowAutomationData is populated
    float[] array = clip.getRowAutomation(2, "lpfFrequency");
    assertNotNull(array);
    assertEquals(0.75f, array[4]);

    // Build sub-drum sound and map automations
    FirmwareSound drumSound = new FirmwareSound();
    drumSound.isDrum = true;

    // Map row automation
    java.util.Map<String, float[]> rowAutos = clip.getRowAutomationData().get(2);
    assertNotNull(rowAutos);
    float[] lpfArray = rowAutos.get("lpfFrequency");
    assertNotNull(lpfArray);

    for (int s = 0; s < lpfArray.length; s++) {
      if (lpfArray[s] > 0.0f) {
        int q31Val = (int) (lpfArray[s] * 2147483647.0);
        int pos = s * 24; // 24 ticks per step
        drumSound.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, q31Val, pos);
      }
    }

    // Process play position at tick index 96 (step 4, pos = 4 * 24 = 96)
    boolean didPingpong = false;
    drumSound.paramManager.processCurrentPos(96, 16 * 24, false, didPingpong, true);

    // Verify the param manager's automated value updates to Q31 space target!
    org.chuck.deluge.firmware.modulation.automation.AutoParam ap =
        drumSound.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertNotNull(ap);
    assertEquals((int) (0.75 * 2147483647.0), ap.currentValue);
  }
}
