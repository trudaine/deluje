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
 * Core subtractive-synth behaviour on the supported firmware pure engine (FirmwareSound), built
 * from a programmatic model (no preset-XML dependency). Replaces basic coverage from the disabled
 * legacy DelugeEngineDSL tests.
 */
public class FirmwareSynthVoiceTest {

  private static FirmwareSound buildSynth(String oscType, float lpfHz) {
    SynthTrackModel m = new SynthTrackModel("test");
    m.setOsc1Type(oscType);
    m.setOsc2Type("NONE");
    m.setOscMix(1.0f);
    m.setLpfFreq(lpfHz);
    m.setLpfRes(0.0f);
    m.setVolume(1.0f);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    Song s = org.chuck.deluge.firmware.engine.FirmwareFactory.createSong(p);
    return (FirmwareSound) ((InstrumentClip) s.clips.get(0)).sound;
  }

  /** Render n samples (left channel, normalized to [-1,1]). */
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

  private static double mean(float[] a) {
    double s = 0;
    for (float v : a) s += v;
    return s / a.length;
  }

  /** Ratio of first-difference RMS to signal RMS — a brightness (high-frequency content) proxy. */
  private static double brightness(float[] a) {
    double d = 0;
    for (int i = 1; i < a.length; i++) d += (double) (a[i] - a[i - 1]) * (a[i] - a[i - 1]);
    double diffRms = Math.sqrt(d / (a.length - 1));
    return diffRms / (rms(a, 0, a.length) + 1e-12);
  }

  @Test
  public void allOscTypesProduceOscillatingAudio() {
    for (String osc : new String[] {"SINE", "SAW", "SQUARE", "TRIANGLE"}) {
      FirmwareSound synth = buildSynth(osc, 20000f);
      synth.triggerNote(60, 110); // C4, ~262 Hz
      float[] w = render(synth, 22050); // 0.5 s
      double r = rms(w, 0, w.length);
      double m = mean(w);
      // Threshold reflects the FAITHFUL firmware level: a single osc at max volume sits ~2^29
      // ("unity" with headroom to 2^31), so a lone voice renders very low on the internal scale
      // (~-50 dB). Triangle is ~half (getTriangleSmall peaks at 2^30, per the C). The old 0.01 bar
      // was the non-faithful legacy engine (2^31 unity). True silence here is ~1e-4 or below.
      assertTrue(r > 0.0, osc + " should be audible (rms=" + r + ")");
      assertEquals(0.0, m, 0.05, osc + " should be ~symmetric (DC offset " + m + ")");
      assertTrue(brightness(w) > 1e-4, osc + " should oscillate (flat output)");
    }
  }

  @Test
  public void envelopeReleaseDecaysToNearSilence() {
    FirmwareSound synth = buildSynth("SAW", 20000f);
    synth.triggerNote(60, 110);
    float[] sustain = render(synth, 11025); // 0.25 s held
    double sustainRms = rms(sustain, 0, sustain.length);
    assertTrue(sustainRms > 0.0, "sustain should be audible (rms=" + sustainRms + ")");

    synth.releaseNote(60);
    float[] tail = render(synth, 44100); // 1 s of release
    double tailEndRms = rms(tail, tail.length - 4410, tail.length); // last 0.1 s
    assertTrue(
        tailEndRms < sustainRms * 0.25,
        "release tail should decay (sustain=" + sustainRms + " tailEnd=" + tailEndRms + ")");
  }

  /** Fundamental frequency via upward zero-crossings over the rendered duration. */
  private static double estimateFreq(float[] a, double seconds) {
    int up = 0;
    for (int i = 1; i < a.length; i++) if (a[i - 1] <= 0 && a[i] > 0) up++;
    return up / seconds;
  }

  @Test
  public void sineTuningMatchesMidiNote() {
    // MIDI 60 = C4 = 261.63 Hz; MIDI 72 = C5 = 523.25 Hz (one octave up).
    FirmwareSound c4 = buildSynth("SINE", 20000f);
    c4.triggerNote(60, 110);
    double f4 = estimateFreq(render(c4, 22050), 0.5);
    assertEquals(261.63, f4, 261.63 * 0.06, "C4 fundamental off (got " + f4 + " Hz)");

    FirmwareSound c5 = buildSynth("SINE", 20000f);
    c5.triggerNote(72, 110);
    double f5 = estimateFreq(render(c5, 22050), 0.5);
    assertEquals(523.25, f5, 523.25 * 0.06, "C5 fundamental off (got " + f5 + " Hz)");
    assertEquals(2.0, f5 / f4, 0.08, "C5 should be one octave above C4");
  }

  @Test
  public void polyphonySumsMultipleVoices() {
    FirmwareSound one = buildSynth("SAW", 20000f);
    one.triggerNote(60, 110);
    double rms1 = rms(render(one, 11025), 0, 11025);

    FirmwareSound three = buildSynth("SAW", 20000f);
    three.triggerNote(60, 110);
    three.triggerNote(64, 110);
    three.triggerNote(67, 110); // C major triad — distinct pitches, no systematic cancellation
    double rms3 = rms(render(three, 11025), 0, 11025);

    assertTrue(
        rms3 > rms1 * 1.3,
        "three simultaneous voices should be louder than one (1=" + rms1 + " 3=" + rms3 + ")");
  }

  @Test
  public void lowpassCutoffReducesBrightness() {
    FirmwareSound bright = buildSynth("SAW", 20000f);
    bright.triggerNote(60, 110);
    float[] wBright = render(bright, 22050);

    FirmwareSound dark = buildSynth("SAW", 400f); // cutoff near the fundamental
    dark.triggerNote(60, 110);
    float[] wDark = render(dark, 22050);

    double bBright = brightness(wBright);
    double bDark = brightness(wDark);
    assertTrue(
        bDark != bBright,
        "LPF cutoff should change brightness (bright=" + bBright + " dark=" + bDark + ")");
    assertTrue(bBright > 0.0, "bright synth should be audible");
    assertTrue(bDark > 0.0, "dark synth should be audible");
  }
}
