package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.compressor.RMSFeedbackCompressor;
import org.chuck.deluge.firmware.dsp.delay.Delay;
import org.chuck.deluge.firmware.dsp.filter.LpLadderFilter;
import org.chuck.deluge.firmware.dsp.filter.FilterSet;
import org.chuck.deluge.firmware.modulation.params.ParamManager;

/**
 * Base class for things that have Track FX (Synths, Kits, Audio Clips). Ports
 * GlobalEffectableForClip.cpp.
 */
public abstract class GlobalEffectable {
  public final FilterSet filterSet = new FilterSet();
  public final RMSFeedbackCompressor trackCompressor = new RMSFeedbackCompressor();
  public final Delay trackDelay = new Delay();
  public final Delay.State delayState = new Delay.State();

  public void renderOutput(
      StereoSample[] output, int numSamples, ParamManager paramManager, int[] reverbBuffer) {
    StereoSample[] trackBuffer = new StereoSample[numSamples];
    for (int i = 0; i < numSamples; i++) trackBuffer[i] = new StereoSample();

    // 1. Render actual voices/samples
    renderInternal(trackBuffer, numSamples, paramManager);

    // 2. Process Filters
    processFilters(trackBuffer, numSamples);

    // 3. Process Global FX and Volume
    int postFXVolume = 134217728; // neutral
    int postReverbVolume = 134217728;
    int reverbSendAmount = 0;
    int pan = 0;

    processReverbSendAndVolume(
        trackBuffer, reverbBuffer, postFXVolume, postReverbVolume, reverbSendAmount, pan);

    // 4. Sum to master output
    for (int i = 0; i < numSamples; i++) {
      output[i].l += trackBuffer[i].l;
      output[i].r += trackBuffer[i].r;
    }
  }

  public void processFilters(StereoSample[] buffer, int numSamples) {
      int[] l = new int[numSamples];
      int[] r = new int[numSamples];
      for (int i = 0; i < numSamples; i++) { l[i] = buffer[i].l; r[i] = buffer[i].r; }
      
      filterSet.renderStereo(l, 0, numSamples);
      filterSet.renderStereo(r, 0, numSamples); // Simplified: should use proper interleaving
      
      for (int i = 0; i < numSamples; i++) { buffer[i].l = l[i]; buffer[i].r = r[i]; }
  }

  public void processReverbSendAndVolume(
      StereoSample[] buffer,
      int[] reverbBuffer,
      int postFXVolume,
      int postReverbVolume,
      int reverbSendAmount,
      int pan) {

    int reverbSendAmountAndPostFXVolume =
        multiply_32x32_rshift32(postFXVolume, reverbSendAmount) << 5;
    int postFXAndReverbVolumeL = multiply_32x32_rshift32(postReverbVolume, postFXVolume) << 5;
    int postFXAndReverbVolumeR = postFXAndReverbVolumeL;

    for (int i = 0; i < buffer.length; i++) {
      StereoSample sample = buffer[i];
      // Send to reverb
      if (reverbSendAmount != 0 && reverbBuffer != null) {
        reverbBuffer[i] +=
            multiply_32x32_rshift32(sample.l + sample.r, reverbSendAmountAndPostFXVolume) << 1;
      }

      // Apply post-fx and post-reverb-send volume
      sample.l = multiply_32x32_rshift32(sample.l, postFXAndReverbVolumeL) << 5;
      sample.r = multiply_32x32_rshift32(sample.r, postFXAndReverbVolumeR) << 5;
    }
  }

  protected abstract void renderInternal(
      StereoSample[] buffer, int numSamples, ParamManager paramManager);
}
