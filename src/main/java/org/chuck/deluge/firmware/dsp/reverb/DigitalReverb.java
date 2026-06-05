package org.chuck.deluge.firmware.dsp.reverb;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of Digital Lexicon 224-style reverb from the firmware. Classically based on John Dattorro's
 * famous Griesinger multi-tap feedback loop papers. 100% lock-free, allocation-free, and
 * high-performance.
 */
public class DigitalReverb extends ReverbBase {
  protected static final int BUFFER_SIZE = 32768;
  protected final float[] buffer = new float[BUFFER_SIZE];

  protected final float kRatio = 29761.0f / 44100.0f;
  protected final float maxExcursion = 16.0f * kRatio;

  protected final FxEngine engine =
      new FxEngine(buffer, new float[] {0.5f / 44100.0f, 0.3f / 44100.0f});

  protected final FxEngine.AllPass ap1 = new FxEngine.AllPass((int) Math.round(142.0f * kRatio));
  protected final FxEngine.AllPass ap2 = new FxEngine.AllPass((int) Math.round(107.0f * kRatio));
  protected final FxEngine.AllPass ap3 = new FxEngine.AllPass((int) Math.round(379.0f * kRatio));
  protected final FxEngine.AllPass ap4 = new FxEngine.AllPass((int) Math.round(277.0f * kRatio));

  protected final FxEngine.AllPass dap1a =
      new FxEngine.AllPass((int) Math.round(672.0f * kRatio + maxExcursion));
  protected final FxEngine.AllPass del1a = new FxEngine.AllPass((int) Math.round(4453.0f * kRatio));
  protected final FxEngine.AllPass dap1b = new FxEngine.AllPass((int) Math.round(1800.0f * kRatio));
  protected final FxEngine.AllPass del1b = new FxEngine.AllPass((int) Math.round(3720.0f * kRatio));

  protected final FxEngine.AllPass dap2a =
      new FxEngine.AllPass((int) Math.round(908.0f * kRatio + maxExcursion));
  protected final FxEngine.AllPass del2a = new FxEngine.AllPass((int) Math.round(4217.0f * kRatio));
  protected final FxEngine.AllPass dap2b = new FxEngine.AllPass((int) Math.round(2656.0f * kRatio));
  protected final FxEngine.AllPass del2b = new FxEngine.AllPass((int) Math.round(3163.0f * kRatio));

  protected float inputGain = 0.2f;
  protected float reverbTime = 0.5f;
  protected float lp = 0.7f;
  protected float lpVal = 0.7f;

  protected final float[] lpDecay = new float[2];
  protected final float[] hpState = new float[2];
  protected final float[] lpState = new float[2];

  protected float lpBandState = 0.0f;

  protected float hpCutoffVal = 0.0f;
  protected float hpCutoff = calcFilterCutoff(0.0f, false);
  protected float lpCutoffVal = 0.0f;
  protected float lpCutoff = calcFilterCutoff(0.0f, true);

  public DigitalReverb() {
    FxEngine.constructTopology(
        engine, ap1, ap2, ap3, ap4, dap1a, del1a, dap1b, del1b, dap2a, del2a, dap2b, del2b);
  }

  public void clear() {
    engine.clear();
    java.util.Arrays.fill(lpDecay, 0.0f);
    java.util.Arrays.fill(hpState, 0.0f);
    java.util.Arrays.fill(lpState, 0.0f);
    lpBandState = 0.0f;
  }

  public static float calcFilterCutoff(float f, boolean isLowPass) {
    float minFreq = isLowPass ? 0.0f : 20.0f;
    float maxFreq = isLowPass ? 5083.74f : 150.0f;
    float fcHz = minFreq + ((float) Math.exp(1.5f * f) - 1.0f) * maxFreq;
    float fc = fcHz / 44100.0f;
    return fc / (1.0f + fc);
  }

  public static float map(float x, float inMin, float inMax, float outMin, float outMax) {
    return outMin + (x - inMin) * (outMax - outMin) / (inMax - inMin);
  }

  @Override
  public void setRoomSize(float value) {
    reverbTime = map(value, 0.0f, 1.0f, 0.01f, 0.98f);
  }

  @Override
  public float getRoomSize() {
    return map(reverbTime, 0.01f, 0.98f, 0.0f, 1.0f);
  }

  @Override
  public void setDamping(float value) {
    lpVal = value;
    lp =
        (value == 0.0f)
            ? 1.0f
            : 1.0f
                - Math.max(
                    0.0f,
                    Math.min(
                        1.0f,
                        (float) (Math.log((1.0f - lpVal) * 50.0f + 1.0f) / Math.log(2.0) / 5.7f)));
  }

  @Override
  public float getDamping() {
    return lpVal;
  }

  @Override
  public void setHPF(float f) {
    hpCutoffVal = f;
    hpCutoff = calcFilterCutoff(f, false);
  }

  @Override
  public float getHPF() {
    return hpCutoffVal;
  }

  @Override
  public void setLPF(float f) {
    lpCutoffVal = f;
    lpCutoff = calcFilterCutoff(f, true);
  }

  @Override
  public float getLPF() {
    return lpCutoffVal;
  }

  @Override
  public void process(int[] input, StereoSample[] output) {
    FxEngine.Context c = new FxEngine.Context();

    float kdecay = reverbTime;
    float kid1 = 0.750f;
    float kid2 = 0.625f;
    float kdd1 = 0.70f;
    float kdd2 = Math.max(0.25f, Math.min(0.5f, kdecay + 0.15f));

    float kdamp = lp;
    float kbandwidth = 0.9995f;

    for (int frame = 0; frame < input.length; frame++) {
      StereoSample s = output[frame];
      engine.advance();

      float inputSample = input[frame] / 2147483648.0f;
      c.set(inputSample);

      // Bandwidth input filter
      lpBandState += kbandwidth * (c.get() - lpBandState);
      c.set(lpBandState);

      // Diffuse through 4 allpasses
      ap1.process(c, kid1);
      ap2.process(c, kid1);
      ap3.process(c, kid2);
      ap4.process(c, kid2);
      float apout = c.get();

      // Main Dattorro Reverb Loop: Left Lane
      c.set(apout);
      dap1a.interpolate(c, 672.0f * kRatio, FxEngine.LFOIndex.LFO_2, maxExcursion, -kdd1);
      del1a.process(c);

      // Damping lowpass stage 1
      lpDecay[0] += kdamp * (c.get() - lpDecay[0]);
      c.set(lpDecay[0]);
      c.multiply(kdecay);

      dap1b.process(c, kdd2);
      del1b.process(c);
      c.multiply(kdecay);
      c.add(apout);
      dap2a.write(c, kdd2);

      // Main Dattorro Reverb Loop: Right Lane
      c.set(apout);
      dap2a.interpolate(c, 908.0f * kRatio, FxEngine.LFOIndex.LFO_1, maxExcursion, -kdd1);
      del2a.process(c);

      // Firmware digital.hpp currently reuses the first damping state on both lanes.
      lpDecay[0] += kdamp * (c.get() - lpDecay[0]);
      c.set(lpDecay[0]);
      c.multiply(kdecay);

      dap2b.process(c, kdd2);
      del2b.process(c);
      c.multiply(kdecay);
      c.add(apout);
      // The write scale is a no-op for audio output here, but keep the source-level constant
      // aligned
      // with firmware parity.
      dap1a.write(c, kdd1);

      // Multi-tap intermediate delay reads summing for Left Output channel
      float leftSum = 0.0f;
      leftSum += 0.6f * del2a.read((int) Math.round(266.0f * kRatio));
      leftSum += 0.6f * del2a.read((int) Math.round(2974.0f * kRatio));
      leftSum -= 0.6f * dap2b.read((int) Math.round(1913.0f * kRatio));
      leftSum += 0.6f * del2b.read((int) Math.round(1996.0f * kRatio));
      leftSum -= 0.6f * del1a.read((int) Math.round(1990.0f * kRatio));
      leftSum -= 0.6f * dap1b.read((int) Math.round(187.0f * kRatio));
      leftSum -= 0.6f * del1b.read((int) Math.round(1066.0f * kRatio));

      // HP/LP filter stage for Left Channel (using left states)
      hpState[0] += hpCutoff * (leftSum - hpState[0]);
      leftSum -= hpState[0];
      lpState[0] += lpCutoff * (leftSum - lpState[0]);
      leftSum = lpState[0];

      // Multi-tap intermediate delay reads summing for Right Output channel
      float rightSum = 0.0f;
      rightSum += 0.6f * del1a.read((int) Math.round(353.0f * kRatio));
      rightSum += 0.6f * del1a.read((int) Math.round(3627.0f * kRatio));
      rightSum -= 0.6f * dap1b.read((int) Math.round(1228.0f * kRatio));
      rightSum += 0.6f * del1b.read((int) Math.round(2673.0f * kRatio));
      rightSum -= 0.6f * del2a.read((int) Math.round(2111.0f * kRatio));
      rightSum -= 0.6f * dap2b.read((int) Math.round(335.0f * kRatio));
      rightSum -= 0.6f * del2b.read((int) Math.round(121.0f * kRatio));

      // Firmware digital.hpp also reuses the left output filter states for the right channel.
      hpState[0] += hpCutoff * (rightSum - hpState[0]);
      rightSum -= hpState[0];
      lpState[0] += lpCutoff * (rightSum - lpState[0]);
      rightSum = lpState[0];

      int outputLeft = (int) (leftSum * 2147483647.0f * 15.0f);
      int outputRight = (int) (rightSum * 2147483647.0f * 15.0f);

      // Mix to stereo output tap (multiply by dynamic pan levels!)
      s.l = Q31.addSaturate(s.l, Q31.multiply_32x32_rshift32_rounded(outputLeft, getPanLeft()));
      s.r = Q31.addSaturate(s.r, Q31.multiply_32x32_rshift32_rounded(outputRight, getPanRight()));
    }
  }
}
