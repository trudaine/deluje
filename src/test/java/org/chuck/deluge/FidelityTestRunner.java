package org.chuck.deluge;

import java.io.File;
import org.chuck.deluge.engine.JavaAudioDriver;
import org.chuck.deluge.firmware2.StereoSample;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.engine.FirmwareFactory;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.playback.PlaybackHandler;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.xml.DelugeXmlParser;

/** Renders Deluge XML song files to WAV files offline for comparison testing. */
public class FidelityTestRunner {

  public static void renderSongToWav(File xmlFile, File targetWavFile, double durationSeconds)
      throws Exception {
    ProjectModel project = DelugeXmlParser.parseSong(xmlFile);
    Song song = FirmwareFactory.createSong(project);

    FirmwareAudioEngine engine = new FirmwareAudioEngine();

    // Add all instruments to engine sounds
    engine.sounds.clear();
    for (var clip : song.clips) {
      if (clip instanceof org.chuck.deluge.firmware.model.InstrumentClip ic && ic.sound != null) {
        engine.sounds.add(ic.sound);
      }
    }

    PlaybackHandler playbackHandler = new PlaybackHandler();
    playbackHandler.setSong(song);
    playbackHandler.start();

    float bpm = project.getBpm();
    if (bpm <= 0) bpm = 120.0f;

    double ticksPerSec = (bpm / 60.0) * 96.0;
    double ticksPerSample = ticksPerSec / 44100.0;
    double accumulatedTicks = 0;

    // Set arpeggiator config
    for (org.chuck.deluge.firmware2.GlobalEffectable sound : engine.sounds) {
      if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound fsArp) {
        int div = fsArp.arpDivision > 0 ? fsArp.arpDivision : 16;
        double rateMul = (fsArp.arpRateMultiplier > 0.01f) ? fsArp.arpRateMultiplier : 1.0;
        double stepSamples = (4.0 / div) * (60.0 / bpm) * 44100.0 / rateMul;
        fsArp.arpPhaseIncrement = (stepSamples > 1) ? (int) (4294967296.0 / stepSamples) : 0;
        fsArp.currentBpm = bpm;
      }
    }

    int sampleRate = 44100;
    int totalSamples = (int) (durationSeconds * sampleRate);
    int numBlocks = totalSamples / 128;

    byte[] pcmData = new byte[numBlocks * 128 * 4]; // 16-bit stereo = 4 bytes per sample

    for (int b = 0; b < numBlocks; b++) {
      // Advance clock sequencer
      accumulatedTicks += ticksPerSample * 128;
      int toAdvance = (int) accumulatedTicks;
      if (toAdvance > 0) {
        playbackHandler.advanceTicks(toAdvance);
        accumulatedTicks -= toAdvance;
      }

      // Render audio
      engine.renderBlock(128);

      for (int i = 0; i < 128; i++) {
        StereoSample s = engine.masterBuffer[i];

        // Scale and saturate to 16-bit
        short left = (short) Math.max(-32768, Math.min(32767, s.l >> 16));
        short right = (short) Math.max(-32768, Math.min(32767, s.r >> 16));

        int idx = (b * 128 + i) * 4;
        pcmData[idx] = (byte) (left & 0xFF);
        pcmData[idx + 1] = (byte) ((left >> 8) & 0xFF);
        pcmData[idx + 2] = (byte) (right & 0xFF);
        pcmData[idx + 3] = (byte) ((right >> 8) & 0xFF);
      }
    }

    JavaAudioDriver.saveWavFile(pcmData, targetWavFile);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 2) {
      System.out.println(
          "Usage: java org.chuck.deluge.FidelityTestRunner <song.xml> <output.wav> [durationSeconds]");
      return;
    }
    double duration = 5.0;
    if (args.length >= 3) {
      duration = Double.parseDouble(args[2]);
    }
    renderSongToWav(new File(args[0]), new File(args[1]), duration);
  }
}
