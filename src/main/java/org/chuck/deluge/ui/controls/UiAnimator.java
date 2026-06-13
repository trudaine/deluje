package org.chuck.deluge.ui.controls;

import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.Timer;

/**
 * A single shared animation clock for the Deluge UI. One Swing {@link Timer} broadcasts a tick to
 * all registered listeners (~30fps) so blink / fade / scroll animations stay phase-aligned and
 * cheap, instead of every widget owning its own timer.
 *
 * <p>The timer only runs while there is at least one listener. Blink phase helpers mirror the
 * firmware flash periods (kFlashTime=110ms slow, kFastFlashTime=60ms fast — definitions_cxx.hpp).
 */
public final class UiAnimator {

  /** Firmware kFlashTime: slow cursor / playhead blink half-period (ms). */
  public static final long FLASH_SLOW_MS = 110;

  /** Firmware kFastFlashTime: fast record-cursor blink half-period (ms). */
  public static final long FLASH_FAST_MS = 60;

  private static final UiAnimator INSTANCE = new UiAnimator();

  public static UiAnimator get() {
    return INSTANCE;
  }

  /** A per-frame callback. */
  public interface Tick {
    void onTick(long frame);
  }

  private final CopyOnWriteArrayList<Tick> listeners = new CopyOnWriteArrayList<>();
  private final Timer timer;
  private long frame = 0;

  private UiAnimator() {
    timer = new Timer(33, e -> fire());
    timer.setCoalesce(true);
  }

  private void fire() {
    frame++;
    for (Tick t : listeners) {
      t.onTick(frame);
    }
  }

  public void add(Tick t) {
    if (t == null || listeners.contains(t)) {
      return;
    }
    listeners.add(t);
    if (!timer.isRunning()) {
      timer.start();
    }
  }

  public void remove(Tick t) {
    listeners.remove(t);
    if (listeners.isEmpty() && timer.isRunning()) {
      timer.stop();
    }
  }

  public long frame() {
    return frame;
  }

  public boolean isRunning() {
    return timer.isRunning();
  }

  /** Number of currently registered tick listeners (used to assert leak-safety in tests). */
  public int listenerCount() {
    return listeners.size();
  }

  /**
   * Returns true during the "on" half of a blink cycle of the given half-period, based on wall
   * clock time so all widgets blink in unison regardless of when they were created.
   */
  public static boolean blinkOn(long halfPeriodMs) {
    if (halfPeriodMs <= 0) {
      return true;
    }
    return ((System.currentTimeMillis() / halfPeriodMs) & 1L) == 0L;
  }
}
