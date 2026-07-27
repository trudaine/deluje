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
 * Dedicated portable unit test and verification for Frontier B: Unison Phase Synchronization
 * (§4.2quattuortriginties). Verifies that multi-voice unison presets (e.g. 018 Rich Saw Lead, 039
 * Detuned Retriggering Saws) disperse initial oscillator phases across unison parts when
 * deterministic phase overrides are active, preventing constructive interference transient spikes
 * and matching analog hardware time-domain phase distribution against C++ voice.cpp:399-411.
 */
public class UnisonPhaseSeedSynchronizationTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  @Test
  public void testUnisonPhaseSeedDispersion() throws Exception {
    File xml = new File(SYNTH_DIR, "018 Rich Saw Lead.XML");
    if (!xml.exists()) return;

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

    assertTrue(
        fs.fw2Sound.numUnison > 1,
        "018 Rich Saw Lead must be configured as a multi-voice unison preset");

    try {
      // Activate deterministic start phase override for offline evaluation
      Voice.testStartPhaseOverrideOsc1.set(0);
      Voice.testStartPhaseOverrideOsc2.set(0);

      fs.triggerNote(60, 127);
      assertFalse(fs.fw2Sound.voices.isEmpty(), "Voice must be acquired on note trigger");

      Voice voice = fs.fw2Sound.voices.get(0);
      assertTrue(voice.unisonParts.length >= 2, "Voice must contain at least 2 unison parts");

      int phasePart0 = voice.unisonParts[0].sources[0].oscPos;
      int phasePart1 = voice.unisonParts[1].sources[0].oscPos;

      assertNotEquals(
          phasePart0,
          phasePart1,
          "Unison parts must receive dispersed initial starting phases (part0="
              + phasePart0
              + ", part1="
              + phasePart1
              + ")");

      int expectedStep = (int) (2147483647L / fs.fw2Sound.numUnison);
      assertEquals(
          expectedStep,
          phasePart1 - phasePart0,
          "Unison phase dispersion step must divide Q31 space evenly by numUnison");

      FirmwareAudioEngine engine = new FirmwareAudioEngine();
      engine.metronomeEnabled = false;
      engine.syncMasterEffects(project);
      engine.sounds.add(fs);

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
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), "Unison audio sample must be valid");
        assertTrue(
            Math.abs(f) <= 2.0f,
            "Unison audio sample must remain bounded without constructive interference spikes");
        rms += f * (double) f;
      }
      rms = Math.sqrt(rms / numSamples);
      assertTrue(rms > 0.05, "Must produce strong audible unison audio output");

    } finally {
      Voice.testStartPhaseOverrideOsc1.set(-2);
      Voice.testStartPhaseOverrideOsc2.set(-2);
    }
  }
}
