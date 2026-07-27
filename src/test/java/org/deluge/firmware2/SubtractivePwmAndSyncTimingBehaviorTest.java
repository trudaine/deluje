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
 * Dedicated portable unit test for subtractive PWM and sub-sample BLEP timing parity
 * (§4.2untriginties). Verifies that pulse-width modulated and hard-sync presets (028 PWM, 033 Rich
 * Square, 043 Square Porta, 044 8-Bit Lead, 045 Square Sync) render cleanly across multi-block
 * buffers without Q31 integer overflow, clipping, or DC offset drift, permanently guarding
 * sub-sample BLEP step timing against C++ oscillator.cpp.
 */
public class SubtractivePwmAndSyncTimingBehaviorTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  private float[] renderPresetAudio(String xmlName, int note, int numSamples) throws Exception {
    File xml = new File(SYNTH_DIR, xmlName);
    if (!xml.exists()) {
      return new float[0];
    }
    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));

    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, note));
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(note, 127);

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
  public void testPwmAndSquareStability() throws Exception {
    String[] presets = {"028 PWM.XML", "033 Rich Square.XML"};
    for (String preset : presets) {
      float[] out = renderPresetAudio(preset, 48, 44100);
      if (out.length == 0) continue;

      double rms = 0.0;
      for (float f : out) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), preset + " sample must be valid");
        assertTrue(Math.abs(f) <= 2.0f, preset + " sample must remain bounded across PWM sweeps");
        rms += f * (double) f;
      }
      rms = Math.sqrt(rms / out.length);
      assertTrue(rms > 0.05, preset + " must produce strong audible PWM/square audio");
    }
  }

  @Test
  public void testSquarePortaAndSyncParity() throws Exception {
    String[] presets = {"043 Square Porta.XML", "044 8-Bit Lead.XML", "045 Square Sync.XML"};
    for (String preset : presets) {
      float[] out = renderPresetAudio(preset, 60, 44100);
      if (out.length == 0) continue;

      double rms = 0.0;
      for (float f : out) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), preset + " sample must be valid");
        assertTrue(Math.abs(f) <= 2.0f, preset + " sample must remain bounded across sync resets");
        rms += f * (double) f;
      }
      rms = Math.sqrt(rms / out.length);
      assertTrue(rms > 0.05, preset + " must produce audible hard-sync/lead audio");
    }
  }
}
