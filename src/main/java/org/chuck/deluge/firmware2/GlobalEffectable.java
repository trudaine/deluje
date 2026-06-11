package org.chuck.deluge.firmware2;

import java.util.Arrays;
import org.chuck.deluge.firmware.dsp.StereoSample;

/**
 * Faithful port of the Deluge C++ GlobalEffectable / GlobalEffectableForClip logic. Handles the
 * track-level filter set, panning, and reverb send. Uses flat, interleaved stereo buffers (int[],
 * LRLRLR) matching firmware2 DSP conventions.
 */
public abstract class GlobalEffectable {
  public final FilterSet filterSet = new FilterSet();
  public int reverbSendKnob = Integer.MIN_VALUE;

  // Track level output gains (Q30/Q31), matching postFXVolume / postReverbVolume
  protected int postFXVolume = 1073741824; // 1 << 30 (Q30 "1")
  protected int postReverbVolume = 1073741824; // 1 << 30

  private int[] trackBuffer = new int[256];

  public void renderOutput(int[] output, int numSamples, int[] reverbBuffer) {
    int requiredLen = numSamples * 2;
    if (trackBuffer.length < requiredLen) {
      trackBuffer = new int[requiredLen];
    }
    Arrays.fill(trackBuffer, 0, requiredLen, 0);

    // Render actual voices or child elements into trackBuffer
    postFXVolume = 1073741824;
    postReverbVolume = 1073741824;
    renderInternal(trackBuffer, numSamples, reverbBuffer);

    // Apply FilterSet
    if (filterSet.isOn()) {
      filterSet.renderLongStereo(trackBuffer, numSamples);
    }

    // Process reverb send and track volume
    int reverbAmountAdjust = postFXVolume >> 1;
    int reverbSendAmount =
        Functions.getFinalParameterValueVolume(
            reverbAmountAdjust, Functions.cableToLinearParamShortcut(reverbSendKnob));

    int finalGain = Functions.multiply_32x32_rshift32(postFXVolume, postReverbVolume) << 1;

    for (int i = 0; i < numSamples; i++) {
      int l = trackBuffer[i * 2];
      int r = trackBuffer[i * 2 + 1];

      if (reverbSendAmount != 0 && reverbBuffer != null) {
        int mono = Functions.add_saturate(l, r);
        reverbBuffer[i] =
            Functions.add_saturate(
                reverbBuffer[i], Functions.multiply_32x32_rshift32(mono, reverbSendAmount) << 1);
      }

      // Apply track final gain and sum to main output
      int outL = Functions.multiply_32x32_rshift32(l, finalGain) << 1;
      int outR = Functions.multiply_32x32_rshift32(r, finalGain) << 1;

      output[i * 2] = Functions.add_saturate(output[i * 2], outL);
      output[i * 2 + 1] = Functions.add_saturate(output[i * 2 + 1], outR);
    }
  }

  protected abstract void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer);

  public void renderOutput(StereoSample[] buffer, int numSamples, Object unused) {
    int[] flat = new int[numSamples * 2];
    int[] reverb = null;
    if (unused instanceof int[]) {
      reverb = (int[]) unused;
    }
    renderOutput(flat, numSamples, reverb);
    for (int i = 0; i < numSamples; i++) {
      buffer[i].l = flat[i * 2];
      buffer[i].r = flat[i * 2 + 1];
    }
  }
}
