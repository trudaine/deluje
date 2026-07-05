package org.deluge.firmware2;

/**
 * Faithful port of the Deluge Reverb system.
 *
 * <p>C sources:
 *
 * <ul>
 *   <li>{@code dsp/reverb/reverb.hpp} (126 lines) — model dispatcher
 *   <li>{@code dsp/reverb/base.hpp} (63 lines) — base class
 *   <li>{@code dsp/reverb/fx_engine.hpp} (230 lines) — delay-network engine
 *   <li>{@code dsp/reverb/mutable.hpp} (193 lines) — Mutable Instruments (Dattorro Griesinger)
 * </ul>
 *
 * <p>Three models: Freeverb (faithful, default), Mutable (Griesinger topology), Digital (extends
 * Mutable). Only Freeverb and Mutable are ported.
 */
public class Reverb {

  public enum Model {
    FREEVERB,
    MUTABLE,
    DIGITAL
  }

  static final float ONE_Q31_F = 2147483647.0f;
  static final int ONE_Q31 = 2147483647;

  /**
   * C: std::numeric_limits<uint32_t>::max() — the MutableModel output scale (mutable.hpp:92,107).
   */
  static final float UINT32_MAX_F = 4294967295.0f;

  // ── OnePole + Interpolate (fx_engine.hpp:13-21) ──

  /** C: fx_engine.hpp:13-16 */
  static float onePole(float[] stateRef, int idx, float in, float coefficient) {
    float out = stateRef[idx];
    out += coefficient * (in - out);
    stateRef[idx] = out;
    return out;
  }

  /** C: fx_engine.hpp:18-21 */
  static float interpolate(float x0, float x1, float fractional) {
    return x0 + (x1 - x0) * fractional;
  }

  // ── DualCosineOscillator (cosine_oscillator.hpp:10-79) ──

  /** C: cosine_oscillator.hpp — dual cosine LFO for reverb modulation. */
  static class LFO {
    float[] f = new float[2];
    float[] y0 = {0, 0}, y1 = {0, 0};
    float[] iir = new float[2], amp = new float[2];

    LFO(float f1, float f2) {
      f[0] = f1;
      f[1] = f2;
      initApprox();
    }

    /** C:26-43 — InitApproximate */
    void initApprox() {
      float[] sign = {16, 16};
      for (int i = 0; i < 2; i++) {
        float freq = f[i] - 0.25f;
        if (freq < 0) freq = -freq;
        else if (freq > 0.5f) freq -= 0.5f;
        else sign[i] = -16;
        iir[i] = sign[i] * freq * (1 - 2 * freq);
        amp[i] = iir[i] * 0.25f;
      }
      start();
    }

    /** C:45-48 */
    void start() {
      System.arraycopy(amp, 0, y0, 0, 2);
      y1[0] = 0.5f;
      y1[1] = 0.5f;
    }

    /** C:50 */
    float[] values() {
      return new float[] {y0[0] + 0.5f, y0[1] + 0.5f};
    }

    /** C:52-57 */
    float[] next() {
      float t0 = y1[0], t1 = y1[1];
      y1[0] = iir[0] * y1[0] - y0[0];
      y1[1] = iir[1] * y1[1] - y0[1];
      y0[0] = t0;
      y0[1] = t1;
      return new float[] {t0 + 0.5f, t1 + 0.5f};
    }

    void setFreq(int i, float v) {
      f[i] = v;
      initApprox();
    }
  }

  // ── FxEngine (fx_engine.hpp:29-78) ──

  /**
   * C: fx_engine.hpp:29-78 — circular buffer engine for delay networks. write_ptr_ decrements on
   * Advance(); at(index) = buffer[(write_ptr_ + index) & mask].
   */
  static class FxEngine {
    float[] buf;
    int mask, writePtr;
    LFO lfo;

    FxEngine(int size, float lfoF1, float lfoF2) {
      int sz = 1;
      while (sz < size) sz <<= 1;
      buf = new float[sz];
      mask = sz - 1;
      lfo = new LFO(lfoF1, lfoF2);
    }

    /** C:35-38 */
    void clear() {
      java.util.Arrays.fill(buf, 0);
      writePtr = 0;
    }

    /** C:44-49 — decrement write pointer */
    void advance() {
      writePtr--;
      if (writePtr < 0) writePtr += buf.length;
    }

    /** C:52 — read at offset from write pointer */
    float at(int index) {
      return buf[(writePtr + index) & mask];
    }

    /** C:54 — write at offset */
    void setAt(int index, float v) {
      buf[(writePtr + index) & mask] = v;
    }

    /** C:55-69 — LFO access */
    float lfoVal(int idx) {
      if ((writePtr & 31) == 0) lfo.next();
      return lfo.values()[idx];
    }
  }

  // ── Context (fx_engine.hpp:81-97) ──

  /** C: fx_engine.hpp:81-97 — pipeline value accumulator. */
  static class Context {
    float acc;

    float get() {
      return acc;
    }

    void set(float v) {
      acc = v;
    }

    void add(float v) {
      acc += v;
    }

    void multiply(float v) {
      acc *= v;
    }

    /** C:89 — lowpass */
    void lp(float[] state, int idx, float coeff) {
      acc = onePole(state, idx, acc, coeff);
    }

    /** C:91-93 — highpass */
    void hp(float[] state, int idx, float coeff) {
      acc -= onePole(state, idx, acc, coeff);
    }
  }

  // ── DelayLine (fx_engine.hpp:99-140) ──

  /** C: fx_engine.hpp:99-140 — fixed delay line within FxEngine buffer. */
  static class DelayLine {
    int length, base;
    FxEngine engine;

    DelayLine(int len) {
      this.length = len;
    }

    /** C:110-115 — absolute index into engine buffer */
    float at(int index) {
      if (index == -1) index = length - 1; // TAIL = -1
      return engine.at(base + index);
    }

    void setAt(int index, float v) {
      if (index == -1) index = length - 1;
      engine.setAt(base + index, v);
    }

    /** C:104-107 — Process: store input, output tail */
    void process(Context c, int offset) {
      setAt(offset, c.get());
      c.set(at(length - offset));
    }

    /** C:117-121 — read */
    float read(int offset) {
      return at(offset);
    }

    /** C:124-130 — interpolated read */
    float interpRead(float offset) {
      int iOff = (int) offset;
      float frac = offset - (float) iOff;
      return interpolate(at(iOff), at(iOff + 1), frac);
    }

    /** C:134 */
    void write(int offset, float v) {
      setAt(offset, v);
    }
  }

  // ── AllPass (fx_engine.hpp:142-218) ──

  /**
   * C: fx_engine.hpp:142-218 — allpass filter within the delay network. Schroeder topology: y[n] =
   * -scale * x[n] + x[n-1] + scale * y[n-1]
   */
  static class AllPass extends DelayLine {
    AllPass(int len) {
      super(len);
    }

    /** C:202-217 — Schroeder allpass section */
    void process(Context c, float scale) {
      float head = c.get(); // C:203
      float tail = at(-1); // C:204 — TAIL
      float feedback = head + tail * scale; // C:206
      setAt(0, feedback); // C:207 — feedback into delay
      float ffw = feedback * (-scale) + tail; // C:209 — feedforward
      c.set(ffw); // C:210 — output
    }

    /** C:146-150 — read with scale */
    float read(Context c, int offset, float scale) {
      float r = read(offset);
      c.add(r * scale);
      return r;
    }

    /** C:155-158 — write with scale */
    void write(Context c, int offset, float scale) {
      write(offset, c.get());
      c.multiply(scale);
    }

    /** C:164-167 — write with scale + input */
    void write(Context c, int offset, float scale, float input) {
      write(c, offset, scale);
      c.add(input);
    }

    /** C:174-179 — interpolated read with LFO + scale */
    float interpRead(Context c, float offset, int lfoIdx, float amp, float scale) {
      offset += amp * engine.lfoVal(lfoIdx);
      float r = interpRead(offset);
      c.add(r * scale);
      return r;
    }

    /** C:188-191 — ProcessInterpolate */
    void processInterp(Context c, float offset, int lfoIdx, float amp, float scale) {
      float r = interpRead(c, offset, lfoIdx, amp, scale);
      write(c, 0, -scale, r);
    }
  }

  // ── ConstructTopology (fx_engine.hpp:220-227) ──

  /** C: fx_engine.hpp:220-227 — assign base offsets to delay lines in engine buffer. */
  static void constructTopology(FxEngine e, DelayLine[] delays) {
    int base = 0;
    for (DelayLine d : delays) {
      d.engine = e;
      d.base = base;
      base += d.length + 1;
    }
  }

  // ── Mutable model (mutable.hpp:15-191) ──

  /**
   * C: mutable.hpp:15-191 — Dattorro Griesinger topology. 4 input AP diffusers → loop of 2×(AllPass
   * + AllPass + Delay) with LFO modulation.
   */
  public static class MutableModel {
    static final int BUFSZ = 32768;
    FxEngine engine = new FxEngine(BUFSZ, 0.5f / 44100, 0.3f / 44100);

    // C:165-166
    float reverbTime = 0.665f, diffusion = 0.625f, lp = 0.7f;
    float lpDecay1, lpDecay2;
    float hpCutoffVal, hpCutoff = 0.00045f, lpCutoff;
    // Six distinct persistent states (mutable.hpp): output HP hp_r_/hp_l_ (hpSt[0]/[1]), output LP
    // lp_r_/lp_l_ (lpSt[0]/[1]), and the decay-loop damping lp_decay_1_/lp_decay_2_
    // (lpDecay[0]/[1]).
    // The decay damping MUST NOT share state with the output LP (the C keeps them separate).
    float[] hpSt = {0, 0}, lpSt = {0, 0};
    float[] lpDecay = {0, 0};

    /** C:118 */
    public void clear() {
      engine.clear();
    }

    /** C:126-128 */
    public void setRoomSize(float v) {
      reverbTime = 0.01f + v * 0.97f;
    }

    /** C:133-135 */
    public void setDamping(float v) {
      lp =
          (v == 0)
              ? 1
              : 1
                  - Math.min(
                      1, Math.max(0, (float) (Math.log(((1 - v) * 50) + 1) / Math.log(2)) / 5.7f));
    }

    /** C:139 */
    public void setWidth(float v) {
      diffusion = 0.1f + v * 0.8f;
    }

    /** C:142-145 */
    public void setHPF(float v) {
      hpCutoffVal = v;
      float fcHz = 20 + (float) (Math.exp(1.5 * v) - 1) * 150;
      float fc = fcHz / 44100;
      hpCutoff = fc / (1 + fc);
    }

    /** C:149-151 */
    public void setLPF(float v) {
      float fcHz = (float) (Math.exp(1.5 * v) - 1) * 5083.74f;
      float fc = fcHz / 44100;
      lpCutoff = fc / (1 + fc);
    }

    public void setPanLevels(int l, int r) {
      panL = l;
      panR = r;
    }

    int panL = ONE_Q31, panR = ONE_Q31;

    /**
     * C: mutable.hpp:23-116 — process audio buffer. Input: Q31 int array. Output: accumulated into
     * outputLR int[][].
     */
    public void process(int[] input, int[][] outputLR, int numSamples) {
      // C:28-38 — create delay lines (re-created each call per C code)
      AllPass ap1 = new AllPass(150), ap2 = new AllPass(214);
      AllPass ap3 = new AllPass(319), ap4 = new AllPass(527);
      AllPass dap1a = new AllPass(2182), dap1b = new AllPass(2690);
      AllPass del1 = new AllPass(4501);
      AllPass dap2a = new AllPass(2525), dap2b = new AllPass(2197);
      AllPass del2 = new AllPass(6312);

      // C:42-47 — assign bases in engine buffer
      constructTopology(
          engine, new DelayLine[] {ap1, ap2, ap3, ap4, dap1a, dap1b, del1, dap2a, dap2b, del2});

      Context c = new Context();
      float kap = diffusion, klp = lp, krt = reverbTime;
      float lp1 = lpDecay1, lp2 = lpDecay2;

      for (int frame = 0; frame < numSamples; frame++) {
        engine.advance(); // C:61

        // C:67-69 — input
        float inSample = input[frame] / ONE_Q31_F;
        c.set(inSample);

        // C:73-77 — 4 input diffusers
        ap1.process(c, kap);
        ap2.process(c, kap);
        ap3.process(c, kap);
        ap4.process(c, kap);
        float apout = c.get();

        // C:80-92 — right channel loop
        c.set(apout);
        del2.interpRead(c, 6261, 1 /*LFO_2*/, 50, krt);
        c.lp(
            lpDecay, 0,
            klp); // C:82 — c.Lp(lp_1, klp), decay damping (separate from output LP lp_r_)
        dap1a.process(c, -kap);
        dap1b.process(c, kap);
        del1.write(c, 0, 2.0f);
        float wetR = c.get();
        wetR -= onePole(hpSt, 0, wetR, hpCutoff); // HP right
        wetR = onePole(lpSt, 0, wetR, lpCutoff); // LP right
        int outputRight = (int) (wetR * UINT32_MAX_F * 0xF); // C:91-92 (uint32 max, NOT int32)
        // C mutable.hpp:111 — s.r += multiply_32x32_rshift32_rounded(output_right, getPanRight())
        outputLR[frame][1] += Functions.multiply_32x32_rshift32_rounded(outputRight, panR);

        // C:94-107 — left channel loop
        c.set(apout);
        del1.interpRead(c, 4460, 0 /*LFO_1*/, 40, krt);
        c.lp(
            lpDecay, 1,
            klp); // C:96 — c.Lp(lp_2, klp), decay damping (separate from output LP lp_l_)
        dap2a.process(c, -kap);
        dap2b.process(c, kap);
        del2.write(c, 0, 2.0f);
        float wetL = c.get();
        wetL -= onePole(hpSt, 1, wetL, hpCutoff); // HP left
        wetL = onePole(lpSt, 1, wetL, lpCutoff); // LP left
        int outputLeft = (int) (wetL * UINT32_MAX_F * 0xF); // C:106-107 (uint32 max, NOT int32)
        // C mutable.hpp:110 — s.l += multiply_32x32_rshift32_rounded(output_left, getPanLeft())
        outputLR[frame][0] += Functions.multiply_32x32_rshift32_rounded(outputLeft, panL);
      }
    }
  }

  // ── Digital model (digital.hpp:24-145) ──

  /**
   * C: digital.hpp:24-145 — the Griesinger topology from Dattorro's "Effect Design" Part 1,
   * modelled on the Lexicon 224. Extends {@link MutableModel}; only {@code process()} differs (the
   * param setters are identical and inherited). Reuses the inherited persistent states: {@code
   * lpDecay[0]} = lp_decay_1_, {@code hpSt[1]}/{@code lpSt[1]} = hp_l_/lp_l_; adds {@code lpBandSt}
   * = lp_band_.
   */
  public static class DigitalModel extends MutableModel {
    static final float K_RATIO = 29761.0f / 44100.0f; // C:25 — Lexicon→Deluge sample-rate ratio
    static final int MAX_EXCURSION = (int) (16.0f * K_RATIO); // C:27
    final float[] lpBandSt = {0}; // C: lp_band_ (digital.hpp:144)

    @Override
    public void process(int[] input, int[][] outputLR, int numSamples) {
      Context c = new Context();

      // C:33-46 — delay-line network (dap* are AllPass; del* are plain DelayLine)
      AllPass ap1 = new AllPass((int) (142 * K_RATIO)), ap2 = new AllPass((int) (107 * K_RATIO));
      AllPass ap3 = new AllPass((int) (379 * K_RATIO)), ap4 = new AllPass((int) (277 * K_RATIO));
      AllPass dap1a = new AllPass((int) (672 * K_RATIO) + MAX_EXCURSION);
      DelayLine del1a = new DelayLine((int) (4453 * K_RATIO));
      AllPass dap1b = new AllPass((int) (1800 * K_RATIO));
      DelayLine del1b = new DelayLine((int) (3720 * K_RATIO));
      AllPass dap2a = new AllPass((int) (908 * K_RATIO) + MAX_EXCURSION);
      DelayLine del2a = new DelayLine((int) (4217 * K_RATIO));
      AllPass dap2b = new AllPass((int) (2656 * K_RATIO));
      DelayLine del2b = new DelayLine((int) (3163 * K_RATIO));

      constructTopology(
          engine,
          new DelayLine[] {
            ap1, ap2, ap3, ap4, dap1a, del1a, dap1b, del1b, dap2a, del2a, dap2b, del2b
          });

      float kdecay = reverbTime; // C:52
      float kid1 = 0.750f, kid2 = 0.625f; // C:53-54 — input diffusion
      float kdd1 = 0.70f; // C:55 — decay diffusion 1
      float kdd2 = Math.max(0.25f, Math.min(0.5f, kdecay + 0.15f)); // C:56
      float kdamp = lp; // C:58
      float kbandwidth = 0.9995f; // C:59

      for (int frame = 0; frame < numSamples; frame++) {
        engine.advance(); // C:68

        float inSample = input[frame] / ONE_Q31_F; // C:70
        c.set(inSample); // C:71
        c.lp(lpBandSt, 0, kbandwidth); // C:73 — bandwidth limit

        // C:76-79 — 4 input diffusers
        ap1.process(c, kid1);
        ap2.process(c, kid1);
        ap3.process(c, kid2);
        ap4.process(c, kid2);
        float apout = c.get(); // C:80

        // C:83-92 — first half of the figure-8 loop
        c.set(apout);
        dap1a.interpRead(c, 672f * K_RATIO, 1 /*LFO_2*/, MAX_EXCURSION, -kdd1); // C:84
        del1a.process(c, 0); // C:85
        c.lp(lpDecay, 0, kdamp); // C:86 — lp_1 damping
        c.multiply(kdecay); // C:87
        dap1b.process(c, kdd2); // C:88
        del1b.process(c, 0); // C:89
        c.multiply(kdecay); // C:90
        c.add(apout); // C:91
        dap2a.write(c, 0, kdd2); // C:92

        // C:94-103 — second half
        c.set(apout);
        dap2a.interpRead(c, 908f * K_RATIO, 0 /*LFO_1*/, MAX_EXCURSION, -kdd1); // C:95
        del2a.process(c, 0); // C:96
        c.lp(lpDecay, 0, kdamp); // C:97 — lp_1 again (C reuses the same state)
        c.multiply(kdecay); // C:98
        dap2b.process(c, kdd2); // C:99
        del2b.process(c, 0); // C:100
        c.multiply(kdecay); // C:101
        c.add(apout); // C:102
        dap1a.write(c, 0, kdd1); // C:103

        // C:105-114 — left output taps (C uses hp_l_/lp_l_ = index 1)
        float leftSum = 0;
        leftSum += 0.6f * del2a.at((int) (266 * K_RATIO));
        leftSum += 0.6f * del2a.at((int) (2974 * K_RATIO));
        leftSum -= 0.6f * dap2b.at((int) (1913 * K_RATIO));
        leftSum += 0.6f * del2b.at((int) (1996 * K_RATIO));
        leftSum -= 0.6f * del1a.at((int) (1990 * K_RATIO));
        leftSum -= 0.6f * dap1b.at((int) (187 * K_RATIO));
        leftSum -= 0.6f * del1b.at((int) (1066 * K_RATIO));
        leftSum -= onePole(hpSt, 1, leftSum, hpCutoff); // C:113
        leftSum = onePole(lpSt, 1, leftSum, lpCutoff); // C:114

        // C:116-125 — right output taps (the C also uses hp_l_/lp_l_ here, sic — kept faithful)
        float rightSum = 0;
        rightSum += 0.6f * del1a.at((int) (353 * K_RATIO));
        rightSum += 0.6f * del1a.at((int) (3627 * K_RATIO));
        rightSum -= 0.6f * dap1b.at((int) (1228 * K_RATIO));
        rightSum += 0.6f * del1b.at((int) (2673 * K_RATIO));
        rightSum -= 0.6f * del2a.at((int) (2111 * K_RATIO));
        rightSum -= 0.6f * dap2b.at((int) (335 * K_RATIO));
        rightSum -= 0.6f * del2b.at((int) (121 * K_RATIO));
        rightSum -= onePole(hpSt, 1, rightSum, hpCutoff); // C:124 (hp_l_, sic)
        rightSum = onePole(lpSt, 1, rightSum, lpCutoff); // C:125 (lp_l_, sic)

        // C digital.hpp:127-135 — output (uint32 max scale), then pan applied on mix like the
        // Mutable model: output[frame] += multiply_32x32_rshift32_rounded(out, getPan*()).
        int outputLeft = (int) (leftSum * UINT32_MAX_F * 0xF);
        int outputRight = (int) (rightSum * UINT32_MAX_F * 0xF);
        outputLR[frame][0] += Functions.multiply_32x32_rshift32_rounded(outputLeft, panL);
        outputLR[frame][1] += Functions.multiply_32x32_rshift32_rounded(outputRight, panR);
      }
    }
  }

  // ── Reverb Container (reverb.hpp:13-126) ──

  public static class Container {
    public Model model = Model.FREEVERB;
    Freeverb freeverb = new Freeverb();
    MutableModel mutableModel;
    // C song.cpp:179-183 — every loaded/new song applies reverbLPF = 50/50 = 1.0 (near-open)
    // via audio_engine.cpp:1358. Without this the Mutable/Digital output one-pole coefficient
    // is 0 and the models render silence.
    float roomSize, damping, width, hpf;
    float lpf = 1.0f;

    public void setModel(Model m) {
      // DIGITAL needs the Digital subclass; MUTABLE needs the base class. Re-create if the type
      // doesn't match the requested model (so switching models picks the right process()).
      if (m == Model.MUTABLE && (mutableModel == null || mutableModel instanceof DigitalModel)) {
        mutableModel = new MutableModel();
      } else if (m == Model.DIGITAL && !(mutableModel instanceof DigitalModel)) {
        mutableModel = new DigitalModel();
      }
      model = m;
      applyParams();
    }

    void applyParams() {
      switch (model) {
        case FREEVERB:
          freeverb.setRoomSize(roomSize);
          freeverb.setDamping(damping);
          freeverb.setWidth(width);
          break;
        case MUTABLE:
        case DIGITAL:
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

    public void process(int[] input, int[][] outputLR) {
      switch (model) {
        case FREEVERB:
          freeverb.process(input, outputLR);
          break;
        case MUTABLE:
        case DIGITAL:
          if (mutableModel != null) {
            mutableModel.process(input, outputLR, input.length);
          }
          break;
      }
    }

    public void setPanLevels(int l, int r) {
      freeverb.setPanLevels(l, r);
      if (mutableModel != null) mutableModel.setPanLevels(l, r);
    }

    public void setRoomSize(float v) {
      roomSize = v;
      applyParams();
    }

    public void setDamping(float v) {
      damping = v;
      applyParams();
    }

    public void setWidth(float v) {
      width = v;
      applyParams();
    }

    public void setHPF(float v) {
      hpf = v;
      applyParams();
    }

    public void setLPF(float v) {
      lpf = v;
      applyParams();
    }

    public void mute() {
      freeverb.mute();
      if (mutableModel != null) mutableModel.clear();
    }
  }
}
