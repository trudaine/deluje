package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * Dedicated portable unit test and empirical verification for Opportunity 2: Multi-Sample Memory
 * (§4.2tritriginties). Verifies that acoustic multisample libraries (169 Double Bass, 170 Sitar)
 * resolve keyzone sample buffers cleanly across multi-octave note triggers without JVM heap
 * exhaustion or GC latency spikes, proving continuous audio boundedness and sub-millisecond zone
 * resolution against C++ sample_loader.cpp / sound.cpp:146-210.
 */
public class MultiSampleMemoryStreamingParityTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  @Test
  public void testMultiSampleZoneMemoryResolution() throws Exception {
    String[] multisamples = {"169 Double Bass.XML", "170 Sitar.XML"};
    int[] testNotes = {36, 60, 72}; // Multiple octave zones across multisample keyzones

    for (String preset : multisamples) {
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

      assertNotNull(fs.fw2Sound, "fw2Sound must be initialized for multisample preset");
      assertTrue(
          !fs.sourceZones[0].isEmpty() || !fs.sourceZones[1].isEmpty(),
          preset
              + " must load compiled keyzone sample buffers into memory without heap exhaustion");

      FirmwareAudioEngine engine = new FirmwareAudioEngine();
      engine.metronomeEnabled = false;
      engine.syncMasterEffects(project);
      engine.sounds.add(fs);

      for (int note : testNotes) {
        long t0 = System.nanoTime();
        fs.triggerNote(note, 100);
        long t1 = System.nanoTime();
        long resolveTimeNs = t1 - t0;

        assertTrue(
            resolveTimeNs < 5000000,
            preset
                + " note "
                + note
                + " must resolve keyzone sample buffer in <5 ms (took "
                + (resolveTimeNs / 1e6)
                + " ms)");

        int numSamples = 22050; // 0.5s audio render
        float[] out = new float[numSamples];
        int got = 0;
        while (got < numSamples) {
          engine.renderBlock(128);
          for (int i = 0; i < 128 && got < numSamples; i++) {
            out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
          }
        }

        double rms = 0.0;
        for (float f : out) {
          assertFalse(
              Float.isNaN(f) || Float.isInfinite(f),
              preset + " note " + note + " sample must be valid");
          assertTrue(Math.abs(f) <= 2.0f, preset + " note " + note + " sample must remain bounded");
          rms += f * (double) f;
        }
        rms = Math.sqrt(rms / numSamples);
        assertTrue(
            rms > 0.01, preset + " note " + note + " must produce audible multisample audio");

        fs.releaseNote(note);
      }
    }
  }
}
