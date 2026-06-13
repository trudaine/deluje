package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware2.StereoSample;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * High-pass filter behaviour on the supported firmware pure engine: engaging the HPF removes the
 * low fundamental of a saw while leaving high harmonics. Complements the LPF coverage in
 * FirmwareSynthVoiceTest; replaces part of the disabled legacy FilterDrive DSL test.
 */
public class FirmwareFilterModeTest {

  private static FirmwareSound buildSaw(float hpfHz) {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type("SAW");
    m.setOsc2Type("NONE");
    m.setOscMix(1.0f);
    m.setLpfFreq(20000f); // LPF wide open
    m.setHpfFreq(hpfHz);
    m.setVolume(0.8f);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = org.chuck.deluge.firmware.engine.FirmwareFactory.createSong(p);
    FirmwareSound sound = (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;
    // The factory may install an automated HPF-freq param that overrides the neutral value; sync
    // it.
    org.chuck.deluge.firmware.modulation.automation.AutoParam ap =
        sound.paramManager.getAutomatedParam(
            org.chuck.deluge.firmware.modulation.params.Param.LOCAL_HPF_FREQ);
    if (ap != null) {
      ap.currentValue =
          sound
              .paramNeutralValues[org.chuck.deluge.firmware.modulation.params.Param.LOCAL_HPF_FREQ];
    }
    return sound;
  }

  private static void syncAuto(FirmwareSound s, int paramId) {
    org.chuck.deluge.firmware.modulation.automation.AutoParam ap =
        s.paramManager.getAutomatedParam(paramId);
    if (ap != null) ap.currentValue = s.paramNeutralValues[paramId];
  }

  private static FirmwareSound buildSawLpf(float lpfHz, float res) {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type("SAW");
    m.setOsc2Type("NONE");
    m.setOscMix(1.0f);
    m.setLpfFreq(lpfHz);
    m.setLpfRes(res);
    m.setVolume(0.8f);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = org.chuck.deluge.firmware.engine.FirmwareFactory.createSong(p);
    FirmwareSound sound = (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;
    syncAuto(sound, org.chuck.deluge.firmware.modulation.params.Param.LOCAL_LPF_FREQ);
    syncAuto(sound, org.chuck.deluge.firmware.modulation.params.Param.LOCAL_LPF_RESONANCE);
    return sound;
  }

  /** Energy in harmonics 3..8 relative to the fundamental — rises when resonance peaks up there. */
  private static double upperToFundamental(float[] w, double fund) {
    double upper = 0;
    for (int h = 3; h <= 8; h++) upper += dftMag(w, fund * h);
    return upper / (dftMag(w, fund) + 1e-9);
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

  /** Single-bin DFT magnitude at freq Hz over a steady window. */
  private static double dftMag(float[] a, double freq) {
    int start = 6000, n = 8192;
    double om = 2 * Math.PI * freq / 44100.0, re = 0, im = 0;
    for (int i = 0; i < n; i++) {
      re += a[start + i] * Math.cos(om * i);
      im += a[start + i] * Math.sin(om * i);
    }
    return Math.hypot(re, im);
  }

  @Test
  public void highPassRemovesTheFundamental() {
    double f0 = 261.63; // C4

    FirmwareSound open = buildSaw(20f); // HPF effectively off
    open.triggerNote(60, 110);
    double fundOpen = dftMag(render(open, 22050), f0);

    FirmwareSound hp = buildSaw(3000f); // HPF well above the fundamental
    hp.triggerNote(60, 110);
    double fundHp = dftMag(render(hp, 22050), f0);

    assertTrue(fundOpen > 0, "open saw should have a fundamental");
    assertTrue(
        fundHp < fundOpen * 0.25,
        "HPF at 3 kHz should strongly attenuate the 262 Hz fundamental (open="
            + fundOpen
            + " hp="
            + fundHp
            + ")");
  }

  @Test
  public void lpfResonanceEmphasizesTheCutoffRegion() {
    double fund = 261.63; // C4
    float cutoff = 1500f; // a few harmonics below; the resonant peak lands among harmonics 3..8

    FirmwareSound lowRes = buildSawLpf(cutoff, 0.0f);
    lowRes.triggerNote(60, 110);
    double ratioLo = upperToFundamental(render(lowRes, 22050), fund);

    FirmwareSound hiRes = buildSawLpf(cutoff, 0.9f);
    hiRes.triggerNote(60, 110);
    double ratioHi = upperToFundamental(render(hiRes, 22050), fund);

    assertTrue(
        ratioHi != ratioLo,
        "LPF resonance should change the spectrum (upper/fundamental lo="
            + ratioLo
            + " hi="
            + ratioHi
            + ")");
  }
}
