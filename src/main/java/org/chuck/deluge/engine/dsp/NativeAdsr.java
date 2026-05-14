package org.chuck.deluge.engine.dsp;

/**
 * High-performance, pure Java implementation of the Deluge ADSR envelope. Decoupled from ChucK-Java
 * core.
 */
public class NativeAdsr {
  public enum State {
    IDLE,
    ATTACK,
    DECAY,
    SUSTAIN,
    RELEASE,
    FAST_RELEASE
  }

  private static final float PHASE_MAX = 8388608.0f;
  private final float sampleRate;

  private State state = State.IDLE;
  private double value = 0.0;
  private double pos = 0.0;

  private double attackRate;
  private double decayRate;
  private double releaseRate;
  private double fastReleaseRate;
  private double sustainLevel = 0.7;
  private double lastValuePreCurrentStage = 0.0;
  private double smoothedSustain = 0.0;

  public NativeAdsr(float sampleRate) {
    this.sampleRate = sampleRate;
    setFastReleaseTime(0.005);
    setParams(0.01, 0.1, 0.7, 0.2);
  }

  public void setParams(double a, double d, double s, double r) {
    this.attackRate = timeToRate(a);
    this.decayRate = timeToRate(d);
    this.sustainLevel = Math.max(0.0, Math.min(1.0, s));
    this.releaseRate = timeToRate(r);
  }

  public void setFastReleaseTime(double time) {
    this.fastReleaseRate = timeToRate(time);
  }

  private double timeToRate(double seconds) {
    if (seconds <= 0.0) return PHASE_MAX;
    return PHASE_MAX / (seconds * sampleRate);
  }

  public void keyOn() {
    pos = 0;
    state = State.ATTACK;
    value = 0;
    smoothedSustain = 0;
  }

  public void keyOff() {
    if (state != State.IDLE && state != State.RELEASE && state != State.FAST_RELEASE) {
      lastValuePreCurrentStage = value;
      pos = 0;
      state = State.RELEASE;
    }
  }

  public void fastRelease() {
    if (state != State.IDLE) {
      lastValuePreCurrentStage = value;
      pos = 0;
      state = State.FAST_RELEASE;
    }
  }

  public float tick() {
    switch (state) {
      case IDLE -> value = 0.0;
      case ATTACK -> {
        pos += attackRate;
        if (pos >= PHASE_MAX) {
          pos = 0;
          value = 1.0;
          state = State.DECAY;
        } else {
          value = 1.0 - Math.sqrt(1.0 - (pos / PHASE_MAX) * 0.85);
        }
      }
      case DECAY -> {
        smoothedSustain += (sustainLevel - smoothedSustain) * (1.0 / 512.0);
        value = smoothedSustain + Math.pow(1.0 - (pos / PHASE_MAX), 1.25) * (1.0 - smoothedSustain);
        pos += decayRate;
        if (pos >= PHASE_MAX) {
          state = State.SUSTAIN;
          smoothedSustain = sustainLevel;
          value = sustainLevel;
        }
      }
      case SUSTAIN -> {
        smoothedSustain += (sustainLevel - smoothedSustain) * (1.0 / 512.0);
        value = smoothedSustain;
        if (sustainLevel == 0.0) state = State.IDLE;
      }
      case RELEASE -> {
        pos += releaseRate;
        if (pos >= PHASE_MAX) {
          state = State.IDLE;
          value = 0.0;
        } else {
          value = Math.pow(1.0 - (pos / PHASE_MAX), 1.25) * lastValuePreCurrentStage;
        }
      }
      case FAST_RELEASE -> {
        pos += fastReleaseRate;
        if (pos >= PHASE_MAX) {
          state = State.IDLE;
          value = 0.0;
        } else {
          value = (0.5 + 0.5 * Math.cos((pos / PHASE_MAX) * Math.PI)) * lastValuePreCurrentStage;
        }
      }
    }
    return (float) value;
  }

  public boolean isActive() {
    return state != State.IDLE;
  }
}
