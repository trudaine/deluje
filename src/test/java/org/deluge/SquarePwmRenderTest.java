package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Oscillator;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies the band-limited square pulse-width port (renderPulseWave): the duty cycle (positive
 * sample fraction at the raw osc level, where there's no filtering/DC-block) must TRACK pulseWidth.
 * Before the fix, duty was stuck at ~0.50 for every pulseWidth.
 */
public class SquarePwmRenderTest {

  private static double duty(int pulseWidth) {
    int n = 8192;
    int[] buf = new int[n];
    int[] phase = {0};
    int phaseInc = 25000000; // ~256 Hz → band-limited path
    Oscillator.renderOsc(
        OscType.SQUARE, 0, buf, 0, n, phaseInc, pulseWidth, phase, false, 0, false, 0, 0, 0);
    long pos = 0;
    long maxAbs = 0;
    for (int v : buf) {
      if (v > 0) pos++;
      maxAbs = Math.max(maxAbs, Math.abs((long) v));
    }
    double d = pos / (double) n;
    System.out.printf("  pulseWidth=0x%08X duty=%.4f maxAbs=%d%n", pulseWidth, d, maxAbs);
    return d;
  }

  private static double goertzel(double[] x, double f) {
    double w = 2 * Math.PI * f / 44100;
    double coeff = 2 * Math.cos(w);
    double s1 = 0, s2 = 0;
    for (double v : x) {
      double s = v + coeff * s1 - s2;
      s2 = s1;
      s1 = s;
    }
    return s2 * s2 + s1 * s1 - coeff * s1 * s2;
  }

  /** Full chain: preset osc1PhaseWidthQ31 → factory → engine. Returns 2nd/1st harmonic ratio. */
  private static double secondHarmonicRatio(int osc1PhaseWidthQ31) {
    FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    SynthTrackModel synth = new SynthTrackModel("PWMChain");
    synth.setOsc1Type("SQUARE");
    synth.setOsc2Type("NONE");
    if (osc1PhaseWidthQ31 != Integer.MIN_VALUE) synth.setOsc1PhaseWidthQ31(osc1PhaseWidthQ31);
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
    return goertzel(buf, 522.0) / Math.max(goertzel(buf, 261.0), 1e-12);
  }

  @Test
  void fullChainPulseWidthAddsEvenHarmonic() {
    double neutral = secondHarmonicRatio(Integer.MIN_VALUE); // 50% → odd harmonics only
    double pw = secondHarmonicRatio(0x40000000); // off-centre → even harmonics appear
    System.out.printf("[PWM full-chain] neutral 2nd/1st=%.5f  pw 2nd/1st=%.5f%n", neutral, pw);
    assertTrue(
        neutral < 0.005, "neutral square should have ~no 2nd harmonic (got " + neutral + ")");
    // Off-centre duty introduces a clear even harmonic that's absent at 50% (neutral≈0 → pw≈0.029).
    assertTrue(
        pw > 0.01 && pw > neutral + 0.01,
        "pulse width did not add even harmonics end-to-end (neutral="
            + neutral
            + " pw="
            + pw
            + ")");
  }

  @Test
  void pulseWidthChangesDuty() {
    double d0 = duty(0); // neutral → ~50%
    double d1 = duty(0x40000000);
    double d2 = duty(0x60000000);
    double d3 = duty(0x78000000);
    assertEquals(0.5, d0, 0.06, "neutral square should be ~50% duty");
    // At least one off-centre pulse width must move the duty clearly away from 50%.
    double maxDev = Math.max(Math.abs(d1 - 0.5), Math.max(Math.abs(d2 - 0.5), Math.abs(d3 - 0.5)));
    assertTrue(
        maxDev > 0.05,
        "pulseWidth did not change the duty (d0="
            + d0
            + " d1="
            + d1
            + " d2="
            + d2
            + " d3="
            + d3
            + ")");
  }
}
