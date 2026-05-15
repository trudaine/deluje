package org.chuck.deluge.firmware.dsp.dx;

import org.chuck.deluge.firmware.util.LookupTables;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of FmCore from fm_core.cpp.
 * Implements 100% bit-accurate 6-operator FM synthesis with 32 algorithms.
 */
public class FmCore {
  public static final int DX_MAX_N = 132;
  public static final int kGainLevelThresh = 1120;

  public static class FmOpParams {
    public int phase;
    public int freq;
    public int gain_out;
    public int level_in;
  }

  public static class FmAlgorithm {
    public final int[] ops = new int[6];

    public FmAlgorithm(int op0, int op1, int op2, int op3, int op4, int op5) {
      ops[0] = op0;
      ops[1] = op1;
      ops[2] = op2;
      ops[3] = op3;
      ops[4] = op4;
      ops[5] = op5;
    }
  }

  public static final FmAlgorithm[] algorithms = {
    new FmAlgorithm(0xc1, 0x11, 0x11, 0x14, 0x01, 0x14), // 1
    new FmAlgorithm(0x01, 0x11, 0x11, 0x14, 0xc1, 0x14), // 2
    new FmAlgorithm(0xc1, 0x11, 0x14, 0x01, 0x11, 0x14), // 3
    new FmAlgorithm(0xc1, 0x11, 0x94, 0x01, 0x11, 0x14), // 4
    new FmAlgorithm(0xc1, 0x14, 0x01, 0x14, 0x01, 0x14), // 5
    new FmAlgorithm(0xc1, 0x94, 0x01, 0x14, 0x01, 0x14), // 6
    new FmAlgorithm(0xc1, 0x11, 0x05, 0x14, 0x01, 0x14), // 7
    new FmAlgorithm(0x01, 0x11, 0xc5, 0x14, 0x01, 0x14), // 8
    new FmAlgorithm(0x01, 0x11, 0x05, 0x14, 0xc1, 0x14), // 9
    new FmAlgorithm(0x01, 0x05, 0x14, 0xc1, 0x11, 0x14), // 10
    new FmAlgorithm(0xc1, 0x05, 0x14, 0x01, 0x11, 0x14), // 11
    new FmAlgorithm(0x01, 0x05, 0x05, 0x14, 0xc1, 0x14), // 12
    new FmAlgorithm(0xc1, 0x05, 0x05, 0x14, 0x01, 0x14), // 13
    new FmAlgorithm(0xc1, 0x05, 0x11, 0x14, 0x01, 0x14), // 14
    new FmAlgorithm(0x01, 0x05, 0x11, 0x14, 0xc1, 0x14), // 15
    new FmAlgorithm(0xc1, 0x11, 0x02, 0x25, 0x05, 0x14), // 16
    new FmAlgorithm(0x01, 0x11, 0x02, 0x25, 0xc5, 0x14), // 17
    new FmAlgorithm(0x01, 0x11, 0x11, 0xc5, 0x05, 0x14), // 18
    new FmAlgorithm(0xc1, 0x14, 0x14, 0x01, 0x11, 0x14), // 19
    new FmAlgorithm(0x01, 0x05, 0x14, 0xc1, 0x14, 0x14), // 20
    new FmAlgorithm(0x01, 0x14, 0x14, 0xc1, 0x14, 0x14), // 21
    new FmAlgorithm(0xc1, 0x14, 0x14, 0x14, 0x01, 0x14), // 22
    new FmAlgorithm(0xc1, 0x14, 0x14, 0x01, 0x14, 0x04), // 23
    new FmAlgorithm(0xc1, 0x14, 0x14, 0x14, 0x04, 0x04), // 24
    new FmAlgorithm(0xc1, 0x14, 0x14, 0x04, 0x04, 0x04), // 25
    new FmAlgorithm(0xc1, 0x05, 0x14, 0x01, 0x14, 0x04), // 26
    new FmAlgorithm(0x01, 0x05, 0x14, 0xc1, 0x14, 0x04), // 27
    new FmAlgorithm(0x04, 0xc1, 0x11, 0x14, 0x01, 0x14), // 28
    new FmAlgorithm(0xc1, 0x14, 0x01, 0x14, 0x04, 0x04), // 29
    new FmAlgorithm(0x04, 0xc1, 0x11, 0x14, 0x04, 0x04), // 30
    new FmAlgorithm(0xc1, 0x14, 0x04, 0x04, 0x04, 0x04), // 31
    new FmAlgorithm(0xc4, 0x04, 0x04, 0x04, 0x04, 0x04), // 32
  };

  private final int[][] buf = new int[2][DX_MAX_N];

  public void render(
      int[] output, int n, FmOpParams[] params, int algorithm, int[] fb_buf, int feedback_shift) {
    if (algorithm < 0 || algorithm >= 32) return;
    FmAlgorithm alg = algorithms[algorithm];
    int inv_n = (1 << 30) / n;
    boolean[] has_contents = {true, false, false};

    for (int op = 0; op < 6; op++) {
      int flags = alg.ops[op];
      boolean add = (flags & 0x04) != 0; 
      FmOpParams param = params[op];
      int inbus = (flags >> 4) & 3;
      int outbus = flags & 3;

      int[] outptr = (outbus == 0) ? output : buf[outbus - 1];
      int gain1 = param.gain_out;
      // Bit-accurate Exp2 lookup
      int gain2 = LookupTables.exp2Lookup(param.level_in - (14 << 24));
      param.gain_out = gain2;
      int dgain = (int) (((long) (gain2 - gain1) * inv_n) >> 30);

      if (gain1 >= kGainLevelThresh || gain2 >= kGainLevelThresh) {
        if (!has_contents[outbus]) add = false;

        int[] inptr = (inbus == 0) ? null : buf[inbus - 1];
        
        if (inptr == null || !has_contents[inbus]) {
          if ((flags & 0xc0) == 0xc0 && feedback_shift < 16) {
             FmOpKernelVector.compute_fb(outptr, n, inptr, param.phase, param.freq, gain1, gain2, dgain, fb_buf, feedback_shift, add);
          } else {
             FmOpKernelVector.compute(outptr, n, null, param.phase, param.freq, gain1, dgain, add);
          }
        } else {
           FmOpKernelVector.compute(outptr, n, inptr, param.phase, param.freq, gain1, dgain, add);
        }
        has_contents[outbus] = true;
      } else if (!add) {
        has_contents[outbus] = false;
      }
      param.phase += param.freq * n;
    }
  }
}
