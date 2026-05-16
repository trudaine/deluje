package org.chuck.deluge.firmware.hid.pic;

import org.chuck.deluge.project.PreferencesManager;

/**
 * Single source of truth for display grid dimensions.
 *
 * <p>The real Deluge hardware has a fixed 16×8 pad grid. This class extends that to support
 * multiple grid sizes for the Swing UI, driven by {@link PreferencesManager.GridMode}.
 *
 * <p>All display-layer classes ({@link PIC}, {@link org.chuck.deluge.firmware.hid.MatrixDriver
 * MatrixDriver}, {@link org.chuck.deluge.firmware.hid.PadLEDs PadLEDs},
 * {@link SwingPicTransport}) reference these values rather than declaring their own constants.
 */
public final class GridConfig {

  private GridConfig() {}

  /** Default (hardware-real) display width in pads. */
  public static final int kHardwareWidth = 16;
  /** Default (hardware-real) display height in pads. */
  public static final int kHardwareHeight = 8;
  /** Fixed sidebar width — always 2 for the real Deluge and Swing alike. */
  public static final int kSideBarWidth = 2;

  // ── Current (possibly overridden) dimensions ──

  private static int displayWidth = kHardwareWidth;
  private static int displayHeight = kHardwareHeight;

  /** Get the current display width (main pad area). */
  public static int getDisplayWidth() {
    return displayWidth;
  }

  /** Get the current display height (main pad area). */
  public static int getDisplayHeight() {
    return displayHeight;
  }

  /** Total width including side bar. */
  public static int getTotalWidth() {
    return displayWidth + kSideBarWidth;
  }

  /** Total height (same as display height — side bar is only horizontal). */
  public static int getTotalHeight() {
    return displayHeight;
  }

  /** Total main pad count. */
  public static int getMainPadCount() {
    return displayWidth * displayHeight;
  }

  /** Number of column-pairs needed to cover the display width. */
  public static int getColumnPairCount() {
    return (displayWidth + 1) / 2 + 1; // main pairs + 1 side pair
  }

  /**
   * Configure dimensions from a {@link PreferencesManager.GridMode}.
   *
   * <p>GridMode defines {@code rows} (height) and {@code columns} (width). Calling this re-sizes
   * the display to match. Default is 16×8 (hardware real).
   */
  public static void configure(PreferencesManager.GridMode mode) {
    displayWidth = mode.columns;
    displayHeight = mode.rows;
  }

  /** Reset dimensions to hardware defaults (16×8). */
  public static void resetToHardware() {
    displayWidth = kHardwareWidth;
    displayHeight = kHardwareHeight;
  }
}
