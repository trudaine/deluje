package org.chuck.deluge.firmware.model.clip;

import org.chuck.deluge.firmware.gui.views.ArrangerView;
import org.chuck.deluge.firmware.gui.views.SessionView;
import org.chuck.deluge.firmware.hid.MatrixDriver;
import org.chuck.deluge.firmware.model.Song;

/**
 * Port of the Deluge's ClipMinder class. Handles automatic view transitions between Session and
 * Arranger modes during playback.
 */
public class ClipMinder {
  private final Song song;
  private final MatrixDriver matrixDriver;

  public ClipMinder(Song song, MatrixDriver matrixDriver) {
    this.song = song;
    this.matrixDriver = matrixDriver;
  }

  public void transitionToArrangerOrSession() {
    // ── Bit-Accurate View Transition ──
    if (song.isInArrangerMode()) {
      matrixDriver.pushUI(new ArrangerView(song));
    } else {
      matrixDriver.pushUI(new SessionView(song));
    }
  }
}
