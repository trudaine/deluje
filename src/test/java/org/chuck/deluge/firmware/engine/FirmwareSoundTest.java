package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware2.StereoSample;
import org.chuck.deluge.firmware.model.PolyphonyMode;
import org.junit.jupiter.api.Test;

public class FirmwareSoundTest {
  @Test
  public void testPolyphony() {
    FirmwareSound sound = new FirmwareSound();
    sound.polyphonic = PolyphonyMode.POLY;

    sound.triggerNote(60, 100);
    sound.triggerNote(62, 100);

    int activeCount = 0;
    for (org.chuck.deluge.firmware2.Voice v : sound.fw2Sound.voices) if (v.active) activeCount++;
    assertEquals(2, activeCount);
  }

  @Test
  public void testMono() {
    FirmwareSound sound = new FirmwareSound();
    sound.polyphonic = PolyphonyMode.MONO;

    sound.triggerNote(60, 100);
    sound.triggerNote(62, 100);

    // Render to allow first voice to transition to OFF (after noteOff(0))
    StereoSample[] buf = new StereoSample[16384];
    for (int i = 0; i < 16384; i++) buf[i] = new StereoSample();
    sound.renderInternal(buf, 16384, null);

    int activeCount = 0;
    for (org.chuck.deluge.firmware2.Voice v : sound.fw2Sound.voices) if (v.active) activeCount++;
    assertEquals(1, activeCount);
  }
}
