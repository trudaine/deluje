package org.chuck.deluge.firmware.dsp.oscillators;

import static org.chuck.deluge.firmware.util.Q31.*;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;
import org.chuck.deluge.firmware.util.Q31;

public class BasicWaves {
  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

  /** Renders a wave from a lookup table. Bit-accurate signed promotion and interpolation. */
  public static int renderWave(
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
      // SIGNED promotion (no masking)
      long v1 = table[whichValue];
      long v2 = table[whichValue + 1];

      // 16-bit fractional part for linear interpolation
      long frac = (p >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      
      // Interpolate in 32:32 domain
      long v1_32 = v1 << 16;
      long v2_32 = v2 << 16;
      int val = (int) (v1_32 + ((v2_32 - v1_32) * frac >> 16));

      int wet = val;
      if (applyAmplitude) {
        currentAmplitude += amplitudeIncrement;
        wet = Q31.mult(currentAmplitude, val);
      }
      
      // Saturating sum to maintain signal integrity
      outputBuffer[offset + i] = Q31.addSaturate(outputBuffer[offset + i], wet);
    }

    return currentPhase;
  }

  /** Renders a pulse wave by ring-modulating two wave lookups. */
  public static int renderPulseWave(
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
      int phaseA = currentPhase;
      int phaseB = currentPhase + phaseToAdd;

      // Sample A
      int whichValueA = phaseA >>> (32 - tableSizeMagnitude);
      long vA1 = (long)table[whichValueA] << 16;
      long vA2 = (long)table[whichValueA + 1] << 16;
      int fracA = (phaseA >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      int valA = (int) (vA1 + ((vA2 - vA1) * fracA >> 16));

      // Sample B
      int whichValueB = phaseB >>> (32 - tableSizeMagnitude);
      long vB1 = (long)table[whichValueB] << 16;
      long vB2 = (long)table[whichValueB + 1] << 16;
      int fracB = (phaseB >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      int valB = (int) (vB1 + ((vB2 - vB1) * fracB >> 16));

      // Ring mod
      int val = Q31.mult(valA, valB);

      int wet = val;
      if (applyAmplitude) {
        currentAmplitude += amplitudeIncrement;
        wet = Q31.mult(currentAmplitude, val);
      }
      outputBuffer[offset + i] = Q31.addSaturate(outputBuffer[offset + i], wet);
    }
    return currentPhase;
  }

  public static int renderCrudeSawWaveWithAmplitude(
      int[] buffer,
      int offset,
      int numSamples,
      int phase,
      int phaseIncrement,
      int amplitude,
      int amplitudeIncrement) {
    int currentPhase = phase;
    int currentAmplitude = amplitude;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      currentAmplitude += amplitudeIncrement;
      int val = currentPhase >> 1;
      int wet = Q31.mult(currentAmplitude, val);
      buffer[offset + i] = Q31.addSaturate(buffer[offset + i], wet);
    }
    return currentPhase;
  }

  public static int renderCrudeSawWaveWithoutAmplitude(
      int[] buffer, int offset, int numSamples, int phase, int phaseIncrement) {
    int currentPhase = phase;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      buffer[offset + i] = Q31.addSaturate(buffer[offset + i], currentPhase >> 1);
    }
    return currentPhase;
  }

  public static int[] getTableInfo(int phaseIncrement) {
    if (phaseIncrement <= 1247086) return new int[] {0, 13};
    if (phaseIncrement <= 1764571) return new int[] {1, 12};
    if (phaseIncrement <= 2494173) return new int[] {2, 12};
    if (phaseIncrement <= 3526245) return new int[] {3, 11};
    if (phaseIncrement <= 4982560) return new int[] {4, 11};
    if (phaseIncrement <= 7040929) return new int[] {5, 11};
    if (phaseIncrement <= 9988296) return new int[] {6, 11};
    if (phaseIncrement <= 14035840) return new int[] {7, 11};
    if (phaseIncrement <= 19701684) return new int[] {8, 10};
    if (phaseIncrement <= 28163718) return new int[] {9, 10};
    if (phaseIncrement <= 39953186) return new int[] {10, 10};
    if (phaseIncrement <= 56143360) return new int[] {11, 10};
    if (phaseIncrement <= 82137088) return new int[] {12, 10};
    if (phaseIncrement <= 112286720) return new int[] {13, 9};
    if (phaseIncrement <= 164274176) return new int[] {14, 9};
    if (phaseIncrement <= 224573440) return new int[] {15, 8};
    if (phaseIncrement <= 328548352) return new int[] {16, 8};
    if (phaseIncrement <= 449146880) return new int[] {17, 7};
    if (phaseIncrement <= 898293760) return new int[] {18, 6};
    return new int[] {19, 5};
  }
}
