package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * Faithful-port checks for the firmware2 sample-engine foundation (Phase A/B): SampleHolder duration
 * helpers and SamplePlaybackGuide.setupPlaybackBounds. Pure position math re-derived from the C
 * (sample_holder.cpp, sample_playback_guide.cpp) — no firmware/ oracle.
 */
class SampleEngineTest {

  private static Sample sample(int numChannels, int byteDepth, int startPosBytes, long lengthInSamples) {
    Sample s = new Sample();
    s.numChannels = numChannels;
    s.byteDepth = byteDepth;
    s.audioDataStartPosBytes = startPosBytes;
    s.lengthInSamples = lengthInSamples;
    return s;
  }

  @Test
  void sampleHolderDurationMatchesC() {
    Random r = new Random(9);
    for (int n = 0; n < 50_000; n++) {
      long len = r.nextInt(2_000_000);
      long start = r.nextInt(1_000_000);
      long end = r.nextInt(2_000_000);
      SampleHolder h = new SampleHolder();
      h.audioFile = sample(2, 3, 44, len);
      h.startPos = start;
      h.endPos = end;
      // C: getEndPos(false) = min(endPos, lengthInSamples); getEndPos(true) = endPos.
      assertEquals(Math.min(end, len), h.getEndPos(false));
      assertEquals(end, h.getEndPos(true));
      assertEquals(Math.min(end, len) - start, h.getDurationInSamples(false));
      assertEquals(end - start, h.getDurationInSamples(true));
    }
  }

  @Test
  void setupPlaybackBoundsMatchesC() {
    Random r = new Random(10);
    for (int n = 0; n < 50_000; n++) {
      int numChannels = 1 + r.nextInt(2);
      int byteDepth = 2 + r.nextInt(2); // 2 or 3
      int startPosBytes = r.nextInt(100);
      long len = 1000 + r.nextInt(1_000_000);
      int start = r.nextInt(500_000);
      int end = r.nextInt(500_000);

      Sample s = sample(numChannels, byteDepth, startPosBytes, len);
      SampleHolder h = new SampleHolder();
      h.audioFile = s;
      h.startPos = start;
      h.endPos = end;
      int bytesPerSample = numChannels * byteDepth;

      for (boolean reversed : new boolean[] {false, true}) {
        SamplePlaybackGuide g = new SamplePlaybackGuide();
        g.audioFileHolder = h;
        g.setupPlaybackBounds(reversed);

        int startSample;
        int endSample;
        if (!reversed) {
          startSample = (int) h.startPos;
          endSample = (int) h.getEndPos();
        } else {
          startSample = (int) h.getEndPos() - 1;
          endSample = (int) h.startPos - 1;
        }
        int expStart = startPosBytes + startSample * bytesPerSample;
        int expEnd = startPosBytes + endSample * bytesPerSample;
        assertEquals(reversed ? -1 : 1, g.playDirection);
        assertEquals(expStart, g.startPlaybackAtByte, "start reversed=" + reversed);
        assertEquals(expEnd, g.endPlaybackAtByte, "end reversed=" + reversed);
      }
    }
  }

  private static Sample constSample(int numChannels, int frames, int value) {
    Sample s = new Sample();
    s.numChannels = numChannels;
    s.byteDepth = 3;
    s.lengthInSamples = frames;
    s.data = new int[frames * numChannels];
    java.util.Arrays.fill(s.data, value);
    return s;
  }

  /**
   * A constant input must produce a constant resampled output: at unity rate the phase stays 0, so the
   * interpolator returns kernelSum(phase0)*value, and a flat amplitude ramp gives the same MAC each
   * sample. Verifies the full-precision interpolation + amplitude path end-to-end (mono + stereo).
   */
  @Test
  void readResampledConstantSignalIsConstant() {
    for (int numChannels = 1; numChannels <= 2; numChannels++) {
      int value = 1_000_000;
      SampleReader r = new SampleReader();
      r.sample = constSample(numChannels, 4096, value);
      r.init(2000);

      int amp = 1 << 27;
      int[] amplitude = {amp};
      int n = 200;
      int[] osc = new int[n * numChannels];
      r.readResampled(osc, n, numChannels, 0 /*kernel*/, 1 << 24 /*unity*/, amplitude, 0 /*flat*/);

      // Independent expected: interpolateWide of an all-`value` history at phase 0, then the MAC.
      int[] hist = new int[16];
      java.util.Arrays.fill(hist, value);
      int[] sr = SincInterpolator.interpolateWide(hist, hist, numChannels, 0, 0);
      int expected = Functions.multiply_accumulate_32x32_rshift32_rounded(0, sr[0], amp);
      for (int i = 0; i < osc.length; i++) {
        assertEquals(expected, osc[i], "constant resample ch=" + numChannels + " idx=" + i);
      }
    }
  }

  /** Re-derive the readResampled loop independently and compare (catches loop-transcription bugs). */
  @Test
  void readResampledMatchesReDerivation() {
    Random r = new Random(44);
    int numChannels = 2;
    int frames = 8192;
    Sample s = new Sample();
    s.numChannels = numChannels;
    s.byteDepth = 3;
    s.lengthInSamples = frames;
    s.data = new int[frames * numChannels];
    for (int i = 0; i < s.data.length; i++) s.data[i] = r.nextInt() >> 1;

    int phaseIncrement = (1 << 24) + r.nextInt(1 << 23); // ~1.0..1.5x
    int whichKernel = 3;
    int n = 300;

    SampleReader rd = new SampleReader();
    rd.sample = s;
    rd.init(1000);
    int[] osc = new int[n * numChannels];
    int[] amp = {1 << 26};
    rd.readResampled(osc, n, numChannels, whichKernel, phaseIncrement, amp, 1 << 10);

    // Independent re-derivation with the same model.
    int[] bufL = new int[16];
    int[] bufR = new int[16];
    int playPos = 1000 - 15;
    for (int i = 0; i < 16; i++) {
      for (int j = 15; j >= 1; j--) { bufL[j] = bufL[j - 1]; bufR[j] = bufR[j - 1]; }
      int base = playPos * numChannels;
      bufL[0] = (playPos >= 0 && playPos < frames) ? s.data[base] : 0;
      bufR[0] = (playPos >= 0 && playPos < frames) ? s.data[base + 1] : 0;
      playPos++;
    }
    int oscPos = 0;
    int amplitude = 1 << 26;
    boolean done = false;
    int o = 0;
    int[] expOsc = new int[n * numChannels];
    for (int step = 0; step < n; step++) {
      if (!done) {
        done = true;
      } else {
        oscPos += phaseIncrement;
        int jump = oscPos >>> 24;
        if (jump != 0) {
          oscPos &= 16777215;
          if (jump > 16) { playPos += (jump - 16); jump = 16; }
          for (int k = 0; k < jump; k++) {
            for (int j = 15; j >= 1; j--) { bufL[j] = bufL[j - 1]; bufR[j] = bufR[j - 1]; }
            int base = playPos * numChannels;
            bufL[0] = (playPos >= 0 && playPos < frames) ? s.data[base] : 0;
            bufR[0] = (playPos >= 0 && playPos < frames) ? s.data[base + 1] : 0;
            playPos++;
          }
        }
      }
      int[] sr = SincInterpolator.interpolateWide(bufL, bufR, numChannels, whichKernel, oscPos);
      amplitude += 1 << 10;
      expOsc[o] = Functions.multiply_accumulate_32x32_rshift32_rounded(expOsc[o], sr[0], amplitude);
      o++;
      expOsc[o] = Functions.multiply_accumulate_32x32_rshift32_rounded(expOsc[o], sr[1], amplitude);
      o++;
    }
    org.junit.jupiter.api.Assertions.assertArrayEquals(expOsc, osc);
  }
}

