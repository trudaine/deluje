package org.chuck.deluge.ui.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Verifies the encoder gesture routing: turn / press / press+turn. */
public class DelugeEncoderKnobTest {

  private static MouseEvent ev(DelugeEncoderKnob k, int id, int mods, int y, int button) {
    return new MouseEvent(k, id, System.currentTimeMillis(), mods, 10, y, 0, false, button);
  }

  @Test
  public void clickWithoutDragFiresPress() {
    DelugeEncoderKnob k = new DelugeEncoderKnob("CUT", Color.ORANGE);
    k.setSize(48, 48);
    AtomicInteger presses = new AtomicInteger();
    k.onPress(presses::incrementAndGet);

    k.dispatchEvent(ev(k, MouseEvent.MOUSE_PRESSED, 0, 20, MouseEvent.BUTTON1));
    k.dispatchEvent(ev(k, MouseEvent.MOUSE_RELEASED, 0, 20, MouseEvent.BUTTON1));

    assertEquals(1, presses.get());
  }

  @Test
  public void dragUpFiresPositiveTurnAndNotPress() {
    DelugeEncoderKnob k = new DelugeEncoderKnob("CUT", Color.ORANGE);
    k.setSize(48, 48);
    AtomicInteger turn = new AtomicInteger();
    AtomicInteger presses = new AtomicInteger();
    k.onTurn(turn::addAndGet);
    k.onPress(presses::incrementAndGet);

    k.dispatchEvent(ev(k, MouseEvent.MOUSE_PRESSED, 0, 30, MouseEvent.BUTTON1));
    // drag up 12px (> PIXELS_PER_DETENT=10) => one +1 detent
    k.dispatchEvent(ev(k, MouseEvent.MOUSE_DRAGGED, InputEvent.BUTTON1_DOWN_MASK, 18, 0));
    k.dispatchEvent(ev(k, MouseEvent.MOUSE_RELEASED, 0, 18, MouseEvent.BUTTON1));

    assertEquals(1, turn.get(), "one detent up");
    assertEquals(0, presses.get(), "a drag is not a press");
  }

  @Test
  public void dragDownFiresNegativeTurn() {
    DelugeEncoderKnob k = new DelugeEncoderKnob("CUT", Color.ORANGE);
    k.setSize(48, 48);
    AtomicInteger turn = new AtomicInteger();
    k.onTurn(turn::addAndGet);

    k.dispatchEvent(ev(k, MouseEvent.MOUSE_PRESSED, 0, 10, MouseEvent.BUTTON1));
    k.dispatchEvent(ev(k, MouseEvent.MOUSE_DRAGGED, InputEvent.BUTTON1_DOWN_MASK, 32, 0));
    k.dispatchEvent(ev(k, MouseEvent.MOUSE_RELEASED, 0, 32, MouseEvent.BUTTON1));

    assertEquals(-2, turn.get(), "22px down => two -1 detents");
  }

  @Test
  public void altDragRoutesToPressTurn() {
    DelugeEncoderKnob k = new DelugeEncoderKnob("CUT", Color.ORANGE);
    k.setSize(48, 48);
    AtomicInteger turn = new AtomicInteger();
    AtomicInteger pressTurn = new AtomicInteger();
    k.onTurn(turn::addAndGet);
    k.onPressTurn(pressTurn::addAndGet);

    k.dispatchEvent(
        ev(k, MouseEvent.MOUSE_PRESSED, InputEvent.ALT_DOWN_MASK, 30, MouseEvent.BUTTON1));
    k.dispatchEvent(
        ev(
            k,
            MouseEvent.MOUSE_DRAGGED,
            InputEvent.BUTTON1_DOWN_MASK | InputEvent.ALT_DOWN_MASK,
            18,
            0));
    k.dispatchEvent(ev(k, MouseEvent.MOUSE_RELEASED, 0, 18, MouseEvent.BUTTON1));

    assertEquals(0, turn.get(), "alt-drag is press+turn, not plain turn");
    assertEquals(1, pressTurn.get());
  }
}
