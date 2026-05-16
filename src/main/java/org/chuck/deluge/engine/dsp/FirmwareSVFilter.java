package org.chuck.deluge.engine.dsp;

import org.chuck.audio.ChuckUGen;
import org.chuck.deluge.firmware.dsp.filter.FirmwareFilter;
import org.chuck.deluge.firmware.dsp.filter.SVFilter;
import org.chuck.deluge.firmware.util.Q31;

/** Wrapper for the high-fidelity ported SVFilter. */
public class FirmwareSVFilter extends ChuckUGen {
  public final SVFilter firmware;
  private final int[] sampleBuffer = new int[1];

  public FirmwareSVFilter() {
    this.firmware = new SVFilter();
  }

  public void setConfig(float freq, float res, FirmwareFilter.FilterMode mode, float morph) {
    // ── Bit-Accurate Config Mapping ──
    // Hardware curveFrequency takes q31
    // Res mapping is squared taper in firmware
    firmware.setConfig(
        (int) (freq * 2147483647.0),
        (int) (res * 536870896.0), // ~0.25 Q31 max
        mode,
        (int) (morph * 2147483647.0),
        1 << 28); // Hardware neutral gain
  }

  @Override
  protected float compute(float input, long systemTime) {
    sampleBuffer[0] = Q31.fromFloat(input);

    // In-place processing
    firmware.doFilter(sampleBuffer, 0, 1, 1);

    return Q31.toFloat(sampleBuffer[0]);
  }
}
