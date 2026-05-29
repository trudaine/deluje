package org.chuck.deluge.model.tuning;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser for standard Scala Keyboard Mapping (.kbm) files. Correctly strips comment lines,
 * processes map sizes, reference notes, and parses custom active keys assignments.
 */
public class ScalaKeyboardMapParser {

  public static ScalaKeyboardMap parse(InputStream is, String name) throws Exception {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
      ScalaKeyboardMap kbm = new ScalaKeyboardMap(name);
      List<String> values = new ArrayList<>();

      String line;
      while ((line = reader.readLine()) != null) {
        line = line.trim();
        if (line.isEmpty() || line.startsWith("!")) {
          continue; // Skip comments and empty lines
        }

        // Strip trailing comments
        int commentIdx = line.indexOf('!');
        if (commentIdx != -1) {
          line = line.substring(0, commentIdx).trim();
        }
        values.add(line);
      }

      if (values.size() < 6) {
        throw new IllegalArgumentException(
            "Malformed Keyboard Map: header requires exactly 6 variables fields.");
      }

      int mapSize = Integer.parseInt(values.get(0));
      int firstMidi = Integer.parseInt(values.get(1));
      int lastMidi = Integer.parseInt(values.get(2));
      int middleMidi = Integer.parseInt(values.get(3));
      double refFreq = Double.parseDouble(values.get(4));
      int octaveDegree = Integer.parseInt(values.get(5));

      kbm.setMapSize(mapSize);
      kbm.setFirstMidiNote(firstMidi);
      kbm.setLastMidiNote(lastMidi);
      kbm.setMiddleMidiNote(middleMidi);
      kbm.setReferenceFrequency(refFreq);
      kbm.setOctaveDegree(octaveDegree);

      if (mapSize > 0 && values.size() >= 6 + mapSize) {
        int[] mapping = new int[mapSize];
        for (int i = 0; i < mapSize; i++) {
          String valStr = values.get(6 + i).toLowerCase();
          if ("x".equals(valStr)) {
            mapping[i] = -1; // -1 represents standard silent/unmapped key!
          } else {
            mapping[i] = Integer.parseInt(valStr);
          }
        }
        kbm.setKeyMapping(mapping);
      }

      return kbm;
    }
  }
}
