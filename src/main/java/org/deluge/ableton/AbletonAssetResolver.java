package org.deluge.ableton;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Ableton sample references to valid local file paths on macOS and Windows. Integrates
 * Factory Packs, the Core Library, the User Library, and absolute hints.
 */
public class AbletonAssetResolver {
  private static final Logger LOG = Logger.getLogger(AbletonAssetResolver.class.getName());
  private static final Pattern LIVE_VERSION_PATTERN =
      Pattern.compile("(?i)(?:live\\s+)?(\\d+)(?:\\.(\\d+))?");

  private static final boolean IS_WINDOWS =
      System.getProperty("os.name").toLowerCase().contains("win");
  private static final String HOME = System.getProperty("user.home");

  // Lazy-initialized via holder — defers filesystem access to first use, surviving JVM restarts
  private static String packsDir;
  private static String userLibDir;
  private static String appResourcesDir;

  private static synchronized String getPacksDir() {
    if (packsDir == null) packsDir = detectPacksDir();
    return packsDir;
  }

  private static synchronized String getUserLibDir() {
    if (userLibDir == null) userLibDir = detectUserLibDir();
    return userLibDir;
  }

  private static synchronized String getAppResourcesDir() {
    if (appResourcesDir == null) appResourcesDir = detectAbletonAppResourcesDir();
    return appResourcesDir;
  }

  /**
   * Resolve an Ableton sample path to a valid local file.
   *
   * @param livePackName the name of the Live Pack (e.g. "Sequencers"), or null/empty if not in a
   *     pack
   * @param relativePath the relative path of the sample (e.g. "Racks/Drum Racks/Samples/Kick/Kick
   *     Fat Click.aif")
   * @param absolutePathHint the absolute path hint stored in the ALS (e.g. "/Volumes/data/tmp/...")
   * @return the resolved File, or null if it cannot be found
   */
  public static File resolveSamplePath(
      String livePackName, String relativePath, String absolutePathHint) {
    // 1. Check if the absolute path hint exists locally
    if (absolutePathHint != null && !absolutePathHint.isBlank()) {
      File file = new File(absolutePathHint);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    // 2. If it's part of a Factory Pack, resolve it
    if (livePackName != null
        && !livePackName.isBlank()
        && relativePath != null
        && !relativePath.isBlank()) {
      File file = new File(new File(getPacksDir(), livePackName), relativePath);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    // 3. Check Core Library
    if (relativePath != null && !relativePath.isBlank()) {
      File file = new File(getAppResourcesDir() + "Core Library/", relativePath);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    // 4. Check Builtin Library
    if (relativePath != null && !relativePath.isBlank()) {
      File file = new File(getAppResourcesDir() + "Builtin/", relativePath);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    // 5. Check User Library
    if (relativePath != null && !relativePath.isBlank()) {
      File file = new File(getUserLibDir(), relativePath);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    LOG.warning(
        "Unable to resolve Ableton sample: pack="
            + livePackName
            + ", rel="
            + relativePath
            + ", absHint="
            + absolutePathHint);
    return null;
  }

  // ── Dynamic Path Detection Utilities ──

  private static String detectPacksDir() {
    // Check Documents first (Windows default, macOS secondary)
    File docsPacks = new File(HOME, "Documents/Ableton/Factory Packs");
    if (docsPacks.exists() && docsPacks.isDirectory()) {
      return docsPacks.getAbsolutePath() + "/";
    }
    // Check Music next (macOS default, Windows secondary)
    File musicPacks = new File(HOME, "Music/Ableton/Factory Packs");
    if (musicPacks.exists() && musicPacks.isDirectory()) {
      return musicPacks.getAbsolutePath() + "/";
    }
    // Fallback default
    return HOME
        + (IS_WINDOWS ? "/Documents/Ableton/Factory Packs/" : "/Music/Ableton/Factory Packs/");
  }

  private static String detectUserLibDir() {
    File docsLib = new File(HOME, "Documents/Ableton/User Library");
    if (docsLib.exists() && docsLib.isDirectory()) {
      return docsLib.getAbsolutePath() + "/";
    }
    File musicLib = new File(HOME, "Music/Ableton/User Library");
    if (musicLib.exists() && musicLib.isDirectory()) {
      return musicLib.getAbsolutePath() + "/";
    }
    // Fallback default
    return HOME
        + (IS_WINDOWS ? "/Documents/Ableton/User Library/" : "/Music/Ableton/User Library/");
  }

  private static String detectAbletonAppResourcesDir() {
    if (IS_WINDOWS) {
      // Windows default Program Files scan
      File abDir = new File("C:\\Program Files\\Ableton");
      if (abDir.exists() && abDir.isDirectory()) {
        File[] versions = abDir.listFiles();
        if (versions != null) {
          List<File> liveFolders = new ArrayList<>();
          for (File f : versions) {
            if (f.isDirectory() && f.getName().startsWith("Live")) {
              liveFolders.add(f);
            }
          }
          if (!liveFolders.isEmpty()) {
            // Sort descending: highest version first (e.g. Live 12 Suite > Live 9 Suite)
            liveFolders.sort((a, b) -> compareAbletonVersionNames(a.getName(), b.getName()));
            File detected = liveFolders.get(0);
            LOG.info(
                "[AbletonAssetResolver] Dynamically detected Windows Ableton: "
                    + detected.getName());
            return detected.getAbsolutePath() + "/Resources/";
          }
        }
      }
      return "C:\\Program Files\\Ableton\\Live 12 Suite\\Resources\\";
    } else {
      // macOS Applications folder scan
      File appsDir = new File("/Applications");
      if (appsDir.exists() && appsDir.isDirectory()) {
        File[] files = appsDir.listFiles();
        if (files != null) {
          List<File> abletonApps = new ArrayList<>();
          for (File f : files) {
            String name = f.getName();
            if (name.startsWith("Ableton Live") && name.endsWith(".app")) {
              abletonApps.add(f);
            }
          }
          if (!abletonApps.isEmpty()) {
            // Sort descending: highest version first (e.g. Live 12 Suite > Live 9 Suite)
            abletonApps.sort((a, b) -> compareAbletonVersionNames(a.getName(), b.getName()));
            File detected = abletonApps.get(0);
            LOG.info(
                "[AbletonAssetResolver] Dynamically detected macOS Ableton: " + detected.getName());
            return detected.getAbsolutePath() + "/Contents/App-Resources/";
          }
        }
      }
      return "/Applications/Ableton Live 12 Suite.app/Contents/App-Resources/";
    }
  }

  /**
   * Compares two Ableton application or folder names in descending version order so numeric major
   * and minor versions win over lexicographical comparison (e.g. {@code "Ableton Live 12
   * Suite.app"} sorts before {@code "Ableton Live 9 Suite.app"}).
   */
  static int compareAbletonVersionNames(String nameA, String nameB) {
    int[] verA = parseLiveVersion(nameA);
    int[] verB = parseLiveVersion(nameB);
    if (verA[0] != verB[0]) {
      return Integer.compare(verB[0], verA[0]);
    }
    if (verA[1] != verB[1]) {
      return Integer.compare(verB[1], verA[1]);
    }
    return nameB.compareToIgnoreCase(nameA);
  }

  private static int[] parseLiveVersion(String name) {
    if (name == null) return new int[] {0, 0};
    Matcher m = LIVE_VERSION_PATTERN.matcher(name);
    if (m.find()) {
      int major = Integer.parseInt(m.group(1));
      int minor = m.group(2) != null ? Integer.parseInt(m.group(2)) : 0;
      return new int[] {major, minor};
    }
    return new int[] {0, 0};
  }

  /**
   * Returns a highly intelligent default folder to open the file chooser for importing Ableton
   * sets. Checks both Documents and Music folders and falls back to user home.
   */
  public static File getDefaultImportDir() {
    File docsAbleton = new File(HOME, "Documents/Ableton");
    if (docsAbleton.exists() && docsAbleton.isDirectory()) {
      return docsAbleton;
    }
    File musicAbleton = new File(HOME, "Music/Ableton");
    if (musicAbleton.exists() && musicAbleton.isDirectory()) {
      return musicAbleton;
    }
    return new File(HOME);
  }
}
