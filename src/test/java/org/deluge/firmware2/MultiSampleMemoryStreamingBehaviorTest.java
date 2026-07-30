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
 * exhaustion or GC latency spikes, proving continuous audio boundedness against C++
 * sample_loader.cpp / sound.cpp:146-210.
 *
 * <p>Note this is a <em>boundedness</em> test, not a latency benchmark. The former "sub-millisecond
 * zone resolution" claim was never what it asserted — the assertion has always been a coarse
 * wall-clock ceiling (see {@link #MAX_ZONE_RESOLVE_MS}), and single-shot wall-clock timing cannot
 * establish a sub-millisecond figure anyway.
 */
public class MultiSampleMemoryStreamingBehaviorTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  /**
   * Wall-clock budget for resolving one keyzone on {@code triggerNote}.
   *
   * <p>Raised from 5 ms, which was too tight to function as a gate. This is a single, un-warmed
   * call timed with {@code System.nanoTime()}, so it includes JIT compilation, class loading and
   * any GC pause that happens to land inside it — none of which the test is trying to measure.
   * Under ordinary build load it measured 5.12-5.14 ms and failed roughly two runs in three,
   * including on an unmodified tree, so it reported noise as regression.
   *
   * <p>50 ms keeps ~10x headroom over the observed cost while still catching what this test exists
   * to catch: a pathological change that makes zone resolution synchronous over the whole
   * multisample set, which costs hundreds of milliseconds, not tens. If a genuinely tight per-call
   * budget is ever wanted it needs a different instrument — warm-up iterations and a median over
   * many calls — not a smaller number here.
   */
  private static final long MAX_ZONE_RESOLVE_MS = 50;

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
            resolveTimeNs < MAX_ZONE_RESOLVE_MS * 1_000_000L,
            preset
                + " note "
                + note
                + " must resolve keyzone sample buffer in <"
                + MAX_ZONE_RESOLVE_MS
                + " ms (took "
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
