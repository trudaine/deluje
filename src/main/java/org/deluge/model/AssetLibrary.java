package org.deluge.model;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Manages the library of User assets, loaded from the filesystem via PreferencesManager paths. */
public class AssetLibrary {

  public record AssetEntry(String category, String name, String path, boolean isFactory) {
    @Override
    public String toString() {
      return (isFactory ? "[F] " : "[U] ") + name;
    }
  }

  private final List<AssetEntry> synthPresets = new ArrayList<>();
  private final List<AssetEntry> kitPresets = new ArrayList<>();

  public AssetLibrary() {
    loadLibrary();
  }

  private void loadLibrary() {
    scanDir("SYNTHS", org.deluge.project.PreferencesManager.getSynthsDir(), synthPresets, false);
    scanDir("KITS", org.deluge.project.PreferencesManager.getKitsDir(), kitPresets, false);
    scanDir("SONGS", org.deluge.project.PreferencesManager.getSongsDir(), new ArrayList<>(), false);
  }

  private void scanDir(String category, File dir, List<AssetEntry> list, boolean isFactory) {
    if (dir == null || !dir.isDirectory()) return;
    try {
      scanRecursive(category, dir, dir, list, isFactory);
    } catch (IOException e) {
      System.err.println("AssetLibrary: Failed to scan " + dir + ": " + e.getMessage());
    }
  }

  private void scanRecursive(
      String category, File root, File current, List<AssetEntry> list, boolean isFactory)
      throws IOException {
    File[] files = current.listFiles();
    if (files == null) return;
    for (File f : files) {
      if (f.isDirectory()) {
        scanRecursive(category, root, f, list, isFactory);
      } else if (f.getName().toUpperCase().endsWith(".XML")) {
        String name = f.getName().substring(0, f.getName().lastIndexOf('.'));
        list.add(new AssetEntry(category, name, f.getAbsolutePath(), isFactory));
      }
    }
  }

  public List<AssetEntry> getSynths() {
    return synthPresets;
  }

  public List<AssetEntry> getKits() {
    return kitPresets;
  }
}
