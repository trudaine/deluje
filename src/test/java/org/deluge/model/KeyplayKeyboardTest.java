package org.deluge.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class KeyplayKeyboardTest {

  @Test
  void testGetNote() {
    // Bottom-left pad: row 7, col 0 -> BASE_NOTE (50)
    assertEquals(50, KeyplayKeyboard.getNote(7, 0));

    // Bottom-right pad: row 7, col 15 -> 50 + 15 = 65
    assertEquals(65, KeyplayKeyboard.getNote(7, 15));

    // Top-left pad: row 0, col 0 -> 50 + 7 * 5 = 85
    assertEquals(85, KeyplayKeyboard.getNote(0, 0));

    // Top-right pad: row 0, col 15 -> 50 + 15 + 7 * 5 = 100
    assertEquals(100, KeyplayKeyboard.getNote(0, 15));

    // Middle pad check: row 4, col 8 -> 50 + 8 + (7 - 4) * 5 = 58 + 15 = 73
    assertEquals(73, KeyplayKeyboard.getNote(4, 8));
  }

  @Test
  void testGetDrumIndex() {
    // Bottom-left pad: row 7, col 0 -> 0
    assertEquals(0, KeyplayKeyboard.getDrumIndex(7, 0));

    // Bottom-right pad: row 7, col 15 -> 15
    assertEquals(15, KeyplayKeyboard.getDrumIndex(7, 15));

    // Top-left pad: row 0, col 0 -> 7 * 16 = 112
    assertEquals(112, KeyplayKeyboard.getDrumIndex(0, 0));

    // Top-right pad: row 0, col 15 -> 15 + 7 * 16 = 127
    assertEquals(127, KeyplayKeyboard.getDrumIndex(0, 15));

    // Middle pad check: row 4, col 8 -> 8 + (7 - 4) * 16 = 8 + 48 = 56
    assertEquals(56, KeyplayKeyboard.getDrumIndex(4, 8));
  }
}
