package org.chuck.deluge.engine;

import javax.sound.sampled.*;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.playback.PlaybackHandler;

/** Pure Java audio driver using javax.sound.sampled. */
public class JavaAudioDriver implements Runnable {
  private final FirmwareAudioEngine engine;
  private final PlaybackHandler playbackHandler;
  private SourceDataLine line;
  private boolean running = true;
  private static final int BLOCK_SIZE = 128;
  private final byte[] byteBuffer = new byte[BLOCK_SIZE * 4];

  private double ticksPerSample = 0.005; // 120BPM default
  private double accumulatedTicks = 0;

  public JavaAudioDriver(FirmwareAudioEngine engine, PlaybackHandler playbackHandler) {
    this.engine = engine;
    this.playbackHandler = playbackHandler;
  }

  public void updateBpm(float bpm) {
    double ticksPerSec = (bpm / 60.0) * 96.0;
    this.ticksPerSample = ticksPerSec / 44100.0;
  }

  public void stop() {
    running = false;
  }

  @Override
  public void run() {
    try {
      System.out.println("[JavaAudioDriver] Searching for audio line...");
      AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
      line = (SourceDataLine) AudioSystem.getLine(info);
      line.open(format, 65536);
      line.start();

      System.out.println("[JavaAudioDriver] Opened SUCCESS: " + line.getLineInfo());

      // ── HARDWARE TEST BEEP (2 SECONDS, VERY LOUD) ──
      System.out.println("[JavaAudioDriver] >>> STARTING 2s BEEP TEST <<<");
      for (int b = 0; b < 700; b++) {
        for (int i = 0; i < BLOCK_SIZE; i++) {
          double sample = Math.sin(2.0 * Math.PI * 440.0 * (b * BLOCK_SIZE + i) / 44100.0) * 0.7;
          short val = (short) (sample * 32767.0);
          byteBuffer[i * 4] = (byte) (val & 0xFF);
          byteBuffer[i * 4 + 1] = (byte) ((val >> 8) & 0xFF);
          byteBuffer[i * 4 + 2] = (byte) (val & 0xFF);
          byteBuffer[i * 4 + 3] = (byte) ((val >> 8) & 0xFF);
        }
        line.write(byteBuffer, 0, byteBuffer.length);
      }
      System.out.println("[JavaAudioDriver] >>> BEEP TEST COMPLETE <<<");

      int peak = 0;
      int blockCounter = 0;

      while (running) {
        if (playbackHandler != null) {
          accumulatedTicks += ticksPerSample * BLOCK_SIZE;
          int toAdvance = (int) accumulatedTicks;
          if (toAdvance > 0) {
            playbackHandler.advanceTicks(toAdvance);
            accumulatedTicks -= toAdvance;
          }
        }

        long start = System.nanoTime();
        engine.renderBlock(BLOCK_SIZE);
        long duration = System.nanoTime() - start;
        if (duration > 2900000) {
          System.out.println(
              "[WARN] Audio block render took too long: " + (duration / 1000000.0) + " ms");
        }

        for (int i = 0; i < BLOCK_SIZE; i++) {
          StereoSample s = engine.masterBuffer[i];
          int absL = Math.abs(s.l);
          if (absL > peak) peak = absL;

          short left = (short) (s.l >> 16);
          short right = (short) (s.r >> 16);

          byteBuffer[i * 4] = (byte) (left & 0xFF);
          byteBuffer[i * 4 + 1] = (byte) ((left >> 8) & 0xFF);
          byteBuffer[i * 4 + 2] = (byte) (right & 0xFF);
          byteBuffer[i * 4 + 3] = (byte) ((right >> 8) & 0xFF);
        }

        if (blockCounter % 200 == 0 && peak > 1000) {
          System.out.println("[JavaAudioDriver] LIVE SIGNAL PEAK: " + peak);
        }
        if (blockCounter % 200 == 0) peak = 0;
        blockCounter++;

        line.write(byteBuffer, 0, byteBuffer.length);
      }

      line.drain();
      line.close();
    } catch (Exception e) {
      System.err.println("[JavaAudioDriver] Error: " + e.getMessage());
      e.printStackTrace();
    }
  }
}
