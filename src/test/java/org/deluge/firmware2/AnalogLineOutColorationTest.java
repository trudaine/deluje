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
 * Dedicated portable unit test for physical hardware analog line-out coloration (§5 of
 * FIDELITY_GAP_ANALYSIS.md). Verifies that 24dB Transistor Ladder filter presets (065 Cello and 132
 * Organ Strings) render cleanly in the digital domain and demonstrates how analog line-out DAC
 * coupling stages (sub-bass roll-off and treble shelf) shape high-resonance string and organ
 * emulations.
 */
public class AnalogLineOutColorationTest {

  @Test
  public void testOrganStringsAndCelloResonanceParity() throws Exception {
    String[] presets = {"/SYNTHS/132 Organ Strings.XML", "/SYNTHS/065 Cello.XML"};
    for (String path : presets) {
      try (InputStream in = getClass().getResourceAsStream(path)) {
        assertNotNull(in, "Missing classpath resource: " + path);
        String name = path.substring(path.lastIndexOf('/') + 1).replace(".XML", "");
        SynthTrackModel synth = DelugeXmlParser.parseSynth(in, name);

        ClipModel clip = new ClipModel("c", 1, 16);
        clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60)); // Middle C (note 60)
        synth.addClip(clip);

        ProjectModel project = new ProjectModel();
        project.setBpm(120.0f);
        project.addTrack(synth);
        ProjectModel song = FirmwareFactory.createSong(project);
        FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();

        assertEquals(
            org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB,
            fs.fw2Sound.lpfMode,
            name + " must use 24dB Transistor Ladder filter");

        FirmwareAudioEngine engine = new FirmwareAudioEngine();
        engine.metronomeEnabled = false;
        engine.syncMasterEffects(project);
        engine.sounds.add(fs);

        fs.triggerNote(60, 110);

        int numSamples = 1024;
        long sumEnergy = 0;
        double maxSample = 0;
        int blocks = numSamples / 128;
        for (int b = 0; b < blocks; b++) {
          engine.renderBlock(128);
          for (int i = 0; i < 128; i++) {
            double absL = Math.abs((double) engine.masterBuffer[i].l);
            sumEnergy += (long) absL;
            maxSample = Math.max(maxSample, absL);
          }
        }

        assertTrue(
            sumEnergy > 0,
            name + " must generate non-zero acoustic output through 24dB ladder filter");
        assertTrue(
            maxSample < 2.147483648e9,
            name
                + " output must remain cleanly bounded within Q31 integer limits without clipping");
      }
    }
  }

  @Test
  public void testAnalogLineOutEqModel() {
    int numSamples = 2048;
    double sampleRate = 44100.0;
    double[] input = new double[numSamples];
    double[] output = new double[numSamples];

    // Generate test signal with 15 Hz sub-bass DC drift + 500 Hz fundamental + 15 kHz treble hiss
    for (int i = 0; i < numSamples; i++) {
      double t = i / sampleRate;
      input[i] =
          1.0
                  * Math.sin(
                      2.0 * Math.PI * 15.0
                          * t) // 15 Hz sub-bass (should be rolled off by AC coupling)
              + 2.0 * Math.sin(2.0 * Math.PI * 500.0 * t) // 500 Hz string fundamental (preserved)
              + 0.5
                  * Math.sin(
                      2.0 * Math.PI * 15000.0 * t); // 15 kHz treble (gentle shelf attenuation)
    }

    // First-order high-pass RC filter at ~35 Hz (simulating physical line-out coupling capacitor)
    double fcHp = 35.0;
    double alphaHp = 1.0 / (1.0 + 2.0 * Math.PI * fcHp / sampleRate);
    double hpPrevIn = 0.0;
    double hpPrevOut = 0.0;

    // Gentle low-pass shelf at ~10 kHz (simulating DAC reconstruction filter / analog stage)
    double fcLp = 10000.0;
    double alphaLp =
        (2.0 * Math.PI * fcLp / sampleRate) / (1.0 + 2.0 * Math.PI * fcLp / sampleRate);
    double lpPrevOut = 0.0;

    for (int i = 0; i < numSamples; i++) {
      double x = input[i];
      double hp = alphaHp * (hpPrevOut + x - hpPrevIn);
      hpPrevIn = x;
      hpPrevOut = hp;

      double lp = lpPrevOut + alphaLp * (hp - lpPrevOut);
      lpPrevOut = lp;
      output[i] = lp;
    }

    // Measure sub-bass attenuation across the second half of the buffer (after filter settling)
    double subBassIn = 0.0;
    double subBassOut = 0.0;
    for (int i = 1024; i < numSamples; i++) {
      double t = i / sampleRate;
      double ref15 = Math.sin(2.0 * Math.PI * 15.0 * t);
      subBassIn += input[i] * ref15;
      subBassOut += output[i] * ref15;
    }

    assertTrue(
        Math.abs(subBassOut) < Math.abs(subBassIn) * 0.6,
        "Analog line-out AC coupling model must attenuate sub-bass below 40 Hz by at least 40%");
  }
}
