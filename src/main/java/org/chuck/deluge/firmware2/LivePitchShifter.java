package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code processing/live/live_pitch_shifter.{cpp,h}}: the live (input-monitoring)
 * pitch shifter — a two-play-head crossfade engine over the {@link LiveInputBuffer} ring. {@code
 * INPUT_ENABLE_REPITCHED_BUFFER == 0}, so the repitched-buffer paths are omitted.
 *
 * <p>Complete: constructor, helpers, {@link #computeLiveHopParameters} (hopEnd parameter head),
 * {@link #hopEnd} (perc beam-search + bidirectional crossfade-point search over the ring buffer),
 * and {@link #render} (hop-shortening, percussiveness-cut, two-head crossfade mix). Seams: {@code
 * LiveInputBuffer} + {@code audioSampleTimer} + {@code inputBlock} are injected (the C reads the
 * hardware via {@code AudioEngine}).
 */
public class LivePitchShifter {

  static final int K_MAX_SAMPLE_VALUE = 16777216;
  static final int K_INTERPOLATION_MAX_NUM_SAMPLES = 16;

  // hopEnd parameter tables (live_pitch_shifter.cpp:308-354). Fine = 17 (2^4+1); Coarse = 5 (2^2+1)
  // with
  // one trailing duplicate so interpolateTableSigned's table[whichValue+1] read at the clamped
  // upper
  // bound stays in bounds (×0 there ⇒ bit-identical to the C, same fix as the time-stretcher
  // tables).
  static final int[] minSearchFine = {8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 12, 14, 16, 17};
  static final int[] minSearchCoarse = {10, 10, 10, 17, 20, 20};
  static final int[] maxSearchFine = {8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 10, 11, 12, 14, 16, 18, 21};
  static final int[] maxSearchCoarse = {15, 15, 15, 21, 20, 20};
  static final int[] percThresholdFine = {
    18, 18, 18, 18, 24, 30, 25, 35, 130, 40, 40, 45, 50, 47, 45, 42, 40
  };
  static final int[] percThresholdCoarse = {15, 18, 130, 40, 20, 20};
  static final int[] crossfadeFine = {
    30, 31, 32, 34, 35, 30, 25, 10, 10, 15, 15, 22, 30, 16, 15, 13, 40
  };
  static final int[] crossfadeCoarse = {30, 30, 10, 40, 20, 20};
  static final int[] maxHopLengthFine = {
    20, 27, 35, 42, 50, 60, 70, 90, 140, 140, 140, 140, 140, 140, 140, 140, 140
  };
  static final int[] maxHopLengthCoarse = {10, 20, 140, 140, 140, 140};
  static final int[] randomFine = {0, 0, 0, 0, 0, 0, 0, 0, 0, 15, 25, 76, 50, 57, 65, 72, 80};
  static final int[] randomCoarse = {0, 0, 0, 80, 80, 80};

  /** Indices into {@link #computeLiveHopParameters}. */
  public static final int LHP_MIN_SEARCH = 0;

  public static final int LHP_MAX_SEARCH = 1;
  public static final int LHP_PERC_THRESHOLD = 2;
  public static final int LHP_NEXT_CROSSFADE = 3;
  public static final int LHP_MAX_HOP_LENGTH = 4;
  public static final int LHP_RANDOM = 5;

  public int numChannels;
  public LiveInputBuffer.InputType inputType;

  public int crossfadeProgress; // uint32, out of kMaxSampleValue
  public int crossfadeIncrement; // uint32
  public int nextCrossfadeLength;
  public int samplesTilHopEnd;
  public int samplesIntoHop;
  public int percThresholdForCut;

  public final LivePitchShifterPlayHead[] playHeads = {
    new LivePitchShifterPlayHead(), new LivePitchShifterPlayHead()
  };

  /** C: live_pitch_shifter.cpp:29-60 */
  public LivePitchShifter(LiveInputBuffer.InputType newInputType, int phaseIncrement) {
    inputType = newInputType;
    numChannels = (newInputType == LiveInputBuffer.InputType.STEREO) ? 2 : 1;

    if (phaseIncrement < K_MAX_SAMPLE_VALUE) {
      nextCrossfadeLength = samplesTilHopEnd = K_INTERPOLATION_MAX_NUM_SAMPLES * 2;
    } else if (phaseIncrement < 17774841) { // up by less than 1 semitone
      samplesTilHopEnd = 2048;
      nextCrossfadeLength = 256;
    } else {
      nextCrossfadeLength = samplesTilHopEnd = 256;
    }

    crossfadeProgress = K_MAX_SAMPLE_VALUE;
    samplesIntoHop = 0;
    // considerRepitchedBuffer: no-op when INPUT_ENABLE_REPITCHED_BUFFER == 0

    playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode =
        LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT;
    playHeads[TimeStretcher.PLAY_HEAD_NEWER].oscPos = 0;
    playHeads[TimeStretcher.PLAY_HEAD_NEWER].rawBufferReadPos = 0;
    playHeads[TimeStretcher.PLAY_HEAD_NEWER].percPos = 0xFFFFFFFF;
  }

  /** C: olderPlayHeadIsCurrentlySounding (live_pitch_shifter.cpp:867-869). */
  public boolean olderPlayHeadIsCurrentlySounding() {
    return Integer.compareUnsigned(crossfadeProgress, K_MAX_SAMPLE_VALUE) < 0;
  }

  /** C: mayBeRemovedWithoutClick (live_pitch_shifter.cpp:871-873). */
  public boolean mayBeRemovedWithoutClick() {
    return !olderPlayHeadIsCurrentlySounding()
        && playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode
            == LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT;
  }

  /**
   * C: hopEnd parameter head (live_pitch_shifter.cpp:382-420) — pitchLog =
   * quickLog(phaseIncrement), then Fine (±1 octave) / Coarse table interpolation for the
   * search/crossfade/hop parameters. Pure.
   *
   * @return {@code {minSearch, maxSearch, percThresholdForCut, nextCrossfadeLength, maxHopLength,
   *     randomElement}}
   */
  public static int[] computeLiveHopParameters(int phaseIncrement) {
    int pitchLog = Functions.quickLog(phaseIncrement); // C:382

    int minSearch;
    int maxSearch;
    int percThreshold;
    int nextCrossfade;
    int maxHopLength;
    int randomElement;

    if (pitchLog >= (800 << 20) && pitchLog < (864 << 20)) { // C:392
      int position = pitchLog - (800 << 20);
      minSearch = Functions.interpolateTableSigned(position, 26, minSearchFine, 4) >> 9;
      maxSearch = Functions.interpolateTableSigned(position, 26, maxSearchFine, 4) >> 9;
      percThreshold = Functions.interpolateTableSigned(position, 26, percThresholdFine, 4) >> 16;
      nextCrossfade = Functions.interpolateTableSigned(position, 26, crossfadeFine, 4) >> 12;
      maxHopLength =
          (Functions.interpolateTableSigned(position, 26, maxHopLengthFine, 4) >> 16) * 100;
      randomElement = Functions.interpolateTableSigned(position, 26, randomFine, 4);
    } else { // C:404-419
      int pl = pitchLog;
      if (pl > (896 << 20)) {
        pl = (896 << 20);
      } else if (pl < (768 << 20)) {
        pl = (768 << 20);
      }
      int position = pl - (768 << 20);
      minSearch = Functions.interpolateTableSigned(position, 27, minSearchCoarse, 2) >> 9;
      maxSearch = Functions.interpolateTableSigned(position, 27, maxSearchCoarse, 2) >> 9;
      percThreshold = Functions.interpolateTableSigned(position, 27, percThresholdCoarse, 2) >> 16;
      nextCrossfade = Functions.interpolateTableSigned(position, 27, crossfadeCoarse, 2) >> 12;
      maxHopLength =
          (Functions.interpolateTableSigned(position, 27, maxHopLengthCoarse, 2) >> 16) * 100;
      randomElement = Functions.interpolateTableSigned(position, 27, randomCoarse, 2);
    }

    return new int[] {
      minSearch, maxSearch, percThreshold, nextCrossfade, maxHopLength, randomElement
    };
  }

  // ── render (live_pitch_shifter.cpp:71-306) ──

  /**
   * C: live_pitch_shifter.cpp:71-306 — the main render loop. INPUT_ENABLE_REPITCHED_BUFFER is 0, so
   * the repitched-buffer paths are omitted. Seams: {@code liveInputBuffer} (injected; the C reads
   * {@code AudioEngine::getOrCreateLiveInputBuffer}), {@code audioSampleTimer} (the C reads the
   * hardware timer; injected as the {@code currentTime} parameter to {@code giveInput}).
   */
  public void render(
      int[] outputBuffer,
      int numSamples,
      int phaseIncrement,
      int amplitude,
      int amplitudeIncrement,
      int interpolationBufferSize,
      LiveInputBuffer liveInputBuffer,
      int audioSampleTimer,
      int[] inputBlock) {
    liveInputBuffer.giveInput(
        inputBlock, numSamples, audioSampleTimer, inputType); // C:80 (input via seam)

    int whichKernel = Functions.getWhichKernel(phaseIncrement); // C:82

    int numRawSamplesProcessedAtStart = liveInputBuffer.numRawSamplesProcessed - numSamples; // C:84

    boolean justDidHop = false;
    int outOff = 0; // output pointer offset (C advances outputBuffer by samples*channels)

    startRenderAgain: // C:126
    while (true) {
      // C:128-187 — hop shortening when pitching above 1x
      if (!justDidHop && phaseIncrement > K_MAX_SAMPLE_VALUE) {
        int maxPlayableSamplesNewer =
            playHeads[TimeStretcher.PLAY_HEAD_NEWER].getEstimatedPlaytimeRemaining(
                liveInputBuffer, phaseIncrement); // C:129-135

        if (samplesTilHopEnd + nextCrossfadeLength > maxPlayableSamplesNewer) { // C:137
          int maxTotalPlayable = maxPlayableSamplesNewer + samplesIntoHop; // C:139
          nextCrossfadeLength = Math.min(nextCrossfadeLength, maxTotalPlayable >> 1); // C:140
          samplesTilHopEnd = maxPlayableSamplesNewer - nextCrossfadeLength; // C:142

          if (samplesTilHopEnd < 0) { // C:145
            samplesTilHopEnd = 0;
            nextCrossfadeLength = Math.max(maxPlayableSamplesNewer, 0); // C:147
            crossfadeProgress = K_MAX_SAMPLE_VALUE; // C:149
          } else if (samplesTilHopEnd > 0 && olderPlayHeadIsCurrentlySounding()) { // C:156
            int minCrossfadeIncrement =
                Integer.divideUnsigned(K_MAX_SAMPLE_VALUE - crossfadeProgress, samplesTilHopEnd)
                    + 1; // C:157
            if (Integer.compareUnsigned(minCrossfadeIncrement, crossfadeIncrement) > 0) { // C:158
              crossfadeIncrement = minCrossfadeIncrement; // C:159
            }
          }
        }

        if (samplesTilHopEnd != 0 && olderPlayHeadIsCurrentlySounding()) { // C:165
          int maxPlayableSamplesOlder =
              playHeads[TimeStretcher.PLAY_HEAD_OLDER].getEstimatedPlaytimeRemaining(
                  liveInputBuffer, phaseIncrement); // C:166-172
          if (maxPlayableSamplesOlder == 0) { // C:174
            crossfadeIncrement = K_MAX_SAMPLE_VALUE; // C:175
          } else {
            int minCrossfadeIncrement =
                Integer.divideUnsigned(
                        K_MAX_SAMPLE_VALUE - crossfadeProgress, maxPlayableSamplesOlder)
                    + 1; // C:178-179
            if (Integer.compareUnsigned(minCrossfadeIncrement, crossfadeIncrement) > 0) { // C:181
              crossfadeIncrement = minCrossfadeIncrement;
            }
          }
        }
      }

      // C:189-222 — percussiveness cut (pitch-down + pitch-up, skips when older head still audible)
      if (!justDidHop
          && !olderPlayHeadIsCurrentlySounding()
          && samplesTilHopEnd != 0
          && playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode
              != LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT) { // C:192-193

        int howFarBack =
            playHeads[TimeStretcher.PLAY_HEAD_NEWER].getNumRawSamplesBehindInput(
                liveInputBuffer, phaseIncrement); // C:195-196

        int newerPlayHeadPercPos =
            (liveInputBuffer.numRawSamplesProcessed - howFarBack - 1)
                >> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE; // C:198-199

        int latestPercPosBefore =
            (numRawSamplesProcessedAtStart - 1)
                >> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE; // C:201
        int latestPercPosNow =
            (liveInputBuffer.numRawSamplesProcessed - 1)
                >> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE; // C:202

        if (latestPercPosNow != newerPlayHeadPercPos
            && (newerPlayHeadPercPos != playHeads[TimeStretcher.PLAY_HEAD_NEWER].percPos
                || latestPercPosNow != latestPercPosBefore)) { // C:204-206

          int percLatest =
              liveInputBuffer
                      .percBuffer[latestPercPosNow & (LiveInputBuffer.K_INPUT_PERC_BUFFER_SIZE - 1)]
                  & 0xFF; // C:208
          int percNewerPlayHead =
              liveInputBuffer
                      .percBuffer[
                      newerPlayHeadPercPos & (LiveInputBuffer.K_INPUT_PERC_BUFFER_SIZE - 1)]
                  & 0xFF; // C:209

          if (percLatest >= percNewerPlayHead + percThresholdForCut) { // C:211
            samplesTilHopEnd = 0; // C:216
          }
        }

        playHeads[TimeStretcher.PLAY_HEAD_NEWER].percPos = newerPlayHeadPercPos; // C:220
      }

      if (samplesTilHopEnd == 0) { // C:224
        hopEnd(
            phaseIncrement,
            liveInputBuffer,
            numRawSamplesProcessedAtStart,
            liveInputBuffer.numRawSamplesProcessed);
        justDidHop = true;
        continue; // C:227 goto startRenderAgain
      }

      int numSamplesThisTimestretchedRead = Math.min(numSamples, samplesTilHopEnd); // C:230

      boolean olderPlayHeadAudibleHere = olderPlayHeadIsCurrentlySounding(); // C:232

      int newerSourceAmplitudeNow;
      int newerAmplitudeIncrementNow;
      int olderSourceAmplitudeNow;
      int olderAmplitudeIncrementNow;

      // C:240-269 — crossfade amplitude envelopes (identical to VoiceSample.renderTimeStretched)
      if (olderPlayHeadAudibleHere) {
        int newerHopAmplitudeNow = crossfadeProgress << 7; // C:244
        int olderHopAmplitudeNow = 2147483647 - newerHopAmplitudeNow; // C:245
        crossfadeProgress += crossfadeIncrement * numSamplesThisTimestretchedRead; // C:247
        int newerHopAmplitudeAfter =
            Functions.lshiftAndSaturateUnknown(crossfadeProgress, 7); // C:249
        int newerHopAmplitudeIncrement =
            (newerHopAmplitudeAfter - newerHopAmplitudeNow)
                / numSamplesThisTimestretchedRead; // C:251-252
        int hopAmplitudeChange =
            Functions.multiply_32x32_rshift32(amplitude, newerHopAmplitudeIncrement) << 1; // C:255
        newerAmplitudeIncrementNow = amplitudeIncrement + hopAmplitudeChange; // C:257
        newerSourceAmplitudeNow =
            Functions.multiply_32x32_rshift32(amplitude, newerHopAmplitudeNow) << 1; // C:258
        olderAmplitudeIncrementNow = amplitudeIncrement - hopAmplitudeChange; // C:260
        olderSourceAmplitudeNow =
            Functions.multiply_32x32_rshift32(amplitude, olderHopAmplitudeNow) << 1; // C:261
      } else {
        newerSourceAmplitudeNow = amplitude; // C:266
        newerAmplitudeIncrementNow = amplitudeIncrement; // C:267
        olderSourceAmplitudeNow = 0;
        olderAmplitudeIncrementNow = 0;
      }

      // C:272-279 — newer play-head (render into a local temp, then merge at outOff)
      int[] tmp = new int[numSamplesThisTimestretchedRead * numChannels];
      playHeads[TimeStretcher.PLAY_HEAD_NEWER].render(
          tmp,
          numSamplesThisTimestretchedRead,
          numChannels,
          phaseIncrement,
          newerSourceAmplitudeNow,
          newerAmplitudeIncrementNow,
          liveInputBuffer.rawBuffer,
          whichKernel,
          interpolationBufferSize);

      // C:282-291 — older play-head
      if (olderPlayHeadAudibleHere) {
        playHeads[TimeStretcher.PLAY_HEAD_OLDER].render(
            tmp,
            numSamplesThisTimestretchedRead,
            numChannels,
            phaseIncrement,
            olderSourceAmplitudeNow,
            olderAmplitudeIncrementNow,
            liveInputBuffer.rawBuffer,
            whichKernel,
            interpolationBufferSize);
      }

      for (int i = 0; i < tmp.length; i++) {
        outputBuffer[outOff + i] += tmp[i];
      }

      samplesTilHopEnd -= numSamplesThisTimestretchedRead; // C:293
      samplesIntoHop += numSamplesThisTimestretchedRead; // C:294
      numSamples -= numSamplesThisTimestretchedRead; // C:295

      if (numSamples != 0) { // C:297
        outOff += numSamplesThisTimestretchedRead * numChannels; // C:298 outputBuffer += ...
        amplitude += amplitudeIncrement * numSamplesThisTimestretchedRead; // C:300
        numRawSamplesProcessedAtStart += numSamplesThisTimestretchedRead; // C:302
        continue; // C:304 goto startRenderAgain
      }
      break; // C:305 — done
    }
  }

  // ── hopEnd (live_pitch_shifter.cpp:382-839) ──

  /**
   * C: live_pitch_shifter.cpp:382-839 — the full hopEnd: parameter lookup, perc beam-search for
   * pitch-up, simpler placement for pitch-down, then the bidirectional crossfade-point search over
   * the ring buffer. {@code INPUT_ENABLE_REPITCHED_BUFFER == 0}, so the repitched-buffer paths are
   * omitted.
   */
  private void hopEnd(
      int phaseIncrement,
      LiveInputBuffer liveInputBuffer,
      int numRawSamplesProcessedAtNowTime,
      int numRawSamplesProcessedLatest) {

    int[] hp = computeLiveHopParameters(phaseIncrement); // C:382-420
    int minSearch = hp[LHP_MIN_SEARCH];
    int maxSearch = hp[LHP_MAX_SEARCH];
    percThresholdForCut = hp[LHP_PERC_THRESHOLD];
    int thisCrossfadeLength = hp[LHP_NEXT_CROSSFADE];
    int maxHopLength = hp[LHP_MAX_HOP_LENGTH];
    int randomElement = hp[LHP_RANDOM];

    // C:436-437
    int lengthPerMovingAverage =
        (int) (((phaseIncrement & 0xFFFFFFFFL) * TimeStretcher.K_MOVING_AVERAGE_LENGTH) >>> 24);
    lengthPerMovingAverage =
        Math.max(1, Math.min(TimeStretcher.K_MOVING_AVERAGE_LENGTH * 2, lengthPerMovingAverage));

    int crossfadeLengthSamplesSource =
        (int)
            (((thisCrossfadeLength & 0xFFFFFFFFL) * (phaseIncrement & 0xFFFFFFFFL))
                >>> 24); // C:441

    int maxOffsetFromHead =
        (playHeads[TimeStretcher.PLAY_HEAD_OLDER].rawBufferReadPos
                + LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE
                - numRawSamplesProcessedLatest)
            & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1); // C:445-446

    int averagesEndOffsetFromHead =
        (crossfadeLengthSamplesSource >> 1)
            + ((lengthPerMovingAverage * TimeStretcher.K_NUM_MOVING_AVERAGES) >> 1); // C:449-450
    averagesEndOffsetFromHead = Math.min(averagesEndOffsetFromHead, maxOffsetFromHead); // C:451-453

    lengthPerMovingAverage =
        Math.min(lengthPerMovingAverage, averagesEndOffsetFromHead >> 1); // C:458-459

    int averagesStartOffsetFromHead =
        averagesEndOffsetFromHead
            - lengthPerMovingAverage * TimeStretcher.K_NUM_MOVING_AVERAGES; // C:461-462

    // C:464-473 — old-head moving averages
    int[] oldHeadTotals = new int[TimeStretcher.K_NUM_MOVING_AVERAGES];
    if (lengthPerMovingAverage != 0) {
      int averagesStartPosOldHead =
          (playHeads[TimeStretcher.PLAY_HEAD_OLDER].rawBufferReadPos + averagesStartOffsetFromHead)
              & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1); // C:469-470
      liveInputBuffer.getAveragesForCrossfade(
          oldHeadTotals, averagesStartPosOldHead, lengthPerMovingAverage, numChannels); // C:471-472
    }

    int averagesStartPosNewHead = 0;
    int searchSize = 0;
    int searchDirection = 0;
    int numFullDirectionsSearched = 0;
    int howFarBack = 0;

    // C:487-575 — pitch UP: perc beam-search for the best howFarBack
    if (phaseIncrement > K_MAX_SAMPLE_VALUE) {
      minSearch +=
          Functions.multiply_32x32_rshift32(
                  minSearch,
                  Functions.multiply_32x32_rshift32(Functions.getNoise(), randomElement << 8))
              << 2; // C:497-498

      int backEdge = minSearch >> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE; // C:501
      int howFarBackSearched = 0;
      int percPos =
          (numRawSamplesProcessedAtNowTime + LiveInputBuffer.K_PERC_BUFFER_REDUCTION_SIZE - 1)
              >> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE; // C:503-504

      int totalPerc = 0;
      float bestAverage = 0;
      int bestHowFarBack = minSearch >> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE; // C:508

      percSearch:
      while (backEdge < (maxSearch >> LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE)) { // C:510
        while (howFarBackSearched < backEdge) { // C:512
          howFarBackSearched++;
          if (howFarBackSearched > percPos) break percSearch; // C:514-516
          int percHere =
              liveInputBuffer
                      .percBuffer[
                      (percPos - howFarBackSearched)
                          & (LiveInputBuffer.K_INPUT_PERC_BUFFER_SIZE - 1)]
                  & 0xFF; // C:517-519
          totalPerc += percHere; // C:520
        }
        float averagePerc = (float) totalPerc / howFarBackSearched; // C:523
        if (averagePerc > bestAverage) { // C:524
          bestAverage = averagePerc;
          bestHowFarBack = howFarBackSearched; // C:526
        }
        backEdge++; // C:531
      }
      // stopPercSearch (C:534)

      howFarBack = bestHowFarBack << LiveInputBuffer.K_PERC_BUFFER_REDUCTION_MAGNITUDE; // C:536

      samplesTilHopEnd =
          (int)
                  (((howFarBack & 0xFFFFFFFFL) << 24)
                      / ((phaseIncrement - K_MAX_SAMPLE_VALUE) & 0xFFFFFFFFL))
              - thisCrossfadeLength; // C:543-544
      samplesTilHopEnd = Math.max(100, Math.min(samplesTilHopEnd, maxHopLength)); // C:545-551

      int minDistanceBack =
          numRawSamplesProcessedAtNowTime
              - numRawSamplesProcessedLatest
              + averagesStartOffsetFromHead
              + lengthPerMovingAverage * TimeStretcher.K_NUM_MOVING_AVERAGES; // C:553-555
      howFarBack = Math.max(howFarBack, minDistanceBack); // C:556
      if (howFarBack > numRawSamplesProcessedAtNowTime)
        howFarBack = numRawSamplesProcessedAtNowTime; // C:558-560

      if (lengthPerMovingAverage != 0) { // C:562
        averagesStartPosNewHead =
            (numRawSamplesProcessedAtNowTime - howFarBack + averagesStartOffsetFromHead)
                & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1); // C:563-564
        searchSize = 490; // C:565 allow tracking down to ~45Hz
        searchSize = Math.min(searchSize, samplesTilHopEnd); // C:567
        numFullDirectionsSearched = 0; // C:569
        searchDirection = 1; // C:570
      }

      playHeads[TimeStretcher.PLAY_HEAD_NEWER].rawBufferReadPos =
          (numRawSamplesProcessedAtNowTime - howFarBack)
              & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1); // C:573-574
    }

    // C:578-598 — pitch DOWN: simpler, uses maxHopLength
    else {
      samplesTilHopEnd = maxHopLength; // C:579

      if (lengthPerMovingAverage != 0) { // C:581
        averagesStartPosNewHead =
            (numRawSamplesProcessedLatest
                    - lengthPerMovingAverage * TimeStretcher.K_NUM_MOVING_AVERAGES)
                & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1); // C:582-584
        searchSize = 980; // C:585
        searchSize = Math.min(searchSize, samplesIntoHop); // C:587
        numFullDirectionsSearched = 1; // C:589
        searchDirection = -1; // C:590
      }

      playHeads[TimeStretcher.PLAY_HEAD_NEWER].rawBufferReadPos =
          (numRawSamplesProcessedLatest
                  - lengthPerMovingAverage * TimeStretcher.K_NUM_MOVING_AVERAGES
                  - averagesStartOffsetFromHead)
              & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1); // C:593-597
    }

    int bestOffset = 0; // C:600
    int additionalOscPos = 0; // C:602

    // C:605-751 — bidirectional crossfade-point search (same algorithm as
    // TimeStretcher.searchForCrossfadeOffset)
    boolean doSearch =
        lengthPerMovingAverage != 0
            && playHeads[TimeStretcher.PLAY_HEAD_OLDER].mode
                != LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT; // C:605
    if (doSearch) {
      // C:610-618 — bounds checks
      doSearch =
          ((averagesStartPosNewHead - averagesStartOffsetFromHead)
                  & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1))
              < numRawSamplesProcessedLatest;
      if (doSearch) {
        doSearch =
            ((numRawSamplesProcessedLatest - averagesStartPosNewHead)
                    & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1))
                >= lengthPerMovingAverage * TimeStretcher.K_NUM_MOVING_AVERAGES;
      }
    }
    searchBlock:
    if (doSearch) {

      int[] newHeadTotals = new int[TimeStretcher.K_NUM_MOVING_AVERAGES];
      liveInputBuffer.getAveragesForCrossfade(
          newHeadTotals, averagesStartPosNewHead, lengthPerMovingAverage, numChannels); // C:621-622

      int bestDifferenceAbs =
          TimeStretcher.getTotalDifferenceAbs(oldHeadTotals, newHeadTotals); // C:624
      int timesSignFlipped = 0; // C:625
      int initialTotalChange = TimeStretcher.getTotalChange(oldHeadTotals, newHeadTotals); // C:627

      // Direction loop (C:629-749, gotos → loop structure matching
      // TimeStretcher.searchForCrossfadeOffset)
      directionLoop:
      while (true) {
        // ── startSearch (C:629-668) ──
        int lastTotalChange = initialTotalChange;
        int[] readPos = new int[TimeStretcher.K_NUM_MOVING_AVERAGES + 1];
        readPos[0] = averagesStartPosNewHead;
        if (searchDirection == -1) {
          readPos[0] = (readPos[0] - 1) & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1);
        }
        int[] newHeadRunningTotals = new int[TimeStretcher.K_NUM_MOVING_AVERAGES];
        for (int i = 0; i < TimeStretcher.K_NUM_MOVING_AVERAGES; i++) {
          newHeadRunningTotals[i] = newHeadTotals[i];
          readPos[i + 1] =
              (readPos[i] + lengthPerMovingAverage) & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1);
        }

        int offsetNow = 0;
        int searchSizeBoundary;
        if (searchDirection == -1) {
          if (numRawSamplesProcessedLatest < LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE) {
            searchSizeBoundary = averagesStartPosNewHead - averagesStartOffsetFromHead - 1;
            if (searchSizeBoundary <= 0) {
              // goto searchNextDirection
              numFullDirectionsSearched++;
              if (numFullDirectionsSearched < 2) {
                searchDirection = -searchDirection;
                continue directionLoop;
              }
              break directionLoop;
            }
          } else {
            searchSizeBoundary = searchSize;
          }
        } else {
          // C live_pitch_shifter.cpp:661 indexes readPos[kNumMovingAverages + 1], one PAST the
          // 4-element stack array (C UB — on hardware it reads adjacent stack memory). The
          // semantically intended value is the END of the averages window, i.e. the last filled
          // element readPos[kNumMovingAverages]; Java uses that defined behaviour.
          searchSizeBoundary =
              (numRawSamplesProcessedLatest - readPos[TimeStretcher.K_NUM_MOVING_AVERAGES])
                  & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1);
        }

        int endOffset = Math.min(searchSize, searchSizeBoundary) * searchDirection;
        boolean restartOther = false;

        // ── slide loop (C:670-742) ──
        do {
          for (int i = 0; i < TimeStretcher.K_NUM_MOVING_AVERAGES + 1; i++) {
            int readValue = liveInputBuffer.rawBuffer[readPos[i] * numChannels] >> 16;
            if (numChannels == 2) {
              readValue += liveInputBuffer.rawBuffer[readPos[i] * 2 + 1] >> 16;
            }
            readPos[i] =
                (readPos[i] + searchDirection) & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1);
            if (i < TimeStretcher.K_NUM_MOVING_AVERAGES) {
              newHeadRunningTotals[i] -= readValue * searchDirection;
            }
            if (i > 0) {
              newHeadRunningTotals[i - 1] += readValue * searchDirection;
            }
          }

          int differenceAbs =
              TimeStretcher.getTotalDifferenceAbs(oldHeadTotals, newHeadRunningTotals);

          if (offsetNow == 0
              && searchDirection == 1
              && numFullDirectionsSearched == 0
              && differenceAbs > bestDifferenceAbs) {
            restartOther = true;
            break; // → restart with opposite direction
          }

          int newOffsetNow = offsetNow + searchDirection;

          boolean thisOffsetIsBestMatch = (differenceAbs < bestDifferenceAbs);
          if (thisOffsetIsBestMatch) {
            bestDifferenceAbs = differenceAbs;
            bestOffset = newOffsetNow;
          }

          int thisTotalChange = TimeStretcher.getTotalChange(oldHeadTotals, newHeadRunningTotals);

          // C:711 — sign flip → sub-sample refinement
          if ((thisTotalChange >>> 31) != (lastTotalChange >>> 31)) {
            if (phaseIncrement != K_MAX_SAMPLE_VALUE
                && (thisOffsetIsBestMatch || offsetNow == bestOffset)) {
              long thisAbs = Math.abs((long) thisTotalChange);
              long lastAbs = Math.abs((long) lastTotalChange);
              additionalOscPos = (int) ((lastAbs << 24) / (lastAbs + thisAbs));
              if (searchDirection == -1) {
                additionalOscPos = K_MAX_SAMPLE_VALUE - additionalOscPos;
              }
              if (thisOffsetIsBestMatch != (searchDirection == -1)) {
                bestOffset--;
              }
            }
            timesSignFlipped++;
            if (timesSignFlipped >= 2) {
              break directionLoop; // → stopSearch
            }
          }

          offsetNow = newOffsetNow;
          lastTotalChange = thisTotalChange;
        } while (offsetNow != endOffset);

        if (restartOther) {
          searchDirection = -searchDirection;
          continue directionLoop;
        }

        // ── searchNextDirection (C:744-750) ──
        numFullDirectionsSearched++;
        if (numFullDirectionsSearched < 2) {
          searchDirection = -searchDirection;
          continue directionLoop;
        }
        break directionLoop;
      }
    }
    // stopSearch (C:753)

    additionalOscPos += playHeads[TimeStretcher.PLAY_HEAD_OLDER].oscPos; // C:755
    if (additionalOscPos >= K_MAX_SAMPLE_VALUE) { // C:756
      additionalOscPos -= K_MAX_SAMPLE_VALUE;
      bestOffset++; // C:758
    }

    playHeads[TimeStretcher.PLAY_HEAD_NEWER].rawBufferReadPos =
        (playHeads[TimeStretcher.PLAY_HEAD_NEWER].rawBufferReadPos + bestOffset)
            & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1); // C:761-762

    // C:778-793 — set the new play-head mode
    if (phaseIncrement == K_MAX_SAMPLE_VALUE) { // C:778
      playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode =
          LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT; // C:779
      playHeads[TimeStretcher.PLAY_HEAD_NEWER].rawBufferReadPos =
          numRawSamplesProcessedAtNowTime & (LiveInputBuffer.K_INPUT_RAW_BUFFER_SIZE - 1); // C:780
    } else { // C:784
      playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode =
          LivePitchShifterPlayHead.PlayHeadMode.RAW_REPITCHING; // C:785
      playHeads[TimeStretcher.PLAY_HEAD_NEWER].fillInterpolationBuffer(
          liveInputBuffer, numChannels); // C:789
      playHeads[TimeStretcher.PLAY_HEAD_NEWER].oscPos = additionalOscPos; // C:790
    }

    playHeads[TimeStretcher.PLAY_HEAD_NEWER].percPos = 0xFFFFFFFF; // C:797

    nextCrossfadeLength = thisCrossfadeLength;

    if (thisCrossfadeLength != 0) { // C:799
      crossfadeProgress = 0; // C:800
      crossfadeIncrement = (K_MAX_SAMPLE_VALUE - 1) / thisCrossfadeLength + 1; // C:801
    } else {
      crossfadeProgress = K_MAX_SAMPLE_VALUE; // C:804
    }

    // C:832
    samplesIntoHop = 0;
  }
}
