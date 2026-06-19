package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class ModFxTest {
  @Test
  public void testChorus() {
    ModFx processor = new ModFx();
    int[][] buffer = new int[1024][2];
    for (int i = 0; i < 1024; i++) {
      buffer[i][0] = 1000000;
      buffer[i][1] = 1000000;
    }

    int[] postFXVolume = {1 << 30};
    // Process once to fill buffer
    processor.processModFX(
        buffer, 1024, ModFx.ModFXType.CHORUS, 100, 1 << 30, postFXVolume, 1 << 30, 0, false, true);
    // Process again to hear the delayed signal
    processor.processModFX(
        buffer, 1024, ModFx.ModFXType.CHORUS, 100, 1 << 30, postFXVolume, 1 << 30, 0, false, true);

    // Just verify it doesn't crash and changes the signal
    assertNotEquals(1000000, buffer[buffer.length - 1][0]);
    assertNotEquals(1 << 30, postFXVolume[0], "chorus should also adjust post-FX volume");
  }

  @Test
  public void testPhaser() {
    ModFx processor = new ModFx();
    int[][] buffer = new int[128][2];
    for (int i = 0; i < 128; i++) {
      buffer[i][0] = 1000000;
      buffer[i][1] = 1000000;
    }

    int[] postFXVolume = {1 << 30};
    processor.processModFX(
        buffer, 128, ModFx.ModFXType.PHASER, 100, 1 << 30, postFXVolume, 0, 1 << 30, false, true);

    assertNotEquals(1000000, buffer[0][0]);
    assertNotEquals(1 << 30, postFXVolume[0], "phaser feedback should adjust post-FX volume");
  }
}
