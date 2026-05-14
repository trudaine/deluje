package org.chuck.deluge.engine.dsp;

import org.chuck.audio.ChuckUGen;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.compressor.RMSFeedbackCompressor;

/**
 * Wrapper for the high-fidelity ported RMSFeedbackCompressor. Mono version for integration into
 * existing DSL.
 */
public class FirmwareCompressor extends ChuckUGen {
  private final RMSFeedbackCompressor firmware;
  private final StereoSample[] buffer;

  public FirmwareCompressor() {
    this.firmware = new RMSFeedbackCompressor();
    this.buffer = new StereoSample[1];
    this.buffer[0] = new StereoSample();
  }

  public void setThreshold(float t) {
    firmware.setThreshold((int) (t * 2147483647.0));
  }

  public void setRatio(float r) {
    firmware.setRatio((int) (r * 2147483647.0));
  }

  @Override
  protected float compute(float input, long systemTime) {
    buffer[0].l = (int) (input * 2147483648.0);
    buffer[0].r = buffer[0].l;

    // Render with unity gain defaults for now
    firmware.render(buffer, 1 << 31, 1 << 31, 1 << 28);

    return (float) (buffer[0].l / 2147483648.0);
  }
}
