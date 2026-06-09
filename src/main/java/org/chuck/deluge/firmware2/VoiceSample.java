package org.chuck.deluge.firmware2;

/**
 * Minimal pitched sample voice for the firmware2 sample engine (the pitched-playback milestone of
 * docs/FIRMWARE2_SAMPLE_ENGINE_PLAN.md). Plays an in-RAM {@link Sample} once at a given pitch via
 * {@link SampleReader}, choosing the native (1:1) or resampled path exactly as the C does
 * ({@code phaseIncrement == kMaxSampleValue} ⇒ native; else windowed-sinc with the C's
 * {@link Functions#getWhichKernel}).
 *
 * <p>Scope: a single non-looping play head, no time-stretch (that is {@code TimeStretcher.hopEnd},
 * a later increment), no cluster/loop/end-of-sample envelope handling yet.
 */
public class VoiceSample {

  /** C: kMaxSampleValue (1 << 24) — unity phaseIncrement ⇒ no resampling. */
  static final int K_MAX_SAMPLE_VALUE = 16777216;

  public final SampleReader reader = new SampleReader();
  public boolean active;

  /** Exclusive end frame in the play direction (forwards: end > start; reverse: end < start). */
  public int endFrame;
  public boolean looping;
  public int loopStartFrame;

  // ── Time-stretch state (the two-play-head crossfade) ──
  public final TimeStretcher timeStretcher = new TimeStretcher();
  public final SampleReader olderReader = new SampleReader();

  /** Begin time-stretched playback from {@code startFrame}; the first render hops immediately. */
  public void setupTimeStretch(Sample sample, int startFrame, int playDirection) {
    reader.sample = sample;
    reader.playDirection = playDirection;
    reader.init(startFrame);
    timeStretcher.samplePosBig = (long) startFrame << 24;
    timeStretcher.samplesTilHopEnd = 0; // ⇒ hop on the first render
    timeStretcher.crossfadeProgress = K_MAX_SAMPLE_VALUE;
    timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = false;
    timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = false;
    active = true;
  }

  /**
   * Time-stretched render (pitch decoupled from speed): the two-play-head crossfade assembly that ties
   * {@link TimeStretcher#hopEnd} together with the older/newer {@link SampleReader}s
   * (voice_sample.cpp:1160-1432, the in-RAM / no-cache / TIME_STRETCH_ENABLE_BUFFER=0 path).
   *
   * <p>{@code samplePosBig} advances at the combined (pitch × stretch) rate and drives hop placement;
   * each head reads at {@code phaseIncrement} (pitch). At each hop the newer head is forked into the
   * older head, repositioned by {@code hopEnd}, and the two are linearly crossfaded.
   *
   * <p>{@code oldHeadBytePos} comes from {@link SampleReader#getPlayByteLowLevel} (with the
   * interpolation-buffer compensation), matching the C.
   *
   * @param amplitude single-element voice amplitude ramp accumulator
   */
  public void renderTimeStretched(int[] osc, int numSamples, int numChannels, int phaseIncrement,
      int timeStretchRatio, int[] amplitude, int amplitudeIncrement) {
    if (!active) {
      return;
    }
    Sample sample = reader.sample;
    int bps = sample.byteDepth * sample.numChannels;
    boolean native_ = (phaseIncrement == K_MAX_SAMPLE_VALUE);
    int whichKernel = native_ ? 0 : Functions.getWhichKernel(phaseIncrement);
    long combinedIncrement = ((phaseIncrement & 0xFFFFFFFFL) * (timeStretchRatio & 0xFFFFFFFFL)) >>> 24; // C:614

    int produced = 0;
    while (produced < numSamples) {
      // C:1162-1173 — time to hop?
      if (timeStretcher.samplesTilHopEnd <= 0) {
        int samplePos = timeStretcher.getSamplePos(reader.playDirection);
        int oldHeadBytePos = reader.getPlayByteLowLevel(true); // C:284 (compensates for the interp buffer)
        olderReader.copyStateFrom(reader); // fork the older head before repositioning the newer one
        int[] hop = timeStretcher.hopEnd(
            sample, null, TimeStretcher.LoopType.NONE, oldHeadBytePos, samplePos, phaseIncrement,
            timeStretchRatio, combinedIncrement, reader.playDirection, Functions.getNoise(),
            olderReader.oscPos);
        if (timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]) {
          int newFrame = (hop[0] - sample.audioDataStartPosBytes) / bps; // setupNewPlayHead (in-RAM)
          reader.init(newFrame);
          reader.oscPos = hop[1]; // C:998
        }
      }

      int numThis = Math.min(numSamples - produced, timeStretcher.samplesTilHopEnd);
      if (numThis <= 0) {
        break;
      }

      // C:1206-1285 — crossfade amplitude envelopes for the two heads.
      boolean olderAudible = timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER]
          && timeStretcher.crossfadeProgress < K_MAX_SAMPLE_VALUE;
      int preCacheAmplitude = amplitude[0] >> 1;
      int preCacheAmplitudeIncrement = amplitudeIncrement >> 1;
      int newerSourceAmplitudeNow;
      int newerAmplitudeIncrementNow;
      int olderSourceAmplitudeNow = 0;
      int olderAmplitudeIncrementNow = 0;
      if (olderAudible) {
        int newerHopAmplitudeNow = timeStretcher.crossfadeProgress << 7;
        int olderHopAmplitudeNow = 2147483647 - newerHopAmplitudeNow;
        timeStretcher.crossfadeProgress += timeStretcher.crossfadeIncrement * numThis;
        int newerHopAmplitudeAfter = Functions.lshiftAndSaturateUnknown(timeStretcher.crossfadeProgress, 7);
        int newerHopAmplitudeIncrement = (newerHopAmplitudeAfter - newerHopAmplitudeNow) / numThis;
        int hopAmplitudeChange = Functions.multiply_32x32_rshift32(preCacheAmplitude, newerHopAmplitudeIncrement) << 1;
        newerAmplitudeIncrementNow = preCacheAmplitudeIncrement + hopAmplitudeChange;
        newerSourceAmplitudeNow = Functions.multiply_32x32_rshift32(preCacheAmplitude, newerHopAmplitudeNow) << 1;
        olderAmplitudeIncrementNow = preCacheAmplitudeIncrement - hopAmplitudeChange;
        olderSourceAmplitudeNow = Functions.multiply_32x32_rshift32(preCacheAmplitude, olderHopAmplitudeNow) << 1;
      } else {
        newerSourceAmplitudeNow = preCacheAmplitude;
        newerAmplitudeIncrementNow = preCacheAmplitudeIncrement;
      }

      int[] tmp = new int[numThis * numChannels];
      // C:1300-1332 — newer head
      if (timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]) {
        readHead(reader, tmp, numThis, numChannels, native_, whichKernel, phaseIncrement,
            newerSourceAmplitudeNow, newerAmplitudeIncrementNow);
      }
      // C:1337-1367 — older head
      if (olderAudible) {
        readHead(olderReader, tmp, numThis, numChannels, native_, whichKernel, phaseIncrement,
            olderSourceAmplitudeNow, olderAmplitudeIncrementNow);
        if (timeStretcher.crossfadeProgress >= K_MAX_SAMPLE_VALUE) { // C:1370 — older finished
          timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = false;
        }
      }

      int base = produced * numChannels;
      for (int i = 0; i < tmp.length; i++) {
        osc[base + i] += tmp[i];
      }

      timeStretcher.samplePosBig += combinedIncrement * numThis * reader.playDirection; // C:1396
      timeStretcher.samplesTilHopEnd -= numThis; // C:1432
      produced += numThis;
    }
  }

  private static void readHead(SampleReader head, int[] tmp, int numThis, int numChannels, boolean native_,
      int whichKernel, int phaseIncrement, int sourceAmplitude, int amplitudeIncrement) {
    int[] amp = {sourceAmplitude};
    if (native_) {
      head.readNative(tmp, numThis, numChannels, amp, amplitudeIncrement);
    } else {
      head.readResampled(tmp, numThis, numChannels, whichKernel, phaseIncrement, amp, amplitudeIncrement);
    }
  }

  /** One-shot, no loop — plays {@code sample} from {@code startFrame} to the sample end. */
  public void setup(Sample sample, int startFrame, int playDirection) {
    int end = (playDirection == 1) ? (int) sample.lengthInSamples : -1;
    setup(sample, startFrame, end, playDirection, false, startFrame);
  }

  /** Full setup with explicit end + optional looping. */
  public void setup(Sample sample, int startFrame, int endFrame, int playDirection, boolean looping,
                    int loopStartFrame) {
    reader.sample = sample;
    reader.playDirection = playDirection;
    reader.init(startFrame);
    this.endFrame = endFrame;
    this.looping = looping;
    this.loopStartFrame = loopStartFrame;
    active = true;
  }

  /** Input frames remaining before the end, in the play direction. */
  private long framesUntilEnd() {
    return (long) (endFrame - reader.playPos) * reader.playDirection;
  }

  /**
   * Render {@code numSamples} into {@code osc} (interleaved when stereo), accumulating, with the given
   * pitch and amplitude ramp. Picks native vs resampled like the C, and stops (one-shot) or wraps
   * (looping) at {@link #endFrame}. Block-chunked so a render never reads past the end; the exact
   * sample-accurate window math is the C considerUpcomingWindow's job (adapter approximation here for
   * the resampled case).
   */
  public void render(int[] osc, int numSamples, int numChannels, int phaseIncrement,
                     int[] amplitude, int amplitudeIncrement) {
    if (!active) {
      return;
    }
    boolean native_ = (phaseIncrement == K_MAX_SAMPLE_VALUE);
    int produced = 0;
    while (produced < numSamples) {
      long left = framesUntilEnd();
      if (left <= 0) {
        if (looping) {
          reader.init(loopStartFrame);
          left = framesUntilEnd();
        }
        if (left <= 0) { // one-shot ended, or degenerate loop
          active = false;
          return;
        }
      }

      // How many output samples we can emit before consuming `left` input frames.
      long outAvail = native_ ? left : Math.max(1, (left << 24) / phaseIncrement);
      int chunk = (int) Math.min(numSamples - produced, outAvail);

      int[] tmp = new int[chunk * numChannels];
      if (native_) {
        reader.readNative(tmp, chunk, numChannels, amplitude, amplitudeIncrement);
      } else {
        reader.readResampled(tmp, chunk, numChannels, Functions.getWhichKernel(phaseIncrement),
            phaseIncrement, amplitude, amplitudeIncrement);
      }
      int base = produced * numChannels;
      for (int i = 0; i < tmp.length; i++) {
        osc[base + i] += tmp[i];
      }
      produced += chunk;
    }
  }
}
