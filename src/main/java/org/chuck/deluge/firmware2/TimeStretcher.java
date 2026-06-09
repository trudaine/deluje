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
}
