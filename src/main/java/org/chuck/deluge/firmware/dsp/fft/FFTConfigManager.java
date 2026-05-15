package org.chuck.deluge.firmware.dsp.fft;

import org.chuck.deluge.firmware.util.LookupTables;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of FFTConfigManager from the firmware.
 * Manages FFT configurations for spectral processing with bit-accurate fixed-point math.
 */
public class FFTConfigManager {
  public static class FFTConfig {
    public final int magnitude;
    public final int size;

    public FFTConfig(int magnitude) {
      this.magnitude = magnitude;
      this.size = 1 << magnitude;
    }
    
    /** 
     * Performs a bit-accurate fixed-point forward FFT.
     * Replicates the behavior of the hardware's NE10_INT32 R2C FFT logic.
     */
    public void forward(int[] real, int[] imag) {
        int n = size;
        
        // 1. Bit-reversal permutation
        int j = 0;
        for (int i = 0; i < n; i++) {
            if (i < j) {
                int tr = real[i]; real[i] = real[j]; real[j] = tr;
                int ti = imag[i]; imag[i] = imag[j]; imag[j] = ti;
            }
            int m = n >> 1;
            while (m >= 1 && j >= m) {
                j -= m;
                m >>= 1;
            }
            j += m;
        }

        // 2. Radix-2 Stages with Bit-Accurate Fixed-Point Trig
        for (int stage = 1; stage <= magnitude; stage++) {
            int m = 1 << stage;
            int halfM = m >> 1;
            int phaseInc = (int)(0xFFFFFFFFL / m);
            
            for (int k = 0; k < n; k += m) {
                int phase = 0;
                for (int i = 0; i < halfM; i++) {
                    // Get bit-accurate cos/sin from firmware lookup tables
                    // cos(theta) = sin(theta + PI/2)
                    int cos = LookupTables.sinLookup(phase + 0x400000); 
                    int sin = -LookupTables.sinLookup(phase); // forward FFT
                    
                    int tr = (int)(((long)real[k + i + halfM] * cos - (long)imag[k + i + halfM] * sin) >> 31);
                    int ti = (int)(((long)real[k + i + halfM] * sin + (long)imag[k + i + halfM] * cos) >> 31);
                    
                    real[k + i + halfM] = real[k + i] - tr;
                    imag[k + i + halfM] = imag[k + i] - ti;
                    real[k + i] = real[k + i] + tr;
                    imag[k + i] = imag[k + i] + ti;
                    
                    phase += phaseInc;
                }
            }
        }
    }
  }

  public static FFTConfig getConfig(int magnitude) {
    return new FFTConfig(magnitude);
  }
}
