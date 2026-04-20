package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the library of bundled Factory assets and local User assets.
 */
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
    loadFactoryLibrary();
    loadUserLibrary();
  }

  private void loadFactoryLibrary() {
    // Hardcoded high-value Factory Synths (from resources)
    String[] factorySynths = {
      "000 Rich Saw Bass", "001 Sync Bass", "002 Basic Square Bass", "003 Synthwave Bass",
      "046 Saw Sync", "060 Bass Guitar", "071 Rhodes", "073 Piano", "076 Organ",
      "130 Dark Strings", "131 Warm Strings", "136 Synthwave Pad", "154 Rich FM Pad 1"
    };
    for (String s : factorySynths) {
      synthPresets.add(new AssetEntry("SYNTHS", s, "/SYNTHS/" + s + ".XML", true));
    }

    // Hardcoded Factory Kits
    String[] factoryKits = {
      "000 TR-808", "003 TR-909", "014 CR-78", "020 Leonard Ludvigsen Beatbox"
    };
    for (String k : factoryKits) {
      kitPresets.add(new AssetEntry("KITS", k, "/KITS/" + k + ".XML", true));
    }
  }

  private void loadUserLibrary() {
    java.io.File userDir = new java.io.File("presets");
    if (!userDir.exists()) userDir.mkdirs();
    
    java.io.File[] files = userDir.listFiles((dir, name) -> name.toUpperCase().endsWith(".XML"));
    if (files != null) {
        for (java.io.File f : files) {
            boolean isSynth = f.getName().toUpperCase().contains("SYNTH");
            AssetEntry entry = new AssetEntry(isSynth ? "SYNTHS" : "KITS", f.getName().replace(".XML", ""), f.getAbsolutePath(), false);
            if (isSynth) synthPresets.add(entry);
            else kitPresets.add(entry);
        }
    }
  }

  public List<AssetEntry> getSynths() { return synthPresets; }
  public List<AssetEntry> getKits() { return kitPresets; }
}
