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
    SAMPLE
  }

  // ── Helper: getTableNumber (port of dsp::getTableNumber) ──
  // Maps a phase increment to a band-limited table index + size magnitude.

  /** Port of dsp::getTableNumber. Returns [tableNumber, tableSizeMagnitude]. */
  public static int[] getTableNumber(int phaseIncrement) {
    int magnitude = 31 - Integer.numberOfLeadingZeros(phaseIncrement);
    // Table number 0-31; size 6-8 depending on increment
    int tableNumber = Math.max(0, magnitude - 18);
    int tableSizeMagnitude;
    if (tableNumber < 6) {
      tableSizeMagnitude = 8;
    } else if (tableNumber < 8) {
      tableSizeMagnitude = 7;
    } else {
      tableSizeMagnitude = 8; // large tables
    }
    return new int[] {tableNumber, tableSizeMagnitude};
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

    // Fallback analog modeling types to standard band-limited digital types
    // due to lack of analogSawTables/analogSquareTables binary data in resources.
    if (type == OscType.ANALOG_SAW_2) {
      type = OscType.SAW;
    } else if (type == OscType.ANALOG_SQUARE) {
      type = OscType.SQUARE;
    }

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

      if (type == OscType.SAW) {
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
      int ampNow = amplitude << 1;
      int ampInc2 = amplitudeIncrement << 1;
      int a = ampNow;
      for (int i = 0; i < numSamples; i++) {
        phase += phaseIncrement;
        a += ampInc2;
        int sample = SineOsc.doFMNew(phase, 0);
        if (applyAmplitude) {
          buffer[off + i] =
              Functions.multiply_accumulate_32x32_rshift32_rounded(buffer[off + i], sample, a);
        } else {
          buffer[off + i] = sample << 1;
        }
      }
      maybeStorePhase(type, startPhase, phase, doPulseWave);
      return;
    }

    // ── TRIANGLE (lines 174-264) ──
    if (type == OscType.TRIANGLE) {
      if (Integer.compareUnsigned(phaseIncrement, 69273666) < 0) {
        // Low freq: use getTriangleSmall (crude but adequate)
        int ampNow = amplitude << 1;
        int ampInc2 = amplitudeIncrement << 1;
        for (int i = 0; i < numSamples; i++) {
          phase += phaseIncrement;
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
                buffer,
                off,
                numSamples,
                new int[] {phase},
                phaseIncrement,
                amplitude,
                amplitudeIncrement);
          } else {
            renderCrudeSawNoAmp(buffer, off, numSamples, new int[] {phase}, phaseIncrement);
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
      phase =
          renderWave(
              sawTable,
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
      phase =
          renderWave(
              squareTable,
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

    maybeStorePhase(type, startPhase, phase, doPulseWave);
  }
}
