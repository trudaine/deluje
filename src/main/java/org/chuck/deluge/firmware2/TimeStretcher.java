package org.chuck.deluge.firmware2;

/**
 * Faithful port of {@code dsp/timestretch/time_stretcher.cpp} + {@code .h} — IN PROGRESS (Phase B of
 * docs/FIRMWARE2_SAMPLE_ENGINE_PLAN.md).
 *
 * <p><b>Scope note.</b> The C TimeStretcher's main methods ({@code hopEnd}, {@code readFromBuffer},
 * {@code init}/{@code reInit}/{@code setupNewPlayHead}) are NOT separable DSP — they are interleaved
 * throughout with the sample-streaming engine ({@code SamplePlaybackGuide}, {@code SampleLowLevelReader},
 * {@code Cluster}, {@code SampleCache}). Porting them faithfully requires those classes first (revised
 * plan §3). What IS self-contained and ported here, faithfully and tested:
 * <ul>
 *   <li>the crossfade match metrics {@code getTotalDifferenceAbs} / {@code getTotalChange}
 *       (time_stretcher.h:106-127), pure functions over the moving-average totals;</li>
 *   <li>{@code getSamplePos} (time_stretcher.cpp:1227-1234);</li>
 *   <li>the {@code TimeStretch} constants (definitions_cxx.hpp:780-795).</li>
 * </ul>
 * Everything else is deferred until the reader/guide position-and-loop math is ported. Every method
 * here cites the C file:line it ports.
 */
public class TimeStretcher {

  // ── TimeStretch constants (definitions_cxx.hpp:780-795) ──

  /** C: definitions_cxx.hpp:781 */
  public static final int K_DEFAULT_FIRST_HOP_LENGTH = 200;
  /** C: definitions_cxx.hpp:786 — 3 sounds way better than 2. */
  public static final int K_NUM_MOVING_AVERAGES = 3;
  /** C: definitions_cxx.hpp:790 — 30..40 ideal; higher screws up high notes. */
  public static final int K_MOVING_AVERAGE_LENGTH = 35;
  /** C: definitions_cxx.hpp:794 — TIME_STRETCH_ENABLE_BUFFER is 0, so 256. */
  public static final int K_BUFFER_SIZE = 256;

  // ── State (time_stretcher.h:70-99) — only the fields the ported methods touch so far ──

  /** C: time_stretcher.h:70 — whole samples (both channels), from audioDataStart, {@code <<} 24. */
  public long samplePosBig;

  // ── getSamplePos (time_stretcher.cpp:1227-1234) ──

  /** C: time_stretcher.cpp:1227-1234. */
  public int getSamplePos(int playDirection) {
    if (playDirection == 1) {
      return (int) (samplePosBig >> 24);
    } else {
      return (int) ((samplePosBig + 16777215) >> 24);
    }
  }

  // ── getTotalDifferenceAbs / getTotalChange (time_stretcher.h:106-127) ──

  /** C: time_stretcher.h:106-115 — sum of |totals2[i] - totals1[i]| over the moving averages. */
  public static int getTotalDifferenceAbs(int[] totals1, int[] totals2) {
    int totalDifferenceAbs = 0;
    for (int i = 0; i < K_NUM_MOVING_AVERAGES; i++) {
      int differenceAbsHere = totals2[i] - totals1[i];
      if (differenceAbsHere < 0) {
        differenceAbsHere = -differenceAbsHere;
      }
      totalDifferenceAbs += differenceAbsHere;
    }
    return totalDifferenceAbs;
  }

  /** C: time_stretcher.h:117-127 — sum(totals2) - sum(totals1) over the moving averages. */
  public static int getTotalChange(int[] totals1, int[] totals2) {
    int totalChange = 0;
    for (int i = 0; i < K_NUM_MOVING_AVERAGES; i++) {
      totalChange += totals2[i];
    }
    for (int i = 0; i < K_NUM_MOVING_AVERAGES; i++) {
      totalChange -= totals1[i];
    }
    return totalChange;
  }

  // ── Hop-size / crossfade parameter tables (time_stretcher.cpp:197-230) ──
  // Fine tables: 17 entries (2^4+1), Coarse: 5 (2^2+1). Indexed by speedLog around ±1 octave.

  // The C arrays are 5 (Coarse) / 17 (Fine). At the clamped upper bound (speedLog == 896<<20) the C
  // interpolateTableSigned reads table[5] of a Coarse array — out of bounds, but harmless because
  // strength2 == 0 there. Java throws on OOB, so the Coarse arrays get one trailing duplicate; ×0 keeps
  // the result bit-identical to the C.
  static final int[] minHopSizeCoarse = {2500, 3000, 3000, 600, 300, 300};
  static final int[] minHopSizeFine = {
    3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 3000, 2500, 2000, 1500, 1000, 900, 800, 700, 600
  };
  static final int[] maxHopSizeCoarse = {5000, 6500, 11000, 4000, 2500, 2500};
  static final int[] maxHopSizeFine = {
    6500, 7000, 8000, 9000, 9500, 9750, 10000, 11000, 11000, 7500, 8000, 6500, 5000, 4750, 4500, 4250,
    4000
  };
  static final int[] crossfadeProportionalCoarse = {200, 160, 0, 9, 9, 9};
  static final int[] crossfadeProportionalFine = {
    160, 140, 125, 110, 90, 70, 50, 20, 0, 20, 20, 20, 20, 17, 14, 11, 9
  };
  static final int[] crossfadeAbsoluteCoarse = {10, 10, 60, 40, 20, 20};
  static final int[] crossfadeAbsoluteFine = {
    10, 10, 10, 10, 10, 10, 10, 170, 60, 90, 20, 30, 40, 40, 40, 40, 40
  };
  static final int[] randomCoarse = {85, 120, 0, 0, 0, 0};
  static final int[] randomFine = {
    120, 95, 70, 45, 20, 15, 10, 10, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

  /** Indices into the {@link #computeHopParameters} result. */
  public static final int HP_MIN_BEAM_WIDTH = 0;
  public static final int HP_MAX_BEAM_WIDTH = 1;
  public static final int HP_CROSSFADE_PROPORTIONAL = 2;
  public static final int HP_CROSSFADE_ABSOLUTE = 3;
  public static final int HP_RANDOM_ELEMENT = 4;

  /**
   * C: hopEnd (time_stretcher.cpp:317-374) — the self-contained head of the hop: turn the time-stretch
   * ratio into the beam-width / crossfade parameters via {@code quickLog} + table interpolation, then
   * apply the random element to {@code minBeamWidth}. Pure (the C reads {@code getNoise()} for the
   * random element; passed in here as {@code noise} so this is deterministic and testable).
   *
   * @return {@code {minBeamWidth, maxBeamWidth, crossfadeProportional, crossfadeAbsolute, randomElement}}
   */
  public static int[] computeHopParameters(int timeStretchRatio, int noise) {
    int speedLog = Functions.quickLog(timeStretchRatio); // C:317

    int minBeamWidth;
    int maxBeamWidth;
    int crossfadeProportional;
    int crossfadeAbsolute;
    int randomElement;

    // Neutral is (832 << 20); each octave is (32 << 20). Within ±1 octave use the Fine tables. (C:330)
    if (speedLog >= (800 << 20) && speedLog < (864 << 20)) {
      int position = speedLog - (800 << 20);
      minBeamWidth = Functions.interpolateTableSigned(position, 26, minHopSizeFine, 4) >> 16;
      maxBeamWidth = Functions.interpolateTableSigned(position, 26, maxHopSizeFine, 4) >> 16;
      crossfadeProportional = Functions.interpolateTableSigned(position, 26, crossfadeProportionalFine, 4) << 8;
      crossfadeAbsolute = Functions.interpolateTableSigned(position, 26, crossfadeAbsoluteFine, 4) >> 16;
      randomElement = Functions.interpolateTableSigned(position, 26, randomFine, 4);
    } else { // C:342-358
      if (speedLog > (896 << 20)) {
        speedLog = (896 << 20);
      } else if (speedLog < (768 << 20)) {
        speedLog = (768 << 20);
      }
      int position = speedLog - (768 << 20);
      minBeamWidth = Functions.interpolateTableSigned(position, 27, minHopSizeCoarse, 2) >> 16;
      maxBeamWidth = Functions.interpolateTableSigned(position, 27, maxHopSizeCoarse, 2) >> 16;
      crossfadeProportional = Functions.interpolateTableSigned(position, 27, crossfadeProportionalCoarse, 2) << 8;
      crossfadeAbsolute = Functions.interpolateTableSigned(position, 27, crossfadeAbsoluteCoarse, 2) >> 16;
      randomElement = Functions.interpolateTableSigned(position, 27, randomCoarse, 2);
    }

    // C:372-373 — apply random element to minBeamWidth.
    minBeamWidth +=
        Functions.multiply_32x32_rshift32(
                minBeamWidth, Functions.multiply_32x32_rshift32(noise, randomElement << 8))
            << 2;

    return new int[] {minBeamWidth, maxBeamWidth, crossfadeProportional, crossfadeAbsolute, randomElement};
  }
}
