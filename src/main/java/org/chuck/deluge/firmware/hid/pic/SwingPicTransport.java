package org.chuck.deluge.firmware.hid.pic;

import java.awt.Color;
import javax.swing.JButton;
import org.chuck.deluge.firmware.hid.RGB;

/**
 * A {@link PicTransport} that renders PIC pad-LED commands to the Swing grid's JButton array. This
 * captures {@link PIC.Message#SET_COLOUR_FOR_TWO_COLUMNS} commands and updates the corresponding
 * pad buttons in the grid.
 *
 * <p>The transport sits between the firmware's pad-colour logic and the Swing rendering layer.
 * Instead of the firmware calling {@code SwingGridPanel.setPadColour(x, y, rgb)} directly, the
 * firmware calls {@code PIC.setColourForTwoColumns(idx, colours)} which flows through this
 * transport, writing to a framebuffer and optionally flushing to the Swing UI.
 *
 * <p>Dimensions are driven by {@link GridConfig}, defaulting to 16×8 (real Deluge hardware) and
 * extendable to larger grids via {@link PreferencesManager.GridMode}.
 */
public class SwingPicTransport implements PicTransport {

  private static int kDisplayWidth() {
    return GridConfig.getDisplayWidth();
  }

  private static int kDisplayHeight() {
    return GridConfig.getDisplayHeight();
  }

  private static int kTotalWidth() {
    return GridConfig.getTotalWidth();
  }

  /** Framebuffer: pad colours indexed as [y][x]. Allocate at max possible grid. */
  private static final int MAX_WIDTH = 26; // 24 + 2 sidebar

  private static final int MAX_HEIGHT = 16;

  private final RGB[][] framebuffer = new RGB[MAX_HEIGHT][MAX_WIDTH];

  private JButton[][] padButtons;

  /** Decode state machine for SET_COLOUR_FOR_TWO_COLUMNS. */
  private enum DecodeState {
    IDLE,
    COLOUR_COLUMNS_HEADER,
    COLOUR_COLUMNS_DATA
  }

  private DecodeState decodeState = DecodeState.IDLE;
  private int decodeColumnPair = -1;
  private int decodeByteCount = 0;
  private int decodeRGBIndex = 0;
  private int[] decodeRGBBytes = new int[3];

  public SwingPicTransport() {
    int h = kDisplayHeight();
    int w = kTotalWidth();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        framebuffer[y][x] = new RGB();
      }
    }
  }

  /** Set the Swing grid's pad button array. Must be called after the grid is constructed. */
  public void setPadButtons(JButton[][] buttons) {
    this.padButtons = buttons;
  }

  /** Get the current framebuffer. */
  public RGB[][] getFramebuffer() {
    return framebuffer;
  }

  @Override
  public void send(int b) {
    // Decode incoming commands to maintain a colour framebuffer
    decodeByte(b);

    // In the C++ firmware, send() writes to a UART DMA buffer.
    // Here we just update the framebuffer — flush() pushes to Swing.
  }

  @Override
  public int read() {
    // PIC responses (pad presses) come from MatrixDriver/SwingDelugeApp, not here.
    return -1;
  }

  @Override
  public void flush() {
    // Push framebuffer to Swing pad buttons
    if (padButtons == null) return;
    int h = kDisplayHeight();
    int w = kTotalWidth();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        RGB rgb = framebuffer[y][x];
        JButton btn = padButtons[y][x];
        if (btn != null) {
          btn.setBackground(new Color(rgb.r & 0xFF, rgb.g & 0xFF, rgb.b & 0xFF));
        }
      }
    }
  }

  /** Directly set a pad colour in the framebuffer without sending through the PIC protocol. */
  public void setPadColour(int x, int y, RGB colour) {
    if (x >= 0 && x < kTotalWidth() && y >= 0 && y < kDisplayHeight()) {
      framebuffer[y][x].r = colour.r;
      framebuffer[y][x].g = colour.g;
      framebuffer[y][x].b = colour.b;
    }
  }

  /** Clear all pads to black. */
  public void clearAll() {
    int h = kDisplayHeight();
    int w = kTotalWidth();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        framebuffer[y][x].r = 0;
        framebuffer[y][x].g = 0;
        framebuffer[y][x].b = 0;
      }
    }
  }

  // ===================== Protocol decoder =====================

  /**
   * Decodes bytes as if they were received from the PIC. We intercept SET_COLOUR_FOR_TWO_COLUMNS
   * messages to update the framebuffer. Other messages are ignored (they control LED on/off
   * behaviour that Swing renders differently).
   */
  private void decodeByte(int b) {
    switch (decodeState) {
      case IDLE -> {
        // Check if this byte is a SET_COLOUR_FOR_TWO_COLUMNS header
        int base = PIC.Message.SET_COLOUR_FOR_TWO_COLUMNS.value; // 1
        int numColumnPairs = GridConfig.getColumnPairCount();
        if (b >= base && b < base + numColumnPairs) {
          decodeColumnPair = b - base;
          decodeState = DecodeState.COLOUR_COLUMNS_HEADER;
          decodeByteCount = 0;
          decodeRGBIndex = 0;
        }
        // Stay IDLE for all other messages
      }
      case COLOUR_COLUMNS_HEADER -> {
        // The "header" is just the message byte itself; data follows immediately.
        // 2×kDisplayHeight RGB values per column pair = 2*height*3 bytes.
        int expectedBytes = GridConfig.getDisplayHeight() * 2 * 3;
        decodeState = DecodeState.COLOUR_COLUMNS_DATA;
        decodeByteCount = 0;
        decodeRGBIndex = 0;
        decodeRGBBytes = new int[3];
        // Fall through to process this byte as data
        int rgbIdx = decodeByteCount / 3;
        int component = decodeByteCount % 3;
        decodeRGBBytes[component] = b;
        decodeByteCount++;
        if (decodeByteCount >= expectedBytes) {
          finishColumnPair();
        }
      }
      case COLOUR_COLUMNS_DATA -> {
        int component = decodeByteCount % 3;
        decodeRGBBytes[component] = b;
        decodeByteCount++;
        if (decodeByteCount % 3 == 0 && decodeByteCount > 0) {
          int rgbIdx = decodeByteCount / 3 - 1;
          int dispHeight = GridConfig.getDisplayHeight();
          int dispWidth = GridConfig.getDisplayWidth();
          int totalWidth = GridConfig.getTotalWidth();
          int padX = decodeColumnPair * 2 + (rgbIdx / dispHeight);
          int padY = rgbIdx % dispHeight;
          int lastMainPair = (dispWidth + 1) / 2 - 1;
          if (padX < dispWidth || (padX < totalWidth && decodeColumnPair == lastMainPair + 1)) {
            framebuffer[padY][padX].r = decodeRGBBytes[0];
            framebuffer[padY][padX].g = decodeRGBBytes[1];
            framebuffer[padY][padX].b = decodeRGBBytes[2];
          }
        }
        int expectedBytes = GridConfig.getDisplayHeight() * 2 * 3;
        if (decodeByteCount >= expectedBytes) {
          finishColumnPair();
        }
      }
    }
  }

  private void finishColumnPair() {
    decodeState = DecodeState.IDLE;
    decodeColumnPair = -1;
  }
}
