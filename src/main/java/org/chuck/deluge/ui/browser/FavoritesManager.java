package org.chuck.deluge.ui.browser;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Manages favorited samples. Stores favorites as a simple list of absolute paths in the user's home
 * directory.
 */
public class FavoritesManager {
  private final Set<String> favorites = new HashSet<>();
  private final Path favoritesFile;

  public FavoritesManager() {
    File home = new File(System.getProperty("user.home"), ".chuck-deluge");
    if (!home.exists()) home.mkdirs();
    favoritesFile = Paths.get(home.getAbsolutePath(), "favorites.json");
    load();
  }

  private void load() {
    favorites.clear();
    try {
      if (Files.exists(favoritesFile)) {
        String content = Files.readString(favoritesFile);
        // Simple manual parsing since Jackson isn't guaranteed and it's a flat list
        content = content.replace("[", "").replace("]", "").replace("\"", "").trim();
        if (!content.isEmpty()) {
          for (String line : content.split(",")) {
            if (!line.trim().isEmpty()) {
              favorites.add(line.trim());
            }
          }
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to load favorites: " + e.getMessage());
    }
  }

  private void save() {
    try {
      StringBuilder sb = new StringBuilder("[\n");
      boolean first = true;
      for (String fav : favorites) {
        if (!first) sb.append(",\n");
        sb.append("  \"").append(fav).append("\"");
        first = false;
      }
      sb.append("\n]");
      Files.writeString(favoritesFile, sb.toString());
    } catch (Exception e) {
      System.err.println("Failed to save favorites: " + e.getMessage());
    }
  }

  public boolean isFavorite(File file) {
    return favorites.contains(file.getAbsolutePath());
  }

  public void toggleFavorite(File file) {
    String path = file.getAbsolutePath();
    if (favorites.contains(path)) {
      favorites.remove(path);
    } else {
      favorites.add(path);
    }
    save();
  }

  public Set<String> getFavorites() {
    return favorites;
  }
}
