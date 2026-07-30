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
 * Dedicated portable unit test and verification for Suggestion 3: Microtuning Parity
 * (§4.2sextriginties). Verifies that modifying song-level microtuning cents tables (e.g. 5-limit
 * Just Intonation) dynamically modulates active voice oscillator phase increments and shifts the
 * rendered audio's fundamental downward, without numerical instability, clipping, or DC drift,
 * against C++ voice.cpp and song.cpp.
 *
 * <p>The note played is 65 (F4) and the temperament entry exercised is index 1; the former "-14
 * cents on E4" description matched neither. Nor is the shift "sub-cent precise" — see the magnitude
 * assertion for why a multi-oscillator preset cannot produce an exact cents ratio.
 */
public class MicrotuningAudioRenderingBehaviorTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  /**
   * Estimates the fundamental frequency by autocorrelation, refining the peak lag with parabolic
   * interpolation for sub-sample precision.
   *
   * <p>Replaces a positive-zero-crossing counter, which could not measure what this test asserts.
   * The preset is a <em>rich</em> saw lead — multiple oscillators plus unison detune — so its
   * waveform crosses zero several times per cycle and the crossing rate is not the fundamental: for
   * note 65 (F4, 349.2 Hz) it reported 581-591 Hz. Worse, the count changes with waveform shape, so
   * a 14-cent shift (0.8%) sat well inside the estimator's own noise and the comparison came out
   * arbitrarily. That is why this test failed intermittently on an unmodified tree.
   *
   * <p>Autocorrelation is the right instrument here despite the project's standing warning about it
   * (CLAUDE.md): that warning is about judging fidelity against hardware recordings, where it gives
   * false readings. This is a controlled A/B of two of our own renders that differ only in tuning,
   * where relative period is exactly what needs measuring. Parabolic interpolation is required, not
   * optional — at 349 Hz one period is ~126 samples, so 0.8% is under one sample and integer-lag
   * resolution alone would quantise the effect away.
   */
  private static double estimateFrequencyHz(float[] samples, int sampleRate) {
    int startIdx = sampleRate / 4; // skip the attack transient (250 ms)
    int n = samples.length - startIdx;

    // Search lags spanning ~80 Hz to ~1 kHz, comfortably bracketing the notes under test.
    int minLag = sampleRate / 1000;
    int maxLag = Math.min(sampleRate / 80, n / 2);

    double bestScore = Double.NEGATIVE_INFINITY;
    int bestLag = minLag;
    double[] score = new double[maxLag + 1];
    for (int lag = minLag; lag <= maxLag; lag++) {
      double sum = 0.0;
      double energy = 1e-12;
      for (int i = 0; i < n - lag; i++) {
        double a = samples[startIdx + i];
        double b = samples[startIdx + i + lag];
        sum += a * b;
        energy += b * b;
      }
      // Normalise so long lags are not penalised purely by having fewer overlapping terms.
      score[lag] = sum / Math.sqrt(energy);
      if (score[lag] > bestScore) {
        bestScore = score[lag];
        bestLag = lag;
      }
    }

    double refined = bestLag;
    if (bestLag > minLag && bestLag < maxLag) {
      double y0 = score[bestLag - 1];
      double y1 = score[bestLag];
      double y2 = score[bestLag + 1];
      double denom = 2.0 * (2.0 * y1 - y0 - y2);
      if (Math.abs(denom) > 1e-12) {
        refined = bestLag + (y2 - y0) / denom;
      }
    }
    return sampleRate / refined;
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
      // Index 1 is correct and was verified by sweeping all 12 entries against this preset and this
      // note: only index 1 moves the pitch at all (every other entry lands within +-0.12 cents,
      // i.e. measurement noise). It is not 65 % 12 = 5 because the preset transposes.
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
      // MAGNITUDE. An exact -14 cent ratio is NOT obtainable from this preset and asserting one was
      // the second defect here: "018 Rich Saw Lead" is a multi-oscillator patch, the temperament
      // entry detunes only the oscillator sitting on that pitch class, and the composite period
      // therefore moves by less than the full amount. Sweeping the table measures -7.98 cents for a
      // -14 cent entry, deterministically. So the honest bound is: the shift is real, downward, and
      // cannot exceed the requested detune — which still catches a sign error, a no-op microtuning
      // path, or a gross scaling error. An exact ratio would need a single-oscillator preset.
      double shiftCents = 1200.0 * Math.log(freqJust / freq12Tet) / Math.log(2.0);
      assertTrue(
          shiftCents < -1.0 && shiftCents > -14.5,
          "A -14 cent temperament entry must shift the rendered pitch audibly downward without"
              + " exceeding the requested detune (measured "
              + shiftCents
              + " cents; 12TET="
              + freq12Tet
              + " Hz, Just="
              + freqJust
              + " Hz)");

    } finally {
      Voice.testStartPhaseOverrideOsc1.set(-2);
      Voice.testStartPhaseOverrideOsc2.set(-2);
    }
  }
}
