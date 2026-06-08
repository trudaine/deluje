package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
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
    sound.useFirmware2 = true;
    System.out.println(
        "paramKnobs[OSC_A]="
            + sound.paramKnobs[org.chuck.deluge.firmware.modulation.params.Param.LOCAL_OSC_A_VOLUME]
            + " knob[VOL]="
            + sound.paramKnobs[org.chuck.deluge.firmware.modulation.params.Param.LOCAL_VOLUME]
            + " knob[LPF]="
            + sound.paramKnobs[org.chuck.deluge.firmware.modulation.params.Param.LOCAL_LPF_FREQ]);

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
    assertTrue(rms > 0.0001, "firmware2 should produce audible output (rms=" + rms + ")");

    // Verify firmware2 voice was created
    assertTrue(sound.fw2Voices.size() > 0, "firmware2 voice list should be populated");
    assertTrue(sound.fw2Voices.get(0).active, "firmware2 voice should be active");

    sound.releaseNote(69, -1);
  }

  @Test
  public void firmware2FlagOffUsesOldEngine() {
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

    // firmware2 OFF — exercise the legacy engine path (useFirmware2 now defaults on, so this test,
    // which asserts the old engine is used, must opt out explicitly — mirroring the flag-on test).
    sound.useFirmware2 = false;
    sound.triggerNote(69, 100);
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
    assertTrue(rms > 0.001, "old engine should produce audio (rms=" + rms + ")");
    assertTrue(sound.fw2Voices.isEmpty(), "fw2Voices should be empty when flag is off");
    assertTrue(sound.voices.size() > 0, "old voices list should be populated");

    sound.releaseNote(69, -1);
  }
}
