package org.deluge.ui;

import java.awt.Color;
import org.deluge.project.PreferencesManager.GridColorTheme;

/**
 * Pure colour combiner for a CLIP-view step pad: given the row's track colour and the pad's state
 * (active / scale-root / in-scale / out-of-scale) under the active {@link GridColorTheme}, returns
 * the final base LED colour.
 *
 * <p>This is the parity-critical branch matrix formerly inlined in {@code
 * SwingGridPanel#getThemeColor}. The CLIP cell as a whole is richer than the shared {@link PadCell}
 * contract ({@code colour, active, text}) — it also drives the scale-root ring, tail, beat marker
 * and velocity intensity, which stay in the renderer as decoration hints. But the <em>colour</em>
 * decision is self-contained and was untested; extracting it here lets the full theme × state
 * matrix be pinned by {@code ClipCellColourTest}.
 *
 * <p>HARDWARE theme is the parity target: an active pad = the track/pitch colour verbatim, the
 * scale root = neon cyan, in-scale = the track colour (the pad dims it), and out-of-scale = black
 * (unlit), matching the hardware where non-scale rows are completely dark.
 */
final class ClipCellColour {
  private ClipCellColour() {}

  /**
   * @param theme the active grid colour theme
   * @param trackColor the row's resolved colour (per-pitch {@code fromHue} for pitched clips, else
   *     the track base colour); null falls back to green
   * @param active whether the pad has an active step
   * @param inScale whether the row's pitch is in the current scale (always true for non-scale/kit)
   * @param isRoot whether the row's pitch is the scale root
   * @return the final base colour to hand the pad
   */
  static Color resolve(
      GridColorTheme theme, Color trackColor, boolean active, boolean inScale, boolean isRoot) {
    Color base = trackColor != null ? trackColor : Color.GREEN;
    switch (theme) {
      case NEON:
        if (active) {
          float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
          return Color.getHSBColor(hsb[0], 1.0f, 1.0f);
        } else if (isRoot) {
          return new Color(0xff, 0x00, 0x7f); // hot neon pink root
        } else if (inScale) {
          float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
          return Color.getHSBColor(hsb[0], 1.0f, 0.25f);
        } else {
          return new Color(0x18, 0x10, 0x22); // deep dark purple-gray
        }
      case MONOCHROME:
        if (active) {
          return Color.WHITE;
        } else if (isRoot) {
          return new Color(0xaa, 0xaa, 0xaa); // medium gray root
        } else if (inScale) {
          return new Color(0x3e, 0x3e, 0x3e); // dark gray in-scale
        } else {
          return new Color(0x15, 0x15, 0x15); // very dark gray out-of-scale
        }
      case STEEL:
        if (active) {
          return new Color(0x00, 0xb0, 0xff); // electric slate blue
        } else if (isRoot) {
          return new Color(0xff, 0xab, 0x40); // bright copper orange root
        } else if (inScale) {
          return new Color(0x00, 0x3b, 0x5c); // dark steel-blue in-scale
        } else {
          return new Color(0x1e, 0x22, 0x27); // dark metal out-of-scale
        }
      case HARDWARE:
      default:
        if (active) {
          return base;
        } else if (isRoot) {
          return new Color(0x00, 0xd2, 0xff); // neon cyan root
        } else if (inScale) {
          return base; // DelugePadButton dims it automatically
        } else {
          return Color.BLACK; // Out-of-scale notes are completely black/unlit on hardware!
        }
    }
  }
}
