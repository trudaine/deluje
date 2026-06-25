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
 * Guards two osc-config bugs: (1) osc2 coarse transpose/cents must reach the DSP (faithful
 * sources[s].transpose / fineTuner), and (2) osc2Type="NONE" must silence osc B (no phantom SINE).
 * Uses Goertzel power at the C4 fundamental (261) vs the octave (522) — RMS/autocorrelation are
 * unreliable here (the latter is what previously masked a phantom osc-B SINE).
 */
public class OscConfigFixTest {

  private static double g(double[] x, double f) {
    double w = 2 * Math.PI * f / 44100, c = 2 * Math.cos(w), s1 = 0, s2 = 0;
    for (double v : x) {
      double s = v + c * s1 - s2;
      s2 = s1;
      s1 = s;
    }
    return s2 * s2 + s1 * s1 - c * s1 * s2;
  }

  private static double octaveRatio(java.util.function.Consumer<SynthTrackModel> cfg) {
    FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    SynthTrackModel synth = new SynthTrackModel("A");
    synth.setOsc1Type("SAW");
    synth.setOsc2Type("NONE");
    cfg.accept(synth);
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
    double[] buf = new double[n];
    int got = 0;
    for (int b = 0; got < n; b++) {
      engine.renderBlock(128);
      if (b < 40) continue;
      for (int i = 0; i < 128 && got < n; i++) buf[got++] = engine.masterBuffer[i].l / 2147483648.0;
    }
    return g(buf, 522.0) / Math.max(g(buf, 261.0), 1e-12);
  }

  @Test
  void osc2TransposeRaisesOctave() {
    // osc B only (oscMix=0 → osc A volume off), osc2 +12 semis should put the fundamental at 522.
    double base =
        octaveRatio(
            s -> {
              s.setOscMix(0.0f);
              s.setOsc2Type("SAW");
            });
    double up =
        octaveRatio(
            s -> {
              s.setOscMix(0.0f);
              s.setOsc2Type("SAW");
              s.setOsc2Transpose(12);
            });
    System.out.printf("[osc2 transpose] base 522/261=%.3f  +12=%.3f%n", base, up);
    assertTrue(base < 1.0, "osc2 at neutral should be 261 (ratio<1), got " + base);
    assertTrue(up > 50.0, "osc2 transpose +12 did not raise an octave (ratio " + up + ")");
  }

  @Test
  void osc2NoneSilencesPhantom() {
    // osc1 +octave with osc2 NONE: the master must be a clean 522, NOT corrupted by a phantom oscB.
    double r = octaveRatio(s -> s.setOsc1PitchAdjustQ31(0x20000000)); // osc2 stays NONE
    System.out.printf("[osc2 NONE] oscA+oct master 522/261=%.3f (no phantom => >>1)%n", r);
    assertTrue(r > 50.0, "phantom osc B corrupted osc-A pitch (ratio " + r + ")");
  }
}
