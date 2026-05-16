package org.chuck.deluge.firmware.dsp.oscillators;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.util.LookupTables;
import org.chuck.deluge.firmware.util.Q31;

public class Oscillator {

  public static void renderOsc(
      OscType type,
      int amplitude,
      int[] buffer,
      int offset,
      int numSamples,
      int phaseIncrement,
      int pulseWidth,
      int[] startPhaseRef,
      boolean applyAmplitude,
      int amplitudeIncrement,
      boolean doOscSync,
      int resetterPhase,
      int resetterPhaseIncrement,
      int retriggerPhase) {

    int phase = startPhaseRef[0];
    startPhaseRef[0] += phaseIncrement * numSamples;

    boolean doPulseWave = false;
    int tableNumber = 0;
    int tableSizeMagnitude = 0;
    int phaseToAdd = 0;

    if (type == OscType.SINE) {
      retriggerPhase += 3221225472L;
    } else if (type != OscType.TRIANGLE) {
      int phaseIncrementForCalculations = phaseIncrement;

      if (type == OscType.SQUARE) {
        doPulseWave = (pulseWidth != 0);
        pulseWidth += 2147483648L;
        if (doPulseWave) {
          phaseIncrementForCalculations = (int) (phaseIncrement * 0.6);
        }
      }

      int[] tableInfo = BasicWaves.getTableInfo(phaseIncrementForCalculations);
      tableNumber = tableInfo[0];
      tableSizeMagnitude = tableInfo[1];

      if (type == OscType.SAW) {
        retriggerPhase += 2147483648L;
      }
    }

    // PW handling for non-square
    if (type != OscType.SQUARE && pulseWidth != 0 && !doOscSync) {
      doOscSync = true;
      int pulseWidthAbsolute = (pulseWidth >= 0) ? pulseWidth : -pulseWidth;
      resetterPhase = phase;
      resetterPhaseIncrement = phaseIncrement;

      int phaseIncrementMultiplier = 2147483647;
      if (pulseWidthAbsolute > 134217728) {
        // Approximate division from firmware:
        long num = 36000000000000000L;
        int denom = 201326592 - pulseWidthAbsolute;
        phaseIncrementMultiplier = (int) (num / denom);
      }

      phaseIncrement =
          Q31.multiply_32x32_rshift32_rounded(phaseIncrement, phaseIncrementMultiplier);

      if (pulseWidth < 0) {
        phaseIncrement = -phaseIncrement;
        phaseToAdd =
            Q31.multiply_32x32_rshift32_rounded(resetterPhase, phaseIncrementMultiplier) - phase;
      } else {
        phaseToAdd =
            -Q31.multiply_32x32_rshift32_rounded(resetterPhase, phaseIncrementMultiplier) + phase;
      }
    }

    int resetterDivideByPhaseIncrement = 0;
    if (doOscSync) {
      long temp = (resetterPhaseIncrement + 65535L) >> 16;
      if (temp != 0) {
        resetterDivideByPhaseIncrement = (int) (2147483648L / temp);
      }
    }

    // Selection of table and rendering
    if (type == OscType.SINE) {
      if (doOscSync) {
        renderSineSync(
            buffer,
            offset,
            numSamples,
            phase,
            phaseIncrement,
            amplitude,
            amplitudeIncrement,
            resetterPhase,
            resetterPhaseIncrement,
            resetterDivideByPhaseIncrement,
            retriggerPhase);
      } else {
        BasicWaves.renderWave(
            LookupTables.sineWaveSmall,
            8,
            amplitude,
            buffer,
            offset,
            numSamples,
            phaseIncrement,
            phase,
            applyAmplitude,
            phaseToAdd,
            amplitudeIncrement);
      }
    } else if (type == OscType.SAW) {
      if (tableNumber < 6 || doOscSync) { // Fallback to crude if sync
        if (applyAmplitude) {
          renderCrudeSawSyncWithAmplitude(
              buffer,
              offset,
              numSamples,
              phase,
              phaseIncrement,
              amplitude,
              amplitudeIncrement,
              doOscSync,
              resetterPhase,
              resetterPhaseIncrement,
              resetterDivideByPhaseIncrement,
              retriggerPhase);
        } else {
          renderCrudeSawSyncWithoutAmplitude(
              buffer,
              offset,
              numSamples,
              phase,
              phaseIncrement,
              doOscSync,
              resetterPhase,
              resetterPhaseIncrement,
              resetterDivideByPhaseIncrement,
              retriggerPhase);
        }
      } else {
        BasicWaves.renderCrudeSawWaveWithAmplitude(
            buffer, offset, numSamples, phase, phaseIncrement, amplitude, amplitudeIncrement);
      }
    } else if (type == OscType.SQUARE) {
      if (doPulseWave && !doOscSync) {
        BasicWaves.renderPulseWave(
            LookupTables.sineWaveSmall,
            8,
            amplitude,
            buffer,
            offset,
            numSamples,
            phaseIncrement,
            phase,
            applyAmplitude,
            pulseWidth,
            amplitudeIncrement);
      } else {
        renderCrudeSquare(
            buffer,
            offset,
            numSamples,
            phase,
            phaseIncrement,
            amplitude,
            amplitudeIncrement,
            pulseWidth,
            applyAmplitude,
            doOscSync,
            resetterPhase,
            resetterPhaseIncrement,
            resetterDivideByPhaseIncrement,
            retriggerPhase);
      }
    } else if (type == OscType.TRIANGLE) {
      renderCrudeTriangle(
          buffer,
          offset,
          numSamples,
          phase,
          phaseIncrement,
          amplitude,
          amplitudeIncrement,
          applyAmplitude,
          doOscSync,
          resetterPhase,
          resetterPhaseIncrement,
          resetterDivideByPhaseIncrement,
          retriggerPhase);
    }
  }

  private static int handleSync(
      int phase,
      int phaseIncrement,
      int resetterPhaseNow,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase) {
    if (Integer.compareUnsigned(resetterPhaseNow, resetterPhaseIncrement) < 0) {
      int shift = Q31.multiply_32x32_rshift32(resetterPhaseNow, phaseIncrement);
      return (Q31.multiply_32x32_rshift32(shift, resetterDivideByPhaseIncrement) << 17)
          + 1
          + retriggerPhase;
    }
    return phase;
  }

  private static void renderSineSync(
      int[] buffer,
      int offset,
      int numSamples,
      int phase,
      int phaseIncrement,
      int amplitude,
      int amplitudeIncrement,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase) {
    int currentPhase = phase;
    int currentAmplitude = amplitude;
    int resetterPhaseNow = resetterPhase;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      resetterPhaseNow += resetterPhaseIncrement;
      currentPhase =
          handleSync(
              currentPhase,
              phaseIncrement,
              resetterPhaseNow,
              resetterPhaseIncrement,
              resetterDivideByPhaseIncrement,
              retriggerPhase);
      currentAmplitude += amplitudeIncrement;

      int whichValue = currentPhase >>> (32 - 8);
      int v1 = LookupTables.sineWaveSmall[whichValue] & 0xFFFF;
      int v2 = LookupTables.sineWaveSmall[whichValue + 1] & 0xFFFF;
      int strength2 = (currentPhase >>> (32 - 16 - 8)) & 0xFFFF;
      strength2 >>>= 1;
      int diff = (short) v2 - (short) v1;
      int val = (v1 << 16) + (diff * strength2 * 2);

      buffer[offset + i] += Q31.mult(currentAmplitude << 1, val);
    }
  }

  private static void renderCrudeSawSyncWithAmplitude(
      int[] buffer,
      int offset,
      int numSamples,
      int phase,
      int phaseIncrement,
      int amplitude,
      int amplitudeIncrement,
      boolean doOscSync,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase) {
    int currentPhase = phase;
    int currentAmplitude = amplitude;
    int resetterPhaseNow = resetterPhase;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      resetterPhaseNow += resetterPhaseIncrement;
      if (doOscSync)
        currentPhase =
            handleSync(
                currentPhase,
                phaseIncrement,
                resetterPhaseNow,
                resetterPhaseIncrement,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
      currentAmplitude += amplitudeIncrement;
      buffer[offset + i] +=
          Q31.multiply_accumulate_32x32_rshift32_rounded(
              buffer[offset + i], currentPhase, currentAmplitude);
    }
  }

  private static void renderCrudeSawSyncWithoutAmplitude(
      int[] buffer,
      int offset,
      int numSamples,
      int phase,
      int phaseIncrement,
      boolean doOscSync,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase) {
    int currentPhase = phase;
    int resetterPhaseNow = resetterPhase;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      resetterPhaseNow += resetterPhaseIncrement;
      if (doOscSync)
        currentPhase =
            handleSync(
                currentPhase,
                phaseIncrement,
                resetterPhaseNow,
                resetterPhaseIncrement,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
      buffer[offset + i] = currentPhase >> 1;
    }
  }

  private static void renderCrudeSquare(
      int[] buffer,
      int offset,
      int numSamples,
      int phase,
      int phaseIncrement,
      int amplitude,
      int amplitudeIncrement,
      int pulseWidth,
      boolean applyAmplitude,
      boolean doOscSync,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase) {
    int currentPhase = phase;
    int currentAmplitude = amplitude;
    int resetterPhaseNow = resetterPhase;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      resetterPhaseNow += resetterPhaseIncrement;
      if (doOscSync)
        currentPhase =
            handleSync(
                currentPhase,
                phaseIncrement,
                resetterPhaseNow,
                resetterPhaseIncrement,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
      currentAmplitude += amplitudeIncrement;
      int val = (currentPhase < pulseWidth) ? Q31.ONE : Q31.NEGATIVE_ONE;
      if (applyAmplitude) {
        buffer[offset + i] += Q31.mult(currentAmplitude, val);
      } else {
        buffer[offset + i] = val;
      }
    }
  }

  private static void renderCrudeTriangle(
      int[] buffer,
      int offset,
      int numSamples,
      int phase,
      int phaseIncrement,
      int amplitude,
      int amplitudeIncrement,
      boolean applyAmplitude,
      boolean doOscSync,
      int resetterPhase,
      int resetterPhaseIncrement,
      int resetterDivideByPhaseIncrement,
      int retriggerPhase) {
    int currentPhase = phase;
    int currentAmplitude = amplitude;
    int resetterPhaseNow = resetterPhase;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      resetterPhaseNow += resetterPhaseIncrement;
      if (doOscSync)
        currentPhase =
            handleSync(
                currentPhase,
                phaseIncrement,
                resetterPhaseNow,
                resetterPhaseIncrement,
                resetterDivideByPhaseIncrement,
                retriggerPhase);
      currentAmplitude += amplitudeIncrement;

      int val = (currentPhase < 0) ? -currentPhase : currentPhase;
      val = (val - 1073741824) << 1;

      if (applyAmplitude) {
        buffer[offset + i] += Q31.mult(currentAmplitude, val);
      } else {
        buffer[offset + i] = val;
      }
    }
  }
}
