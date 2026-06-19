package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.StereoSample;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a sound's reverb send actually reaches the master reverb bus. Previously {@code
 * GlobalEffectable.renderOutput} hardcoded {@code reverbSendAmount = 0}, so no sound ever fed the
 * reverb and the master reverb produced silence regardless of the song's reverb settings.
 */
public class ReverbSendParityTest {

  private static long reverbBusEnergy(int sendKnob) {
    FirmwareSound sound = new FirmwareSound();
    sound.reverbSendKnob = sendKnob;
    sound.triggerNote(60, 100);

    int n = 128; // GlobalEffectable's internal trackBuffer is sized 128
    StereoSample[] out = new StereoSample[n];
    for (int i = 0; i < n; i++) out[i] = new StereoSample();
    int[] reverbBuffer = new int[n];

    sound.renderOutput(out, n, reverbBuffer);

    long energy = 0;
    for (int i = 0; i < n; i++) energy += Math.abs((long) reverbBuffer[i]);
    return energy;
  }

  @Test
  public void reverbSendFeedsTheBus() {
    long dry = reverbBusEnergy(Integer.MIN_VALUE); // INT_MIN knob = off
    long wet = reverbBusEnergy(Integer.MAX_VALUE); // max reverb-send knob

    assertEquals(0, dry, "With the send knob off (INT_MIN) the reverb bus must stay silent");
    assertTrue(
        wet > 0, "With send>0 the sound must feed the reverb bus (regression: was always 0)");
  }
}
