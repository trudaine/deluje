package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Bit-exact golden-buffer test for {@link Ne10FftInt32} against the real C firmware {@code
 * NE10_fft.c} + {@code NE10_fft_int32.c} ({@code scaled_flag = false}).
 *
 * <p>Goldens: {@code src/test/resources/fidelity/ne10_fft/}; regenerate with {@code
 * tools/ne10_fft_harness/build.sh}.
 */
class Ne10FftInt32GoldenBufferTest {

  private static Stream<Arguments> fftSizes() {
    return Stream.of(8, 16, 32, 64, 128, 256, 512, 1024, 2048).map(Arguments::of);
  }

  /** Bit-identical to main_ne10_fft.cpp synthTimeSample(). */
  private static int synthTimeSample(int nfft, int i) {
    int x = i * 1103515245 + nfft * 12345 + 0x9E3779B9;
    x ^= (x >>> 13);
    x *= 1664525;
    x ^= (x >>> 16);
    return (x << 13) >> 13;
  }

  /** Bit-identical to main_ne10_fft.cpp synthFreqComponent(). */
  private static int synthFreqComponent(int nfft, int k, int imagPart) {
    int x = k * 0x9E3779B1 + nfft * 1013904223 + imagPart * 0x85EBCA6B;
    x ^= (x >>> 15);
    x *= 0x85EBCA77; // 2246822519u as int32
    x ^= (x >>> 13);
    return (x << 14) >> 14;
  }

  @ParameterizedTest(name = "r2c nfft={0}")
  @MethodSource("fftSizes")
  void r2cMatchesCGoldenBitExact(int nfft) throws IOException {
    int ncfft = nfft >> 1;
    int mag = Integer.numberOfTrailingZeros(nfft);
    Ne10FftInt32.Config cfg = Ne10FftInt32.getConfig(mag);
    assertNotNull(cfg);

    int[] fin = new int[nfft];
    for (int i = 0; i < nfft; i++) {
      fin[i] = synthTimeSample(nfft, i);
    }
    int[] fout = new int[2 * (ncfft + 1)];
    Ne10FftInt32.r2c1d(fout, fin, cfg, false);

    int[] expected = readGolden("c_ne10_r2c_n" + nfft + ".bin");
    assertNotNull(expected, "missing golden resource for r2c n=" + nfft);
    assertEquals(fout.length, expected.length);

    assertBitExact("r2c_n" + nfft, fout, expected);
  }

  @ParameterizedTest(name = "c2r nfft={0}")
  @MethodSource("fftSizes")
  void c2rMatchesCGoldenBitExact(int nfft) throws IOException {
    int ncfft = nfft >> 1;
    int mag = Integer.numberOfTrailingZeros(nfft);
    Ne10FftInt32.Config cfg = Ne10FftInt32.getConfig(mag);
    assertNotNull(cfg);

    int[] fin = new int[2 * (ncfft + 1)];
    fin[0] = synthFreqComponent(nfft, 0, 0);
    fin[1] = 0;
    for (int k = 1; k < ncfft; k++) {
      fin[2 * k] = synthFreqComponent(nfft, k, 0);
      fin[2 * k + 1] = synthFreqComponent(nfft, k, 1);
    }
    fin[2 * ncfft] = synthFreqComponent(nfft, ncfft, 0);
    fin[2 * ncfft + 1] = 0;

    int[] fout = new int[nfft];
    Ne10FftInt32.c2r1d(fout, fin, cfg, false);

    int[] expected = readGolden("c_ne10_c2r_n" + nfft + ".bin");
    assertNotNull(expected, "missing golden resource for c2r n=" + nfft);
    assertEquals(fout.length, expected.length);

    assertBitExact("c2r_n" + nfft, fout, expected);
  }

  @Test
  void wavetableGeneratorUsesNe10Int32FftForHigherBands() {
    int cycleSize = 256;
    int[] q31 = new int[cycleSize];
    for (int i = 0; i < cycleSize; i++) {
      q31[i] = synthTimeSample(cycleSize, i) << 12;
    }

    WaveTable wt = new WaveTable();
    wt.setup(cycleSize, cycleSize);
    WavetableGenerator.generateBands(wt, q31);

    // Manually run the same C pipeline (wave_table.cpp:437, 579, 681-724) for band 1 (size 128).
    int[] timeIn = new int[cycleSize];
    for (int i = 0; i < cycleSize; i++) {
      timeIn[i] = q31[i] >> 12;
    }
    int[] freq = new int[cycleSize + 2];
    Ne10FftInt32.r2c1d(freq, timeIn, Ne10FftInt32.getConfig(8), false);

    int nyquistBand1 = 64;
    int nyqRe = freq[2 * nyquistBand1];
    int nyqIm = freq[2 * nyquistBand1 + 1];
    int pythag = Functions.fastPythag(nyqRe, nyqIm);
    freq[2 * nyquistBand1] = (nyqRe < 0) ? -pythag : pythag;
    freq[2 * nyquistBand1 + 1] = 0;

    int[] timeOut = new int[128];
    Ne10FftInt32.c2r1d(timeOut, freq, Ne10FftInt32.getConfig(7), false);

    WaveTableBand band1 = wt.bands.get(1);
    assertEquals(128, band1.cycleSizeNoDuplicates);
    int outputShift = 16 - 12 + 8;
    for (int i = 0; i < 128; i++) {
      int shifted = timeOut[i] >> outputShift;
      short expected = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, shifted));
      assertEquals(expected, band1.data[i], "Band 1 sample " + i);
    }
  }

  private static void assertBitExact(String label, int[] actual, int[] expected) {
    int firstDiff = -1;
    long maxAbs = 0;
    for (int i = 0; i < actual.length; i++) {
      long d = Math.abs((long) actual[i] - (long) expected[i]);
      if (d != 0 && firstDiff < 0) {
        firstDiff = i;
      }
      if (d > maxAbs) {
        maxAbs = d;
      }
    }
    final int fd = Math.max(firstDiff, 0);
    assertEquals(
        0L,
        maxAbs,
        () ->
            label
                + " diverges at index "
                + fd
                + " (java="
                + actual[fd]
                + " golden="
                + expected[fd]
                + ")");
  }

  private static int[] readGolden(String file) throws IOException {
    try (InputStream in =
        Ne10FftInt32GoldenBufferTest.class.getResourceAsStream("/fidelity/ne10_fft/" + file)) {
      if (in == null) {
        return null;
      }
      byte[] raw = in.readAllBytes();
      ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int[] out = new int[raw.length / 4];
      for (int i = 0; i < out.length; i++) {
        out[i] = bb.getInt();
      }
      return out;
    }
  }
}
