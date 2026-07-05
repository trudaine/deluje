package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Color;
import org.junit.jupiter.api.Test;

class DelugeColourTest {

  @Test
  void testFromHuePastelDarkChannelFloor() {
    // For a hue where d >= 64, the channel should have the floor value: 256 - 230 = 26
    Color color = DelugeColour.fromHuePastel(0);
    // Let's verify that the dark channel has value 26 (due to the floor).
    // Specifically, for hue 0, the channel darkness d for channel c = 1 (green) or c = 2 (blue):
    // hue + 1920 % 192 = 0.
    // c = 1: std::min(64, |64 - 0|) = 64. Hence d = 64 (>= 64).
    // So green channel should have value 26.
    assertEquals(26, color.getGreen(), "Green channel should be at the dark channel floor (26)");
    assertEquals(26, color.getBlue(), "Blue channel should be at the dark channel floor (26)");
  }
}
