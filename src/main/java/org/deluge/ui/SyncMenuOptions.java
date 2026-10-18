package org.deluge.ui;

/**
 * The C sync-level menu options (model/sync.h:10-33, model/sync.cpp:30-55): 28 entries — OFF, EVEN
 * levels 1..9, TRIPLET levels 1..9, DOTTED levels 1..9. Option {@code v} decodes as {@code
 * syncValueToSyncType} / {@code syncValueToSyncLevel}; levels are the song-file sync levels (1 =
 * whole note … 9 = 1/256 at the default input tick magnitude). Types use the Java model encoding (0
 * even, 1 triplet, 2 dotted).
 */
final class SyncMenuOptions {
  private SyncMenuOptions() {}

  // C model/sync.h:12-13 — SYNC_TYPE_TRIPLET = 10, SYNC_TYPE_DOTTED = 19 (menu option bases).
  static final int TRIPLET_BASE = 10;
  static final int DOTTED_BASE = 19;
  // C model/sync.h:32 — SYNC_LEVEL_256TH = 9.
  static final int MAX_LEVEL = 9;
  static final int NUM_OPTIONS = DOTTED_BASE + MAX_LEVEL;

  /** C syncValueToSyncType (sync.cpp:30-40), in the Java 0/1/2 encoding. */
  static int typeFor(int option) {
    if (option < TRIPLET_BASE) return 0;
    if (option < DOTTED_BASE) return 1;
    return 2;
  }

  /** C syncValueToSyncLevel (sync.cpp:42-52). */
  static int levelFor(int option) {
    if (option < TRIPLET_BASE) return Math.max(0, option);
    if (option < DOTTED_BASE) return option - TRIPLET_BASE + 1;
    return option - DOTTED_BASE + 1;
  }

  /** C syncTypeAndLevelToMenuOption: the inverse of {@link #typeFor} / {@link #levelFor}. */
  static int optionFor(int level, int type) {
    if (level <= 0) return 0;
    int l = Math.min(MAX_LEVEL, level);
    return switch (type) {
      case 1 -> TRIPLET_BASE + l - 1;
      case 2 -> DOTTED_BASE + l - 1;
      default -> l;
    };
  }

  /**
   * Labels at the default input tick magnitude. The C names depend on the song's tick magnitude
   * (syncValueToString, sync.cpp:55-83); this UI shows the default-magnitude names.
   */
  static String[] labels() {
    String[] out = new String[NUM_OPTIONS];
    for (int v = 0; v < NUM_OPTIONS; v++) {
      int level = levelFor(v);
      if (v == 0) {
        out[v] = "OFF";
        continue;
      }
      String base = "1/" + (1 << (level - 1));
      out[v] =
          switch (typeFor(v)) {
            case 1 -> base + "T";
            case 2 -> base + "D";
            default -> base;
          };
    }
    return out;
  }
}
