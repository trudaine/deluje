package org.chuck.deluge.firmware.engine;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global sidechain event bus coordinating multi-track high-fidelity voice envelope ducking trigger
 * signals.
 */
public class GlobalSidechainBus {
  private static final ThreadLocal<AtomicInteger> pendingHitStrength =
      ThreadLocal.withInitial(() -> new AtomicInteger(0));
  private static final ThreadLocal<Integer> activeFrameHitStrength =
      ThreadLocal.withInitial(() -> 0);

  public static void registerHit(int strength) {
    pendingHitStrength.get().updateAndGet(current -> combineHitStrengths(strength, current));
  }

  public static int getPendingHit() {
    return pendingHitStrength.get().get();
  }

  public static void beginAudioFrame() {
    activeFrameHitStrength.set(pendingHitStrength.get().getAndSet(0));
  }

  public static int getActiveFrameHit() {
    return activeFrameHitStrength.get();
  }

  public static void reset() {
    pendingHitStrength.get().set(0);
    activeFrameHitStrength.set(0);
  }

  /** Replicates C++ deluge estimation to combine multiple concurrent trigger values. */
  public static int combineHitStrengths(int strength1, int strength2) {
    long sum = (long) strength1 + (long) strength2;
    if (sum > 2147483647) sum = 2147483647;
    int maxOne = Math.max(strength1, strength2);
    return (maxOne >> 1) + ((int) sum >> 1);
  }
}
