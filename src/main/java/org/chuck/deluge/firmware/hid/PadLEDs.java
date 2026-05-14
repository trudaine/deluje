package org.chuck.deluge.firmware.hid;

/** Virtual framebuffer for the Deluge's 8x16 grid and sidebars. Ports the logic from pad_leds.h. */
public class PadLEDs {
  public static final int kDisplayWidth = 16;
  public static final int kDisplayHeight = 8;
  public static final int kSideBarWidth = 2;

  public static final RGB[][] image = new RGB[kDisplayHeight][kDisplayWidth + kSideBarWidth];
  public static final byte[][] occupancyMask =
      new byte[kDisplayHeight][kDisplayWidth + kSideBarWidth];

  private static boolean flashStateFast = false;
  private static boolean flashStateSlow = false;

  static {
    for (int y = 0; y < kDisplayHeight; y++) {
      for (int x = 0; x < kDisplayWidth + kSideBarWidth; x++) {
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
    if (x >= 0 && x < kDisplayWidth + kSideBarWidth && y >= 0 && y < kDisplayHeight) {
      // Apply color to the image array
      image[y][x].r = color.r;
      image[y][x].g = color.g;
      image[y][x].b = color.b;
    }
  }

  public static void clearAll() {
    for (int y = 0; y < kDisplayHeight; y++) {
      for (int x = 0; x < kDisplayWidth + kSideBarWidth; x++) {
        image[y][x].r = 0;
        image[y][x].g = 0;
        image[y][x].b = 0;
      }
    }
  }
}
