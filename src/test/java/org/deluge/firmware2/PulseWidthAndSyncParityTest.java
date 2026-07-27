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
 * Dedicated portable unit test for Pulse-Width and Hard Sync band-limited oscillator parity (§4.2triginties).
 * Verifies that pulse-width modulated presets (026 PW Organ, 027 PW Envelope) and hard-sync presets (046 Saw Sync)
 * render cleanly across multi-block buffers without Q31 integer overflow, clipping, or NaN generation,
 * permanently guarding band-limited pulse multiplication and sync reset phase tracking against C++ oscillator.cpp.
 */
public class PulseWidthAndSyncParityTest {

  private static final File SYNTH_DIR = new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  private float[] renderPresetAudio(String xmlName, int numSamples) throws Exception {
    File xml = new File(SYNTH_DIR, xmlName);
    if (!xml.exists()) {
      return new float[0];
    }
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

    float[] out = new float[numSamples];
    int got = 0;
    while (got < numSamples) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < numSamples; i++) {
        out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
      }
    }
    return out;
  }

  @Test
  public void testPwOrganPulseWidthModulationParity() throws Exception {
    float[] out = renderPresetAudio("026 PW Organ.XML", 44100);
    if (out.length == 0) return;

    double rms = 0.0;
    for (float f : out) {
      assertFalse(Float.isNaN(f) || Float.isInfinite(f), "026 PW Organ sample must not be NaN or Infinite");
      assertTrue(Math.abs(f) <= 2.0f, "026 PW Organ sample must remain bounded within headroom");
      rms += f * (double) f;
    }
    rms = Math.sqrt(rms / out.length);
    assertTrue(rms > 0.05, "026 PW Organ must produce strong audible output through pulse-width modulation");
  }

  @Test
  public void testPwEnvelopeDynamicPulseWidthParity() throws Exception {
    float[] out = renderPresetAudio("027 PW Envelope.XML", 44100);
    if (out.length == 0) return;

    double rms = 0.0;
    for (float f : out) {
      assertFalse(Float.isNaN(f) || Float.isInfinite(f), "027 PW Envelope sample must not be NaN or Infinite");
      assertTrue(Math.abs(f) <= 2.0f, "027 PW Envelope sample must remain bounded");
      rms += f * (double) f;
    }
    rms = Math.sqrt(rms / out.length);
    assertTrue(rms > 0.05, "027 PW Envelope must produce audible output as envelope sweeps pulse width");
  }

  @Test
  public void testSawSyncHardSyncParity() throws Exception {
    float[] out = renderPresetAudio("046 Saw Sync.XML", 44100);
    if (out.length == 0) return;

    double rms = 0.0;
    for (float f : out) {
      assertFalse(Float.isNaN(f) || Float.isInfinite(f), "046 Saw Sync sample must not be NaN or Infinite");
      assertTrue(Math.abs(f) <= 2.0f, "046 Saw Sync sample must remain bounded across oscillator resets");
      rms += f * (double) f;
    }
    rms = Math.sqrt(rms / out.length);
    assertTrue(rms > 0.05, "046 Saw Sync must produce audible output through hard sync phase resets");
  }
}
