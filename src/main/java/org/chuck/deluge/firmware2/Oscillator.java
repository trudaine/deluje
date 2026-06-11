package org.chuck.deluge.firmware2;

/**
 * Faithful line-by-line port of the Deluge {@code oscillator.cpp} Oscillator::renderOsc. Covers all
 * waveform types: SAW, SQUARE, SINE, TRIANGLE, ANALOG_SAW_2, ANALOG_SQUARE, WAVETABLE, and SAMPLE.
 * SIMD operations (vld1q_s32 etc.) are replaced with equivalent scalar loops.
 *
 * <p>Firmware reference: {@code src/deluge/dsp/oscillators/oscillator.cpp} lines 28-509.
 */
public final class Oscillator {

  private Oscillator() {}

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
      int withAmp = (int) (((long) ampNow * raw[i]) >> 31);
      out[outOff + i] = Functions.add_saturate(out[outOff + i], withAmp);
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
    // C: bufferStartThisSync = applyAmplitude ? oscSyncRenderingBuffer : bufferStart
    int[] raw = new int[numSamples];
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

      if (table instanceof short[] st) {
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
        // vqdmulhq_s32(amplitude, val) == saturating (amplitude*val) >> 31
        wet = (int) (((long) currentAmplitude * val) >> 31);
      }
      outputBuffer[offset + i] = Functions.add_saturate(outputBuffer[offset + i], wet);
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
        wet = (int) (((long) currentAmplitude * val) >> 31);
      }
      outputBuffer[offset + i] = Functions.add_saturate(outputBuffer[offset + i], wet);
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
          phaseIncrement =
              (int)
                  ((((long) phaseIncrement & 0xFFFFFFFFL) << 31)
                      / (((pwAbs & 0xFFFFFFFFL) + 0x80000000L) >>> 1));
        } else {
          if (type == OscType.SAW) {
            resetterPhase += -2147483648;
          } else if (type == OscType.SINE) {
            resetterPhase -= -1073741824;
          }
          int rtpMul = resetterPhase >> 1;
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
      resetterDivideByPhaseIncrement =
          (int) (0x80000000L / (((resetterPhaseInc & 0xFFFF0000L) + 65536) >>> 16));
    }

    // ── SINE (line 147-151) ──
    if (type == OscType.SINE) {
      // C: table = sineWaveSmall; tableSizeMagnitude = 8; goto callRenderWave (which does
      // amplitude<<=1; amplitudeIncrement<<=1). (oscillator.cpp:147-151). Same renderWave path as
      // saw/square — replaces the doFMNew reconstruction (doFMNew is for FM feedback, not the osc).
      if (doOscSync) { // C callRenderWave sync branch (oscillator.cpp:476-498)
        phase =
            renderWaveSync(
                LookupTables.sineWaveSmall,
                8,
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
              LookupTables.sineWaveSmall,
              8,
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
        int ampNow = amplitude;
        int a = ampNow;
        for (int i = 0; i < numSamples; i++) {
          phase += phaseIncrement;
          a += amplitudeIncrement;
          int val = getSquareSmall(phase, pulseWidth);
          if (applyAmplitude) {
            buffer[off + i] =
                Functions.multiply_accumulate_32x32_rshift32_rounded(buffer[off + i], val, a);
          } else {
            buffer[off + i] = val;
          }
        }
        maybeStorePhase(type, startPhase, phase, doPulseWave);
        return;
      }
      // Band-limited square: firmware renderWave over the per-band square wavetable.
      short[] squareTable =
          SquareLookupTables.squareWaveTables[
              Math.min(tableNumber, SquareLookupTables.squareWaveTables.length - 1)];
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
