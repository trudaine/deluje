package org.chuck.deluge.model;

/** Represents an LFO configuration. Synth tracks have 4 of these. */
public record LfoModel(
    float rateHz, LfoType waveform, float depth, String target, boolean isLocal, int syncLevel) {

  public static LfoModel defaultConfig(boolean isLocal) {
    return new LfoModel(1.0f, LfoType.SINE, 1.0f, "NONE", isLocal, 0);
  }
}
