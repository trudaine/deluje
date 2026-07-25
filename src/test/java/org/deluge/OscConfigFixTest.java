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
 * Guards osc-config semantics: (1) osc2 coarse transpose/cents must reach the DSP (faithful
 * sources[s].transpose / fineTuner), and (2) the C-faithful "NONE" contract — the C has no NONE osc
 * type (stringToOscType's else branch returns TRIANGLE, functions.cpp:812-814), so an osc is turned
 * off by its VOLUME param being MIN (isSourceActiveCurrently), never by a type-based silence.
 * Hardware-verified via the ALLSYN_2 sample presets, whose osc2 type="none" audibly plays a
 * triangle tail on the recording. Uses Goertzel power at the C4 fundamental (261) vs the octave
 * (522) — RMS/autocorrelation are unreliable here (the latter is what previously masked a phantom
 * osc-B SINE).
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
  void osc2NoneWithVolumeOffIsSilent() {
    // osc1 +octave with osc2 NONE and osc-B volume OFF (setOscMix(1) → volume 0 → param MIN, the
    // C's isSourceActiveCurrently off state): the master must be a clean 522.
    double r =
        octaveRatio(
            s -> {
              s.setOscMix(1.0f);
              s.setOsc1PitchAdjustQ31(0x20000000);
            });
    System.out.printf("[osc2 NONE, vol off] oscA+oct master 522/261=%.3f (=> >>1)%n", r);
    assertTrue(r > 50.0, "osc B with volume MIN must be silent (ratio " + r + ")");
  }

  @Test
  void osc2NoneWithVolumeUpPlaysTriangleLikeC() {
    // C functions.cpp:812-814: an unrecognized osc type ("none" included) is TRIANGLE, audible
    // whenever the osc-B volume param is up — there is no type-based silencing in the firmware.
    // With osc A raised an octave (522) and osc B left at the default audible volume, the
    // triangle's 261 fundamental must dominate the ratio.
    double r = octaveRatio(s -> s.setOsc1PitchAdjustQ31(0x20000000)); // osc2 stays NONE
    System.out.printf("[osc2 NONE, vol up] 522/261=%.3f (triangle at 261 => <1)%n", r);
    assertTrue(
        r < 1.0, "osc2 type=none with volume up must render the C's TRIANGLE (ratio " + r + ")");
  }
}
