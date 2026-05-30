package org.chuck.deluge.project;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automates the incremental naming scheme of original Deluge: - Unsaved New Song -> SONG000,
 * SONG001... (finding next unused main slot number). - Existing Song -> SONG003 -> SONG003A,
 * SONG003B... (finding next unused alphabetical subslot).
 */
public class SaveNameSuggester {
  private static final Pattern SONG_PATTERN = Pattern.compile("(?i)^SONG(\\d{3})([A-Z])?\\.xml$");

  public static File suggestNextSaveFile(File songsDir, File currentFile) {
    if (songsDir == null) return null;
    if (!songsDir.exists()) {
      songsDir.mkdirs();
    }

    if (currentFile == null) {
      // Unsaved new song: scan directory for highest index and suggest next slot
      int maxNumber = -1;
      File[] files = songsDir.listFiles();
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
      // Existing song: parse current name and find the next available subslot letter (e.g.
      // SONG003A)
      String currentName = currentFile.getName();
      Matcher m = SONG_PATTERN.matcher(currentName);
      if (m.matches()) {
        String numberStr = m.group(1);
        int num = Integer.parseInt(numberStr);
        String currentSubslot = m.group(2); // might be null

        // Loop from A to Z to find the first unused name
        char nextLetter =
            currentSubslot == null ? 'A' : (char) (currentSubslot.toUpperCase().charAt(0));
        if (currentSubslot != null) {
          nextLetter++; // Start from next letter
        }

        while (nextLetter <= 'Z') {
          String candidateName = String.format("SONG%03d%c.xml", num, nextLetter);
          File candidateFile = new File(songsDir, candidateName);
          if (!candidateFile.exists()) {
            return candidateFile;
          }
          nextLetter++;
        }
      }
      // Fallback if patterns do not match or slots are filled: return the original file
      return currentFile;
    }
  }
}
