package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import org.deluge.project.PreferencesManager.GridColorTheme;
import org.junit.jupiter.api.Test;

/**
 * Pins the CLIP step-pad colour matrix (theme × pad state) that used to be inlined and untested in
 * {@code SwingGridPanel.getThemeColor}. The HARDWARE theme is the parity target.
 */
class ClipCellColourTest {

  private static final Color TRACK = DelugeColour.clipColour(-60); // an arbitrary pitched colour

  @Test
  void hardware_activeIsTrackColour_rootIsCyan_outOfScaleIsBlack() {
    assertEquals(
        TRACK,
        ClipCellColour.resolve(GridColorTheme.HARDWARE, TRACK, true, true, false),
        "active = track colour verbatim");
    assertEquals(
        new Color(0x00, 0xd2, 0xff),
        ClipCellColour.resolve(GridColorTheme.HARDWARE, TRACK, false, true, true),
        "scale root = neon cyan");
    assertEquals(
        TRACK,
        ClipCellColour.resolve(GridColorTheme.HARDWARE, TRACK, false, true, false),
        "in-scale = track colour (pad dims it)");
    assertEquals(
        Color.BLACK,
        ClipCellColour.resolve(GridColorTheme.HARDWARE, TRACK, false, false, false),
        "out-of-scale = black/unlit");
  }

  @Test
  void nullTrackColour_fallsBackToGreen_whenActive() {
    assertEquals(
        Color.GREEN, ClipCellColour.resolve(GridColorTheme.HARDWARE, null, true, true, false));
  }

  @Test
  void monochrome_matrix() {
    assertEquals(
        Color.WHITE, ClipCellColour.resolve(GridColorTheme.MONOCHROME, TRACK, true, true, false));
    assertEquals(
        new Color(0xaa, 0xaa, 0xaa),
        ClipCellColour.resolve(GridColorTheme.MONOCHROME, TRACK, false, true, true));
    assertEquals(
        new Color(0x3e, 0x3e, 0x3e),
        ClipCellColour.resolve(GridColorTheme.MONOCHROME, TRACK, false, true, false));
    assertEquals(
        new Color(0x15, 0x15, 0x15),
        ClipCellColour.resolve(GridColorTheme.MONOCHROME, TRACK, false, false, false));
  }

  @Test
  void steel_matrix() {
    assertEquals(
        new Color(0x00, 0xb0, 0xff),
        ClipCellColour.resolve(GridColorTheme.STEEL, TRACK, true, true, false));
    assertEquals(
        new Color(0xff, 0xab, 0x40),
        ClipCellColour.resolve(GridColorTheme.STEEL, TRACK, false, true, true));
    assertEquals(
        new Color(0x1e, 0x22, 0x27),
        ClipCellColour.resolve(GridColorTheme.STEEL, TRACK, false, false, false));
  }

  @Test
  void neon_activeIsFullySaturatedTrackHue() {
    float[] hsb = Color.RGBtoHSB(TRACK.getRed(), TRACK.getGreen(), TRACK.getBlue(), null);
    assertEquals(
        Color.getHSBColor(hsb[0], 1.0f, 1.0f),
        ClipCellColour.resolve(GridColorTheme.NEON, TRACK, true, true, false));
    assertEquals(
        new Color(0xff, 0x00, 0x7f),
        ClipCellColour.resolve(GridColorTheme.NEON, TRACK, false, true, true));
    assertEquals(
        new Color(0x18, 0x10, 0x22),
        ClipCellColour.resolve(GridColorTheme.NEON, TRACK, false, false, false));
  }
}
