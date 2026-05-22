package org.chuck.deluge.firmware.dsp.filter;

import static org.chuck.deluge.firmware.util.Q31.*;

public class BasicFilterComponent {
  public int memory = 0;

  public int doFilter(int input, int moveability) {
    int capped = Math.min(1073741823, moveability); // Guarantee k <= 1.0!
    int diff = subSaturate(input, memory);
    int a = multiply_32x32_rshift32_rounded(diff, capped) << 1;
    int b = a + memory;
    memory = b + a;
    return b;
  }

  public int doAPF(int input, int moveability) {
    int capped = Math.min(1073741823, moveability); // Guarantee k <= 1.0!
    int diff = subSaturate(input, memory);
    int a = multiply_32x32_rshift32_rounded(diff, capped) << 1;
    int b = a + memory;
    memory = a + b;
    return b * 2 - input;
  }

  public void affectFilter(int input, int moveability) {
    int capped = Math.min(536870911, moveability); // Guarantee k <= 1.0 with << 2!
    int diff = subSaturate(input, memory);
    memory += multiply_32x32_rshift32_rounded(diff, capped) << 2;
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
