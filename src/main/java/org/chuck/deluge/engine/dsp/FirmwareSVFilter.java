package org.chuck.deluge.engine.dsp;

import org.chuck.audio.ChuckUGen;
import org.chuck.deluge.firmware.dsp.filter.FirmwareFilter;
import org.chuck.deluge.firmware.dsp.filter.SVFilter;

/** Wrapper for the high-fidelity ported SVFilter. */
public class FirmwareSVFilter extends ChuckUGen {
  public final SVFilter firmware;
  private final int[] sampleBuffer = new int[1];

  public FirmwareSVFilter() {
    this.firmware = new SVFilter();
  }

  public void setConfig(float freq, float res, FirmwareFilter.FilterMode mode, float morph) {
    // freq: normalized 0-1?
    // Firmware curveFrequency takes q31
    firmware.setConfig(
        (int) (freq * 2147483647.0),
        (int) (res * 536870896.0),
        mode,
        (int) (morph * 2147483647.0),
        1 << 28);
  }

  @Override
  protected float compute(float input, long systemTime) {
    sampleBuffer[0] = (int) (input * 2147483648.0);
    firmware.doFilter(sampleBuffer, 0, 1, 1);
    return (float) (sampleBuffer[0] / 2147483648.0);
  }
}
