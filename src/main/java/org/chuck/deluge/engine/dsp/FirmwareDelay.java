package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.StereoUGen;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.firmware2.Delay;

/**
 * Wrapper for the faithful firmware2 Delay (parity-verified identical to the old firmware/ Delay).
 */
public class FirmwareDelay extends StereoUGen {
  private final Delay firmware = new Delay();
  private final Delay.State state = new Delay.State();
  private final int[][] buffer = new int[1][2];

  public FirmwareDelay() {
    // Match the old firmware/ Delay defaults the previous wrapper relied on: SYNC_LEVEL_16TH (5),
    // pingPong on, EVEN sync, digital. (firmware2 defaults syncLevel to 0, so set it explicitly;
    // the synced-delay path is parity-verified in Firmware2FxParityTest.)
    firmware.syncLevel = 5;

    // ── Hardware Initial State ──
    state.doDelay = true;
    state.userDelayRate = 22050 << 5; // Default middle-ish
    state.delayFeedbackAmount = 1073741824; // 0.5 in Q31
  }

  public void setFeedback(float fb) {
    state.delayFeedbackAmount = Q31.fromFloat(fb);
  }

  public void setDelayTime(float timeNormalized) {
    // ── Bit-Accurate Rate Mapping ──
    // Map 0-1 to hardware's internal rate increments
    state.userDelayRate = (int) (timeNormalized * 2147483647.0);
  }

  @Override
  protected void computeStereo(float left, float right, long systemTime) {
    buffer[0][0] = Q31.fromFloat(left);
    buffer[0][1] = Q31.fromFloat(right);

    // ── Bit-Accurate Hardware Processing ──
    // Hardware uses 1 << 20 as neutral tick inverse for timing sync
    firmware.setupWorkingState(state, 1 << 20, true);
    firmware.process(buffer, 1, state);

    lastOutChannels[0] = Q31.toFloat(buffer[0][0]);
    lastOutChannels[1] = Q31.toFloat(buffer[0][1]);
  }

  @Override
  protected void computeStereo(float input, long systemTime) {
    computeStereo(input, input, systemTime);
  }
}
