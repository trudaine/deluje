package org.deluge.firmware2;

/**
 * Verbatim port of the Deluge {@code PhaseIncrementFineTuner} ({@code
 * util/phase_increment_fine_tuner.cpp/.h}). Applies a cents-detune to a phase increment via the
 * {@code centAdjustTableSmall} lookup. {@code detuneScaled} is {@code cents * 42949672}.
 */
public class PhaseIncrementFineTuner {
  private volatile int multiplier;

  public PhaseIncrementFineTuner() {
    setNoDetune();
  }

  // C: multiplier = interpolateTable(2147483648u + detuneScaled, 32, centAdjustTableSmall);
  // The table has 257 entries (2^8+1) → numBitsInTableSize = 8. The input is treated as uint32.
  public void setup(int detuneScaled) {
    multiplier =
        Functions.interpolateTable(
            Integer.MIN_VALUE + detuneScaled, 32, LookupTables.centAdjustTableSmall, 8);
  }

  // C: multiplier = 1073741824;
  public void setNoDetune() {
    multiplier = 1073741824;
  }

  // C: return multiply_32x32_rshift32(phaseIncrement, multiplier) << 2;
  public int detune(int phaseIncrement) {
    return Functions.multiply_32x32_rshift32(phaseIncrement, multiplier) << 2;
  }
}
