package org.deluge.firmware2;

/**
 * Port of NE10's plain-C fixed-point {@code int32} real-to-complex and complex-to-real 1D FFT
 * ({@code NE10_fft.c:71-166}, {@code NE10_fft_int32.c:53-1049, 1158-1266}) together with Deluge's
 * cached config lookup ({@code fft_config_manager.cpp:20-36}).
 *
 * <p>Deluge's wavetable band generator calls {@code ne10_fft_r2c_1d_int32_neon} and {@code
 * ne10_fft_c2r_1d_int32_neon} with {@code scaled_flag = false} ({@code wave_table.cpp:579, 712}).
 * Complex arrays use interleaved {@code int[]} layout ({@code arr[2 * k] = r}, {@code arr[2 * k +
 * 1] = i}), matching {@code ne10_fft_cpx_int32_t*}.
 */
public final class Ne10FftInt32 {

  // C fft_config_manager.cpp:20
  public static final int FFT_CONFIG_MAX_MAGNITUDE = 13;

  // C NE10_types.h:65-66, 229
  private static final int NE10_OK = 0;
  private static final int NE10_ERR = -1;
  private static final int NE10_MAXFACTORS = 32;

  // C NE10_fft.h:57-73
  private static final int NE10_FFT_ALG_DEFAULT = 0;
  private static final int NE10_FFT_ALG_ANY = 1;
  private static final int NE10_FACTOR_EIGHT_FIRST_STAGE = 1;
  private static final int NE10_FACTOR_EIGHT = 2;

  // C NE10_macros.h:47, 84
  private static final float NE10_PI = (float) 3.1415926535897932384626433832795;
  private static final int NE10_F2I32_MAX = 2147483647;

  // C NE10_fft_int32.c:73-74
  private static final int TW_81 = 1518500249;
  private static final int TW_81N = -1518500249;

  private static final Config[] CONFIGS = new Config[FFT_CONFIG_MAX_MAGNITUDE + 1];

  private Ne10FftInt32() {}

  /**
   * Immutable configuration matching {@code ne10_fft_r2c_state_int32_t} ({@code
   * NE10_types.h:342-352}). Working scratch buffers are allocated per transform call so cached
   * configs are safe across threads.
   */
  public static final class Config {
    public final int nfft;
    public final int ncfft;
    final int[] factors;
    final int[] twiddles;
    final int[] superTwiddles;

    Config(int nfft, int ncfft, int[] factors, int[] twiddles, int[] superTwiddles) {
      this.nfft = nfft;
      this.ncfft = ncfft;
      this.factors = factors;
      this.twiddles = twiddles;
      this.superTwiddles = superTwiddles;
    }
  }

  /**
   * Returns a cached r2c/c2r config for {@code nfft = 1 << magnitude}, matching {@code
   * FFTConfigManager::getConfig} ({@code fft_config_manager.cpp:26-36}).
   */
  public static synchronized Config getConfig(int magnitude) {
    if (magnitude < 2 || magnitude > FFT_CONFIG_MAX_MAGNITUDE) {
      return null;
    }
    Config cfg = CONFIGS[magnitude];
    if (cfg == null) {
      cfg = allocR2c(1 << magnitude);
      CONFIGS[magnitude] = cfg;
    }
    return cfg;
  }

  /**
   * Allocates and initializes an r2c/c2r {@code int32} FFT config for {@code nfft}, matching {@code
   * ne10_fft_alloc_r2c_int32} ({@code NE10_fft_int32.c:1158-1235}).
   */
  public static Config allocR2c(int nfft) {
    if (nfft < 4 || (nfft & 1) != 0) {
      return null;
    }
    int ncfft = nfft >> 1;
    int[] factors = new int[NE10_MAXFACTORS * 2];
    int result = factor(ncfft, factors, NE10_FACTOR_EIGHT_FIRST_STAGE);
    if (result == NE10_ERR) {
      return null;
    }
    // Plain-C mixed-radix butterfly supports power-of-two ncfft (NE10_FFT_ALG_DEFAULT).
    int stageCount = factors[0];
    int algFlag = factors[2 * (stageCount + 1)];
    if (algFlag != NE10_FFT_ALG_DEFAULT) {
      return null;
    }

    int[] twiddles = new int[2 * ncfft];
    int[] superTwiddles = new int[ncfft]; // ncfft/2 complex pairs = ncfft ints

    // C NE10_fft_int32.c:1192-1218 — generate stage twiddles (skipping the first stage).
    int fstride = factors[1];
    int twOffset = 0; // complex-element offset into twiddles
    stageCount--;
    for (; stageCount > 0; stageCount--) {
      int curRadix = factors[2 * stageCount];
      fstride /= curRadix;
      int mstride = factors[2 * stageCount + 1];
      for (int j = 0; j < mstride; j++) {
        for (int k = 1; k < curRadix; k++) {
          float phase = -2 * NE10_PI * fstride * k * j / ncfft;
          int idx = 2 * (twOffset + mstride * (k - 1) + j);
          twiddles[idx] = (int) Math.floor(0.5f + NE10_F2I32_MAX * Math.cos(phase));
          twiddles[idx + 1] = (int) Math.floor(0.5f + NE10_F2I32_MAX * Math.sin(phase));
        }
      }
      twOffset += mstride * (curRadix - 1);
    }

    // C NE10_fft_int32.c:1224-1231 — generate super_twiddles for split r2c/c2r.
    for (int j = 0; j < ncfft / 2; j++) {
      float phase = -NE10_PI * ((float) (j + 1) / ncfft + 0.5f);
      superTwiddles[2 * j] = (int) Math.floor(0.5f + NE10_F2I32_MAX * Math.cos(phase));
      superTwiddles[2 * j + 1] = (int) Math.floor(0.5f + NE10_F2I32_MAX * Math.sin(phase));
    }

    return new Config(nfft, ncfft, factors, twiddles, superTwiddles);
  }

  /**
   * Forward real-to-complex 1D FFT, matching {@code ne10_fft_r2c_1d_int32_c} ({@code
   * NE10_fft_int32.c:1241-1250}).
   *
   * @param fout interleaved complex output of length {@code >= 2 * (cfg.ncfft + 1)}
   * @param fin real input of length {@code >= cfg.nfft}
   * @param cfg config for {@code nfft}
   * @param scaledFlag {@code false} for unscaled Deluge wavetable FFT
   */
  public static void r2c1d(int[] fout, int[] fin, Config cfg, boolean scaledFlag) {
    int[] tmpbuf = new int[2 * cfg.ncfft];
    // C :1248 — fout is used as the scratch buffer during the ncfft-point complex butterfly.
    mixedRadixButterfly(tmpbuf, fin, cfg.factors, cfg.twiddles, fout, scaledFlag ? 1 : 0);
    // C :1249 — split ncfft complex bins into ncfft + 1 real-FFT bins in fout.
    splitR2c1d(fout, tmpbuf, cfg.superTwiddles, cfg.ncfft, scaledFlag ? 1 : 0);
  }

  /**
   * Inverse complex-to-real 1D FFT, matching {@code ne10_fft_c2r_1d_int32_c} ({@code
   * NE10_fft_int32.c:1256-1266}).
   *
   * @param fout real output of length {@code >= cfg.nfft}
   * @param fin interleaved complex input of length {@code >= 2 * (cfg.ncfft + 1)}
   * @param cfg config for {@code nfft}
   * @param scaledFlag {@code false} for unscaled Deluge wavetable IFFT
   */
  public static void c2r1d(int[] fout, int[] fin, Config cfg, boolean scaledFlag) {
    int[] tmpbuf1 = new int[2 * cfg.ncfft];
    int[] tmpbuf2 = new int[2 * cfg.ncfft];
    // C :1264 — pack ncfft + 1 Hermitian bins into ncfft complex bins in tmpbuf1.
    splitC2r1d(tmpbuf1, fin, cfg.superTwiddles, cfg.ncfft, scaledFlag ? 1 : 0);
    // C :1265 — inverse ncfft-point complex butterfly directly into fout.
    mixedRadixButterflyInverse(
        fout, tmpbuf1, cfg.factors, cfg.twiddles, tmpbuf2, scaledFlag ? 1 : 0);
  }

  // C NE10_fft.c:71-166 — ne10_factor.
  static int factor(int n, int[] facbuf, int factorFlags) {
    if (facbuf == null || n <= 0) {
      return NE10_ERR;
    }
    int p;
    int i = 1;
    int stageNum = 0;
    int strideMax = n;
    int algFlag = NE10_FFT_ALG_DEFAULT;

    do {
      if ((factorFlags & NE10_FACTOR_EIGHT_FIRST_STAGE) != 0 && (n == 8 || n == 40 || n == 24)) {
        switch (n) {
          case 8 -> p = 8;
          case 24 -> {
            p = 3;
            algFlag = NE10_FFT_ALG_ANY;
          }
          default -> {
            p = 5;
            algFlag = NE10_FFT_ALG_ANY;
          }
        }
      } else if ((factorFlags & NE10_FACTOR_EIGHT) != 0 && (n % 8) == 0) {
        p = 8;
      } else if ((n % 4) == 0) {
        p = 4;
      } else if ((n % 2) == 0) {
        p = 2;
      } else if ((n % 5) == 0) {
        p = 5;
        algFlag = NE10_FFT_ALG_ANY;
      } else if ((n % 3) == 0) {
        p = 3;
        algFlag = NE10_FFT_ALG_ANY;
      } else {
        p = n;
        algFlag = NE10_FFT_ALG_ANY;
      }

      n /= p;
      facbuf[2 * i] = p;
      facbuf[2 * i + 1] = n;
      i++;
      stageNum++;
    } while (n > 1);

    facbuf[0] = stageNum;
    facbuf[1] = strideMax / p;
    if (stageNum > 21) {
      return NE10_ERR;
    }
    facbuf[2 * i] = algFlag;
    return NE10_OK;
  }

  // C NE10_fft_int32.c:53-503 — ne10_mixed_radix_butterfly_int32_c.
  private static void mixedRadixButterfly(
      int[] fout, int[] fin, int[] factors, int[] twiddles, int[] buffer, int scaledFlag) {
    int[] foutLs = fout;
    int stageCount = factors[0];
    int fstride = factors[1];
    int mstride = factors[(stageCount << 1) - 1];
    int n = factors[stageCount << 1];
    int tw = 0;

    int fin1 = 0;
    int fout1 = 0;

    if (n == 8) {
      // C :86-207 — radix-8 first stage (when ncfft = 2^odd).
      n = fstride << 1;
      for (int fCount = 0; fCount < fstride; fCount++) {
        fout1 = fCount * 8;
        int c0 = fin1;
        int c4 = fin1 + fstride * 4;
        int c1 = fin1 + fstride;
        int c5 = fin1 + fstride * 5;
        int c2 = fin1 + fstride * 2;
        int c6 = fin1 + fstride * 6;
        int c3 = fin1 + fstride * 3;
        int c7 = fin1 + fstride * 7;

        int in0r = fin[2 * c0];
        int in0i = fin[2 * c0 + 1];
        int in4r = fin[2 * c4];
        int in4i = fin[2 * c4 + 1];
        int in1r = fin[2 * c1];
        int in1i = fin[2 * c1 + 1];
        int in5r = fin[2 * c5];
        int in5i = fin[2 * c5 + 1];
        int in2r = fin[2 * c2];
        int in2i = fin[2 * c2 + 1];
        int in6r = fin[2 * c6];
        int in6i = fin[2 * c6 + 1];
        int in3r = fin[2 * c3];
        int in3i = fin[2 * c3 + 1];
        int in7r = fin[2 * c7];
        int in7i = fin[2 * c7 + 1];

        if (scaledFlag == 1) {
          in0r /= 8;
          in0i /= 8;
          in4r /= 8;
          in4i /= 8;
          in1r /= 8;
          in1i /= 8;
          in5r /= 8;
          in5i /= 8;
          in2r /= 8;
          in2i /= 8;
          in6r /= 8;
          in6i /= 8;
          in3r /= 8;
          in3i /= 8;
          in7r /= 8;
          in7i /= 8;
        }

        int sin0r = in0r + in4r;
        int sin0i = in0i + in4i;
        int sin1r = in0r - in4r;
        int sin1i = in0i - in4i;
        int sin2r = in1r + in5r;
        int sin2i = in1i + in5i;
        int sin3r = in1r - in5r;
        int sin3i = in1i - in5i;
        int sin4r = in2r + in6r;
        int sin4i = in2i + in6i;
        int sin5r = in2r - in6r;
        int sin5i = in2i - in6i;
        int sin6r = in3r + in7r;
        int sin6i = in3i + in7i;
        int sin7r = in3r - in7r;
        int sin7i = in3i - in7i;

        // C :126-140 — int32 sum/diff before (int64) cast * TW_81 / TW_81N.
        int s0r = sin0r;
        int s0i = sin0i;
        int s1r = sin1r;
        int s1i = sin1i;
        int s2r = sin2r;
        int s2i = sin2i;
        int s3r = (int) (((long) (sin3r + sin3i) * TW_81) >> 31);
        int s3i = (int) (((long) (sin3i - sin3r) * TW_81) >> 31);
        int s4r = sin4r;
        int s4i = sin4i;
        int s5r = sin5i;
        int s5i = -sin5r;
        int s6r = sin6r;
        int s6i = sin6i;
        int s7r = (int) (((long) (sin7r - sin7i) * TW_81N) >> 31);
        int s7i = (int) (((long) (sin7i + sin7r) * TW_81N) >> 31);

        int s8r = s0r + s4r;
        int s8i = s0i + s4i;
        int s9r = s1r + s5r;
        int s9i = s1i + s5i;
        int s10r = s0r - s4r;
        int s10i = s0i - s4i;
        int s11r = s1r - s5r;
        int s11i = s1i - s5i;

        int s12r = s2r + s6r;
        int s12i = s2i + s6i;
        int s13r = s3r + s7r;
        int s13i = s3i + s7i;
        int s14r = s2r - s6r;
        int s14i = s2i - s6i;
        int s15r = s3r - s7r;
        int s15i = s3i - s7i;

        int base = 2 * fout1;
        fout[base] = s8r + s12r;
        fout[base + 1] = s8i + s12i;
        fout[base + 2] = s9r + s13r;
        fout[base + 3] = s9i + s13i;
        fout[base + 4] = s10r + s14i;
        fout[base + 5] = s10i - s14r;
        fout[base + 6] = s11r + s15i;
        fout[base + 7] = s11i - s15r;
        fout[base + 8] = s8r - s12r;
        fout[base + 9] = s8i - s12i;
        fout[base + 10] = s9r - s13r;
        fout[base + 11] = s9i - s13i;
        fout[base + 12] = s10r - s14i;
        fout[base + 13] = s10i + s14r;
        fout[base + 14] = s11r - s15i;
        fout[base + 15] = s11i + s15r;

        fin1++;
      }
      fstride >>= 2;
      stageCount--;
      int[] ftmp = buffer;
      buffer = fout;
      fout = ftmp;
    } else if (n == 4) {
      // C :208-281 — radix-4 first stage (when ncfft = 2^even >= 4).
      for (int fCount = fstride; fCount != 0; fCount--) {
        int c0 = fin1;
        int c1 = fin1 + fstride;
        int c2 = c1 + fstride;
        int c3 = c2 + fstride;

        int in0r = fin[2 * c0];
        int in0i = fin[2 * c0 + 1];
        int in1r = fin[2 * c1];
        int in1i = fin[2 * c1 + 1];
        int in2r = fin[2 * c2];
        int in2i = fin[2 * c2 + 1];
        int in3r = fin[2 * c3];
        int in3i = fin[2 * c3 + 1];

        if (scaledFlag == 1) {
          in0r /= 4;
          in0i /= 4;
          in1r /= 4;
          in1i /= 4;
          in2r /= 4;
          in2i /= 4;
          in3r /= 4;
          in3i /= 4;
        }

        int s0r = in0r + in2r;
        int s0i = in0i + in2i;
        int s1r = in0r - in2r;
        int s1i = in0i - in2i;
        int s2r = in1r + in3r;
        int s2i = in1i + in3i;
        int s3r = in1r - in3r;
        int s3i = in1i - in3i;

        int base = 2 * fout1;
        fout[base] = s0r + s2r;
        fout[base + 1] = s0i + s2i;
        fout[base + 2] = s1r + s3i;
        fout[base + 3] = s1i - s3r;
        fout[base + 4] = s0r - s2r;
        fout[base + 5] = s0i - s2i;
        fout[base + 6] = s1r - s3i;
        fout[base + 7] = s1i + s3r;

        fout1 += 4;
        fin1++;
      }
      n = fstride;
      int[] ftmp = buffer;
      buffer = fout;
      fout = ftmp;
      stageCount--;
      fstride >>= 2;
    } else if (n == 2) {
      // C :282-298 — length 2.
      int in0r = fin[0];
      int in0i = fin[1];
      int in1r = fin[2];
      int in1i = fin[3];
      if (scaledFlag == 1) {
        in0r /= 2;
        in0i /= 2;
        in1r /= 2;
        in1i /= 2;
      }
      fout[0] = in0r + in1r;
      fout[1] = in0i + in1i;
      fout[2] = in0r - in1r;
      fout[3] = in0i - in1i;
      return;
    } else {
      // C :299-303 — length 1.
      fout[0] = fin[0];
      fout[1] = fin[1];
      return;
    }

    // C :305-405 — middle stages (all but the last one).
    for (; stageCount > 1; stageCount--) {
      fin1 = 0;
      for (int fCount = 0; fCount < fstride; fCount++) {
        fout1 = (fCount * mstride) << 2;
        int tw1 = tw;
        for (int mCount = mstride; mCount != 0; mCount--) {
          radix4TwiddleStageForward(
              fout, fout1, mstride, buffer, fin1, n, twiddles, tw1, mstride, scaledFlag);
          tw1++;
          fin1++;
          fout1++;
        }
      }
      tw += mstride * 3;
      mstride <<= 2;
      int[] ftmp = buffer;
      buffer = fout;
      fout = ftmp;
      fstride >>= 2;
    }

    // C :407-502 — last stage (writes to foutLs with output stride n).
    if (stageCount != 0) {
      fin1 = 0;
      fout1 = 0;
      for (int fCount = 0; fCount < fstride; fCount++) {
        int tw1 = tw;
        for (int mCount = mstride; mCount != 0; mCount--) {
          radix4TwiddleStageForward(
              foutLs, fout1, n, buffer, fin1, n, twiddles, tw1, mstride, scaledFlag);
          tw1++;
          fin1++;
          fout1++;
        }
      }
    }
  }

  private static void radix4TwiddleStageForward(
      int[] out,
      int outIdx,
      int outStride,
      int[] in,
      int inIdx,
      int inStride,
      int[] twiddles,
      int twIdx,
      int twStride,
      int scaledFlag) {
    int tw0r = twiddles[2 * twIdx];
    int tw0i = twiddles[2 * twIdx + 1];
    int tw1r = twiddles[2 * (twIdx + twStride)];
    int tw1i = twiddles[2 * (twIdx + twStride) + 1];
    int tw2r = twiddles[2 * (twIdx + 2 * twStride)];
    int tw2i = twiddles[2 * (twIdx + 2 * twStride) + 1];

    int i0 = 2 * inIdx;
    int i1 = 2 * (inIdx + inStride);
    int i2 = 2 * (inIdx + 2 * inStride);
    int i3 = 2 * (inIdx + 3 * inStride);

    int in0r = in[i0];
    int in0i = in[i0 + 1];
    int in1r = in[i1];
    int in1i = in[i1 + 1];
    int in2r = in[i2];
    int in2i = in[i2 + 1];
    int in3r = in[i3];
    int in3i = in[i3 + 1];

    if (scaledFlag == 1) {
      in0r /= 4;
      in0i /= 4;
      in1r /= 4;
      in1i /= 4;
      in2r /= 4;
      in2i /= 4;
      in3r /= 4;
      in3i /= 4;
    }

    int s0r = in0r;
    int s0i = in0i;
    int s1r = (int) ((((long) in1r * tw0r) - ((long) in1i * tw0i)) >> 31);
    int s1i = (int) ((((long) in1i * tw0r) + ((long) in1r * tw0i)) >> 31);
    int s2r = (int) ((((long) in2r * tw1r) - ((long) in2i * tw1i)) >> 31);
    int s2i = (int) ((((long) in2i * tw1r) + ((long) in2r * tw1i)) >> 31);
    int s3r = (int) ((((long) in3r * tw2r) - ((long) in3i * tw2i)) >> 31);
    int s3i = (int) ((((long) in3i * tw2r) + ((long) in3r * tw2i)) >> 31);

    int s4r = s0r + s2r;
    int s4i = s0i + s2i;
    int s5r = s0r - s2r;
    int s5i = s0i - s2i;
    int s6r = s1r + s3r;
    int s6i = s1i + s3i;
    int s7r = s1r - s3r;
    int s7i = s1i - s3i;

    int o0 = 2 * outIdx;
    int o1 = 2 * (outIdx + outStride);
    int o2 = 2 * (outIdx + 2 * outStride);
    int o3 = 2 * (outIdx + 3 * outStride);

    out[o0] = s4r + s6r;
    out[o0 + 1] = s4i + s6i;
    out[o1] = s5r + s7i;
    out[o1 + 1] = s5i - s7r;
    out[o2] = s4r - s6r;
    out[o2 + 1] = s4i - s6i;
    out[o3] = s5r - s7i;
    out[o3 + 1] = s5i + s7r;
  }

  // C NE10_fft_int32.c:505-958 — ne10_mixed_radix_butterfly_inverse_int32_c.
  private static void mixedRadixButterflyInverse(
      int[] fout, int[] fin, int[] factors, int[] twiddles, int[] buffer, int scaledFlag) {
    int[] foutLs = fout;
    int stageCount = factors[0];
    int fstride = factors[1];
    int mstride = factors[(stageCount << 1) - 1];
    int n = factors[stageCount << 1];
    int tw = 0;

    int fin1 = 0;
    int fout1 = 0;

    if (n == 8) {
      // C :538-661 — radix-8 inverse first stage (when ncfft = 2^odd).
      n = fstride << 1;
      for (int fCount = 0; fCount < fstride; fCount++) {
        fout1 = fCount * 8;
        int c0 = fin1;
        int c4 = fin1 + fstride * 4;
        int c1 = fin1 + fstride;
        int c5 = fin1 + fstride * 5;
        int c2 = fin1 + fstride * 2;
        int c6 = fin1 + fstride * 6;
        int c3 = fin1 + fstride * 3;
        int c7 = fin1 + fstride * 7;

        int in0r = fin[2 * c0];
        int in0i = fin[2 * c0 + 1];
        int in4r = fin[2 * c4];
        int in4i = fin[2 * c4 + 1];
        int in1r = fin[2 * c1];
        int in1i = fin[2 * c1 + 1];
        int in5r = fin[2 * c5];
        int in5i = fin[2 * c5 + 1];
        int in2r = fin[2 * c2];
        int in2i = fin[2 * c2 + 1];
        int in6r = fin[2 * c6];
        int in6i = fin[2 * c6 + 1];
        int in3r = fin[2 * c3];
        int in3i = fin[2 * c3 + 1];
        int in7r = fin[2 * c7];
        int in7i = fin[2 * c7 + 1];

        if (scaledFlag == 1) {
          in0r /= 8;
          in0i /= 8;
          in4r /= 8;
          in4i /= 8;
          in1r /= 8;
          in1i /= 8;
          in5r /= 8;
          in5i /= 8;
          in2r /= 8;
          in2i /= 8;
          in6r /= 8;
          in6i /= 8;
          in3r /= 8;
          in3i /= 8;
          in7r /= 8;
          in7i /= 8;
        }

        int sin0r = in0r + in4r;
        int sin0i = in0i + in4i;
        int sin1r = in0r - in4r;
        int sin1i = in0i - in4i;
        int sin2r = in1r + in5r;
        int sin2i = in1i + in5i;
        int sin3r = in1r - in5r;
        int sin3i = in1i - in5i;
        int sin4r = in2r + in6r;
        int sin4i = in2i + in6i;
        int sin5r = in2r - in6r;
        int sin5i = in2i - in6i;
        int sin6r = in3r + in7r;
        int sin6i = in3i + in7i;
        int sin7r = in3r - in7r;
        int sin7i = in3i - in7i;

        // C :580-594 — inverse twiddle signs on scratch[3], scratch[5], scratch[7].
        int s0r = sin0r;
        int s0i = sin0i;
        int s1r = sin1r;
        int s1i = sin1i;
        int s2r = sin2r;
        int s2i = sin2i;
        int s3r = (int) (((long) (sin3r - sin3i) * TW_81) >> 31);
        int s3i = (int) (((long) (sin3i + sin3r) * TW_81) >> 31);
        int s4r = sin4r;
        int s4i = sin4i;
        int s5r = -sin5i;
        int s5i = sin5r;
        int s6r = sin6r;
        int s6i = sin6i;
        int s7r = (int) (((long) (sin7r + sin7i) * TW_81N) >> 31);
        int s7i = (int) (((long) (sin7i - sin7r) * TW_81N) >> 31);

        int s8r = s0r + s4r;
        int s8i = s0i + s4i;
        int s9r = s1r + s5r;
        int s9i = s1i + s5i;
        int s10r = s0r - s4r;
        int s10i = s0i - s4i;
        int s11r = s1r - s5r;
        int s11i = s1i - s5i;

        int s12r = s2r + s6r;
        int s12i = s2i + s6i;
        int s13r = s3r + s7r;
        int s13i = s3i + s7i;
        int s14r = s2r - s6r;
        int s14i = s2i - s6i;
        int s15r = s3r - s7r;
        int s15i = s3i - s7i;

        int base = 2 * fout1;
        fout[base] = s8r + s12r;
        fout[base + 1] = s8i + s12i;
        fout[base + 2] = s9r + s13r;
        fout[base + 3] = s9i + s13i;
        fout[base + 4] = s10r - s14i;
        fout[base + 5] = s10i + s14r;
        fout[base + 6] = s11r - s15i;
        fout[base + 7] = s11i + s15r;
        fout[base + 8] = s8r - s12r;
        fout[base + 9] = s8i - s12i;
        fout[base + 10] = s9r - s13r;
        fout[base + 11] = s9i - s13i;
        fout[base + 12] = s10r + s14i;
        fout[base + 13] = s10i - s14r;
        fout[base + 14] = s11r + s15i;
        fout[base + 15] = s11i - s15r;

        fin1++;
      }
      fstride >>= 2;
      stageCount--;
      int[] ftmp = buffer;
      buffer = fout;
      fout = ftmp;
    } else if (n == 4) {
      // C :662-735 — radix-4 inverse first stage.
      for (int fCount = fstride; fCount != 0; fCount--) {
        int c0 = fin1;
        int c1 = fin1 + fstride;
        int c2 = c1 + fstride;
        int c3 = c2 + fstride;

        int in0r = fin[2 * c0];
        int in0i = fin[2 * c0 + 1];
        int in1r = fin[2 * c1];
        int in1i = fin[2 * c1 + 1];
        int in2r = fin[2 * c2];
        int in2i = fin[2 * c2 + 1];
        int in3r = fin[2 * c3];
        int in3i = fin[2 * c3 + 1];

        if (scaledFlag == 1) {
          in0r /= 4;
          in0i /= 4;
          in1r /= 4;
          in1i /= 4;
          in2r /= 4;
          in2i /= 4;
          in3r /= 4;
          in3i /= 4;
        }

        int s0r = in0r + in2r;
        int s0i = in0i + in2i;
        int s1r = in0r - in2r;
        int s1i = in0i - in2i;
        int s2r = in1r + in3r;
        int s2i = in1i + in3i;
        int s3r = in1r - in3r;
        int s3i = in1i - in3i;

        int base = 2 * fout1;
        fout[base] = s0r + s2r;
        fout[base + 1] = s0i + s2i;
        fout[base + 2] = s1r - s3i;
        fout[base + 3] = s1i + s3r;
        fout[base + 4] = s0r - s2r;
        fout[base + 5] = s0i - s2i;
        fout[base + 6] = s1r + s3i;
        fout[base + 7] = s1i - s3r;

        fout1 += 4;
        fin1++;
      }
      n = fstride;
      stageCount--;
      fstride >>= 2;
      int[] ftmp = buffer;
      buffer = fout;
      fout = ftmp;
    } else if (n == 2) {
      // C :736-752 — length 2.
      int in0r = fin[0];
      int in0i = fin[1];
      int in1r = fin[2];
      int in1i = fin[3];
      if (scaledFlag == 1) {
        in0r /= 2;
        in0i /= 2;
        in1r /= 2;
        in1i /= 2;
      }
      fout[0] = in0r + in1r;
      fout[1] = in0i + in1i;
      fout[2] = in0r - in1r;
      fout[3] = in0i - in1i;
      return;
    } else {
      // C :753-757 — length 1.
      fout[0] = fin[0];
      fout[1] = fin[1];
      return;
    }

    // C :760-860 — middle stages.
    for (; stageCount > 1; stageCount--) {
      fin1 = 0;
      for (int fCount = 0; fCount < fstride; fCount++) {
        fout1 = (fCount * mstride) << 2;
        int tw1 = tw;
        for (int mCount = mstride; mCount != 0; mCount--) {
          radix4TwiddleStageInverse(
              fout, fout1, mstride, buffer, fin1, n, twiddles, tw1, mstride, scaledFlag);
          tw1++;
          fin1++;
          fout1++;
        }
      }
      tw += mstride * 3;
      mstride <<= 2;
      int[] ftmp = buffer;
      buffer = fout;
      fout = ftmp;
      fstride >>= 2;
    }

    // C :862-957 — last stage (writes to foutLs with output stride n).
    if (stageCount != 0) {
      fin1 = 0;
      fout1 = 0;
      for (int fCount = 0; fCount < fstride; fCount++) {
        int tw1 = tw;
        for (int mCount = mstride; mCount != 0; mCount--) {
          radix4TwiddleStageInverse(
              foutLs, fout1, n, buffer, fin1, n, twiddles, tw1, mstride, scaledFlag);
          tw1++;
          fin1++;
          fout1++;
        }
      }
    }
  }

  private static void radix4TwiddleStageInverse(
      int[] out,
      int outIdx,
      int outStride,
      int[] in,
      int inIdx,
      int inStride,
      int[] twiddles,
      int twIdx,
      int twStride,
      int scaledFlag) {
    int tw0r = twiddles[2 * twIdx];
    int tw0i = twiddles[2 * twIdx + 1];
    int tw1r = twiddles[2 * (twIdx + twStride)];
    int tw1i = twiddles[2 * (twIdx + twStride) + 1];
    int tw2r = twiddles[2 * (twIdx + 2 * twStride)];
    int tw2i = twiddles[2 * (twIdx + 2 * twStride) + 1];

    int i0 = 2 * inIdx;
    int i1 = 2 * (inIdx + inStride);
    int i2 = 2 * (inIdx + 2 * inStride);
    int i3 = 2 * (inIdx + 3 * inStride);

    int in0r = in[i0];
    int in0i = in[i0 + 1];
    int in1r = in[i1];
    int in1i = in[i1 + 1];
    int in2r = in[i2];
    int in2i = in[i2 + 1];
    int in3r = in[i3];
    int in3i = in[i3 + 1];

    if (scaledFlag == 1) {
      in0r /= 4;
      in0i /= 4;
      in1r /= 4;
      in1i /= 4;
      in2r /= 4;
      in2i /= 4;
      in3r /= 4;
      in3i /= 4;
    }

    int s0r = in0r;
    int s0i = in0i;
    // C :794-807, 898-911 — conjugate twiddle multiply (+ on real, - on imag).
    int s1r = (int) ((((long) in1r * tw0r) + ((long) in1i * tw0i)) >> 31);
    int s1i = (int) ((((long) in1i * tw0r) - ((long) in1r * tw0i)) >> 31);
    int s2r = (int) ((((long) in2r * tw1r) + ((long) in2i * tw1i)) >> 31);
    int s2i = (int) ((((long) in2i * tw1r) - ((long) in2r * tw1i)) >> 31);
    int s3r = (int) ((((long) in3r * tw2r) + ((long) in3i * tw2i)) >> 31);
    int s3i = (int) ((((long) in3i * tw2r) - ((long) in3r * tw2i)) >> 31);

    int s4r = s0r + s2r;
    int s4i = s0i + s2i;
    int s5r = s0r - s2r;
    int s5i = s0i - s2i;
    int s6r = s1r + s3r;
    int s6i = s1i + s3i;
    int s7r = s1r - s3r;
    int s7i = s1i - s3i;

    int o0 = 2 * outIdx;
    int o1 = 2 * (outIdx + outStride);
    int o2 = 2 * (outIdx + 2 * outStride);
    int o3 = 2 * (outIdx + 3 * outStride);

    out[o0] = s4r + s6r;
    out[o0 + 1] = s4i + s6i;
    out[o1] = s5r - s7i;
    out[o1 + 1] = s5i + s7r;
    out[o2] = s4r - s6r;
    out[o2 + 1] = s4i - s6i;
    out[o3] = s5r + s7i;
    out[o3 + 1] = s5i - s7r;
  }

  // C NE10_fft_int32.c:960-1004 — ne10_fft_split_r2c_1d_int32.
  private static void splitR2c1d(
      int[] dst, int[] src, int[] superTwiddles, int ncfft, int scaledFlag) {
    int tdcr = src[0];
    int tdci = src[1];
    if (scaledFlag != 0) {
      tdcr /= 2;
      tdci /= 2;
    }

    dst[0] = tdcr + tdci;
    dst[1] = 0;
    dst[2 * ncfft] = tdcr - tdci;
    dst[2 * ncfft + 1] = 0;

    for (int k = 1; k <= ncfft / 2; ++k) {
      int fpkr = src[2 * k];
      int fpki = src[2 * k + 1];
      int fpnkr = src[2 * (ncfft - k)];
      int fpnki = -src[2 * (ncfft - k) + 1];

      if (scaledFlag != 0) {
        fpkr /= 2;
        fpki /= 2;
        fpnkr /= 2;
        fpnki /= 2;
      }

      int f1kr = fpkr + fpnkr;
      int f1ki = fpki + fpnki;
      int f2kr = fpkr - fpnkr;
      int f2ki = fpki - fpnki;

      int stwr = superTwiddles[2 * (k - 1)];
      int stwi = superTwiddles[2 * (k - 1) + 1];

      // C :996-997 — each product is >> 32 (truncating to int32) before subtract/add, then << 1.
      int twr = (((int) (((long) f2kr * stwr) >> 32)) - ((int) (((long) f2ki * stwi) >> 32))) << 1;
      int twi = (((int) (((long) f2kr * stwi) >> 32)) + ((int) (((long) f2ki * stwr) >> 32))) << 1;

      dst[2 * k] = (f1kr + twr) >> 1;
      dst[2 * k + 1] = (f1ki + twi) >> 1;
      dst[2 * (ncfft - k)] = (f1kr - twr) >> 1;
      dst[2 * (ncfft - k) + 1] = (twi - f1ki) >> 1;
    }
  }

  // C NE10_fft_int32.c:1006-1049 — ne10_fft_split_c2r_1d_int32.
  private static void splitC2r1d(
      int[] dst, int[] src, int[] superTwiddles, int ncfft, int scaledFlag) {
    dst[0] = src[0] + src[2 * ncfft];
    dst[1] = src[0] - src[2 * ncfft];
    if (scaledFlag != 0) {
      dst[0] /= 2;
      dst[1] /= 2;
    }

    for (int k = 1; k <= ncfft / 2; k++) {
      int fkr = src[2 * k];
      int fki = src[2 * k + 1];
      int fnkcr = src[2 * (ncfft - k)];
      int fnkci = -src[2 * (ncfft - k) + 1];

      if (scaledFlag != 0) {
        fkr /= 2;
        fki /= 2;
        fnkcr /= 2;
        fnkci /= 2;
      }

      int fekr = fkr + fnkcr;
      int feki = fki + fnkci;
      int tmpr = fkr - fnkcr;
      int tmpi = fki - fnkci;

      int stwr = superTwiddles[2 * (k - 1)];
      int stwi = superTwiddles[2 * (k - 1) + 1];

      // C :1040-1041 — each product is >> 32 (truncating to int32) before add/subtract, then << 1.
      int fokr = (((int) (((long) tmpr * stwr) >> 32)) + ((int) (((long) tmpi * stwi) >> 32))) << 1;
      int foki = (((int) (((long) tmpi * stwr) >> 32)) - ((int) (((long) tmpr * stwi) >> 32))) << 1;

      dst[2 * k] = fekr + fokr;
      dst[2 * k + 1] = feki + foki;
      dst[2 * (ncfft - k)] = fekr - fokr;
      dst[2 * (ncfft - k) + 1] = foki - feki;
    }
  }
}
