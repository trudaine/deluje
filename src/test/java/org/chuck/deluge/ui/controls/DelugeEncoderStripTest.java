package org.chuck.deluge.ui.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class DelugeEncoderStripTest {

  @Test
  public void xAndYKnobsRouteToTheirCallbacks() {
    AtomicInteger x = new AtomicInteger();
    AtomicInteger y = new AtomicInteger();
    DelugeEncoderStrip strip = new DelugeEncoderStrip(x::addAndGet, y::addAndGet);

    DelugeEncoderKnob xk = strip.getXKnob();
    xk.setSize(48, 48);
    xk.dispatchEvent(
        new MouseEvent(xk, MouseEvent.MOUSE_PRESSED, 1L, 0, 10, 30, 1, false, MouseEvent.BUTTON1));
    xk.dispatchEvent(
        new MouseEvent(
            xk, MouseEvent.MOUSE_DRAGGED, 2L, InputEvent.BUTTON1_DOWN_MASK, 10, 18, 0, false, 0));
    xk.dispatchEvent(
        new MouseEvent(xk, MouseEvent.MOUSE_RELEASED, 3L, 0, 10, 18, 1, false, MouseEvent.BUTTON1));

    assertEquals(1, x.get(), "X knob drag-up routes +1 to scrollX");
    assertEquals(0, y.get(), "Y untouched");
  }
}
