package org.deluge.ui;

import java.awt.Color;
import org.deluge.firmware2.Functions;

/**
 * Faithful port of the Deluge {@code RGB::fromHue} / {@code fromHuePastel} (gui/colour/rgb.cpp) —
 * the palette the hardware uses for clip / note / arranger colours. A clip's colour is derived from
 * its {@code colourOffset}: {@code fromHue(colourOffset * -8 / 3)} (instrument_clip.cpp:1235,
 * audio_clip.cpp:1251), so distinct offsets yield the distinct pastels seen on hardware.
 */
public final class DelugeColour {
  private DelugeColour() {}

  private static int channelDarkness(int c, int hue) {
    if (c == 0) {
      return (hue < 64) ? hue : Math.min(64, Math.abs(192 - hue));
    }
    return Math.min(64, Math.abs(c * 64 - hue));
  }

  /** RGB::fromHue(hue) — rgb.cpp:4-31. */
  public static Color fromHue(int hue) {
    hue = ((hue + 1920) & 0xFFFF) % 192;
    int[] rgb = new int[3];
    for (int c = 0; c < 3; c++) {
      int d = channelDarkness(c, hue);
      if (d < 64) {
        int phase = ((d << 3) + 256) & 1023; // (channelDarkness<<3 + bit_value<9>) & bitmask<10>
        long v = (long) Functions.getSine(phase, 10) + 0x80000000L; // + median_value<uint32_t>
        rgb[c] = (int) (v >> 24);
      }
    }
    return new Color(rgb[0], rgb[1], rgb[2]);
  }

  /** RGB::fromHuePastel(hue) — rgb.cpp:35-. */
  public static Color fromHuePastel(int hue) {
    final long kMaxPastel = 230;
    hue = ((hue + 1920) & 0xFFFF) % 192;
    int[] rgb = new int[3];
    for (int c = 0; c < 3; c++) {
      int d = channelDarkness(c, hue);
      if (d < 64) {
        int phase = ((d << 3) + 256) & 1023;
        long basic = ((long) Functions.getSine(phase, 10) + 0x80000000L) & 0xFFFFFFFFL;
        long flipped = 0xFFFFFFFFL - basic;
        long flippedScaled = (flipped >> 8) * kMaxPastel;
        rgb[c] = (int) (((0xFFFFFFFFL - flippedScaled) >> 24) & 0xFF);
      }
    }
    return new Color(rgb[0], rgb[1], rgb[2]);
  }

  /** The clip/track colour the Deluge shows for a given {@code colourOffset} (note-row hue). */
  public static Color clipColour(int colourOffset) {
    return fromHue(colourOffset * -8 / 3);
  }

  /**
   * The Deluge {@code defaultClipSectionColours} palette (session.cpp:62). Arranger clip instances
   * and the session/arranger section sidebar are coloured by their clip's SECTION via this table —
   * NOT by the clip's own colourOffset (clip_instance.cpp:31-37 {@code
   * defaultClipSectionColours[clip->section]}). Section 0 is {@code fromHue(102)} (light blue).
   */
  public static Color sectionColour(int section) {
    if (section < 0) return monochrome(128); // arrangement-only clips -> grey (monochrome(128))
    return switch (section % 16) {
      case 0 -> fromHue(102); // bright light blue
      case 1 -> fromHue(168); // bright dark pink
      case 2 -> fromHue(24); // bright light orange
      case 3 -> fromHue(84); // bright turquoise
      case 4 -> new Color(255, 0, 0); // red
      case 5 -> new Color(128, 255, 0); // lime
      case 6 -> new Color(0, 0, 255); // blue
      case 7 -> fromHue(12); // bright dark orange
      case 8 -> fromHue(147); // bright purple
      case 9 -> new Color(255, 255, 0); // yellow
      case 10 -> new Color(0, 255, 0); // green
      case 11 -> fromHue(157); // bright magenta
      case 12 -> new Color(51, 109, 145); // pastel blue
      case 13 -> new Color(255, 128, 128); // pink_full
      case 14 -> new Color(221, 72, 13); // pastel orange
      default -> new Color(85, 182, 72); // pastel green
    };
  }

  /** RGB::monochrome(v) — equal channels (used for arrangement-only clip instances). */
  public static Color monochrome(int v) {
    return new Color(v, v, v);
  }

  /** session_view.cpp:1655 — the hue step the auto-assigned output colours rotate by. */
  public static final double COLOUR_STEP = 22.5882352941;

  /**
   * The Deluge SESSION/SONG-view colour of a track (output) — session_view.cpp:3356-3361. Each pad
   * is {@code fromHue(output->colour)}. When {@code output->colour == 0} (unset), the hardware
   * assigns a rotating hue in render order via a static {@code lastColour} that advances by {@code
   * colourStep}; the i-th such output resolves to {@code fmod(1 + i*colourStep, 192)}. So distinct
   * {@code colour="0"} instruments (as in most songs) still get distinct pastels.
   *
   * @param storedColour the instrument's stored {@code colour} (0-191), 0 = auto-assign
   * @param autoIndex the track's position among auto-assigned (colour==0) tracks
   */
  public static Color sessionColour(int storedColour, int autoIndex) {
    int colour = storedColour;
    if (colour == 0) {
      colour = (int) ((1.0 + autoIndex * COLOUR_STEP) % 192.0);
    }
    return fromHue(colour);
  }
}
