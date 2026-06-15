package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code deluge/util/functions.cpp} and the DSP portions of {@code
 * functions.h}. Every function, every constant, every shift matches the C++ source exactly. C
 * integer types are mapped as:
 *
 * <ul>
 *   <li>{@code int32_t} → Java {@code int} (signed 32-bit, same overflow behaviour for + - * with
 *       {@code (int)((long)x * y >> 32)} for multiplies, matching the firmware's Q31 helpers)
 *   <li>{@code uint32_t} → Java {@code int} (stored signed, compared unsigned with {@code
 *       Integer.compareUnsigned})
 *   <li>{@code int16_t} → Java {@code short}
 *   <li>{@code uint16_t} → Java {@code char} (zero-extended to int when read)
 *   <li>{@code int8_t/uint8_t} → Java {@code byte} (masked with {@code 0xFF} for unsigned)
 *   <li>{@code float/double} → Java {@code float/double} (IEEE 754, identical)
 *   <li>{@code bool} → Java {@code boolean}
 *   <li>{@code const int32_t[]} → Java {@code static final int[]}
 * </ul>
 *
 * <p>Firmware reference: {@code ~/a/DelugeFirmware/src/deluge/util/functions.cpp} lines 52-567 plus
 * {@code functions.h} lines 52-240, and {@code fixedpoint.h} for Q31 arithmetic.
 */
public final class Functions {

  private Functions() {}

  // ── Fixed-point Q31 arithmetic (port of fixedpoint.h) ──

  /** multiply_32x32_rshift32: (a * b) >> 32, signed (C: smmul instruction). */
  public static int multiply_32x32_rshift32(int a, int b) {
    return (int) (((long) a * (long) b) >> 32);
  }

  /** multiply_32x32_rshift32_rounded: (a * b + 0x80000000) >> 32. */
  public static int multiply_32x32_rshift32_rounded(int a, int b) {
    return (int) (((long) a * (long) b + 0x80000000L) >> 32);
  }

  /** multiply_accumulate_32x32_rshift32: acc + ((a * b) >> 32). */
  public static int multiply_accumulate_32x32_rshift32(int acc, int a, int b) {
    return acc + (int) (((long) a * (long) b) >> 32);
  }

  /** multiply_accumulate_32x32_rshift32_rounded. */
  public static int multiply_accumulate_32x32_rshift32_rounded(int acc, int a, int b) {
    return acc + (int) (((long) a * (long) b + 0x80000000L) >> 32);
  }

  /** ONE_Q31: 2^31 - 1 (the largest positive signed 32-bit value). */
  public static final int ONE_Q31 = 2147483647;

  /** NEGATIVE_ONE_Q31: -2^31. */
  public static final int NEGATIVE_ONE_Q31 = -2147483648;

  /** ONE_Q16: 2^27 (misnamed in the firmware; retained for compatibility). */
  public static final int ONE_Q16 = 134217728;

  /** kMaxSampleValue = 1 << kBitDepth = 1 << 24. */
  public static final int K_MAX_SAMPLE_VALUE = 1 << 24;

  // ── signed_saturate (port of functions.h / fixedpoint.h) ──

  /** signed_saturate<bits>(val): ASM SSAT. bits 1-31, Java emulation. */
  public static int signed_saturate(int val, int bits) {
    int limit = (1 << (bits - 1)) - 1;
    if (val > limit) {
      return limit;
    }
    if (val < -limit - 1) {
      return -limit - 1;
    }
    return val;
  }

  /** lshiftAndSaturate<bits>(val): saturate then left-shift. */
  public static int lshiftAndSaturate(int val, int bits) {
    return signed_saturate(val, 32 - bits) << bits;
  }

  public static int lshiftAndSaturateUnknown(int val, int lshift) {
    int bits = 32 - lshift;
    int saturated;
    if (bits >= 12 && bits <= 31) {
      saturated = signed_saturate(val, bits);
    } else {
      saturated = signed_saturate(val, 12);
    }
    return saturated << (lshift & 31);
  }

  // ── add_saturate / sub_saturate (port of fixedpoint.h) ──

  public static int add_saturate(int a, int b) {
    long r = (long) a + (long) b;
    if (r > ONE_Q31) return ONE_Q31;
    if (r < NEGATIVE_ONE_Q31) return NEGATIVE_ONE_Q31;
    return (int) r;
  }

  public static int sub_saturate(int a, int b) {
    long r = (long) a - (long) b;
    if (r > ONE_Q31) return ONE_Q31;
    if (r < NEGATIVE_ONE_Q31) return NEGATIVE_ONE_Q31;
    return (int) r;
  }

  // ── Param tables (port of functions.cpp lines 52-171) ──

  /**
   * Number of parameters. Must match {@code kNumParams} from {@code params.h}. Value: 55 local +
   * global params.
   */
  public static final int K_NUM_PARAMS = 55;

  // The runtime-initialised arrays from C.  In Java they are computed on first use.
  // See FunctionsInit below.

  /**
   * Port of getParamRange(p). Returns the "range" of the user preset knob — the strength with which
   * the stored value is folded into the cable combiner. (functions.cpp:56-83)
   */
  public static int getParamRange(int p) {
    // Using the firmware param IDs directly.  These must match params.h.
    switch (p) {
      case Param.LOCAL_ENV_0_ATTACK:
      case Param.LOCAL_ENV_1_ATTACK:
      case Param.LOCAL_ENV_2_ATTACK:
      case Param.LOCAL_ENV_3_ATTACK:
        return (int) (536870912L * 3L / 2L); // 536870912 * 1.5

      case Param.GLOBAL_DELAY_RATE:
        return 536870912;

      case Param.LOCAL_PITCH_ADJUST:
      case Param.LOCAL_OSC_A_PITCH_ADJUST:
      case Param.LOCAL_OSC_B_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_0_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_1_PITCH_ADJUST:
        return 536870912;

      case Param.LOCAL_LPF_FREQ:
        return (int) (536870912L * 14L / 10L); // 536870912 * 1.4

      default:
        return 1073741824;
    }
  }

  /**
   * Port of getParamNeutralValue(p). The "neutral" value used by getFinalParameterValue* as the
   * presetValue input. (functions.cpp:85-171)
   */
  public static int getParamNeutralValue(int p) {
    switch (p) {
      case Param.LOCAL_OSC_A_VOLUME:
      case Param.LOCAL_OSC_B_VOLUME:
      case Param.GLOBAL_VOLUME_POST_REVERB_SEND:
      case Param.LOCAL_NOISE_VOLUME:
      case Param.GLOBAL_REVERB_AMOUNT:
      case Param.GLOBAL_VOLUME_POST_FX:
      case Param.LOCAL_VOLUME:
        return 134217728;

      case Param.LOCAL_MODULATOR_0_VOLUME:
      case Param.LOCAL_MODULATOR_1_VOLUME:
        return 33554432;

      case Param.LOCAL_LPF_FREQ:
        return 2000000;
      case Param.LOCAL_HPF_FREQ:
        return 2672947;

      case Param.GLOBAL_LFO_FREQ_1:
      case Param.GLOBAL_LFO_FREQ_2:
      case Param.LOCAL_LFO_LOCAL_FREQ_1:
      case Param.LOCAL_LFO_LOCAL_FREQ_2:
      case Param.GLOBAL_MOD_FX_RATE:
        return 121739;

      case Param.LOCAL_LPF_RESONANCE:
      case Param.LOCAL_HPF_RESONANCE:
      case Param.LOCAL_LPF_MORPH:
      case Param.LOCAL_HPF_MORPH:
      case Param.LOCAL_FOLD:
        return 25 * 10737418;

      case Param.LOCAL_PAN:
      case Param.LOCAL_OSC_A_PHASE_WIDTH:
      case Param.LOCAL_OSC_B_PHASE_WIDTH:
        return 0;

      case Param.LOCAL_ENV_0_ATTACK:
      case Param.LOCAL_ENV_1_ATTACK:
      case Param.LOCAL_ENV_2_ATTACK:
      case Param.LOCAL_ENV_3_ATTACK:
        return 4096;

      case Param.LOCAL_ENV_0_RELEASE:
      case Param.LOCAL_ENV_1_RELEASE:
      case Param.LOCAL_ENV_2_RELEASE:
      case Param.LOCAL_ENV_3_RELEASE:
        return 140 << 9;

      case Param.LOCAL_ENV_0_DECAY:
      case Param.LOCAL_ENV_1_DECAY:
      case Param.LOCAL_ENV_2_DECAY:
      case Param.LOCAL_ENV_3_DECAY:
        return 70 << 9;

      case Param.LOCAL_ENV_0_SUSTAIN:
      case Param.LOCAL_ENV_1_SUSTAIN:
      case Param.LOCAL_ENV_2_SUSTAIN:
      case Param.LOCAL_ENV_3_SUSTAIN:
      case Param.GLOBAL_DELAY_FEEDBACK:
        return 1073741824;

      case Param.LOCAL_MODULATOR_0_FEEDBACK:
      case Param.LOCAL_MODULATOR_1_FEEDBACK:
      case Param.LOCAL_CARRIER_0_FEEDBACK:
      case Param.LOCAL_CARRIER_1_FEEDBACK:
        return 5931642;

      case Param.GLOBAL_DELAY_RATE:
      case Param.GLOBAL_ARP_RATE:
      case Param.LOCAL_PITCH_ADJUST:
      case Param.LOCAL_OSC_A_PITCH_ADJUST:
      case Param.LOCAL_OSC_B_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_0_PITCH_ADJUST:
      case Param.LOCAL_MODULATOR_1_PITCH_ADJUST:
        return K_MAX_SAMPLE_VALUE;

      case Param.GLOBAL_MOD_FX_DEPTH:
        return 526133494;

      default:
        return 0;
    }
  }

  // ── Final-value curves (functions.cpp:184-258) ──

  /**
   * getFinalParameterValueHybrid. Allows max output ±1073741824 (full pan range).
   * (functions.cpp:184-189)
   */
  public static int getFinalParameterValueHybrid(int paramNeutralValue, int patchedValue) {
    int preLimits = (paramNeutralValue >> 2) + (patchedValue >> 1);
    return signed_saturate(preLimits, 32 - 3) << 2;
  }

  /** getFinalParameterValueVolume. Parabola curve for volume params. (functions.cpp:191-226) */
  public static int getFinalParameterValueVolume(int paramNeutralValue, int patchedValue) {
    long temp = (long) patchedValue + 536870912L;
    if (temp < 0L) {
      temp = 0L;
    }
    int positivePatchedValue = (int) ((temp >> 16) * (temp >> 15));
    return lshiftAndSaturate(multiply_32x32_rshift32(positivePatchedValue, paramNeutralValue), 5);
  }

  /** getFinalParameterValueLinear. Linear curve for non-volume params. (functions.cpp:228-242) */
  public static int getFinalParameterValueLinear(int paramNeutralValue, int patchedValue) {
    long temp = (long) patchedValue + 536870912L;
    if (temp < 0L) {
      temp = 0L;
    }
    return lshiftAndSaturate(multiply_32x32_rshift32((int) temp, paramNeutralValue), 3);
  }

  /** getFinalParameterValueExp. Delegates to getExp. (functions.cpp:244-246) */
  public static int getFinalParameterValueExp(int paramNeutralValue, int patchedValue) {
    return getExp(paramNeutralValue, patchedValue);
  }

  /**
   * getFinalParameterValueExpWithDumbEnvelopeHack. Envelope rates use lookupReleaseRate for
   * decay/release and getExp on negated patchedValue for attack. (functions.cpp:248-258)
   */
  public static int getFinalParameterValueExpWithDumbEnvelopeHack(
      int paramNeutralValue, int patchedValue, int p) {
    // Decay and release → lookupReleaseRate
    if (Param.LOCAL_ENV_0_DECAY <= p && p <= Param.LOCAL_ENV_3_RELEASE) {
      return multiply_32x32_rshift32(paramNeutralValue, lookupReleaseRate(patchedValue));
    }
    // Attack → negate patchedValue for getExp
    if (Param.LOCAL_ENV_0_ATTACK <= p && p <= Param.LOCAL_ENV_3_ATTACK) {
      patchedValue = (patchedValue == Integer.MIN_VALUE) ? Integer.MAX_VALUE : -patchedValue;
    }
    return getFinalParameterValueExp(paramNeutralValue, patchedValue);
  }

  // ── Cable combiners (functions.cpp:260-290, patcher.cpp helpers) ──

  /** cableToLinearParamShortcut. Skips range adjustment. (functions.cpp:260-262: {@code >> 2}) */
  public static int cableToLinearParamShortcut(int sourceValue) {
    return sourceValue >> 2;
  }

  /** cableToExpParamShortcut. Skips range adjustment. (functions.cpp:264-266: {@code >> 2}) */
  public static int cableToExpParamShortcut(int sourceValue) {
    return sourceValue >> 2;
  }

  // ── shiftVolumeByDB (functions.cpp:443-490) ──
  // (used by song-level volume; port on demand — currently not used in DSP)

  // ── interpolateTable (functions.cpp:492-509) ──

  /**
   * Interpolate into a uint16_t[] lookup table. (functions.cpp:492-509). Clamped for Java array
   * safety (C table sizes chosen so whichValue stays in range).
   */
  public static int interpolateTable(
      int input, int numBitsInInput, int[] table, int numBitsInTableSize) {
    int whichValue = input >>> (numBitsInInput - numBitsInTableSize);
    if (whichValue < 0) whichValue = 0;
    int maxIndex = (1 << numBitsInTableSize);
    if (whichValue > maxIndex) whichValue = maxIndex;
    int value1 = table[whichValue];
    int value2 = table[Math.min(whichValue + 1, maxIndex)];

    int rshiftAmount = numBitsInInput - 15 - numBitsInTableSize;
    int rshifted;
    if (rshiftAmount >= 0) {
      rshifted = input >>> rshiftAmount;
    } else {
      rshifted = input << (-rshiftAmount);
    }

    int strength2 = rshifted & 32767;
    int strength1 = 32768 - strength2;
    return value1 * strength1 + value2 * strength2;
  }

  /** interpolateTableInverse. Verbatim port (functions.cpp:511-546). Table values are uint16. */
  public static int interpolateTableInverse(
      int tableValueBig, int numBitsInLookupOutput, int[] table, int numBitsInTableSize) {
    int tableValue = tableValueBig >> 15;
    int tableSize = 1 << numBitsInTableSize;

    int tableDirection = (table[0] < table[tableSize]) ? 1 : -1;

    // Check we're not off either end of the table
    if ((tableValue - table[0]) * tableDirection <= 0) {
      return 0;
    }
    if ((tableValue - table[tableSize]) * tableDirection >= 0) {
      return (1 << numBitsInLookupOutput) - 1;
    }

    int rangeStart = 0;
    int rangeEnd = tableSize;

    while (rangeStart + 1 < rangeEnd) {
      int examinePos = (rangeStart + rangeEnd) >> 1;
      if ((tableValue - table[examinePos]) * tableDirection >= 0) {
        rangeStart = examinePos;
      } else {
        rangeEnd = examinePos;
      }
    }

    int output = rangeStart << (numBitsInLookupOutput - numBitsInTableSize);
    output +=
        (int)
            ((long) (tableValueBig - ((table[rangeStart] & 0xFFFF) << 15))
                * (1 << (numBitsInLookupOutput - numBitsInTableSize))
                / (long) (((table[rangeStart + 1] & 0xFFFF) - (table[rangeStart] & 0xFFFF)) << 15));
    return output;
  }

  // ── getDecay8 / getDecay4 (functions.cpp:548-554) ──

  /** getDecay8. Fixed-point exponential decay. (functions.cpp:548-550) */
  public static int getDecay8(int input, int numBitsInInput) {
    return interpolateTable(input, numBitsInInput, LookupTables.decayTableSmall8, 8);
  }

  /** getDecay4. Fixed-point exponential decay (coarser). (functions.cpp:552-554) */
  public static int getDecay4(int input, int numBitsInInput) {
    return interpolateTable(input, numBitsInInput, LookupTables.decayTableSmall4, 8);
  }

  /** kMaxMenuValue (definitions_cxx.hpp:330). */
  public static final int K_MAX_MENU_VALUE = 50;

  /**
   * computeCurrentValueForUnsignedMenuItem (value_scaling.cpp:18). Scales a uint32 stored menu
   * value to [0, kMaxMenuValue]. {@code value} is uint32 → use 64-bit unsigned.
   */
  public static int computeCurrentValueForUnsignedMenuItem(int value) {
    return (int) (((value & 0xFFFFFFFFL) * K_MAX_MENU_VALUE + 2147483648L) >> 32);
  }

  /**
   * computeFinalValueForUnsignedMenuItem (value_scaling.cpp:60-62) — the inverse: scales a menu
   * index [0, kMaxMenuValue] to the stored uint32 ({@code value * 85899345}).
   */
  public static int computeFinalValueForUnsignedMenuItem(int value) {
    return value * 85899345;
  }

  // ── getExp (functions.cpp:556-565) ──

  /** Exponential mapping: presetValue * 2^(adjustment). (functions.cpp:556-565) */
  public static int getExp(int presetValue, int adjustment) {
    int magnitudeIncrease = (adjustment >> 26) + 2;
    int interp = interpolateTable(adjustment & 67108863, 26, LookupTables.expTableSmall, 8);
    int adjustedPresetValue = multiply_32x32_rshift32(presetValue, interp);
    return increaseMagnitudeAndSaturate(adjustedPresetValue, magnitudeIncrease);
  }

  /** increaseMagnitudeAndSaturate. (functions.h functions.cpp:564) */
  public static int increaseMagnitudeAndSaturate(int number, int magnitude) {
    if (magnitude > 0) {
      return lshiftAndSaturateUnknown(number, magnitude);
    }
    return number >> (-magnitude); // arithmetic right shift
  }

  // ── getWhichKernel (functions.cpp:2017-2037) ──

  /** C: functions.cpp:2017-2037 — pick the windowed-sinc kernel for a pitch (phaseIncrement). */
  public static int getWhichKernel(int phaseIncrement) {
    if (phaseIncrement < 17268826) {
      return 0; // half a semitone up
    } else {
      int whichKernel = 1;
      while (phaseIncrement >= 32599202) { // 11.5 semitones up
        phaseIncrement >>= 1;
        whichKernel += 2;
        if (whichKernel == 5) {
          break;
        }
      }
      if (phaseIncrement >= 23051117) { // 5.5 semitones up
        whichKernel++;
      }
      return whichKernel;
    }
  }

  // ── quickLog (functions.cpp:567-573) ──

  /** quickLog (functions.cpp:567-573). magnitude = getMagnitudeOld = 32 - clz (functions.h:394). */
  public static int quickLog(int input) {
    int magnitude =
        32 - Integer.numberOfLeadingZeros(input); // C getMagnitudeOld: 32 - clz, NOT 31 - clz
    int inputLSBs = increaseMagnitude(input, 26 - magnitude);
    return (magnitude << 25) + (inputLSBs & ~(1 << 26));
  }

  /**
   * increaseMagnitude (functions.h:361-366). C uses {@code int32_t} with {@code magnitude >= 0} and
   * an arithmetic right shift ({@code number >> -magnitude}); match it exactly (was {@code > 0} and
   * a logical {@code >>>}, which diverged for negative {@code number}).
   */
  public static int increaseMagnitude(int number, int magnitude) {
    if (magnitude >= 0) {
      return number << magnitude;
    }
    return number >> (-magnitude);
  }

  // ── instantTan (functions.cpp, line ~210 region) ──

  /**
   * instantTan. Maps a Q31 frequency to tan(f) in Q17. (functions.cpp — defined alongside
   * interpolateTable in the file). Clamped for Java array safety.
   */
  public static int instantTan(int input) {
    int whichValue = input >> 25; // 25
    // Clamp: C relies on undefined-behaviour memory access for out-of-range
    if (whichValue < 0) whichValue = 0;
    if (whichValue >= 64) whichValue = 63;
    int howMuchFurther = (input << 6) & 2147483647; // 6
    int value1 = LookupTables.tanTable[whichValue];
    int value2 = LookupTables.tanTable[whichValue + 1];
    long sum =
        (long) multiply_32x32_rshift32(value2, howMuchFurther)
            + multiply_32x32_rshift32(value1, 2147483647 - howMuchFurther);
    long shifted = sum << 1;
    if (shifted > 2147483647L) {
      return 2147483647;
    }
    return (int) shifted;
  }

  // ── lookupReleaseRate (functions.cpp) ──

  /**
   * lookupReleaseRate. Maps a patched value to a release rate via the 65-entry releaseRateTable64.
   * (functions.cpp)
   */
  public static int lookupReleaseRate(int input) {
    int magnitude = 24;
    int whichValue = input >> magnitude; // 25
    int howMuchFurther = (input << (31 - magnitude)) & 2147483647; // 6
    whichValue += 32; // Put it in the range 0 to 64
    if (whichValue < 0) {
      return LookupTables.releaseRateTable64[0];
    } else if (whichValue >= 64) {
      return LookupTables.releaseRateTable64[64];
    }
    int value1 = LookupTables.releaseRateTable64[whichValue];
    int value2 = LookupTables.releaseRateTable64[whichValue + 1];
    return (multiply_32x32_rshift32(value2, howMuchFurther)
            + multiply_32x32_rshift32(value1, 2147483647 - howMuchFurther))
        << 1;
  }

  // ── getParamFromUserValue (functions.cpp:1412) ──

  /**
   * getParamFromUserValue. Maps a menu user value (typically -25..25 or 0..50) to a stored Q31 knob
   * value. (functions.cpp:1412-1438)
   */
  public static int getParamFromUserValue(int p, int userValue) {
    switch (p) {
      case Param.STATIC_SIDECHAIN_ATTACK:
        return LookupTables.attackRateTable[userValue] * 4;

      case Param.STATIC_SIDECHAIN_RELEASE:
        return LookupTables.releaseRateTable[userValue] * 8;

      case Param.LOCAL_OSC_A_PHASE_WIDTH:
      case Param.LOCAL_OSC_B_PHASE_WIDTH:
        // C: (uint32_t)userValue * (85899345 >> 1); Java: use unsigned-multiply then truncate
        return (int) ((userValue & 0xFFFFFFFFL) * (85899345L >> 1));

      case Param.PATCH_CABLE:
      case Param.STATIC_SIDECHAIN_VOLUME:
        return (int) ((userValue & 0xFFFFFFFFL) * 21474836L);

      case Param.UNPATCHED_BASS:
      case Param.UNPATCHED_TREBLE:
        if (userValue == -50) return -2147483648;
        if (userValue == 0) return 0;
        return (int) ((userValue & 0xFFFFFFFFL) * 42949672L);

      default:
        // C: (uint32_t)userValue * 85899345 - 2147483648
        // 2147483648 = 0x80000000 = Integer.MIN_VALUE in Java
        return (int) ((userValue & 0xFFFFFFFFL) * 85899345L) - 0x80000000;
    }
  }

  // ── Noise / tanH / misc ──

  private static int jcong = 380116160;

  public static void resetNoiseSeed() {
    jcong = 380116160;
  }

  public static int getNoise() {
    jcong = 69069 * jcong + 1234567;
    return jcong;
  }

  // ── Wavefolder (dsp/util.hpp:23-80) ──

  /** C: dsp/util.hpp:27 — FOLD_MIN = 0.1 * ONE_Q31. */
  private static final int FOLD_MIN = (int) (0.1 * 2147483647.0);

  /** C: dsp/util.hpp:28 — THREE_FOURTHS = 0.75 * ONE_Q31. */
  private static final int THREE_FOURTHS = (int) (0.75 * 2147483647.0);

  /**
   * C: dsp/util.hpp:50-64 polynomialOscillatorApproximation — approximates wavefolding by flipping
   * the input around zero several times (the 4(3x/4 - x^3) polynomial applied twice).
   */
  private static int polynomialOscillatorApproximation(int x) {
    int x2 = 2 * multiply_32x32_rshift32(x, x);
    int x3 = 2 * multiply_32x32_rshift32(x2, x);
    int r1 = 8 * (multiply_32x32_rshift32(THREE_FOURTHS, x) - x3);

    int r2 = 2 * multiply_32x32_rshift32(r1, r1);
    int r3 = 2 * multiply_32x32_rshift32(r2, r1);
    return 8 * (multiply_32x32_rshift32(THREE_FOURTHS, r1) - r3);
  }

  /**
   * C: dsp/util.hpp:66-80 foldBufferPolyApproximation — the LOCAL_FOLD wavefolder applied to the
   * osc buffer before the filters (voice.cpp:1499/1585). Works on any contiguous sample range (mono
   * or interleaved stereo).
   */
  public static void foldBufferPolyApproximation(int[] buf, int from, int toExclusive, int level) {
    int foldLevel = add_saturate(level, FOLD_MIN);
    for (int i = from; i < toExclusive; i++) {
      int c = buf[i];
      int x = lshiftAndSaturate(multiply_32x32_rshift32(foldLevel, c), 8);
      // volume compensation
      buf[i] = polynomialOscillatorApproximation(x) >> 7;
    }
  }

  /**
   * C: functions.cpp:1500-1507 — the phase at which the given wave type crosses zero (used as the
   * base when a retrigger phase is applied).
   */
  public static int getOscInitialPhaseForZero(Oscillator.OscType waveType) {
    if (waveType == null) return 0;
    switch (waveType) {
      case TRIANGLE:
        return 1073741824;
      default:
        return 0;
    }
  }

  public static int interpolateTableSigned(
      int input, int numBitsInInput, int[] table, int numBitsInTableSize) {
    return interpolateTableSigned(input, numBitsInInput, table, 0, numBitsInTableSize);
  }

  public static int interpolateTableSigned(
      int input, int numBitsInInput, int[] table, int tableOffset, int numBitsInTableSize) {
    int whichValue = tableOffset + (input >>> (numBitsInInput - numBitsInTableSize));
    int rshiftAmount = numBitsInInput - 16 - numBitsInTableSize;
    int rshifted;
    if (rshiftAmount >= 0) {
      rshifted = input >>> rshiftAmount;
    } else {
      rshifted = input << (-rshiftAmount);
    }
    int strength2 = rshifted & 65535;
    int strength1 = 65536 - strength2;
    return (short) table[whichValue] * strength1 + (short) table[whichValue + 1] * strength2;
  }

  public static int interpolateTableSigned2d(
      int inputX,
      int inputY,
      int numBitsInInputX,
      int numBitsInInputY,
      int[] table,
      int numBitsInTableSizeX,
      int numBitsInTableSizeY) {
    int whichValue = inputY >>> (numBitsInInputY - numBitsInTableSizeY);
    int tableSizeOneRow = (1 << numBitsInTableSizeX) + 1;
    int value1 =
        interpolateTableSigned(
            inputX, numBitsInInputX, table, whichValue * tableSizeOneRow, numBitsInTableSizeX);
    int value2 =
        interpolateTableSigned(
            inputX,
            numBitsInInputX,
            table,
            (whichValue + 1) * tableSizeOneRow,
            numBitsInTableSizeX);
    int lshiftAmount = 31 + numBitsInTableSizeY - numBitsInInputY;
    int strength2;
    if (lshiftAmount >= 0) {
      strength2 = (inputY << lshiftAmount) & 2147483647;
    } else {
      strength2 = (inputY >>> (0 - lshiftAmount)) & 2147483647;
    }
    int strength1 = 2147483647 - strength2;
    return multiply_32x32_rshift32(value1, strength1) + multiply_32x32_rshift32(value2, strength2);
  }

  public static int getTanHUnknown(int input, int saturationAmount) {
    int workingValue;
    if (saturationAmount != 0) {
      workingValue = lshiftAndSaturateUnknown(input, saturationAmount) + 0x80000000;
    } else {
      workingValue = input + 0x80000000;
    }
    return interpolateTableSigned(workingValue, 32, LookupTables.tanHSmall, 8)
        >> (saturationAmount + 2);
  }

  public static int getTanHAntialiased(int input, int lastWorkingValue, int saturationAmount) {
    int workingValue = lshiftAndSaturateUnknown(input, saturationAmount) + 0x80000000;
    return interpolateTableSigned2d(
            workingValue, lastWorkingValue, 32, 32, LookupTables.tanH2d, 7, 6)
        >> (saturationAmount + 1);
  }

  /** getSine from waves.h:29 — interpolated sine lookup via sineWaveSmall table. */
  public static int getSine(int phase, int numBitsInInput) {
    return interpolateTableSigned(phase, numBitsInInput, LookupTables.sineWaveSmall, 8);
  }

  // ── getTriangle / getSquare (waves.h) ──

  /** getTriangle from waves.h — 4-segment triangle wave. Unsigned phase arithmetic. */
  public static int getTriangle(int phase) {
    int slope = 2;
    int offset = -2147483648; // 0x80000000u as signed
    if (Integer.compareUnsigned(phase, 0x80000000) >= 0) {
      slope = -2;
      offset = 2147483647; // 0x80000000u - 1
    }
    return slope * phase + offset;
  }

  /** getSquare from waves.h — full-scale Q31 square. */
  public static int getSquare(int phase) {
    return getSquare(phase, 0x80000000);
  }

  public static int getSquare(int phase, int phaseWidth) {
    return (Integer.compareUnsigned(phase, phaseWidth) >= 0) ? -2147483648 : 2147483647;
  }

  // ── Cable combine helpers (patcher.cpp) ──

  /** One multiplicative step of the linear cable combiner. */
  public static int patchCombineLinearStep(int runningTotal, int source, int strength) {
    int scaledSource = multiply_32x32_rshift32(source, strength);
    int madePositive = scaledSource + 536870912;
    int preLimits = multiply_32x32_rshift32(runningTotal, madePositive);
    return lshiftAndSaturate(preLimits, 3);
  }

  /** One additive step of the exp cable combiner. */
  public static int patchCombineExpStep(int runningTotal, int source, int strength) {
    return runningTotal + multiply_32x32_rshift32(source, strength);
  }
}
