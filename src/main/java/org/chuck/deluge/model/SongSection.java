package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Represents a section (A-Z) in Song mode, containing a group of pattern references. */
public class SongSection {
  private final String id; // e.g. "A", "B", "C"
  private final List<String> patternIds = new ArrayList<>();

  public SongSection(String id) {
    this.id = id;
  }

  public String getId() {
    return id;
  }

  public List<String> getPatternIds() {
    return patternIds;
  }

  public void addPatternId(String patternId) {
    this.patternIds.add(patternId);
  }
}
