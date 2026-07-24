package org.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge {@code oscillator.cpp} Oscillator::renderOsc. Covers all
 * waveform types: SAW, SQUARE, SINE, TRIANGLE, ANALOG_SAW_2, ANALOG_SQUARE, WAVETABLE, and SAMPLE.
 * SIMD operations (vld1q_s32 etc.) are replaced with equivalent scalar loops.
 *
 * <p>Firmware reference: {@code src/deluge/dsp/oscillators/oscillator.cpp} lines 28-509.
 */
public final class Oscillator {

  private Oscillator() {}

  private static final ThreadLocal<int[]> rawScratch = ThreadLocal.withInitial(() -> new int[0]);

  /** Waveform type, matching the firmware OscType enum. */
  public enum OscType {
    SINE,
    TRIANGLE,
    SAW,
    SQUARE,
    ANALOG_SAW_2,
    ANALOG_SQUARE,
    WAVETABLE,
    SAMPLE,
    // C definitions_cxx.hpp:367 has DX7 after SAMPLE (index 8); rendered per-source in
    // voice.cpp:2371.
    DX7,
    // Live-input sources (C OscType::INPUT_L/R/STEREO; rendered in voice.cpp:2232-2360).
    INPUT_L,
    INPUT_R,
    INPUT_STEREO
  }

  // ── Helper: getTableNumber (port of dsp::getTableNumber) ──
  // Maps a phase increment to a band-limited table index + size magnitude.

  /**
   * Verbatim port of dsp::getTableNumber (basic_waves.cpp:175-236). Returns [tableNumber,
   * tableSizeMagnitude]. {@code phaseIncrement} is uint32_t in C, so compare unsigned.
   * Size-magnitude is 13/12/11/10/9 by band (table size = 2^mag + 1).
   */
  public static int[] getTableNumber(int phaseIncrement) {
    long pi = phaseIncrement & 0xFFFFFFFFL;
    if (pi <= 1247086L) return new int[] {0, 13};
    else if (pi <= 1764571L) return new int[] {1, 12};
    else if (pi <= 2494173L) return new int[] {2, 12};
    else if (pi <= 3526245L) return new int[] {3, 11};
    else if (pi <= 4982560L) return new int[] {4, 11};
    else if (pi <= 7040929L) return new int[] {5, 11};
    else if (pi <= 9988296L) return new int[] {6, 11};
    else if (pi <= 14035840L) return new int[] {7, 11};
    else if (pi <= 19701684L) return new int[] {8, 11};
    else if (pi <= 28256363L) return new int[] {9, 11};
    else if (pi <= 40518559L) return new int[] {10, 11};
    else if (pi <= 55063683L) return new int[] {11, 11};
    else if (pi <= 79536431L) return new int[] {12, 11};
    else if (pi <= 113025455L) return new int[] {13, 11};
    else if (pi <= 165191049L) return new int[] {14, 10};
    else if (pi <= 238609294L) return new int[] {15, 10};
    else if (pi <= 306783378L) return new int[] {16, 10};
    else if (pi <= 429496729L) return new int[] {17, 10};
    else if (pi <= 715827882L) return new int[] {18, 9};
    else return new int[] {19, 9};
  }

  /** Port of getSquare(phase, pulseWidth) — returns full-scale Q31 square wave. */
  public static int getSquare(int phase, int pulseWidth) {
    return (Integer.compareUnsigned(phase, pulseWidth) >= 0)
        ? Functions.NEGATIVE_ONE_Q31
        : Functions.ONE_Q31;
  }

  /** Port of getSquareSmall — returns half-scale for fixed-amplitude rendering. */
  public static int getSquareSmall(int phase, int pulseWidth) {
    return (Integer.compareUnsigned(phase, pulseWidth) >= 0) ? -1073741824 : 1073741823;
  }

  /** Port of getTriangleSmall — 4-segment triangle wave from phase. */
  public static int getTriangleSmall(int phase) {
    int p = phase;
    if (p < 0) {
      p = -p;
    }
    return p - 1073741824;
  }

  // ── Crude saw (no table) — port of renderCrudeSawWave* ──

  /** Port of renderCrudeSawWaveWithAmplitude. Crude aliasing saw, accumulating. */
  public static void renderCrudeSawWithAmp(
      int[] buf, int off, int n, int[] phaseOut, int phaseInc, int amp, int ampInc) {
    int p = phaseOut[0];
    int a = amp;
    for (int i = 0; i < n; i++) {
      p += phaseInc;
      a += ampInc;
      buf[off + i] = Functions.multiply_accumulate_32x32_rshift32_rounded(buf[off + i], p, a);
    }
    phaseOut[0] = p;
  }

  /** Port of renderCrudeSawWaveWithoutAmplitude. Crude saw, overwriting. */
  public static void renderCrudeSawNoAmp(int[] buf, int off, int n, int[] phaseOut, int phaseInc) {
    int p = phaseOut[0];
    for (int i = 0; i < n; i++) {
      p += phaseInc;
      buf[off + i] = p >> 1;
    }
    phaseOut[0] = p;
  }

  // ── MaybeStorePhase (oscillator.cpp:530-534) ──

  private static void maybeStorePhase(
      OscType type, int[] startPhase, int phase, boolean doPulseWave) {
    if (!(doPulseWave && type != OscType.SQUARE)) {
      startPhase[0] = phase;
    }
  }

  // ── Oscillator hard sync (render_wave.h:25-90 renderOscSync + oscillator.cpp:475-498) ──

  /**
   * Raw table-wave segment: same interpolation as {@link #renderWave} but writes the RAW values (no
   * amplitude, overwrite) — the scalar equivalent of the C's storeVectorWaveForOneSync lambda
   * (oscillator.cpp:479-487 storing waveRenderingFunctionGeneral vectors).
   */
  private static void renderWaveRawSegment(
      short[] table,
      int tableSizeMagnitude,
      int[] buf,
      int from,
      int toExclusive,
      int phaseIncrement,
      int phase,
      int phaseToAdd) {
    int currentPhase = phase;
    for (int i = from; i < toExclusive; i++) {
      currentPhase += phaseIncrement;
      int p = currentPhase + phaseToAdd;
      int whichValue = p >>> (32 - tableSizeMagnitude);
      long v1 = table[whichValue];
      long v2 = table[whichValue + 1];
      long frac = (p >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      long v1_32 = v1 << 16;
      long interpolatedDiff = (((v2 << 16) - v1_32) * frac) >> 16;
      buf[i] = (int) (v1_32 + interpolatedDiff);
    }
  }

  /** {@code int[]}-table overload of {@link #renderWaveRawSegment} (e.g. sineWaveSmall). */
  private static void renderWaveRawSegment(
      int[] table,
      int tableSizeMagnitude,
      int[] buf,
      int from,
      int toExclusive,
      int phaseIncrement,
      int phase,
      int phaseToAdd) {
    int currentPhase = phase;
    for (int i = from; i < toExclusive; i++) {
      currentPhase += phaseIncrement;
      int p = currentPhase + phaseToAdd;
      int whichValue = p >>> (32 - tableSizeMagnitude);
      long v1 = table[whichValue];
      long v2 = table[whichValue + 1];
      long frac = (p >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      long v1_32 = v1 << 16;
      long interpolatedDiff = (((v2 << 16) - v1_32) * frac) >> 16;
      buf[i] = (int) (v1_32 + interpolatedDiff);
    }
  }

  /**
   * Raw pulse segment: the per-sample math of {@link #renderPulseWave} (C {@code
   * waveRenderingFunctionPulse}, vector_rendering_function.h:51-99) writing RAW values — the scalar
   * equivalent of the C's pulse storeVectorWaveForOneSync lambda (oscillator.cpp:428-436).
   */
  private static void renderPulseRawSegment(
      short[] table,
      int tableSizeMagnitude,
      int[] buf,
      int from,
      int toExclusive,
      int phaseIncrement,
      int phase,
      int phaseToAdd) {
    int currentPhase = phase;
    for (int i = from; i < toExclusive; i++) {
      currentPhase += phaseIncrement;
      int pa = currentPhase;
      int pb = currentPhase + phaseToAdd;

      int idxA = pa >>> (32 - tableSizeMagnitude);
      int rshiftedA = (idxA >>> 16) & 0x7FFF;
      int valueA1 = table[idxA];
      int valueA2 = table[idxA + 1];
      int strengthA1 = (rshiftedA | 0x8000) - 0x10000;
      int strengthA2 = -32768 - strengthA1;
      long outA = sat32(2L * strengthA2 * valueA2);
      outA = sat32(outA + sat32(2L * strengthA1 * valueA1));

      int idxB = pb >>> (32 - tableSizeMagnitude);
      int rshiftedB = (idxB >>> 16) & 0x7FFF;
      int valueB1 = table[idxB];
      int valueB2 = table[idxB + 1];
      int strengthB2 = rshiftedB;
      int strengthB1 = 0x7FFF - strengthB2;
      long outB = sat32(2L * strengthB2 * valueB2);
      outB = sat32(outB + sat32(2L * strengthB1 * valueB1));

      long prod = outA * outB;
      long rounded = prod + (1L << 30);
      int mrf = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded >> 31));
      buf[i] = Functions.lshiftAndSaturate(mrf, 1);
    }
  }

  /**
   * C: Oscillator::applyAmplitudeVectorToBuffer (oscillator.cpp:510-529) — applies the (already
   * doubled, per callRenderWave) amplitude ramp to the raw synced wave and accumulates into the
   * output. Scalar equivalent using this port's renderWave amplitude convention ({@code
   * (amp*val)>>31} with saturating accumulate).
   */
  private static void applyAmplitudeVectorToBuffer(
      int amplitude, int numSamples, int amplitudeIncrement, int[] out, int outOff, int[] raw) {
    int ampNow = amplitude;
    for (int i = 0; i < numSamples; i++) {
      ampNow += amplitudeIncrement;
      // C oscillator.cpp:516-521: vqdmulh(ampVector(halved), wave) + wrapping vaddq —
      // net sat(((amp>>1)*val)>>31).
      int withAmp = sat32(((long) (ampNow >> 1) * raw[i]) >> 31);
      out[outOff + i] = out[outOff + i] + withAmp;
    }
  }

  /**
   * Port of render_wave.h:25-90 {@code renderOscSync} for table waves: renders raw wave segments
   * between hard-sync resets, blending the crossover sample with a half-sine fade, then applies the
   * amplitude ramp (or writes raw when {@code !applyAmplitude}, matching the C's direct bufferStart
   * use). Returns the updated 32-bit phase.
   */
  private static int renderWaveSync(
      Object table, // short[] or int[]
      int tableSizeMagnitude,
      int amplitude,
      int[] outputBuffer,
      int offset,
      int numSamples,
      int phaseIncrement,
      int phase,
      boolean applyAmplitude,
      int phaseToAdd,
      int amplitudeIncrement,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase) {
    return renderWaveSync(
        table,
        tableSizeMagnitude,
        amplitude,
        outputBuffer,
        offset,
        numSamples,
        phaseIncrement,
        phase,
        applyAmplitude,
        phaseToAdd,
        amplitudeIncrement,
        resetterPhase,
        resetterPhaseIncrement,
        resetterDivideByPhaseIncrement,
        retriggerPhase,
        false);
  }

  /**
   * As above, with {@code pulseWave} selecting the C's {@code waveRenderingFunctionPulse} segment
   * renderer (oscillator.cpp:428-449 — the synced variable-width pulse path).
   */
  private static int renderWaveSync(
      Object table, // short[] or int[]
      int tableSizeMagnitude,
      int amplitude,
      int[] outputBuffer,
      int offset,
      int numSamples,
      int phaseIncrement,
      int phase,
      boolean applyAmplitude,
      int phaseToAdd,
      int amplitudeIncrement,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase,
      boolean pulseWave) {
    // C: bufferStartThisSync = applyAmplitude ? oscSyncRenderingBuffer : bufferStart
    int[] raw = rawScratch.get();
    if (raw.length < numSamples) {
      raw = new int[numSamples];
      rawScratch.set(raw);
    }
    int bufPos = 0; // index into raw[]
    boolean renderedASyncFromItsStartYet = false;
    int crossoverSampleBeforeSync = 0;
    int fadeBetweenSyncs = 0;
    long numSamplesThisOscSyncSession = numSamples;
    long samplesIncludingNextCrossoverSample = 1; // includes the crossover sample at the end

    while (true) {
      // C render_wave.h:41-46 (all uint32 arithmetic)
      long distanceTilNextCrossoverSample =
          (-(resetterPhase & 0xFFFFFFFFL) - ((resetterPhaseIncrement & 0xFFFFFFFFL) >>> 1))
              & 0xFFFFFFFFL;
      samplesIncludingNextCrossoverSample +=
          ((distanceTilNextCrossoverSample - 1) & 0xFFFFFFFFL)
              / (resetterPhaseIncrement & 0xFFFFFFFFL);
      boolean shouldBeginNextSyncAfter =
          numSamplesThisOscSyncSession >= samplesIncludingNextCrossoverSample;
      int numSamplesThisSyncRender =
          (int)
              (shouldBeginNextSyncAfter
                  ? samplesIncludingNextCrossoverSample
                  : numSamplesThisOscSyncSession);

      if (pulseWave) {
        renderPulseRawSegment(
            (short[]) table,
            tableSizeMagnitude,
            raw,
            bufPos,
            bufPos + numSamplesThisSyncRender,
            phaseIncrement,
            phase,
            phaseToAdd);
      } else if (table instanceof short[] st) {
        renderWaveRawSegment(
            st,
            tableSizeMagnitude,
            raw,
            bufPos,
            bufPos + numSamplesThisSyncRender,
            phaseIncrement,
            phase,
            phaseToAdd);
      } else {
        renderWaveRawSegment(
            (int[]) table,
            tableSizeMagnitude,
            raw,
            bufPos,
            bufPos + numSamplesThisSyncRender,
            phaseIncrement,
            phase,
            phaseToAdd);
      }

      // C render_wave.h:55-61 — half-sine crossfade of the crossover sample at this window's start
      if (renderedASyncFromItsStartYet) {
        int average = (raw[bufPos] >> 1) + (crossoverSampleBeforeSync >> 1);
        int halfDifference = (raw[bufPos] >> 1) - (crossoverSampleBeforeSync >> 1);
        int sineValue = Functions.getSine(fadeBetweenSyncs >> 1, 32);
        raw[bufPos] = average + (Functions.multiply_32x32_rshift32(halfDifference, sineValue) << 1);
      }

      if (shouldBeginNextSyncAfter) {
        // C render_wave.h:63-84
        bufPos += (int) samplesIncludingNextCrossoverSample - 1;
        crossoverSampleBeforeSync = raw[bufPos];
        numSamplesThisOscSyncSession -= samplesIncludingNextCrossoverSample - 1;
        resetterPhase +=
            resetterPhaseIncrement
                * (int)
                    (samplesIncludingNextCrossoverSample - (renderedASyncFromItsStartYet ? 1 : 0));
        fadeBetweenSyncs =
            Functions.multiply_32x32_rshift32(resetterPhase, resetterDivideByPhaseIncrement) << 17;
        phase =
            Functions.multiply_32x32_rshift32(fadeBetweenSyncs, phaseIncrement) + retriggerPhase;
        phase -= phaseIncrement; // we're going back and redoing the last sample
        renderedASyncFromItsStartYet = true;
        samplesIncludingNextCrossoverSample = 2;
        continue;
      }

      phase += phaseIncrement * numSamplesThisSyncRender;
      break;
    }

    if (applyAmplitude) {
      applyAmplitudeVectorToBuffer(
          amplitude, numSamples, amplitudeIncrement, outputBuffer, offset, raw);
    } else {
      System.arraycopy(raw, 0, outputBuffer, offset, numSamples);
    }
    return phase;
  }

  /**
   * Port of basic_waves.cpp renderWave + processing/vector_rendering_function.h
   * waveRenderingFunctionGeneral (scalar). Band-limited wavetable oscillator with linear
   * interpolation. Returns the updated 32-bit phase.
   */
  static int renderWave(
      short[] table,
      int tableSizeMagnitude,
      int amplitude,
      int[] outputBuffer,
      int offset,
      int numSamples,
      int phaseIncrement,
      int phase,
      boolean applyAmplitude,
      int phaseToAdd,
      int amplitudeIncrement) {
    int currentPhase = phase;
    int currentAmplitude = amplitude;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      int p = currentPhase + phaseToAdd;

      int whichValue = p >>> (32 - tableSizeMagnitude);
      long v1 = table[whichValue]; // signed int16, sign-extended
      long v2 = table[whichValue + 1];

      // 16-bit interpolation fraction (waveRenderingFunctionGeneral: strength2)
      long frac = (p >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      long v1_32 = v1 << 16;
      long interpolatedDiff = (((v2 << 16) - v1_32) * frac) >> 16;
      int val = (int) (v1_32 + interpolatedDiff);

      int wet = val;
      if (applyAmplitude) {
        currentAmplitude += amplitudeIncrement;
        // C basic_waves.cpp:34+43: createAmplitudeVector halves the amplitude, then
        // vqdmulhq_s32(a,b) = sat((2ab)>>32) — net sat(((amp>>1)*val)>>31) = (amp*val)>>32.
        // (The old ">>30" here mis-derived vqdmulh and dropped the halving: 4x the C level,
        // which the master stage then compensated linearly — wrong into every nonlinear stage.)
        wet = sat32(((long) (currentAmplitude >> 1) * val) >> 31);
      }
      // C accumulates with plain (wrapping) vaddq_s32.
      outputBuffer[offset + i] = outputBuffer[offset + i] + wet;
    }
    return currentPhase;
  }

  /** Saturate a long to the signed 32-bit range. */
  private static int sat32(long v) {
    if (v > Integer.MAX_VALUE) return Integer.MAX_VALUE;
    if (v < Integer.MIN_VALUE) return Integer.MIN_VALUE;
    return (int) v;
  }

  /**
   * Faithful scalar port of {@code dsp::renderPulseWave} + {@code waveRenderingFunctionPulse}
   * (basic_waves.cpp:58, processing/vector_rendering_function.h:51). Renders a variable-width pulse
   * as the polarity-flipped product of two square-table reads, one offset by {@code phaseToAdd}.
   * Argon→scalar mapping verified against the non-pulse path (renderWaveRawSegment).
   */
  static int renderPulseWave(
      short[] table,
      int tableSizeMagnitude,
      int amplitude,
      int[] outputBuffer,
      int offset,
      int numSamples,
      int phaseIncrement,
      int phase,
      boolean applyAmplitude,
      int phaseToAdd,
      int amplitudeIncrement) {
    int currentPhase = phase;
    int currentAmplitude = amplitude;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      int pa = currentPhase;
      int pb = currentPhase + phaseToAdd;

      // Read A (gather + "sneaky backwards" strengths → flips polarity). indicesX =
      // phase>>(32-mag);
      // rshiftedX = (indicesX>>16) & int16_max (0 for mag<=16). C
      // vector_rendering_function.h:60-76.
      int idxA = pa >>> (32 - tableSizeMagnitude);
      int rshiftedA = (idxA >>> 16) & 0x7FFF;
      int valueA1 = table[idxA];
      int valueA2 = table[idxA + 1];
      int strengthA1 = (rshiftedA | 0x8000) - 0x10000; // rshiftedA | int16_min, as int16 (negative)
      int strengthA2 = -32768 - strengthA1; // int16_min - strengthA1
      long outA = sat32(2L * strengthA2 * valueA2);
      outA = sat32(outA + sat32(2L * strengthA1 * valueA1));

      // Read B (forward strengths). C:65-83.
      int idxB = pb >>> (32 - tableSizeMagnitude);
      int rshiftedB = (idxB >>> 16) & 0x7FFF;
      int valueB1 = table[idxB];
      int valueB2 = table[idxB + 1];
      int strengthB2 = rshiftedB; // already & int16_max
      int strengthB1 = 0x7FFF - strengthB2; // int16_max - strengthB2
      long outB = sat32(2L * strengthB2 * valueB2);
      outB = sat32(outB + sat32(2L * strengthB1 * valueB1));

      // output = MultiplyRoundFixedPoint(outputA, outputB) << 1
      // C++ MultiplyRoundFixedPoint is VQRDMULH (saturating rounding doubling multiply high).
      // vqrdmulh(a, b) = sat32((2 * a * b + 2^30) >> 31)
      long prod = outA * outB;
      long rounded = prod + (1L << 30);
      int mrf = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, rounded >> 31));
      int val = Functions.lshiftAndSaturate(mrf, 1);

      int wet = val;
      if (applyAmplitude) {
        currentAmplitude += amplitudeIncrement;
        // C-exact vqdmulh chain: sat(((amp>>1)*val)>>31) — see renderWave.
        wet = sat32(((long) (currentAmplitude >> 1) * val) >> 31);
      }
      outputBuffer[offset + i] = outputBuffer[offset + i] + wet;
    }
    return currentPhase;
  }

  /**
   * {@code int[]} overload of {@link #renderWave} for tables stored as {@code int[]} holding int16
   * values (e.g. {@code sineWaveSmall}). Body identical to the {@code short[]} version.
   */
  static int renderWave(
      int[] table,
      int tableSizeMagnitude,
      int amplitude,
      int[] outputBuffer,
      int offset,
      int numSamples,
      int phaseIncrement,
      int phase,
      boolean applyAmplitude,
      int phaseToAdd,
      int amplitudeIncrement) {
    int currentPhase = phase;
    int currentAmplitude = amplitude;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      int p = currentPhase + phaseToAdd;

      int whichValue = p >>> (32 - tableSizeMagnitude);
      long v1 = table[whichValue];
      long v2 = table[whichValue + 1];

      long frac = (p >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      long v1_32 = v1 << 16;
      long interpolatedDiff = (((v2 << 16) - v1_32) * frac) >> 16;
      int val = (int) (v1_32 + interpolatedDiff);

      int wet = val;
      if (applyAmplitude) {
        currentAmplitude += amplitudeIncrement;
        // C-exact vqdmulh chain: sat(((amp>>1)*val)>>31) — see renderWave.
        wet = sat32(((long) (currentAmplitude >> 1) * val) >> 31);
      }
      outputBuffer[offset + i] = outputBuffer[offset + i] + wet;
    }
    return currentPhase;
  }

  // ── renderOsc main entry point (oscillator.cpp:28-509) ──

  /**
   * Port of Oscillator::renderOsc. Renders one waveform into a buffer.
   *
   * @param type waveform type
   * @param amplitude Q31 amplitude (applied per sample when applyAmplitude is true)
   * @param buffer output buffer (accumulated or overwritten)
   * @param off offset into buffer
   * @param numSamples number of samples to render
   * @param phaseIncrement per-sample phase advance (32-bit)
   * @param pulseWidth pulse width for square waves (0 = 50%)
   * @param startPhase [in/out] initial phase, updated in-place
   * @param applyAmplitude if true, multiply by amplitude and accumulate
   * @param amplitudeIncrement amplitude ramp per sample
   * @param doOscSync oscillator hard sync enabled
   * @param resetterPhase sync resetter phase
   * @param resetterPhaseInc sync resetter phase increment
   * @param retriggerPhase retrigger offset
   */
  public static void renderOsc(
      OscType type,
      int amplitude,
      int[] buffer,
      int off,
      int numSamples,
      int phaseIncrement,
      int pulseWidth,
      int[] startPhase,
      boolean applyAmplitude,
      int amplitudeIncrement,
      boolean doOscSync,
      int resetterPhase,
      int resetterPhaseInc,
      int retriggerPhase) {

    // uint32_t phase = *startPhase;  // line 36
    int phase = startPhase[0];
    // *startPhase += phaseIncrement * numSamples;  // line 37
    startPhase[0] += phaseIncrement * numSamples;

    boolean doPulseWave = false;
    int resetterDivideByPhaseIncrement = 0;
    int tableNumber = 0;
    int tableSizeMagnitude = 0;
    int phaseToAdd = 0;

    // SINE: retrigger phase adjustment (line 49-51)
    if (type == OscType.SINE) {
      retriggerPhase += -1073741824; // 3221225472u in unsigned
    }

    // Not SINE and not TRIANGLE (line 53-82)
    else if (type != OscType.TRIANGLE) {
      int phaseIncForCalc = phaseIncrement;
      if (type == OscType.SQUARE) {
        // doPulseWave = (pulseWidth != 0); pulseWidth += 2147483648u; (lines 58-64)
        doPulseWave = (pulseWidth != 0);
        pulseWidth += -2147483648; // 2147483648u as signed
        if (doPulseWave) {
          // phaseIncrementForCalculations = phaseIncrement * 0.6
          phaseIncForCalc = (int) (phaseIncrement * 0.6);
        }
      }
      int[] tn = getTableNumber(phaseIncForCalc);
      tableNumber = tn[0];
      tableSizeMagnitude = tn[1];

      if (type == OscType.ANALOG_SAW_2) {
        // C oscillator.cpp:70-77 — at high CPU direness, saw-shaped analog bands (>=8) fall back
        // to the crude digital saw. cpuDireness is 0 on desktop: tableNumber < 0 + 6 never holds
        // for tableNumber >= 8, so no fallback here (kept for fidelity of the condition).
        if (tableNumber >= 8 && tableNumber < 6) {
          type = OscType.SAW;
        }
      } else if (type == OscType.SAW) {
        retriggerPhase += -2147483648; // 2147483648u
      }
    }

    // Non-SQUARE pulse width handling (lines 84-133)
    if (type != OscType.SQUARE) {
      doPulseWave = (pulseWidth != 0 && !doOscSync);
      if (doPulseWave) {
        doOscSync = true;
        int pwAbs = (pulseWidth >= 0) ? pulseWidth : -pulseWidth;
        resetterPhase = phase;
        resetterPhaseInc = phaseIncrement;

        if (type == OscType.ANALOG_SQUARE) {
          long rtpDiv = ((long) resetterPhase & 0xFFFFFFFFL) << 30;
          if (Integer.compareUnsigned(resetterPhase, -(resetterPhaseInc >>> 1)) >= 0) {
            rtpDiv -= 1L << 62;
          }
          phase = (int) (rtpDiv / (((pwAbs & 0xFFFFFFFFL) + 0x80000000L) >>> 1));
          // C oscillator.cpp:105 — the divisor here is NOT halved (only the phase division
          // above divides by ">> 1").
          phaseIncrement =
              (int)
                  ((((long) phaseIncrement & 0xFFFFFFFFL) << 31)
                      / ((pwAbs & 0xFFFFFFFFL) + 0x80000000L));
        } else {
          if (type == OscType.SAW) {
            resetterPhase += -2147483648;
          } else if (type == OscType.SINE) {
            resetterPhase -= -1073741824;
          }
          // C oscillator.cpp:116 — resetterPhase is uint32_t, so this is a LOGICAL shift.
          int rtpMul = resetterPhase >>> 1;
          if (Integer.compareUnsigned(resetterPhase, -(resetterPhaseInc >>> 1)) >= 0) {
            rtpMul -= 1 << 31;
          }
          phase = Functions.multiply_32x32_rshift32_rounded((pwAbs >> 1) + 1073741824, rtpMul) << 3;
          phaseIncrement =
              Functions.multiply_32x32_rshift32_rounded(
                      (pwAbs >> 1) + 1073741824, phaseIncrement >>> 1)
                  << 3;
        }
        phase += retriggerPhase;
        doOscSync = true; // proceed to osc sync setup below
      }
    }

    // Osc sync setup (lines 136-144)
    if (doOscSync) {
      long divisor = ((resetterPhaseInc & 0xFFFFFFFFL) + 65535) >>> 16;
      if (divisor > 0) {
        resetterDivideByPhaseIncrement = (int) (2147483648L / divisor);
      }
    }

    // ── SINE (line 147-151) ──
    if (type == OscType.SINE) {
      // C: table = sineWaveSmall; tableSizeMagnitude = 8; goto callRenderWave
      // (oscillator.cpp:147-151). The callRenderWave label sits AFTER the "amplitude <<= 1;
      // amplitudeIncrement <<= 1;" at oscillator.cpp:471-472, so sine is rendered with the
      // UNDOUBLED amplitude (unlike saw/square, which fall through the doubling).
      if (doOscSync) { // C callRenderWave sync branch (oscillator.cpp:476-498)
        phase =
            renderWaveSync(
                LookupTables.sineWaveSmall,
                8,
                amplitude,
                buffer,
                off,
                numSamples,
                phaseIncrement,
                phase,
                applyAmplitude,
                0,
                amplitudeIncrement,
                resetterPhase,
                resetterPhaseInc,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      phase =
          renderWave(
              LookupTables.sineWaveSmall,
              8,
              amplitude,
              buffer,
              off,
              numSamples,
              phaseIncrement,
              phase,
              applyAmplitude,
              0,
              amplitudeIncrement);
      maybeStorePhase(type, startPhase, phase, doPulseWave);
      return;
    }

    // ── TRIANGLE (lines 174-264) ──
    if (type == OscType.TRIANGLE) {
      if (Integer.compareUnsigned(phaseIncrement, 69273666) < 0) {
        // Low freq: use getTriangleSmall (crude but adequate)
        int ampNow = amplitude << 1;
        int ampInc2 = amplitudeIncrement << 1;
        int rstPhase = resetterPhase;
        for (int i = 0; i < numSamples; i++) {
          phase += phaseIncrement;
          // C oscillator.cpp:177-196 — per-sample hard-sync reset for the crude triangle.
          if (doOscSync) {
            rstPhase += resetterPhaseInc;
            if (Integer.compareUnsigned(rstPhase, resetterPhaseInc) < 0) {
              phase =
                  (Functions.multiply_32x32_rshift32(
                              Functions.multiply_32x32_rshift32(rstPhase, phaseIncrement),
                              resetterDivideByPhaseIncrement)
                          << 17)
                      + 1
                      + retriggerPhase;
            }
          }
          ampNow += ampInc2;
          int val = getTriangleSmall(phase);
          if (applyAmplitude) {
            buffer[off + i] =
                Functions.multiply_accumulate_32x32_rshift32_rounded(buffer[off + i], val, ampNow);
          } else {
            buffer[off + i] = val << 1;
          }
        }
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      // High freq: anti-aliasing tables lookup (lines 235-263)
      short[] table;
      if (Integer.compareUnsigned(phaseIncrement, 429496729) <= 0) {
        tableSizeMagnitude = 7;
        if (Integer.compareUnsigned(phaseIncrement, 102261126) <= 0) {
          table = TriangleLookupTables.triangleWaveAntiAliasing21;
        } else if (Integer.compareUnsigned(phaseIncrement, 143165576) <= 0) {
          table = TriangleLookupTables.triangleWaveAntiAliasing15;
        } else if (Integer.compareUnsigned(phaseIncrement, 238609294) <= 0) {
          table = TriangleLookupTables.triangleWaveAntiAliasing9;
        } else {
          table = TriangleLookupTables.triangleWaveAntiAliasing5;
        }
      } else {
        tableSizeMagnitude = 6;
        if (Integer.compareUnsigned(phaseIncrement, 715827882) <= 0) {
          table = TriangleLookupTables.triangleWaveAntiAliasing3;
        } else {
          table = TriangleLookupTables.triangleWaveAntiAliasing1;
        }
      }
      if (doOscSync) { // C callRenderWave sync branch (oscillator.cpp:476-498)
        phase =
            renderWaveSync(
                table,
                tableSizeMagnitude,
                amplitude,
                buffer,
                off,
                numSamples,
                phaseIncrement,
                phase,
                applyAmplitude,
                0,
                amplitudeIncrement,
                resetterPhase,
                resetterPhaseInc,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      phase =
          renderWave(
              table,
              tableSizeMagnitude,
              amplitude,
              buffer,
              off,
              numSamples,
              phaseIncrement,
              phase,
              applyAmplitude,
              0,
              amplitudeIncrement);
      maybeStorePhase(type, startPhase, phase, doPulseWave);
      return;
    }

    // ── SAW (lines 270-326) ──
    if (type == OscType.SAW) {
      if (tableNumber < 6) { // cpuDireness + 6 where cpuDireness=0
        if (!doOscSync) {
          if (applyAmplitude) {
            renderCrudeSawWithAmp(
                buffer, off, numSamples, startPhase, phaseIncrement, amplitude, amplitudeIncrement);
          } else {
            renderCrudeSawNoAmp(buffer, off, numSamples, startPhase, phaseIncrement);
          }
          return;
        }
        // Osc sync crude saw: per-sample loop
        int ampNow = amplitude;
        int rstPhase = resetterPhase;
        for (int i = 0; i < numSamples; i++) {
          phase += phaseIncrement;
          rstPhase += resetterPhaseInc;
          if (Integer.compareUnsigned(rstPhase, resetterPhaseInc) < 0) {
            phase =
                (Functions.multiply_32x32_rshift32(
                            Functions.multiply_32x32_rshift32(rstPhase, phaseIncrement),
                            resetterDivideByPhaseIncrement)
                        << 17)
                    + 1
                    + retriggerPhase;
          }
          ampNow += amplitudeIncrement;
          if (applyAmplitude) {
            buffer[off + i] =
                Functions.multiply_accumulate_32x32_rshift32_rounded(
                    buffer[off + i], phase, ampNow);
          } else {
            buffer[off + i] = phase >> 1;
          }
        }
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      // Band-limited saw: firmware renderWave (waveRenderingFunctionGeneral) over the per-band saw
      // wavetable. Self-contained firmware2 data + interpolation.
      short[] sawTable =
          SawLookupTables.sawWaveTables[
              Math.min(tableNumber, SawLookupTables.sawWaveTables.length - 1)];
      // C: amplitude <<= 1; amplitudeIncrement <<= 1; before callRenderWave
      // (oscillator.cpp:470-471)
      if (doOscSync) { // C callRenderWave sync branch (oscillator.cpp:476-498)
        phase =
            renderWaveSync(
                sawTable,
                tableSizeMagnitude,
                amplitude << 1,
                buffer,
                off,
                numSamples,
                phaseIncrement,
                phase,
                applyAmplitude,
                0,
                amplitudeIncrement << 1,
                resetterPhase,
                resetterPhaseInc,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      phase =
          renderWave(
              sawTable,
              tableSizeMagnitude,
              amplitude << 1,
              buffer,
              off,
              numSamples,
              phaseIncrement,
              phase,
              applyAmplitude,
              0,
              amplitudeIncrement << 1);
      maybeStorePhase(type, startPhase, phase, doPulseWave);
      return;
    }

    // ── SQUARE (lines 328-457) ──
    if (type == OscType.SQUARE) {
      if (tableNumber < 6) {
        int a = amplitude;
        // C oscillator.cpp:332-410 — the amplitude path uses the FULL-SCALE getSquare;
        // getSquareSmall is only for the raw (!applyAmplitude) path. The doOscSync branch
        // (oscillator.cpp:381-410) resets phase per-sample like the crude triangle.
        int rstPhase = resetterPhase;
        for (int i = 0; i < numSamples; i++) {
          phase += phaseIncrement;
          if (doOscSync) {
            rstPhase += resetterPhaseInc;
            if (Integer.compareUnsigned(rstPhase, resetterPhaseInc) < 0) {
              phase =
                  (Functions.multiply_32x32_rshift32(
                              Functions.multiply_32x32_rshift32(rstPhase, phaseIncrement),
                              resetterDivideByPhaseIncrement)
                          << 17)
                      + 1
                      + retriggerPhase;
            }
          }
          if (applyAmplitude) {
            a += amplitudeIncrement;
            buffer[off + i] =
                Functions.multiply_accumulate_32x32_rshift32_rounded(
                    buffer[off + i], getSquare(phase, pulseWidth), a);
          } else {
            buffer[off + i] = getSquareSmall(phase, pulseWidth);
          }
        }
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      // Band-limited square: firmware renderWave over the per-band square wavetable.
      short[] squareTable =
          SquareLookupTables.squareWaveTables[
              Math.min(tableNumber, SquareLookupTables.squareWaveTables.length - 1)];

      // Band-limited PULSE width (C oscillator.cpp:417-453). pulseWidth is already offset by
      // -2^31. The pulse is the product of two square reads offset by phaseToAdd; the C
      // pre-halves phase/phaseIncrement (the product doubles the effective freq) and doubles
      // amplitude. With osc sync, the same halved-phase pulse renders through renderOscSync with
      // waveRenderingFunctionPulse and the phase is re-doubled after (oscillator.cpp:426-447).
      if (doPulseWave) {
        int pAmp = amplitude << 1;
        int pAmpInc = amplitudeIncrement << 1;
        // C oscillator.cpp:422 — pulseWidth is uint32_t: LOGICAL shift.
        int pToAdd = -(pulseWidth >>> 1);
        int pPhase = phase >>> 1;
        int pInc = phaseIncrement >>> 1;
        if (doOscSync) {
          int newPhase =
              renderWaveSync(
                  squareTable,
                  tableSizeMagnitude,
                  pAmp,
                  buffer,
                  off,
                  numSamples,
                  pInc,
                  pPhase,
                  applyAmplitude,
                  pToAdd,
                  pAmpInc,
                  resetterPhase,
                  resetterPhaseInc,
                  resetterDivideByPhaseIncrement,
                  retriggerPhase,
                  true);
          phase = newPhase << 1; // C:445 — phase <<= 1
          maybeStorePhase(type, startPhase, phase, doPulseWave);
          return;
        }
        // Unsynced: startPhase was already advanced with the ORIGINAL phaseIncrement at entry,
        // so we return without maybeStorePhase (matching the C's plain return).
        renderPulseWave(
            squareTable,
            tableSizeMagnitude,
            pAmp,
            buffer,
            off,
            numSamples,
            pInc,
            pPhase,
            applyAmplitude,
            pToAdd,
            pAmpInc);
        return;
      }
      // C: amplitude <<= 1; amplitudeIncrement <<= 1; before callRenderWave
      // (oscillator.cpp:470-471)
      if (doOscSync) { // C callRenderWave sync branch (oscillator.cpp:476-498)
        phase =
            renderWaveSync(
                squareTable,
                tableSizeMagnitude,
                amplitude << 1,
                buffer,
                off,
                numSamples,
                phaseIncrement,
                phase,
                applyAmplitude,
                0,
                amplitudeIncrement << 1,
                resetterPhase,
                resetterPhaseInc,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      phase =
          renderWave(
              squareTable,
              tableSizeMagnitude,
              amplitude << 1,
              buffer,
              off,
              numSamples,
              phaseIncrement,
              phase,
              applyAmplitude,
              0,
              amplitudeIncrement << 1);
      maybeStorePhase(type, startPhase, phase, doPulseWave);
      return;
    }

    // ── ANALOG_SAW_2 (oscillator.cpp:459-461) ──
    // analogSawTables are non-null for all 20 bands (no crude fallback at cpuDireness 0).
    if (type == OscType.ANALOG_SAW_2) {
      short[] table =
          AnalogSawLookupTables.analogSawTables[
              Math.min(tableNumber, AnalogSawLookupTables.analogSawTables.length - 1)];
      // C: amplitude <<= 1; amplitudeIncrement <<= 1; before callRenderWave
      // (oscillator.cpp:470-471)
      if (doOscSync) { // C callRenderWave sync branch (oscillator.cpp:476-498)
        phase =
            renderWaveSync(
                table,
                tableSizeMagnitude,
                amplitude << 1,
                buffer,
                off,
                numSamples,
                phaseIncrement,
                phase,
                applyAmplitude,
                phaseToAdd,
                amplitudeIncrement << 1,
                resetterPhase,
                resetterPhaseInc,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      phase =
          renderWave(
              table,
              tableSizeMagnitude,
              amplitude << 1,
              buffer,
              off,
              numSamples,
              phaseIncrement,
              phase,
              applyAmplitude,
              phaseToAdd,
              amplitudeIncrement << 1);
      maybeStorePhase(type, startPhase, phase, doPulseWave);
      return;
    }

    // ── ANALOG_SQUARE (oscillator.cpp:463-466) ──
    if (type == OscType.ANALOG_SQUARE) {
      short[] table =
          AnalogSquareLookupTables.analogSquareTables[
              Math.min(tableNumber, AnalogSquareLookupTables.analogSquareTables.length - 1)];
      if (doOscSync) { // C callRenderWave sync branch (oscillator.cpp:476-498)
        phase =
            renderWaveSync(
                table,
                tableSizeMagnitude,
                amplitude << 1,
                buffer,
                off,
                numSamples,
                phaseIncrement,
                phase,
                applyAmplitude,
                phaseToAdd,
                amplitudeIncrement << 1,
                resetterPhase,
                resetterPhaseInc,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      phase =
          renderWave(
              table,
              tableSizeMagnitude,
              amplitude << 1,
              buffer,
              off,
              numSamples,
              phaseIncrement,
              phase,
              applyAmplitude,
              phaseToAdd,
              amplitudeIncrement << 1);
      maybeStorePhase(type, startPhase, phase, doPulseWave);
      return;
    }

    maybeStorePhase(type, startPhase, phase, doPulseWave);
  }
}
