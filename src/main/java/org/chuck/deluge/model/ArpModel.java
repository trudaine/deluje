package org.chuck.deluge.model;

/** Represents the arpeggiator configuration for a synth track. */
public record ArpModel(boolean active, String mode, float rate, int octaves, float gate) {

  public static ArpModel defaultConfig() {
    return new ArpModel(false, "UP", 1.0f, 1, 0.5f);
  }
}
