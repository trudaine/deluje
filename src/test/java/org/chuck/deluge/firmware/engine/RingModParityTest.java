package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.junit.jupiter.api.Test;

/**
 * Verifies that SynthMode.RINGMOD actually ring-modulates (osc A × osc B) rather than degrading to a
 * plain oscillator sum. With two sine oscillators at the same note, ring modulation produces DC +
 * 2f (sin·sin = ½ − ½cos(2·2πft)), so after removing the DC offset the signal oscillates at twice
 * the fundamental — i.e. roughly double the zero-crossing rate of the subtractive (single-sine)
 * render. Before the fix, RINGMOD summed the sources and stayed at the fundamental.
 */
public class RingModParityTest {

  private static double[] renderMono(FirmwareSound.SynthMode mode, int total) {
    FirmwareSound sound = new FirmwareSound();
    sound.synthMode = mode;
    sound.triggerNote(60, 100);
    StereoSample[] buf = new StereoSample[total];
    for (int i = 0; i < total; i++) buf[i] = new StereoSample();
    sound.renderInternal(buf, total, null);
    double[] mono = new double[total];
    for (int i = 0; i < total; i++) mono[i] = buf[i].l;
    return mono;
  }

  /** Zero crossings of the signal after subtracting its mean (removes ring-mod DC offset). */
  private static int zeroCrossings(double[] x, int from, int to) {
    double mean = 0;
    for (int i = from; i < to; i++) mean += x[i];
    mean /= (to - from);
    int crossings = 0;
    boolean prevPos = (x[from] - mean) >= 0;
    for (int i = from + 1; i < to; i++) {
      boolean pos = (x[i] - mean) >= 0;
      if (pos != prevPos) crossings++;
      prevPos = pos;
    }
    return crossings;
  }

  private static double rms(double[] x, int from, int to) {
    double s = 0;
    for (int i = from; i < to; i++) s += x[i] * x[i];
    return Math.sqrt(s / (to - from));
  }

  @Test
  public void ringModDoublesFundamental() {
    int total = 12000;
    int from = 4000; // skip envelope attack, measure steady state
    int to = total;

    double[] sub = renderMono(FirmwareSound.SynthMode.SUBTRACTIVE, total);
    double[] ring = renderMono(FirmwareSound.SynthMode.RINGMOD, total);

    double subRms = rms(sub, from, to);
    double ringRms = rms(ring, from, to);
    int subZc = zeroCrossings(sub, from, to);
    int ringZc = zeroCrossings(ring, from, to);

    assertTrue(subRms > 0, "Subtractive render should be non-silent");
    assertTrue(ringRms > 0, "RingMod render should be non-silent (regression: it was a silent/sum)");

    // Ring modulation of two equal-frequency sines doubles the effective frequency.
    assertTrue(
        ringZc > subZc * 1.6,
        "RingMod should roughly double zero-crossings (got ring="
            + ringZc
            + ", sub="
            + subZc
            + ") — proves A*B multiplication, not a sum");
  }
}
