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

  /** Directional start frame in the play direction. */
  public int startFrame;

  /** Exclusive end frame in the play direction (forwards: end > start; reverse: end < start). */
  public int endFrame;

  public boolean looping;
  public int loopStartFrame;

  /**
   * C VoiceSamplePlaybackGuide::loopEndPlaybackAtByte / noteOffReceived
   * (voice_sample_playback_guide.cpp:38-101): when {@link #hasLoopEnd} is true, the sample loops at
   * {@link #loopEndFrame} while held, then exits the loop on {@link #onNoteOff()} and plays out
   * through {@link #endFrame}.
   */
  public boolean hasLoopEnd;

  public int loopEndFrame;
  public boolean noteOffReceived;

  public int pendingSamplesLate;
  public Sample pendingSample;
  public int pendingStartFrame;
  public int pendingEndFrame;
  public int pendingPlayDirection;
  public boolean pendingLooping;
  public int pendingLoopStartFrame;
  public boolean pendingHasLoopEnd;
  public int pendingLoopEndFrame;

  // ── Time-stretch state (the two-play-head crossfade) ──
  public final TimeStretcher timeStretcher = new TimeStretcher();
  public final SampleReader olderReader = new SampleReader();
  private final SamplePlaybackGuide guide = new SamplePlaybackGuide();

  /**
   * C: VoiceSamplePlaybackGuide::shouldObeyLoopEndPointNow (voice_sample_playback_guide.cpp:73).
   */
  public boolean shouldObeyLoopEndPointNow() {
    return hasLoopEnd && !noteOffReceived;
  }

  /** C: VoiceSamplePlaybackGuide::getBytePosToEndOrLoopPlayback (:87-94), in frames. */
  public int currentEndOrLoopFrame() {
    return shouldObeyLoopEndPointNow() ? loopEndFrame : endFrame;
  }

  /** C: VoiceSamplePlaybackGuide::getLoopingType (:96-101) == LOW_LEVEL. */
  public boolean isLoopingNow() {
    return hasLoopEnd ? !noteOffReceived : looping;
  }

  private void syncReaderBounds() {
    reader.setBounds(currentEndOrLoopFrame(), isLoopingNow(), loopStartFrame);
  }

  /**
   * C: Voice::noteOff + VoiceSample::noteOffWhenLoopEndPointExists (voice.cpp:573-575, 608-619;
   * voice_sample.cpp:58-75).
   */
  public void onNoteOff() {
    noteOffReceived = true;
    if (reader.sample != null) {
      syncReaderBounds();
      if (timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER]
          && olderReader.sample != null) {
        olderReader.setBounds(currentEndOrLoopFrame(), isLoopingNow(), loopStartFrame);
      }
    }
  }

  /**
   * Begin time-stretched playback from {@code startFrame} (one-shot to waveform end). C
   * TimeStretcher::init (time_stretcher.cpp:54-56, 130-139).
   */
  public void setupTimeStretch(Sample sample, int startFrame, int playDirection) {
    int end = (playDirection == 1) ? (int) sample.lengthInSamples : -1;
    setupTimeStretch(sample, startFrame, end, playDirection, false, startFrame, false, end);
  }

  /**
   * Begin time-stretched playback with directional bounds and optional loop/loop-end markers (C
   * TimeStretcher::init, time_stretcher.cpp:54-56, 130-139).
   */
  public void setupTimeStretch(
      Sample sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame,
      boolean hasLoopEnd,
      int loopEndFrame) {
    this.startFrame = startFrame;
    this.endFrame = endFrame;
    this.looping = looping;
    this.loopStartFrame = loopStartFrame;
    this.hasLoopEnd = hasLoopEnd;
    this.loopEndFrame = loopEndFrame;
    this.noteOffReceived = false;
    this.pendingSamplesLate = 0;

    int dir = (playDirection < 0) ? -1 : 1;
    reader.sample = sample;
    reader.playDirection = dir;
    reader.init(startFrame);
    syncReaderBounds();

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
   * (voice_sample.cpp:974-1435, the in-RAM / no-cache / TIME_STRETCH_ENABLE_BUFFER=0 path).
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

    syncReaderBounds();

    int produced = 0;
    while (produced < numSamples) {
      int reassessmentFrame = currentEndOrLoopFrame();
      boolean loopingNow = isLoopingNow();
      TimeStretcher.LoopType loopingType =
          loopingNow ? TimeStretcher.LoopType.LOW_LEVEL : TimeStretcher.LoopType.NONE;

      guide.playDirection = reader.playDirection;
      guide.startPlaybackAtByte =
          sample.audioDataStartPosBytes + (loopingNow ? loopStartFrame : startFrame) * bps;
      guide.endPlaybackAtByte = sample.audioDataStartPosBytes + reassessmentFrame * bps;

      // C voice_sample.cpp:974-1059 + time_stretcher.cpp:147-167 — reassess samplePosBig against
      // the loop/end point before hopping or reading.
      int numToRead = numSamples - produced;
      if (combinedIncrement > 0) {
        long loopEndBig = (long) reassessmentFrame << 24;
        int loopGuard = 0;
        while (true) {
          long samplePosBigAfter =
              timeStretcher.samplePosBig + combinedIncrement * numToRead * reader.playDirection;
          if (reader.playDirection == -1) {
            samplePosBigAfter += 16777215L;
          }
          long frameAfter = samplePosBigAfter >> 24;
          long overshootFrames = (frameAfter - reassessmentFrame) * reader.playDirection;
          if (overshootFrames <= 0) {
            break;
          }
          long leftToDo = loopEndBig - timeStretcher.samplePosBig;
          long leftToDoAbs = leftToDo * reader.playDirection;
          if (leftToDoAbs < combinedIncrement) {
            if (loopingNow) {
              int loopSpan = reassessmentFrame - loopStartFrame;
              if (loopSpan * reader.playDirection <= 0 || ++loopGuard >= 1024) {
                active = false;
                return;
              }
              timeStretcher.samplePosBig = ((long) loopStartFrame << 24) - leftToDo; // C:1023-1024
              if (!timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]) {
                timeStretcher.samplesTilHopEnd = 0; // C time_stretcher.cpp:163 — force hop
              }
              continue;
            } else {
              active = false; // C:1042 — one-shot reached end
              return;
            }
          } else {
            int combinedIncrementsLeft = (int) Long.divideUnsigned(leftToDoAbs, combinedIncrement);
            numToRead = Math.min(numToRead, Math.max(1, combinedIncrementsLeft)); // C:1049-1056
            break;
          }
        }
      }

      // C:1162-1173 — time to hop?
      if (timeStretcher.samplesTilHopEnd <= 0) {
        int samplePos = timeStretcher.getSamplePos(reader.playDirection);
        int oldHeadBytePos =
            reader.getPlayByteLowLevel(true); // C:284 (compensates for the interp buffer)
        olderReader.copyStateFrom(reader); // fork the older head before repositioning the newer one
        timeStretcher.hopEndVoid(
            sample,
            guide,
            loopingType,
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
              (timeStretcher.newHeadBytePosResult - sample.audioDataStartPosBytes)
                  / bps; // setupNewPlayHead (in-RAM)
          // C time_stretcher.cpp:1001-1014 — changeClusterIfNecessary wraps currentPlayPos into
          // the loop before interpolationBufferSizeLastTime = 0 primes history on the next block.
          reader.playPos = newFrame;
          syncReaderBounds();
          reader.init(reader.playPos);
          reader.oscPos = timeStretcher.additionalOscPosResult; // C:1014
        }
      }

      // C voice_sample.cpp:1434-1438 — one-shot: when both stretch heads have died (newer killed
      // past the waveform end, older's crossfade finished), the voice ends instead of re-hopping
      // and rendering silence forever.
      if (loopingType == TimeStretcher.LoopType.NONE
          && !timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER]
          && !timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER]) {
        active = false;
        return;
      }

      int numThis = Math.min(numToRead, timeStretcher.samplesTilHopEnd);
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
    setup(sample, startFrame, end, playDirection, false, startFrame, false, end);
  }

  /** Full setup with explicit end + optional looping. */
  public void setup(
      Sample sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame) {
    setup(sample, startFrame, endFrame, playDirection, looping, loopStartFrame, false, endFrame);
  }

  /**
   * Full setup with explicit end, optional looping, and optional distinct loop-end point (C
   * VoiceSample::noteOn + VoiceSamplePlaybackGuide::setupPlaybackBounds, voice_sample.cpp:43-55).
   */
  public void setup(
      Sample sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame,
      boolean hasLoopEnd,
      int loopEndFrame) {
    // C voice_sample.cpp:39, 52 — clear any prior time-stretcher active state on voice reuse.
    timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = false;
    timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = false;
    this.startFrame = startFrame;
    this.endFrame = endFrame;
    this.looping = looping;
    this.loopStartFrame = loopStartFrame;
    this.hasLoopEnd = hasLoopEnd;
    this.loopEndFrame = loopEndFrame;
    this.noteOffReceived = false;
    this.pendingSamplesLate = 0;

    int dir = (playDirection < 0) ? -1 : 1;
    reader.sample = sample;
    reader.playDirection = dir;
    reader.init(startFrame);
    syncReaderBounds();
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
    setupLate(
        sample,
        startFrame,
        endFrame,
        playDirection,
        looping,
        loopStartFrame,
        false,
        endFrame,
        samplesLate);
  }

  public void setupLate(
      Sample sample,
      int startFrame,
      int endFrame,
      int playDirection,
      boolean looping,
      int loopStartFrame,
      boolean hasLoopEnd,
      int loopEndFrame,
      int samplesLate) {
    // C voice_sample.cpp:52 — timeStretcher = nullptr on noteOn.
    timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_OLDER] = false;
    timeStretcher.playHeadStillActive[TimeStretcher.PLAY_HEAD_NEWER] = false;
    this.pendingSample = sample;
    this.pendingStartFrame = startFrame;
    this.pendingEndFrame = endFrame;
    this.pendingPlayDirection = (playDirection < 0) ? -1 : 1;
    this.pendingLooping = looping;
    this.pendingLoopStartFrame = loopStartFrame;
    this.pendingHasLoopEnd = hasLoopEnd;
    this.pendingLoopEndFrame = loopEndFrame;
    this.pendingSamplesLate = samplesLate;
    this.noteOffReceived = false;
    this.active = true;
  }

  public boolean attemptLateSampleStart(int rawSamplesSinceStart) {
    int startAtFrame = rawSamplesSinceStart * pendingPlayDirection + pendingStartFrame;

    // Check if we've already passed the end frame (C voice_sample.cpp:176).
    if ((startAtFrame - pendingEndFrame) * pendingPlayDirection >= 0) {
      active = false;
      pendingSamplesLate = 0;
      return false; // failure
    }

    boolean savedNoteOff = noteOffReceived;
    setup(
        pendingSample,
        startAtFrame,
        pendingEndFrame,
        pendingPlayDirection,
        pendingLooping,
        pendingLoopStartFrame,
        pendingHasLoopEnd,
        pendingLoopEndFrame);
    if (savedNoteOff) {
      onNoteOff();
    }
    pendingSamplesLate = 0;
    return true; // success
  }

  /**
   * Render {@code numSamples} into {@code osc} (interleaved when stereo), accumulating, with the
   * given pitch and amplitude ramp. Picks native vs resampled like the C, and stops (one-shot) or
   * wraps (looping) at {@link #currentEndOrLoopFrame()} (sample_low_level_reader.cpp:402-765).
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
    // render call), so compute once instead of per output chunk.
    int whichKernel = native_ ? 0 : Functions.getWhichKernel(phaseIncrement);
    int interpBufSize =
        native_
            ? 0
            : (linearInterpolation ? 2 : Functions.getInterpolationBufferSize(phaseIncrement));

    syncReaderBounds();

    int produced = 0;
    while (produced < numSamples) {
      int endOrLoop = currentEndOrLoopFrame();
      boolean loopingNow = isLoopingNow();
      int chunk;

      if (loopingNow) {
        if ((endOrLoop - loopStartFrame) * reader.playDirection <= 0) {
          active = false;
          break;
        }
        // SampleReader wraps playPos seamlessly inside pushFrame() / readNative() across
        // [loopStartFrame, endOrLoop), preserving history and fractional oscPos.
        chunk = numSamples - produced;
      } else {
        // C doZeroes (sample_low_level_reader.cpp:680-685, 759): resampled playback drains
        // interpBufSize zeroes past endFrame so the sinc/linear kernel rings out cleanly.
        long stopFrame = endOrLoop + (long) (native_ ? 0 : interpBufSize) * reader.playDirection;
        long left = (stopFrame - reader.playPos) * reader.playDirection;
        if (left <= 0) {
          active = false;
          break;
        }
        long outAvail =
            native_
                ? left
                : (phaseIncrement <= 0
                    ? (numSamples - produced)
                    : Math.max(
                        1L,
                        ((left << 24) + (phaseIncrement & 0xFFFFFFFFL) - 1)
                            / (phaseIncrement & 0xFFFFFFFFL)));
        chunk = (int) Math.min(numSamples - produced, outAvail);
      }

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

      if (!loopingNow) {
        long stopFrame = endOrLoop + (long) (native_ ? 0 : interpBufSize) * reader.playDirection;
        if ((stopFrame - reader.playPos) * reader.playDirection <= 0) {
          active = false;
          break;
        }
      }
    }
    amplitude[0] = native_ ? (ampArr[0] >> 3) : (ampArr[0] >> 4);
  }
}
