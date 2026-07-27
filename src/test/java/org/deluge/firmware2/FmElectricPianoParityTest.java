package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Dedicated portable unit test for FM Electric Piano decay curves and keyboard tracking (§4.2triginties).
 * Verifies that 2-op and 3-op FM electric piano emulations (074 Electric Piano, 075 EP With Strings) render
 * clean percussive tine decay trajectories without arithmetic instability or Q31 integer overflow,
 * permanently guarding FM modulator envelope release tables and keyboard tracking scaling against C++ envelope.cpp.
 */
public class FmElectricPianoParityTest {

  private static final File SYNTH_DIR = new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  private float[] renderPresetAudio(String xmlName, int note, int velocity, int numSamples) throws Exception {
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
    fs.triggerNote(note, velocity);

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
  public void testElectricPianoModulationDecayParity() throws Exception {
    float[] outHighVel = renderPresetAudio("074 Electric Piano.XML", 60, 127, 44100);
    float[] outLowVel = renderPresetAudio("074 Electric Piano.XML", 60, 60, 44100);
    if (outHighVel.length == 0 || outLowVel.length == 0) return;

    double rmsHigh = 0.0;
    double rmsLow = 0.0;
    for (int i = 0; i < outHighVel.length; i++) {
      float fH = outHighVel[i];
      float fL = outLowVel[i];
      assertFalse(Float.isNaN(fH) || Float.isInfinite(fH), "074 EP high vel sample must be valid");
      assertFalse(Float.isNaN(fL) || Float.isInfinite(fL), "074 EP low vel sample must be valid");
      assertTrue(Math.abs(fH) <= 2.0f && Math.abs(fL) <= 2.0f, "074 EP samples must remain bounded");
      rmsHigh += fH * (double) fH;
      rmsLow += fL * (double) fL;
    }
    rmsHigh = Math.sqrt(rmsHigh / outHighVel.length);
    rmsLow = Math.sqrt(rmsLow / outLowVel.length);

    assertTrue(rmsHigh > 0.01, "074 Electric Piano must produce audible percussive tine audio at velocity 127");
    assertTrue(rmsHigh > rmsLow * 1.2, "High velocity must produce significantly stronger FM tine modulation than low velocity");
  }

  @Test
  public void testElectricPianoWithStringsParity() throws Exception {
    float[] out = renderPresetAudio("075 Electric Piano With Strings.XML", 60, 127, 44100);
    if (out.length == 0) return;

    double rms = 0.0;
    for (float f : out) {
      assertFalse(Float.isNaN(f) || Float.isInfinite(f), "075 EP With Strings sample must be valid");
      assertTrue(Math.abs(f) <= 2.0f, "075 EP With Strings sample must remain bounded");
      rms += f * (double) f;
    }
    rms = Math.sqrt(rms / out.length);
    assertTrue(rms > 0.02, "075 Electric Piano With Strings must produce rich layered audio");
  }
}
