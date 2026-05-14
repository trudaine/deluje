package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.StereoUGen;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.reverb.freeverb.Freeverb;

/** Wrapper for the high-fidelity ported Freeverb. */
public class FirmwareReverb extends StereoUGen {
  private final Freeverb firmware;
  private final int[] inputBuffer = new int[1];
  private final StereoSample[] outputSample = new StereoSample[1];

  public FirmwareReverb() {
    this.firmware = new Freeverb();
    this.outputSample[0] = new StereoSample();
  }

  @Override
  protected void computeStereo(float left, float right, long systemTime) {
    // Firmware Freeverb.process takes a mono int[] input and StereoSample[] output
    // We approximate mono input by averaging
    inputBuffer[0] = (int) (((left + right) * 0.5f) * 2147483648.0);

    firmware.process(inputBuffer, outputSample);

    lastOutChannels[0] = (float) (outputSample[0].l / 2147483648.0);
    lastOutChannels[1] = (float) (outputSample[0].r / 2147483648.0);
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
}
