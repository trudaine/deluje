package org.chuck.deluge.model.tuning;

/**
 * Model class representing a parsed Scala (.scl) musical scale tuning model. Provides custom steps
 * and octave transpositions math, supporting dynamic EDO and alternative microtonality.
 */
public class ScalaScale {
  private final String name;
  private final String description;
  private final int stepsCount;
  private final double[] stepRatios; // Size stepsCount, stepRatios[0] is 1.0 (unison)
  private final double octaveRatio; // Octave repeat ratio (usually 2.0)

  private int referenceMidiNote = 60; // Default Middle C
  private double referenceFrequency = 261.625565; // Default Middle C (A440 scale)

  public ScalaScale(
      String name, String description, int stepsCount, double[] stepRatios, double octaveRatio) {
    this.name = name;
    this.description = description;
    this.stepsCount = stepsCount;
    this.stepRatios = stepRatios;
    this.octaveRatio = octaveRatio;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public int getStepsCount() {
    return stepsCount;
  }

  public double[] getStepRatios() {
    return stepRatios;
  }

  public double getOctaveRatio() {
    return octaveRatio;
  }

  public int getReferenceMidiNote() {
    return referenceMidiNote;
  }

  public void setReferenceMidiNote(int note) {
    this.referenceMidiNote = note;
  }

  public double getReferenceFrequency() {
    return referenceFrequency;
  }

  public void setReferenceFrequency(double freq) {
    this.referenceFrequency = freq;
  }

  /** Translate a MIDI note code (including fractional pitch modulations) to frequency in Hz. */
  public double mtof(double midiNote) {
    double diff = midiNote - referenceMidiNote;

    // Support fractional pitch (detuning) by calculating step and fractional cents offset
    double floorDiff = Math.floor(diff);
    double fraction = diff - floorDiff;

    int intDiff = (int) floorDiff;
    int octave = Math.floorDiv(intDiff, stepsCount);
    int step = Math.floorMod(intDiff, stepsCount);

    double baseRatio = stepRatios[step];
    double octaveFactor = Math.pow(octaveRatio, octave);

    double baseFreq = referenceFrequency * baseRatio * octaveFactor;

    // Apply standard pitch cents detuning (vibrato/pitch bends remain consistent in standard cents)
    if (fraction != 0.0) {
      baseFreq *= Math.pow(2.0, fraction / 12.0);
    }

    return baseFreq;
  }
}
