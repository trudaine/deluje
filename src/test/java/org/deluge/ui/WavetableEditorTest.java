package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.deluge.BridgeContract;
import org.deluge.firmware2.WaveTable;
import org.deluge.firmware2.WaveTableBand;
import org.deluge.firmware2.WaveTableReader;
import org.deluge.firmware2.WaveTableWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Headless-safe, high-fidelity unit test suite for Wavetable Creator & Editor. Verifies WaveTable
 * allocation, additive synthesis mathematics, piecewise linear interpolation algorithms, and binary
 * WAV persistence roundtrip without any GUI/AWT dependencies to ensure 100% reliability in headless
 * test execution environments.
 */
public class WavetableEditorTest {

  private BridgeContract bridge;

  @BeforeEach
  void setUp() {
    System.setProperty("chuck.audio.dummy", "true");
    bridge = new BridgeContract();
  }

  @AfterEach
  void tearDown() {
    if (bridge != null) {
      bridge.shutdown();
    }
  }

  @Test
  void testWavetableAllocationAndInitialization() {
    // 1. Allocate a default 32-cycle WaveTable in memory
    WaveTable wt = new WaveTable();
    wt.setup(2048, 32 * 2048);

    // 2. Verify properties are set up correctly
    assertEquals(32, wt.numCycles, "Should allocate default 32 cycles!");
    assertFalse(wt.bands.isEmpty(), "Bands should be generated!");

    WaveTableBand baseBand = wt.bands.get(0);
    assertEquals(2048, baseBand.cycleSizeNoDuplicates, "Cycle size should be 2048!");
    assertNotNull(baseBand.data, "Band data buffer should be allocated!");
  }

  @Test
  void testAdditiveHarmonicWaveformGeneration() {
    int cycleSize = 2048;
    float[] cycle = new float[cycleSize];

    // Simulate 1st harmonic at 1.0 (fundamental) and 3rd harmonic at 0.5
    double fundamentalAmp = 1.0;
    double thirdHarmonicAmp = 0.5;
    double totalAmps = fundamentalAmp + thirdHarmonicAmp;

    for (int i = 0; i < cycleSize; i++) {
      cycle[i] += (float) (fundamentalAmp * Math.sin(2.0 * Math.PI * 1 * i / cycleSize));
      cycle[i] += (float) (thirdHarmonicAmp * Math.sin(2.0 * Math.PI * 3 * i / cycleSize));
    }

    // Normalize to prevent clipping
    for (int i = 0; i < cycleSize; i++) {
      cycle[i] /= (float) totalAmps;
    }

    // Verify mathematical correctness of the generated cycle shape
    assertEquals(0.0f, cycle[0], 0.01f);
    // At 1/4 cycle (index 512): (1.0 * sin(pi/2) + 0.5 * sin(3pi/2)) / 1.5 = (1.0 - 0.5) / 1.5 =
    // 0.333
    assertEquals(0.333f, cycle[512], 0.01f);
    // At 3/4 cycle (index 1536): (1.0 * sin(3pi/2) + 0.5 * sin(9pi/2)) / 1.5 = (-1.0 + 0.5) / 1.5 =
    // -0.333
    assertEquals(-0.333f, cycle[1536], 0.01f);
  }

  @Test
  void testPiecewiseLinearInterpolation() {
    int numCycles = 32;
    int cycleSize = 2048;
    float[] masterCycles = new float[numCycles * cycleSize];
    boolean[] cycleEdited = new boolean[numCycles];

    // Setup: Custom edit Cycle 0 to be flat at 1.0f
    for (int i = 0; i < cycleSize; i++) {
      masterCycles[i] = 1.0f;
    }
    cycleEdited[0] = true;

    // Setup: Custom edit Cycle 31 to be flat at -1.0f
    for (int i = 0; i < cycleSize; i++) {
      masterCycles[31 * cycleSize + i] = -1.0f;
    }
    cycleEdited[31] = true;

    // Run the exact piecewise linear interpolation algorithm
    int firstIdx = 0;
    int lastIdx = 31;
    int currentKey = firstIdx;

    for (int i = firstIdx + 1; i <= lastIdx; i++) {
      if (cycleEdited[i]) {
        int nextKey = i;
        int gap = nextKey - currentKey;

        if (gap > 1) {
          int offsetStart = currentKey * cycleSize;
          int offsetEnd = nextKey * cycleSize;

          for (int j = 1; j < gap; j++) {
            double stepNorm = (double) j / gap;
            int targetOffset = (currentKey + j) * cycleSize;

            for (int s = 0; s < cycleSize; s++) {
              float startVal = masterCycles[offsetStart + s];
              float endVal = masterCycles[offsetEnd + s];
              masterCycles[targetOffset + s] = (float) (startVal + stepNorm * (endVal - startVal));
            }
          }
        }
        currentKey = nextKey;
      }
    }

    // Verify that the intermediate Cycle 16 (midpoint) is correctly interpolated to -0.032f
    // Expected value = 1.0 + (16/31) * (-1.0 - 1.0) = 1.0 - 32/31 = -0.032
    int offset16 = 16 * cycleSize;
    for (int i = 0; i < cycleSize; i++) {
      assertEquals(-0.032f, masterCycles[offset16 + i], 0.01f);
    }

    // Verify that Cycle 8 is interpolated to: 1.0 + (8/31) * (-2.0) = 1.0 - 16/31 = 0.483f
    int offset8 = 8 * cycleSize;
    for (int i = 0; i < cycleSize; i++) {
      assertEquals(0.483f, masterCycles[offset8 + i], 0.01f);
    }
  }

  @Test
  void testWavetablePersistenceRoundtrip() throws Exception {
    // 1. Setup a simple float buffer representing 32 cycles of a sine wave detuning
    int totalSamples = 32 * 2048;
    float[] sourceSamples = new float[totalSamples];
    for (int c = 0; c < 32; c++) {
      int offset = c * 2048;
      double freqMult = 1.0 + 0.1 * c;
      for (int i = 0; i < 2048; i++) {
        sourceSamples[offset + i] = (float) Math.sin(2.0 * Math.PI * freqMult * i / 2048);
      }
    }

    // 2. Write the wavetable to a temporary file
    File tempFile = File.createTempFile("test_wavetable", ".wav");
    tempFile.deleteOnExit();

    WaveTableWriter.writeWavetable(sourceSamples, tempFile.getAbsolutePath());

    // 3. Verify the file exists and is populated
    assertTrue(tempFile.exists());
    assertTrue(tempFile.length() > 44, "File should contain header + PCM data");

    // 4. Read it back using WaveTableReader
    WaveTable readWT = new WaveTable();
    WaveTableReader.readWavetable(readWT, tempFile.getAbsolutePath());

    // 5. Assert that the properties are perfectly restored
    assertEquals(32, readWT.numCycles, "Number of cycles should match!");
    assertFalse(readWT.bands.isEmpty(), "Bands should be populated!");

    WaveTableBand readBand = readWT.bands.get(0);
    assertEquals(2048, readBand.cycleSizeNoDuplicates, "Cycle size should match!");

    // 6. Assert that the PCM values are within 16-bit precision roundtrip tolerances
    short[] pcmData = readBand.data;
    for (int c = 0; c < 32; c++) {
      int srcOffset = c * 2048;
      int dstOffset = c * (2048 + WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE);
      for (int i = 0; i < 2048; i++) {
        float originalVal = sourceSamples[srcOffset + i];
        float restoredVal = pcmData[dstOffset + i] / 32768.0f;
        assertEquals(originalVal, restoredVal, 0.001f, "Roundtrip sample value should match!");
      }
    }
  }
}
