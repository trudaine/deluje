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

  private double renderRms(AudioTrackModel at) throws Exception {
    ProjectModel project = new ProjectModel();
    project.addTrack(at);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.clear();
    for (var clip : song.getClips()) {
      if (clip instanceof ClipModel ic && ic.getSound() != null) {
        var sound = (org.deluge.firmware2.GlobalEffectable) ic.getSound();
        if (sound instanceof org.deluge.firmware2.AudioOutput ao) {
          ao.setAmplitude(
              1 << 20); // Turn down amplitude to prevent clipping under 128x master boost
        }
        engine.sounds.add(sound);
      }
    }
    PlaybackHandler handler = new PlaybackHandler();
    handler.setProject(song);
    handler.start();
    engine.setTransportPlaying(true);
    int n = 128;
    double sumSq = 0;
    long count = 0;
    for (int done = 0; done < 44100; done += n) {
      handler.advanceTicks(1);
      engine.renderBlock(n);
      for (int i = 0; i < n; i++) {
        double l = engine.masterBuffer[i].l / 2147483648.0;
        sumSq += l * l;
        count++;
      }
    }
    return Math.sqrt(sumSq / count);
  }

  @Test
  void arrangementPlacementGatesPlayback() throws Exception {
    // Place the audio clip at bars 4..8 (startTick 384, duration 384 @96 PPQN). It must be silent
    // before tick 384, audible inside the window, and silent again after tick 768.
    ProjectModel project = new ProjectModel();
    AudioTrackModel at = new AudioTrackModel("Arr");
    AudioTrackModel.AudioClip ac = new AudioTrackModel.AudioClip();
    ac.setFilePath(wav().getAbsolutePath());
    at.addAudioClip(ac);
    project.addTrack(at);
    project.addArrangerClip(new org.deluge.model.ArrangerClip(0, null, ac, 384, 384)); // bars 4..8

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.clear();
    for (var clip : song.getClips()) {
      if (clip instanceof ClipModel ic && ic.getSound() != null) {
        engine.sounds.add((org.deluge.firmware2.GlobalEffectable) ic.getSound());
      }
    }
    engine.setTransportPlaying(true);
    int n = 128;
    long peakBefore = 0, peakInside = 0, peakAfterLate = 0;
    for (long tick = 0; tick < 1600; tick++) {
      engine.setTransportTick(tick);
      engine.renderBlock(n);
      long blockPeak = 0;
      for (int i = 0; i < n; i++) {
        blockPeak = Math.max(blockPeak, Math.abs((long) engine.masterBuffer[i].l));
      }
      if (tick < 384) peakBefore = Math.max(peakBefore, blockPeak);
      else if (tick < 768) peakInside = Math.max(peakInside, blockPeak);
      else if (tick >= 1300) peakAfterLate = Math.max(peakAfterLate, blockPeak); // tail decayed
    }
    System.out.printf(
        "[AudioOutput] arrangement before=%d inside=%d afterLate=%d%n",
        peakBefore, peakInside, peakAfterLate);
    // Silent before the placement; audible inside; well after the end only the FX tail remains
    // (the clip itself is gated off), so the late window is a small fraction of the inside level.
    assertEquals(0, peakBefore, "audio clip played before its arrangement start");
    assertTrue(peakInside > 0, "audio clip silent inside its arrangement range");
    assertTrue(
        peakAfterLate < peakInside / 8,
        "audio clip still playing after its arrangement end (afterLate=" + peakAfterLate + ")");
  }

  @Test
  void multipleArrangementInstancesEachPlay() throws Exception {
    // Two placements of the same audio track: bars 4..8 (384..768) and bars 12..16 (1152..1536).
    // Audible in BOTH windows; silent in the gap between them (936..1148, clear of FX tails).
    ProjectModel project = new ProjectModel();
    AudioTrackModel at = new AudioTrackModel("MultiArr");
    AudioTrackModel.AudioClip ac = new AudioTrackModel.AudioClip();
    ac.setFilePath(wav().getAbsolutePath());
    at.addAudioClip(ac);
    project.addTrack(at);
    project.addArrangerClip(new org.deluge.model.ArrangerClip(0, null, ac, 384, 384));
    project.addArrangerClip(new org.deluge.model.ArrangerClip(0, null, ac, 1152, 384));

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.clear();
    for (var clip : song.getClips()) {
      if (clip instanceof ClipModel ic && ic.getSound() != null) {
        engine.sounds.add((org.deluge.firmware2.GlobalEffectable) ic.getSound());
      }
    }
    engine.setTransportPlaying(true);
    int n = 128;
    long peakWin1 = 0, peakGap = 0, peakWin2 = 0;
    for (long tick = 0; tick < 1600; tick++) {
      engine.setTransportTick(tick);
      engine.renderBlock(n);
      long blockPeak = 0;
      for (int i = 0; i < n; i++) {
        blockPeak = Math.max(blockPeak, Math.abs((long) engine.masterBuffer[i].l));
      }
      if (tick >= 384 && tick < 768) peakWin1 = Math.max(peakWin1, blockPeak);
      else if (tick >= 936 && tick < 1148) peakGap = Math.max(peakGap, blockPeak);
      else if (tick >= 1152 && tick < 1536) peakWin2 = Math.max(peakWin2, blockPeak);
    }
    System.out.printf("[AudioOutput] multi win1=%d gap=%d win2=%d%n", peakWin1, peakGap, peakWin2);
    assertTrue(peakWin1 > 0, "first arrangement instance silent");
    assertTrue(peakWin2 > 0, "second arrangement instance silent");
    assertTrue(peakGap < peakWin1 / 8, "audio played in the gap between instances");
  }

  @Test
  void loopsPastSampleEnd() throws Exception {
    // The kick is ~0.4s; render 2s and check the FINAL window is still non-silent — proves the clip
    // loops (a one-shot would be silent after the sample ended).
    ProjectModel project = new ProjectModel();
    AudioTrackModel at = new AudioTrackModel("Loop");
    AudioTrackModel.AudioClip ac = new AudioTrackModel.AudioClip();
    ac.setFilePath(wav().getAbsolutePath());
    at.addAudioClip(ac);
    project.addTrack(at);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.clear();
    for (var clip : song.getClips()) {
      if (clip instanceof ClipModel ic && ic.getSound() != null) {
        engine.sounds.add((org.deluge.firmware2.GlobalEffectable) ic.getSound());
      }
    }
    PlaybackHandler handler = new PlaybackHandler();
    handler.setProject(song);
    handler.start();
    engine.setTransportPlaying(true);
    int n = 128;
    double tailSumSq = 0;
    long tailCount = 0;
    int total = 44100 * 2; // 2 seconds
    for (int done = 0; done < total; done += n) {
      handler.advanceTicks(1);
      engine.renderBlock(n);
      if (done > total - 22050) { // final 0.5s window
        for (int i = 0; i < n; i++) {
          double l = engine.masterBuffer[i].l / 2147483648.0;
          tailSumSq += l * l;
          tailCount++;
        }
      }
    }
    double tailRms = Math.sqrt(tailSumSq / tailCount);
    System.out.printf("[AudioOutput] tail RMS (loop check)=%.5f%n", tailRms);
    assertTrue(tailRms > 1e-4, "clip stopped instead of looping (tail was silent)");
  }

  @Test
  void pitchedPlaybackIsAudible() throws Exception {
    // Coupled mode: faster rate → higher pitch (resampled). Must still produce sound.
    AudioTrackModel at = new AudioTrackModel("Pitched");
    AudioTrackModel.AudioClip ac = new AudioTrackModel.AudioClip();
    ac.setFilePath(wav().getAbsolutePath());
    ac.setPitchSpeedIndependent(false);
    at.addAudioClip(ac);
    at.setPlayRate(1.5f);
    double rms = renderRms(at);
    System.out.printf("[AudioOutput] pitched(1.5x) RMS=%.5f%n", rms);
    assertTrue(rms > 1e-4, "pitched (resampled) audio render was silent");
  }

  @Test
  void timeStretchPlaybackIsAudible() throws Exception {
    // Independent mode: slower speed, pitch unchanged (time-stretch). Must still produce sound.
    AudioTrackModel at = new AudioTrackModel("Stretched");
    AudioTrackModel.AudioClip ac = new AudioTrackModel.AudioClip();
    ac.setFilePath(wav().getAbsolutePath());
    ac.setPitchSpeedIndependent(true);
    at.addAudioClip(ac);
    at.setPlayRate(0.75f);
    double rms = renderRms(at);
    System.out.printf("[AudioOutput] timestretch(0.75x) RMS=%.5f%n", rms);
    assertTrue(rms > 1e-4, "time-stretched audio render was silent");
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
        var sound = (org.deluge.firmware2.GlobalEffectable) ic.getSound();
        if (sound instanceof org.deluge.firmware2.AudioOutput ao) {
          ao.setAmplitude(
              1 << 20); // Turn down amplitude to prevent clipping under 128x master boost
        }
        engine.sounds.add(sound);
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
