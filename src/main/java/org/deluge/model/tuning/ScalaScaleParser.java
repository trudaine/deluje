package org.deluge.model.tuning;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for standard Scala (.scl) microtonal scale tuning files. Parses comment lines, description
 * labels, dynamic steps sizes, fractions ratios, and cents offsets.
 */
public class ScalaScaleParser {

  public static ScalaScale parse(InputStream is, String name) throws Exception {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      String description = "";
      boolean descriptionSeen = false;
      int stepsCount = -1;
      List<Double> stepValues = new ArrayList<>();

      String line;
      while ((line = reader.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.startsWith("!")) {
          continue; // Skip comment lines
        }

        if (!descriptionSeen) {
          description = trimmed;
          descriptionSeen = true;
          continue;
        }

        if (trimmed.isEmpty()) {
          continue; // Skip blank lines after description
        }

        if (stepsCount == -1) {
          String countToken = trimmed.split("\\s+")[0];
          stepsCount = Integer.parseInt(countToken);
          continue;
        }

        // Strip trailing comment text and ignore any trailing note labels after the pitch token
        int commentIdx = trimmed.indexOf('!');
        if (commentIdx != -1) {
          trimmed = trimmed.substring(0, commentIdx).trim();
        }
        String pitchToken = trimmed.split("\\s+")[0];

        double ratio;
        if (pitchToken.contains("/")) {
          String[] parts = pitchToken.split("/", 2);
          double num = Double.parseDouble(parts[0].trim());
          double den = Double.parseDouble(parts[1].trim());
          ratio = num / den;
        } else if (pitchToken.contains(".")) {
          double cents = Double.parseDouble(pitchToken);
          ratio = Math.pow(2.0, cents / 1200.0);
        } else {
          // Scala .scl spec: a single integer without '.' or '/' is a ratio with 1 (e.g. 2 is 2/1).
          ratio = Double.parseDouble(pitchToken);
        }
        stepValues.add(ratio);
      }

      if (stepsCount <= 0 || stepValues.size() < stepsCount) {
        throw new IllegalArgumentException(
            "Malformed Scala file: expected "
                + stepsCount
                + " steps but parsed "
                + stepValues.size());
      }

      double[] stepRatios = new double[stepsCount];
      stepRatios[0] = 1.0; // Unison step is always 1.0 (unaltered)
      for (int i = 0; i < stepsCount - 1; i++) {
        stepRatios[i + 1] = stepValues.get(i);
      }

      double octaveRatio = stepValues.get(stepsCount - 1);

      return new ScalaScale(name, description, stepsCount, stepRatios, octaveRatio);
    }
  }
}
