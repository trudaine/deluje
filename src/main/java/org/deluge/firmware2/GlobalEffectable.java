package org.deluge.firmware2;

import java.util.Arrays;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

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

  private static final VectorSpecies<Integer> I_SPECIES = IntVector.SPECIES_PREFERRED;

  /**
   * Vectorized abs-max over {@code a[0..len)} (bit-identical to a scalar Math.abs/Math.max loop).
   */
  static int absMax(int[] a, int len) {
    int i = 0;
    int max = 0;
    int bound = I_SPECIES.loopBound(len);
    if (bound > 0) {
      IntVector vmax = IntVector.zero(I_SPECIES);
      for (; i < bound; i += I_SPECIES.length()) {
        vmax = vmax.max(IntVector.fromArray(I_SPECIES, a, i).abs());
      }
      max = vmax.reduceLanes(VectorOperators.MAX);
    }
    for (; i < len; i++) {
      max = Math.max(max, Math.abs(a[i]));
    }
    return max;
  }

  private int[] trackBuffer = new int[256];
  private int[] trackReverbBuffer = new int[128];
  private int[] flatBuffer;
  private transient long[] tempOutLong = new long[256];
  private transient long[] tempRevLong = new long[128];

  public void renderOutput(long[] output, int numSamples, long[] reverbBuffer) {
    if (muted) {
      return;
    }
    int requiredLen = numSamples * 2;
    if (trackBuffer.length < requiredLen) {
      trackBuffer = new int[requiredLen];
    }
    Arrays.fill(trackBuffer, 0, requiredLen, 0);

    if (trackReverbBuffer.length < numSamples) {
      trackReverbBuffer = new int[numSamples];
    }
    Arrays.fill(trackReverbBuffer, 0, numSamples, 0);

    // Render actual voices or child elements into trackBuffer (32-bit)
    postFXVolume = 134217728;
    postReverbVolume = 134217728;
    renderInternal(trackBuffer, numSamples, trackReverbBuffer);

    // Silence-detection scan (a Java-side optimization, not faithful-C math): abs-max over the
    // track buffer, every track every block. max-of-abs is order-independent, so the SIMD reduction
    // is bit-identical to the scalar loop (including the Math.abs(MIN_VALUE)==MIN_VALUE edge).
    int maxValTrack = absMax(trackBuffer, numSamples * 2);
    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry && maxValTrack > 1000) {
      System.out.println(
          "[TELEMETRY GlobalEffectable] trackBuffer max absolute value: " + maxValTrack);
    }

    // Surgical Optimization: If the track is silent and has no active filter to decay,
    // skip the entire FX, panning, and summing loop to reclaim massive CPU cycles!
    if (maxValTrack == 0 && !filterSet.isOn()) {
      return;
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
        // Sum reverb send in 64-bit
        reverbBuffer[i] += (long) trackReverbBuffer[i] + revSend;
      }

      // Apply track final gain and sum to main output in 64-bit (infinite headroom!)
      int outL =
          Functions.lshiftAndSaturate(
              Functions.multiply_32x32_rshift32(l, postFXAndReverbVolumeL), 5);
      int outR =
          Functions.lshiftAndSaturate(
              Functions.multiply_32x32_rshift32(r, postFXAndReverbVolumeL), 5);

      output[i * 2] += outL;
      output[i * 2 + 1] += outR;
    }
  }

  public void renderOutput(int[] output, int numSamples, int[] reverbBuffer) {
    int requiredLen = numSamples * 2;
    if (tempOutLong == null || tempOutLong.length < requiredLen) {
      tempOutLong = new long[requiredLen];
    }
    Arrays.fill(tempOutLong, 0, requiredLen, 0L);

    long[] revLong = null;
    if (reverbBuffer != null) {
      if (tempRevLong == null || tempRevLong.length < numSamples) {
        tempRevLong = new long[numSamples];
      }
      Arrays.fill(tempRevLong, 0, numSamples, 0L);
      revLong = tempRevLong;
    }

    renderOutput(tempOutLong, numSamples, revLong);

    for (int i = 0; i < numSamples * 2; i++) {
      output[i] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, tempOutLong[i]));
    }
    if (reverbBuffer != null) {
      for (int i = 0; i < numSamples; i++) {
        reverbBuffer[i] =
            (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, tempRevLong[i]));
      }
    }
  }

  public void renderOutputSumming(int[] output, int numSamples, int[] reverbBuffer) {
    int requiredLen = numSamples * 2;
    if (tempOutLong == null || tempOutLong.length < requiredLen) {
      tempOutLong = new long[requiredLen];
    }
    Arrays.fill(tempOutLong, 0, requiredLen, 0L);

    long[] revLong = null;
    if (reverbBuffer != null) {
      if (tempRevLong == null || tempRevLong.length < numSamples) {
        tempRevLong = new long[numSamples];
      }
      Arrays.fill(tempRevLong, 0, numSamples, 0L);
      revLong = tempRevLong;
    }

    renderOutput(tempOutLong, numSamples, revLong);

    for (int i = 0; i < numSamples * 2; i++) {
      long summed = (long) output[i] + tempOutLong[i];
      output[i] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, summed));
    }
    if (reverbBuffer != null) {
      for (int i = 0; i < numSamples; i++) {
        long summed = (long) reverbBuffer[i] + tempRevLong[i];
        reverbBuffer[i] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, summed));
      }
    }
  }

  protected abstract void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer);

  public void renderOutput(StereoSample[] buffer, int numSamples, Object unused) {
    int requiredLen = numSamples * 2;
    if (tempOutLong == null || tempOutLong.length < requiredLen) {
      tempOutLong = new long[requiredLen];
    }
    Arrays.fill(tempOutLong, 0, requiredLen, 0L);

    long[] revLong = null;
    if (unused instanceof int[]) {
      if (tempRevLong == null || tempRevLong.length < numSamples) {
        tempRevLong = new long[numSamples];
      }
      Arrays.fill(tempRevLong, 0, numSamples, 0L);
      revLong = tempRevLong;
    }

    renderOutput(tempOutLong, numSamples, revLong);

    for (int i = 0; i < numSamples; i++) {
      buffer[i].l =
          (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, tempOutLong[i * 2]));
      buffer[i].r =
          (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, tempOutLong[i * 2 + 1]));
    }
    if (unused instanceof int[]) {
      int[] reverb = (int[]) unused;
      for (int i = 0; i < numSamples; i++) {
        reverb[i] = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, tempRevLong[i]));
      }
    }
  }
}
