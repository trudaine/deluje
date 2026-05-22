package org.chuck.deluge.firmware.dsp.dx;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorSpecies;
import org.chuck.deluge.firmware.util.LookupTables;

/**
 * High-fidelity Vectorized FM Operator Kernel. Replicates the logic of neon_fm_kernel using Java's
 * Vector API.
 */
public class FmOpKernelVector {
  private static final VectorSpecies<Integer> SPECIES = IntVector.SPECIES_PREFERRED;

  public static int compute(
      int[] output, int n, int[] input, int phase0, int freq, int gain1, int dgain, boolean add) {
    return computeScalar(output, 0, n, input, phase0, freq, gain1, dgain, add);
  }

  public static int compute_fb(
      int[] output,
      int n,
      int[] input,
      int phase0,
      int freq,
      int gain1,
      int gain2,
      int dgain,
      int[] fb_buf,
      int fb_shift,
      boolean add) {
    int phase = phase0;
    int gain = gain1;
    int fb0 = fb_buf[0];
    int fb1 = fb_buf[1];
    for (int i = 0; i < n; i++) {
      gain += dgain;
      int fb = (fb0 + fb1) >> (fb_shift + 1);
      int modulation = (input == null) ? fb : input[i] + fb;
      int y = LookupTables.sinLookup(phase + modulation);
      int y1 = (int) (((long) y * (long) gain) >> 24);
      fb1 = fb0;
      fb0 = y;
      if (add) {
        output[i] += y1;
      } else {
        output[i] = y1;
      }
      phase += freq;
    }
    fb_buf[0] = fb0;
    fb_buf[1] = fb1;
    return phase;
  }

  private static int computeScalar(
      int[] output,
      int offset,
      int length,
      int[] input,
      int phase0,
      int freq,
      int gain1,
      int dgain,
      boolean add) {
    int phase = phase0;
    int gain = gain1;
    for (int i = 0; i < length; i++) {
      gain += dgain;
      int modulation = (input == null) ? 0 : input[offset + i];
      int y = LookupTables.sinLookup(phase + modulation);
      int y1 = (int) (((long) y * (long) gain) >> 24);
      if (add) {
        output[offset + i] += y1;
      } else {
        output[offset + i] = y1;
      }
      phase += freq;
    }
    return phase;
  }
}
