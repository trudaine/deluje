package org.chuck.deluge.firmware.hid.pic;

/**
 * Abstraction over PIC communication. In the C++ firmware this sends/receives bytes over UART to
 * the physical PIC microcontroller. In Java, implementations can wrap Swing rendering, a serial
 * port, or a mock for testing.
 */
public interface PicTransport {

  /** Send a single byte to the PIC. */
  void send(int b);

  /** Read a single response byte from the PIC (non-blocking, returns -1 if none available). */
  int read();

  /** Flush any buffered output. */
  void flush();
}
