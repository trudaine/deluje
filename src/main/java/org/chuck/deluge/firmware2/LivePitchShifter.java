package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code processing/live/live_pitch_shifter.{cpp,h}}: the live (input-monitoring)
 * pitch shifter — a two-play-head crossfade engine over the {@link LiveInputBuffer} ring.
 * {@code INPUT_ENABLE_REPITCHED_BUFFER == 0}, so the repitched-buffer paths are omitted.
 *
 * <p>IN PROGRESS: this increment ports the constructor, the search-parameter tables + parameter head
 * ({@link #computeLiveHopParameters}, the pitchLog→table lookup of hopEnd), and the small helpers. The
 * render loop + the hopEnd beam-search body are the remaining (large) units.
 */
public class LivePitchShifter {

  static final int K_MAX_SAMPLE_VALUE = 16777216;
  static final int K_INTERPOLATION_MAX_NUM_SAMPLES = 16;

  // hopEnd parameter tables (live_pitch_shifter.cpp:308-354). Fine = 17 (2^4+1); Coarse = 5 (2^2+1) with
  // one trailing duplicate so interpolateTableSigned's table[whichValue+1] read at the clamped upper
  // bound stays in bounds (×0 there ⇒ bit-identical to the C, same fix as the time-stretcher tables).
  static final int[] minSearchFine = {8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 12, 14, 16, 17};
  static final int[] minSearchCoarse = {10, 10, 10, 17, 20, 20};
  static final int[] maxSearchFine = {8, 8, 8, 8, 8, 8, 8, 8, 8, 8, 10, 11, 12, 14, 16, 18, 21};
  static final int[] maxSearchCoarse = {15, 15, 15, 21, 20, 20};
  static final int[] percThresholdFine = {18, 18, 18, 18, 24, 30, 25, 35, 130, 40, 40, 45, 50, 47, 45, 42, 40};
  static final int[] percThresholdCoarse = {15, 18, 130, 40, 20, 20};
  static final int[] crossfadeFine = {30, 31, 32, 34, 35, 30, 25, 10, 10, 15, 15, 22, 30, 16, 15, 13, 40};
  static final int[] crossfadeCoarse = {30, 30, 10, 40, 20, 20};
  static final int[] maxHopLengthFine = {20, 27, 35, 42, 50, 60, 70, 90, 140, 140, 140, 140, 140, 140, 140, 140, 140};
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

  public int crossfadeProgress;   // uint32, out of kMaxSampleValue
  public int crossfadeIncrement;  // uint32
  public int nextCrossfadeLength;
  public int samplesTilHopEnd;
  public int samplesIntoHop;
  public int percThresholdForCut;

  public final LivePitchShifterPlayHead[] playHeads =
      {new LivePitchShifterPlayHead(), new LivePitchShifterPlayHead()};

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

    playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode = LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT;
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
        && playHeads[TimeStretcher.PLAY_HEAD_NEWER].mode == LivePitchShifterPlayHead.PlayHeadMode.RAW_DIRECT;
  }

  /**
   * C: hopEnd parameter head (live_pitch_shifter.cpp:382-420) — pitchLog = quickLog(phaseIncrement),
   * then Fine (±1 octave) / Coarse table interpolation for the search/crossfade/hop parameters. Pure.
   *
   * @return {@code {minSearch, maxSearch, percThresholdForCut, nextCrossfadeLength, maxHopLength, randomElement}}
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
      maxHopLength = (Functions.interpolateTableSigned(position, 26, maxHopLengthFine, 4) >> 16) * 100;
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
      maxHopLength = (Functions.interpolateTableSigned(position, 27, maxHopLengthCoarse, 2) >> 16) * 100;
      randomElement = Functions.interpolateTableSigned(position, 27, randomCoarse, 2);
    }

    return new int[] {minSearch, maxSearch, percThreshold, nextCrossfade, maxHopLength, randomElement};
  }
}
