package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Encapsulates the multisample key zone configurations for both oscillators. */
public class KeyZoneConfig {
  private final List<KeyZone> osc1Zones = new ArrayList<>();
  private final List<KeyZone> osc2Zones = new ArrayList<>();

  public List<KeyZone> getOsc1Zones() {
    return osc1Zones;
  }

  public List<KeyZone> getOsc2Zones() {
    return osc2Zones;
  }

  public void copyFrom(KeyZoneConfig other) {
    this.osc1Zones.clear();
    this.osc1Zones.addAll(other.osc1Zones);
    this.osc2Zones.clear();
    this.osc2Zones.addAll(other.osc2Zones);
  }
}
