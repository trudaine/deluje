package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.modulation.automation.AutoParam;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchCable;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware2.StereoSample;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Local LFO modulation on the supported firmware pure engine. An LFO routed to volume produces
 * tremolo (the amplitude wobbles at the LFO rate); without the cable the level is steady. Replaces
 * coverage from the disabled legacy MultiLfo DSL test.
 */
public class FirmwareLfoModulationTest {

  private static final int ONE = 2147483647;

  private static FirmwareSound buildSine(boolean addLfoToVolume) {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type("SINE");
    m.setOsc2Type("NONE");
    m.setOscMix(1.0f);
    m.setLpfFreq(20000f);
    m.setVolume(0.8f);
    m.setEnv(0, new org.chuck.deluge.model.EnvelopeModel(0.0f, 30.0f, 0.7f, 0.2f, "NONE", 0.0f));
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = org.chuck.deluge.firmware.engine.FirmwareFactory.createSong(p);
    FirmwareSound sound = (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;

    // Drive the local LFO at ~5 Hz. The LFO-rate param is now the phase increment directly
    // (firmware
    // scale: full 2^32 cycle per sample), so 5 Hz = 5 * 2^32 / 44100 ≈ 487018. The factory may
    // install
    // an automated param for the rate that overrides the neutral value, so set both.
    int fiveHz = (int) (5.0 * 4294967296.0 / 44100.0);
    sound.paramNeutralValues[Param.LOCAL_LFO_LOCAL_FREQ_1] = fiveHz;
    AutoParam rateAp = sound.paramManager.getAutomatedParam(Param.LOCAL_LFO_LOCAL_FREQ_1);
    if (rateAp != null) rateAp.currentValue = fiveHz;
    sound.lfoWaveforms[1] =
        org.chuck.deluge.firmware2.Lfo.LfoType.SINE; // LFO_LOCAL_1 uses waveform index 1

    if (addLfoToVolume) {
      PatchCable c = new PatchCable();
      c.from = PatchSource.LFO_LOCAL_1;
      c.amount = ONE / 2;
      sound.paramManager.getPatchCableSet().addCable(Param.LOCAL_VOLUME, c);
    }
    return sound;
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

  private static double rms(float[] a, int from, int to) {
    double s = 0;
    for (int i = from; i < to; i++) s += (double) a[i] * a[i];
    return Math.sqrt(s / Math.max(1, to - from));
  }

  /** Peak-to-trough variation of per-window RMS, normalized by mean RMS — a tremolo-depth proxy. */
  private static double rmsWobble(float[] a, int win) {
    double min = Double.MAX_VALUE, max = -Double.MAX_VALUE, sum = 0;
    int cnt = 0;
    int skip = 8820; // skip the ~0.2 s attack transient
    for (int w = skip; w + win <= a.length; w += win) {
      double r = rms(a, w, w + win);
      min = Math.min(min, r);
      max = Math.max(max, r);
      sum += r;
      cnt++;
    }
    double mean = sum / Math.max(1, cnt);
    return (max - min) / (mean + 1e-12);
  }

  @Test
  public void lfoToVolumeProducesTremolo() {
    int win = 2205; // 0.05 s windows (LFO ~5 Hz → ~4 windows/cycle)

    FirmwareSound steady = buildSine(false);
    steady.triggerNote(60, 100);
    double wobbleSteady = rmsWobble(render(steady, 44100), win);

    FirmwareSound trem = buildSine(true);
    trem.triggerNote(60, 100);
    double wobbleTrem = rmsWobble(render(trem, 44100), win);

    assertTrue(wobbleSteady < 0.15, "no-LFO note should be steady (wobble=" + wobbleSteady + ")");
    assertTrue(wobbleTrem > 0.3, "LFO->volume should produce tremolo (wobble=" + wobbleTrem + ")");
    assertTrue(
        wobbleTrem > wobbleSteady * 2.0,
        "tremolo wobble should clearly exceed steady (trem="
            + wobbleTrem
            + " steady="
            + wobbleSteady
            + ")");
  }
}
