package org.chuck.deluge.firmware.dsp.filter;

import static org.chuck.deluge.firmware.util.Q31.*;

public class BasicFilterComponent {
  public int memory = 0;

  // Faithful port of ladder_components.h: moveability (= tan(f)/(1+tan(f))) is used directly with NO
  // cap. The previous Java `Math.min(1073741823, moveability)` clamp halved the coefficient at high
  // cutoffs (forcing g<=0.5), capping the filter's open cutoff at ~6 kHz instead of ~20 kHz and
  // muffling every bright/open patch.
  public int doFilter(int input, int moveability) {
    int diff = subSaturate(input, memory);
    int a = multiply_32x32_rshift32_rounded(diff, moveability) << 1;
    int b = a + memory;
    memory = b + a;
    return b;
  }

  public int doAPF(int input, int moveability) {
    int diff = subSaturate(input, memory);
    int a = multiply_32x32_rshift32_rounded(diff, moveability) << 1;
    int b = a + memory;
    memory = a + b;
    return b * 2 - input;
  }

  public void affectFilter(int input, int moveability) {
    int diff = subSaturate(input, memory);
    memory += multiply_32x32_rshift32_rounded(diff, moveability) << 2;
  }

  public void reset() {
    memory = 0;
  }

  public int getFeedbackOutput(int feedbackAmount) {
    return multiply_32x32_rshift32_rounded(memory, feedbackAmount) << 2;
  }

  public int getFeedbackOutputWithoutLshift(int feedbackAmount) {
    return multiply_32x32_rshift32_rounded(memory, feedbackAmount);
  }
}
