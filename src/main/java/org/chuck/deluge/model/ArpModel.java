package org.chuck.deluge.model;

/** Represents the arpeggiator configuration for a synth track. */
public record ArpModel(
    String mode, String octaveMode, int rhythmPattern, float rateHz, String syncType, float gate) {

  public ArpModel {
    rhythmPattern = Math.max(0, Math.min(50, rhythmPattern));
    gate = Math.max(0.0f, Math.min(1.0f, gate));
  }

  public static ArpModel defaultConfig() {
    return new ArpModel("OFF", "UP", 0, 10.0f, "EVEN", 0.5f);
  }
}
