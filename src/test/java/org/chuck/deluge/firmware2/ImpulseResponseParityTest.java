package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Parity test for the analog-delay IR FIR (dsp/convolution/impulse_response_processor.h, ported
 * verbatim as Delay.ImpulseResponseProcessor). Verifies the transposed-form implementation against
 * (a) the analytical impulse response and (b) an independent direct-form convolution using the same
 * fixed-point primitive — both follow directly from the C's structure.
 */
class ImpulseResponseParityTest {

  @Test
  void impulseYieldsTheIrTaps() {
    Delay.ImpulseResponseProcessor p = new Delay.ImpulseResponseProcessor();
    int impulse = 1 << 30;
    int[] io = new int[2];
    for (int n = 0; n < Delay.ImpulseResponseProcessor.IR_SIZE + 5; n++) {
      io[0] = (n == 0) ? impulse : 0;
      io[1] = (n == 0) ? impulse : 0;
      p.process(io, io);
      int expected =
          (n < Delay.ImpulseResponseProcessor.IR_SIZE)
              ? Functions.multiply_32x32_rshift32_rounded(
                  impulse, Delay.ImpulseResponseProcessor.ir[n])
              : 0;
      assertEquals(expected, io[0], "impulse response tap " + n + " (left)");
      assertEquals(expected, io[1], "impulse response tap " + n + " (right)");
    }
  }

  @Test
  void matchesDirectFormConvolutionOnRandomInput() {
    Delay.ImpulseResponseProcessor p = new Delay.ImpulseResponseProcessor();
    Random rnd = new Random(12345);
    int n = 512;
    int[] x = new int[n];
    for (int i = 0; i < n; i++) {
      x[i] = rnd.nextInt() >> 4; // headroom so the tap sums can't overflow differently
    }
    int[] io = new int[2];
    for (int i = 0; i < n; i++) {
      io[0] = x[i];
      io[1] = x[i];
      p.process(io, io);
      // Direct-form reference: y[i] = sum_k round32(x[i-k] * ir[k])
      long expected = 0;
      for (int k = 0; k < Delay.ImpulseResponseProcessor.IR_SIZE && k <= i; k++) {
        expected +=
            Functions.multiply_32x32_rshift32_rounded(
                x[i - k], Delay.ImpulseResponseProcessor.ir[k]);
      }
      assertEquals((int) expected, io[0], "convolution output at sample " + i);
      assertEquals((int) expected, io[1], "convolution output at sample " + i + " (right)");
    }
  }
}
