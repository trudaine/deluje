package org.chuck.deluge.firmware.engine;

import static org.chuck.deluge.firmware.util.Q31.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.compressor.RMSFeedbackCompressor;
import org.chuck.deluge.firmware.dsp.filter.FilterSet;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.util.Q31;

/** Base class for things that can have global FX applied (Synths, Kits). */
public abstract class GlobalEffectable {
  public final FilterSet filterSet = new FilterSet();
  public final Stutterer stutterer = new Stutterer();
  public final ParamManager paramManager = new ParamManager();

  public void renderOutput(
      StereoSample[] output, int numSamples, int[] reverbBuffer) {
    StereoSample[] trackBuffer = new StereoSample[numSamples];
    for (int i = 0; i < numSamples; i++) trackBuffer[i] = new StereoSample();

    // 1. Render actual voices/samples
    renderInternal(trackBuffer, numSamples, paramManager);

    // 2. Process Filters
    processFilters(trackBuffer, numSamples);

    // 3. Process Global FX and Volume
    // Neutral values in Q31
    int postFXVolume = Q31.ONE; 
    int postReverbVolume = Q31.ONE;
    int reverbSendAmount = 0;
    int pan = 0; // Center in Q31 is 0

    processReverbSendAndVolume(
        trackBuffer, reverbBuffer, postFXVolume, postReverbVolume, reverbSendAmount, pan);

    // 4. Sum to master output
    for (int i = 0; i < numSamples; i++) {
      output[i].l = addSaturate(output[i].l, trackBuffer[i].l);
      output[i].r = addSaturate(output[i].r, trackBuffer[i].r);
    }
  }

  public void processFilters(StereoSample[] buffer, int numSamples) {
      // ── Bit-Accurate Stereo Filter Rendering ──
      filterSet.renderStereoInterleaved(buffer, numSamples);
  }

  public void processReverbSendAndVolume(
      StereoSample[] buffer,
      int[] reverbBuffer,
      int postFXVolume,
      int postReverbVolume,
      int reverbSendAmount,
      int pan) {

    // ── Bit-Accurate Gain Staging ──
    int finalGain = Q31.mult(postFXVolume, postReverbVolume);

    for (int i = 0; i < buffer.length; i++) {
      StereoSample sample = buffer[i];
      
      // Send to reverb (mono sum)
      if (reverbSendAmount != 0 && reverbBuffer != null) {
        int mono = addSaturate(sample.l, sample.r);
        reverbBuffer[i] = addSaturate(reverbBuffer[i], Q31.mult(mono, reverbSendAmount));
      }

      // Apply Final Volume and Pan
      // (Simplified pan for now: just apply finalGain)
      sample.l = Q31.mult(sample.l, finalGain);
      sample.r = Q31.mult(sample.r, finalGain);
    }
  }

  protected abstract void renderInternal(
      StereoSample[] buffer, int numSamples, ParamManager paramManager);
}
