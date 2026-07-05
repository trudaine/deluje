package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URISyntaxException;
import org.deluge.firmware2.GlobalSidechainBus;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.automation.AutoParam;
import org.deluge.modulation.patch.PatchCable;
import org.deluge.modulation.patch.PatchSource;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Golden-signature regression coverage for the pure firmware engine. */
public class FirmwareGoldenSignatureTest {
  @BeforeEach
  public void setUp() {
    GlobalSidechainBus.reset();
    org.deluge.firmware2.Functions.resetNoiseSeed();
    org.deluge.engine.FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Voice.testStartPhaseOverrideOsc1.set(-2);
    org.deluge.firmware2.Voice.testStartPhaseOverrideOsc2.set(-2);
  }

  private static final int ONE = 2147483647;
  private static final int SAMPLE_RATE = 44100;
  private static final String DX7_PATCH_HEX =
      "63461E1E63000000000000000000000552000E0008631E1E1E630000000000000000000003630001000700000000000000000000000000000000000001000700000000000000000000000000000000000001000700000000000000000000000000000000000001000700000000000000000000000000000000000001000763636363323232320400012300000001000118464D2042454C4C20202003";

  private static byte[] hex(String s) {
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++) {
      b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    }
    return b;
  }

  private static FirmwareSound buildSubtractive(String oscType, float lpfHz, float volume) {
    SynthTrackModel m = new SynthTrackModel("golden");
    m.setOsc1Type(oscType);
    m.setOsc2Type("NONE");
    m.setRetrigPhase(0);
    m.setMod1RetrigPhase(0);
    m.setMod2RetrigPhase(0);
    m.setOscMix(1.0f);
    m.setLpfFreq(lpfHz);
    m.setLpfRes(0.0f);
    m.setVolume(volume);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    ProjectModel s = FirmwareFactory.createSong(p);
    return (FirmwareSound) (s.getTracks().get(0).getActiveClip()).getSound();
  }

  private static FirmwareSound buildNativeFm() {
    SynthTrackModel m = new SynthTrackModel("golden-fm");
    m.setOsc1Type("SINE");
    m.setOsc2Type("SINE");
    m.setRetrigPhase(0);
    m.setMod1RetrigPhase(0);
    m.setMod2RetrigPhase(0);
    m.setLpfFreq(20000f);
    m.setVolume(0.8f);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    ProjectModel s = FirmwareFactory.createSong(p);
    FirmwareSound sound = (FirmwareSound) (s.getTracks().get(0).getActiveClip()).getSound();
    sound.setSynthMode(FirmwareSound.SynthMode.FM);
    sound.fmRatio1 = 2.0f;
    sound.fmModulatorAmountBase[0] = ONE / 4;
    return sound;
  }

  private static FirmwareSound buildLfoTremolo() {
    FirmwareSound sound = buildSubtractive("SINE", 20000f, 0.8f);
    int fiveHz = (int) (5.0 * 4294967296.0 / SAMPLE_RATE);
    sound.paramNeutralValues[Param.LOCAL_LFO_LOCAL_FREQ_1] = fiveHz;
    AutoParam rateAp = sound.paramManager.getAutomatedParam(Param.LOCAL_LFO_LOCAL_FREQ_1);
    if (rateAp != null) {
      rateAp.currentValue = fiveHz;
    }
    sound.lfoWaveforms[1] = org.deluge.firmware2.Lfo.LfoType.SINE;
    PatchCable cable = new PatchCable();
    cable.from = PatchSource.LFO_LOCAL_1;
    cable.amount = ONE / 2;
    sound.paramManager.getPatchCableSet().addCable(Param.LOCAL_VOLUME, cable);
    return sound;
  }

  private static FirmwareSound buildEnvelopeShape() {
    SynthTrackModel m = new SynthTrackModel("env-shape");
    m.setOsc1Type("SINE");
    m.setOsc2Type("NONE");
    m.setRetrigPhase(0);
    m.setMod1RetrigPhase(0);
    m.setMod2RetrigPhase(0);
    m.setOscMix(1.0f);
    m.setLpfFreq(20000f);
    m.setLpfRes(0.0f);
    m.setVolume(1.0f);
    m.setEnv(0, new org.deluge.model.EnvelopeModel(0.01f, 0.1f, 0.20f, 0.2f, "NONE", 0.0f));
    m.getRawKnobs().setEnvRateKnobsQ31(0, 200000000, -1073741824, -1073741824);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    ProjectModel s = FirmwareFactory.createSong(p);
    return (FirmwareSound) (s.getTracks().get(0).getActiveClip()).getSound();
  }

  private static FirmwareSound buildRingMod() {
    SynthTrackModel model = new SynthTrackModel("ringmod");
    model.setOsc1Type("SINE");
    model.setOsc2Type("SINE");
    model.setRetrigPhase(0);
    model.setMod1RetrigPhase(0);
    model.setMod2RetrigPhase(0);
    model.setOscAVolume(1.0f);
    model.setOscBVolume(1.0f);
    model.setOsc2Transpose(0);
    model.setOsc2Cents(0);
    model.setVolume(1.0f);
    model.setLpfFreq(20000f);
    model.setLpfRes(0.0f);
    model.setSynthMode(2);
    model.addClip(new ClipModel("c", 8, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(model);
    ProjectModel song = FirmwareFactory.createSong(project);
    return (FirmwareSound) (song.getTracks().get(0).getActiveClip()).getSound();
  }

  private static FirmwareSound buildDx7() {
    FirmwareSound sound = new FirmwareSound();
    sound.dx7Patch = hex(DX7_PATCH_HEX);
    return sound;
  }

  private static FirmwareSound buildXmlBasicFm() throws Exception {
    File file = resourceFile("/fidelity/049 Basic FM.XML");
    SynthTrackModel model = DelugeXmlParser.parseSynth(file);
    model.addClip(new ClipModel("c", 8, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(model);
    ProjectModel song = FirmwareFactory.createSong(project);
    return (FirmwareSound) (song.getTracks().get(0).getActiveClip()).getSound();
  }

  private static File resourceFile(String path) throws URISyntaxException {
    var url = FirmwareGoldenSignatureTest.class.getResource(path);
    assertNotNull(url, "missing test resource " + path);
    return new File(url.toURI());
  }

  private static float[] render(
      FirmwareSound sound, int note, int velocity, int total, int releaseAt) {
    float[] out = new float[total];
    StereoSample[] block = new StereoSample[128];
    for (int i = 0; i < 128; i++) {
      block[i] = new StereoSample();
    }
    sound.triggerNote(note, velocity);
    boolean released = false;
    for (int off = 0; off < total; off += 128) {
      if (!released && releaseAt >= 0 && off >= releaseAt) {
        sound.releaseNote(note, -1);
        released = true;
      }
      for (int i = 0; i < 128; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }
      sound.renderOutput(block, 128, null);
      for (int i = 0; i < 128 && off + i < total; i++) {
        out[off + i] = block[i].l / 2147483648.0f;
      }
    }
    return out;
  }

  private static double peak(float[] samples, int from, int to) {
    double max = 0;
    for (int i = from; i < to; i++) {
      max = Math.max(max, Math.abs(samples[i]));
    }
    return max;
  }

  private static double rms(float[] samples, int from, int to) {
    double sum = 0;
    for (int i = from; i < to; i++) {
      sum += (double) samples[i] * samples[i];
    }
    return Math.sqrt(sum / Math.max(1, to - from));
  }

  private static double brightness(float[] samples, int from, int to) {
    double diff = 0;
    for (int i = from + 1; i < to; i++) {
      double d = samples[i] - samples[i - 1];
      diff += d * d;
    }
    return Math.sqrt(diff / Math.max(1, to - from - 1)) / (rms(samples, from, to) + 1e-12);
  }

  private static double goertzelMagnitude(float[] samples, int from, int to, double freq) {
    int n = to - from;
    double omega = 2.0 * Math.PI * freq / SAMPLE_RATE;
    double coeff = 2.0 * Math.cos(omega);
    double s0;
    double s1 = 0;
    double s2 = 0;
    for (int i = from; i < to; i++) {
      s0 = samples[i] + coeff * s1 - s2;
      s2 = s1;
      s1 = s0;
    }
    double real = s1 - s2 * Math.cos(omega);
    double imag = s2 * Math.sin(omega);
    return Math.sqrt(real * real + imag * imag) / Math.max(1, n);
  }

  private static double fundamental(float[] samples, int start, int n) {
    int minLag = SAMPLE_RATE / 800;
    int maxLag = SAMPLE_RATE / 80;
    double[] ac = new double[maxLag + 1];
    double best = 0;
    for (int lag = minLag; lag <= maxLag; lag++) {
      double sum = 0;
      for (int i = 0; i < n; i++) {
        sum += (double) samples[start + i] * samples[start + i + lag];
      }
      ac[lag] = sum;
      best = Math.max(best, sum);
    }
    for (int lag = minLag; lag <= maxLag; lag++) {
      if (ac[lag] >= 0.85 * best) {
        return (double) SAMPLE_RATE / lag;
      }
    }
    return 0;
  }

  private static double rmsWobble(float[] samples, int windowSize) {
    double min = Double.MAX_VALUE;
    double max = -Double.MAX_VALUE;
    double sum = 0;
    int count = 0;
    int skip = 8820;
    for (int start = skip; start + windowSize <= samples.length; start += windowSize) {
      double value = rms(samples, start, start + windowSize);
      min = Math.min(min, value);
      max = Math.max(max, value);
      sum += value;
      count++;
    }
    return (max - min) / (sum / Math.max(1, count) + 1e-12);
  }

  private static void assertClose(
      String label,
      double expected,
      double actual,
      double relativeTolerance,
      double absoluteTolerance) {
    double tolerance = Math.max(Math.abs(expected) * relativeTolerance, absoluteTolerance);
    assertEquals(
        expected, actual, tolerance, label + " expected=" + expected + " actual=" + actual);
  }

  @Test
  public void sawFilterSignaturesStayStable() {
    float[] bright = render(buildSubtractive("SAW", 20000f, 1.0f), 60, 110, 22050, -1);
    float[] dark = render(buildSubtractive("SAW", 400f, 1.0f), 60, 110, 22050, -1);
    int from = 4096;
    int to = 22050;
    double f0 = fundamental(bright, from, 8192);
    double brightPeak = peak(bright, from, to);
    double brightRms = rms(bright, from, to);
    double brightBrightness = brightness(bright, from, to);
    double brightH1 = goertzelMagnitude(bright, from, to, f0);
    double brightH5 = goertzelMagnitude(bright, from, to, f0 * 5.0);
    double darkPeak = peak(dark, from, to);
    double darkRms = rms(dark, from, to);
    double darkBrightness = brightness(dark, from, to);
    double darkH1 = goertzelMagnitude(dark, from, to, f0);
    double darkH5 = goertzelMagnitude(dark, from, to, f0 * 5.0);

    assertClose("saw bright peak", 0.032189954, brightPeak, 0.30, 0.05);
    assertClose("saw bright rms", 0.014264459, brightRms, 0.30, 0.05);
    assertClose("saw bright brightness", 0.221090962, brightBrightness, 0.30, 0.05);
    assertClose("saw bright h1", 0.000086312, brightH1, 0.30, 0.05);
    assertClose("saw bright h5", 0.000013305, brightH5, 0.30, 0.05);

    assertClose("saw dark peak", 0.021431219, darkPeak, 0.30, 0.05);
    assertClose("saw dark rms", 0.010708479, darkRms, 0.30, 0.05);
    assertClose("saw dark brightness", 0.050030530, darkBrightness, 0.30, 0.05);
    assertClose("saw dark h1", 0.000085476, darkH1, 0.30, 0.05);
    assertClose("saw dark h5", 0.000004374, darkH5, 0.30, 0.05);
    assertTrue(brightBrightness > darkBrightness * 2.0, "open LPF should stay much brighter");
  }

  @Test
  public void nativeFmSignatureStaysStable() {
    float[] fm = render(buildNativeFm(), 60, 110, 22050, -1);
    int from = 4096;
    int to = 22050;
    double f0 = fundamental(fm, from, 8192);
    double refF0 = 130.860534;
    double peak = peak(fm, from, to);
    double rms = rms(fm, from, to);
    double brightness = brightness(fm, from, to);
    double h1 = goertzelMagnitude(fm, from, to, refF0);

    assertClose("fm peak", 0.085970670, peak, 0.30, 0.05);
    assertClose("fm rms", 0.056966717, rms, 0.30, 0.05);
    assertClose("fm brightness", 0.513303217, brightness, 0.30, 0.05);
    assertTrue(h1 > 0.000001, "fm carrier bin should stay clearly present");
    assertClose("fm f0", 262.500000000, f0, 0.30, 0.05);
  }

  @Test
  public void lfoTremoloSignatureStaysStable() {
    float[] tremolo = render(buildLfoTremolo(), 60, 100, 44100, -1);
    int from = 4096;
    int to = 44100;
    double peak = peak(tremolo, from, to);
    double rms = rms(tremolo, from, to);
    double brightness = brightness(tremolo, from, to);
    double wobble = rmsWobble(tremolo, 2205);

    // Re-baselined 2026-07-04: sine osc amplitude undoubling (see envelopeShape note).
    assertClose("lfo tremolo peak", 0.051292821764945984, peak, 0.30, 0.05);
    assertClose("lfo tremolo rms", 0.023192325, rms, 0.30, 0.05);
    assertClose("lfo tremolo brightness", 0.037281974, brightness, 0.30, 0.05);
    // Re-baselined after the C-faithful volume-curve-neutral fix (Patcher uses
    // getParamNeutralValue,
    // not the 0 center knob): the voice is now correctly audible, so the tremolo depth is fuller.
    assertClose("lfo tremolo wobble", 1.856690683, wobble, 0.30, 0.05);
    assertTrue(wobble > 1.0, "tremolo should stay obviously modulated");
  }

  @Test
  public void envelopeShapeSignatureStaysStable() {
    float[] env = render(buildEnvelopeShape(), 60, 110, 176400, 110250);
    double attackEarly = rms(env, 0, 512);
    double attackPeak = rms(env, 1536, 2048);
    double decayBody = rms(env, 8192, 12288);
    double sustain = rms(env, 88200, 92610);
    double releaseStart = rms(env, 110250, 114660);
    double releaseMid = rms(env, 123480, 127890);
    double releaseTailPeak = peak(env, 145530, 149940);
    // Re-baselined 2026-07-04: sine osc no longer doubles its amplitude (C oscillator.cpp:147-151
    // jumps past the <<1 at :471-472), so the sine-based fixtures halved.
    assertClose("env attack early", 0.009501531637691943, attackEarly, 0.10, 0.0005);
    assertClose("env attack peak", 0.033917754118354924, attackPeak, 0.10, 0.0005);
    assertClose("env decay body", 0.06317444278703321 / 2, decayBody, 0.10, 0.0005);
    assertClose("env sustain", 0.03959552466236948 / 2, sustain, 0.10, 0.0005);
    assertClose("env release start", 0.029970938478750042 / 2, releaseStart, 0.10, 0.0005);
    assertClose("env release mid", 0.0041317333781902554 / 2, releaseMid, 0.10, 0.0005);
    assertTrue(
        attackPeak > attackEarly * 1.5, "attack should rise clearly above its opening level");
    assertTrue(decayBody > sustain * 1.5, "decay body should stay well above sustain");
    assertTrue(releaseMid < releaseStart * 0.5, "release should decay steeply after note-off");
    assertTrue(releaseTailPeak < 0.001, "release tail should approach silence");
  }

  @Test
  public void ringModAndDx7SignaturesStayStable() {
    float[] ring = render(buildRingMod(), 60, 100, 12000, -1);
    float[] dx7 = render(buildDx7(), 60, 100, 12000, -1);
    int from = 4096;
    int to = 12000;
    double ringPeak = peak(ring, from, to);
    double ringRms = rms(ring, from, to);
    double ringBrightness = brightness(ring, from, to);
    double dx7Peak = peak(dx7, from, to);
    double dx7Rms = rms(dx7, from, to);
    double dx7Brightness = brightness(dx7, from, to);
    double dx7H1 = goertzelMagnitude(dx7, from, to, 261.625565);
    double dx7H3 = goertzelMagnitude(dx7, from, to, 261.625565 * 3.0);
    assertClose("ring peak", 0.015608668, ringPeak, 0.10, 0.0005);
    assertClose("ring rms", 0.009261414, ringRms, 0.10, 0.0005);
    assertClose("ring brightness", 0.074440891, ringBrightness, 0.10, 0.0005);

    // Re-baselined 2026-07-05: the C filter-engagement bypass (sound.cpp:2506-2519) stops the
    // ring fixture's max-cutoff ladder from consuming per-sample noise draws, shifting the
    // shared CONG stream position for this render (the DX7 random detune/phases differ).
    assertClose("dx7 peak", 0.019105032086372375, dx7Peak, 0.10, 0.0005);
    assertClose("dx7 rms", 0.013321079207954802, dx7Rms, 0.10, 0.0005);
    // Updated 2026-06-28: DX7 pitch-envelope rates/levels-swap fix (Dx7Voice.PitchEnv) corrected a
    // spurious +4-octave offset on neutral pitch envelopes, shifting DX7 brightness.
    assertClose("dx7 brightness", 0.03731857465320066, dx7Brightness, 0.10, 0.05);
    assertClose("dx7 h1", 0.009412460496958971, dx7H1, 0.10, 0.0005);
    assertClose("dx7 h3", 0.000026857, dx7H3, 0.10, 0.0005);
    assertTrue(dx7H3 > 0.000001, "dx7 patch should stay richer than a pure sine");
  }

  @Test
  public void basicFmXmlSignatureStaysStable() throws Exception {
    float[] xmlFm = render(buildXmlBasicFm(), 60, 110, 44100, 26460);
    int from = 4096;
    int to = 26000;
    double f0 = fundamental(xmlFm, from, 8192);
    double refF0 = 135.692308;
    double h1 = goertzelMagnitude(xmlFm, from, to, refF0);
    double h3 = goertzelMagnitude(xmlFm, from, to, refF0 * 3.0);
    double h5 = goertzelMagnitude(xmlFm, from, to, refF0 * 5.0);
    double peak = peak(xmlFm, from, to);
    double rms = rms(xmlFm, from, to);
    double brightness = brightness(xmlFm, from, to);
    assertClose("049 peak", 0.032087833, peak, 0.10, 0.0005);
    assertClose("049 rms", 0.017439031, rms, 0.10, 0.0005);
    assertClose("049 brightness", 0.037263413, brightness, 0.10, 0.0005);
    assertClose("049 h1", 0.000106584, h1, 0.10, 0.0005);
    assertClose("049 h3", 0.000031248, h3, 0.10, 0.0005);
    assertClose("049 h5", 0.000008095, h5, 0.10, 0.0005);
    assertClose("049 f0", 277.358490566, f0, 0.10, 0.05);
    assertTrue(h3 > 1e-6, "049 Basic FM should have active FM sidebands");
  }
}
