package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.StereoUGen;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.delay.Delay;

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

    // Initial setup
    state.doDelay = true;
    state.userDelayRate = 22050 << 5; // Default middle-ish
    state.delayFeedbackAmount = (int) (0.5 * 2147483648.0);
  }

  public void setFeedback(float fb) {
    state.delayFeedbackAmount = (int) (fb * 2147483647.0);
  }

  public void setDelayTime(float timeNormalized) {
    // Map 0-1 to something reasonable in q31 or direct rate
    state.userDelayRate = (int) (timeNormalized * 2147483647.0);
  }

  @Override
  protected void computeStereo(float left, float right, long systemTime) {
    buffer[0].l = (int) (left * 2147483648.0);
    buffer[0].r = (int) (right * 2147483648.0);

    firmware.setupWorkingState(state, 1 << 20, true); // rough tick inverse
    firmware.process(buffer, state);

    lastOutChannels[0] = (float) (buffer[0].l / 2147483648.0);
    lastOutChannels[1] = (float) (buffer[0].r / 2147483648.0);
  }

  @Override
  protected void computeStereo(float input, long systemTime) {
    computeStereo(input, input, systemTime);
  }
}
