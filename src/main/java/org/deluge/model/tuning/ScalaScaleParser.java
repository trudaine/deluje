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
      int stepsCount = -1;
      List<Double> stepValues = new ArrayList<>();

      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("!")) {
          continue; // Skip comments and empty lines
        }

        if (description.isEmpty() && stepsCount == -1) {
          description = line;
          continue;
        }

        if (stepsCount == -1) {
          stepsCount = Integer.parseInt(line);
          continue;
        }

        // Strip trailing comment lines (e.g. "3/2 ! perfect fifth")
        int commentIdx = line.indexOf('!');
        if (commentIdx != -1) {
          line = line.substring(0, commentIdx).trim();
        }

        double ratio;
        if (line.contains("/")) {
          String[] parts = line.split("/");
          double num = Double.parseDouble(parts[0].trim());
          double den = Double.parseDouble(parts[1].trim());
          ratio = num / den;
        } else if (line.contains(".")) {
          double cents = Double.parseDouble(line);
          ratio = Math.pow(2.0, cents / 1200.0);
        } else {
          // Whole integer steps parsed as whole cents values
          double cents = Double.parseDouble(line);
          ratio = Math.pow(2.0, cents / 1200.0);
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
