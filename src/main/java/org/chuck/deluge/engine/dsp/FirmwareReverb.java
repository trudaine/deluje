package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.StereoUGen;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.firmware2.Reverb;

/**
 * Wrapper for the faithful firmware2 Reverb (Freeverb/Mutable/Digital). Note: firmware2 corrects
 * real bugs the old firmware/ reverb had (Freeverb wet2/cross-feed, the Mutable 2x output scale),
 * so the reverb tone changes — that's the faithful-to-C behaviour.
 */
public class FirmwareReverb extends StereoUGen {
  private final Reverb.Container firmware = new Reverb.Container();
  private final int[] inputBuffer = new int[1];
  private final int[][] outputLR = new int[1][2];

  @Override
  protected void computeStereo(float left, float right, long systemTime) {
    // ── Bit-Accurate Mono Summing ──
    // Hardware sums L+R and divides by 2 before entering the reverb.
    int l = Q31.fromFloat(left);
    int r = Q31.fromFloat(right);
    inputBuffer[0] = (l >> 1) + (r >> 1);

    // The firmware2 reverb ACCUMULATES into the output, so clear it for this sample's wet signal.
    outputLR[0][0] = 0;
    outputLR[0][1] = 0;
    firmware.process(inputBuffer, outputLR);

    lastOutChannels[0] = Q31.toFloat(outputLR[0][0]);
    lastOutChannels[1] = Q31.toFloat(outputLR[0][1]);
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
    if (m >= 0 && m < Reverb.Model.values().length) {
      firmware.setModel(Reverb.Model.values()[m]);
    }
  }
}
