package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge {@code envelope.cpp} / {@code envelope.h}.
 *
 * <p>The render function is a state machine (ATTACK→DECAY→SUSTAIN→RELEASE/FAST_RELEASE→OFF). It
 * returns {@code (lastValue - 1073741824) << 1} — a BIPOLAR (centred-around-0) value used for
 * modulation. The VCA uses {@code lastValue} directly (unipolar).
 */
public class Envelope {

  public enum Stage {
    ATTACK,
    DECAY,
    SUSTAIN,
    RELEASE,
    FAST_RELEASE,
    OFF
  }

  public Stage state = Stage.OFF;
  public int lastValue;
  public int lastValuePreCurrentStage;
  public int pos;
  public int smoothedSustain;
  public int fastReleaseIncrement;
  public boolean ignoredNoteOff;
  public int timeEnteredState; // for voice-stealing priority

  // C: AudioEngine::nextVoiceState (audio_engine.cpp:165, starts at 1). A single global monotonic
  // counter shared across all envelopes/voices — each state entry gets a unique increasing stamp so
  // voice-stealing can order voices chronologically. (Java static mirrors the C global.)
  static int nextVoiceState = 1;

  public Envelope() {}

  // ── render (envelope.cpp:29-118) ──

  /** Render one block of envelope. Returns centred modulation value. (envelope.cpp:29-118) */
  public int render(
      int numSamples, int attack, int decay, int sustain, int release, int[] releaseTable) {
    // Loop using switch with fallthrough for state transitions
    for (; ; ) {
      switch (state) {
        case ATTACK:
          // pos += attack * numSamples;  // (line 35)
          pos = (int) (((pos & 0xFFFFFFFFL) + (attack & 0xFFFFFFFFL) * numSamples) & 0xFFFFFFFFL);
          if (Integer.compareUnsigned(pos, 8388608) >= 0) { // C unsigned comparison
            pos = 0;
            setState(Stage.DECAY);
            continue; // goto considerEnvelopeStage
          }
          // lastValue = 2147483647 - getDecay4(pos, 23);  // (line 43)
          lastValue = 2147483647 - Functions.getDecay4(pos, 23);
          // lastValue = std::max(lastValue, (int32_t)1);  // (line 44)
          if (lastValue < 1) lastValue = 1;
          break;

        case DECAY:
          // smoothedSustain = add_saturate(smoothedSustain, numSamples * (((int32_t)sustain -
          // smoothedSustain) >> 9));
          // (lines 57-58)
          smoothedSustain =
              Functions.add_saturate(
                  smoothedSustain, numSamples * ((sustain - smoothedSustain) >> 9));
          lastValue =
              smoothedSustain
                  + Functions.multiply_32x32_rshift32(
                          Functions.getDecay8(pos, 23), 2147483647 - smoothedSustain)
                      * 2;
          // pos += decay * numSamples;  // (line 60)
          pos = (int) (((pos & 0xFFFFFFFFL) + (decay & 0xFFFFFFFFL) * numSamples) & 0xFFFFFFFFL);
          if (Integer.compareUnsigned(pos, 8388608) >= 0) { // C unsigned comparison
            setState(Stage.SUSTAIN);
          }
          break;

        case SUSTAIN:
          // smoothedSustain = add_saturate(smoothedSustain, numSamples * (((int32_t)sustain -
          // smoothedSustain) >> 9));
          // (line 69)
          smoothedSustain =
              Functions.add_saturate(
                  smoothedSustain, numSamples * ((sustain - smoothedSustain) >> 9));
          lastValue = smoothedSustain;
          if (sustain == 0) {
            setState(Stage.OFF); // (line 73)
          } else if (ignoredNoteOff) {
            unconditionalRelease(Stage.RELEASE, 1024); // (lines 75-76)
          }
          break;

        case RELEASE:
          pos = (int) (((pos & 0xFFFFFFFFL) + (release & 0xFFFFFFFFL) * numSamples) & 0xFFFFFFFFL);
          if (Integer.compareUnsigned(pos, 8388608) >= 0) {
            setState(Stage.OFF); // (line 83)
            lastValue = 0;
            return -2147483648; // (line 85)
          }
          // lastValue = multiply_32x32_rshift32(interpolateTable(pos, 23, releaseTable),
          // lastValuePreCurrentStage) << 1;
          // (line 90)
          lastValue =
              Functions.multiply_32x32_rshift32(
                      Functions.interpolateTable(pos, 23, releaseTable, 8),
                      lastValuePreCurrentStage)
                  << 1;
          break;

        case FAST_RELEASE:
          // if (fastReleaseIncrement < 2 * release) { release = 2 * release; fastReleaseIncrement =
          // release; }
          // (lines 94-97)
          if (fastReleaseIncrement < 2 * release) {
            release = 2 * release;
            fastReleaseIncrement = release;
          }
          pos =
              (int)
                  (((pos & 0xFFFFFFFFL) + (fastReleaseIncrement & 0xFFFFFFFFL) * numSamples)
                      & 0xFFFFFFFFL);
          if (Integer.compareUnsigned(pos, 8388608) >= 0) {
            setState(Stage.OFF); // (line 100)
            return -2147483648; // (line 101)
          }
          // Sine-shaped fast release (lines 109-111)
          lastValue =
              Functions.multiply_32x32_rshift32(
                      (Functions.getSine((pos + (8388608 >> 1)) & 16777215, 24) >> 1) + 1073741824,
                      lastValuePreCurrentStage)
                  << 1;
          break;

        default: // OFF
          return -2147483648; // (line 114)
      }

      // return (lastValue - 1073741824) << 1; // Centre the range around 0  // (line 118)
      return (lastValue - 1073741824) << 1;
    }
  }

  public int noteOn(int envelopeIndex, Sound sound, Voice voice) {
    int attack = voice.paramFinalValues[Param.LOCAL_ENV_0_ATTACK + envelopeIndex];
    smoothedSustain = voice.paramFinalValues[Param.LOCAL_ENV_0_SUSTAIN + envelopeIndex];
    boolean directlyToDecay = (attack > 245632);
    return noteOn(directlyToDecay);
  }

  public int noteOn(boolean directlyToDecay) {
    ignoredNoteOff = false;
    pos = 0;
    if (!directlyToDecay) {
      setState(Stage.ATTACK);
      lastValue = 0;
    } else {
      setState(Stage.DECAY);
      lastValue = 2147483647;
    }
    return (lastValue - 1073741824) << 1; // Centre
  }

  // ── unconditionalRelease (envelope.cpp:176-185) ──

  public void unconditionalRelease(Stage typeOfRelease, int newFastReleaseIncrement) {
    fastReleaseIncrement = newFastReleaseIncrement;
    if (state != typeOfRelease) {
      setState(typeOfRelease);
      pos = 0;
      lastValuePreCurrentStage = lastValue;
    }
  }

  // ── setState (envelope.cpp:161-169) ──

  public void setState(Stage newState) {
    state = newState;
    timeEnteredState = nextVoiceState++; // envelope.cpp:163 — global monotonic stamp
  }

  // ── resumeAttack (envelope.cpp:187-190) ──
  public void resumeAttack(int oldLastValue) {
    if (state == Stage.ATTACK) {
      pos =
          Functions.interpolateTableInverse(
              2147483647 - oldLastValue, 23, LookupTables.decayTableSmall4, 8);
    }
  }

  public void unconditionalOff() {
    lastValuePreCurrentStage = lastValue;
    setState(Stage.OFF);
  }
}
