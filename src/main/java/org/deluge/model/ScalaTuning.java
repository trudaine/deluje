package org.deluge.model;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * 100% C++ hardware parity (`// C microtuning.cpp`) Scala (.scl) custom microtuning format parser
 * and frequency calculator.
 *
 * <p>Supports arbitrary non-equal temperament intervals (ratio and cent specifications), non-octave
 * scale periods (e.g. Bohlen-Pierce), and custom base reference note frequencies.
 */
public class ScalaTuning {
  private static ScalaTuning activeTuning = null;

  private final String description;
  private final double[] noteCents; // index 0 is 0.0 (root), length is count + 1
  private final double periodCents; // total cents per period (usually 1200.0)

  public ScalaTuning(String description, double[] noteCents) {
    this.description = description != null ? description : "Custom Tuning";
    this.noteCents = noteCents;
    this.periodCents = noteCents.length > 1 ? noteCents[noteCents.length - 1] : 1200.0;
  }

  public String getDescription() {
    return description;
  }

  public double[] getNoteCents() {
    return noteCents.clone();
  }

  public double getPeriodCents() {
    return periodCents;
  }

  public int getNotesPerPeriod() {
    return Math.max(1, noteCents.length - 1);
  }

  /** Parses standard Scala (.scl) format text content. */
  public static ScalaTuning parseScl(String sclContent) {
    if (sclContent == null || sclContent.trim().isEmpty()) {
      throw new IllegalArgumentException("Scala content cannot be empty");
    }
    List<String> validLines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new StringReader(sclContent))) {
      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("!")) {
          continue;
        }
        validLines.add(trimmed);
      }
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to read Scala content: " + e.getMessage(), e);
    }

    if (validLines.size() < 2) {
      throw new IllegalArgumentException("Invalid Scala format: missing header lines");
    }

    String desc = validLines.get(0);
    int count;
    try {
      // Scala count line might have trailing comments after whitespace
      String countToken = validLines.get(1).split("\\s+")[0];
      count = Integer.parseInt(countToken);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("Invalid note count in Scala file: " + validLines.get(1));
    }

    if (count <= 0) {
      throw new IllegalArgumentException("Scala note count must be positive");
    }

    double[] cents = new double[count + 1];
    cents[0] = 0.0;

    int intervalsParsed = 0;
    for (int i = 2; i < validLines.size() && intervalsParsed < count; i++) {
      String token = validLines.get(i).split("\\s+")[0];
      double c = parseIntervalToken(token);
      intervalsParsed++;
      cents[intervalsParsed] = c;
    }

    if (intervalsParsed < count) {
      throw new IllegalArgumentException(
          "Scala file specified "
              + count
              + " intervals but only contained "
              + intervalsParsed);
    }

    return new ScalaTuning(desc, cents);
  }

  private static double parseIntervalToken(String token) {
    if (token.contains(".")) {
      // Explicit cents float value (e.g. 100.0 or 701.955)
      return Double.parseDouble(token);
    } else if (token.contains("/")) {
      // Frequency ratio num/den (e.g. 3/2 or 9/8)
      String[] parts = token.split("/");
      double num = Double.parseDouble(parts[0]);
      double den = Double.parseDouble(parts[1]);
      if (den == 0.0) throw new IllegalArgumentException("Ratio denominator cannot be zero");
      return 1200.0 * Math.log(num / den) / Math.log(2.0);
    } else {
      // Integer ratio N/1 (e.g. 2 for 2/1 octave)
      double num = Double.parseDouble(token);
      return 1200.0 * Math.log(num) / Math.log(2.0);
    }
  }

  /**
   * Calculates the exact frequency in Hz for a MIDI note under this custom microtuning.
   *
   * @param midiNote MIDI note index (0-127)
   * @param rootMidiNote Reference MIDI root note (default 60 = C4)
   * @param rootFrequencyHz Reference root frequency in Hz (default 261.6255653 for C4 = 440 Hz
   *     A4-9st)
   */
  public double getFrequency(int midiNote, int rootMidiNote, double rootFrequencyHz) {
    int n = getNotesPerPeriod();
    int k = midiNote - rootMidiNote;
    int periodShift = Math.floorDiv(k, n);
    int degree = Math.floorMod(k, n);

    double deltaCents = periodShift * periodCents + noteCents[degree];
    return rootFrequencyHz * Math.pow(2.0, deltaCents / 1200.0);
  }

  /** Convenience method using standard 440Hz reference (C4 = 60 -> 261.6255653 Hz). */
  public double getFrequency(int midiNote) {
    return getFrequency(midiNote, 60, 261.6255653006);
  }

  /** Gets the globally active custom Scala microtuning, or null if standard 12-TET is active. */
  public static ScalaTuning getActiveTuning() {
    return activeTuning;
  }

  /** Sets the globally active custom Scala microtuning (pass null to restore 12-TET). */
  public static void setActiveTuning(ScalaTuning tuning) {
    activeTuning = tuning;
  }

  /**
   * Converts a MIDI note to frequency in Hz, applying custom Scala microtuning if active or
   * standard equal temperament otherwise.
   */
  public static double midiToFrequencyHz(int midiNote) {
    if (activeTuning != null) {
      return activeTuning.getFrequency(midiNote);
    }
    return 440.0 * Math.pow(2.0, (midiNote - 69) / 12.0);
  }
}
