package org.chuck.deluge.firmware.dsp.reverb;

/**
 * Port of FxEngine from the firmware. Manages low-overhead circular multi-tap delays buffers and
 * cascaded Schroeder allpasses.
 */
public class FxEngine {
  public static final int TAIL = -1;

  public enum LFOIndex {
    LFO_1,
    LFO_2
  }

  private int writePtr = 0;
  private final float[] buffer;
  private final int mask;
  private final DualCosineOscillator lfo;

  public FxEngine(float[] signal, float[] lfoFreqs) {
    this.buffer = signal;
    this.mask = signal.length - 1;
    this.lfo = new DualCosineOscillator(lfoFreqs);
  }

  public void clear() {
    java.util.Arrays.fill(buffer, 0.0f);
    writePtr = 0;
    lfo.start();
  }

  public void setLFOFrequency(LFOIndex index, float frequency) {
    lfo.setFrequency(index.ordinal(), frequency * 32.0f);
  }

  public void advance() {
    --writePtr;
    if (writePtr < 0) {
      writePtr += buffer.length;
    }
  }

  public float getAt(int index) {
    return buffer[(writePtr + index) & mask];
  }

  public void setAt(int index, float value) {
    buffer[(writePtr + index) & mask] = value;
  }

  public void stepLFO() {
    if ((writePtr & 31) == 0) {
      lfo.next();
    }
  }

  public float getLfoValue(LFOIndex index) {
    stepLFO();
    return lfo.getValue(index.ordinal());
  }

  // ── INNER CLASSES ──

  public static class Context {
    private float accumulator = 0.0f;

    public float get() {
      return accumulator;
    }

    public void set(float value) {
      accumulator = value;
    }

    public void add(float value) {
      accumulator += value;
    }

    public void multiply(float value) {
      accumulator *= value;
    }

    public void reset() {
      accumulator = 0.0f;
    }

    public void lp(float[] state, int stateIdx, float coefficient) {
      state[stateIdx] += coefficient * (accumulator - state[stateIdx]);
      accumulator = state[stateIdx];
    }

    public void hp(float[] state, int stateIdx, float coefficient) {
      state[stateIdx] += coefficient * (accumulator - state[stateIdx]);
      accumulator -= state[stateIdx];
    }
  }

  public static class DelayLine {
    public final int length;
    public int base = 0;
    public FxEngine engine;

    public DelayLine(int length) {
      this.length = length;
    }

    public void process(Context c) {
      process(c, 0);
    }

    public void process(Context c, int offset) {
      this.write(offset, c.get());
      c.set(this.read(length - offset));
    }

    public float read(int index) {
      if (index == TAIL) {
        index = length - 1;
      }
      return engine.getAt(this.base + index);
    }

    public float interpolate(float offset) {
      int offsetIntegral = (int) offset;
      float offsetFractional = offset - offsetIntegral;
      float a = read(offsetIntegral);
      float b = read(offsetIntegral + 1);
      return a + (b - a) * offsetFractional;
    }

    public void write(int index, float value) {
      if (index == TAIL) {
        index = length - 1;
      }
      engine.setAt(this.base + index, value);
    }
  }

  public static class AllPass extends DelayLine {
    public AllPass(int length) {
      super(length);
    }

    public float read(Context c, int offset, float scale) {
      float r = super.read(offset);
      c.add(r * scale);
      return r;
    }

    public void write(Context c, int offset, float scale) {
      super.write(offset, c.get());
      c.multiply(scale);
    }

    public void write(Context c, float scale) {
      write(c, 0, scale);
    }

    public void write(Context c, int offset, float scale, float input) {
      write(c, offset, scale);
      c.add(input);
    }

    public void write(Context c, float scale, float input) {
      write(c, 0, scale, input);
    }

    public float interpolate(Context c, float offset, float scale) {
      float r = super.interpolate(offset);
      c.add(r * scale);
      return r;
    }

    public float interpolate(
        Context c, float offset, LFOIndex index, float amplitude, float scale) {
      offset += amplitude * this.engine.getLfoValue(index);
      return interpolate(c, offset, scale);
    }

    public void processInterpolate(
        Context c, float offset, LFOIndex index, float amplitude, float scale) {
      float readVal = this.interpolate(c, offset, index, amplitude, scale);
      this.write(c, 0, -scale, readVal);
    }

    public void process(Context c, float scale) {
      float head = c.get();
      float tail = this.read(TAIL);

      float feedback = head + (tail * scale);
      this.write(0, feedback);

      float feedforward = (feedback * -scale) + tail;
      c.set(feedforward);
    }
  }

  public static void constructTopology(FxEngine e, DelayLine... delays) {
    int base = 0;
    for (DelayLine d : delays) {
      d.engine = e;
      d.base = base;
      base += d.length + 1;
    }
  }
}
