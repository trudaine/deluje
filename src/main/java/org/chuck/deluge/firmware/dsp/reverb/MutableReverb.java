package org.chuck.deluge.firmware.dsp.reverb;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of Mutable space reverb from the firmware. Classically based on Emilie Gillet's
 * Clouds/Plates ambient space diffuser Dattorro loop. 100% lock-free, allocation-free, and
 * high-performance.
 */
public class MutableReverb extends ReverbBase {
  protected static final int BUFFER_SIZE = 32768;
  protected final float[] buffer = new float[BUFFER_SIZE];

  protected final FxEngine engine =
      new FxEngine(buffer, new float[] {0.5f / 44100.0f, 0.3f / 44100.0f});

  protected final FxEngine.AllPass ap1 = new FxEngine.AllPass(150);
  protected final FxEngine.AllPass ap2 = new FxEngine.AllPass(214);
  protected final FxEngine.AllPass ap3 = new FxEngine.AllPass(319);
  protected final FxEngine.AllPass ap4 = new FxEngine.AllPass(527);

  protected final FxEngine.AllPass dap1a = new FxEngine.AllPass(2182);
  protected final FxEngine.AllPass dap1b = new FxEngine.AllPass(2690);
  protected final FxEngine.AllPass del1 = new FxEngine.AllPass(4501);

  protected final FxEngine.AllPass dap2a = new FxEngine.AllPass(2525);
  protected final FxEngine.AllPass dap2b = new FxEngine.AllPass(2197);
  protected final FxEngine.AllPass del2 = new FxEngine.AllPass(6312);

  protected float inputGain = 0.2f;
  protected float reverbTime = 0.665f;
  protected float diffusion = 0.625f;
  protected float lp = 0.7f;
  protected float lpVal = 0.7f;

  protected final float[] lpDecay = new float[2];
  protected final float[] hpState = new float[2];
  protected final float[] lpState = new float[2];

  protected float hpCutoffVal = 0.0f;
  protected float hpCutoff = calcFilterCutoff(0.0f, false);
  protected float lpCutoffVal = 0.0f;
  protected float lpCutoff = calcFilterCutoff(0.0f, true);

  public MutableReverb() {
    FxEngine.constructTopology(engine, ap1, ap2, ap3, ap4, dap1a, dap1b, del1, dap2a, dap2b, del2);
  }

  public void clear() {
    engine.clear();
    java.util.Arrays.fill(lpDecay, 0.0f);
    java.util.Arrays.fill(hpState, 0.0f);
    java.util.Arrays.fill(lpState, 0.0f);
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
  public void setWidth(float value) {
    diffusion = map(value, 0.0f, 1.0f, 0.1f, 0.9f);
  }

  @Override
  public float getWidth() {
    return map(diffusion, 0.1f, 0.9f, 0.0f, 1.0f);
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

    float kap = diffusion;
    float klp = lp;
    float krt = reverbTime;

    float lp1 = lpDecay[0];
    float lp2 = lpDecay[1];

    for (int frame = 0; frame < input.length; frame++) {
      StereoSample s = output[frame];
      engine.advance();

      float inputSample = input[frame] / 2147483648.0f;
      c.set(inputSample);

      // Diffuse through 4 allpasses
      ap1.process(c, kap);
      ap2.process(c, kap);
      ap3.process(c, kap);
      ap4.process(c, kap);
      float apout = c.get();

      // Main reverb loop left lane
      c.set(apout);
      del2.interpolate(c, 6261.0f, FxEngine.LFOIndex.LFO_2, 50.0f, krt);

      // LP filter decay stage 1
      lpDecay[0] += klp * (c.get() - lpDecay[0]);
      c.set(lpDecay[0]);

      dap1a.process(c, -kap);
      dap1b.process(c, kap);
      del1.write(0, c.get());
      float wet = c.get();

      // HP filter decay (using right tap states for stereo crossover!)
      hpState[1] += hpCutoff * (wet - hpState[1]);
      wet -= hpState[1];

      // LP filter output (using right tap states!)
      lpState[1] += lpCutoff * (wet - lpState[1]);
      wet = lpState[1];

      int outputRight = (int) (wet * 2147483647.0f * 15.0f);

      // Main reverb loop right lane
      c.set(apout);
      del1.interpolate(c, 4460.0f, FxEngine.LFOIndex.LFO_1, 40.0f, krt);

      // LP filter decay stage 2
      lpDecay[1] += klp * (c.get() - lpDecay[1]);
      c.set(lpDecay[1]);

      dap2a.process(c, -kap);
      dap2b.process(c, kap);
      del2.write(0, c.get());
      wet = c.get();

      // HP filter decay (using left tap states!)
      hpState[0] += hpCutoff * (wet - hpState[0]);
      wet -= hpState[0];

      // LP filter output (using left tap states!)
      lpState[0] += lpCutoff * (wet - lpState[0]);
      wet = lpState[0];

      int outputLeft = (int) (wet * 2147483647.0f * 15.0f);

      // Mix to stereo output tap (multiply by dynamic pan levels!)
      s.l = Q31.addSaturate(s.l, Q31.multiply_32x32_rshift32_rounded(outputLeft, getPanLeft()));
      s.r = Q31.addSaturate(s.r, Q31.multiply_32x32_rshift32_rounded(outputRight, getPanRight()));
    }
  }
}
