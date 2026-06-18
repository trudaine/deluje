package org.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Represents a section (A-Z) in Song mode, containing a group of pattern references. */
public class SongSection {
  private final String id; // e.g. "A", "B", "C"
  private final List<String> patternIds = new ArrayList<>();
  private int numRepeats = 0;
  private int loopToSection = -1;
  private int linkToSection = -1;

  public SongSection(String id) {
    this.id = id;
  }

  public String getId() {
    return id;
  }

  public int getNumRepeats() {
    return numRepeats;
  }

  public void setNumRepeats(int v) {
    this.numRepeats = v;
  }

  public int getLoopToSection() {
    return loopToSection;
  }

  public void setLoopToSection(int v) {
    this.loopToSection = v;
  }

  public int getLinkToSection() {
    return linkToSection;
  }

  public void setLinkToSection(int v) {
    this.linkToSection = v;
  }

  public List<String> getPatternIds() {
    return patternIds;
  }

  public void addPatternId(String patternId) {
    this.patternIds.add(patternId);
  }
}
