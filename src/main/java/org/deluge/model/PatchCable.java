package org.deluge.model;

import java.util.Collections;
import java.util.List;

/**
 * Represents a modulation routing (source -> destination) with an amount, polarity, and nested
 * depth control cables.
 */
public record PatchCable(
    String source,
    String destination,
    float amount,
    Polarity polarity,
    List<PatchCable> depthControlledBy) {

  public enum Polarity {
    UNIPOLAR,
    BIPOLAR
  }

  /** Creates a default cable with empty depth control list. */
  public PatchCable(String source, String destination, float amount, Polarity polarity) {
    this(source, destination, amount, polarity, Collections.emptyList());
  }

  /** Creates a default UNIPOLAR cable at 0 amount for migration convenience. */
  public PatchCable(String source, String destination, float amount) {
    this(source, destination, amount, Polarity.UNIPOLAR, Collections.emptyList());
  }

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
