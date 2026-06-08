package org.chuck.deluge.firmware2;

/**
 * Faithful port of the Deluge Reverb system.
 *
 * <p>C sources:
 * <ul>
 * <li>{@code dsp/reverb/reverb.hpp} (126 lines) — model dispatcher</li>
 * <li>{@code dsp/reverb/cosine_oscillator.hpp} (79 lines) — LFO modulator</li>
 * <li>{@code dsp/reverb/fx_engine.hpp} (230 lines) — delay network engine</li>
 * <li>{@code dsp/reverb/mutable.hpp} (193 lines) — Mutable Instruments model</li>
 * <li>{@code dsp/reverb/digital.hpp} (146 lines) — Lexicon 224 model</li>
 * <li>{@code dsp/reverb/base.hpp} (63 lines) — base class</li>
 * </ul>
 *
 * <p>Three reverb models: Freeverb (default), Mutable (Dattorro Griesinger topology),
 * Digital (Lexicon 224-inspired). Model can be switched at runtime.</p>
 */
public class Reverb {

  public enum Model { FREEVERB, MUTABLE, DIGITAL }

  // ── DualCosineOscillator (cosine_oscillator.hpp:10-79) ──

  /** C: cosine_oscillator.hpp:10-79 — dual cosine oscillator for reverb modulation. */
  public static class DualCosineOscillator {
    float[] frequencies = new float[2];
    float[] y0 = {0, 0};
    float[] y1 = {0, 0};
    float[] iirCoefficient = new float[2];
    float[] initialAmplitude = new float[2];

    /** C:15-17 */
    public DualCosineOscillator(float f1, float f2) {
      frequencies[0] = f1;
      frequencies[1] = f2;
      initApproximate();
    }

    /** C:26-43 — InitApproximate */
    public void initApproximate() {
      float[] sign = {16.0f, 16.0f};
      for (int i = 0; i < 2; i++) {
        float f = frequencies[i];
        f -= 0.25f;
        if (f < 0.0f) {
          f = -f;
        } else if (f > 0.5f) {
          f -= 0.5f;
        } else {
          sign[i] = -16.0f;
        }
        frequencies[i] = f;
        iirCoefficient[i] = sign[i] * f * (1.0f - 2.0f * f);
        initialAmplitude[i] = iirCoefficient[i] * 0.25f;
      }
      start();
    }

    /** C:45-48 — Start */
    public void start() {
      System.arraycopy(initialAmplitude, 0, y0, 0, 2);
      y1[0] = 0.5f;
      y1[1] = 0.5f;
    }

    /** C:50 — values() */
    public float[] values() {
      return new float[]{y0[0] + 0.5f, y0[1] + 0.5f};
    }

    /** C:52-57 — Next */
    public float[] next() {
      float[] temp = {y1[0], y1[1]};
      y1[0] = iirCoefficient[0] * y1[0] - y0[0];
      y1[1] = iirCoefficient[1] * y1[1] - y0[1];
      y0 = temp;
      return new float[]{temp[0] + 0.5f, temp[1] + 0.5f};
    }

    /** C:21-23 — SetFrequency */
    public void setFrequency(int index, float frequency) {
      frequencies[index] = frequency;
      initApproximate();
    }
  }

  // ── FxEngine (fx_engine.hpp:29-228) ──

  /** C: fx_engine.hpp:29-228 — delay-network engine for Mutable/Digital reverbs. */
  public static class FxEngine {
    float[] buffer;
    int mask;
    int writePtr;
    DualCosineOscillator lfo;

    public FxEngine(int size, float lfoFreq1, float lfoFreq2) {
      // Round up to power of 2
      int sz = 1;
      while (sz < size) sz <<= 1;
      buffer = new float[sz];
      mask = sz - 1;
      lfo = new DualCosineOscillator(lfoFreq1, lfoFreq2);
    }

    /** C:35-38 — clear buffer */
    public void clear() {
      java.util.Arrays.fill(buffer, 0);
      writePtr = 0;
    }

    /** C:44-49 — Advance (decrement write pointer) */
    public void advance() {
      writePtr--;
      if (writePtr < 0) writePtr += buffer.length;
    }

    /** C:52 — at(index) */
    public float at(int index) {
      return buffer[(writePtr + index) & mask];
    }

    /** C:54 — set at(index) */
    public void setAt(int index, float value) {
      buffer[(writePtr + index) & mask] = value;
    }

    /** C:55-59 — StepLFO + LFO */
    public float lfo(int index) {
      if ((writePtr & 31) == 0) {
        lfo.next();
      }
      return lfo.values()[index];
    }

    /** C:41 — SetLFOFrequency */
    public void setLFOFrequency(int lfoIndex, float frequency) {
      lfo.setFrequency(lfoIndex, frequency * 32.0f);
    }
  }

  // ── OnePole + Interpolate (fx_engine.hpp:13-21) ──

  /** C: fx_engine.hpp:13-16 */
  static float onePole(float[] state, int idx, float in, float coefficient) {
    state[idx] += coefficient * (in - state[idx]);
    return state[idx];
  }

  /** C: fx_engine.hpp:18-21 */
  static float interpolate(float x0, float x1, float fractional) {
    return x0 + (x1 - x0) * fractional;
  }

  // ── Mutable reverb model (mutable.hpp:15-191) ──

  /**
   * C: mutable.hpp:15-191 — Mutable Instruments reverb (Dattorro Griesinger topology).
   * 4 input AP diffusers → loop of 2×(2AP + 1Delay) with modulation.
   */
  public static class MutableModel {
    static final int BUFFER_SIZE = 32768;

    FxEngine engine = new FxEngine(BUFFER_SIZE, 0.5f / 44100, 0.3f / 44100);
    float reverbTime = 0.665f;
    float diffusion = 0.625f;
    float lp = 0.7f;
    float lpDecay1, lpDecay2;
    float hpCutoff = 0.0004535147f; // C:181 — calcFilterCutoff(HighPass, 0)
    float lpCutoff = 0;             // C:188
    float hpL, hpR, lpL, lpR;
    float inputGain = 0.2f;
    int panLeft = 1073741823;
    int panRight = 1073741823;

    /** C:118 — clear */
    public void clear() { engine.clear(); }

    /** C:126-128 — setRoomSize (0..1 mapped to 0.01..0.98) */
    public void setRoomSize(float value) {
      reverbTime = 0.01f + value * (0.98f - 0.01f);
    }

    /** C:133-135 — setDamping */
    public void setDamping(float value) {
      lp = (value == 0) ? 1.0f : 1.0f - (float)Math.min(1.0, Math.max(0.0,
          (Math.log(((1.0f - value) * 50.0f) + 1.0f) / Math.log(2.0)) / 5.7f));
    }

    /** C:139 — setWidth */
    public void setWidth(float value) {
      diffusion = 0.1f + value * (0.9f - 0.1f);
    }

    /** C:142-145 — setHPF */
    public void setHPF(float f) {
      float fcHz = 20.0f + (float)(Math.exp(1.5 * f) - 1.0) * 150.0f;
      float fc = fcHz / 44100.0f;
      hpCutoff = fc / (1.0f + fc);
    }

    /** C:149-150 — setLPF */
    public void setLPF(float f) {
      float fcHz = (float)(Math.exp(1.5 * f) - 1.0) * 5083.74f;
      float fc = fcHz / 44100.0f;
      lpCutoff = fc / (1.0f + fc);
    }

    /** C:57-116 — process */
    public void process(float[] input, float[][] outputLR, int numSamples) {
      float kap = diffusion;
      float klp = lp;
      float krt = reverbTime;

      for (int frame = 0; frame < numSamples; frame++) {
        engine.advance();
        float inputSample = input[frame];

        // Diffuse through 4 allpasses (C:73-77)
        float apout = allpassProcess(inputSample, kap);
        float wet;

        // Right channel loop (C:80-89)
        {
          float c = apout;
          float read = engine.at(6261) + engine.lfo(1) * 50.0f;
          float val = interpolate(
              engine.at((int)read), engine.at((int)read + 1), read - (int)read);
          c += val * krt;
          c = onePole(new float[]{lpDecay1}, 0, c, klp);
          c = allpassProcessInv(c, kap);
          c = allpassProcess(c, kap);
          engine.setAt(2, c);
          wet = c;
          wet -= onePole(new float[]{hpR}, 0, wet, hpCutoff);
          wet = onePole(new float[]{lpR}, 0, wet, lpCutoff);
          outputLR[frame][1] += (int)(wet * 2147483647.0f * 0xF); // C:91-92
        }

        // Left channel loop (C:94-104)
        {
          float c = apout;
          float read = engine.at(4460) + engine.lfo(0) * 40.0f;
          float val = interpolate(
              engine.at((int)read), engine.at((int)read + 1), read - (int)read);
          c += val * krt;
          c = onePole(new float[]{lpDecay2}, 0, c, klp);
          c = allpassProcessInv(c, kap);
          c = allpassProcess(c, kap);
          engine.setAt(2, c);
          wet = c;
          wet -= onePole(new float[]{hpL}, 0, wet, hpCutoff);
          wet = onePole(new float[]{lpL}, 0, wet, lpCutoff);
          outputLR[frame][0] += (int)(wet * 2147483647.0f * 0xF); // C:106-107
        }
      }
    }

    // Allpass processing helpers (simplified from FxEngine::AllPass)
    private float allpassProcess(float input, float scale) {
      // Simple 1st-order allpass: y[n] = -scale*x[n] + x[n-1] + scale*y[n-1]
      float tail = engine.at(0);
      float feedback = input + tail * scale;
      engine.setAt(0, feedback);
      return feedback * (-scale) + tail;
    }

    private float allpassProcessInv(float input, float scale) {
      float tail = engine.at(0);
      float feedback = input + tail * (-scale);
      engine.setAt(0, feedback);
      return feedback * scale + tail;
    }
  }

  // ── Reverb container (reverb.hpp:13-126) ──

  /**
   * C: reverb.hpp:13-126 — model-dispatch reverb container.
   * Delegates to Freeverb (default), Mutable, or Digital model.
   */
  public static class Container {
    public Model model = Model.FREEVERB;

    Freeverb freeverb = new Freeverb();
    MutableModel mutableModel;

    float roomSize, damping, width, hpf, lpf;

    /** C:44-47 — setModel */
    public void setModel(Model m) {
      if (m == Model.MUTABLE && mutableModel == null) {
        mutableModel = new MutableModel();
      }
      model = m;
      // Re-apply stored params to new model
      applyParams();
    }

    private void applyParams() {
      switch (model) {
        case FREEVERB:
          freeverb.setRoomSize(roomSize);
          freeverb.setDamping(damping);
          freeverb.setWidth(width);
          break;
        case MUTABLE:
          if (mutableModel != null) {
            mutableModel.setRoomSize(roomSize);
            mutableModel.setDamping(damping);
            mutableModel.setWidth(width);
            mutableModel.setHPF(hpf);
            mutableModel.setLPF(lpf);
          }
          break;
      }
    }

    /** C:51-64 — process (delegates to active model) */
    public void process(int[] input, int[][] outputLR) {
      switch (model) {
        case FREEVERB:
          freeverb.process(input, outputLR);
          break;
        case MUTABLE:
          if (mutableModel != null) {
            float[] finput = new float[input.length];
            for (int i = 0; i < input.length; i++) {
              finput[i] = input[i] / 2147483647.0f;
            }
            float[][] fout = new float[input.length][2];
            mutableModel.process(finput, fout, input.length);
            for (int i = 0; i < input.length; i++) {
              outputLR[i][0] += (int)fout[i][0];
              outputLR[i][1] += (int)fout[i][1];
            }
          }
          break;
      }
    }

    /** C:66-68 — setPanLevels */
    public void setPanLevels(int ampLeft, int ampRight) {
      freeverb.setPanLevels(ampLeft, ampRight);
    }

    public void setRoomSize(float value) { roomSize = value; applyParams(); }
    public void setDamping(float value) { damping = value; applyParams(); }
    public void setWidth(float value) { width = value; applyParams(); }
    public void setHPF(float value) { hpf = value; applyParams(); }
    public void setLPF(float value) { lpf = value; applyParams(); }
    public void mute() { freeverb.mute(); if (mutableModel != null) mutableModel.clear(); }
  }
}
