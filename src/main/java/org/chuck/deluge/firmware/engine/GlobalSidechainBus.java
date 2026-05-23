package org.chuck.deluge.firmware.engine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global sidechain event bus coordinating multi-track high-fidelity voice envelope ducking trigger
 * signals.
 */
public class GlobalSidechainBus {
  private static final AtomicInteger pendingHitStrength = new AtomicInteger(0);
  private static volatile int activeFrameHitStrength = 0;

  public static void registerHit(int strength) {
    pendingHitStrength.updateAndGet(current -> combineHitStrengths(strength, current));
  }

  public static int getPendingHit() {
    return pendingHitStrength.get();
  }

  public static void beginAudioFrame() {
    activeFrameHitStrength = pendingHitStrength.getAndSet(0);
  }

  public static int getActiveFrameHit() {
    return activeFrameHitStrength;
  }

  public static void reset() {
    pendingHitStrength.set(0);
    activeFrameHitStrength = 0;
  }

  /** Replicates C++ deluge estimation to combine multiple concurrent trigger values. */
  public static int combineHitStrengths(int strength1, int strength2) {
    long sum = (long) strength1 + (long) strength2;
    if (sum > 2147483647) sum = 2147483647;
    int maxOne = Math.max(strength1, strength2);
    return (maxOne >> 1) + ((int) sum >> 1);
  }
}
