package org.deluge.model;

/** Represents a single ADSR envelope definition. Synth tracks have 4 of these. */
public record EnvelopeModel(
    float attack, float decay, float sustain, float release, String target, float amount) {
  public EnvelopeModel {
    attack = Math.max(0.0f, attack);
    decay = Math.max(0.0f, decay);
    sustain = Math.max(0.0f, Math.min(1.0f, sustain));
    release = Math.max(0.0f, release);
  }

  public static EnvelopeModel defaultConfig() {
    return new EnvelopeModel(0.01f, 0.1f, 0.7f, 0.2f, "NONE", 0.0f);
  }
}
