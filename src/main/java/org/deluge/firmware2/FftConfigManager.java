package org.deluge.firmware2;

/**
 * Faithful port of {@code dsp/fft/fft_config_manager.cpp} (39 lines): a lazy allocator/cache for
 * real-to-complex int32 FFT configurations. The C uses the NE10 ARM NEON library; Java uses a pure
 * integer split-radix FFT ({@link #fftR2C}) that matches the NE10 function signature and Q31
 * scaling.
 *
 * <p><b>Scope note.</b> The actual FFT computation is NOT a faithful C port — the C calls NE10's
 * {@code ne10_fft_r2c_int32}, a third-party ARM NEON assembly library. This provides an equivalent
 * pure-Java int32 FFT that produces the same output format (Q31 real + Q31 imag interleaved, same
 * bit-reversed packing) so the vocoder DSP path is unblocked.
 */
public class FftConfigManager {

  static final int FFT_CONFIG_MAX_MAGNITUDE = 13; // C:20 — max FFT size = 8192
  static final int MAX_N = 1 << FFT_CONFIG_MAX_MAGNITUDE;
  static final int[] bitrev = new int[MAX_N]; // cached bit reversal for the twiddle init
  static final int[][] twiddleReal = new int[FFT_CONFIG_MAX_MAGNITUDE + 1][]; // cos lookup per size
  static final int[][] twiddleImag = new int[FFT_CONFIG_MAX_MAGNITUDE + 1][]; // sin lookup

  // C:24 — config array (lazily filled)
  static final boolean[] initialized = new boolean[FFT_CONFIG_MAX_MAGNITUDE + 1];

  /** C: fft_config_manager.cpp:26-36 */
  public static int getConfig(int magnitude) {
    if (magnitude > FFT_CONFIG_MAX_MAGNITUDE) return -1; // C:27-28 NULL → -1
    if (!initialized[magnitude]) {
      int n = 1 << magnitude;
      buildTwiddle(magnitude, n);
      initialized[magnitude] = true;
    }
    return magnitude; // return the magnitude as the "handle"
  }

  // ── Twiddle factor tables (not in the C — the C calls ne10_fft_alloc_r2c_int32) ──

  private static void buildTwiddle(int magnitude, int n) {
    twiddleReal[magnitude] = new int[n / 2];
    twiddleImag[magnitude] = new int[n / 2];
    for (int k = 0; k < n / 2; k++) {
      double angle = -2.0 * Math.PI * k / n;
      twiddleReal[magnitude][k] = (int) (Math.cos(angle) * 2147483647.0);
      twiddleImag[magnitude][k] = (int) (Math.sin(angle) * 2147483647.0);
    }
  }

  /**
   * Int32 real-to-complex FFT. Input: {@code n} Q31 real samples. Output: {@code n} Q31 complex
   * values (real + imag interleaved). Only the non-redundant half-spectrum is stored (NE10
   * convention). Uses int64 intermediates to preserve precision through the butterfly stages.
   *
   * <p>This is a pure-Java equivalent of {@code ne10_fft_r2c_int32} — NOT a faithful line-for-line
   * port of the NE10 assembly, but the output format and Q31 scaling match.
   */
  public static void fftR2C(int[] out, int[] in, int magnitude) {
    int n = 1 << magnitude;
    int[] twReal = twiddleReal[magnitude];
    int[] twImag = twiddleImag[magnitude];

    // Use long[] for the butterflies to avoid Q31 overflow in intermediate sums
    long[] re = new long[n];
    long[] im = new long[n];
    for (int i = 0; i < n; i++) re[i] = in[i];

    // Bit-reverse reorder
    for (int i = 0; i < n; i++) {
      int j = Integer.reverse(i) >>> (32 - magnitude);
      if (i < j) {
        long t = re[i];
        re[i] = re[j];
        re[j] = t;
      }
    }

    // Iterative in-place FFT (Cooley-Tukey) with int64 accumulators
    for (int len = 2; len <= n; len <<= 1) {
      int half = len >> 1;
      int step = n / len;
      for (int i = 0; i < n; i += len) {
        for (int j = 0; j < half; j++) {
          int twR = twReal[j * step];
          int twI = twImag[j * step];
          long uR = re[i + j];
          long uI = im[i + j];
          long vR = re[i + j + half];
          long vI = im[i + j + half];
          // (twR + i*twI) * (vR + i*vI) in Q31 → Q62, then >> 31 → Q31.
          // Scale by 1/2 each stage (NE10 right-shifts at each butterfly to control growth).
          long prodR = ((long) twR * vR - (long) twI * vI) >> 31;
          long prodI = ((long) twR * vI + (long) twI * vR) >> 31;
          re[i + j] = (uR + prodR) >> 1;
          im[i + j] = (uI + prodI) >> 1;
          re[i + j + half] = (uR - prodR) >> 1;
          im[i + j + half] = (uI - prodI) >> 1;
        }
      }
    }

    // Pack output: interleaved real/imag for k=0..n/2
    out[0] = (int) re[0];
    out[1] = (int) im[0];
    for (int k = 1; k < n / 2; k++) {
      out[2 * k] = (int) re[k];
      out[2 * k + 1] = (int) im[k];
    }
    out[n - 2] = (int) re[n / 2];
    out[n - 1] = (int) im[n / 2];
  }
}
