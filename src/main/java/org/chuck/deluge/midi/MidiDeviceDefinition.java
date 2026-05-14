package org.chuck.deluge.midi;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** XML-serializable model class representing one MIDI device definition. */
public class MidiDeviceDefinition {
  private String id;
  private String name;
  private String manufacturer = "";
  private String description = "";
  private final List<CcMapping> ccMappings = new ArrayList<>();

  /** A single CC-to-parameter mapping. */
  public static record CcMapping(int cc, String paramName, String displayName) {
    public CcMapping {
      if (cc < 0 || cc > 127) throw new IllegalArgumentException("CC out of range: " + cc);
      Objects.requireNonNull(paramName, "paramName must not be null");
      if (paramName.isBlank()) throw new IllegalArgumentException("paramName must not be blank");
      if (displayName == null || displayName.isBlank()) displayName = paramName;
    }
  }

  public MidiDeviceDefinition() {}

  public MidiDeviceDefinition(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getManufacturer() {
    return manufacturer;
  }

  public void setManufacturer(String m) {
    this.manufacturer = m != null ? m : "";
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String d) {
    this.description = d != null ? d : "";
  }

  public List<CcMapping> getCcMappings() {
    return ccMappings;
  }

  public void addCcMapping(CcMapping mapping) {
    ccMappings.add(mapping);
  }

  public void removeCcMapping(int cc) {
    ccMappings.removeIf(m -> m.cc() == cc);
  }

  /** Look up a mapping by CC number. Returns null if not found. */
  public CcMapping findMapping(int cc) {
    for (CcMapping m : ccMappings) {
      if (m.cc() == cc) return m;
    }
    return null;
  }
}
