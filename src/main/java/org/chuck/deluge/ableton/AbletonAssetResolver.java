package org.chuck.deluge.ableton;

import java.io.File;
import java.util.logging.Logger;

/**
 * Resolves Ableton sample references to valid local file paths on macOS.
 * Integrates Factory Packs, the Core Library, the User Library, and absolute hints.
 */
public class AbletonAssetResolver {
  private static final Logger LOG = Logger.getLogger(AbletonAssetResolver.class.getName());

  private static final String PACKS_DIR = "/Users/ludo/Music/Ableton/Factory Packs/";
  private static final String USER_LIB_DIR = "/Users/ludo/Music/Ableton/User Library/";
  private static final String CORE_LIB_DIR = "/Applications/Ableton Live 12 Suite.app/Contents/App-Resources/Core Library/";
  private static final String BUILTIN_DIR = "/Applications/Ableton Live 12 Suite.app/Contents/App-Resources/Builtin/";

  /**
   * Resolve an Ableton sample path to a valid local file on macOS.
   *
   * @param livePackName the name of the Live Pack (e.g. "Sequencers"), or null/empty if not in a pack
   * @param relativePath the relative path of the sample (e.g. "Racks/Drum Racks/Samples/Kick/Kick Fat Click.aif")
   * @param absolutePathHint the absolute path hint stored in the ALS (e.g. "/Volumes/data/tmp/...")
   * @return the resolved File, or null if it cannot be found
   */
  public static File resolveSamplePath(String livePackName, String relativePath, String absolutePathHint) {
    // 1. Check if the absolute path hint exists locally
    if (absolutePathHint != null && !absolutePathHint.isBlank()) {
      File file = new File(absolutePathHint);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    // 2. If it's part of a Factory Pack, resolve it
    if (livePackName != null && !livePackName.isBlank() && relativePath != null && !relativePath.isBlank()) {
      File file = new File(PACKS_DIR + livePackName + "/" + relativePath);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    // 3. Check Core Library
    if (relativePath != null && !relativePath.isBlank()) {
      File file = new File(CORE_LIB_DIR + relativePath);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    // 4. Check Builtin Library
    if (relativePath != null && !relativePath.isBlank()) {
      File file = new File(BUILTIN_DIR + relativePath);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    // 5. Check User Library
    if (relativePath != null && !relativePath.isBlank()) {
      File file = new File(USER_LIB_DIR + relativePath);
      if (file.exists() && file.isFile()) {
        return file;
      }
    }

    LOG.warning("Unable to resolve Ableton sample: pack=" + livePackName + ", rel=" + relativePath + ", absHint=" + absolutePathHint);
    return null;
  }
}
