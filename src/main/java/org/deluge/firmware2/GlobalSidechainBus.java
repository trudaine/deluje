package org.deluge.firmware2;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global sidechain event bus coordinating multi-track high-fidelity voice envelope ducking trigger
 * signals. Moved to org.deluge.firmware2 for package decoupling.
 */
public class GlobalSidechainBus {
  // C: AudioEngine::sideChainHitPending is a single GLOBAL, written from wherever a note-on
  // happens and consumed once per audio render. A ThreadLocal here silently dropped hits
  // registered on the Swing EDT / MIDI threads (manually played kicks never ducked) — the
  // pending accumulator must be shared across threads.
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

  /** Replicates C++ combineHitStrengths (functions.cpp:1419-1426). */
  public static int combineHitStrengths(int strength1, int strength2) {
    return Functions.combineHitStrengths(strength1, strength2);
  }
}
