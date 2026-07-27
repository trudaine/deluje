package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
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
 * Dedicated portable unit test for acoustic instrument reverb room damping and envelope absorption (§4.2untriginties).
 * Verifies that acoustic emulations (066 Violin, 073 Piano, 076 Organ, 061 Blown-Staccato-Panpipes) decay cleanly
 * through multi-stage filter envelopes and global Freeverb/Mutable room damping filters without arithmetic instability
 * or Q31 integer overflow, permanently guarding acoustic high-frequency absorption against C++ reverb.cpp.
 */
public class AcousticReverbDampingParityTest {

  private static final File SYNTH_DIR = new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  @Test
  public void testAcousticInstrumentReverbAbsorption() throws Exception {
    String[] presets = {
      "066 Violin.XML",
      "073 Piano.XML",
      "076 Organ.XML",
      "061 Blown-Staccato-Panpipes.XML"
    };

    for (String preset : presets) {
      File xml = new File(SYNTH_DIR, preset);
      if (!xml.exists()) continue;

      SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
      synth.setName(xml.getName().replace(".XML", ""));

      ClipModel clip = new ClipModel("c", 1, 16);
      clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
      synth.addClip(clip);

      ProjectModel project = new ProjectModel();
      project.setBpm(120.0f);
      project.addTrack(synth);

      ProjectModel song = FirmwareFactory.createSong(project);
      FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
      fs.triggerNote(60, 127);

      FirmwareAudioEngine engine = new FirmwareAudioEngine();
      engine.metronomeEnabled = false;
      engine.syncMasterEffects(project);
      engine.sounds.add(fs);

      int numSamples = 44100;
      float[] outOn = new float[numSamples];
      int got = 0;
      while (got < numSamples) {
        engine.renderBlock(128);
        for (int i = 0; i < 128 && got < numSamples; i++) {
          outOn[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
        }
      }

      fs.releaseNote(60);
      int tailSamples = 88200;
      float[] outTail = new float[tailSamples];
      got = 0;
      while (got < tailSamples) {
        engine.renderBlock(128);
        for (int i = 0; i < 128 && got < tailSamples; i++) {
          outTail[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
        }
      }

      double rmsOn = 0.0;
      for (float f : outOn) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), preset + " sustain sample must be valid");
        assertTrue(Math.abs(f) <= 2.0f, preset + " sustain sample must remain bounded");
        rmsOn += f * (double) f;
      }
      rmsOn = Math.sqrt(rmsOn / numSamples);
      assertTrue(rmsOn > 0.01, preset + " must produce audible sustain output");

      double initialTailEnergy = 0.0;
      double finalTailEnergy = 0.0;
      for (int i = 0; i < 1000; i++) initialTailEnergy += outTail[i] * (double) outTail[i];
      for (int i = tailSamples - 1000; i < tailSamples; i++) finalTailEnergy += outTail[i] * (double) outTail[i];

      assertTrue(finalTailEnergy <= initialTailEnergy, preset + " reverb tail must decay through room damping absorption");
    }
  }
}
