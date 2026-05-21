package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.filter.FilterSet;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.util.Q31;

/** Base class for things that can have global FX applied (Synths, Kits). */
public abstract class GlobalEffectable {
  public final FilterSet filterSet = new FilterSet();
  public final Stutterer stutterer = new Stutterer();
  public final ParamManager paramManager = new ParamManager();

  // Pre-allocate to avoid GC jitter
  private final StereoSample[] trackBuffer = new StereoSample[128];

  public GlobalEffectable() {
    for (int i = 0; i < 128; i++) trackBuffer[i] = new StereoSample();
  }

  public void renderOutput(StereoSample[] output, int numSamples, int[] reverbBuffer) {
    // 1. Clear pre-allocated buffer
    for (int i = 0; i < numSamples; i++) {
      trackBuffer[i].l = 0;
      trackBuffer[i].r = 0;
    }

    // 2. Render actual voices/samples
    renderInternal(trackBuffer, numSamples, paramManager);

    // 3. Process Filters
    processFilters(trackBuffer, numSamples);

    // 4. Process Global FX and Volume
    int postFXVolume = Q31.ONE;
    int postReverbVolume = Q31.ONE;
    int reverbSendAmount = 0;
    int pan = 0; // Center in Q31 is 0

    processReverbSendAndVolume(
        trackBuffer, reverbBuffer, postFXVolume, postReverbVolume, reverbSendAmount, pan);

    // 5. Sum to master output
    for (int i = 0; i < numSamples; i++) {
      output[i].l = addSaturate(output[i].l, trackBuffer[i].l);
      output[i].r = addSaturate(output[i].r, trackBuffer[i].r);
    }
  }

  public void processFilters(StereoSample[] buffer, int numSamples) {
    filterSet.renderStereoInterleaved(buffer, numSamples);
  }

  public void processReverbSendAndVolume(
      StereoSample[] buffer,
      int[] reverbBuffer,
      int postFXVolume,
      int postReverbVolume,
      int reverbSendAmount,
      int pan) {

    int finalGain = Q31.mult(postFXVolume, postReverbVolume);

    for (int i = 0; i < buffer.length; i++) {
      StereoSample sample = buffer[i];
      if (reverbSendAmount != 0 && reverbBuffer != null) {
        int mono = addSaturate(sample.l, sample.r);
        reverbBuffer[i] = addSaturate(reverbBuffer[i], Q31.mult(mono, reverbSendAmount));
      }
      sample.l = Q31.mult(sample.l, finalGain);
      sample.r = Q31.mult(sample.r, finalGain);
    }
  }

  protected abstract void renderInternal(
      StereoSample[] buffer, int numSamples, ParamManager paramManager);
}
