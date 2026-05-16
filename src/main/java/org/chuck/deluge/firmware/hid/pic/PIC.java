package org.chuck.deluge.firmware.hid.pic;

import org.chuck.deluge.firmware.hid.RGB;

/**
 * Port of the C++ {@code drivers/pic/pic.h} — the static class for interacting with the PIC
 * peripheral. All hardware I/O goes through a {@link PicTransport} so the same protocol calls work
 * for both real hardware (serial) and Swing rendering.
 */
public class PIC {

  /** Convenience references to {@link GridConfig} — the single source of truth. */
  public static int kDisplayWidth() {
    return GridConfig.getDisplayWidth();
  }

  public static int kDisplayHeight() {
    return GridConfig.getDisplayHeight();
  }

  public static int kSideBarWidth() {
    return GridConfig.kSideBarWidth;
  }

  public static int kMainPadCount() {
    return GridConfig.getMainPadCount();
  }

  private static final int kNumGoldKnobIndicatorLEDs = 24;
  private static final int kNumericDisplayLength = 4;

  private static PicTransport transport = null;

  private PIC() {}

  /** Set the transport implementation. Call once at startup. */
  public static void setTransport(PicTransport t) {
    transport = t;
  }

  /** Get the current transport. */
  public static PicTransport getTransport() {
    return transport;
  }

  // ===================== Message & Response enums =====================

  public enum Message {
    NONE(0),
    SET_COLOUR_FOR_TWO_COLUMNS(1), // 9 variants (8 main pairs + 1 side pair)
    SET_FLASH_COLOR(10),
    SET_DEBOUNCE_TIME(18),
    SET_REFRESH_TIME(19),
    SET_GOLD_KNOB_0_INDICATORS(20),
    SET_GOLD_KNOB_1_INDICATORS(21),
    RESEND_BUTTON_STATES(22),
    SET_FLASH_LENGTH(23),
    SET_PAD_FLASHING(24),
    SET_LED_OFF(152),
    SET_LED_ON(188),
    UPDATE_SEVEN_SEGMENT_DISPLAY(224),
    SET_UART_SPEED(225),
    SET_SCROLL_ROW(228),
    SET_SCROLL_LEFT(236),
    SET_SCROLL_RIGHT(237),
    SET_SCROLL_RIGHT_FULL(238),
    SET_SCROLL_LEFT_FULL(239),
    DONE_SENDING_ROWS(240),
    SET_SCROLL_UP(241),
    SET_SCROLL_DOWN(242),
    SET_DIMMER_INTERVAL(243),
    SET_MIN_INTERRUPT_INTERVAL(244),
    REQUEST_FIRMWARE_VERSION(245),
    ENABLE_OLED(247),
    SELECT_OLED(248),
    DESELECT_OLED(249),
    SET_DC_LOW(250),
    SET_DC_HIGH(251);

    public final int value;

    Message(int value) {
      this.value = value;
    }

    public static Message fromValue(int v) {
      for (Message m : values()) {
        if (m.value == v) return m;
      }
      return NONE;
    }
  }

  public enum Response {
    NONE(0),
    UNKNOWN_BOOT_RESPONSE(129),
    RESET_SETTINGS(175),
    FIRMWARE_VERSION_NEXT(245),
    UNKNOWN_OLED_RELATED_COMMAND(246),
    SET_DC_HIGH(251),
    NEXT_PAD_OFF(252),
    UNKNOWN_BREAK(253),
    NO_PRESSES_HAPPENING(254);

    public final int value;

    Response(int value) {
      this.value = value;
    }

    /** Messages at or below this value are pad/button press reports. */
    public static final int kPadAndButtonMessagesEnd = 180;

    public static Response fromValue(int v) {
      for (Response r : values()) {
        if (r.value == v) return r;
      }
      return NONE;
    }
  }

  // ===================== High-level API =====================

  /** UART baud rate for rapid pad updates. */
  public static final int kUartFullSpeedPadsHz = 200000; // 400000 glitches sometimes

  /** Switch UART to full speed for pad updates. */
  public static void setupForPads() {
    // No-op in Java — the transport decides the serial speed.
    // In C++ this calls uartSetBaudRate(UART_CHANNEL_PIC, kUartFullSpeedPadsHz);
  }

  /**
   * Set colour for two columns of LEDs. The Deluge main pads are updated in groups of two adjacent
   * columns as though they were a continuous strip of 16 LEDs.
   *
   * @param idx column-pair index (0-3 for main pads, 4 for side bar)
   * @param colours 16 colours (8 rows × 2 columns)
   */
  public static void setColourForTwoColumns(int idx, RGB[] colours) {
    ensureTransport();
    send(Message.SET_COLOUR_FOR_TWO_COLUMNS.value + idx);
    for (RGB colour : colours) {
      sendColour(colour);
    }
  }

  /** Set debounce time in milliseconds. */
  public static void setDebounce(int timeMs) {
    send(Message.SET_DEBOUNCE_TIME, timeMs & 0xFF);
  }

  /** Set the gold knob indicator LEDs. */
  public static void setGoldKnobIndicator(boolean which, byte[] indicator) {
    Message msg = which ? Message.SET_GOLD_KNOB_1_INDICATORS : Message.SET_GOLD_KNOB_0_INDICATORS;
    send(msg.value);
    for (int i = 0; i < Math.min(indicator.length, kNumGoldKnobIndicatorLEDs); i++) {
      send(indicator[i] & 0xFF);
    }
  }

  /** Turn off the LED at the given index. */
  public static void setLEDOff(int idx) {
    send(Message.SET_LED_OFF.value + idx);
  }

  /** Turn on the LED at the given index. */
  public static void setLEDOn(int idx) {
    send(Message.SET_LED_ON.value + idx);
  }

  /** Request that the PIC resend all button states. */
  public static void resendButtonStates() {
    send(Message.RESEND_BUTTON_STATES);
  }

  /** Set minimum interrupt interval in milliseconds. */
  public static void setMinInterruptInterval(int timeMs) {
    send(Message.SET_MIN_INTERRUPT_INTERVAL, timeMs & 0xFF);
  }

  /** Set flash length in milliseconds. */
  public static void setFlashLength(int timeMs) {
    send(Message.SET_FLASH_LENGTH, timeMs & 0xFF);
  }

  /** Set the UART speed on the PIC to match our full-speed pads rate. */
  public static void setUARTSpeed() {
    int speed = (int) (4_000_000.0f / kUartFullSpeedPadsHz - 0.5f);
    send(Message.SET_UART_SPEED, speed & 0xFF);
  }

  /** Flash a main pad. */
  public static void flashMainPad(int idx) {
    send(Message.SET_PAD_FLASHING.value + idx);
  }

  /** Flash a pad with a specific colour index known to the PIC. */
  public static void flashMainPadWithColourIdx(int idx, int colourIdx) {
    send(Message.SET_FLASH_COLOR.value + colourIdx);
    flashMainPad(idx);
  }

  /** Update the 7-segment numeric display. */
  public static void update7SEG(byte[] display) {
    send(Message.UPDATE_SEVEN_SEGMENT_DISPLAY.value);
    for (int i = 0; i < Math.min(display.length, kNumericDisplayLength); i++) {
      send(display[i] & 0xFF);
    }
  }

  /** Enable the OLED display. */
  public static void enableOLED() {
    send(Message.ENABLE_OLED);
  }

  /** Select the OLED display (SPI chip select). */
  public static void selectOLED() {
    send(Message.SELECT_OLED);
  }

  /** Deselect the OLED display. */
  public static void deselectOLED() {
    send(Message.DESELECT_OLED);
  }

  /** Set DC low for OLED. */
  public static void setDCLow() {
    send(Message.SET_DC_LOW);
  }

  /** Set DC high for OLED. */
  public static void setDCHigh() {
    send(Message.SET_DC_HIGH);
  }

  /** Request the PIC firmware version string. */
  public static void requestFirmwareVersion() {
    send(Message.REQUEST_FIRMWARE_VERSION);
  }

  /** Send a raw RGB colour. */
  public static void sendColour(RGB colour) {
    send(colour.r & 0xFF);
    send(colour.g & 0xFF);
    send(colour.b & 0xFF);
  }

  /** Set refresh time in milliseconds. */
  public static void setRefreshTime(int timeMs) {
    send(Message.SET_REFRESH_TIME, timeMs & 0xFF);
  }

  /** Set dimmer interval. */
  public static void setDimmerInterval(int interval) {
    send(Message.SET_DIMMER_INTERVAL, interval & 0xFF);
  }

  /** Send a scroll row colour for horizontal animation. */
  public static void sendScrollRow(int idx, RGB colour) {
    send(Message.SET_SCROLL_ROW.value + idx);
    sendColour(colour);
  }

  /** Setup horizontal scroll with bitflags. */
  public static void setupHorizontalScroll(int bitflags) {
    send(Message.SET_SCROLL_LEFT.value + (bitflags & 0xFF));
  }

  /** Do a vertical scroll with colours for the full row. */
  public static void doVerticalScroll(boolean direction, RGB[] colours) {
    Message msg = direction ? Message.SET_SCROLL_UP : Message.SET_SCROLL_DOWN;
    send(msg.value);
    for (RGB colour : colours) {
      sendColour(colour);
    }
  }

  /** Signal that all rows have been sent. */
  public static void doneSendingRows() {
    send(Message.DONE_SENDING_ROWS);
  }

  /** Flush the transport buffer. */
  public static void flush() {
    ensureTransport();
    transport.flush();
  }

  // ===================== Low-level send/receive =====================

  /** Send a Message enum value as a single byte. */
  public static void send(Message msg) {
    send(msg.value);
  }

  /** Send a Message followed by a series of byte values. */
  public static void send(Message msg, int... bytes) {
    send(msg.value);
    for (int b : bytes) {
      send(b & 0xFF);
    }
  }

  /** Send a single byte to the PIC. */
  public static void send(int b) {
    ensureTransport();
    transport.send(b & 0xFF);
  }

  /** Read a single response byte (non-blocking). Returns -1 if none available. */
  public static int read() {
    ensureTransport();
    return transport.read();
  }

  /**
   * Read a response with a simple blocking poll loop (busy-wait up to {@code timeoutMicros}
   * microseconds). Returns NONE on timeout.
   */
  public static Response read(long timeoutMicros) {
    long deadline = System.nanoTime() + timeoutMicros * 1000L;
    while (System.nanoTime() < deadline) {
      int val = read();
      if (val >= 0) {
        return Response.fromValue(val);
      }
      Thread.onSpinWait();
    }
    return Response.NONE;
  }

  /**
   * Read responses using a callback handler. The handler is called for each received byte. If it
   * returns a non-zero value, reading stops and that value is returned. Returns 1 on timeout.
   */
  public static int read(long timeoutMicros, java.util.function.IntFunction<Integer> handler) {
    long deadline = System.nanoTime() + timeoutMicros * 1000L;
    while (System.nanoTime() < deadline) {
      int val = read();
      if (val >= 0) {
        int result = handler.apply(val);
        if (result != 0) return result;
      }
      Thread.onSpinWait();
    }
    return 1; // timeout with failure
  }

  // ===================== Internal =====================

  private static void ensureTransport() {
    if (transport == null) {
      throw new IllegalStateException("PIC transport not set. Call PIC.setTransport() first.");
    }
  }
}
