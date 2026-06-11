package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.storage.wave_table.WaveTable;
import org.chuck.deluge.firmware.storage.wave_table.WavetableGenerator;
import org.junit.jupiter.api.Test;

class WavetableOscTest {

  private WaveTable buildTwoCycleWavetable() {
    int cycleSize = 2048;
    int totalSamples = cycleSize * 2;
    float[] samples = new float[totalSamples];

    // Cycle 0: Sine wave
    for (int i = 0; i < cycleSize; i++) {
      samples[i] = (float) Math.sin(2.0 * Math.PI * i / cycleSize);
    }
    // Cycle 1: Saw-like ramp
    for (int i = 0; i < cycleSize; i++) {
      samples[cycleSize + i] = -1.0f + 2.0f * i / cycleSize;
    }

    WaveTable wt = new WaveTable();
    wt.setup(cycleSize, totalSamples);
    WavetableGenerator.generateBands(wt, samples);
    return wt;
  }

  private FirmwareSound makeWavetableSound(WaveTable wt, float waveIndex) {
    FirmwareSound s = new FirmwareSound();
    s.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
    s.oscTypes[0] = OscType.WAVETABLE;
    s.oscTypes[1] = OscType.SINE; // Osc B is quiet
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE; // off
    s.paramNeutralValues[Param.LOCAL_VOLUME] = Integer.MAX_VALUE;
    s.osc1RetriggerPhase = 0; // deterministic start phase

    // Inject the wavetable
    s.fw2Sound.waveTables[0] = wt;

    // Set wave index parameter (0.0 to 1.0 mapped to Q31)
    int waveIndexQ31 = (int) Math.round((double) waveIndex * 2147483647.0);
    s.paramNeutralValues[Param.LOCAL_OSC_A_WAVE_INDEX] = waveIndexQ31;

    return s;
  }

  private float[] render(FirmwareSound s, int blocks) {
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    eng.sounds.add(s);
    s.triggerNote(60, 100);
    float[] out = new float[blocks * 128];
    for (int b = 0; b < blocks; b++) {
      eng.renderBlock(128);
      for (int i = 0; i < 128; i++) {
        out[b * 128 + i] = eng.masterBuffer[i].l / 2147483648.0f;
      }
    }
    return out;
  }

  @Test
  void wavetableOscillatorRendersNonSilentDeterministicOutput() {
    WaveTable wt = buildTwoCycleWavetable();
    FirmwareSound s1 = makeWavetableSound(wt, 0.0f);
    FirmwareSound s2 = makeWavetableSound(wt, 0.0f);

    float[] r1 = render(s1, 10);
    float[] r2 = render(s2, 10);

    // Verify determinism
    float maxDiff = 0f;
    float maxAbsVal = 0f;
    for (int i = 0; i < r1.length; i++) {
      maxDiff = Math.max(maxDiff, Math.abs(r1[i] - r2[i]));
      maxAbsVal = Math.max(maxAbsVal, Math.abs(r1[i]));
    }

    System.out.println("Max absolute value in r1: " + maxAbsVal);
    assertEquals(0.0f, maxDiff, 1e-6, "Wavetable render should be sample-identical");
    assertTrue(maxAbsVal > 0.0001f, "Wavetable should render non-silent output");
  }

  @Test
  void modulatingWaveIndexMorphsTheWaveform() {
    WaveTable wt = buildTwoCycleWavetable();
    // Index 0.0 should be a sine wave
    float[] rSine = render(makeWavetableSound(wt, 0.0f), 10);
    // Index 1.0 should be a saw-like wave
    float[] rSaw = render(makeWavetableSound(wt, 1.0f), 10);

    // Verify they are different waveforms
    float maxDiff = 0f;
    for (int i = 0; i < rSine.length; i++) {
      maxDiff = Math.max(maxDiff, Math.abs(rSine[i] - rSaw[i]));
    }
    assertTrue(maxDiff > 0.0001f, "Waveforms at index 0.0 and 1.0 should clearly differ");
  }

  @Test
  void checkWaveTableRenderDirect() {
    WaveTable wt = buildTwoCycleWavetable();
    int[] buf = new int[128];
    // phaseIncrement = 20000000
    wt.render(buf, 0, 128, 20000000, 0, false, 0, 0, 0, 0, 0, 0);
    int nonZeroCount = 0;
    for (int v : buf) {
      if (v != 0) nonZeroCount++;
    }
    System.out.println("Direct WT render non-zero count: " + nonZeroCount);
    assertTrue(nonZeroCount > 0, "Direct wavetable rendering should yield non-zero values");
  }
}
