package org.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code ladder_components.h} BasicFilterComponent. A one-pole
 * low-pass filter stage used as a building block in the ladder and SVF filters. {@code moveability
 * = tan(f)/(1+tan(f))} — the filter coefficient, range 0 to ~1.0.
 */
public class BasicFilterComponent {

  public int memory = 0;

  /** Reset to zero (called by constructor in firmware). */
  public BasicFilterComponent() {
    reset();
  }

  public int doFilter(int input, int moveability) {
    int a =
        Functions.lshiftAndSaturate(
            Functions.multiply_32x32_rshift32_rounded(input - memory, moveability), 1);
    int b = a + memory;
    memory = b + a;
    return b;
  }

  /** doAPF — one-pole all-pass filter. (ladder_components.h:33-37) */
  public int doAPF(int input, int moveability) {
    // q31_t a = multiply_32x32_rshift32_rounded(input - memory, moveability) << 1;
    int a =
        Functions.lshiftAndSaturate(
            Functions.multiply_32x32_rshift32_rounded(input - memory, moveability), 1);
    // q31_t b = a + memory;
    int b = a + memory;
    // memory = a + b;
    memory = a + b;
    // return b * 2 - input;
    return b * 2 - input;
  }

  /** affectFilter — feed a sample into the filter with no output. (ladder_components.h:39-40) */
  public void affectFilter(int input, int moveability) {
    // memory += multiply_32x32_rshift32_rounded(input - memory, moveability) << 2;
    memory =
        Functions.add_saturate(
            memory,
            Functions.lshiftAndSaturate(
                Functions.multiply_32x32_rshift32_rounded(input - memory, moveability), 2));
  }

  /** Reset the one-pole memory to zero. (ladder_components.h:42) */
  public void reset() {
    memory = 0;
  }

  /**
   * getFeedbackOutput — feedback with left-shift for level compensation.
   * (ladder_components.h:43-44)
   */
  public int getFeedbackOutput(int feedbackAmount) {
    // return multiply_32x32_rshift32_rounded(memory, feedbackAmount) << 2;
    return Functions.lshiftAndSaturate(
        Functions.multiply_32x32_rshift32_rounded(memory, feedbackAmount), 2);
  }

  /** getFeedbackOutputWithoutLshift — raw feedback. (ladder_components.h:46-47) */
  public int getFeedbackOutputWithoutLshift(int feedbackAmount) {
    // return multiply_32x32_rshift32_rounded(memory, feedbackAmount);
    return Functions.multiply_32x32_rshift32_rounded(memory, feedbackAmount);
  }
}
