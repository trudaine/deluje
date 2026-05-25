package org.chuck.deluge.engine;

import java.util.concurrent.atomic.AtomicBoolean;
import org.chuck.deluge.BridgeContract;

/**
 * Dedicated step sequencer clock thread for NativeJavaSequencer.
 *
 * <p>Replaces the {@link java.util.concurrent.ScheduledExecutorService}-based approach with a tight
 * spin-wait loop that delivers step ticks with swing-aware timing, stutter support, and
 * bar-boundary clip queue processing.
 *
 * <p>Matching the behavior of {@code DelugeEngineDSL.clock_shred()}:
 *
 * <ul>
 *   <li>Swing per even/odd step (same formula: {@code 1.0 ± (swing - 0.5) * 0.4})
 *   <li>Stutter mode: repeat current step at sub-division rate when {@code G_STUTTER_ON}
 *   <li>Transport-aware: stops advancing when {@code G_PLAY == 0}
 *   <li>Bar-boundary clip queue processing at {@code step % 16 == 0}
 * </ul>
 */
public class SequencerClock implements Runnable {

  private final AtomicBoolean running = new AtomicBoolean(false);
  private final BridgeContract bridge;
  private final TickListener listener;

  private volatile boolean playing = false;
  private volatile int masterStep = 0;

  private Thread clockThread;

  /** Nanoseconds per millisecond. */
  private static final long NS_PER_MS = 1_000_000L;

  /** Minimum sleep duration before falling back to Thread.yield(). */
  private static final long MIN_SLEEP_NS = 500_000L; // 0.5 ms

  /** Listener interface called on each clock tick from the clock thread. */
  @FunctionalInterface
  public interface TickListener {
    /**
     * Called on every step tick. Runs on the clock thread — implementations must be fast (ideally
     * just writing to shared volatile fields).
     *
     * @param step the current absolute step number
     * @param isNewStep true if this is a new step (not a stutter repeat)
     */
    void onTick(int step, boolean isNewStep);
  }

  public SequencerClock(BridgeContract bridge, TickListener listener) {
    this.bridge = bridge;
    this.listener = listener;
  }

  public void start() {
    if (running.compareAndSet(false, true)) {
      clockThread = new Thread(this, "SequencerClock");
      clockThread.setPriority(Thread.MAX_PRIORITY);
      clockThread.setDaemon(true);
      clockThread.start();
    }
  }

  public void stop() {
    running.set(false);
  }

  public boolean isRunning() {
    return running.get();
  }

  public int getMasterStep() {
    return masterStep;
  }

  /** Calculate the duration of a single step in nanoseconds, with swing. */
  public long calculateStepDurationNs(int step, double bpm, double swing) {
    double baseSec = 60.0 / bpm / 4.0;
    double adj = (step % 2 == 0) ? 1.0 + (swing - 0.5) * 0.4 : 1.0 - (swing - 0.5) * 0.4;
    return (long) (baseSec * adj * 1_000_000_000L);
  }

  @Override
  public void run() {
    long lastStepStart = System.nanoTime();
    int step = 0;

    // Pre-alloc: avoid allocating Duration on every tick
    long lastReportedPlay = -1;

    while (running.get()) {
      long play = (long) bridge.getPlayState();
      long durNs;

      if (play == 0) {
        if (playing) {
          playing = false;
          step = 0;
          masterStep = 0;
          // Fire one tick so the renderer can flush / reset
          listener.onTick(0, true);
        }
        // Not playing — sleep in 10ms chunks to avoid busy-wait
        if (lastReportedPlay != 0) {
          lastReportedPlay = 0;
        }
        sleepNs(10 * NS_PER_MS);
        continue;
      }

      if (!playing) {
        playing = true;
        step = 0;
        masterStep = 0;
        lastStepStart = System.nanoTime();
      }
      lastReportedPlay = play;

      // Read current master state
      double bpm = bridge.getBpm();
      double swing = bridge.getSwing();
      long stutter = (long) bridge.getStutterOn();
      double stutterDiv = bridge.getStutterDiv();

      if (stutter == 0) {
        // Normal step advance
        durNs = calculateStepDurationNs(step, bpm, swing);

        // Process bar-boundary clip queues (matching clock_shred: step % barSteps == 0)
        // Do this before the tick so the new clip is active for this step
        int barSteps = (bridge != null && bridge.getTrackLength(0) % 12 == 0) ? 12 : 16;
        if (step % barSteps == 0) {
          bridge.processLaunchQueue();
        }

        // Set current step visible to other threads (matching G_CURRENT_STEP)
        bridge.setCurrentStep(step);

        // Notify listener
        listener.onTick(step, true);

        // Wait for the step duration, compensating for drift
        long elapsed = System.nanoTime() - lastStepStart;
        long remaining = durNs - elapsed;
        if (remaining > MIN_SLEEP_NS) {
          sleepNs(remaining - MIN_SLEEP_NS / 2);
          // Spin-wait the rest for precision
          while (System.nanoTime() - lastStepStart < durNs) {
            Thread.yield();
          }
        } else if (remaining > 0) {
          while (System.nanoTime() - lastStepStart < durNs) {
            Thread.yield();
          }
        }

        lastStepStart += durNs;
        step++;
        masterStep++;
      } else {
        // Stutter mode: repeat same step at higher rate
        durNs = calculateStepDurationNs(step, bpm, swing);
        long subDurNs = (long) (durNs / Math.max(1.0, stutterDiv));

        bridge.setCurrentStep(step);
        listener.onTick(step, false);

        long elapsed = System.nanoTime() - lastStepStart;
        long remaining = subDurNs - elapsed;
        if (remaining > MIN_SLEEP_NS) {
          sleepNs(remaining - MIN_SLEEP_NS / 2);
          while (System.nanoTime() - lastStepStart < subDurNs) {
            Thread.yield();
          }
        } else if (remaining > 0) {
          while (System.nanoTime() - lastStepStart < subDurNs) {
            Thread.yield();
          }
        }

        lastStepStart += subDurNs;
        // Step doesn't advance during stutter — just masterStep keeps reference
      }
    }
  }

  private static void sleepNs(long ns) {
    if (ns <= 0) return;
    long ms = ns / NS_PER_MS;
    int nanos = (int) (ns % NS_PER_MS);
    try {
      Thread.sleep(ms, nanos);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
