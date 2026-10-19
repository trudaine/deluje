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

  /**
   * Written by this app's arp writers next to the C keys. The C ignores unknown attributes; the
   * parser uses it to tell a current save (C-encoded syncLevel) from an older save of this app,
   * which carried the model's note denominator in syncLevel.
   */
  public static final String FORMAT_ATTR = "arpSyncFormat";

  public static final String FORMAT_C = "c";

  /**
   * An older save of this app: it wrote the "active" attribute (never a C key) and not {@link
   * #FORMAT_ATTR}. Its syncLevel is the model's denominator, and numOctaves may be stale next to
   * the edited &lt;octaves&gt; tag.
   */
  public static boolean isLegacyAppArp(org.w3c.dom.Element arpEl) {
    return arpEl.hasAttribute("active") && !FORMAT_C.equals(arpEl.getAttribute(FORMAT_ATTR));
  }

  /**
   * C SyncType as a file value (model/sync.h:11-13: EVEN 0, TRIPLET 10, DOTTED 19, written raw by
   * writeSyncTypeToFile, storage_manager.h:148-150) → the model's 0 / 1 / 2.
   */
  public static int syncTypeFromFile(int fileValue) {
    if (fileValue == 10) return 1;
    if (fileValue == 19) return 2;
    return 0;
  }

  /** The model's 0 even / 1 triplet / 2 dotted → the C SyncType file value. */
  public static int syncTypeToFile(int modelValue) {
    return switch (modelValue) {
      case 1 -> 10;
      case 2 -> 19;
      default -> 0;
    };
  }

  /** C FlashStorage::defaultMagnitude (flash_storage.cpp:350), used for a standalone preset. */
  public static final int DEFAULT_INPUT_TICK_MAGNITUDE = 2;

  /**
   * C stringToArpNoteMode (functions.cpp): unknown strings are UP. Also accepts this app's own
   * panel tokens ("PLAY", "UPDN", …), which its DelugeXmlExporter used to write verbatim; the C
   * never writes them, so firmware files decode exactly as the C does.
   */
  public static String noteModeFromFile(String s) {
    if (s == null) return "UP";
    if (java.util.List.of("UP", "DOWN", "UPDN", "RAND", "WLK1", "WLK2", "WLK3", "PLAY", "PATT")
        .contains(s)) {
      return s;
    }
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

  /** C stringToArpOctaveMode (functions.cpp); also this app's legacy tokens, as above. */
  public static String octaveModeFromFile(String s) {
    if (s == null) return "UP";
    if (java.util.List.of("UP", "DOWN", "UPDN", "ALT", "RAND").contains(s)) return s;
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
   * for the constructor default {@code 8 - magnitude}. 0 stays 0 (not synced). A period longer than
   * a whole note (possible at magnitude 2 and up) is returned negated: -N is N whole notes.
   */
  public static int syncDenominatorFromFile(int fileValue, int inputTickMagnitude) {
    int level =
        DelugeXmlUtil.convertSyncLevelFromFileValueToInternalValue(fileValue, inputTickMagnitude);
    if (level == 0) return 0;
    int exp = level + inputTickMagnitude - 4;
    return exp >= 0 ? 1 << exp : -(1 << -exp);
  }

  /** C internal SyncLevel (1..9, 0 = none) → model denominator, as in the function above. */
  public static int syncDenominatorFromInternal(int level, int inputTickMagnitude) {
    if (level <= 0) return 0;
    int exp = level + inputTickMagnitude - 4;
    return exp >= 0 ? 1 << exp : -(1 << -exp);
  }

  /** Model denominator → the C internal SyncLevel (1..9, 0 = none), the inverse of the above. */
  public static int internalSyncLevelFromDenominator(int denominator, int inputTickMagnitude) {
    if (denominator == 0) return 0;
    int log2 =
        denominator > 0
            ? 31 - Integer.numberOfLeadingZeros(denominator)
            : -(31 - Integer.numberOfLeadingZeros(-denominator)); // -N: N whole notes
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

  /**
   * Applies one signed Q31 {@code UNPATCHED_ARP_*} parameter value from {@code <defaultParams>} or
   * {@code <soundParams>} onto an {@link org.deluge.model.ArpModel} (C
   * mod_controllable_audio.cpp:113-127, arpeggiator.cpp:1968-1983, unpatched_param.cpp:36-38).
   */
  public static org.deluge.model.ArpModel applyUnpatchedKnobToArp(
      org.deluge.model.ArpModel arp, int paramId, int rawQ31) {
    if (arp == null) return null;
    int menuVal =
        org.deluge.firmware2.Functions.computeCurrentValueForUnsignedMenuItem(
            rawQ31 + Integer.MIN_VALUE);
    float normVal = menuVal / 50.0f;
    var b = arp.toBuilder();
    return switch (paramId) {
      case org.deluge.firmware2.Param.UNPATCHED_ARP_GATE ->
          b.gate(rawQ31 == 0 ? 0.5f : DelugeHexMapper.q31ToUnipolarFloatUnified(rawQ31)).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RHYTHM -> b.rhythmIndex(menuVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SEQUENCE_LENGTH -> b.seqLength(menuVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_CHORD_POLYPHONY ->
          b.chordPolyphony(Math.round((menuVal / 50.0f) * 8.0f)).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RATCHET_AMOUNT ->
          b.ratchetAmount(Math.round((menuVal / 50.0f) * 4.0f)).build();
      case org.deluge.firmware2.Param.UNPATCHED_NOTE_PROBABILITY ->
          b.noteProbability(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_BASS_PROBABILITY ->
          b.bassProbability(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SWAP_PROBABILITY ->
          b.swapProbability(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_GLIDE_PROBABILITY ->
          b.glideProbability(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_REVERSE_PROBABILITY ->
          b.reverseProbability(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_CHORD_PROBABILITY ->
          b.chordProbability(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RATCHET_PROBABILITY ->
          b.ratchetProbability(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SPREAD_GATE -> b.gateSpread(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SPREAD_OCTAVE ->
          b.octaveSpread(normVal).build();
      case org.deluge.firmware2.Param.UNPATCHED_SPREAD_VELOCITY -> b.velSpread(normVal).build();
      default -> arp;
    };
  }

  /**
   * Maps an {@link org.deluge.model.ArpModel} field back to its signed Q31 {@code UNPATCHED_ARP_*}
   * parameter value (C unpatched_param.cpp:84-86, value_scaling.cpp:22-33).
   */
  public static int unpatchedQ31FromArp(org.deluge.model.ArpModel arp, int paramId) {
    if (arp == null) return org.deluge.firmware2.Arpeggiator.getUnpatchedArpDefault(paramId);
    return switch (paramId) {
      case org.deluge.firmware2.Param.UNPATCHED_ARP_GATE ->
          Math.abs(arp.gate() - 0.5f) < 1e-6f
              ? 0
              : DelugeHexMapper.unipolarFloatToQ31Unified(arp.gate());
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RHYTHM ->
          org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(
              Math.max(0, Math.min(50, arp.rhythmIndex())));
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SEQUENCE_LENGTH ->
          org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(
              Math.max(0, Math.min(50, arp.seqLength())));
      case org.deluge.firmware2.Param.UNPATCHED_ARP_CHORD_POLYPHONY ->
          org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(
              Math.round((Math.max(0, Math.min(8, arp.chordPolyphony())) / 8.0f) * 50.0f));
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RATCHET_AMOUNT ->
          org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(
              Math.round((Math.max(0, Math.min(4, arp.ratchetAmount())) / 4.0f) * 50.0f));
      case org.deluge.firmware2.Param.UNPATCHED_NOTE_PROBABILITY ->
          menuQ31FromNorm(arp.noteProbability());
      case org.deluge.firmware2.Param.UNPATCHED_ARP_BASS_PROBABILITY ->
          menuQ31FromNorm(arp.bassProbability());
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SWAP_PROBABILITY ->
          menuQ31FromNorm(arp.swapProbability());
      case org.deluge.firmware2.Param.UNPATCHED_ARP_GLIDE_PROBABILITY ->
          menuQ31FromNorm(arp.glideProbability());
      case org.deluge.firmware2.Param.UNPATCHED_REVERSE_PROBABILITY ->
          menuQ31FromNorm(arp.reverseProbability());
      case org.deluge.firmware2.Param.UNPATCHED_ARP_CHORD_PROBABILITY ->
          menuQ31FromNorm(arp.chordProbability());
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RATCHET_PROBABILITY ->
          menuQ31FromNorm(arp.ratchetProbability());
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SPREAD_GATE ->
          menuQ31FromNorm(arp.gateSpread());
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SPREAD_OCTAVE ->
          menuQ31FromNorm(arp.octaveSpread());
      case org.deluge.firmware2.Param.UNPATCHED_SPREAD_VELOCITY -> menuQ31FromNorm(arp.velSpread());
      default -> org.deluge.firmware2.Arpeggiator.getUnpatchedArpDefault(paramId);
    };
  }

  private static int menuQ31FromNorm(float norm) {
    float clamped = Math.max(0f, Math.min(1f, norm));
    return org.deluge.firmware2.Functions.computeFinalValueForStandardMenuItem(
        Math.round(clamped * 50.0f));
  }

  /**
   * Returns true if the {@link org.deluge.model.ArpModel} field corresponding to {@code paramId}
   * differs between {@code oldArp} and {@code newArp}.
   */
  public static boolean isUnpatchedArpFieldChanged(
      org.deluge.model.ArpModel oldArp, org.deluge.model.ArpModel newArp, int paramId) {
    if (oldArp == newArp) return false;
    if (oldArp == null || newArp == null) return true;
    return switch (paramId) {
      case org.deluge.firmware2.Param.UNPATCHED_ARP_GATE ->
          Float.compare(oldArp.gate(), newArp.gate()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RHYTHM ->
          oldArp.rhythmIndex() != newArp.rhythmIndex();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SEQUENCE_LENGTH ->
          oldArp.seqLength() != newArp.seqLength();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_CHORD_POLYPHONY ->
          oldArp.chordPolyphony() != newArp.chordPolyphony();
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RATCHET_AMOUNT ->
          oldArp.ratchetAmount() != newArp.ratchetAmount();
      case org.deluge.firmware2.Param.UNPATCHED_NOTE_PROBABILITY ->
          Float.compare(oldArp.noteProbability(), newArp.noteProbability()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_ARP_BASS_PROBABILITY ->
          Float.compare(oldArp.bassProbability(), newArp.bassProbability()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SWAP_PROBABILITY ->
          Float.compare(oldArp.swapProbability(), newArp.swapProbability()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_ARP_GLIDE_PROBABILITY ->
          Float.compare(oldArp.glideProbability(), newArp.glideProbability()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_REVERSE_PROBABILITY ->
          Float.compare(oldArp.reverseProbability(), newArp.reverseProbability()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_ARP_CHORD_PROBABILITY ->
          Float.compare(oldArp.chordProbability(), newArp.chordProbability()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_ARP_RATCHET_PROBABILITY ->
          Float.compare(oldArp.ratchetProbability(), newArp.ratchetProbability()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SPREAD_GATE ->
          Float.compare(oldArp.gateSpread(), newArp.gateSpread()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_ARP_SPREAD_OCTAVE ->
          Float.compare(oldArp.octaveSpread(), newArp.octaveSpread()) != 0;
      case org.deluge.firmware2.Param.UNPATCHED_SPREAD_VELOCITY ->
          Float.compare(oldArp.velSpread(), newArp.velSpread()) != 0;
      default -> false;
    };
  }
}
