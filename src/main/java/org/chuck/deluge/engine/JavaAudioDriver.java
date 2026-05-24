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

  private static final float[] visBufferL = new float[2048];
  private static final float[] visBufferR = new float[2048];
  private static int visWriteIdx = 0;

  public static float[] getLiveVisBufferL() {
    float[] copy = new float[2048];
    synchronized (visBufferL) {
      int len = 2048;
      System.arraycopy(visBufferL, visWriteIdx, copy, 0, len - visWriteIdx);
      System.arraycopy(visBufferL, 0, copy, len - visWriteIdx, visWriteIdx);
    }
    return copy;
  }

  public static float[] getLiveVisBufferR() {
    float[] copy = new float[2048];
    synchronized (visBufferR) {
      int len = 2048;
      System.arraycopy(visBufferR, visWriteIdx, copy, 0, len - visWriteIdx);
      System.arraycopy(visBufferR, 0, copy, len - visWriteIdx, visWriteIdx);
    }
    return copy;
  }

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
      Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
      System.out.println("[JavaAudioDriver] Searching for audio line...");
      AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
      DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
      line = (SourceDataLine) AudioSystem.getLine(info);
      line.open(format, 65536);
      line.start();

      // Prime the line buffer with a 16-block silence cushion to protect against initial JIT
      // compilation latency spikes!
      byte[] priming = new byte[BLOCK_SIZE * 16 * 4];
      line.write(priming, 0, priming.length);

      System.out.println("[JavaAudioDriver] Opened SUCCESS: " + line.getLineInfo());

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

        synchronized (visBufferL) {
          for (int i = 0; i < BLOCK_SIZE; i++) {
            StereoSample s = engine.masterBuffer[i];
            int absL = Math.abs(s.l);
            if (absL > peak) peak = absL;

            int leftVal = s.l >> 16;
            int rightVal = s.r >> 16;
            short left = (short) Math.max(-32768, Math.min(32767, leftVal));
            short right = (short) Math.max(-32768, Math.min(32767, rightVal));

            byteBuffer[i * 4] = (byte) (left & 0xFF);
            byteBuffer[i * 4 + 1] = (byte) ((left >> 8) & 0xFF);
            byteBuffer[i * 4 + 2] = (byte) (right & 0xFF);
            byteBuffer[i * 4 + 3] = (byte) ((right >> 8) & 0xFF);

            // Write to visualizer buffer: s.l is 32-bit fixed point: divide by 2^31 to float
            float leftFloat = (float) s.l / 2147483648.0f;
            float rightFloat = (float) s.r / 2147483648.0f;
            visBufferL[visWriteIdx] = leftFloat;
            visBufferR[visWriteIdx] = rightFloat;
            visWriteIdx = (visWriteIdx + 1) % 2048;
          }
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
