package org.chuck.deluge.model;

/** Represents a modulation routing (source -> destination) with an amount. */
public record PatchCable(String source, String destination, float amount) {

  /**
   * Scales the linear XML patch amount into a usable DSP coefficient. Destinations like PITCH
   * require exponential (quadratic) scaling.
   */
  public static float applyScaling(String dest, float rawAmount) {
    if ("PITCH".equals(dest) || "OSC1_PITCH".equals(dest) || "OSC2_PITCH".equals(dest)) {
      // Quadratic scaling preserving sign
      return Math.signum(rawAmount) * (rawAmount * rawAmount);
    }
    // Linear scaling for non-pitch destinations
    return rawAmount;
  }
}
