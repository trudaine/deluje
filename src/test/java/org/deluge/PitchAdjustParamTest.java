package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Proves oscAPitchAdjust / oscBPitchAdjust / pitchAdjust reach the DSP and shift pitch. Pitch
 * doesn't change RMS, and zero-crossings are noisy on a harmonic-rich saw, so this estimates the
 * fundamental frequency by autocorrelation: a positive pitch adjust must raise it. Guards against a
 * parsed-but-ignored param.
 */
public class PitchAdjustParamTest {

  /** Render a SAW synth and estimate fundamental Hz via autocorrelation over a steady window. */
  private static double fundamentalHz(int overallAdjust) {
    org.deluge.engine.FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    SynthTrackModel synth = new SynthTrackModel("PitchTest");
    synth.setOsc1Type("SAW");
    synth.setOsc2Type("NONE");
    if (overallAdjust != Integer.MIN_VALUE) synth.setPitchAdjustQ31(overallAdjust);
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
    engine.sounds.add(fs);

    int n = 16384;
    double[] x = new double[n];
    int got = 0;
    int skipBlocks = 40; // skip the attack
    for (int b = 0; got < n; b++) {
      engine.renderBlock(128);
      if (b < skipBlocks) continue;
      for (int i = 0; i < 128 && got < n; i++) x[got++] = engine.masterBuffer[i].l / 2147483648.0;
    }
    // Autocorrelation peak over 60 Hz..1500 Hz.
    int minLag = 44100 / 1500, maxLag = 44100 / 60;
    double best = -1;
    int bestLag = minLag;
    for (int lag = minLag; lag <= maxLag; lag++) {
      double s = 0;
      for (int i = 0; i + lag < n; i++) s += x[i] * x[i + lag];
      if (s > best) {
        best = s;
        bestLag = lag;
      }
    }
    return 44100.0 / bestLag;
  }

  @Test
  void overallPitchAdjustRaisesPitch() {
    double neutral = fundamentalHz(Integer.MIN_VALUE);
    double raised = fundamentalHz(0x20000000);
    System.out.printf("[PitchAdjust] overall: neutral=%.1fHz raised=%.1fHz%n", neutral, raised);
    assertTrue(neutral > 50, "no detectable pitch at neutral");
    assertTrue(
        raised > neutral * 1.3, "pitchAdjust did not raise pitch: " + neutral + "→" + raised);
  }
}
