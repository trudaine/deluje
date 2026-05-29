package org.chuck.deluge.model.tuning;

/**
 * Model class representing a parsed Scala Keyboard Mapping (.kbm) file structure. Standardizes
 * reference keys offsets, physical frequency nodes, and custom keyboard maps lists.
 */
public class ScalaKeyboardMap {
  private final String name;
  private int mapSize = 0;
  private int firstMidiNote = 0;
  private int lastMidiNote = 127;
  private int middleMidiNote = 60;
  private double referenceFrequency = 261.625565;
  private int octaveDegree = 12;
  private int[] keyMapping = null; // null means default linear mapping

  public ScalaKeyboardMap(String name) {
    this.name = name;
  }

  public String getName() {
    return name;
  }

  public int getMapSize() {
    return mapSize;
  }

  public void setMapSize(int mapSize) {
    this.mapSize = mapSize;
  }

  public int getFirstMidiNote() {
    return firstMidiNote;
  }

  public void setFirstMidiNote(int note) {
    this.firstMidiNote = note;
  }

  public int getLastMidiNote() {
    return lastMidiNote;
  }

  public void setLastMidiNote(int note) {
    this.lastMidiNote = note;
  }

  public int getMiddleMidiNote() {
    return middleMidiNote;
  }

  public void setMiddleMidiNote(int note) {
    this.middleMidiNote = note;
  }

  public double getReferenceFrequency() {
    return referenceFrequency;
  }

  public void setReferenceFrequency(double freq) {
    this.referenceFrequency = freq;
  }

  public int getOctaveDegree() {
    return octaveDegree;
  }

  public void setOctaveDegree(int degree) {
    this.octaveDegree = degree;
  }

  public int[] getKeyMapping() {
    return keyMapping;
  }

  public void setKeyMapping(int[] mapping) {
    this.keyMapping = mapping;
  }
}
