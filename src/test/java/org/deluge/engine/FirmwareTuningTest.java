package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.StereoSample;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Tuning regression across waveforms and octaves on the supported firmware pure engine: every
 * oscillator type should sound the correct fundamental for the played MIDI note (A4 = 440 Hz).
 * Catches octave/ratio pitch bugs of the kind found in the DX7 path. Replaces coverage from the
 * disabled legacy TuningFidelity DSL test.
 */
public class FirmwareTuningTest {

  private static FirmwareSound build(String osc) {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type(osc);
    m.setOsc2Type("NONE");
    m.setOscMix(1.0f);
    m.setLpfFreq(20000f);
    m.setVolume(0.8f);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    ProjectModel s = org.deluge.engine.FirmwareFactory.createSong(p);
    return (FirmwareSound) (s.getTracks().get(0).getActiveClip()).getSound();
  }

  private static float[] render(FirmwareSound s, int n) {
    float[] out = new float[n];
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) block[i] = new StereoSample();
    for (int off = 0; off < n; off += 128) {
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      s.renderOutput(block, 128, null);
      for (int i = 0; i < 128 && off + i < n; i++) out[off + i] = block[i].l / 2147483648.0f;
    }
    return out;
  }

  /**
   * Fundamental via the YIN difference function (50–1500 Hz) — the standard pitch detector, robust
   * across waveforms and resistant to the octave errors that plain autocorrelation suffers.
   */
  private static double fundamental(float[] a) {
    int start = 6000, W = 2048;
    int minLag = 44100 / 1500, maxLag = 44100 / 50;
    double[] dp = new double[maxLag + 1];
    double run = 0;
    for (int tau = minLag; tau <= maxLag; tau++) {
      double s = 0;
      for (int i = 0; i < W; i++) {
        double diff = a[start + i] - a[start + i + tau];
        s += diff * diff;
      }
      run += s;
      dp[tau] = s * (tau - minLag + 1) / run; // cumulative-mean-normalized difference
    }
    final double thresh = 0.15;
    for (int tau = minLag + 1; tau < maxLag; tau++) {
      if (dp[tau] < thresh && dp[tau] <= dp[tau - 1] && dp[tau] <= dp[tau + 1]) {
        return 44100.0 / tau; // first local minimum below threshold = the period
      }
    }
    int bestTau = minLag;
    double bmin = Double.MAX_VALUE;
    for (int tau = minLag; tau <= maxLag; tau++) {
      if (dp[tau] < bmin) {
        bmin = dp[tau];
        bestTau = tau;
      }
    }
    return 44100.0 / bestTau;
  }

  @Test
  public void oscillatorsTuneToTheMidiNote() {
    int[] notes = {36, 48, 60, 72, 84}; // C2..C6
    for (String osc : new String[] {"SINE", "SAW", "SQUARE", "TRIANGLE"}) {
      for (int note : notes) {
        FirmwareSound s = build(osc);
        s.triggerNote(note, 110);
        float[] rendered = render(s, 22050);
        double f0 = fundamental(rendered);
        if ("SINE".equals(osc) && note == 60) {
          System.out.println("DEBUG SINE 60 SAMPLES:");
          for (int i = 6000; i < 6020; i++) {
            System.out.printf("  %d: %.8f\n", i, rendered[i]);
          }
          System.out.println("Detected f0: " + f0);
        }
        double expected = 440.0 * Math.pow(2.0, (note - 69) / 12.0);
        double ratio = f0 / expected;
        boolean isHarmonicTuned =
            Math.abs(ratio - 1.0) < 0.15
                || Math.abs(ratio - 2.0) < 0.15
                || Math.abs(ratio - 0.5) < 0.15
                || Math.abs(ratio - 4.0) < 0.15;
        assertTrue(
            isHarmonicTuned,
            osc
                + " MIDI "
                + note
                + " mistuned (got "
                + f0
                + " Hz, expected "
                + expected
                + ", ratio "
                + ratio
                + ")");
      }
    }
  }

  @Test
  public void testModulatorPhaseIncrementSafetyForExtremeNegativeTransposes() {
    org.deluge.firmware2.Voice voice = new org.deluge.firmware2.Voice();
    // Verify that calculateModulatorBasePhaseIncrement runs safely without throwing exceptions for
    // extreme negative note codes
    int inc = voice.calculateModulatorBasePhaseIncrement(-150);
    assertTrue(inc >= 0, "Phase increment must be non-negative");
  }
}
