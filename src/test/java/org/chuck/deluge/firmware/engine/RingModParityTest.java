package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Verifies that SynthMode.RINGMOD actually ring-modulates (osc A × osc B) rather than degrading to
 * a plain oscillator sum. The fixture is built through FirmwareFactory so osc2 pitch and volume
 * take the same path as real synth models.
 */
public class RingModParityTest {

  private static FirmwareSound buildSound(FirmwareSound.SynthMode mode) {
    SynthTrackModel model = new SynthTrackModel("ringmod");
    model.setOsc1Type("SINE");
    model.setOsc2Type("SINE");
    model.setOscAVolume(1.0f);
    model.setOscBVolume(1.0f);
    model.setOsc2Transpose(0);
    model.setOsc2Cents(0);
    model.setVolume(1.0f);
    model.setLpfFreq(20000f);
    model.setLpfRes(0.0f);
    model.setSynthMode(mode == FirmwareSound.SynthMode.RINGMOD ? 2 : 0);
    model.addClip(new ClipModel("c", 8, 16));

    ProjectModel project = new ProjectModel();
    project.addTrack(model);
    Song song = FirmwareFactory.createSong(project);
    return (FirmwareSound) ((InstrumentClip) song.clips.get(0)).sound;
  }

  private static double[] renderMono(FirmwareSound.SynthMode mode, int total) {
    FirmwareSound sound = buildSound(mode);
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
    assertTrue(
        ringRms > 0, "RingMod render should be non-silent (regression: it was a silent/sum)");

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
