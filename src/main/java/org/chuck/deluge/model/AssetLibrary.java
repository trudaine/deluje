package org.chuck.deluge.model;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/** Manages the library of bundled Factory assets and local User assets. */
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
    scanFactoryFolder("SYNTHS", "/SYNTHS", synthPresets);
    scanFactoryFolder("KITS", "/KITS", kitPresets);
  }

  private void scanFactoryFolder(String category, String internalDir, List<AssetEntry> list) {
    try {
      URL url = getClass().getResource(internalDir);
      if (url == null) return;

      URI uri = url.toURI();
      Path rootPath;
      FileSystem fs = null;

      if (uri.getScheme().equals("jar")) {
        try {
          fs = FileSystems.getFileSystem(uri);
        } catch (Exception e) {
          fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
        }
        rootPath = fs.getPath(internalDir);
      } else {
        rootPath = Paths.get(uri);
      }

      if (Files.exists(rootPath)) {
        try (Stream<Path> walk = Files.walk(rootPath)) {
          walk.filter(p -> p.toString().toUpperCase().endsWith(".XML"))
              .forEach(
                  p -> {
                    String filename = p.getFileName().toString();
                    String name = filename.substring(0, filename.lastIndexOf('.'));
                    String path = internalDir + "/" + filename;
                    list.add(new AssetEntry(category, name, path, true));
                  });
        }
      }
    } catch (IOException | URISyntaxException e) {
      System.err.println(
          "AssetLibrary: Failed to scan factory resources for " + category + ": " + e.getMessage());
    }
  }

  private void loadUserLibrary() {
    java.io.File userDir = new java.io.File("presets");
    if (!userDir.exists()) userDir.mkdirs();

    java.io.File[] files = userDir.listFiles((dir, name) -> name.toUpperCase().endsWith(".XML"));
    if (files != null) {
      for (java.io.File f : files) {
        boolean isSynth = f.getName().toUpperCase().contains("SYNTH");
        AssetEntry entry =
            new AssetEntry(
                isSynth ? "SYNTHS" : "KITS",
                f.getName().replace(".XML", ""),
                f.getAbsolutePath(),
                false);
        if (isSynth) synthPresets.add(entry);
        else kitPresets.add(entry);
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
