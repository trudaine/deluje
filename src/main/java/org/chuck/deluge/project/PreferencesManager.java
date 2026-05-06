package org.chuck.deluge.project;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

/** Manages global application preferences such as paths and recent files. */
public class PreferencesManager {
  private static final Preferences prefs = Preferences.userNodeForPackage(PreferencesManager.class);

  /** Configurable grid size modes for the sequencer grid. */
  public enum GridMode {
    /** 8 voice rows × 16 step columns */
    GRID_8x16(8, 16),
    /** 16 voice rows × 16 step columns */
    GRID_16x16(16, 16),
    /** 24 voice rows × 16 step columns */
    GRID_24x16(24, 16),
    /** 16 voice rows × 24 step columns */
    GRID_16x24(16, 24);

    public final int rows;
    public final int columns;

    GridMode(int rows, int columns) {
      this.rows = rows;
      this.columns = columns;
    }

    public static GridMode fromString(String s) {
      if (s == null) return GRID_8x16;
      for (GridMode m : values()) {
        if (m.name().equals(s)) return m;
      }
      return GRID_8x16;
    }
  }

  /** Available sequencer engine implementations. */
  public enum SequencerEngine {
    /** The original ChucK-based sequencer DSL. */
    CHUCK,
    /** Experimental pure Java sequencer implementation. */
    PURE_JAVA;

    public static SequencerEngine fromString(String s) {
      try {
        return valueOf(s);
      } catch (Exception e) {
        return CHUCK;
      }
    }
  }

  private static final String KEY_GRID_MODE = "grid.mode";
  private static final String KEY_SEQUENCER_ENGINE = "sequencer.engine";

  public static GridMode getGridMode() {
    return GridMode.fromString(prefs.get(KEY_GRID_MODE, "GRID_8x16"));
  }

  public static void setGridMode(GridMode mode) {
    prefs.put(KEY_GRID_MODE, mode.name());
  }

  public static SequencerEngine getSequencerEngine() {
    return SequencerEngine.fromString(prefs.get(KEY_SEQUENCER_ENGINE, "CHUCK"));
  }

  public static void setSequencerEngine(SequencerEngine engine) {
    prefs.put(KEY_SEQUENCER_ENGINE, engine.name());
  }

  private static final String KEY_LIBRARY_DIR = "library_dir";
  private static final String KEY_RECENT_FILES =
      "recent_files"; // Comma-separated for simplicity MVP
  private static final int MAX_RECENT = 10;

  /** Root of the Deluge library (contains SONGS, KITS, SYNTHS, SAMPLES subdirectories). */
  public static java.io.File getLibraryDir() {
    String path = prefs.get(KEY_LIBRARY_DIR, "");
    if (!path.isEmpty()) {
      return new java.io.File(path);
    }
    // Legacy fallback: derive from samples_dir preference
    String legacy = prefs.get("samples_dir", "");
    if (!legacy.isEmpty()) {
      java.io.File parent = new java.io.File(legacy).getParentFile();
      return parent != null ? parent : new java.io.File(System.getProperty("user.home"), "Deluge");
    }
    return new java.io.File(System.getProperty("user.home"), "Deluge");
  }

  public static void setLibraryDir(String path) {
    prefs.put(KEY_LIBRARY_DIR, path);
  }

  /** The SAMPLES subdirectory of the library root. */
  public static String getSamplesDir() {
    return new java.io.File(getLibraryDir(), "SAMPLES").getAbsolutePath();
  }

  public static java.io.File getSongsDir() {
    return ensureDir(new java.io.File(getLibraryDir(), "SONGS"));
  }

  public static java.io.File getKitsDir() {
    return ensureDir(new java.io.File(getLibraryDir(), "KITS"));
  }

  public static java.io.File getSynthsDir() {
    return ensureDir(new java.io.File(getLibraryDir(), "SYNTHS"));
  }

  private static java.io.File ensureDir(java.io.File dir) {
    dir.mkdirs();
    return dir;
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

  public static String get(String key, String def) {
    return prefs.get(key, def);
  }

  public static void set(String key, String value) {
    prefs.put(key, value);
  }

  public static String[] getKeys() {
    try {
      return prefs.keys();
    } catch (Exception e) {
      return new String[0];
    }
  }
}
