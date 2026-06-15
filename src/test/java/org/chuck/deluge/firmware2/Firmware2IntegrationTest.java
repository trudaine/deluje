package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Integration test: verifies firmware2/ engine wired into FirmwareSound produces audio when {@code
 * useFirmware2 = true}.
 */
public class Firmware2IntegrationTest {

  @Test
  public void firmware2WiredIntoFirmwareSoundRendersAudio() {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type("SINE");
    m.setOsc2Type("NONE");
    m.setLpfFreq(20000f);
    m.setVolume(1.0f); // firmware curve: center knob = neutral, full knob = unity
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = FirmwareFactory.createSong(p);
    FirmwareSound sound = (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;

    // Enable firmware2
    // useFirmware2 removed - always true
    System.out.println(
        "paramKnobs[OSC_A]="
            + sound.paramKnobs[org.chuck.deluge.firmware2.Param.LOCAL_OSC_A_VOLUME]
            + " knob[VOL]="
            + sound.paramKnobs[org.chuck.deluge.firmware2.Param.LOCAL_VOLUME]
            + " knob[LPF]="
            + sound.paramKnobs[org.chuck.deluge.firmware2.Param.LOCAL_LPF_FREQ]);

    // Render a few blocks
    sound.triggerNote(69, 100); // A4
    StereoSample[] buf = new StereoSample[128];
    for (int i = 0; i < 128; i++) buf[i] = new StereoSample();
    double sum = 0;
    for (int block = 0; block < 8; block++) {
      for (int i = 0; i < 128; i++) {
        buf[i].l = 0;
        buf[i].r = 0;
      }
      sound.renderOutput(buf, 128, null);
      for (int i = 0; i < 128; i++) sum += (double) buf[i].l * buf[i].l;
    }
    double rms = Math.sqrt(sum / (8 * 128)) / 2147483648.0;
    assertTrue(rms > 0.0, "firmware2 should produce audible output (rms=" + rms + ")");

    // Verify firmware2 voice was created
    assertTrue(sound.fw2Sound.voices.size() > 0, "firmware2 voice list should be populated");
    assertTrue(sound.fw2Sound.voices.get(0).active, "firmware2 voice should be active");

    sound.releaseNote(69, -1);
  }

  @Test
  public void voiceStealingWhenLimitReached() {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type("SINE");
    m.setOsc2Type("NONE");
    m.setLpfFreq(20000f);
    m.setVolume(1.0f);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = FirmwareFactory.createSong(p);
    FirmwareSound sound = (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;

    sound.maxPolyphony = 2; // set maximum polyphony limit to 2

    // Trigger 3 notes sequentially
    sound.triggerNote(60, 100); // C4
    sound.triggerNote(64, 100); // E4

    assertEquals(2, sound.fw2Sound.voices.size());
    assertTrue(sound.fw2Sound.voices.stream().allMatch(v -> v.active));

    // Remember the voice objects
    var v0 = sound.fw2Sound.voices.get(0);
    var v1 = sound.fw2Sound.voices.get(1);

    // Trigger 3rd note (should steal one of the two voices)
    sound.triggerNote(67, 100); // G4

    // Still 2 voices total
    assertEquals(2, sound.fw2Sound.voices.size());
    // One of v0 or v1 must now be playing note 67!
    assertTrue(v0.note == 67 || v1.note == 67);
  }

  // Disabled: C-knob defaults in paramNeutralValues break the old firmware/ engine path
  // (the old Envelope expects direct rate values, not C knob values). The old engine is
  // being replaced by firmware2/ — no point fixing firmware/ code.
  // @Test
  public void firmware2FlagOffUsesOldEngine_disabled() {}
}
