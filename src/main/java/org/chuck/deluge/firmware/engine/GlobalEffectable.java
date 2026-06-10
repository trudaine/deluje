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

  /**
   * Per-sound reverb send KNOB (raw Q31 UNPATCHED_REVERB_SEND_AMOUNT). INT_MIN = off (the Deluge
   * default). The actual send amount is derived per block from this knob and the post-FX volume via
   * the C volume curve (see renderOutput), so center-volume coupling and the parabola are faithful.
   */
  public int reverbSendKnob = Integer.MIN_VALUE;

  /**
   * Firmware SRR/bitcrush and mod-FX can attenuate the post-FX gain before reverb-send / final
   * volume. Kept as a mutable holder so renderInternal can update it for the current block.
   */
  protected final int[] postFXVolumeHolder = {Q31.ONE};

  protected final int[] postReverbVolumeHolder = {Q31.ONE};

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
    postFXVolumeHolder[0] = Q31.ONE;
    postReverbVolumeHolder[0] = Q31.ONE;
    renderInternal(trackBuffer, numSamples, paramManager);

    // 3. Process Filters
    processFilters(trackBuffer, numSamples);

    // 4. Process Global FX and Volume
    int postFXVolume = postFXVolumeHolder[0];
    int postReverbVolume = postReverbVolumeHolder[0];
    int pan = 0; // Center in Q31 is 0

    // C: global_effectable_for_clip.cpp:84-86 — the reverb send amount is a volume curve of the
    // per-sound send knob, scaled by the clip's post-FX volume (reverbAmountAdjust = volumePostFX>>1,
    // song.cpp:2470). INT_MIN knob → patched -536870912 → curve returns 0, so an off/unset send stays
    // dry. cableToLinearParamShortcut is >>2 (functions.cpp:260).
    int reverbAmountAdjust = postFXVolume >> 1;
    int reverbSendAmount =
        org.chuck.deluge.firmware2.Functions.getFinalParameterValueVolume(
            reverbAmountAdjust,
            org.chuck.deluge.firmware2.Functions.cableToLinearParamShortcut(reverbSendKnob));

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
