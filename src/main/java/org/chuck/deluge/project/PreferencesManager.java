package org.chuck.deluge.project;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/** Manages global application preferences such as paths and recent files. */
public class PreferencesManager {
  private static final Preferences prefs = Preferences.userNodeForPackage(PreferencesManager.class);

  private static final String KEY_SAMPLES_DIR = "samples_dir";
  private static final String KEY_RECENT_FILES =
      "recent_files"; // Comma-separated for simplicity MVP
  private static final int MAX_RECENT = 10;

  public static String getSamplesDir() {
    return prefs.get(KEY_SAMPLES_DIR, System.getProperty("user.home") + "/Deluge/SAMPLES");
  }

  public static void setSamplesDir(String path) {
    prefs.put(KEY_SAMPLES_DIR, path);
  }

  public static List<String> getRecentFiles() {
    String raw = prefs.get(KEY_RECENT_FILES, "");
    List<String> list = new ArrayList<>();
    if (!raw.isEmpty()) {
      for (String path : raw.split(",")) {
        if (!path.trim().isEmpty()) {
          list.add(path.trim());
        }
      }
    }
    return list;
  }

  public static void addRecentFile(String path) {
    List<String> recent = getRecentFiles();
    recent.remove(path); // Remove if exists to push to front
    recent.add(0, path);

    // Trim to max
    while (recent.size() > MAX_RECENT) {
      recent.remove(recent.size() - 1);
    }

    prefs.put(KEY_RECENT_FILES, String.join(",", recent));
  }

  public static void clearRecentFiles() {
    prefs.remove(KEY_RECENT_FILES);
  }
}
