package org.deluge.project;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;

/**
 * Scans a preset folder to locate the first numerical or alphabetical preset file to emulate
 * original physical device boot-up configuration defaults (C strcmpspecial, name_compare.cpp:91).
 */
public class PresetFinder {
  public static File findFirstPreset(File presetsDir) {
    if (presetsDir == null || !presetsDir.exists()) return null;
    File[] files =
        presetsDir.listFiles(
            new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                String lower = name.toLowerCase();
                return lower.endsWith(".xml") || lower.endsWith(".json");
              }
            });
    if (files == null || files.length == 0) return null;

    // Sort preset display names using C++ strcmpspecial (C name_compare.cpp:91-235).
    Arrays.sort(
        files,
        (a, b) ->
            strcmpspecial(stripPresetExtension(a.getName()), stripPresetExtension(b.getName())));
    return files[0];
  }

  static String stripPresetExtension(String name) {
    if (name == null) return "";
    String lower = name.toLowerCase();
    if (lower.endsWith(".xml")) {
      return name.substring(0, name.length() - 4);
    }
    if (lower.endsWith(".json")) {
      return name.substring(0, name.length() - 5);
    }
    return name;
  }

  /**
   * Port of C++ {@code strcmpspecial} with {@code shouldInterpretNoteNames = false} (C
   * name_compare.cpp:91-235, load_instrument_preset_ui.cpp:696). Compares digit runs numerically,
   * breaks ties by leading-zero count, folds ASCII letters to lowercase, and ranks {@code '.'} then
   * {@code '_'} before other characters.
   */
  public static int strcmpspecial(String first, String second) {
    if (first == null) first = "";
    if (second == null) second = "";

    int i = 0;
    int j = 0;
    int resultIfGetToEndOfBothStrings = 0;

    while (true) {
      boolean firstIsFinished = (i >= first.length());
      boolean secondIsFinished = (j >= second.length());

      if (firstIsFinished && secondIsFinished) {
        return resultIfGetToEndOfBothStrings;
      }

      if (firstIsFinished || secondIsFinished) {
        int fc = firstIsFinished ? 0 : first.charAt(i);
        int sc = secondIsFinished ? 0 : second.charAt(j);
        return fc - sc;
      }

      char fc = first.charAt(i);
      char sc = second.charAt(j);
      boolean firstIsNumber = (fc >= '0' && fc <= '9');
      boolean secondIsNumber = (sc >= '0' && sc <= '9');

      if (firstIsNumber && secondIsNumber) {
        if (resultIfGetToEndOfBothStrings == 0) {
          int firstHere = i;
          int secondHere = j;
          while (true) {
            char firstChar = firstHere < first.length() ? first.charAt(firstHere) : 0;
            char secondChar = secondHere < second.length() ? second.charAt(secondHere) : 0;
            if (firstChar == '0' && secondChar == '0') {
              firstHere++;
              secondHere++;
              continue;
            }
            resultIfGetToEndOfBothStrings = (int) firstChar - (int) secondChar;
            break;
          }
        }

        long firstNumber = fc - '0';
        long secondNumber = sc - '0';
        i++;
        j++;

        while (i < first.length() && first.charAt(i) >= '0' && first.charAt(i) <= '9') {
          firstNumber = firstNumber * 10 + (first.charAt(i) - '0');
          i++;
        }

        while (j < second.length() && second.charAt(j) >= '0' && second.charAt(j) <= '9') {
          secondNumber = secondNumber * 10 + (second.charAt(j) - '0');
          j++;
        }

        if (firstNumber != secondNumber) {
          return firstNumber < secondNumber ? -1 : 1;
        }
      } else {
        char firstChar = (fc >= 'A' && fc <= 'Z') ? (char) (fc + 32) : fc;
        char secondChar = (sc >= 'A' && sc <= 'Z') ? (char) (sc + 32) : sc;

        if (firstChar == secondChar) {
          i++;
          j++;
        } else {
          if (firstChar == '.') return -1;
          if (secondChar == '.') return 1;
          if (firstChar == '_') return -1;
          if (secondChar == '_') return 1;
          return (int) firstChar - (int) secondChar;
        }
      }
    }
  }
}
