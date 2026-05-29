package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.StereoUGen;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.reverb.ReverbContainer;
import org.chuck.deluge.firmware.util.Q31;

/** Wrapper for the high-fidelity ported ReverbContainer. */
public class FirmwareReverb extends StereoUGen {
  private final ReverbContainer firmware;
  private final int[] inputBuffer = new int[1];
  private final StereoSample[] outputSample = new StereoSample[1];

  public FirmwareReverb() {
    this.firmware = new ReverbContainer();
    this.outputSample[0] = new StereoSample();
  }

  @Override
  protected void computeStereo(float left, float right, long systemTime) {
    // ── Bit-Accurate Mono Summing ──
    // Hardware sums L+R and divides by 2 before entering Freeverb
    int l = Q31.fromFloat(left);
    int r = Q31.fromFloat(right);
    inputBuffer[0] = (l >> 1) + (r >> 1);

    firmware.process(inputBuffer, outputSample);

    lastOutChannels[0] = Q31.toFloat(outputSample[0].l);
    lastOutChannels[1] = Q31.toFloat(outputSample[0].r);
  }

  @Override
  protected void computeStereo(float input, long systemTime) {
    computeStereo(input, input, systemTime);
  }

  public void roomSize(float v) {
    firmware.setRoomSize(v);
  }

  public void damp(float v) {
    firmware.setDamping(v);
  }

  public void width(float v) {
    firmware.setWidth(v);
  }

  public void model(int m) {
    if (m >= 0 && m < ReverbContainer.Model.values().length) {
      firmware.setModel(ReverbContainer.Model.values()[m]);
    }
  }
}
