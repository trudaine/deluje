package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.chuck.deluge.ui.controls.UiAnimator;
import org.junit.jupiter.api.Test;

/** Verifies the playhead blink registers on the shared clock and is leak-safe. */
public class DelugePadButtonTest {

  @Test
  public void playheadRegistersAndUnregistersOnTheSharedClock() {
    int base = UiAnimator.get().listenerCount();
    DelugePadButton pad = new DelugePadButton();

    pad.setPlayhead(true);
    assertEquals(base + 1, UiAnimator.get().listenerCount(), "playhead pad adds one tick listener");

    pad.setPlayhead(false);
    assertEquals(base, UiAnimator.get().listenerCount(), "clearing playhead removes it");
  }

  @Test
  public void removeNotifyUnregistersToAvoidLeakOnRefresh() {
    int base = UiAnimator.get().listenerCount();
    DelugePadButton pad = new DelugePadButton();
    pad.setPlayhead(true);
    assertEquals(base + 1, UiAnimator.get().listenerCount());

    // Grid rebuilds pads on refresh -> the discarded pad must drop its registration.
    pad.removeNotify();
    assertEquals(base, UiAnimator.get().listenerCount(), "removeNotify unregisters the tick");
  }

  @Test
  public void tiedTailPadPaintsWithoutThrowing() {
    DelugePadButton pad = new DelugePadButton();
    pad.setSize(40, 40);
    pad.setActive(true);
    pad.setBaseColor(new Color(0x33, 0xcc, 0xff));
    pad.setTied(true);
    BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    pad.paint(g);
    g.dispose();
  }

  @Test
  public void playheadPadPaintsWithoutThrowing() {
    DelugePadButton pad = new DelugePadButton();
    pad.setSize(40, 40);
    pad.setActive(true);
    pad.setBaseColor(new Color(0x33, 0xcc, 0xff));
    pad.setPlayhead(true);
    BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    pad.paint(g);
    g.dispose();
    pad.setPlayhead(false); // cleanup registration
  }
}
