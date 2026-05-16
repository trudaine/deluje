package org.chuck.deluge.firmware.dsp.oscillators;

import static org.chuck.deluge.firmware.util.Q31.*;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;
import org.chuck.deluge.firmware.util.Q31;

public class BasicWaves {
  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

  /** Renders a wave from a lookup table. Emulates SIMD rendering logic from basic_waves.cpp. */
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

    int i = 0;
    int upperBound = SPECIES.loopBound(numSamples);

    for (; i < upperBound; i += SPECIES.length()) {
      int[] tempValues = new int[SPECIES.length()];

      for (int j = 0; j < SPECIES.length(); j++) {
        currentPhase += phaseIncrement;
        int p = currentPhase + phaseToAdd;

        int whichValue = p >>> (32 - tableSizeMagnitude);
        int v1 = table[whichValue];
        int v2 = table[whichValue + 1];

        int strength2 = (p >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
        int strength1 = 65536 - strength2;

        tempValues[j] = v1 * strength1 + v2 * strength2;
      }

      IntVector valueVector = IntVector.fromArray(SPECIES, tempValues, 0);

      if (applyAmplitude) {
        int[] res = new int[SPECIES.length()];
        for (int j = 0; j < SPECIES.length(); j++) {
          currentAmplitude += amplitudeIncrement;
          res[j] = Q31.mult(currentAmplitude, tempValues[j]);
        }
        valueVector = IntVector.fromArray(SPECIES, res, 0);

        IntVector existing = IntVector.fromArray(SPECIES, outputBuffer, offset + i);
        valueVector = valueVector.add(existing);
      }

      valueVector.intoArray(outputBuffer, offset + i);
    }

    // Tail processing
    for (; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      int p = currentPhase + phaseToAdd;

      int whichValue = p >>> (32 - tableSizeMagnitude);
      int v1 = table[whichValue];
      int v2 = table[whichValue + 1];

      int strength2 = (p >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      int strength1 = 65536 - strength2;
      int val = v1 * strength1 + v2 * strength2;

      if (applyAmplitude) {
        currentAmplitude += amplitudeIncrement;
        int wet = Q31.mult(currentAmplitude, val);
        outputBuffer[offset + i] = Q31.addSaturate(outputBuffer[offset + i], wet);
      } else {
        outputBuffer[offset + i] = val;
      }
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
      int pulseWidth,
      int amplitudeIncrement) {

    int currentPhase = phase;
    int currentAmplitude = amplitude;

    int i = 0;
    int upperBound = SPECIES.loopBound(numSamples);

    for (; i < upperBound; i += SPECIES.length()) {
      int[] tempValues = new int[SPECIES.length()];

      for (int j = 0; j < SPECIES.length(); j++) {
        currentPhase += phaseIncrement;
        int phaseA = currentPhase;
        int phaseB = currentPhase + pulseWidth;

        // Sample A
        int whichValueA = phaseA >>> (32 - tableSizeMagnitude);
        int vA1 = table[whichValueA];
        int vA2 = table[whichValueA + 1];
        int strengthA2 = (phaseA >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
        int strengthA1 = 65536 - strengthA2;
        int valA = vA1 * strengthA1 + vA2 * strengthA2;

        // Sample B
        int whichValueB = phaseB >>> (32 - tableSizeMagnitude);
        int vB1 = table[whichValueB];
        int vB2 = table[whichValueB + 1];
        int strengthB2 = (phaseB >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
        int strengthB1 = 65536 - strengthB2;
        int valB = vB1 * strengthB1 + vB2 * strengthB2;

        // Ring mod (multiplication)
        // No redundant shift back - Q31 mult is already full scale
        tempValues[j] = Q31.mult(valA, valB);
      }

      IntVector valueVector = IntVector.fromArray(SPECIES, tempValues, 0);

      if (applyAmplitude) {
        int[] res = new int[SPECIES.length()];
        for (int j = 0; j < SPECIES.length(); j++) {
          currentAmplitude += amplitudeIncrement;
          res[j] = Q31.mult(currentAmplitude, tempValues[j]);
        }
        valueVector = IntVector.fromArray(SPECIES, res, 0);

        IntVector existing = IntVector.fromArray(SPECIES, outputBuffer, offset + i);
        valueVector = valueVector.add(existing);
      }

      valueVector.intoArray(outputBuffer, offset + i);
    }

    // Tail
    for (; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      int phaseA = currentPhase;
      int phaseB = currentPhase + pulseWidth;

      int whichValueA = phaseA >>> (32 - tableSizeMagnitude);
      int vA1 = table[whichValueA];
      int vA2 = table[whichValueA + 1];
      int strengthA2 = (phaseA >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      int strengthA1 = 65536 - strengthA2;
      int valA = vA1 * strengthA1 + vA2 * strengthA2;

      int whichValueB = phaseB >>> (32 - tableSizeMagnitude);
      int vB1 = table[whichValueB];
      int vB2 = table[whichValueB + 1];
      int strengthB2 = (phaseB >>> (32 - 16 - tableSizeMagnitude)) & 0xFFFF;
      int strengthB1 = 65536 - strengthB2;
      int valB = vB1 * strengthB1 + vB2 * strengthB2;

      int val = Q31.mult(valA, valB);

      if (applyAmplitude) {
        currentAmplitude += amplitudeIncrement;
        int wet = Q31.mult(currentAmplitude, val);
        outputBuffer[offset + i] = Q31.addSaturate(outputBuffer[offset + i], wet);
      } else {
        outputBuffer[offset + i] = val;
      }
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
      buffer[offset + i] =
          Q31.multiply_accumulate_32x32_rshift32_rounded(
              buffer[offset + i], currentPhase, currentAmplitude);
    }
    return currentPhase;
  }

  public static int renderCrudeSawWaveWithoutAmplitude(
      int[] buffer, int offset, int numSamples, int phase, int phaseIncrement) {
    int currentPhase = phase;
    for (int i = 0; i < numSamples; i++) {
      currentPhase += phaseIncrement;
      buffer[offset + i] = currentPhase >> 1;
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
