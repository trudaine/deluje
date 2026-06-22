package org.deluge.hid;

import org.deluge.hid.pic.GridConfig;

/** Virtual framebuffer for the Deluge's grid and sidebars. Ports the logic from pad_leds.h. */
public class PadLEDs {
  /** Width of main pad area — delegates to {@link GridConfig}. */
  public static int kDisplayWidth() {
    return GridConfig.getDisplayWidth();
  }

  /** Height of main pad area — delegates to {@link GridConfig}. */
  public static int kDisplayHeight() {
    return GridConfig.getDisplayHeight();
  }

  public static final int kSideBarWidth = GridConfig.kSideBarWidth;

  // Allocate for maximum possible grid (24+2 × 16) so the arrays are resizable.
  private static final int MAX_WIDTH = 26; // 24 + 2 sidebar
  private static final int MAX_HEIGHT = 16;

  public static final RGB[][] image = new RGB[MAX_HEIGHT][MAX_WIDTH];
  public static final byte[][] occupancyMask = new byte[MAX_HEIGHT][MAX_WIDTH];

  private static boolean flashStateFast = false;
  private static boolean flashStateSlow = false;

  static {
    int h = GridConfig.getTotalHeight();
    int w = GridConfig.getTotalWidth();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        image[y][x] = new RGB();
      }
    }
  }

  /** Update flash states based on system time. Called periodically. */
  public static void updateFlashes(long systemTimeMs) {
    flashStateFast = (systemTimeMs / 100) % 2 == 0;
    flashStateSlow = (systemTimeMs / 400) % 2 == 0;
  }

  public static boolean getFlashFast() {
    return flashStateFast;
  }

  public static boolean getFlashSlow() {
    return flashStateSlow;
  }

  public static void set(int x, int y, RGB color) {
    if (x >= 0 && x < GridConfig.getTotalWidth() && y >= 0 && y < GridConfig.getTotalHeight()) {
      // Apply color to the image array
      image[y][x].r = color.r;
      image[y][x].g = color.g;
      image[y][x].b = color.b;
    }
  }

  public static void clearAll() {
    int h = GridConfig.getTotalHeight();
    int w = GridConfig.getTotalWidth();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        image[y][x].r = 0;
        image[y][x].g = 0;
        image[y][x].b = 0;
      }
    }
  }
}
