package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code dsp/granular/GranularProcessor.cpp} (347 lines) + header (120 lines).
 *
 * <p>Granular post-FX processor: records into a circular buffer, generates up to 8
 * overlapping grains with randomized pitch/reverse/pan, mixes wet/dry with feedback.
 * Used for the Deluge's grain/stutter/glitch effect.</p>
 */
public class GranularProcessor {

  // C: GranularProcessor.h:119 — kModFXGrainBufferSize = 65536
  static final int K_MOD_FX_GRAIN_BUFFER_SIZE = 65536;
  static final int K_MASK = K_MOD_FX_GRAIN_BUFFER_SIZE - 1;
  static final int K_SAMPLE_RATE = 44100;
  static final int ONE_Q31 = 2147483647;
  static final int ONE_OVER_SQRT2_Q31 = 1518500250; // ~2147483647/sqrt(2)

  // ── Grain struct (GranularProcessor.h:31-41) ──

  static class Grain {
    int length;     // C:32 — 0=OFF
    int startPoint; // C:33 — in samples
    int counter;    // C:34 — relative pos
    int pitch = 1024; // C:35 — 1024=1.0 (10.6 fixed point)
    int volScale;   // C:36
    int volScaleMax; // C:37
    boolean rev;    // C:38
    int panVolL;    // C:39
    int panVolR;    // C:40
  }

  // ── State (GranularProcessor.h:72-94) ──

  final int[][] grainBuffer = new int[K_MOD_FX_GRAIN_BUFFER_SIZE][2]; // StereoSample[]
  final Grain[] grains = new Grain[8];
  int bufferWriteIndex;
  int grainSize = 13230;        // C:291 — 300ms
  int grainRate = 1260;         // C:292 — 35Hz
  int grainShift = 13230;       // C:290 — 300ms
  int grainFeedbackVol = 161061273; // C:293
  int grainVol;
  int grainDryVol = ONE_Q31;    // C:298
  int pitchRandomness;
  boolean grainInitialized;
  int wrapsToShutdown;
  boolean bufferFull;
  int densityKnobPos, rateKnobPos, mixKnobPos;

  // C:91-92 — output LPFs (12kHz one-pole)
  final BasicFilterComponent lpfL = new BasicFilterComponent();
  final BasicFilterComponent lpfR = new BasicFilterComponent();

  // ── Constructor (GranularProcessor.cpp:287-304) ──

  public GranularProcessor() {
    for (int i = 0; i < 8; i++) {
      grains[i] = new Grain();
    }
    clearGrainFXBuffer();
  }

  // ── setWrapsToShutdown (GranularProcessor.cpp:29-45) ──

  void setWrapsToShutdown() {
    if (grainFeedbackVol < 33554432) wrapsToShutdown = 1;
    else if (grainFeedbackVol <= 100663296) wrapsToShutdown = 2;
    else if (grainFeedbackVol <= 218103808) wrapsToShutdown = 3;
    else wrapsToShutdown = 4;
  }

  // ── clearGrainFXBuffer (GranularProcessor.cpp:278-286) ──

  public void clearGrainFXBuffer() {
    for (int i = 0; i < 8; i++) grains[i].length = 0;
    grainInitialized = false;
    bufferWriteIndex = 0;
    wrapsToShutdown = 0;
    bufferFull = false;
  }

  // ── processGrainFX (GranularProcessor.cpp:47-87) ──

  public void processGrainFX(int[][] buffer, int numSamples, int rate, int mix,
                              int density, int pitchRand, int[] postFXVolume,
                              boolean anySoundComingIn, float tempoBPM) {
    if (anySoundComingIn || wrapsToShutdown > 0) {
      if (anySoundComingIn) setWrapsToShutdown();

      setupGrainFX(rate, mix, density, pitchRand, postFXVolume);

      for (int i = 0; i < numSamples; i++) {
        int[] grainWet = processOneGrainSample(buffer[i]);

        // C:64-65 — apply grain volume
        int wetL = Functions.multiply_32x32_rshift32(grainWet[0], grainVol);
        int wetR = Functions.multiply_32x32_rshift32(grainWet[1], grainVol);

        // C:68-69 — 12kHz LPF
        wetL = lpfL.doFilter(wetL, 1 << 29);
        wetR = lpfR.doFilter(wetR, 1 << 29);

        // C:72-73 — wet/dry mix
        buffer[i][0] = Functions.add_saturate(
            Functions.multiply_32x32_rshift32(buffer[i][0], grainDryVol), wetL);
        buffer[i][1] = Functions.add_saturate(
            Functions.multiply_32x32_rshift32(buffer[i][1], grainDryVol), wetR);
        // C:76 — reverb backdoor feed (skipped — needs AudioEngine integration)
      }
    }
    if (bufferWriteIndex > K_MOD_FX_GRAIN_BUFFER_SIZE / 2) {
      bufferFull = true;
    }
  }

  // ── setupGrainFX (GranularProcessor.cpp:88-127) ──

  void setupGrainFX(int rate, int mix, int density, int pitchRand, int[] postFXVolume) {
    if (!grainInitialized && bufferWriteIndex >= 65536) {
      grainInitialized = true;
    }
    // C:93 — apply sqrt(2) volume reduction
    postFXVolume[0] = Functions.multiply_32x32_rshift32(postFXVolume[0], ONE_OVER_SQRT2_Q31) << 1;

    // C:95-96 — grain shift (~300ms base)
    grainShift = 44 * 300;

    // C:98-104 — grain size from density
    if (densityKnobPos != density || rateKnobPos != rate) {
      densityKnobPos = density;
      int densityQ31 = (density / 2) + 1073741824; // convert to 0..2^31
      // C:103 — q31_mult(_grainRate << 3, density). q31_mult is smmul*2, i.e. multiply_..._rshift32 << 1.
      grainSize = 1760 + (Functions.multiply_32x32_rshift32(grainRate << 3, densityQ31) << 1);
    }

    // C:106-113 — grain rate
    if (rateKnobPos != rate) {
      rateKnobPos = rate;
      int raw = Math.max(0, Math.min(256, (Functions.quickLog(rate) - 364249088) >> 21));
      grainRate = ((360 * raw >> 8) * raw >> 8);
      grainRate = Math.max(1, grainRate);
      grainRate = (K_SAMPLE_RATE << 1) / grainRate;
    }

    // C:115
    pitchRandomness = toPositive(pitchRand);

    // C:117-126 — mix (cubic mapping for grain vol)
    if (mixKnobPos != mix) {
      mixKnobPos = mix;
      grainVol = mix - 0x80000000; // bias from unsigned
      grainVol = (Functions.multiply_32x32_rshift32_rounded(
          Functions.multiply_32x32_rshift32_rounded(grainVol, grainVol), grainVol) << 2) + 0x80000000;
      grainVol = Math.max(0, Math.min(ONE_Q31, grainVol));
      grainDryVol = (int)Math.max(0, Math.min(ONE_Q31, ((long)(0x80000000L - grainVol) << 3)));
      grainFeedbackVol = grainVol >> 1;
    }
  }

  /** C: fixedpoint.h:37-39 — (a / 2) + 2^30. Signed truncating division, NOT {@code >> 1}. */
  static int toPositive(int val) {
    return (val / 2) + 1073741824;
  }

  // ── processOneGrainSample (GranularProcessor.cpp:128-174) ──

  int[] processOneGrainSample(int[] currentSample) {
    if (bufferWriteIndex >= K_MOD_FX_GRAIN_BUFFER_SIZE) {
      bufferWriteIndex = 0;
      wrapsToShutdown--;
    }
    int writeIdx = bufferWriteIndex;

    // C:134-136 — setup new grains periodically
    if (bufferFull && (bufferWriteIndex % grainRate) == 0) {
      setupGrainsIfNeeded(writeIdx);
    }

    int grainsL = 0, grainsR = 0;

    for (int i = 0; i < 8; i++) {
      if (grains[i].length > 0) {
        // C:143-146 — triangle window
        int vol = (grains[i].counter <= (grains[i].length >> 1))
            ? grains[i].counter * grains[i].volScale
            : grains[i].volScaleMax - (grains[i].counter - (grains[i].length >> 1)) * grains[i].volScale;

        // C:147-150 — pitch shift
        int delta = grains[i].counter * (grains[i].rev ? -1 : 1);
        if (grains[i].pitch != 1024) {
          delta = (delta * grains[i].pitch) >> 10;
        }

        // C:151 — position in buffer
        int pos = (grains[i].startPoint + delta + K_MOD_FX_GRAIN_BUFFER_SIZE) & K_MASK;

        // C:152-155 — accumulate with pan
        grainsL = Functions.multiply_accumulate_32x32_rshift32_rounded(
            grainsL, Functions.multiply_32x32_rshift32(grainBuffer[pos][0], vol), grains[i].panVolL);
        grainsR = Functions.multiply_accumulate_32x32_rshift32_rounded(
            grainsR, Functions.multiply_32x32_rshift32(grainBuffer[pos][1], vol), grains[i].panVolR);

        // C:157-159
        grains[i].counter++;
        if (grains[i].counter >= grains[i].length) {
          grains[i].length = 0;
        }
      }
    }

    grainsL <<= 3; // C:164
    grainsR <<= 3; // C:165

    // C:167-170 — write feedback to buffer
    grainBuffer[writeIdx][0] = Functions.multiply_accumulate_32x32_rshift32_rounded(
        currentSample[0], grainsL, grainFeedbackVol);
    grainBuffer[writeIdx][1] = Functions.multiply_accumulate_32x32_rshift32_rounded(
        currentSample[1], grainsR, grainFeedbackVol);

    bufferWriteIndex++; // C:172
    return new int[]{grainsL, grainsR};
  }

  // ── setupGrainsIfNeeded (GranularProcessor.cpp:175-277) ──

  void setupGrainsIfNeeded(int writeIdx) {
    for (int i = 0; i < 8; i++) {
      if (grains[i].length <= 0) {
        grains[i].length = grainSize;
        // C:179-181 — random spray in buffer
        int spray = random(K_MOD_FX_GRAIN_BUFFER_SIZE >> 1) - (K_MOD_FX_GRAIN_BUFFER_SIZE >> 2);
        grains[i].startPoint = (bufferWriteIndex + K_MOD_FX_GRAIN_BUFFER_SIZE - grainShift + spray) & K_MASK;
        grains[i].counter = 0;
        grains[i].rev = (getRandom255() < 76);

        // C:186 — int8_t typeRand = multiply_32x32_rshift32(q31_mult(STD, pitchRandomness), 7).
        // q31_mult is smmul*2 (<<1); the int8_t cast (NOT a clamp) is what lets the default case fire.
        byte typeRand = (byte) Functions.multiply_32x32_rshift32(
            Functions.multiply_32x32_rshift32(sampleTriangleDistribution(), pitchRandomness) << 1, 7);

        switch (typeRand) {
          case -3: grains[i].pitch = 512; grains[i].rev = true; break;   // octave down reverse
          case -2: grains[i].pitch = 767; grains[i].rev = true; break;   // 4th down reverse
          case -1: grains[i].pitch = 1024; grains[i].rev = true; break;  // unison reverse
          case 0:  grains[i].pitch = 1024; break;                        // unison
          case 1:  grains[i].pitch = 2048; break;                        // octave up
          case 2:  grains[i].pitch = 1534; break;                        // 5th up
          case 3:  grains[i].pitch = 2048; grains[i].rev = true; break;  // octave up reverse
          default: grains[i].pitch = 3072; grains[i].rev = true; break;  // octave+5th reverse
        }

        // C:220-267 — adjust start point and length for reverse/forward
        if (grains[i].rev) {
          grains[i].startPoint = (writeIdx + K_MOD_FX_GRAIN_BUFFER_SIZE - 1) & K_MASK;
          grains[i].length = (grains[i].pitch > 1024)
              ? Math.min(grains[i].length, 21659)
              : Math.min(grains[i].length, 30251);
        } else {
          if (grains[i].pitch > 1024) {
            int startMax = (writeIdx + grains[i].length
                - ((grains[i].length * grains[i].pitch) >> 10) + K_MOD_FX_GRAIN_BUFFER_SIZE) & K_MASK;
            if (!(unsignedLT(grains[i].startPoint, startMax) && unsignedGT(grains[i].startPoint, writeIdx))) {
              grains[i].startPoint = (startMax + K_MOD_FX_GRAIN_BUFFER_SIZE - 1) & K_MASK;
            }
          } else if (grains[i].pitch < 1024) {
            int startMax = (writeIdx + grains[i].length
                - ((grains[i].length * grains[i].pitch) >> 10) + K_MOD_FX_GRAIN_BUFFER_SIZE) & K_MASK;
            if (!(unsignedGT(grains[i].startPoint, startMax) && unsignedLT(grains[i].startPoint, writeIdx))) {
              grains[i].startPoint = (writeIdx + K_MOD_FX_GRAIN_BUFFER_SIZE - 1) & K_MASK;
            }
          }
        }

        // C:245-267 — uninitialized grain: limit to available buffer
        if (!grainInitialized) {
          if (!grains[i].rev) {
            grains[i].pitch = 1024;
            if (bufferWriteIndex > 13231) {
              int newStart = Math.max(440, random(bufferWriteIndex - 2));
              grains[i].startPoint = (writeIdx - newStart + K_MOD_FX_GRAIN_BUFFER_SIZE) & K_MASK;
            } else {
              grains[i].length = 0;
            }
          } else {
            grains[i].pitch = Math.min(grains[i].pitch, 1024);
            if (bufferWriteIndex > 13231) {
              grains[i].length = Math.min(grains[i].length, bufferWriteIndex - 2);
              grains[i].startPoint = (writeIdx - 1 + K_MOD_FX_GRAIN_BUFFER_SIZE) & K_MASK;
            } else {
              grains[i].length = 0;
            }
          }
        }

        // C:268-273 — triangle window setup + pan
        if (grains[i].length > 0) {
          grains[i].volScale = ONE_Q31 / (grains[i].length >> 1);
          grains[i].volScaleMax = grains[i].volScale * (grains[i].length >> 1);
          int panAmount = (getRandom255() - 128) << 23;
          if (panAmount == 0) {
            grains[i].panVolL = 1073741823;
            grains[i].panVolR = 1073741823;
          } else {
            int panOffset = Math.max(-1073741824, Math.min(1073741824, panAmount));
            grains[i].panVolR = (panAmount >= 0) ? 1073741823 : 1073741824 + panOffset;
            grains[i].panVolL = (panAmount <= 0) ? 1073741823 : 1073741824 - panOffset;
          }
        }
        break;
      }
    }
  }

  // ── Helpers ──

  static int getRandom255() { return Functions.getNoise() >>> 24; }

  static int random(int upperLimit) {
    return ((Functions.getNoise() >> 16) & 0xFFFF) % (upperLimit + 1);
  }

  /** C: functions.h:313-319 — Irwin-Hall triangle: add_saturate of two full getNoise() values. */
  static int sampleTriangleDistribution() {
    int u1 = Functions.getNoise();
    int u2 = Functions.getNoise();
    return Functions.add_saturate(u1, u2);
  }

  static boolean unsignedLT(int a, int b) { return Integer.compareUnsigned(a, b) < 0; }
  static boolean unsignedGT(int a, int b) { return Integer.compareUnsigned(a, b) > 0; }
}
