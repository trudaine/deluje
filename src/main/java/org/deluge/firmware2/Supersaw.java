package org.deluge.firmware2;

/**
 * Faithful port of {@code dsp/oscillators/supersaw.{h,cpp}} (upstream {@code 451c9f82f}, "basic
 * supersaw", 2026-10-31).
 *
 * <p>JP-8000 style supersaw: a centre saw plus up to six detuned side saws, band limited with
 * polyBLEP. Spread (the wave-index param) sets both the detune (quadratically) and the side/centre
 * mix; pulse width takes saws away.
 *
 * <p><b>Vector code.</b> The C renders four samples per NEON vector and is templated on the saw
 * count so each gets a fully unrolled loop. Those are code-generation concerns: each output sample
 * depends only on its own phases, so this port is a scalar loop with the same arithmetic — {@code
 * MultiplyFixedQMax} is {@code vqdmulh} ({@link #qdmulh}) and {@code ShiftRightAccumulate<n>(b)} is
 * {@code a + (b >> n)} (argon vector.hpp:529, :834). The C also writes up to three samples past
 * {@code numSamples} because of its block-of-4 loop; this writes exactly {@code numSamples}, which
 * is the same for every sample the caller reads.
 *
 * <p><b>The one platform difference.</b> The per-saw reciprocals come from {@code
 * ReciprocalEstimateRefine(2)} — the ARM {@code vrecpe.f32} table estimate plus two Newton steps —
 * so {@link #reciprocalEstimate} ports the ARM ARM's {@code FPRecipEstimate} pseudocode rather than
 * calling {@code 1.0f / x}. They differ in the last bits, and that reaches the output: the
 * reciprocal sets the polyBLEP's {@code t}. (It does not reach the edge positions, which the
 * integer remainder corrects exactly.) See {@code tools/supersaw_harness/build.sh} — SIMDE's x86
 * {@code vrecpe_f32} is a bit-trick, not ARM's estimate, so the harness patches in this same
 * algorithm to keep the golden hardware-faithful.
 */
public final class Supersaw {

  /** C: supersaw.h:29 — kNumSaws. */
  public static final int NUM_SAWS = 7;

  private Supersaw() {}

  /**
   * C: supersaw.h:36-42 — sidePhasesFrom. Side phases spread around the centre phase, so a
   * retriggered supersaw always starts the same way and the saws don't start out in phase with each
   * other. Each is another golden ratio of a cycle on from the last.
   */
  public static int[] sidePhasesFrom(int centrePhase) {
    int[] phases = new int[NUM_SAWS - 1];
    for (int i = 0; i < phases.length; i++) {
      phases[i] = centrePhase + (i + 1) * 0x9E3779B9;
    }
    return phases;
  }

  /**
   * C: supersaw.h:46-51 — numSawsFromPulseWidth. Pulse width takes saws away: all of them at zero,
   * down to just the centre saw at full. Negative values (from patching) count the same as
   * positive, as they do for the other oscillators' pulse width.
   */
  public static int numSawsFromPulseWidth(int pulseWidth) {
    // uint32_t pulseWidthAbsolute = (int32_t)pulseWidth >= 0 ? pulseWidth : -pulseWidth; unsigned
    // negation, so INT_MIN stays INT_MIN (0x80000000) rather than becoming an error case.
    long pulseWidthAbsolute = (pulseWidth >= 0 ? pulseWidth : -pulseWidth) & 0xFFFFFFFFL;
    int removed = (int) ((pulseWidthAbsolute * NUM_SAWS) >>> 31);
    return NUM_SAWS - Math.min(removed, NUM_SAWS - 1);
  }

  // C supersaw.cpp:37-39 — frequency offset of each saw at full detune (Adam Szabo, "How to
  // Emulate the Super Saw", 2010), and a spare lane so the increments come out as two vectors.
  // Ordered as saws get added: centre, then the outer pair, then the middle pair, then the inner.
  private static final float[] DETUNE_OFFSETS = {
    0.f, -0.11002313f, 0.10745242f, -0.06288439f, 0.06216538f, -0.01952356f, 0.01991221f, 0.f,
  };

  /** C: supersaw.cpp:42 — spread at the top of the wave index param. */
  private static final float MAX_SPREAD = 0.6f;

  /** C: supersaw.cpp:45-47 — centreMix. */
  private static float centreMix(float spread) {
    return 0.99785f - 0.55366f * spread;
  }

  /** C: supersaw.cpp:48-50 — sideMix. */
  private static float sideMix(float spread) {
    return (1.2841f - 0.73764f * spread) * spread + 0.044372f;
  }

  /**
   * C: supersaw.cpp:53-55 — sideShift. The side saws are summed shifted right by this, the least
   * that can't overflow. {@code std::bit_width(numSaws - 2)}.
   */
  private static int sideShift(int numSaws) {
    return 32 - Integer.numberOfLeadingZeros(numSaws - 2);
  }

  /** C: {@code MultiplyFixedQMax} — NEON {@code vqdmulh}: saturate((2 * a * b) >> 32). */
  static int qdmulh(int a, int b) {
    long product = ((long) a * (long) b) >> 31;
    if (product > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    if (product < Integer.MIN_VALUE) {
      return Integer.MIN_VALUE;
    }
    return (int) product;
  }

  /**
   * ARM ARM {@code FPRecipEstimate} for single precision — what {@code vrecpe.f32} computes: an
   * 8-bit-accurate reciprocal from the exponent and a table built on the top mantissa bits.
   */
  static float reciprocalEstimate(float value) {
    int bits = Float.floatToRawIntBits(value);
    int sign = bits & 0x80000000;
    int exponent = (bits >>> 23) & 0xFF;
    int fraction = bits & 0x7FFFFF;

    if (exponent == 0xFF) { // NaN or infinity
      return (fraction != 0) ? Float.NaN : Float.intBitsToFloat(sign);
    }
    if (exponent == 0 && fraction == 0) { // zero -> infinity
      return Float.intBitsToFloat(sign | 0x7F800000);
    }
    if (exponent == 0) { // denormal: the estimate flushes to infinity like the hardware
      return Float.intBitsToFloat(sign | 0x7F800000);
    }
    if (exponent >= 253) { // the result would be denormal; the hardware returns a zero
      return Float.intBitsToFloat(sign);
    }

    // RecipEstimate(a): a = a * 2 + 1; b = 2^19 / a; r = (b + 1) / 2. ARM's `a` is the input
    // normalised into [0.5, 1) and scaled by 512 — i.e. the implicit 1 plus fraction bits <22:15>,
    // EIGHT bits. Taking seven (>>> 16) is self-consistent but wrong by up to 20%, which the
    // reciprocalEstimateMatchesArmSpec bound catches (the golden cannot: the harness runs this same
    // algorithm).
    int a = 256 + (fraction >>> 15); // 256 <= a < 512
    int doubled = a * 2 + 1;
    int b = (1 << 19) / doubled;
    int r = (b + 1) >> 1; // 256 <= r < 512

    int resultExponent = 253 - exponent;
    int resultFraction = (r - 256) << 15;
    return Float.intBitsToFloat(sign | (resultExponent << 23) | resultFraction);
  }

  /**
   * C: {@code ReciprocalEstimateRefine(2)} (argon vector.hpp:627-634) — the estimate, then two
   * Newton steps {@code est = est * (2 - value * est)} ({@code vrecps}).
   */
  static float reciprocalEstimateRefine2(float value) {
    float est = reciprocalEstimate(value);
    est = est * (2.f - value * est);
    est = est * (2.f - value * est);
    return est;
  }

  /**
   * C: supersaw.cpp:208-271 — Supersaw::render. Renders into {@code buffer}, overwriting it, at the
   * same RMS level as a single saw with a peak of 2^29.
   *
   * @param spread bipolar, ±2^30, as the wave index param
   * @param numSaws 1 to {@link #NUM_SAWS}; saws are added as the centre, then pairs from the
   *     outside in
   * @param phase in/out: {@code phase[0]} is the centre saw's phase, advanced by this call
   * @param sidePhases in/out: the six side-saw phases, advanced by this call
   * @param bandLimit whether to apply the polyBLEP; without it the saws alias, but it saves CPU
   */
  public static void render(
      int[] buffer,
      int off,
      int numSamples,
      int phaseIncrement,
      int spread,
      int numSaws,
      int[] phase,
      int[] sidePhases,
      boolean bandLimit) {
    numSaws = Math.max(1, Math.min(NUM_SAWS, numSaws)); // C:209

    // C:212-214 — spread runs 0..kMaxSpread over the whole bipolar param range; detune goes up
    // with its square, so there's finer control over the small detunes.
    int clampedSpread = Math.max(-(1 << 30), Math.min((1 << 30) - 1, spread));
    float x = (float) (clampedSpread + (1 << 30)) * (MAX_SPREAD * 0x1p-31f);
    float detune = x * x;
    float centreGain = centreMix(x);
    float sideGain = sideMix(x);
    // C:218 — std::sqrt on a float; Math.sqrt is correctly rounded in double, then narrowed, which
    // is the same value for every input here (a single rounding of an exactly-representable root).
    float norm =
        1.f
            / (float)
                Math.sqrt(centreGain * centreGain + (float) (numSaws - 1) * sideGain * sideGain);
    centreGain *= norm;
    sideGain *= norm;

    int[] increments = new int[8];
    float[] reciprocals = new float[8];
    for (int v = 0; v < increments.length; v += 4) {
      for (int lane = 0; lane < 4; lane++) {
        // C:223-225 — ratio = 1 + offset * detune (vmla, unfused), then * phaseIncrement as float.
        float ratio = 1.f + DETUNE_OFFSETS[v + lane] * detune;
        float sawIncrement = ratio * (float) (phaseIncrement & 0xFFFFFFFFL);
        // ConvertTo<uint32_t>: vcvt.u32.f32 truncates toward zero and saturates at 0 / 2^32-1.
        increments[v + lane] = convertFloatToUnsigned(sawIncrement);
        reciprocals[v + lane] = reciprocalEstimateRefine2(sawIncrement);
      }
    }
    // C:230 — float only has 24 bits; keep the centre saw exactly in tune with the rest of the
    // synth
    increments[0] = phaseIncrement;

    int[] phases = new int[NUM_SAWS];
    phases[0] = phase[0];
    System.arraycopy(sidePhases, 0, phases, 1, NUM_SAWS - 1);

    renderNaive(buffer, off, numSamples, phases, increments, numSaws, centreGain, sideGain);

    if (bandLimit) {
      // C:261-270 — a residual of 1 is half the jump of one saw, in the naive pass's units.
      final float centreScale = centreGain * 0x1p29f;
      final float sideScale = sideGain * 0x1p29f;
      for (int i = 0; i < numSaws; i++) {
        addBlepResiduals(
            buffer,
            off,
            numSamples,
            phases[i],
            increments[i],
            reciprocals[i],
            i == 0 ? centreScale : sideScale);
      }
    }

    // C:273-277 — saws that aren't playing hold their phase
    phase[0] = phases[0] + increments[0] * numSamples;
    for (int i = 1; i < numSaws; i++) {
      sidePhases[i - 1] = phases[i] + increments[i] * numSamples;
    }
  }

  /** NEON {@code vcvt.u32.f32}: truncate toward zero, saturating into the uint32 range. */
  private static int convertFloatToUnsigned(float value) {
    if (Float.isNaN(value) || value <= 0.f) {
      return 0;
    }
    long truncated = (long) value; // toward zero
    if (truncated > 0xFFFFFFFFL) {
      return -1; // 2^32 - 1
    }
    return (int) truncated;
  }

  /**
   * C: supersaw.cpp:80-127 — renderNaive. The naive (aliasing) saws, mixed. The centre saw is used
   * at full scale; the side saws are summed at 1/2^kShift scale so they cannot overflow.
   */
  private static void renderNaive(
      int[] buffer,
      int off,
      int numSamples,
      int[] phases,
      int[] increments,
      int numSaws,
      float centreGain,
      float sideGain) {
    final int shift = numSaws > 1 ? sideShift(numSaws) : 0;
    // C:88-89 — both gains in one register, as Q29 (the side gain pre-scaled by the shift).
    final int centreGainFixed = (int) (centreGain * 0x1p29f);
    final int sideGainFixed = (int) (sideGain * 0x1p29f * (1 << shift));

    for (int n = 0; n < numSamples; n++) {
      // C:94-95 — each saw's phase vector starts at phase + increment, so sample n is phase +
      // increment * (n + 1).
      int mix = qdmulh(phases[0] + increments[0] * (n + 1), centreGainFixed);
      if (numSaws > 1) {
        int sides = (phases[1] + increments[1] * (n + 1)) >> shift;
        for (int i = 2; i < numSaws; i++) {
          sides += (phases[i] + increments[i] * (n + 1)) >> shift;
        }
        mix += qdmulh(sides, sideGainFixed);
      }
      buffer[off + n] = mix;
    }
  }

  /**
   * C: supersaw.cpp:131-176 — addBlepResiduals. Adds the polyBLEP residual around every falling
   * edge of one saw. The saw is {@code (int32_t)phase}, which falls from max to min where phase
   * crosses 2^31. Edges just past the end of the buffer still correct its last sample; that edge
   * comes round again at the start of the next buffer, which then does the sample after it.
   */
  private static void addBlepResiduals(
      int[] buffer,
      int off,
      int numSamples,
      int phase,
      int increment,
      float reciprocal,
      float scale) {
    long incrementUnsigned = increment & 0xFFFFFFFFL;
    // C:134 — phase measured from the falling edge
    long x = (phase ^ 0x80000000) & 0xFFFFFFFFL;
    int sample = -1;
    while (true) {
      // C:138-141 — the next edge is floor(y / increment) + 1 samples on. The float estimate is
      // within 1 of that; the remainder fixes it up exactly.
      long y = (~x) & 0xFFFFFFFFL;
      float estimate = (float) y * reciprocal;
      if (estimate >= (float) (numSamples - sample + 1)) {
        return;
      }
      long quotient = (long) estimate & 0xFFFFFFFFL; // (uint32_t)estimate
      long remainder = y - quotient * incrementUnsigned;
      if (remainder < 0) {
        quotient--;
        remainder += incrementUnsigned;
      } else if (remainder >= incrementUnsigned) {
        quotient++;
        remainder -= incrementUnsigned;
      }

      sample += (int) quotient + 1; // C:155 — (int32_t)quotient + 1
      if (sample > numSamples) {
        return;
      }

      // C:161-162 — how far past the edge this sample is, in samples
      x = (incrementUnsigned - 1 - remainder) & 0xFFFFFFFFL;
      float t = Math.min((float) x * reciprocal, 1.f);

      if (sample > 0) {
        buffer[off + sample - 1] -= (int) (t * t * scale);
      }
      if (sample < numSamples) {
        float u = 1.f - t;
        buffer[off + sample] += (int) (u * u * scale);
      }
    }
  }
}
