package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware2.StereoSample;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * The hardware-fidelity test songs (SONGS/Test*Fidelity.xml) are written in the EXACT format the
 * community firmware c1.2.0 writes (attribute style, presetName ↔ instrumentPresetName linkage,
 * noteDataWithLift) so the physical Deluge can load them — the first generation used a homemade
 * format the firmware rejected (claimOutput finds no instrument by name → FILE_CORRUPTED). This
 * guards the OTHER side: our parser + engine must also load them and produce sound.
 */
public class FidelitySongSmokeTest {

  private static File songFile(String name) {
    File f = new File("src/main/resources/SONGS/" + name);
    assertTrue(f.exists(), "missing " + f);
    return f;
  }

  private double renderRms(String songName, double seconds) throws Exception {
    ProjectModel project = DelugeXmlParser.parseSong(songFile(songName));
    Song song = FirmwareFactory.createSong(project);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.clear();
    int clips = 0;
    for (var clip : song.clips) {
      if (clip instanceof InstrumentClip ic && ic.sound != null) {
        engine.sounds.add(ic.sound);
        clips++;
      }
    }
    assertTrue(clips > 0, songName + ": no instrument clips parsed");

    var playbackHandler = new org.chuck.deluge.firmware.playback.PlaybackHandler();
    playbackHandler.setSong(song);
    playbackHandler.start();

    int n = 128;
    // 120 BPM, 96 PPQN → ticks advance per rendered sample (same clocking as FidelityTestRunner).
    double ticksPerSample = (120.0 / 60.0) * 96.0 / 44100.0;
    double acc = 0;
    double sumSq = 0;
    long count = 0;
    int totalSamples = (int) (seconds * 44100);
    for (int done = 0; done < totalSamples; done += n) {
      acc += ticksPerSample * n;
      int whole = (int) acc;
      if (whole > 0) {
        playbackHandler.advanceTicks(whole);
        acc -= whole;
      }
      engine.renderBlock(n);
      for (int i = 0; i < n; i++) {
        StereoSample s = engine.masterBuffer[i];
        double l = s.l / 2147483648.0;
        sumSq += l * l;
        count++;
      }
    }
    return Math.sqrt(sumSq / count);
  }

  @Test
  void synthFidelitySongParsesAndSounds() throws Exception {
    double rms = renderRms("TestSynthFidelity.xml", 1.0);
    assertTrue(rms > 1e-4, "TestSynthFidelity should produce sound; rms=" + rms);
  }

  @Test
  void unisonFidelitySongParsesAndSounds() throws Exception {
    double rms = renderRms("TestUnisonFidelity.xml", 1.0);
    assertTrue(rms > 1e-4, "TestUnisonFidelity should produce sound; rms=" + rms);
  }

  @Test
  void kitFidelitySongParsesAndSounds() throws Exception {
    double rms = renderRms("TestKitFidelity.xml", 1.0);
    assertTrue(rms > 1e-4, "TestKitFidelity should produce sound; rms=" + rms);
  }

  @Test
  void calibrationSongsParseAndSound() throws Exception {
    // Batch 2 (2026-06-12): isolated-subsystem calibration songs for hardware comparison.
    String[] songs = {
      "TestEnvFidelity.xml",
      "TestFilterFidelity.xml",
      "TestLfoFidelity.xml",
      "TestFmFidelity.xml",
      "TestTuningFidelity.xml",
      "TestNoiseFidelity.xml",
      "TestDelayFidelity.xml",
    };
    for (String song : songs) {
      double rms = renderRms(song, 2.0); // 2s: the env song has a slow attack
      assertTrue(rms > 1e-4, song + " should produce sound; rms=" + rms);
    }
  }
}
