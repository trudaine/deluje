package org.deluge.ui;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.Timer;

/**
 * Manages track-level and voice-level VU meters in the Deluge grid panel. Encapsulates the volume
 * envelope levels array and coordinates the high-frequency (~30fps) decay animation timer,
 * preventing UI thread leaks and keeping the main grid code clean.
 */
public class GridVuManager {
  private static final int MAX_ROWS = 128;

  private final double[] vuLevels = new double[MAX_ROWS];
  private final Map<Integer, VUMeterPanel> voiceVuMeters = new ConcurrentHashMap<>();
  private final Map<Integer, VUMeterPanel> trackVuMeters = new ConcurrentHashMap<>();
  private Timer globalVuTimer;

  public GridVuManager() {
    startTimer();
  }

  /** Register a VU meter panel for a voice row. */
  public void registerVoiceVu(int row, VUMeterPanel vu) {
    if (vu != null) {
      voiceVuMeters.put(row, vu);
    }
  }

  /** Register a VU meter panel for a track row. */
  public void registerTrackVu(int track, VUMeterPanel vu) {
    if (vu != null) {
      trackVuMeters.put(track, vu);
    }
  }

  /** Spike the VU level for a specific engine row (called when a note is triggered). */
  public void spikeVu(int engineRow) {
    if (engineRow >= 0 && engineRow < vuLevels.length) {
      vuLevels[engineRow] = 1.0;
    }
  }

  /** Clear all registered VU meters and reset levels, stopping the decay timer. */
  public void clear() {
    voiceVuMeters.clear();
    trackVuMeters.clear();
    for (int i = 0; i < vuLevels.length; i++) {
      vuLevels[i] = 0.0;
    }
    if (globalVuTimer != null) {
      globalVuTimer.stop();
    }
  }

  /** Start or restart the decay timer (decaying levels at 33ms intervals / ~30fps). */
  public void startTimer() {
    if (globalVuTimer != null) {
      globalVuTimer.stop();
    }
    globalVuTimer =
        new Timer(
            33,
            ev -> {
              voiceVuMeters.forEach(
                  (r, vu) -> {
                    if (r >= 0 && r < vuLevels.length) {
                      vuLevels[r] *= 0.80;
                      vu.setLvl(vuLevels[r]);
                    }
                  });
              trackVuMeters.forEach(
                  (t, vu) -> {
                    if (t >= 0 && t < vuLevels.length) {
                      vuLevels[t] *= 0.80;
                      vu.setLvl(vuLevels[t]);
                    }
                  });
            });
    globalVuTimer.start();
  }

  /** Shutdown the manager, ensuring the timer is stopped and resources are cleared. */
  public void shutdown() {
    clear();
  }
}
