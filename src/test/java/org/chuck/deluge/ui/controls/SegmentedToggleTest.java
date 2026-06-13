package org.chuck.deluge.ui.controls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class SegmentedToggleTest {

  private static SegmentedToggle make(int initial, AtomicInteger sink) {
    SegmentedToggle t = new SegmentedToggle(new String[] {"12dB", "24dB", "DRIVE"}, initial, Color.ORANGE);
    t.setSize(120, 28); // 3 segments => 40px each
    t.onChange(sink::set);
    return t;
  }

  private static void click(SegmentedToggle t, int x) {
    t.dispatchEvent(
        new MouseEvent(t, MouseEvent.MOUSE_PRESSED, 1L, 0, x, 14, 1, false, MouseEvent.BUTTON1));
  }

  @Test
  public void clickSelectsSegmentUnderCursor() {
    AtomicInteger sink = new AtomicInteger(-1);
    SegmentedToggle t = make(0, sink);
    click(t, 50); // second segment (40..80)
    assertEquals(1, t.getSelectedIndex());
    assertEquals(1, sink.get());
    assertEquals("24dB", t.getSelectedOption());
  }

  @Test
  public void clickingSameSegmentDoesNotFire() {
    AtomicInteger sink = new AtomicInteger(-1);
    SegmentedToggle t = make(2, sink);
    click(t, 100); // third segment, already selected
    assertEquals(-1, sink.get(), "no change event when selection unchanged");
  }

  @Test
  public void silentSetDoesNotFire() {
    AtomicInteger sink = new AtomicInteger(-1);
    SegmentedToggle t = make(0, sink);
    t.setSelectedIndexSilently(2);
    assertEquals(2, t.getSelectedIndex());
    assertEquals(-1, sink.get());
  }

  @Test
  public void outOfRangeIsClamped() {
    AtomicInteger sink = new AtomicInteger(-1);
    SegmentedToggle t = make(0, sink);
    t.setSelectedIndex(99);
    assertEquals(2, t.getSelectedIndex());
  }

  @Test
  public void rendersWithoutThrowing() {
    SegmentedToggle t = make(1, new AtomicInteger());
    BufferedImage img = new BufferedImage(120, 28, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    t.paint(g);
    g.dispose();
  }
}
