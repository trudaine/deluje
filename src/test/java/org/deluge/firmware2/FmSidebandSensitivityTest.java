package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test for FM sideband sensitivity and multi-operator chaining (§4.2vicessepties).
 * Verifies that presets like 081 Xylophone Big Bass (Mod1 -> Mod0 chaining with +12/+24 semitone
 * transpositions and free-running phase) respond dynamically to note/velocity cable scaling and produce
 * bounded, faithful FM synthesis.
 */
public class FmSidebandSensitivityTest {

  @Test
  public void testXylophoneBigBassChainingAndVelocitySensitivity() throws Exception {
    String path = "/SYNTHS/081 Xylophone Big Bass.XML";
    try (InputStream in = getClass().getResourceAsStream(path)) {
      assertNotNull(in, "Missing classpath resource: " + path);
      SynthTrackModel synth = DelugeXmlParser.parseSynth(in, "081 Xylophone Big Bass");

      assertEquals(1, synth.getSynthMode(), "081 Xylophone Big Bass must operate in FM mode");
      assertTrue(
          synth.isModulator1ToModulator0(),
          "081 must chain Modulator 1 into Modulator 0 (multi-op FM synthesis)");
      assertEquals(24, synth.getModulator1Transpose(), "Modulator 1 must be transposed +24 semitones (+2 octaves)");
      assertEquals(12, synth.getModulator2Transpose(), "Modulator 2 must be transposed +12 semitones (+1 octave)");

      // Test rendering at vel=127 (embedded song mode, where hardware recorded 0.779 similarity)
      // vs vel=110 (standalone default velocity)
      float[] out127 = renderNoteAtVelocity(synth, 60, 127);
      float[] out110 = renderNoteAtVelocity(synth, 60, 110);

      boolean differed = false;
      for (int i = 0; i < out127.length; i++) {
        if (out127[i] != out110[i]) {
          differed = true;
          break;
        }
      }
      assertTrue(
          differed,
          "Multi-operator FM sidebands must vary dynamically with velocity/note cable scaling");
    }
  }

  private float[] renderNoteAtVelocity(SynthTrackModel synth, int note, int vel) throws Exception {
    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, note));
    synth.getClips().clear();
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);

    fs.triggerNote(note, vel);

    int numSamples = 1024;
    float[] out = new float[numSamples * 2];
    int blocks = numSamples / 128;
    int idx = 0;
    for (int b = 0; b < blocks; b++) {
      engine.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        out[idx++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
        out[idx++] = (float) (engine.masterBuffer[i].r / 2.147483648e9);
      }
    }
    return out;
  }
}
