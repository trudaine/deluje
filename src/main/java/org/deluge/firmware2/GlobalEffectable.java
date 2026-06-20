package org.deluge.firmware2;

import java.util.Arrays;

/**
 * Faithful port of the Deluge C++ GlobalEffectable / GlobalEffectableForClip logic. Handles the
 * track-level filter set, panning, and reverb send. Uses flat, interleaved stereo buffers (int[],
 * LRLRLR) matching firmware2 DSP conventions.
 */
public abstract class GlobalEffectable {
  public final FilterSet filterSet = new FilterSet();
  public int reverbSendKnob = Integer.MIN_VALUE;
  public boolean muted = false;

  // Track level output gains. Q31 convention: 2147483647 = unity (the verified pre-flat-buffer
  // behavior — the old firmware/engine/GlobalEffectable used Q31.ONE holders with (a*b)>>31
  // mults, validated by MasterFxRegressionTest/ReverbSendRoutingTest). The flat-buffer rewrite
  // briefly used 1<<30 defaults with <<1 staging, which attenuated every track by 4× at neutral
  // (the C combines its 2^27-neutral volumes with <<5 staging, mod_controllable_audio.cpp:222/258
  // — unity at neutral; Q31.ONE with >>31 is the same unity in this bridge's convention).
  public int postFXVolume = 134217728;
  public int postReverbVolume = 134217728;

  private int[] trackBuffer = new int[256];
  private int[] flatBuffer;

  public void renderOutput(int[] output, int numSamples, int[] reverbBuffer) {
    int requiredLen = numSamples * 2;
    if (trackBuffer.length < requiredLen) {
      trackBuffer = new int[requiredLen];
    }
    Arrays.fill(trackBuffer, 0, requiredLen, 0);

    // Render actual voices or child elements into trackBuffer
    postFXVolume = 134217728;
    postReverbVolume = 134217728;
    renderInternal(trackBuffer, numSamples, reverbBuffer);

    int maxValTrack = 0;
    for (int i = 0; i < numSamples * 2; i++) {
      maxValTrack = Math.max(maxValTrack, Math.abs(trackBuffer[i]));
    }
    if (org.deluge.firmware.engine.FirmwareAudioEngine.debugTelemetry && maxValTrack > 1000) {
      System.out.println(
          "[TELEMETRY GlobalEffectable] trackBuffer max absolute value: " + maxValTrack);
    }

    // Apply FilterSet
    if (filterSet.isOn()) {
      filterSet.renderLongStereo(trackBuffer, numSamples);
    }

    if (muted) {
      postFXVolume = 0;
    }

    // C: global_effectable_for_clip.cpp:84-86 — the reverb send amount is a volume curve of the
    // per-sound send knob, scaled by the clip's post-FX volume (reverbAmountAdjust =
    // volumePostFX>>1). INT_MIN knob → curve returns 0, so an off/unset send stays dry.
    int reverbAmountAdjust = postFXVolume >> 1;
    int reverbSendAmount =
        Functions.getFinalParameterValueVolume(
            reverbAmountAdjust, Functions.cableToLinearParamShortcut(reverbSendKnob));

    int reverbSendAmountAndPostFXVolume =
        Functions.lshiftAndSaturate(
            Functions.multiply_32x32_rshift32(postFXVolume, reverbSendAmount), 5);
    int postFXAndReverbVolumeL =
        Functions.lshiftAndSaturate(
            Functions.multiply_32x32_rshift32(postReverbVolume, postFXVolume), 5);

    for (int i = 0; i < numSamples; i++) {
      int l = trackBuffer[i * 2];
      int r = trackBuffer[i * 2 + 1];

      if (reverbSendAmount != 0 && reverbBuffer != null) {
        int mono = Functions.add_saturate(l, r);
        int revSend =
            Functions.lshiftAndSaturate(
                Functions.multiply_32x32_rshift32(mono, reverbSendAmountAndPostFXVolume), 1);
        reverbBuffer[i] = Functions.add_saturate(reverbBuffer[i], revSend);
      }

      // Apply track final gain and sum to main output
      int outL =
          Functions.lshiftAndSaturate(
              Functions.multiply_32x32_rshift32(l, postFXAndReverbVolumeL), 5);
      int outR =
          Functions.lshiftAndSaturate(
              Functions.multiply_32x32_rshift32(r, postFXAndReverbVolumeL), 5);

      output[i * 2] = Functions.add_saturate(output[i * 2], outL);
      output[i * 2 + 1] = Functions.add_saturate(output[i * 2 + 1], outR);
    }
  }

  protected abstract void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer);

  public void renderOutput(StereoSample[] buffer, int numSamples, Object unused) {
    int requiredLen = numSamples * 2;
    if (flatBuffer == null || flatBuffer.length < requiredLen) {
      flatBuffer = new int[requiredLen];
    }
    Arrays.fill(flatBuffer, 0, requiredLen, 0);
    int[] reverb = null;
    if (unused instanceof int[]) {
      reverb = (int[]) unused;
    }
    renderOutput(flatBuffer, numSamples, reverb);
    for (int i = 0; i < numSamples; i++) {
      buffer[i].l = flatBuffer[i * 2];
      buffer[i].r = flatBuffer[i * 2 + 1];
    }
  }
}
