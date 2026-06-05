package org.chuck.deluge.firmware.dsp.fx;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.junit.jupiter.api.Test;

public class ModFXProcessorTest {
  @Test
  public void testChorus() {
    ModFXProcessor processor = new ModFXProcessor();
    StereoSample[] buffer = new StereoSample[1024];
    for (int i = 0; i < 1024; i++) buffer[i] = new StereoSample(1000000, 1000000);

    int[] postFXVolume = {1 << 30};
    // Process once to fill buffer
    processor.processModFX(buffer, ModFXType.CHORUS, 100, 1 << 30, postFXVolume, 1 << 30, 0);
    // Process again to hear the delayed signal
    processor.processModFX(buffer, ModFXType.CHORUS, 100, 1 << 30, postFXVolume, 1 << 30, 0);

    // Just verify it doesn't crash and changes the signal
    assertNotEquals(1000000, buffer[buffer.length - 1].l);
    assertNotEquals(1 << 30, postFXVolume[0], "chorus should also adjust post-FX volume");
  }

  @Test
  public void testPhaser() {
    ModFXProcessor processor = new ModFXProcessor();
    StereoSample[] buffer = new StereoSample[128];
    for (int i = 0; i < 128; i++) buffer[i] = new StereoSample(1000000, 1000000);

    int[] postFXVolume = {1 << 30};
    processor.processModFX(buffer, ModFXType.PHASER, 100, 1 << 30, postFXVolume, 0, 1 << 30);

    assertNotEquals(1000000, buffer[0].l);
    assertNotEquals(1 << 30, postFXVolume[0], "phaser feedback should adjust post-FX volume");
  }
}
