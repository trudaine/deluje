package org.deluge.firmware2;

/**
 * Minimal pitched sample voice for the firmware2 sample engine (the pitched-playback milestone of
 * docs/FIRMWARE2_SAMPLE_ENGINE_PLAN.md). Plays an in-RAM {@link Sample} once at a given pitch via
 * {@link SampleReader}, choosing the native (1:1) or resampled path exactly as the C does ({@code
 * phaseIncrement == kMaxSampleValue} ⇒ native; else windowed-sinc with the C's {@link
 * Functions#getWhichKernel}).
 *
 * <p>Scope: a single non-looping play head, no time-stretch (that is {@code TimeStretcher.hopEnd},
 * a later increment), no cluster/loop/end-of-sample envelope handling yet.
 */
public class VoiceSample {

  /** C: kMaxSampleValue (1 << 24) — unity phaseIncrement ⇒ no resampling. */
  static final int K_MAX_SAMPLE_VALUE = 16777216;

  public final SampleReader reader = new SampleReader();
  public boolean active;
  private transient int[] renderScratchBuffer = new int[256];

  private int[] getScratchBuffer(int requiredCapacity) {
    if (renderScratchBuffer == null || renderScratchBuffer.length < requiredCapacity) {
      renderScratchBuffer = new int[requiredCapacity];
    }
    return renderScratchBuffer;
  }

  /**
   * C SampleControls::interpolationMode == LINEAR (sample_controls.cpp:30-33): the per-source
   * "lo-fi" interpolation choice forces the 2-tap linear path regardless of CPU direness.
   */
  public boolean linearInterpolation;

  /** Exclusive end frame in the play direction (forwards: end > start; reverse: end < start). */
  public int endFrame;

  public boolean looping;
  public int loopStartFrame;

  public int pendingSamplesLate;
  public Sample pendingSample;
  public int pendingStartFrame;
  public int pendingEndFrame;
  public int pendingPlayDirection;
  public boolean pendingLooping;
  public int pendingLoopStartFrame;

  // ── Time-stretch state (the two-play-head crossfade) ──
  public final TimeStretcher timeStretcher = new TimeStretcher();
  public final SampleReader olderReader = new SampleReader();

  /**
   * Begin time-stretched playback from {@code startFrame}. C TimeStretcher::init
   * (time_stretcher.cpp:54-56, 130-139): both heads start active, and the existing head plays the
   * REAL attack for kDefaultFirstHopLength (200) ± a small random element before the first hop —
   * hopping immediately displaced/skipped every stretched attack transient.
   */
  public void setupTimeStretch(Sample sample, int startFrame, int playDirection) {
    reader.sample = sample;
    reader.playDirection = playDirection;
    reader.init(startFrame);
    timeStretcher.samplePosBig = (long) startFrame << 24;
    // C:136 — samplesTilHopEnd = kDefaultFirstHopLength + ((int8_t)getRandom255() >> 2)
    timeStretcher.samplesTilHopEnd = 200 + (((byte) Arpeggiator.getRandom255()) >> 2);
    timeStretcher.crossfadeProgress = K_MAX_SAMPLE_VALUE; // C:138
    timeStretcher.crossfadeIncrement = 0; // C:139
    timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = true; // C:54
    timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = true; // C:55
    active = true;
  }

  /**
   * Time-stretched render (pitch decoupled from speed): the two-play-head crossfade assembly that
   * ties {@link TimeStretcher#hopEnd} together with the older/newer {@link SampleReader}s
   * (voice_sample.cpp:1160-1432, the in-RAM / no-cache / TIME_STRETCH_ENABLE_BUFFER=0 path).
   *
   * <p>{@code samplePosBig} advances at the combined (pitch × stretch) rate and drives hop
   * placement; each head reads at {@code phaseIncrement} (pitch). At each hop the newer head is
   * forked into the older head, repositioned by {@code hopEnd}, and the two are linearly
   * crossfaded.
   *
   * <p>{@code oldHeadBytePos} comes from {@link SampleReader#getPlayByteLowLevel} (with the
   * interpolation-buffer compensation), matching the C.
   *
   * @param amplitude single-element voice amplitude ramp accumulator
   */
  public void renderTimeStretched(
      int[] osc,
      int numSamples,
      int numChannels,
      int phaseIncrement,
      int timeStretchRatio,
      int[] amplitude,
      int amplitudeIncrement) {
    if (!active) {
      return;
    }
    Sample sample = reader.sample;
    int bps = sample.byteDepth * sample.numChannels;
    boolean native_ = (phaseIncrement == K_MAX_SAMPLE_VALUE);
    int whichKernel = native_ ? 0 : Functions.getWhichKernel(phaseIncrement);
    // C: voice.cpp:2106 — resampling quality (sinc vs linear) per the cpuDireness fallback.
    int interpolationBufferSize =
        linearInterpolation ? 2 : Functions.getInterpolationBufferSize(phaseIncrement);
    long combinedIncrement =
        ((phaseIncrement & 0xFFFFFFFFL) * (timeStretchRatio & 0xFFFFFFFFL)) >>> 24; // C:614

    int ampVal = Functions.lshiftAndSaturate(amplitude[0], 3);
    int ampInc = Functions.lshiftAndSaturate(amplitudeIncrement, 3);
    if (!native_) {
      ampVal = Functions.lshiftAndSaturate(ampVal, 1);
      ampInc = Functions.lshiftAndSaturate(ampInc, 1);
    }
    ampVal = Functions.lshiftAndSaturate(ampVal, 1);
    ampInc = Functions.lshiftAndSaturate(ampInc, 1);

    int produced = 0;
    while (produced < numSamples) {
      // C:1162-1173 — time to hop?
      if (timeStretcher.samplesTilHopEnd <= 0) {
        int samplePos = timeStretcher.getSamplePos(reader.playDirection);
        int oldHeadBytePos =
            reader.getPlayByteLowLevel(true); // C:284 (compensates for the interp buffer)
        olderReader.copyStateFrom(reader); // fork the older head before repositioning the newer one
        timeStretcher.hopEndVoid(
            sample,
            null,
            TimeStretcher.LoopType.NONE,
            oldHeadBytePos,
            samplePos,
            phaseIncrement,
            timeStretchRatio,
            combinedIncrement,
            reader.playDirection,
            Functions.getNoise(),
            olderReader.oscPos);
        if (timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]) {
          int newFrame =
              (timeStretcher.newHeadBytePosResult - sample.audioDataStartPosBytes) / bps; // setupNewPlayHead (in-RAM)
          reader.init(newFrame);
          reader.oscPos = timeStretcher.additionalOscPosResult; // C:998
        }
      }

      // C voice_sample.cpp:1427-1430 — one-shot: when both stretch heads have died (newer killed
      // past the waveform end, older's crossfade finished), the voice ends instead of re-hopping
      // and rendering silence forever.
      if (!timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER]
          && !timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]) {
        active = false;
        return;
      }

      int numThis = Math.min(numSamples - produced, timeStretcher.samplesTilHopEnd);
      if (numThis <= 0) {
        break;
      }

      // C:1206-1285 — crossfade amplitude envelopes for the two heads.
      boolean olderAudible =
          timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER]
              && timeStretcher.crossfadeProgress < K_MAX_SAMPLE_VALUE;
      int preCacheAmplitude = ampVal >> 1;
      int preCacheAmplitudeIncrement = ampInc >> 1;
      int newerSourceAmplitudeNow;
      int newerAmplitudeIncrementNow;
      int olderSourceAmplitudeNow = 0;
      int olderAmplitudeIncrementNow = 0;
      if (olderAudible) {
        int newerHopAmplitudeNow =
            Functions.lshiftAndSaturateUnknown(timeStretcher.crossfadeProgress, 7);
        int olderHopAmplitudeNow = 2147483647 - newerHopAmplitudeNow;
        timeStretcher.crossfadeProgress += timeStretcher.crossfadeIncrement * numThis;
        int newerHopAmplitudeAfter =
            Functions.lshiftAndSaturateUnknown(timeStretcher.crossfadeProgress, 7);
        int newerHopAmplitudeIncrement = (newerHopAmplitudeAfter - newerHopAmplitudeNow) / numThis;
        int hopAmplitudeChange =
            Functions.multiply_32x32_rshift32(preCacheAmplitude, newerHopAmplitudeIncrement) << 1;
        newerAmplitudeIncrementNow = preCacheAmplitudeIncrement + hopAmplitudeChange;
        newerSourceAmplitudeNow =
            Functions.multiply_32x32_rshift32(preCacheAmplitude, newerHopAmplitudeNow) << 1;
        olderAmplitudeIncrementNow = preCacheAmplitudeIncrement - hopAmplitudeChange;
        olderSourceAmplitudeNow =
            Functions.multiply_32x32_rshift32(preCacheAmplitude, olderHopAmplitudeNow) << 1;
      } else {
        newerSourceAmplitudeNow = preCacheAmplitude;
        newerAmplitudeIncrementNow = preCacheAmplitudeIncrement;
      }

      int[] tmp = getScratchBuffer(numThis * numChannels);
      java.util.Arrays.fill(tmp, 0, numThis * numChannels, 0);
      // C:1300-1332 — newer head
      if (timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]) {
        readHead(
            reader,
            tmp,
            numThis,
            numChannels,
            native_,
            whichKernel,
            phaseIncrement,
            interpolationBufferSize,
            newerSourceAmplitudeNow,
            newerAmplitudeIncrementNow);
      }
      // C:1337-1367 — older head
      if (olderAudible) {
        readHead(
            olderReader,
            tmp,
            numThis,
            numChannels,
            native_,
            whichKernel,
            phaseIncrement,
            interpolationBufferSize,
            olderSourceAmplitudeNow,
            olderAmplitudeIncrementNow);
        if (timeStretcher.crossfadeProgress >= K_MAX_SAMPLE_VALUE) { // C:1370 — older finished
          timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = false;
        }
      }

      int base = produced * numChannels;
      int len = numThis * numChannels;
      for (int i = 0; i < len; i++) {
        osc[base + i] += tmp[i];
      }

      timeStretcher.samplePosBig += combinedIncrement * numThis * reader.playDirection; // C:1396
      timeStretcher.samplesTilHopEnd -= numThis; // C:1432
      produced += numThis;
      ampVal += ampInc * numThis;
    }
    amplitude[0] = native_ ? (ampVal >> 3) : (ampVal >> 5);
  }

  private static void readHead(
      SampleReader head,
      int[] tmp,
      int numThis,
      int numChannels,
      boolean native_,
      int whichKernel,
      int phaseIncrement,
      int interpolationBufferSize,
      int sourceAmplitude,
      int amplitudeIncrement) {
    int[] amp = {sourceAmplitude};
    if (native_) {
      head.readNative(tmp, numThis, numChannels, amp, amplitudeIncrement);
    } else {
      head.readResampled(
          tmp,
          numThis,
          numChannels,
          whichKernel,
          phaseIncrement,
          interpolationBufferSize,
          amp,
          amplitudeIncrement);
    }
  }

  /** One-shot, no loop — plays {@code sample} from {@code startFrame} to the sample end. */
  public void setup(Sample sample, int startFrame, int playDirection) {
    int end = (playDirection == 1) ? (int) sample.lengthInSamples : -1;
    setup(sample, startFrame, end, playDirection, false, startFrame);
  }

  /** Full setup with explicit end + optional looping. */
  public void setup(
      Sample sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame) {
    reader.sample = sample;
    reader.playDirection = playDirection;
    reader.init(startFrame);
    this.endFrame = endFrame;
    this.looping = looping;
    this.loopStartFrame = loopStartFrame;
    active = true;
  }

  public void setupLate(
      Sample sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame,
      int samplesLate) {
    this.pendingSample = sample;
    this.pendingStartFrame = startFrame;
    this.pendingEndFrame = endFrame;
    this.pendingPlayDirection = playDirection;
    this.pendingLooping = looping;
    this.pendingLoopStartFrame = loopStartFrame;
    this.pendingSamplesLate = samplesLate;
    this.active = true;
  }

  public boolean attemptLateSampleStart(int rawSamplesSinceStart) {
    int startAtFrame = rawSamplesSinceStart * pendingPlayDirection + pendingStartFrame;

    // Check if we've already passed the end frame
    if ((startAtFrame - pendingEndFrame) * pendingPlayDirection >= 0) {
      active = false;
      pendingSamplesLate = 0;
      return false; // failure
    }

    setup(
        pendingSample,
        startAtFrame,
        pendingEndFrame,
        pendingPlayDirection,
        pendingLooping,
        pendingLoopStartFrame);
    pendingSamplesLate = 0;
    return true; // success
  }

  /** Input frames remaining before the end, in the play direction. */
  private long framesUntilEnd() {
    return (long) (endFrame - reader.playPos) * reader.playDirection;
  }

  /**
   * Render {@code numSamples} into {@code osc} (interleaved when stereo), accumulating, with the
   * given pitch and amplitude ramp. Picks native vs resampled like the C, and stops (one-shot) or
   * wraps (looping) at {@link #endFrame}. Block-chunked so a render never reads past the end; the
   * exact sample-accurate window math is the C considerUpcomingWindow's job (adapter approximation
   * here for the resampled case).
   */
  public void render(
      int[] osc,
      int numSamples,
      int numChannels,
      int phaseIncrement,
      int[] amplitude,
      int amplitudeIncrement) {
    if (!active) {
      return;
    }
    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry
        && (reader.playPos == 0 || Math.random() < 0.001)) {}
    boolean native_ = (phaseIncrement == K_MAX_SAMPLE_VALUE);
    int ampVal = Functions.lshiftAndSaturate(amplitude[0], 3);
    int ampInc = Functions.lshiftAndSaturate(amplitudeIncrement, 3);
    if (!native_) {
      ampVal = Functions.lshiftAndSaturate(ampVal, 1);
      ampInc = Functions.lshiftAndSaturate(ampInc, 1);
    }
    int[] ampArr = {ampVal};

    // whichKernel and interpolationBufferSize depend only on phaseIncrement (constant for this
    // render call), so compute once instead of per output chunk (getInterpolationBufferSize showed
    // up in the resampler profile because it ran in the inner loop).
    int whichKernel = native_ ? 0 : Functions.getWhichKernel(phaseIncrement);
    int interpBufSize =
        native_
            ? 0
            : (linearInterpolation ? 2 : Functions.getInterpolationBufferSize(phaseIncrement));

    int produced = 0;
    while (produced < numSamples) {
      long left = framesUntilEnd();
      if (left <= 0) {
        if (looping) {
          // C sample_low_level_reader.cpp:404-433 — the wrap preserves the overshoot, the
          // fractional phase, and the 16-tap history; re-initing here dropped up to one input
          // frame + the fraction per cycle (audible pitch drift on short sustain loops).
          reader.wrapBy(endFrame - loopStartFrame);
          left = framesUntilEnd();
        }
        if (left <= 0) { // one-shot ended, or degenerate loop
          // C doZeroes (sample_low_level_reader.cpp:692-699): the resampled path keeps feeding
          // zeroes through the interpolation buffer until it has fully drained — the reader's
          // playPos leads the audible sinc center, so stopping at endFrame cut the last ~9 real
          // frames and the windowed-sinc ring-out (a click on samples ending at non-zero
          // amplitude). pushFrame already yields zeroes past the sample data.
          long tailLeft = native_ ? 0 : interpBufSize - (-left);
          if (tailLeft <= 0) {
            active = false;
            return;
          }
          left = tailLeft;
        }
      }

      // How many output samples we can emit before consuming `left` input frames.
      long outAvail =
          native_
              ? left
              : (phaseIncrement == 0 ? numSamples : Math.max(1, (left << 24) / phaseIncrement));
      int chunk = (int) Math.min(numSamples - produced, outAvail);

      int[] tmp = getScratchBuffer(chunk * numChannels);
      java.util.Arrays.fill(tmp, 0, chunk * numChannels, 0);
      if (native_) {
        reader.readNative(tmp, chunk, numChannels, ampArr, ampInc);
      } else {
        reader.readResampled(
            tmp, chunk, numChannels, whichKernel, phaseIncrement, interpBufSize, ampArr, ampInc);
      }
      int base = produced * numChannels;
      int len = chunk * numChannels;
      for (int i = 0; i < len; i++) {
        osc[base + i] += tmp[i];
      }
      produced += chunk;
    }
    amplitude[0] = native_ ? (ampArr[0] >> 3) : (ampArr[0] >> 4);
  }
}
