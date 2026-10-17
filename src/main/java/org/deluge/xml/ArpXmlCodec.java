package org.deluge.xml;

/**
 * Conversions between the C firmware's {@code <arpeggiator>} file values and {@link
 * org.deluge.model.ArpModel}'s own encoding.
 *
 * <p>The model keeps the ARP panel's abbreviations for note/octave mode ({@code UPDN}, {@code
 * PLAY}, {@code ALT}, ...) and the sync level as a note-division denominator (16 = 16th, 0 = not
 * synced). The file carries the C strings from {@code util/functions.cpp} and the C {@code
 * SyncLevel} (model/sync.h:22-33) offset by the song's input tick magnitude (song.cpp:5842-5872).
 * Both directions live here so the parser and every writer agree.
 */
public final class ArpXmlCodec {
  private ArpXmlCodec() {}

  /** C FlashStorage::defaultMagnitude (flash_storage.cpp:350), used for a standalone preset. */
  public static final int DEFAULT_INPUT_TICK_MAGNITUDE = 2;

  /** C stringToArpNoteMode (functions.cpp): unknown strings are UP. */
  public static String noteModeFromFile(String s) {
    if (s == null) return "UP";
    return switch (s) {
      case "down" -> "DOWN";
      case "upDown" -> "UPDN";
      case "asPlayed" -> "PLAY";
      case "walk1" -> "WLK1";
      case "walk2" -> "WLK2";
      case "walk3" -> "WLK3";
      case "pattern" -> "PATT";
      case "random" -> "RAND";
      default -> "UP";
    };
  }

  /** C arpNoteModeToString (functions.cpp). */
  public static String noteModeToFile(String m) {
    if (m == null) return "up";
    return switch (m) {
      case "DOWN" -> "down";
      case "UPDN" -> "upDown";
      case "PLAY" -> "asPlayed";
      case "WLK1" -> "walk1";
      case "WLK2" -> "walk2";
      case "WLK3" -> "walk3";
      case "PATT" -> "pattern";
      case "RAND" -> "random";
      default -> "up";
    };
  }

  /** C stringToArpOctaveMode (functions.cpp): unknown strings are UP. */
  public static String octaveModeFromFile(String s) {
    if (s == null) return "UP";
    return switch (s) {
      case "down" -> "DOWN";
      case "upDown" -> "UPDN";
      case "alt" -> "ALT";
      case "random" -> "RAND";
      default -> "UP";
    };
  }

  /** C arpOctaveModeToString (functions.cpp). */
  public static String octaveModeToFile(String m) {
    if (m == null) return "up";
    return switch (m) {
      case "DOWN" -> "down";
      case "UPDN" -> "upDown";
      case "ALT" -> "alt";
      case "RAND" -> "random";
      default -> "up";
    };
  }

  /**
   * C stringToArpMpeModSource (functions.cpp): "z" = aftertouch, "y" = MPE Y, else off. The model
   * stores 0 = off, 1 = aftertouch, 2 = MPE Y.
   */
  public static int mpeVelocityFromFile(String s) {
    if ("z".equals(s)) return 1;
    if ("y".equals(s)) return 2;
    return 0;
  }

  /** C arpMpeModSourceToString (functions.cpp). */
  public static String mpeVelocityToFile(int v) {
    return switch (v) {
      case 1 -> "z";
      case 2 -> "y";
      default -> "off";
    };
  }

  /**
   * The legacy pre-community-1.1.0 {@code mode} key (C stringToOldArpMode): up / down / both /
   * random turn the arp on, anything else is off.
   */
  public static boolean legacyModeIsOn(String s) {
    return "up".equals(s) || "down".equals(s) || "both".equals(s) || "random".equals(s);
  }

  /** C oldModeToArpNoteMode: DOWN → DOWN, RANDOM → RANDOM, otherwise UP. */
  public static String legacyModeToNoteMode(String s) {
    if ("down".equals(s)) return "DOWN";
    if ("random".equals(s)) return "RAND";
    return "UP";
  }

  /** C oldModeToArpOctaveMode: DOWN → DOWN, BOTH → ALTERNATE, RANDOM → RANDOM, otherwise UP. */
  public static String legacyModeToOctaveMode(String s) {
    if ("down".equals(s)) return "DOWN";
    if ("both".equals(s)) return "ALT";
    if ("random".equals(s)) return "RAND";
    return "UP";
  }

  /** C ArpeggiatorSettings constructor default: internal 8 - magnitude, a 16th (:51-54). */
  public static final int DEFAULT_SYNC_DENOMINATOR = 16;

  /**
   * File sync level → model denominator. C Song::convertSyncLevelFromFileValueToInternalValue
   * (song.cpp:5842-5859) gives the internal level L, clamped to 1..9. The arp's period is {@code 3
   * << (9 - L)} timer ticks (arpeggiator.cpp ticksPerPeriod), and a quarter note is {@code 24 <<
   * magnitude} timer ticks (song.h BPM formula), so the note is 1 / 2^(L + magnitude - 4) — a 16th
   * for the constructor default {@code 8 - magnitude}. 0 stays 0 (not synced). A level slower than
   * a whole note clamps to 1.
   */
  public static int syncDenominatorFromFile(int fileValue, int inputTickMagnitude) {
    int level =
        DelugeXmlUtil.convertSyncLevelFromFileValueToInternalValue(fileValue, inputTickMagnitude);
    if (level == 0) return 0;
    int exp = level + inputTickMagnitude - 4;
    return exp <= 0 ? 1 : 1 << exp;
  }

  /** Model denominator → the C internal SyncLevel (1..9, 0 = none), the inverse of the above. */
  public static int internalSyncLevelFromDenominator(int denominator, int inputTickMagnitude) {
    if (denominator <= 0) return 0;
    int log2 = 31 - Integer.numberOfLeadingZeros(denominator);
    return Math.max(1, Math.min(9, log2 + 4 - inputTickMagnitude));
  }

  /**
   * Model denominator → file sync level: {@link #internalSyncLevelFromDenominator}, then C
   * Song::convertSyncLevelFromInternalValueToFileValue (song.cpp:5861-5872).
   */
  public static int syncFileValueFromDenominator(int denominator, int inputTickMagnitude) {
    return DelugeXmlUtil.convertSyncLevelFromInternalValueToFileValue(
        internalSyncLevelFromDenominator(denominator, inputTickMagnitude), inputTickMagnitude);
  }
}
