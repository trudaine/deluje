package org.chuck.deluge.firmware.dsp.oscillators;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.util.LookupTables;
import org.chuck.deluge.firmware.util.Q31;
import org.junit.jupiter.api.Test;

public class BasicWavesTest {

  @Test
  public void testRenderSine() {
    int[] buffer = new int[128];
    int numSamples = 128;
    int phaseIncrement = 10000000; // arbitrary
    int startPhase = 0;

    BasicWaves.renderWave(
        LookupTables.sineWaveSmall,
        8,
        Q31.ONE,
        buffer,
        0,
        numSamples,
        phaseIncrement,
        startPhase,
        false,
        0,
        0);

    // buffer[0] should be close to sine(10000000)
    // 10000000 / 2^32 * 256 = index into sine table
    int firstSample = buffer[0];
    assertTrue(firstSample > 0);

    // With amplitude
    int[] bufferWithAmp = new int[128];
    BasicWaves.renderWave(
        LookupTables.sineWaveSmall,
        8,
        Q31.ONE,
        bufferWithAmp,
        0,
        numSamples,
        phaseIncrement,
        startPhase,
        true,
        0,
        0);

    // Since original buffer was all zeros, adding with amp should result in same value as without
    // amp?
    // Wait, renderWave says valueVector = valueVector.add(existingDataInBuffer);
    // If buffer was zero, then yes.
    assertEquals(firstSample, bufferWithAmp[0], 100);
  }
}
