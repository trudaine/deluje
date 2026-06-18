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

  /** Grid panel UI style implementations. */
  public enum GridPanelType {
    LEGACY,
    ADVANCED;

    public static GridPanelType fromString(String s) {
      try {
        return valueOf(s);
      } catch (Exception e) {
        return ADVANCED;
      }
    }
  }

  // ── Hardware character keys ──────────────────────────────────────────────
  private static final String KEY_MASTER_SATURATION = "masterSaturation";
  private static final String KEY_FILTER_DRIVE = "filterDrive";
  private static final String KEY_BIT_CRUNCH = "bitCrunch";
  private static final String KEY_REVERB_MODEL = "reverbModel";

  public static boolean isMasterSaturationEnabled() {
    return prefs.getBoolean(KEY_MASTER_SATURATION, false);
  }

  public static void setMasterSaturationEnabled(boolean enabled) {
    prefs.putBoolean(KEY_MASTER_SATURATION, enabled);
  }

  public static boolean isFilterDriveEnabled() {
    return prefs.getBoolean(KEY_FILTER_DRIVE, false);
  }

  public static void setFilterDriveEnabled(boolean enabled) {
    prefs.putBoolean(KEY_FILTER_DRIVE, enabled);
  }

  public static boolean isBitCrunchEnabled() {
    return prefs.getBoolean(KEY_BIT_CRUNCH, false);
  }

  public static void setBitCrunchEnabled(boolean enabled) {
    prefs.putBoolean(KEY_BIT_CRUNCH, enabled);
  }

  public static String getReverbModel() {
    return prefs.get(KEY_REVERB_MODEL, "JCRev");
  }

  public static void setReverbModel(String model) {
    prefs.put(KEY_REVERB_MODEL, model);
  }

  private static final String KEY_MONITOR_GAIN_BOOST = "audio.monitorGainBoost";

  /**
   * Loudest desktop boost that stays clean with the linear output chain. The engine's master
   * compressor bounds its output, so ~32x dense polyphony peaks ~0.84 with headroom; above that the
   * brickwall can catch extremes.
   */
  public static final int MAX_CLEAN_GAIN_BOOST = 128;

  /**
   * Post-engine desktop output boost (LINEAR — the driver no longer soft-clips; the engine's master
   * compressor already did the mastering). Defaults to 24x: a single note peaks ~0.31 and dense
   * polyphony ~0.63, preserving the engine's dynamics (the faithful, "punchy" option B). Clamped to
   * {@link #MAX_CLEAN_GAIN_BOOST}.
   */
  public static int getMonitorGainBoost() {
    int v = prefs.getInt(KEY_MONITOR_GAIN_BOOST, 24);
    return Math.max(1, Math.min(MAX_CLEAN_GAIN_BOOST, v));
  }

  public static void setMonitorGainBoost(int value) {
    prefs.putInt(KEY_MONITOR_GAIN_BOOST, value);
    try {
      prefs.flush();
    } catch (Exception ignored) {
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

  private static final String KEY_GRID_PANEL_TYPE = "grid.panel.type";

  public static GridPanelType getGridPanelType() {
    return GridPanelType.fromString(prefs.get(KEY_GRID_PANEL_TYPE, "ADVANCED"));
  }

  public static void setGridPanelType(GridPanelType type) {
    prefs.put(KEY_GRID_PANEL_TYPE, type.name());
  }

  /** Interaction mode when holding Shift and clicking grid parameter shortcut cells. */
  public enum ShiftInteractionMode {
    POPUP_SLIDER,
    ROTARY_ENCODER;

    public static ShiftInteractionMode fromString(String s) {
      try {
        return valueOf(s);
      } catch (Exception e) {
        return POPUP_SLIDER;
      }
    }
  }

  private static final String KEY_SHIFT_INTERACTION_MODE = "grid.shift.interactionMode";

  public static ShiftInteractionMode getShiftInteractionMode() {
    return ShiftInteractionMode.fromString(prefs.get(KEY_SHIFT_INTERACTION_MODE, "POPUP_SLIDER"));
  }

  public static void setShiftInteractionMode(ShiftInteractionMode mode) {
    prefs.put(KEY_SHIFT_INTERACTION_MODE, mode.name());
  }

  /** Hardware Screen Display Style (Both, OLED Only, or Retro LED Only). */
  public enum DisplayType {
    BOTH,
    OLED_ONLY,
    LED_ONLY;

    public static DisplayType fromString(String s) {
      try {
        return valueOf(s);
      } catch (Exception e) {
        return BOTH;
      }
    }
  }

  private static final String KEY_DISPLAY_TYPE = "display.type";

  public static DisplayType getDisplayType() {
    return DisplayType.fromString(prefs.get(KEY_DISPLAY_TYPE, "BOTH"));
  }

  public static void setDisplayType(DisplayType type) {
    prefs.put(KEY_DISPLAY_TYPE, type.name());
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

    // Fallback 1: Check for 'Deluge' folder in current project root
    java.io.File projectLocal = new java.io.File("Deluge");
    if (projectLocal.isDirectory()) {
      return projectLocal;
    }

    // Fallback 2: derive from samples_dir preference
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

  public static java.io.File getMidiDevicesDir() {
    return ensureDir(new java.io.File(getLibraryDir(), "MIDI_DEVICES"));
  }

  public static java.io.File getMidiDeviceDefinitionsDir() {
    return ensureDir(new java.io.File(getMidiDevicesDir(), "DEFINITION"));
  }

  public static java.io.File getPatternsDir() {
    return ensureDir(new java.io.File(getLibraryDir(), "PATTERNS"));
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

  private static final String KEY_WINDOW_WIDTH = "windowWidth";
  private static final String KEY_WINDOW_HEIGHT = "windowHeight";

  public static int getWindowWidth(int defaultVal) {
    return prefs.getInt(KEY_WINDOW_WIDTH, defaultVal);
  }

  public static void setWindowWidth(int width) {
    prefs.putInt(KEY_WINDOW_WIDTH, width);
    try {
      prefs.flush();
    } catch (Exception ignored) {
    }
  }

  public static int getWindowHeight(int defaultVal) {
    return prefs.getInt(KEY_WINDOW_HEIGHT, defaultVal);
  }

  public static void setWindowHeight(int height) {
    prefs.putInt(KEY_WINDOW_HEIGHT, height);
    try {
      prefs.flush();
    } catch (Exception ignored) {
    }
  }

  public static String get(String key, String def) {
    return prefs.get(key, def);
  }

  public static void set(String key, String value) {
    prefs.put(key, value);
  }

  public static void remove(String key) {
    prefs.remove(key);
    try {
      prefs.flush();
    } catch (Exception e) {
      // Ignore
    }
  }

  public static String[] getKeys() {
    try {
      return prefs.keys();
    } catch (Exception e) {
      return new String[0];
    }
  }
}
