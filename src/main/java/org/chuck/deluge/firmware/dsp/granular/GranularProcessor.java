package org.chuck.deluge.firmware.dsp.granular;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.filter.BasicFilterComponent;
import org.chuck.deluge.firmware.util.Q31;

public class GranularProcessor {
  private static final int kModFXGrainBufferSize = 131072; // Hardware-accurate size

  public static class Grain {
    public int length;
    public int startPoint;
    public int counter;
    public int pitch; // 1024 = 1.0
    public int volScale;
    public int volScaleMax;
    public boolean rev;
    public int panVolL;
    public int panVolR;
  }

  private final StereoSample[] grainBuffer = new StereoSample[kModFXGrainBufferSize];
  private final Grain[] grains = new Grain[8];
  private final BasicFilterComponent lpfL = new BasicFilterComponent();
  private final BasicFilterComponent lpfR = new BasicFilterComponent();
  private int bufferWriteIndex = 0;

  public GranularProcessor() {
    for (int i = 0; i < kModFXGrainBufferSize; i++) grainBuffer[i] = new StereoSample();
    for (int i = 0; i < 8; i++) grains[i] = new Grain();
  }

  private int _grainSize;
  private int _grainRate;
  private int _grainVol;
  private int _grainDryVol;
  private int _grainFeedbackVol;
  private int _grainShift;
  private int _pitchRandomness;

  public void processGrainFX(
      StereoSample[] buffer,
      int grainRate,
      int grainMix,
      int grainDensity,
      int pitchRandomness,
      float tempoBPM) {
    setupGrainFX(grainRate, grainMix, grainDensity, pitchRandomness, tempoBPM);
    for (int i = 0; i < buffer.length; i++) {
      StereoSample wet = processOneGrainSample(buffer[i]);

      int wetL = Q31.multiply_32x32_rshift32(wet.l, _grainVol);
      int wetR = Q31.multiply_32x32_rshift32(wet.r, _grainVol);

      // filter slightly
      wetL = lpfL.doFilter(wetL, 1 << 29);
      wetR = lpfR.doFilter(wetR, 1 << 29);

      buffer[i].l = Q31.addSaturate(Q31.multiply_32x32_rshift32(buffer[i].l, _grainDryVol), wetL);
      buffer[i].r = Q31.addSaturate(Q31.multiply_32x32_rshift32(buffer[i].r, _grainDryVol), wetR);
    }
  }

  private int _densityKnobPos = -1;
  private int _rateKnobPos = -1;

  private void setupGrainFX(
      int grainRate, int grainMix, int grainDensity, int pitchRandomness, float tempoBPM) {
    _grainShift = 44 * 300;
    
    // ── Bit-Accurate Rate & Size Math ──
    if (_densityKnobPos != grainDensity || _rateKnobPos != grainRate) {
        _densityKnobPos = grainDensity;
        int density = ((grainDensity / 2) + (1073741824));
        _grainSize = 1760 + Q31.multiply_32x32_rshift32(_grainRate << 3, density);
    }
    
    if (_rateKnobPos != grainRate) {
        _rateKnobPos = grainRate;
        int grainRateRaw = org.chuck.deluge.firmware.util.FirmwareUtils.quickLog(grainRate);
        grainRateRaw = Math.max(0, Math.min(256, (grainRateRaw - 364249088) >> 21));
        _grainRate = ((360 * grainRateRaw >> 8) * grainRateRaw >> 8); 
        _grainRate = Math.max(1, _grainRate);
        _grainRate = (44100 << 1) / _grainRate;
    }

    _pitchRandomness = Math.abs(pitchRandomness);

    _grainVol = (int) (grainMix - 2147483648L);
    _grainVol =
        (Q31.multiply_32x32_rshift32_rounded(
                    Q31.multiply_32x32_rshift32_rounded(_grainVol, _grainVol), _grainVol)
                << 2)
            + -2147483648;
    _grainVol = Math.max(0, _grainVol);
    _grainDryVol =
        (int) Math.max(0, Math.min(2147483647L, ((2147483648L - (long) _grainVol) << 3)));
    _grainFeedbackVol = _grainVol >> 1;
  }

  private StereoSample processOneGrainSample(StereoSample currentSample) {
    if (bufferWriteIndex % _grainRate == 0) {
      setupGrainsIfNeeded();
    }

    long sumL = 0;
    long sumR = 0;
    for (Grain g : grains) {
      if (g.length > 0) {
        // Triangle window
        int vol =
            g.counter <= (g.length >> 1)
                ? g.counter * g.volScale
                : g.volScaleMax - (g.counter - (g.length >> 1)) * g.volScale;

        int delta = g.counter * (g.rev ? -1 : 1);
        if (g.pitch != 1024) {
          delta = (delta * g.pitch) >> 10;
        }

        int readPos = (g.startPoint + delta + kModFXGrainBufferSize) % kModFXGrainBufferSize;
        sumL += (long) Q31.multiply_32x32_rshift32(grainBuffer[readPos].l, vol) * g.panVolL;
        sumR += (long) Q31.multiply_32x32_rshift32(grainBuffer[readPos].r, vol) * g.panVolR;

        g.counter++;
        if (g.counter >= g.length) g.length = 0;
      }
    }

    int grainsL = (int) (sumL >> 31) << 3;
    int grainsR = (int) (sumR >> 31) << 3;

    grainBuffer[bufferWriteIndex].l =
        Q31.multiply_accumulate_32x32_rshift32_rounded(currentSample.l, grainsL, _grainFeedbackVol);
    grainBuffer[bufferWriteIndex].r =
        Q31.multiply_accumulate_32x32_rshift32_rounded(currentSample.r, grainsR, _grainFeedbackVol);

    bufferWriteIndex = (bufferWriteIndex + 1) % kModFXGrainBufferSize;

    return new StereoSample(grainsL, grainsR);
  }

  private void setupGrainsIfNeeded() {
    for (Grain g : grains) {
      if (g.length <= 0) {
        g.length = _grainSize;
        g.startPoint =
            (bufferWriteIndex + kModFXGrainBufferSize - _grainShift) % kModFXGrainBufferSize;
        g.counter = 0;
        g.rev = Math.random() < 0.3;
        g.pitch = 1024;
        g.volScale = (1 << 30) / (g.length >> 1);
        g.volScaleMax = 1 << 30;
        g.panVolL = 2147483647;
        g.panVolR = 2147483647;
        break;
      }
    }
  }
}
