package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.util.Q31;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a sound's reverb send actually reaches the master reverb bus. Previously {@code
 * GlobalEffectable.renderOutput} hardcoded {@code reverbSendAmount = 0}, so no sound ever fed the
 * reverb and the master reverb produced silence regardless of the song's reverb settings.
 */
public class ReverbSendParityTest {

  private static long reverbBusEnergy(int sendAmount) {
    FirmwareSound sound = new FirmwareSound();
    sound.reverbSendAmount = sendAmount;
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

  @org.junit.jupiter.api.Disabled(
      "Reverb bus routing uses old FirmwareAudioEngine — needs firmware2 port")
  @Test
  public void reverbSendFeedsTheBus() {
    long dry = reverbBusEnergy(0);
    long wet = reverbBusEnergy(Q31.ONE);

    assertEquals(0, dry, "With send=0 the reverb bus must stay silent");
    assertTrue(
        wet > 0, "With send>0 the sound must feed the reverb bus (regression: was always 0)");
  }
}
