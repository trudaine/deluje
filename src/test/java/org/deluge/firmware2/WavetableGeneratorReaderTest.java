package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WavetableGeneratorReaderTest {

  @TempDir Path tempDir;

  @Test
  void bandGainStaysConstantAcrossAllOctaveBands() {
    int cycleSize = 2048;
    float amplitude = 0.25f;
    float[] samples = new float[cycleSize];
    for (int i = 0; i < cycleSize; i++) {
      samples[i] = amplitude * (float) Math.sin(2.0 * Math.PI * i / cycleSize);
    }

    WaveTable wt = new WaveTable();
    wt.setup(cycleSize, cycleSize);
    WavetableGenerator.generateBands(wt, samples);

    int expectedPeak = Math.round(amplitude * 32768.0f);
    assertFalse(wt.bands.isEmpty());
    for (int b = 0; b < wt.bands.size(); b++) {
      WaveTableBand band = wt.bands.get(b);
      int peak = 0;
      for (int i = 0; i < band.cycleSizeNoDuplicates; i++) {
        peak = Math.max(peak, Math.abs(band.data[i]));
      }
      assertEquals(
          expectedPeak,
          peak,
          64,
          "Band " + b + " (size " + band.cycleSizeNoDuplicates + ") must keep constant amplitude");
    }
  }

  @Test
  void band0CopiesPowerOfTwoCycleDirectlyAndFillsDuplicateTail() {
    int cycleSize = 64;
    float[] samples = new float[cycleSize];
    short[] expected = new short[cycleSize];
    for (int i = 0; i < cycleSize; i++) {
      short raw = (short) (-32768 + i * 1024);
      expected[i] = raw;
      samples[i] = raw / 32768.0f;
    }

    WaveTable wt = new WaveTable();
    wt.setup(cycleSize, cycleSize);
    WavetableGenerator.generateBands(wt, samples);

    WaveTableBand band0 = wt.bands.get(0);
    for (int i = 0; i < cycleSize; i++) {
      assertEquals(expected[i], band0.data[i], "Sample " + i + " must round-trip losslessly");
    }
    for (int i = 0; i < WaveTable.WAVETABLE_NUM_DUPLICATE_SAMPLES_AT_END_OF_CYCLE; i++) {
      assertEquals(
          expected[i],
          band0.data[cycleSize + i],
          "Duplicate tail sample " + i + " must match cycle start");
    }
  }

  @Test
  void nyquistBinIsFoldedIntoRealInHigherBandAndRemovedAbove() {
    int cycleSize = 2048;
    int nyquistBand1 = 512; // Nyquist bin for band 1 (size 1024)
    float[] samples = new float[cycleSize];
    for (int i = 0; i < cycleSize; i++) {
      // Phase-shifted harmonic 512 has both real and imaginary components in the forward FFT.
      samples[i] =
          0.5f * (float) Math.sin(2.0 * Math.PI * nyquistBand1 * i / cycleSize + Math.PI / 3.0);
    }

    WaveTable wt = new WaveTable();
    wt.setup(cycleSize, cycleSize);
    WavetableGenerator.generateBands(wt, samples);

    // Band 1 (size 1024) has harmonic 512 at Nyquist: folded into real (-1)^n with magnitude
    // 0.25 * 32768 = 8192 (since forward FFT bin 512 has magnitude 0.5 * 2048 / 2 = 512, divided
    // by 2048 = 0.25).
    WaveTableBand band1 = wt.bands.get(1);
    assertEquals(1024, band1.cycleSizeNoDuplicates);
    int band1Peak = 0;
    for (int i = 0; i < band1.cycleSizeNoDuplicates; i++) {
      band1Peak = Math.max(band1Peak, Math.abs(band1.data[i]));
    }
    assertEquals(8192, band1Peak, 8, "Band 1 must fold Nyquist magnitude into real component");

    // Band 2 (size 512) has Nyquist at harmonic 256, so harmonic 512 is completely filtered out
    // (within 1 LSB due to Q19 input quantization and floor shift).
    WaveTableBand band2 = wt.bands.get(2);
    assertEquals(512, band2.cycleSizeNoDuplicates);
    for (int i = 0; i < band2.cycleSizeNoDuplicates; i++) {
      assertTrue(
          Math.abs(band2.data[i]) <= 1, "Band 2 must filter out harmonics above its Nyquist");
    }
  }

  @Test
  void nonPowerOfTwoSingleCycleUsesDftAndTrailingSamplesAreIgnoredForMultiCycle() {
    // 1. Single-cycle non-power-of-two (300 samples -> band 0 size 512).
    int rawCycleSize = 300;
    float[] singleCycle = new float[rawCycleSize];
    for (int i = 0; i < rawCycleSize; i++) {
      singleCycle[i] = 0.5f * (float) Math.sin(2.0 * Math.PI * i / rawCycleSize);
    }
    WaveTable singleWt = new WaveTable();
    singleWt.setup(rawCycleSize, rawCycleSize);
    assertEquals(1, singleWt.numCycles);
    assertEquals(512, singleWt.bands.get(0).cycleSizeNoDuplicates);

    WavetableGenerator.generateBands(singleWt, singleCycle);
    int peak = 0;
    for (int i = 0; i < 512; i++) {
      peak = Math.max(peak, Math.abs(singleWt.bands.get(0).data[i]));
    }
    assertTrue(peak > 8000 && peak < 17000, "Non-power-of-two single-cycle DFT must render signal");

    // 2. Multi-cycle power-of-two with trailing samples at the end of the file.
    int cycleSize = 256;
    int totalWithTrailing = cycleSize * 2 + 19;
    float[] multiSamples = new float[totalWithTrailing];
    for (int c = 0; c < 2; c++) {
      for (int i = 0; i < cycleSize; i++) {
        multiSamples[c * cycleSize + i] =
            0.4f * (float) Math.sin(2.0 * Math.PI * (c + 1) * i / cycleSize);
      }
    }
    WaveTable multiWt = new WaveTable();
    multiWt.setup(cycleSize, totalWithTrailing);
    assertEquals(2, multiWt.numCycles);
    WavetableGenerator.generateBands(multiWt, multiSamples);
    assertEquals(256, multiWt.bands.get(0).cycleSizeNoDuplicates);
  }

  @Test
  void readerParsesSerumClmChunkBeforeAndAfterDataWithOddPadding() throws IOException {
    int cycleSize = 256;
    int numCycles = 4;
    float[] samples = new float[cycleSize * numCycles];
    for (int i = 0; i < samples.length; i++) {
      samples[i] = 0.5f * (float) Math.sin(2.0 * Math.PI * (i % cycleSize) / cycleSize);
    }

    // "<!>0256" is 7 bytes (odd length -> 1 RIFF pad byte).
    byte[] clmPayload = "<!>0256".getBytes(StandardCharsets.US_ASCII);
    File clmBefore = tempDir.resolve("clm_before.wav").toFile();
    File clmAfter = tempDir.resolve("clm_after.wav").toFile();
    writeCustomWav(clmBefore, 1, 1, 16, samples, clmPayload, true);
    writeCustomWav(clmAfter, 1, 1, 16, samples, clmPayload, false);

    WaveTable wtBefore = new WaveTable();
    WaveTableReader.readWavetable(wtBefore, clmBefore.getAbsolutePath());
    assertEquals(numCycles, wtBefore.numCycles);
    assertEquals(cycleSize, wtBefore.bands.get(0).cycleSizeNoDuplicates);

    WaveTable wtAfter = new WaveTable();
    WaveTableReader.readWavetable(wtAfter, clmAfter.getAbsolutePath());
    assertEquals(numCycles, wtAfter.numCycles);
    assertEquals(cycleSize, wtAfter.bands.get(0).cycleSizeNoDuplicates);
  }

  @Test
  void readerDecodes24BitPcmAnd32BitFloatAndRejectsStereo() throws IOException {
    int cycleSize = 2048;
    float[] samples = new float[cycleSize];
    for (int i = 0; i < cycleSize; i++) {
      samples[i] = 0.5f * (float) Math.sin(2.0 * Math.PI * i / cycleSize);
    }

    File wav24 = tempDir.resolve("mono_24bit.wav").toFile();
    File wavFloat = tempDir.resolve("mono_float32.wav").toFile();
    File wavStereo = tempDir.resolve("stereo_16bit.wav").toFile();
    writeCustomWav(wav24, 1, 1, 24, samples, null, false);
    writeCustomWav(wavFloat, 3, 1, 32, samples, null, false);
    writeCustomWav(wavStereo, 1, 2, 16, samples, null, false);

    WaveTable wt24 = new WaveTable();
    WaveTableReader.readWavetable(wt24, wav24.getAbsolutePath());
    assertEquals(1, wt24.numCycles);
    assertEquals(16384, maxAbs(wt24.bands.get(0).data, cycleSize), 8);

    WaveTable wtFloat = new WaveTable();
    WaveTableReader.readWavetable(wtFloat, wavFloat.getAbsolutePath());
    assertEquals(1, wtFloat.numCycles);
    assertEquals(16384, maxAbs(wtFloat.bands.get(0).data, cycleSize), 8);

    WaveTable wtStereo = new WaveTable();
    WaveTableReader.readWavetable(wtStereo, wavStereo.getAbsolutePath());
    assertTrue(wtStereo.bands.isEmpty(), "Stereo WAV must be rejected as a wavetable");
  }

  @Test
  void band0TruncatesQ31RatherThanRoundingFor24Bit32BitAndFloat() {
    // 24-bit +511 (0x0001FF) -> value32 = 0x0001FF00 -> value32 >> 16 = 1 (rounding would give 2).
    // 24-bit -127 (0xFFFF81) -> value32 = 0xFFFF8100 -> value32 >> 16 = -1 (rounding would give 0).
    byte[] raw24 =
        new byte[] {
          (byte) 0xFF,
          0x01,
          0x00, // +511
          (byte) 0x81,
          (byte) 0xFF,
          (byte) 0xFF, // -127
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0,
          0
        };
    ByteBuffer bb24 = ByteBuffer.wrap(raw24).order(ByteOrder.LITTLE_ENDIAN);
    int[] q31From24 = WaveTableReader.decodeSamplesToQ31(raw24, bb24, 0, 8, 1, 3);
    assertEquals(0x0001FF00, q31From24[0]);
    assertEquals(0xFFFF8100, q31From24[1]);

    WaveTable wt = new WaveTable();
    wt.setup(8, 8);
    WavetableGenerator.generateBands(wt, q31From24);
    assertEquals(1, wt.bands.get(0).data[0], "Band 0 must floor (value32 >> 16), not round");
    assertEquals(-1, wt.bands.get(0).data[1], "Band 0 must floor negative values toward -infinity");

    // Out-of-range float saturates via q31FromFloat (fixedpoint.h:224-252) without pre-clamp drift.
    assertEquals(Integer.MAX_VALUE, WaveTableReader.q31FromFloat(1.25f));
    assertEquals(Integer.MIN_VALUE, WaveTableReader.q31FromFloat(-1.25f));
  }

  private static int maxAbs(short[] data, int len) {
    int max = 0;
    for (int i = 0; i < len; i++) {
      max = Math.max(max, Math.abs(data[i]));
    }
    return max;
  }

  private static void writeCustomWav(
      File file,
      int audioFormat,
      int numChannels,
      int bitsPerSample,
      float[] monoSamples,
      byte[] clmPayload,
      boolean clmBeforeData)
      throws IOException {
    int byteDepth = bitsPerSample >> 3;
    int totalFrames = monoSamples.length;
    int dataSize = totalFrames * numChannels * byteDepth;
    byte[] dataBytes = new byte[dataSize];
    ByteBuffer dataBB = ByteBuffer.wrap(dataBytes).order(ByteOrder.LITTLE_ENDIAN);

    for (int i = 0; i < totalFrames; i++) {
      float s = monoSamples[i];
      for (int ch = 0; ch < numChannels; ch++) {
        if (audioFormat == 3) {
          dataBB.putFloat(s);
        } else if (byteDepth == 2) {
          dataBB.putShort((short) Math.round(s * 32767.0f));
        } else if (byteDepth == 3) {
          int v = Math.round(s * 8388607.0f);
          dataBB.put((byte) (v & 0xFF));
          dataBB.put((byte) ((v >> 8) & 0xFF));
          dataBB.put((byte) ((v >> 16) & 0xFF));
        } else if (byteDepth == 4) {
          dataBB.putInt((int) Math.round(s * 2147483647.0));
        }
      }
    }

    ByteArrayOutputStream chunks = new ByteArrayOutputStream();
    // fmt chunk (16 bytes)
    ByteBuffer fmt = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
    fmt.putInt(0x20746D66); // "fmt "
    fmt.putInt(16);
    fmt.putShort((short) audioFormat);
    fmt.putShort((short) numChannels);
    fmt.putInt(44100);
    fmt.putInt(44100 * numChannels * byteDepth);
    fmt.putShort((short) (numChannels * byteDepth));
    fmt.putShort((short) bitsPerSample);
    chunks.write(fmt.array());

    if (clmPayload != null && clmBeforeData) {
      writeChunk(chunks, 0x206D6C63, clmPayload);
    }
    writeChunk(chunks, 0x61746164, dataBytes);
    if (clmPayload != null && !clmBeforeData) {
      writeChunk(chunks, 0x206D6C63, clmPayload);
    }

    byte[] body = chunks.toByteArray();
    ByteBuffer riff = ByteBuffer.allocate(12 + body.length).order(ByteOrder.LITTLE_ENDIAN);
    riff.putInt(0x46464952); // "RIFF"
    riff.putInt(4 + body.length);
    riff.putInt(0x45564157); // "WAVE"
    riff.put(body);
    Files.write(file.toPath(), riff.array());
  }

  private static void writeChunk(ByteArrayOutputStream out, int chunkId, byte[] payload)
      throws IOException {
    ByteBuffer hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
    hdr.putInt(chunkId);
    hdr.putInt(payload.length);
    out.write(hdr.array());
    out.write(payload);
    if ((payload.length & 1) != 0) {
      out.write(0); // RIFF odd-length pad byte
    }
  }
}
