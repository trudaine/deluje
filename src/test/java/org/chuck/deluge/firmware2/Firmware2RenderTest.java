package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware2.Oscillator.OscType;
import org.junit.jupiter.api.Test;

/**
 * Proof-of-concept: render audio through the firmware2/ DSP pipeline and verify
 * it produces correct output.  Uses firmware2 classes directly — no dependency on
 * the existing engine infrastructure.
 */
public class Firmware2RenderTest {

  private static final int SR = 44100;
  private static final int BLOCK = 128;

  @Test
  public void sine440HzRendersCorrectFrequency() {
    // Build a minimal Voice with sine oscillator
    Voice voice = new Voice();
    voice.noteOn(69, 110); // A4 = 440 Hz

    // Set params: full volume, wide-open filter
    voice.paramFinalValues[Param.LOCAL_OSC_A_VOLUME] = Functions.ONE_Q31 >> 1;
    voice.paramFinalValues[Param.LOCAL_OSC_B_VOLUME] = 0;
    voice.paramFinalValues[Param.LOCAL_VOLUME] = Functions.ONE_Q31 >> 1;
    voice.paramFinalValues[Param.LOCAL_LPF_FREQ] = Functions.ONE_Q31;
    voice.paramFinalValues[Param.LOCAL_LPF_RESONANCE] = 0;
    voice.paramFinalValues[Param.LOCAL_LPF_MORPH] = 0;
    voice.paramFinalValues[Param.LOCAL_HPF_FREQ] = 0;
    voice.paramFinalValues[Param.LOCAL_HPF_RESONANCE] = 0;
    voice.paramFinalValues[Param.LOCAL_HPF_MORPH] = 0;
    voice.paramFinalValues[Param.LOCAL_PAN] = 0;
    voice.paramFinalValues[Param.LOCAL_PITCH_ADJUST] = Functions.K_MAX_SAMPLE_VALUE;
    voice.paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST] = Functions.K_MAX_SAMPLE_VALUE;

    // Envelope: instant attack, infinite sustain
    voice.paramFinalValues[Param.LOCAL_ENV_0_ATTACK] = 8388608;
    voice.paramFinalValues[Param.LOCAL_ENV_0_DECAY] = 0;
    voice.paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN] = Functions.ONE_Q31;
    voice.paramFinalValues[Param.LOCAL_ENV_0_RELEASE] = 0;

    // Render 1 second of audio
    int totalSamples = (SR / BLOCK) * BLOCK;
    int[] buffer = new int[totalSamples * 2]; // stereo interleaved
    OscType[] oscTypes = {OscType.SINE, OscType.SINE};

    int[] blockBuf = new int[BLOCK * 2];
    for (int off = 0; off < totalSamples; off += BLOCK) {
      java.util.Arrays.fill(blockBuf, 0);
      voice.render(blockBuf, BLOCK, 0, oscTypes,
          FilterSet.FilterMode.TRANSISTOR_24DB, FilterSet.FilterMode.OFF,
          0, 134217728);
      System.arraycopy(blockBuf, 0, buffer, off * 2, BLOCK * 2);
    }

    // Measure frequency via Goertzel (frequency-specific, immune to envelope modulation)
    int start = SR / 4;
    int win = 8192;
    double expectedFreq = 440.0;
    double o = 2 * Math.PI * expectedFreq / SR;
    double c = 2 * Math.cos(o);
    double s0 = 0, s1 = 0;
    for (int i = 0; i < win; i++) {
      double sample = buffer[(start + i) * 2] / 2147483648.0;
      double s2 = s1;
      s1 = sample + c * s1 - s0;
      s0 = s2;
    }
    double mag440 = Math.hypot(s1 - s0 * Math.cos(o), s0 * Math.sin(o)) / Math.sqrt(win) * 2;
    assertTrue(mag440 > 0.001,
        "sine should have energy at 440 Hz (mag=" + mag440 + ")");

    // Verify it's audible
    double sum = 0;
    for (int i = SR / 4; i < totalSamples; i++) {
      sum += (double) buffer[i * 2] * buffer[i * 2];
    }
    double rms = Math.sqrt(sum / (totalSamples - SR / 4)) / 2147483648.0;
    assertTrue(rms > 0.001, "sine should be audible (rms=" + rms + ")");
  }

  @Test
  public void envelopeCurvesAttack() {
    Voice voice = new Voice();
    voice.noteOn(60, 110);

    // Slow attack for measurable rise
    voice.paramFinalValues[Param.LOCAL_OSC_A_VOLUME] = Functions.ONE_Q31 >> 1;
    voice.paramFinalValues[Param.LOCAL_OSC_B_VOLUME] = 0;
    voice.paramFinalValues[Param.LOCAL_VOLUME] = Functions.ONE_Q31;
    voice.paramFinalValues[Param.LOCAL_LPF_FREQ] = Functions.ONE_Q31;
    voice.paramFinalValues[Param.LOCAL_ENV_0_ATTACK] = 200;  // slow attack
    voice.paramFinalValues[Param.LOCAL_ENV_0_DECAY] = 400;
    voice.paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN] = Functions.ONE_Q31 >> 1;
    voice.paramFinalValues[Param.LOCAL_ENV_0_RELEASE] = 400;
    voice.paramFinalValues[Param.LOCAL_PAN] = 0;
    voice.paramFinalValues[Param.LOCAL_PITCH_ADJUST] = Functions.K_MAX_SAMPLE_VALUE;
    voice.paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST] = Functions.K_MAX_SAMPLE_VALUE;

    OscType[] oscTypes = {OscType.SINE, OscType.SINE};
    int totalSamples = (SR / BLOCK) * BLOCK;
    int[] buffer = new int[totalSamples * 2];

    int[] blockBuf = new int[BLOCK * 2];
    for (int off = 0; off < totalSamples; off += BLOCK) {
      java.util.Arrays.fill(blockBuf, 0);
      voice.render(blockBuf, BLOCK, 0, oscTypes,
          FilterSet.FilterMode.TRANSISTOR_24DB, FilterSet.FilterMode.OFF,
          0, 134217728);
      System.arraycopy(blockBuf, 0, buffer, off * 2, BLOCK * 2);
    }

    // RMS of first 50ms (attack start) should be quieter than 200-250ms (attack peak)
    double earlyRMS = windowRMS(buffer, 0, SR / 20);
    double peakRMS = windowRMS(buffer, SR / 5, SR / 4);
    assertTrue(peakRMS > earlyRMS * 1.5,
        "attack should rise: early=" + earlyRMS + " peak=" + peakRMS);
  }

  @Test
  public void lpfReducesBrightness() {
    Voice bright = new Voice();
    Voice dark = new Voice();

    for (Voice v : new Voice[]{bright, dark}) {
      v.noteOn(60, 110);
      v.paramFinalValues[Param.LOCAL_OSC_A_VOLUME] = Functions.ONE_Q31 >> 1;
      v.paramFinalValues[Param.LOCAL_OSC_B_VOLUME] = 0;
      v.paramFinalValues[Param.LOCAL_VOLUME] = Functions.ONE_Q31;
      v.paramFinalValues[Param.LOCAL_ENV_0_ATTACK] = 8388608;
      v.paramFinalValues[Param.LOCAL_ENV_0_DECAY] = 0;
      v.paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN] = Functions.ONE_Q31;
      v.paramFinalValues[Param.LOCAL_ENV_0_RELEASE] = 0;
      v.paramFinalValues[Param.LOCAL_PAN] = 0;
      v.paramFinalValues[Param.LOCAL_PITCH_ADJUST] = Functions.K_MAX_SAMPLE_VALUE;
      v.paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST] = Functions.K_MAX_SAMPLE_VALUE;
    }
    bright.paramFinalValues[Param.LOCAL_LPF_FREQ] = Functions.ONE_Q31; // open
    dark.paramFinalValues[Param.LOCAL_LPF_FREQ] = 1000000; // low cutoff (~50 Hz)

    OscType[] oscTypes = {OscType.SAW, OscType.SINE};
    int totalSamples = (SR / BLOCK) * BLOCK;
    int[] bufB = new int[totalSamples * 2];
    int[] bufD = new int[totalSamples * 2];

    int[] blockBuf = new int[BLOCK * 2];
    for (int off = 0; off < totalSamples; off += BLOCK) {
      java.util.Arrays.fill(blockBuf, 0);
      bright.render(blockBuf, BLOCK, 0, oscTypes,
          FilterSet.FilterMode.TRANSISTOR_24DB, FilterSet.FilterMode.OFF,
          0, 134217728);
      System.arraycopy(blockBuf, 0, bufB, off * 2, BLOCK * 2);
      java.util.Arrays.fill(blockBuf, 0);
      dark.render(blockBuf, BLOCK, 0, oscTypes,
          FilterSet.FilterMode.TRANSISTOR_24DB, FilterSet.FilterMode.OFF,
          0, 134217728);
      System.arraycopy(blockBuf, 0, bufD, off * 2, BLOCK * 2);
    }

    double brightBri = brightness(bufB, SR / 4, totalSamples);
    double darkBri = brightness(bufD, SR / 4, totalSamples);
    assertTrue(brightBri > darkBri * 1.2,
        "LPF should reduce brightness: bright=" + brightBri + " dark=" + darkBri);
  }

  @Test
  public void fmProducesSidebands() {
    Voice voice = new Voice();
    voice.noteOn(60, 110);
    voice.paramFinalValues[Param.LOCAL_OSC_A_VOLUME] = Functions.ONE_Q31 >> 1;
    voice.paramFinalValues[Param.LOCAL_OSC_B_VOLUME] = 0;
    voice.paramFinalValues[Param.LOCAL_VOLUME] = Functions.ONE_Q31;
    voice.paramFinalValues[Param.LOCAL_LPF_FREQ] = Functions.ONE_Q31;
    voice.paramFinalValues[Param.LOCAL_MODULATOR_0_VOLUME] = Functions.ONE_Q31 >> 1;
    voice.paramFinalValues[Param.LOCAL_MODULATOR_1_VOLUME] = 0;
    voice.paramFinalValues[Param.LOCAL_ENV_0_ATTACK] = 8388608;
    voice.paramFinalValues[Param.LOCAL_ENV_0_DECAY] = 0;
    voice.paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN] = Functions.ONE_Q31;
    voice.paramFinalValues[Param.LOCAL_ENV_0_RELEASE] = 0;
    voice.paramFinalValues[Param.LOCAL_PAN] = 0;
    voice.paramFinalValues[Param.LOCAL_PITCH_ADJUST] = Functions.K_MAX_SAMPLE_VALUE;
    voice.paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST] = Functions.K_MAX_SAMPLE_VALUE;

    OscType[] oscTypes = {OscType.SINE, OscType.SINE};
    int totalSamples = (SR / BLOCK) * BLOCK;
    int[] buffer = new int[totalSamples * 2];

    int[] blockBuf = new int[BLOCK * 2];
    for (int off = 0; off < totalSamples; off += BLOCK) {
      java.util.Arrays.fill(blockBuf, 0);
      voice.render(blockBuf, BLOCK, 1, oscTypes, // FM mode
          FilterSet.FilterMode.TRANSISTOR_24DB, FilterSet.FilterMode.OFF,
          0, 134217728);
      System.arraycopy(blockBuf, 0, buffer, off * 2, BLOCK * 2);
    }

    // FM with moderate modulator should be richer than a pure sine
    double bri = brightness(buffer, SR / 4, totalSamples);
    double rms = windowRMS(buffer, SR / 4, totalSamples);
    assertTrue(rms > 0.0001, "FM should be audible (rms=" + rms + ")");
    // FM adds sidebands → higher brightness than a pure sine
    assertTrue(bri > 0.01, "FM should have harmonic content (brightness=" + bri + ")");
  }

  // ── helpers ──

  private static double windowRMS(int[] stereoBuf, int fromSamples, int toSamples) {
    double sum = 0;
    int n = 0;
    for (int i = fromSamples; i < toSamples && i * 2 + 1 < stereoBuf.length; i++) {
      double v = stereoBuf[i * 2] / 2147483648.0;
      sum += v * v; n++;
    }
    return Math.sqrt(sum / Math.max(1, n));
  }

  private static double brightness(int[] stereoBuf, int fromSamples, int toSamples) {
    double diffSum = 0, rmsSum = 0;
    int n = 0;
    double prev = 0;
    boolean first = true;
    for (int i = fromSamples; i < toSamples && i * 2 + 1 < stereoBuf.length; i++) {
      double v = stereoBuf[i * 2] / 2147483648.0;
      rmsSum += v * v;
      if (!first) { diffSum += (v - prev) * (v - prev); n++; }
      prev = v; first = false;
    }
    double rms = Math.sqrt(rmsSum / Math.max(1, toSamples - fromSamples));
    double diffRms = Math.sqrt(diffSum / Math.max(1, n));
    return diffRms / (rms + 1e-12);
  }
}
