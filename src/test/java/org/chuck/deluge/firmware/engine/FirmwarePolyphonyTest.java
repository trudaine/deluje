package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.PolyphonyMode;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Polyphony/voice-allocation behaviour on the supported firmware pure engine: POLY mode allocates a
 * voice per held note, MONO mode reuses a single voice. Replaces coverage from the disabled legacy
 * VoiceCount DSL test.
 */
public class FirmwarePolyphonyTest {

  private static FirmwareSound build(PolyphonyMode mode) {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type("SAW");
    m.setOsc2Type("NONE");
    m.setOscMix(1.0f);
    m.setLpfFreq(20000f);
    m.setVolume(0.3f);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = org.chuck.deluge.firmware.engine.FirmwareFactory.createSong(p);
    FirmwareSound sound = (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;
    sound.polyphonic = mode;
    return sound;
  }

  private static double rms(FirmwareSound s, int n) {
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) block[i] = new StereoSample();
    double acc = 0;
    int cnt = 0;
    for (int off = 0; off < n; off += 128) {
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      s.renderOutput(block, 128, null);
      for (int i = 0; i < 128; i++) {
        double v = block[i].l / 2147483648.0;
        acc += v * v;
        cnt++;
      }
    }
    return Math.sqrt(acc / cnt);
  }

  @Test
  public void polyModeAllocatesAVoicePerNote() {
    FirmwareSound poly = build(PolyphonyMode.POLY);
    poly.triggerNote(60, 110);
    poly.triggerNote(64, 110);
    poly.triggerNote(67, 110); // C major triad
    assertEquals(3, poly.voices.size(), "POLY should allocate one voice per distinct note");
  }

  @Test
  public void monoModeReusesASingleVoice() {
    FirmwareSound mono = build(PolyphonyMode.MONO);
    mono.triggerNote(60, 110);
    mono.triggerNote(64, 110);
    mono.triggerNote(67, 110);
    assertEquals(1, mono.voices.size(), "MONO should reuse a single voice");
  }

  @Test
  public void polyChordIsLouderThanMonoSingleVoice() {
    FirmwareSound poly = build(PolyphonyMode.POLY);
    poly.triggerNote(60, 110);
    poly.triggerNote(64, 110);
    poly.triggerNote(67, 110);
    double polyRms = rms(poly, 11025);

    FirmwareSound mono = build(PolyphonyMode.MONO);
    mono.triggerNote(60, 110);
    mono.triggerNote(64, 110);
    mono.triggerNote(67, 110);
    double monoRms = rms(mono, 11025);

    assertTrue(
        polyRms > monoRms * 1.4,
        "a POLY triad should be louder than a MONO single voice (poly="
            + polyRms
            + " mono="
            + monoRms
            + ")");
  }
}
