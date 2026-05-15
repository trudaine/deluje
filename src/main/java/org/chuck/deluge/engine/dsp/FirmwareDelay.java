package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.StereoUGen;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.delay.Delay;
import org.chuck.deluge.firmware.util.Q31;

/** Wrapper for the high-fidelity ported Delay. */
public class FirmwareDelay extends StereoUGen {
  private final Delay firmware;
  private final Delay.State state;
  private final StereoSample[] buffer;

  public FirmwareDelay() {
    this.firmware = new Delay();
    this.state = new Delay.State();
    this.buffer = new StereoSample[1];
    this.buffer[0] = new StereoSample();

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
    buffer[0].l = Q31.fromFloat(left);
    buffer[0].r = Q31.fromFloat(right);

    // ── Bit-Accurate Hardware Processing ──
    // Hardware uses 1 << 20 as neutral tick inverse for timing sync
    firmware.setupWorkingState(state, 1 << 20, true); 
    firmware.process(buffer, state);

    lastOutChannels[0] = Q31.toFloat(buffer[0].l);
    lastOutChannels[1] = Q31.toFloat(buffer[0].r);
  }

  @Override
  protected void computeStereo(float input, long systemTime) {
    computeStereo(input, input, systemTime);
  }
}
