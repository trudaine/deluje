package org.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge {@code sine_osc.h} SineOsc class. The firmware uses ARM
 * SIMD ({@code Argon<uint32_t>}) vectors; this Java port uses the equivalent scalar operations,
 * matching the firmware sample-by-sample.
 *
 * <p>The sine table is an interleaved array of 512 {@code int16_t} entries: even indices hold the
 * sine value, odd indices hold the difference (slope) to the next table entry. {@code doFMNew}
 * performs a single linear-interpolated sine lookup with an external phase offset (used for
 * FM/phase modulation).
 */
public final class SineOsc {

  private SineOsc() {}

  /** Table size exponent: 2^8 = 256 entries (×2 interleaved = 512). */
  private static final int K_SINE_TABLE_SIZE_MAGNITUDE = 8;

  /**
   * Interleaved sine-difference table matching {@code sineWaveDiff[512]} from the firmware. Even
   * indices: sine value (int16_t). Odd indices: difference to next entry (int16_t). Total: 512
   * entries (256 sine values + 256 diffs).
   */
  public static final short[] SINE_WAVE_DIFF = {
    0, 804, 804, 804, 1608, 802, 2410, 802, 3212, 799, 4011, 797, 4808, 794, 5602, 791,
    6393, 786, 7179, 783, 7962, 777, 8739, 773, 9512, 766, 10278, 761, 11039, 754, 11793, 746,
    12539, 740, 13279, 731, 14010, 722, 14732, 714, 15446, 705, 16151, 695, 16846, 684, 17530, 674,
    18204, 664, 18868, 651, 19519, 640, 20159, 628, 20787, 616, 21403, 602, 22005, 589, 22594, 576,
    23170, 561, 23731, 548, 24279, 532, 24811, 518, 25329, 503, 25832, 487, 26319, 471, 26790, 455,
    27245, 438, 27683, 422, 28105, 405, 28510, 388, 28898, 370, 29268, 353, 29621, 335, 29956, 317,
    30273, 298, 30571, 281, 30852, 261, 31113, 243, 31356, 224, 31580, 205, 31785, 186, 31971, 166,
    32137, 148, 32285, 127, 32412, 109, 32521, 88, 32609, 69, 32678, 50, 32728, 29, 32757, 10,
    32767, -10, 32757, -29, 32728, -50, 32678, -69, 32609, -88, 32521, -109, 32412, -127, 32285,
        -148,
    32137, -166, 31971, -186, 31785, -205, 31580, -224, 31356, -243, 31113, -261, 30852, -281,
        30571, -298,
    30273, -317, 29956, -335, 29621, -353, 29268, -370, 28898, -388, 28510, -405, 28105, -422,
        27683, -438,
    27245, -455, 26790, -471, 26319, -487, 25832, -503, 25329, -518, 24811, -532, 24279, -548,
        23731, -561,
    23170, -576, 22594, -589, 22005, -602, 21403, -616, 20787, -628, 20159, -640, 19519, -651,
        18868, -664,
    18204, -674, 17530, -684, 16846, -695, 16151, -705, 15446, -714, 14732, -722, 14010, -731,
        13279, -740,
    12539, -746, 11793, -754, 11039, -761, 10278, -766, 9512, -773, 8739, -777, 7962, -783, 7179,
        -786,
    6393, -791, 5602, -794, 4808, -797, 4011, -799, 3212, -802, 2410, -802, 1608, -804, 804, -804,
    0, -804, -804, -804, -1608, -802, -2410, -802, -3212, -799, -4011, -797, -4808, -794, -5602,
        -791,
    -6393, -786, -7179, -783, -7962, -777, -8739, -773, -9512, -766, -10278, -761, -11039, -754,
        -11793, -746,
    -12539, -740, -13279, -731, -14010, -722, -14732, -714, -15446, -705, -16151, -695, -16846,
        -684, -17530, -674,
    -18204, -664, -18868, -651, -19519, -640, -20159, -628, -20787, -616, -21403, -602, -22005,
        -589, -22594, -576,
    -23170, -561, -23731, -548, -24279, -532, -24811, -518, -25329, -503, -25832, -487, -26319,
        -471, -26790, -455,
    -27245, -438, -27683, -422, -28105, -405, -28510, -388, -28898, -370, -29268, -353, -29621,
        -335, -29956, -317,
    -30273, -298, -30571, -281, -30852, -261, -31113, -243, -31356, -224, -31580, -205, -31785,
        -186, -31971, -166,
    -32137, -148, -32285, -127, -32412, -109, -32521, -88, -32609, -69, -32678, -50, -32728, -29,
        -32757, -10,
    -32767, 10, -32757, 29, -32728, 50, -32678, 69, -32609, 88, -32521, 109, -32412, 127, -32285,
        148,
    -32137, 166, -31971, 186, -31785, 205, -31580, 224, -31356, 243, -31113, 261, -30852, 281,
        -30571, 298,
    -30273, 317, -29956, 335, -29621, 353, -29268, 370, -28898, 388, -28510, 405, -28105, 422,
        -27683, 438,
    -27245, 455, -26790, 471, -26319, 487, -25832, 503, -25329, 518, -24811, 532, -24279, 548,
        -23731, 561,
    -23170, 576, -22594, 589, -22005, 602, -21403, 616, -20787, 628, -20159, 640, -19519, 651,
        -18868, 664,
    -18204, 674, -17530, 684, -16846, 695, -16151, 705, -15446, 714, -14732, 722, -14010, 731,
        -13279, 740,
    -12539, 746, -11793, 754, -11039, 761, -10278, 766, -9512, 773, -8739, 777, -7962, 783, -7179,
        786,
    -6393, 791, -5602, 794, -4808, 797, -4011, 799, -3212, 802, -2410, 802, -1608, 804, -804, 804,
  };

  /**
   * Port of {@code SineOsc::doFMNew(uint32_t carrierPhase, uint32_t phaseShift)}. A single
   * linearly-interpolated sine lookup at phase {@code (carrierPhase >> 8) + phaseShift}. The 24-bit
   * effective phase wraps at 2^24 (16777216) due to the table size.
   *
   * @param carrierPhase 32-bit carrier phase (top 24 bits used via {@code >> 8})
   * @param phaseShift external phase offset in 24-bit domain (FM/modulation input)
   * @return interpolated sine value in Q31
   */
  public static int doFMNew(int carrierPhase, int phaseShift) {
    // uint32_t phaseSmall = (carrierPhase >> 8) + phaseShift;         // line 18
    int phaseSmall = (carrierPhase >>> 8) + phaseShift;
    // int32_t strength2 = phaseSmall & 65535;                         // line 19
    int strength2 = phaseSmall & 65535;
    // uint32_t readOffset = (phaseSmall >> (24-8-1)) & 0b0111111110;  // line 21
    int readOffset = (phaseSmall >>> (24 - 8 - 1)) & 0b0111111110;
    // uint32_t readValue = *(uint32_t*)&sineWaveDiff[readOffset];    // line 23
    // Read two adjacent int16_t values as one uint32_t (LE: sine=low, diff=high)
    int readValue =
        (SINE_WAVE_DIFF[readOffset] & 0xFFFF) | ((SINE_WAVE_DIFF[readOffset + 1] & 0xFFFF) << 16);
    // int32_t value = readValue << 16;                                 // line 24
    int value = readValue << 16;
    // int32_t diff = (int32_t)readValue >> 16;                         // line 25
    int diff = readValue >> 16;
    // return value + diff * strength2;                                  // line 26
    return value + diff * strength2;
  }

  /**
   * Scalar equivalent of {@code render()}: four-sample sine via linear interpolation. Matches the
   * SIMD vector path (@code Argon<uint32_t>}) sample-by-sample. Each of the 4 samples uses the
   * phase increment to advance the phase.
   *
   * @param phase 4-sample phase array (updated in-place)
   * @param phaseIncrement per-sample phase increment
   * @param output 4-sample output array (Q31)
   */
  public static void render4(int[] phase, int phaseIncrement, int[] output) {
    for (int i = 0; i < 4; i++) {
      phase[i] += phaseIncrement * (i + 1); // advance 1, 2, 3, 4 steps
      // render one sample (scalar equivalent of the vector render)
      int strength2 = (phase[i] >>> (32 - 16 - K_SINE_TABLE_SIZE_MAGNITUDE + 1)) & 0x7FFF;
      int index = ((phase[i] >>> (32 - K_SINE_TABLE_SIZE_MAGNITUDE)) << 1);
      int s = SINE_WAVE_DIFF[index] & 0xFFFF;
      int d = SINE_WAVE_DIFF[index + 1] & 0xFFFF;
      int sine = (short) s << 16;
      int diff = (short) d;
      output[i] = sine + diff * strength2 * 2; // MultiplyAddFixedPoint equivalent
    }
  }

  /**
   * Vector-equivalent doFM: four carriers with four phase shifts. Port of {@code
   * doFMVector(phaseVector, phaseShift)}.
   *
   * @param phaseVector 4 carrier phases
   * @param phaseShift 4 phase shifts (24-bit domain)
   * @param output 4 output samples (Q31)
   */
  public static void doFMVector4(int[] phaseVector, int[] phaseShift, int[] output) {
    for (int i = 0; i < 4; i++) {
      int effectivePhase = (phaseVector[i] >>> 8) + phaseShift[i];
      int strength2 = effectivePhase & 65535;
      int readOffset = (effectivePhase >>> (24 - 8 - 1)) & 0b0111111110;
      int readValue =
          (SINE_WAVE_DIFF[readOffset] & 0xFFFF) | ((SINE_WAVE_DIFF[readOffset + 1] & 0xFFFF) << 16);
      output[i] = (readValue << 16) + ((readValue >> 16) * strength2);
    }
  }
}
