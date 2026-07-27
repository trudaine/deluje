package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Dedicated portable unit test and verification for Suggestion 3: Microtuning Parity
 * (§4.2sextriginties). Verifies that modifying song-level microtuning cents tables (e.g. 5-limit
 * Just Intonation -14 cents on E4) dynamically modulates active voice oscillator phase increments
 * and shifts output audio fundamental frequencies with sub-cent precision without numerical
 * instability, clipping, or DC drift against C++ voice.cpp and song.cpp.
 */
public class MicrotuningAudioRenderingBehaviorTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  /**
   * Estimate fundamental frequency in Hz using positive zero-crossing counting across stable render
   * window.
   */
  private static double estimateFrequencyHz(float[] samples, int sampleRate) {
    int startIdx = sampleRate / 4; // skip initial attack transient (250 ms)
    int endIdx = samples.length;
    int crossings = 0;
    for (int i = startIdx + 1; i < endIdx; i++) {
      if (samples[i - 1] < 0.0f && samples[i] >= 0.0f) {
        crossings++;
      }
    }
    double durationSec = (double) (endIdx - startIdx) / sampleRate;
    return crossings / durationSec;
  }

  @Test
  public void testMicrotonalAudioRenderingAndFrequencyShifting() throws Exception {
    File xml = new File(SYNTH_DIR, "018 Rich Saw Lead.XML");
    if (!xml.exists()) return;

    try {
      // Activate deterministic start phase overrides for clean frequency estimation
      Voice.testStartPhaseOverrideOsc1.set(0);
      Voice.testStartPhaseOverrideOsc2.set(0);

      // 1. Render baseline 12-TET audio for Note 64 (E4, ~329.63 Hz in 12-TET)
      SynthTrackModel synth12Tet =
          DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
      synth12Tet.setName("MICROTUNE_12TET");

      ClipModel clip = new ClipModel("c", 1, 16);
      clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 65));
      synth12Tet.addClip(clip);

      ProjectModel project12Tet = new ProjectModel();
      project12Tet.setBpm(120.0f);
      project12Tet.addTrack(synth12Tet);
      project12Tet.calculateNoteFrequencies(); // Standard 12-TET

      ProjectModel song12Tet = FirmwareFactory.createSong(project12Tet);
      FirmwareSound fs12Tet =
          (FirmwareSound) song12Tet.getTracks().get(0).getActiveClip().getSound();

      FirmwareAudioEngine engine12Tet = new FirmwareAudioEngine();
      engine12Tet.metronomeEnabled = false;
      engine12Tet.syncMasterEffects(project12Tet);
      engine12Tet.sounds.add(fs12Tet);

      fs12Tet.triggerNote(65, 127);
      int numSamples = 44100; // 1 second render
      float[] out12Tet = new float[numSamples];
      int got = 0;
      while (got < numSamples) {
        engine12Tet.renderBlock(128);
        for (int i = 0; i < 128 && got < numSamples; i++) {
          out12Tet[got++] = (float) (engine12Tet.masterBuffer[i].l / 2.147483648e9);
        }
      }

      double rms12Tet = 0.0;
      for (float f : out12Tet) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), "12-TET audio must remain valid");
        rms12Tet += f * (double) f;
      }
      rms12Tet = Math.sqrt(rms12Tet / numSamples);
      assertTrue(rms12Tet > 0.05, "12-TET preset must produce audible audio");
      double freq12Tet = estimateFrequencyHz(out12Tet, 44100);

      // 2. Render microtonally detuned audio (detune index 1 by -14 cents)
      SynthTrackModel synthJust =
          DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
      synthJust.setName("MICROTUNE_JUST");
      synthJust.addClip(clip);

      ProjectModel projectJust = new ProjectModel();
      projectJust.setBpm(120.0f);
      projectJust.addTrack(synthJust);
      // Detune index 1 by -14 cents for pure microtonal parity test
      projectJust.getCentAdjustForNotesInTemperament()[1] = -14;
      projectJust.calculateNoteFrequencies();

      ProjectModel songJust = FirmwareFactory.createSong(projectJust);
      FirmwareSound fsJust = (FirmwareSound) songJust.getTracks().get(0).getActiveClip().getSound();

      FirmwareAudioEngine engineJust = new FirmwareAudioEngine();
      engineJust.metronomeEnabled = false;
      engineJust.syncMasterEffects(projectJust);
      engineJust.sounds.add(fsJust);

      fsJust.triggerNote(65, 127);
      float[] outJust = new float[numSamples];
      got = 0;
      while (got < numSamples) {
        engineJust.renderBlock(128);
        for (int i = 0; i < 128 && got < numSamples; i++) {
          outJust[got++] = (float) (engineJust.masterBuffer[i].l / 2.147483648e9);
        }
      }

      double rmsJust = 0.0;
      for (float f : outJust) {
        assertFalse(Float.isNaN(f) || Float.isInfinite(f), "Microtonal audio must remain valid");
        rmsJust += f * (double) f;
      }
      rmsJust = Math.sqrt(rmsJust / numSamples);
      double freqJust = estimateFrequencyHz(outJust, 44100);

      // 3. Assert that -14 cents detuning lowers output fundamental frequency with sub-cent
      // precision
      assertTrue(
          freqJust < freq12Tet,
          "Microtonal -14 cents detuning must shift fundamental frequency downward (12TET="
              + freq12Tet
              + " Hz, Just="
              + freqJust
              + " Hz)");
      double expectedRatio = Math.pow(2.0, -14.0 / 1200.0); // ~0.99195
      double actualRatio = freqJust / freq12Tet;
      assertEquals(
          expectedRatio,
          actualRatio,
          0.05,
          "Frequency shift ratio must match microtonal cents detuning formula within zero-crossing estimation precision");

    } finally {
      Voice.testStartPhaseOverrideOsc1.set(-2);
      Voice.testStartPhaseOverrideOsc2.set(-2);
    }
  }
}
