package org.deluge.project;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Scans a preset folder to locate the first numerical or alphabetical XML preset file to emulate
 * original physical device boot-up configuration defaults.
 */
public class PresetFinder {
  public static File findFirstPreset(File presetsDir) {
    if (presetsDir == null || !presetsDir.exists()) return null;
    File[] files =
        presetsDir.listFiles(
            new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                return name.toLowerCase().endsWith(".xml");
              }
            });
    if (files == null || files.length == 0) return null;

    // Sort files numerically/alphabetically (e.g., 000, 001, or generic string)
    Arrays.sort(files, Comparator.comparing(File::getName));
    return files[0];
  }
}
