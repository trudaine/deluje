package org.deluge.project;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automates the incremental naming scheme of the Deluge firmware (C default_name.cpp:38-183):
 *
 * <ul>
 *   <li>Unsaved New Song -> SONG000, SONG001... (next unused main slot number).
 *   <li>Existing Slot Song -> SONG003 -> SONG003A, SONG003B... (next unused letter A..Z).
 *   <li>Existing Custom Song -> MYTRACK -> MYTRACK 2 -> MYTRACK 3 (or TRACK_1 -> TRACK_2).
 * </ul>
 */
public class SaveNameSuggester {
  private static final Pattern SONG_PATTERN =
      Pattern.compile("(?i)^SONG(\\d{1,3})([A-Z])?\\.(?:xml|json)$");
  private static final Pattern EXTENSION_PATTERN = Pattern.compile("(?i)\\.(?:xml|json)$");
  private static final int MAX_NUMERIC_SUFFIX = 9999;

  public static File suggestNextSaveFile(File songsDir, File currentFile) {
    if (songsDir == null) return null;
    if (!songsDir.exists()) {
      songsDir.mkdirs();
    }

    File[] files = songsDir.listFiles();

    if (currentFile == null) {
      // Unsaved new song: scan directory for highest index and suggest next slot
      int maxNumber = -1;
      if (files != null) {
        for (File f : files) {
          Matcher m = SONG_PATTERN.matcher(f.getName());
          if (m.matches()) {
            int num = Integer.parseInt(m.group(1));
            if (num > maxNumber) {
              maxNumber = num;
            }
          }
        }
      }
      int nextNumber = maxNumber == -1 ? 0 : maxNumber + 1;
      return new File(songsDir, String.format("SONG%03d.xml", nextNumber));
    } else {
      String currentName = currentFile.getName();
      Matcher m = SONG_PATTERN.matcher(currentName);
      if (m.matches()) {
        // Slot-form song name ("SONG185" / "SONG003A", C default_name.cpp:155-164)
        String numberStr = m.group(1);
        String currentSubslot = m.group(2);
        String stem = "SONG" + numberStr;
        int takenMask = takenLetterSuffixes(files, stem);

        char from =
            currentSubslot == null
                ? 'A'
                : (char) (Character.toUpperCase(currentSubslot.charAt(0)) + 1);
        for (char c = from; c <= 'Z'; c++) {
          if ((takenMask & (1 << (c - 'A'))) == 0) {
            return new File(songsDir, stem + c + ".xml");
          }
        }
        return currentFile; // Letters exhausted past Z
      }

      // Custom song name ("MYTRACK.xml" -> "MYTRACK 2.xml", C default_name.cpp:170-183)
      String stem = EXTENSION_PATTERN.matcher(currentName).replaceFirst("");
      char delimiter = ' ';
      String base = stem;
      for (char candidate : new char[] {'_', ' '}) {
        int at = stem.lastIndexOf(candidate);
        if (at < 0 || at + 1 >= stem.length()) continue;
        boolean allDigits = true;
        for (int i = at + 1; i < stem.length(); i++) {
          if (!Character.isDigit(stem.charAt(i))) {
            allDigits = false;
            break;
          }
        }
        if (allDigits) {
          delimiter = candidate;
          base = stem.substring(0, at);
          break;
        }
      }

      String prefix = base + delimiter;
      int highest = numberOfSibling(stem, prefix);
      if (files != null) {
        for (File f : files) {
          highest = Math.max(highest, numberOfSibling(f.getName(), prefix));
        }
      }
      if (highest >= MAX_NUMERIC_SUFFIX) {
        return currentFile;
      }
      int nextNum = Math.max(highest, 1) + 1;
      return new File(songsDir, prefix + nextNum + ".xml");
    }
  }

  private static int takenLetterSuffixes(File[] files, String stem) {
    if (files == null) return 0;
    int mask = 0;
    for (File f : files) {
      String nameNoExt = EXTENSION_PATTERN.matcher(f.getName()).replaceFirst("");
      if (nameNoExt.length() == stem.length() + 1
          && nameNoExt.regionMatches(true, 0, stem, 0, stem.length())) {
        char c = Character.toUpperCase(nameNoExt.charAt(stem.length()));
        if (c >= 'A' && c <= 'Z') {
          mask |= (1 << (c - 'A'));
        }
      }
    }
    return mask;
  }

  /**
   * Reads the numeric suffix after {@code prefix} (C default_name.cpp:103-126). Digits must run up
   * to the first {@code '.'} (extension) or the end of {@code name}.
   */
  private static int numberOfSibling(String name, String prefix) {
    if (name.length() <= prefix.length()) return 0;
    if (!name.regionMatches(true, 0, prefix, 0, prefix.length())) return 0;
    int pos = prefix.length();
    if (!Character.isDigit(name.charAt(pos))) return 0;
    int number = 0;
    while (pos < name.length() && Character.isDigit(name.charAt(pos))) {
      number = number * 10 + (name.charAt(pos) - '0');
      if (number > MAX_NUMERIC_SUFFIX) return MAX_NUMERIC_SUFFIX;
      pos++;
    }
    return (pos == name.length() || name.charAt(pos) == '.') ? number : 0;
  }
}
