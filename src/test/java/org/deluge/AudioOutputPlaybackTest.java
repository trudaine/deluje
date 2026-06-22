package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.playback.PlaybackHandler;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of the audio-track port: an audio track with a loaded clip must actually produce sound
 * through the engine (it was a stub — {@code createAudioSound} returned an empty FirmwareSound).
 * Builds a one-audio-track song pointing at a real WAV and asserts the rendered master is
 * non-silent.
 */
public class AudioOutputPlaybackTest {

  private static File wav() {
    File f = new File("src/main/resources/examples/data/kick.wav");
    if (!f.exists()) f = new File("../deluge/src/main/resources/examples/data/kick.wav");
    assertTrue(f.exists(), "test WAV missing: " + f.getAbsolutePath());
    return f;
  }

  @Test
  void audioTrackClipIsAudible() throws Exception {
    ProjectModel project = new ProjectModel();
    AudioTrackModel at = new AudioTrackModel("AudioTrk");
    AudioTrackModel.AudioClip ac = new AudioTrackModel.AudioClip();
    ac.setFilePath(wav().getAbsolutePath());
    at.addAudioClip(ac);
    project.addTrack(at);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.clear();
    int sounds = 0;
    for (var clip : song.getClips()) {
      if (clip instanceof ClipModel ic && ic.getSound() != null) {
        engine.sounds.add((org.deluge.firmware2.GlobalEffectable) ic.getSound());
        sounds++;
      }
    }
    assertTrue(sounds > 0, "audio track produced no engine sound");

    PlaybackHandler handler = new PlaybackHandler();
    handler.setProject(song);
    handler.start();

    int n = 128;

    // Phase 3 gating: stopped transport → audio clip is silent.
    engine.setTransportPlaying(false);
    long stoppedPeak = 0;
    for (int b = 0; b < 8; b++) {
      engine.renderBlock(n);
      for (int i = 0; i < n; i++)
        stoppedPeak = Math.max(stoppedPeak, Math.abs((long) engine.masterBuffer[i].l));
    }
    assertEquals(0, stoppedPeak, "audio clip rendered while transport stopped");

    // Playing transport → audible.
    engine.setTransportPlaying(true);
    double sumSq = 0;
    long count = 0;
    long peak = 0;
    // Render ~1s; the kick is short so it loops — should stay non-silent throughout.
    for (int done = 0; done < 44100; done += n) {
      handler.advanceTicks(1);
      engine.renderBlock(n);
      for (int i = 0; i < n; i++) {
        StereoSample s = engine.masterBuffer[i];
        double l = s.l / 2147483648.0;
        sumSq += l * l;
        count++;
        peak = Math.max(peak, Math.abs((long) s.l));
      }
    }
    double rms = Math.sqrt(sumSq / count);
    System.out.printf("[AudioOutput] RMS=%.5f peak=%d%n", rms, peak);
    assertTrue(rms > 1e-4, "audio track render was silent (RMS=" + rms + ")");
    assertTrue(peak < Integer.MAX_VALUE, "audio track render hard-clipped every sample");
  }
}
