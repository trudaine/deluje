package org.chuck.deluge.firmware2;

/**
 * Verbatim port of the Deluge DX7 "Mark I" engine ({@code dsp/dx/EngineMkI.cpp} / {@code
 * EngineMkI.h}).
 *
 * <p>An alternate FM engine to the modern MSFA core ({@link FmCore}). The fundamental difference:
 * the operator gain is the ENV (Q14, {@code ENV_MAX = 1<<14}) applied in the LOG domain via {@link
 * #mkiSin} (a Yamaha-style log/exp sine), not the linear Q24 gain that {@code FmCore}/MSFA uses.
 * {@code mkiSin} adds the ENV to the log-sine then exponentiates. Used for vintage mode and the
 * algo-4/6 feedback loops.
 *
 * <p>In C, {@code EngineMkI : FmCore} overrides {@code render()} (virtual dispatch via {@code
 * DxPatch.core}). Here it is a static class; {@code Dx7Voice} selects {@code EngineMkI.render} vs
 * {@code FmCore.render}. Shares {@code FmCore.ALGORITHMS}, {@code FmOpParams}, {@code OUT_BUS_ADD},
 * {@code DX_MAX_N}.
 */
public final class EngineMkI {
  private EngineMkI() {}

  // EngineMkI.cpp:35-46
  private static final int NEGATIVE_BIT = 0x8000;
  private static final int ENV_BITDEPTH = 14;
  private static final int ENV_MAX = 1 << ENV_BITDEPTH;

  private static final int SINLOG_BITDEPTH = 10;
  private static final int SINLOG_TABLESIZE = 1 << SINLOG_BITDEPTH;
  private static final int[] sinLogTable = new int[SINLOG_TABLESIZE]; // uint16

  private static final int SINEXP_BITDEPTH = 10;
  private static final int SINEXP_TABLESIZE = 1 << SINEXP_BITDEPTH;
  private static final int[] sinExpTable = new int[SINEXP_TABLESIZE]; // uint16

  static {
    // EngineMkI::EngineMkI() (EngineMkI.cpp:64-78) — build the log/exp sine tables.
    double bitReso = SINLOG_TABLESIZE;
    for (int i = 0; i < SINLOG_TABLESIZE; i++) {
      double x1 = Math.sin(((0.5 + i) / bitReso) * Math.PI / 2.0);
      sinLogTable[i] = ((int) Math.round(-1024.0 * (Math.log(x1) / Math.log(2.0)))) & 0xFFFF;
    }
    bitReso = SINEXP_TABLESIZE;
    for (int i = 0; i < SINEXP_TABLESIZE; i++) {
      double x1 = (Math.pow(2, (double) i / bitReso) - 1) * 4096;
      sinExpTable[i] = ((int) Math.round(x1)) & 0xFFFF;
    }
  }

  // sinLog (EngineMkI.cpp:49-62). phi treated as uint16.
  private static int sinLog(int phi) {
    final int FILTER = SINLOG_TABLESIZE - 1;
    int index = phi & FILTER;
    switch (phi & (SINLOG_TABLESIZE * 3)) {
      case 0:
        return sinLogTable[index];
      case SINLOG_TABLESIZE:
        return sinLogTable[index ^ FILTER];
      case SINLOG_TABLESIZE * 2:
        return sinLogTable[index] | NEGATIVE_BIT;
      default:
        return sinLogTable[index ^ FILTER] | NEGATIVE_BIT;
    }
  }

  // mkiSin (EngineMkI.cpp:80-97). phase Q?, env = uint16 (gain in log domain).
  static int mkiSin(int phase, int env) {
    int expVal = (sinLog((phase >> (22 - SINLOG_BITDEPTH)) & 0xFFFF) + (env & 0xFFFF)) & 0xFFFF;
    boolean isSigned = (expVal & NEGATIVE_BIT) != 0;
    expVal &= ~NEGATIVE_BIT;
    int result = 4096 + sinExpTable[(expVal & 0x3FF) ^ 0x3FF];
    result >>= (expVal >> 10); // exp
    if (isSigned) {
      return (-result - 1) << 13;
    }
    return result << 13;
  }

  // EngineMkI::compute (EngineMkI.cpp:99-111)
  private static void compute(
      int[] output,
      int off,
      int n,
      int[] input,
      int inOff,
      int phase0,
      int freq,
      int gain1,
      int gain2,
      int dgain,
      boolean add) {
    int gain = gain1;
    int phase = phase0;
    for (int i = 0; i < n; i++) {
      gain += dgain;
      int y = mkiSin(phase + input[inOff + i], gain);
      output[off + i] = y + (add ? output[off + i] : 0);
      phase += freq;
    }
  }

  // EngineMkI::compute_pure (EngineMkI.cpp:113-125)
  private static void computePure(
      int[] output,
      int off,
      int n,
      int phase0,
      int freq,
      int gain1,
      int gain2,
      int dgain,
      boolean add) {
    int gain = gain1;
    int phase = phase0;
    for (int i = 0; i < n; i++) {
      gain += dgain;
      int y = mkiSin(phase, gain);
      output[off + i] = y + (add ? output[off + i] : 0);
      phase += freq;
    }
  }

  // EngineMkI::compute_fb (EngineMkI.cpp:127-147)
  private static void computeFb(
      int[] output,
      int off,
      int n,
      int phase0,
      int freq,
      int gain1,
      int gain2,
      int dgain,
      int[] fbBuf,
      int fbShift,
      boolean add) {
    int gain = gain1;
    int phase = phase0;
    int y0 = fbBuf[0];
    int y = fbBuf[1];
    for (int i = 0; i < n; i++) {
      gain += dgain;
      int scaledFb = (y0 + y) >> (fbShift + 1);
      y0 = y;
      y = mkiSin(phase + scaledFb, gain);
      output[off + i] = y + (add ? output[off + i] : 0);
      phase += freq;
    }
    fbBuf[0] = y0;
    fbBuf[1] = y;
  }

  // EngineMkI::compute_fb2 — ALGO 6, two-operator feedback (EngineMkI.cpp:149-186)
  private static void computeFb2(
      int[] output,
      int off,
      int n,
      FmCore.FmOpParams[] parms,
      int gain01,
      int gain02,
      int dgain0,
      int[] fbBuf,
      int fbShift) {
    int[] dgain = new int[2];
    int[] gain = new int[2];
    int[] phase = new int[2];
    int y0 = fbBuf[0];
    int y = fbBuf[1];

    phase[0] = parms[0].phase;
    phase[1] = parms[1].phase;

    parms[1].gain_out = (ENV_MAX - (parms[1].level_in >> (28 - ENV_BITDEPTH)));

    gain[0] = gain01;
    gain[1] = parms[1].gain_out == 0 ? (ENV_MAX - 1) : parms[1].gain_out;

    dgain[0] = dgain0;
    dgain[1] = (parms[1].gain_out - (parms[1].gain_out == 0 ? (ENV_MAX - 1) : parms[1].gain_out));

    for (int i = 0; i < n; i++) {
      int scaledFb = (y0 + y) >> (fbShift + 1);

      // op 0
      gain[0] += dgain[0];
      y0 = y;
      y = mkiSin(phase[0] + scaledFb, gain[0]);
      phase[0] += parms[0].freq;

      // op 1
      gain[1] += dgain[1];
      y = mkiSin(phase[1] + y, gain[1]);
      phase[1] += parms[1].freq;

      output[off + i] = y;
    }
    fbBuf[0] = y0;
    fbBuf[1] = y;
  }

  // EngineMkI::compute_fb3 — ALGO 4, three-operator feedback (EngineMkI.cpp:188-235)
  private static void computeFb3(
      int[] output,
      int off,
      int n,
      FmCore.FmOpParams[] parms,
      int gain01,
      int gain02,
      int dgain0,
      int[] fbBuf,
      int fbShift) {
    int[] dgain = new int[3];
    int[] gain = new int[3];
    int[] phase = new int[3];
    int y0 = fbBuf[0];
    int y = fbBuf[1];

    phase[0] = parms[0].phase;
    phase[1] = parms[1].phase;
    phase[2] = parms[2].phase;

    parms[1].gain_out = (ENV_MAX - (parms[1].level_in >> (28 - ENV_BITDEPTH)));
    parms[2].gain_out = (ENV_MAX - (parms[2].level_in >> (28 - ENV_BITDEPTH)));

    gain[0] = gain01;
    gain[1] = parms[1].gain_out == 0 ? (ENV_MAX - 1) : parms[1].gain_out;
    gain[2] = parms[2].gain_out == 0 ? (ENV_MAX - 1) : parms[2].gain_out;

    dgain[0] = dgain0;
    dgain[1] = (parms[1].gain_out - (parms[1].gain_out == 0 ? (ENV_MAX - 1) : parms[1].gain_out));
    dgain[2] = (parms[2].gain_out - (parms[2].gain_out == 0 ? (ENV_MAX - 1) : parms[2].gain_out));

    for (int i = 0; i < n; i++) {
      int scaledFb = (y0 + y) >> (fbShift + 1);

      // op 0
      gain[0] += dgain[0];
      y0 = y;
      y = mkiSin(phase[0] + scaledFb, gain[0]);
      phase[0] += parms[0].freq;

      // op 1
      gain[1] += dgain[1];
      y = mkiSin(phase[1] + y, gain[1]);
      phase[1] += parms[1].freq;

      // op 2
      gain[2] += dgain[2];
      y = mkiSin(phase[2] + y, gain[2]);
      phase[2] += parms[2].freq;

      output[off + i] = y;
    }
    fbBuf[0] = y0;
    fbBuf[1] = y;
  }

  // EngineMkI::render (EngineMkI.cpp:237-316)
  public static void render(
      int[] output,
      int n,
      FmCore.FmOpParams[] params,
      int algorithm,
      int[] fbBuf,
      int feedbackShift) {
    final int kLevelThresh = ENV_MAX - 100;
    int[] alg = FmCore.ALGORITHMS[algorithm].clone(); // FmAlgorithm copy (we may mutate ops[0])
    boolean[] hasContents = {true, false, false};
    boolean fbOn = feedbackShift < 16;
    final int invN = (1 << 30) / n;

    // Bus scratch buffers (FmCore buf_).
    int[][] buf = new int[2][n];

    switch (algorithm) {
      case 3:
      case 5:
        if (fbOn) alg[0] = 0xc4;
        break;
      default:
        break;
    }

    for (int op = 0; op < 6; op++) {
      int flags = alg[op];
      boolean add = (flags & FmCore.OUT_BUS_ADD) != 0;
      FmCore.FmOpParams param = params[op];
      int inbus = (flags >> 4) & 3;
      int outbus = flags & 3;
      int[] outptr = (outbus == 0) ? output : buf[outbus - 1];
      int outOff = 0; // output and buf both start at 0

      int gain1 = param.gain_out == 0 ? (ENV_MAX - 1) : param.gain_out;
      int gain2 = ENV_MAX - (param.level_in >> (28 - ENV_BITDEPTH));
      param.gain_out = gain2;
      int dgain = (int) (((long) (gain2 - gain1 + (n >> 1)) * (long) invN) >> 30);

      if (gain1 <= kLevelThresh || gain2 <= kLevelThresh) {

        if (!hasContents[outbus]) {
          add = false;
        }

        if (inbus == 0 || !hasContents[inbus]) {
          // PG's 'dirty' FB for 2 and 3 operators
          if ((flags & 0xc0) == 0xc0 && fbOn) {
            switch (algorithm) {
              case 3: // three-operator feedback, ALGO 4
                computeFb3(
                    outptr,
                    outOff,
                    n,
                    params,
                    gain1,
                    gain2,
                    dgain,
                    fbBuf,
                    Math.min(feedbackShift + 2, 16));
                params[1].phase += params[1].freq * n; // already processed op-5 - op-4
                params[2].phase += params[2].freq * n;
                op += 2; // ignore the 2 other operators
                break;
              case 5: // two-operator feedback, ALGO 6
                computeFb2(
                    outptr,
                    outOff,
                    n,
                    params,
                    gain1,
                    gain2,
                    dgain,
                    fbBuf,
                    Math.min(feedbackShift + 2, 16));
                params[1].phase += params[1].freq * n;
                op++; // ignore next operator
                break;
              case 31: // one-operator feedback, ALGO 32
                computeFb(
                    outptr,
                    outOff,
                    n,
                    param.phase,
                    param.freq,
                    gain1,
                    gain2,
                    dgain,
                    fbBuf,
                    Math.min(feedbackShift + 2, 16),
                    add);
                break;
              default: // one-operator feedback, normal
                computeFb(
                    outptr,
                    outOff,
                    n,
                    param.phase,
                    param.freq,
                    gain1,
                    gain2,
                    dgain,
                    fbBuf,
                    feedbackShift,
                    add);
                break;
            }
          } else {
            computePure(outptr, outOff, n, param.phase, param.freq, gain1, gain2, dgain, add);
          }
        } else {
          compute(
              outptr,
              outOff,
              n,
              buf[inbus - 1],
              0,
              param.phase,
              param.freq,
              gain1,
              gain2,
              dgain,
              add);
        }

        hasContents[outbus] = true;
      } else if (!add) {
        hasContents[outbus] = false;
      }
      param.phase += param.freq * n;
    }
  }
}
