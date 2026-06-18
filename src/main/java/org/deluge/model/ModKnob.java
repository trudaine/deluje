package org.deluge.model;

/** Represents a mapping between an external parameter/macro and a modulation source. */
public record ModKnob(String param, String patchSource) {
  public static ModKnob empty() {
    return new ModKnob("NONE", "NONE");
  }
}
