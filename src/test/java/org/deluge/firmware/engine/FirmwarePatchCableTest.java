package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware.model.InstrumentClip;
import org.deluge.firmware.model.Song;
import org.deluge.firmware.modulation.patch.PatchCable;
import org.deluge.firmware.modulation.patch.PatchSource;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ClipModel;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Patch-cable modulation on the supported firmware pure engine: a source (velocity, envelope)
 * routed to a destination parameter (filter cutoff) actually modulates it. Replaces coverage from
 * the disabled legacy PatchCableModulation/MultiLfo DSL tests.
 */
public class FirmwarePatchCableTest {

  private static final int ONE = 2147483647;

  private static FirmwareSound buildSaw(float lpfHz, EnvelopeModel env1) {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type("SAW");
    m.setOsc2Type("NONE");
    m.setOscMix(1.0f);
    m.setLpfFreq(lpfHz);
    m.setLpfRes(0.0f);
    m.setVolume(0.8f);
    if (env1 != null) m.setEnv(1, env1); // ENV2 (the modulation envelope)
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = org.deluge.firmware.engine.FirmwareFactory.createSong(p);
    return (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;
  }

  private static void cable(FirmwareSound s, PatchSource from, int paramId, int amountQ31) {
    PatchCable c = new PatchCable();
    c.from = from;
    c.amount = amountQ31;
    s.paramManager.getPatchCableSet().addCable(paramId, c);
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

  /** First-difference RMS / signal RMS over [from,to) — a brightness (HF content) proxy. */
  private static double brightness(float[] a, int from, int to) {
    double d = 0;
    for (int i = from + 1; i < to; i++) d += (double) (a[i] - a[i - 1]) * (a[i] - a[i - 1]);
    double diffRms = Math.sqrt(d / Math.max(1, to - from - 1));
    return diffRms / (rms(a, from, to) + 1e-12);
  }

  @Test
  public void velocityToCutoffBrightensWithVelocity() {
    // VELOCITY -> LPF cutoff: a higher-velocity note should open the filter (brighter).
    FirmwareSound hi = buildSaw(700f, null);
    cable(hi, PatchSource.VELOCITY, Param.LOCAL_LPF_FREQ, ONE / 2);
    hi.triggerNote(60, 120);
    double bHi = brightness(render(hi, 22050), 2205, 22050); // skip attack

    FirmwareSound lo = buildSaw(700f, null);
    cable(lo, PatchSource.VELOCITY, Param.LOCAL_LPF_FREQ, ONE / 2);
    lo.triggerNote(60, 12);
    double bLo = brightness(render(lo, 22050), 2205, 22050);

    assertTrue(
        bHi > bLo * 1.1,
        "velocity->cutoff: high velocity should be brighter (hi=" + bHi + " lo=" + bLo + ")");
  }

  @Test
  public void envelopeToCutoffSweepsFilterOverTime() {
    // ENV2 -> LPF cutoff with a slow attack: the filter should open over the course of the note.
    // NOTE: this characterises the CURRENT (unipolar) envelope patch-source behaviour. See finding
    // #1 in docs/java-port-review-non-dx7-2026-06-03.md — the firmware centres the envelope source
    // (bipolar); whether this sweep should start below the base cutoff needs a hardware A/B.
    EnvelopeModel slowAttack = new EnvelopeModel(0.4f, 0.1f, 1.0f, 0.2f, "NONE", 0.0f);
    FirmwareSound s = buildSaw(1500f, slowAttack);
    cable(s, PatchSource.ENVELOPE_1, Param.LOCAL_LPF_FREQ, ONE / 2);
    s.triggerNote(60, 110);
    float[] w = render(s, 22050); // 0.5 s

    double early = brightness(w, 0, 4410); // first 0.1 s (env2 low)
    double late = brightness(w, 17640, 22050); // 0.4–0.5 s (env2 high)
    assertTrue(
        late != early,
        "env2->cutoff should change the filter over time (early=" + early + " late=" + late + ")");
  }
}
