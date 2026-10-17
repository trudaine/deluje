package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests guarding C++ parity fixes in {@link Sidechain}, {@link Delay}, and {@link DelayBuffer}
 * against {@code sidechain.cpp}, {@code delay.cpp}, and {@code delay_buffer.cpp}.
 */
public class SidechainDelayParityTest {

  @Test
  void sidechainSignedNewOffsetComparisonAcceptsStrongHit() {
    // C: sidechain.cpp:130, 136 — int32_t newOffset = ONE_Q31 - pendingHitStrength;
    // if (newOffset < lastValue). When pendingHitStrength == Integer.MIN_VALUE + 1 or > ONE_Q31,
    // newOffset is negative; signed comparison must accept the hit.
    Sidechain sc = new Sidechain();
    sc.syncLevel = 0;
    sc.attack = 1000;
    sc.pendingHitStrength = 0x7FFFFFFF + 100; // slightly above ONE_Q31 in unsigned / negative int
    // newOffset = 0x7FFFFFFF - (0x7FFFFFFF + 100) = -100 < lastValue (0x7FFFFFFF).
    sc.render(1, 0);
    assertEquals(Envelope.Stage.ATTACK, sc.status);
    assertEquals(-100, sc.envelopeOffset);
  }

  @Test
  void sidechainUnsignedPosCompletesAttackAndReleaseStagesOnLargeIncrement() {
    // C: sidechain.cpp:153, 176, sidechain.h:33 — uint32_t pos >= 8388608.
    Sidechain sc = new Sidechain();
    sc.syncLevel = 0;
    sc.attack = 0x40000000; // 1 << 30
    sc.release = 0x40000000;
    sc.status = Envelope.Stage.ATTACK;
    sc.pos = 0;
    sc.envelopeOffset = 0;
    sc.envelopeHeight = 0x7FFFFFFF;

    // numSamples = 2 -> pos = 2 * 0x40000000 = 0x80000000 (negative signed int, >= 8388608
    // unsigned).
    // ATTACK transitions to RELEASE and runs doRelease, where pos again hits 0x80000000 -> OFF.
    int out = sc.render(2, 0);
    assertEquals(Envelope.Stage.OFF, sc.status);
    assertEquals(0, out);
  }

  @Test
  void sidechainRetrospectiveHitUsesUnsignedStageLengthDivision() {
    // C: sidechain.cpp:65-66, 79-80 — uint32_t alteredAttack / alteredRelease;
    // 8388608 / alteredAttack is unsigned division.
    Sidechain sc = new Sidechain();
    sc.syncLevel = 0;
    sc.attack = 0x80000000; // > 8388608 unsigned -> 8388608 / alteredAttack == 0
    sc.release = 0x80000000; // > 8388608 unsigned -> 8388608 / alteredRelease == 0

    sc.registerHitRetrospectively(1000000, 1);
    assertEquals(Envelope.Stage.OFF, sc.status);
  }

  @Test
  void delayBufferGetIdealSizeTreatsRateAsUnsignedAndGuardsZero() {
    // C: delay_buffer.cpp:64-65 — uint32_t newRate; (uint64_t)16384 * 16777216 / newRate.
    // For newRate = 0x80000000 (2^31): (2^14 * 2^24) / 2^31 = 2^7 = 128.
    int[] inner = Delay.DelayBuffer.getIdealBufferSizeFromRate(0x80000000);
    int[] standalone = DelayBuffer.getIdealBufferSizeFromRate(0x80000000);
    assertEquals(128, inner[0]);
    assertEquals(0, inner[1]);
    assertEquals(128, standalone[0]);
    assertEquals(0, standalone[1]);

    // Zero rate is guarded cleanly and clamps to KB_MAX_SIZE (88200).
    int[] innerZero = Delay.DelayBuffer.getIdealBufferSizeFromRate(0);
    int[] standaloneZero = DelayBuffer.getIdealBufferSizeFromRate(0);
    assertEquals(88200, innerZero[0]);
    assertEquals(1, innerZero[1]);
    assertEquals(88200, standaloneZero[0]);
    assertEquals(1, standaloneZero[1]);
  }

  @Test
  void delayBufferMakeNativeRatePreciseRelativeTreatsOtherRateAsUnsigned() {
    // C: delay_buffer.cpp:86-90, delay_buffer.h:299 — otherBuffer.native_rate_ is uint32_t.
    Delay.DelayBuffer innerA = new Delay.DelayBuffer();
    Delay.DelayBuffer innerB = new Delay.DelayBuffer();
    innerA.init(0x80000000); // size_ = 128, native_rate_ = 0x80000000
    innerB.init(0x40000000); // size_ = 256, native_rate_ = 0x40000000
    innerB.makeNativeRatePreciseRelativeToOtherBuffer(innerA);
    assertEquals(0x40000000, innerB.native_rate_);

    DelayBuffer standaloneA = new DelayBuffer();
    DelayBuffer standaloneB = new DelayBuffer();
    standaloneA.init(0x80000000);
    standaloneB.init(0x40000000);
    standaloneB.makeNativeRatePreciseRelativeToOtherBuffer(standaloneA);
    assertEquals(0x40000000, standaloneB.nativeRate());
  }

  @Test
  void standaloneDelayBufferClearRespectsSmallSizeWithoutExtraSpace() {
    // C: delay_buffer.cpp:26-62 — verify init() and clear() succeed without out-of-bounds writes
    // when includeExtraSpace=false at maximum uint32_t rate (bufferSize=64).
    DelayBuffer buf = new DelayBuffer();
    assertDoesNotThrow(() -> buf.init(0xFFFFFFFF, 0, false));
    assertEquals(64, buf.size());
  }
}
