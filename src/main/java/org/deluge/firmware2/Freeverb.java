package org.deluge.firmware2;

import java.util.Arrays;

/**
 * Faithful line-by-line port of the Deluge Freeverb reverb model.
 *
 * <p>C sources:
 *
 * <ul>
 *   <li>{@code dsp/reverb/freeverb/freeverb.cpp} (105 lines) + {@code freeverb.hpp} (165)
 *   <li>{@code dsp/reverb/freeverb/comb.hpp} (76 lines)
 *   <li>{@code dsp/reverb/freeverb/allpass.hpp} (62 lines)
 *   <li>{@code dsp/reverb/freeverb/tuning.h} (72 lines)
 *   <li>{@code dsp/reverb/base.hpp} (63 lines)
 * </ul>
 *
 * <p>Freeverb is a Schroeder-Moorer reverb: 8 parallel comb filters → 4 series allpass filters.
 * Stereo width, room size, damping, and wet/dry mix are adjustable. Input is summed to mono before
 * processing.
 */
public class Freeverb {

  // ── Tuning constants (tuning.h:27-41) ──

  /** C: tuning.h:27 */
  static final int NUMCOMBS = 8;

  /** C: tuning.h:28 */
  static final int NUMALLPASSES = 4;

  /** C: tuning.h:30 */
  static final float FIXEDGAIN = 0.015f;

  /** C: tuning.h:31 */
  static final float SCALEWET = 3;

  /** C: tuning.h:32 */
  static final float SCALEDRY = 2;

  /** C: tuning.h:33 */
  static final float SCALEDAMP = 0.4f;

  /** C: tuning.h:34 */
  static final float SCALEROOM = 0.28f;

  /** C: tuning.h:35 */
  static final float OFFSETROOM = 0.7f;

  /** C: tuning.h:36 */
  static final float INITIALROOM = 0.5f;

  /** C: tuning.h:37 */
  static final float INITIALDAMP = 0.5f;

  /** C: tuning.h:38 */
  static final float INITIALWET = 1.0f / SCALEWET;

  /** C: tuning.h:39 */
  static final float INITIALDRY = 0;

  /** C: tuning.h:40 */
  static final float INITIALWIDTH = 1;

  /** C: tuning.h:41 */
  static final int STEREOSPREAD = 23;

  // Comb filter buffer sizes (tuning.h:47-62)
  static final int COMB_L1 = 1116, COMB_R1 = 1116 + STEREOSPREAD;
  static final int COMB_L2 = 1188, COMB_R2 = 1188 + STEREOSPREAD;
  static final int COMB_L3 = 1277, COMB_R3 = 1277 + STEREOSPREAD;
  static final int COMB_L4 = 1356, COMB_R4 = 1356 + STEREOSPREAD;
  static final int COMB_L5 = 1422, COMB_R5 = 1422 + STEREOSPREAD;
  static final int COMB_L6 = 1491, COMB_R6 = 1491 + STEREOSPREAD;
  static final int COMB_L7 = 1557, COMB_R7 = 1557 + STEREOSPREAD;
  static final int COMB_L8 = 1617, COMB_R8 = 1617 + STEREOSPREAD;

  // Allpass buffer sizes (tuning.h:63-70)
  static final int ALLPASS_L1 = 556, ALLPASS_R1 = 556 + STEREOSPREAD;
  static final int ALLPASS_L2 = 441, ALLPASS_R2 = 441 + STEREOSPREAD;
  static final int ALLPASS_L3 = 341, ALLPASS_R3 = 341 + STEREOSPREAD;
  static final int ALLPASS_L4 = 225, ALLPASS_R4 = 225 + STEREOSPREAD;

  // ── Comb filter (freeverb/comb.hpp:32-75) ──

  /**
   * C: freeverb/comb.hpp:32-75. Comb filter with feedback and lowpass damping.
   *
   * <pre>
   * output = buffer[bufidx]
   * filterstore = (output*damp2 + filterstore*damp1) << 1
   * buffer[bufidx] = input + (filterstore*feedback) << 1
   * </pre>
   */
  public static class Comb {
    int[] buffer; // C: buffer_ (std::span<int32_t>)
    int bufidx; // C: bufidx_
    int feedback; // C: feedback_
    int filterstore; // C: filterstore_
    int damp1; // C: damp1_
    int damp2; // C: damp2_

    /** C: comb.hpp:35 — setBuffer */
    public void setBuffer(int[] buf) {
      this.buffer = buf;
    }

    /** C: comb.hpp:37 — mute */
    public void mute() {
      Arrays.fill(buffer, 0);
    }

    /** C: comb.hpp:39-42 — setDamp */
    public void setDamp(float val) {
      damp1 = (int) (val * Integer.MAX_VALUE); // C:40 — val * numeric_limits<int32_t>::max()
      damp2 = Integer.MAX_VALUE - damp1; // C:41
    }

    /** C: comb.hpp:46 — setFeedback */
    public void setFeedback(int val) {
      feedback = val;
    }

    /** C: comb.hpp:51-65 — process */
    public int process(int input) {
      int output = buffer[bufidx]; // C:52

      // C:54-56 — lowpass filter on feedback
      filterstore =
          (Functions.multiply_32x32_rshift32_rounded(output, damp2)
                  + Functions.multiply_32x32_rshift32_rounded(filterstore, damp1))
              << 1;

      // C:58
      buffer[bufidx] =
          input + (Functions.multiply_32x32_rshift32_rounded(filterstore, feedback) << 1);

      // C:60-62 — advance index
      if (++bufidx >= buffer.length) {
        bufidx = 0;
      }

      return output;
    }
  }

  // ── Allpass filter (freeverb/allpass.hpp:31-61) ──

  /**
   * C: freeverb/allpass.hpp:31-61. Allpass filter.
   *
   * <pre>
   * bufout = buffer[bufidx]
   * output = -input + bufout
   * buffer[bufidx] = input + (bufout >> 1)  // feedback hardcoded to 0.5
   * </pre>
   */
  public static class Allpass {
    int[] buffer; // C: buffer_
    int bufidx; // C: bufidx_
    int feedback; // C: feedback_ (Q31)

    /** C: allpass.hpp:35 — setBuffer */
    public void setBuffer(int[] buf) {
      this.buffer = buf;
    }

    /** C: allpass.hpp:37 — mute */
    public void mute() {
      Arrays.fill(buffer, 0);
    }

    /** C: allpass.hpp:39 — setFeedback (float → Q31) */
    public void setFeedback(float val) {
      feedback = (int) (val * (float) Integer.MAX_VALUE);
    }

    /** C: allpass.hpp:43-55 — process */
    public int process(int input) {
      int bufout = buffer[bufidx]; // C:44
      int output = -input + bufout; // C:45

      // C:47 — shortcut: feedback was always 0.5 by default, so bufout>>1
      buffer[bufidx] = input + (bufout >> 1);

      // C:50-52 — advance index
      if (++bufidx >= buffer.length) {
        bufidx = 0;
      }

      return output;
    }
  }

  // ── Freeverb state ──

  // C: freeverb.hpp:114-121
  float roomsize = INITIALROOM; // C:115
  float damp = INITIALDAMP; // C:116
  float wet; // C:117
  float wet1; // C:118
  int wet2; // C:119
  float dry; // C:120
  float width = INITIALWIDTH; // C:121

  // C: freeverb.hpp:128-133
  final Comb[] combL = new Comb[NUMCOMBS]; // C:128
  final Comb[] combR = new Comb[NUMCOMBS]; // C:129
  final Allpass[] allpassL = new Allpass[NUMALLPASSES]; // C:132
  final Allpass[] allpassR = new Allpass[NUMALLPASSES]; // C:133

  // C: freeverb.hpp:136-161 — buffer arrays
  final int[][] combBufL, combBufR;
  final int[][] allpassBufL, allpassBufR;

  // C: base.hpp:60-61 — pan amplitudes
  int panLeft;
  int panRight;

  // C: freeverb.hpp:163 — input HPF state
  int reverbSendPostLpf;

  // ── Constructor (freeverb.cpp:29-73) ──

  /** C: freeverb.cpp:29-73 */
  public Freeverb() {
    // Allocate comb buffers
    combBufL =
        new int[][] {
          new int[COMB_L1], new int[COMB_L2], new int[COMB_L3], new int[COMB_L4],
          new int[COMB_L5], new int[COMB_L6], new int[COMB_L7], new int[COMB_L8]
        };
    combBufR =
        new int[][] {
          new int[COMB_R1], new int[COMB_R2], new int[COMB_R3], new int[COMB_R4],
          new int[COMB_R5], new int[COMB_R6], new int[COMB_R7], new int[COMB_R8]
        };
    allpassBufL =
        new int[][] {
          new int[ALLPASS_L1], new int[ALLPASS_L2], new int[ALLPASS_L3], new int[ALLPASS_L4]
        };
    allpassBufR =
        new int[][] {
          new int[ALLPASS_R1], new int[ALLPASS_R2], new int[ALLPASS_R3], new int[ALLPASS_R4]
        };

    // C:30-54 — tie components to buffers
    for (int i = 0; i < NUMCOMBS; i++) {
      combL[i] = new Comb();
      combL[i].setBuffer(combBufL[i]);
      combR[i] = new Comb();
      combR[i].setBuffer(combBufR[i]);
    }
    for (int i = 0; i < NUMALLPASSES; i++) {
      allpassL[i] = new Allpass();
      allpassL[i].setBuffer(allpassBufL[i]);
      allpassR[i] = new Allpass();
      allpassR[i].setBuffer(allpassBufR[i]);
    }

    // C:57-64 — set default allpass feedback to 0.5
    for (int i = 0; i < NUMALLPASSES; i++) {
      allpassL[i].setFeedback(0.5f);
      allpassR[i].setFeedback(0.5f);
    }

    // C:65-69 — set initial parameters
    setWet(INITIALWET);
    setRoomSize(INITIALROOM);
    setDry(INITIALDRY);
    setDamping(INITIALDAMP);
    setWidth(INITIALWIDTH);

    // C:72
    mute();
  }

  // ── mute (freeverb.cpp:75-84) ──

  /** C: freeverb.cpp:75-84 */
  public void mute() {
    for (int i = 0; i < NUMCOMBS; i++) {
      combL[i].mute();
      combR[i].mute();
    }
    for (int i = 0; i < NUMALLPASSES; i++) {
      allpassL[i].mute();
      allpassR[i].mute();
    }
  }

  // ── Setters (freeverb.hpp:41-71, freeverb.cpp:86-103) ──

  /** C: freeverb.hpp:41-43 — setRoomSize */
  public void setRoomSize(float value) {
    roomsize = (value * SCALEROOM) + OFFSETROOM; // C:42
    update(); // C:43
  }

  /** C: freeverb.hpp:46 — getRoomSize */
  public float getRoomSize() {
    return (roomsize - OFFSETROOM) / SCALEROOM;
  }

  /** C: freeverb.hpp:48-51 — setDamping */
  public void setDamping(float value) {
    damp = value * SCALEDAMP; // C:49
    update(); // C:50
  }

  /** C: freeverb.hpp:53 — getDamping */
  public float getDamping() {
    return damp / SCALEDAMP;
  }

  /** C: freeverb.hpp:55-57 — setWet */
  public void setWet(float value) {
    wet = value * SCALEWET; // C:56
    update(); // C:57
  }

  /** C: freeverb.hpp:60 — getWet */
  public float getWet() {
    return wet / SCALEWET;
  }

  /** C: freeverb.hpp:62 — setDry */
  public void setDry(float value) {
    dry = value * SCALEDRY;
  }

  /** C: freeverb.hpp:64 — getDry */
  public float getDry() {
    return dry / SCALEDRY;
  }

  /** C: freeverb.hpp:66-69 — setWidth */
  public void setWidth(float value) {
    width = value; // C:67
    update(); // C:68
  }

  /** C: freeverb.hpp:71 — getWidth */
  public float getWidth() {
    return width;
  }

  /** C: base.hpp:13-16 — setPanLevels */
  public void setPanLevels(int ampLeft, int ampRight) {
    panLeft = ampLeft;
    panRight = ampRight;
  }

  // ── update (freeverb.cpp:86-103) ──

  /** C: freeverb.cpp:86-103 — recalculate internal values after parameter change */
  private void update() {
    // C:89-90 — stereo width
    wet1 = wet * (width / 2.0f + 0.5f);
    wet2 = (int) (((1.0f - width) / 2.0f) / (width / 2.0f + 0.5f) * (float) Integer.MAX_VALUE);

    // C:94-97 — set comb feedback from room size
    for (int i = 0; i < NUMCOMBS; i++) {
      combL[i].setFeedback((int) (roomsize * (float) Integer.MAX_VALUE));
      combR[i].setFeedback((int) (roomsize * (float) Integer.MAX_VALUE));
    }

    // C:99-102 — set comb damping
    for (int i = 0; i < NUMCOMBS; i++) {
      combL[i].setDamp(damp);
      combR[i].setDamp(damp);
    }
  }

  // ── ProcessOne (freeverb.hpp:73-96) ──

  /**
   * C: freeverb.hpp:73-96 — process a single sample.
   *
   * @param input mono input sample (Q31)
   * @param outputL accumulator for left output (modified in-place via array)
   * @param outputR accumulator for right output
   */
  void processOne(int input, int[] outputLR) {
    int outL = 0; // C:74
    int outR = 0; // C:75

    // C:78-81 — parallel comb filters
    for (int i = 0; i < NUMCOMBS; i++) {
      outL += combL[i].process(input);
      outR += combR[i].process(input);
    }

    // C:84-87 — series allpass filters
    for (int i = 0; i < NUMALLPASSES; i++) {
      outL = allpassL[i].process(outL);
      outR = allpassR[i].process(outR);
    }

    // C:90-91 — stereo cross-feed
    outL = (outL + Functions.multiply_32x32_rshift32_rounded(outR, wet2)) << 1;
    outR = (outR + Functions.multiply_32x32_rshift32_rounded(outL, wet2)) << 1;

    // C:94-95 — accumulate to output with pan
    outputLR[0] += Functions.multiply_32x32_rshift32_rounded(outL, panLeft);
    outputLR[1] += Functions.multiply_32x32_rshift32_rounded(outR, panRight);
  }

  // ── process (freeverb.hpp:98-109) ──

  /**
   * C: freeverb.hpp:98-109 — process a buffer of mono input samples. Output is accumulated (added)
   * into the outputLR array of [l, r] per sample. Input array is modified in-place (HPF applied).
   */
  public void process(int[] input, int[][] outputLR) {
    // C:100-104 — HPF on reverb input to remove DC offset (~40Hz corner)
    for (int i = 0; i < input.length; i++) {
      int distanceToGo = input[i] - reverbSendPostLpf; // C:101
      reverbSendPostLpf += distanceToGo >> 11; // C:102
      input[i] -= reverbSendPostLpf; // C:103
    }

    // C:106-108
    for (int i = 0; i < input.length; i++) {
      processOne(input[i], outputLR[i]);
    }
  }
}
