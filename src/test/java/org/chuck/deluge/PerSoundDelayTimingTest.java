package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Guards the per-sound delay port (the firmware delay is per-sound, in processFX; our engine had
 * only a master delay driven by song-level globals, so a song's instrument delay never sounded —
 * hardware echoed at 1.0s, our render at ~65ms). TestDelayFidelity's instrument delay is syncLevel
 * 4 = 8×16th = a half note = 1.0s at 120 BPM. We render offline and assert the first echo lands
 * near 1.0s after the dry hit.
 */
public class PerSoundDelayTimingTest {

  @Test
  void instrumentDelayEchoesAtSyncedTime() throws Exception {
    ProjectModel pm =
        DelugeXmlParser.parseSong(new File("src/main/resources/SONGS/TestDelayFidelity.xml"));
    Song song = FirmwareFactory.createSong(pm);
    FirmwareAudioEngine eng = new FirmwareAudioEngine();
    for (var clip : song.clips) {
      if (clip instanceof InstrumentClip ic && ic.sound != null) {
        eng.sounds.add(ic.sound);
      }
    }
    var pb = new org.chuck.deluge.firmware.playback.PlaybackHandler();
    pb.setSong(song);
    pb.start();

    int sr = 44100;
    int n = 128;
    double ticksPerSample = (120.0 / 60.0) * 96.0 / sr;
    double acc = 0;
    int total = (int) (2.5 * sr);
    float[] mono = new float[total];
    int idx = 0;
    for (int done = 0; done < total; done += n) {
      acc += ticksPerSample * n;
      int w = (int) acc;
      if (w > 0) {
        pb.advanceTicks(w);
        acc -= w;
      }
      eng.renderBlock(n);
      for (int i = 0; i < n && idx < total; i++) {
        mono[idx++] = eng.masterBuffer[i].l / 2147483648f;
      }
    }

    // 5ms RMS envelope; find onsets > 4% of peak, separated by > 0.3s.
    int win = (int) (0.005 * sr);
    int ne = total / win;
    float[] env = new float[ne];
    float peak = 0;
    for (int i = 0; i < ne; i++) {
      double s = 0;
      for (int j = 0; j < win; j++) s += mono[i * win + j] * mono[i * win + j];
      env[i] = (float) Math.sqrt(s / win);
      peak = Math.max(peak, env[i]);
    }
    java.util.List<Double> onsets = new java.util.ArrayList<>();
    double lastT = -1;
    for (int i = 0; i < ne; i++) {
      double t = i * win / (double) sr;
      if (env[i] > 0.04 * peak && (lastT < 0 || t - lastT > 0.3)) {
        onsets.add(t);
        lastT = t;
      }
    }

    assertTrue(onsets.size() >= 2, "expected a dry hit + at least one echo; got " + onsets);
    double firstEcho = onsets.get(1) - onsets.get(0);
    // syncLevel 4 @ 120 BPM = 1.0s; allow 10% for envelope-window granularity.
    assertEquals(1.0, firstEcho, 0.1, "per-sound delay should echo ~1.0s after the dry hit");
  }
}
