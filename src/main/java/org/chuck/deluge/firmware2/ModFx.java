package org.chuck.deluge.firmware2;

import org.chuck.deluge.firmware2.Lfo.LfoType;

/**
 * Faithful port of {@code ModFXProcessor} (model/mod_controllable/ModFXProcessor.cpp): the comb-filter
 * based mod FX (chorus / chorus-stereo / dimension / flanger / warble / phaser). NOT grain. The C is
 * templated on {@code ModFXType} and a {@code stereo} flag; this de-templates into runtime branches but
 * keeps the {@code stereo} path (the firmware/ port dropped it). Buffers are {@code int[][]} {l, r}.
 */
public final class ModFx {

  /** definitions_cxx.hpp:418-427. */
  public enum ModFXType {
    NONE,
    FLANGER,
    CHORUS,
    PHASER,
    CHORUS_STEREO,
    WARBLE,
    DIMENSION,
    GRAIN
  }

  // definitions_cxx.hpp:257-266, 463
  static final int kModFXBufferSize = 512;
  static final int kModFXBufferIndexMask = kModFXBufferSize - 1;
  static final int kModFXMaxDelay = (kModFXBufferSize - 1) << 16;
  static final int kFlangerMinTime = 3 << 16;
  static final int kFlangerAmplitude = kModFXMaxDelay - kFlangerMinTime;
  static final int kFlangerOffset = (kModFXMaxDelay + kFlangerMinTime) >> 1;
  static final int kNumAllpassFiltersPhaser = 6;

  // State (C members).
  private final int[][] modFXBuffer = new int[kModFXBufferSize][2];
  private int modFXBufferWriteIndex = 0; // uint16
  private final int[] phaserMemory = new int[2];
  private final int[][] allpassMemory = new int[kNumAllpassFiltersPhaser][2];
  private final Lfo modFXLFO = new Lfo();
  private final Lfo modFXLFOStereo = new Lfo();

  /** resetMemory (ModFXProcessor.cpp). */
  public void resetMemory() {
    for (int[] s : modFXBuffer) {
      s[0] = s[1] = 0;
    }
    for (int[] a : allpassMemory) {
      a[0] = a[1] = 0;
    }
    phaserMemory[0] = phaserMemory[1] = 0;
  }

  /**
   * processModFX (ModFXProcessor.cpp:17-74). modFXOffset/modFXFeedback are the UNPATCHED_MOD_FX_OFFSET
   * / UNPATCHED_MOD_FX_FEEDBACK param values; {@code stereo} is AudioEngine::renderInStereo.
   */
  public void processModFX(
      int[][] buffer,
      int numSamples,
      ModFXType modFXType,
      int modFXRate,
      int modFXDepth,
      int[] postFXVolume,
      int modFXOffset,
      int modFXFeedback,
      boolean stereo,
      boolean anySoundComingIn) {
    if (!anySoundComingIn) {
      return; // C:21-22
    }
    if (modFXType == ModFXType.NONE || modFXType == ModFXType.GRAIN) {
      return;
    }

    LfoType modFXLFOWaveType = LfoType.SINE;
    int modFXDelayOffset = 0;
    int thisModFXDelayDepth = 0;
    int feedback = 0;

    if (modFXType == ModFXType.FLANGER
        || modFXType == ModFXType.PHASER
        || modFXType == ModFXType.WARBLE) {
      // setupModFXWFeedback (C:92-139)
      int a = modFXFeedback >> 1;
      int b = 2147483647 - ((a + 1073741824) >> 2) * 3;
      int c = Functions.multiply_32x32_rshift32(b, b);
      int d = Functions.multiply_32x32_rshift32(b, c);
      feedback = (int) (2147483648L - ((long) d << 2));

      // Adjust volume for flanger feedback.
      int squared = Functions.multiply_32x32_rshift32(feedback, feedback) << 1;
      int squared2 = Functions.multiply_32x32_rshift32(squared, squared) << 1;
      squared2 = Functions.multiply_32x32_rshift32(squared2, squared) << 1;
      squared2 = (Functions.multiply_32x32_rshift32(squared2, squared2) >> 4) * 23; // C:117-118
      postFXVolume[0] = Functions.multiply_32x32_rshift32(postFXVolume[0], 2147483647 - squared2);

      if (modFXType == ModFXType.FLANGER) {
        postFXVolume[0] <<= 1;
        modFXDelayOffset = kFlangerOffset;
        thisModFXDelayDepth = kFlangerAmplitude;
        modFXLFOWaveType = LfoType.TRIANGLE;
      } else if (modFXType == ModFXType.WARBLE) {
        postFXVolume[0] <<= 1;
        modFXDelayOffset = kFlangerOffset + Functions.multiply_32x32_rshift32(kFlangerOffset, modFXOffset);
        thisModFXDelayDepth = Functions.multiply_32x32_rshift32(modFXDelayOffset, modFXDepth) << 1;
        modFXLFOWaveType = LfoType.WARBLER;
      } else { // Phaser
        modFXLFOWaveType = LfoType.SINE;
      }
    } else if (modFXType == ModFXType.CHORUS
        || modFXType == ModFXType.CHORUS_STEREO
        || modFXType == ModFXType.DIMENSION) {
      // setupChorus (C:75-91)
      modFXDelayOffset = Functions.multiply_32x32_rshift32(kModFXMaxDelay, (modFXOffset >> 1) + 1073741824);
      thisModFXDelayDepth = Functions.multiply_32x32_rshift32(modFXDelayOffset, modFXDepth) << 2;
      modFXLFOWaveType = (modFXType == ModFXType.DIMENSION) ? LfoType.TRIANGLE : LfoType.SINE;
      postFXVolume[0] = Functions.multiply_32x32_rshift32(postFXVolume[0], 1518500250) << 1; // /sqrt(2)
    }

    // processModFXBuffer (C:141-160)
    if (modFXType == ModFXType.PHASER) {
      for (int i = 0; i < numSamples; i++) {
        int lfo = modFXLFO.render(1, modFXLFOWaveType, modFXRate);
        processOnePhaserSample(buffer[i], modFXDepth, feedback, lfo);
      }
      return;
    }

    final int width = (int) (0.97 * 2147483647.0); // 0.97 * ONE_Q31 (C:166)
    for (int i = 0; i < numSamples; i++) {
      // processModLFOs (C:162-178)
      int lfo1 = modFXLFO.render(1, modFXLFOWaveType, modFXRate);
      int lfo2;
      if (modFXType == ModFXType.WARBLE) {
        lfo2 = modFXLFOStereo.render(1, modFXLFOWaveType, Functions.multiply_32x32_rshift32(modFXRate, width) << 1);
      } else {
        lfo2 = -lfo1;
      }
      if (stereo) {
        processOneModFXSample(buffer[i], modFXDelayOffset, thisModFXDelayDepth, feedback, lfo1, lfo2, modFXType, true);
      } else {
        processOneModFXSample(buffer[i], modFXDelayOffset, thisModFXDelayDepth, feedback, lfo1, -lfo1, modFXType, false);
      }
    }
  }

  /** processOneModFXSample (ModFXProcessor.cpp:180-243). sample = {l, r}, modified in place. */
  private void processOneModFXSample(
      int[] sample,
      int modFXDelayOffset,
      int thisModFXDelayDepth,
      int feedback,
      int lfoOutput,
      int lfo2Output,
      ModFXType modFXType,
      boolean stereo) {
    int delayTime = Functions.multiply_32x32_rshift32(lfoOutput, thisModFXDelayDepth) + modFXDelayOffset;
    int strength2 = (delayTime & 65535) << 15;
    int strength1 = (65535 << 15) - strength2;
    int sample1Pos = modFXBufferWriteIndex - (delayTime >> 16);

    int modFXOutputL =
        Functions.multiply_32x32_rshift32_rounded(modFXBuffer[sample1Pos & kModFXBufferIndexMask][0], strength1)
            + Functions.multiply_32x32_rshift32_rounded(
                modFXBuffer[(sample1Pos - 1) & kModFXBufferIndexMask][0], strength2);

    if (stereo || modFXType == ModFXType.DIMENSION || modFXType == ModFXType.WARBLE) {
      delayTime = Functions.multiply_32x32_rshift32(lfo2Output, thisModFXDelayDepth) + modFXDelayOffset;
      strength2 = (delayTime & 65535) << 15;
      strength1 = (65535 << 15) - strength2;
      sample1Pos = modFXBufferWriteIndex - (delayTime >> 16);
    }

    int modFXOutputR =
        Functions.multiply_32x32_rshift32_rounded(modFXBuffer[sample1Pos & kModFXBufferIndexMask][1], strength1)
            + Functions.multiply_32x32_rshift32_rounded(
                modFXBuffer[(sample1Pos - 1) & kModFXBufferIndexMask][1], strength2);

    if (modFXType == ModFXType.FLANGER) {
      modFXOutputL = Functions.multiply_32x32_rshift32_rounded(modFXOutputL, feedback) << 2;
      modFXBuffer[modFXBufferWriteIndex][0] = modFXOutputL + sample[0]; // feedback
      modFXOutputR = Functions.multiply_32x32_rshift32_rounded(modFXOutputR, feedback) << 2;
      modFXBuffer[modFXBufferWriteIndex][1] = modFXOutputR + sample[1];
    } else if (modFXType == ModFXType.WARBLE) {
      modFXBuffer[modFXBufferWriteIndex][0] =
          Functions.multiply_32x32_rshift32_rounded(modFXOutputL, feedback) + sample[0];
      modFXBuffer[modFXBufferWriteIndex][1] =
          Functions.multiply_32x32_rshift32_rounded(modFXOutputR, feedback) + sample[1];
      modFXOutputL <<= 1;
      modFXOutputR <<= 1;
    } else { // Chorus, Dimension
      modFXOutputL <<= 1;
      modFXBuffer[modFXBufferWriteIndex][0] = sample[0];
      modFXOutputR <<= 1;
      modFXBuffer[modFXBufferWriteIndex][1] = sample[1];
    }

    if (modFXType == ModFXType.DIMENSION || modFXType == ModFXType.WARBLE) {
      sample[0] = modFXOutputL << 1;
      sample[1] = modFXOutputR << 1;
    } else {
      sample[0] += modFXOutputL;
      sample[1] += modFXOutputR;
    }

    modFXBufferWriteIndex = (modFXBufferWriteIndex + 1) & kModFXBufferIndexMask;
  }

  /** processOnePhaserSample (ModFXProcessor.cpp:245-269). "1" ~ 1073741824 here. */
  private void processOnePhaserSample(int[] sample, int modFXDepth, int feedback, int lfoOutput) {
    int a1 =
        1073741824
            - Functions.multiply_32x32_rshift32_rounded(
                (int) ((((long) lfoOutput) + 2147483648L) >> 1), modFXDepth);

    phaserMemory[0] = sample[0] + (Functions.multiply_32x32_rshift32_rounded(phaserMemory[0], feedback) << 1);
    phaserMemory[1] = sample[1] + (Functions.multiply_32x32_rshift32_rounded(phaserMemory[1], feedback) << 1);

    // Do the allpass filters.
    for (int[] ap : allpassMemory) {
      int whatWasInputL = phaserMemory[0];
      phaserMemory[0] = (Functions.multiply_32x32_rshift32_rounded(phaserMemory[0], -a1) << 2) + ap[0];
      ap[0] = (Functions.multiply_32x32_rshift32_rounded(phaserMemory[0], a1) << 2) + whatWasInputL;

      int whatWasInputR = phaserMemory[1];
      phaserMemory[1] = (Functions.multiply_32x32_rshift32_rounded(phaserMemory[1], -a1) << 2) + ap[1];
      ap[1] = (Functions.multiply_32x32_rshift32_rounded(phaserMemory[1], a1) << 2) + whatWasInputR;
    }

    sample[0] += phaserMemory[0];
    sample[1] += phaserMemory[1];
  }
}
