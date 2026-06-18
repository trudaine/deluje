package org.deluge.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import javax.sound.sampled.*;
import org.deluge.firmware.engine.FirmwareAudioEngine;
import org.deluge.firmware.playback.PlaybackHandler;
import org.deluge.firmware2.StereoSample;

/** Pure Java audio driver using javax.sound.sampled. */
public class JavaAudioDriver implements Runnable {
  public static volatile boolean isResamplingActive = false;

  /**
   * Capture-only: render + resample without opening/writing the soundcard. Tests set this true so
   * the suite is silent. Defaults from the {@code deluge.audio.silent} system property.
   */
  public static volatile boolean silentMode = Boolean.getBoolean("deluge.audio.silent");

  private static final ByteArrayOutputStream recordedBytes = new ByteArrayOutputStream();

  public static void startResampling() {
    synchronized (recordedBytes) {
      recordedBytes.reset();
    }
    isResamplingActive = true;
    System.out.println("[Resampler] Recording started...");
  }

  public static byte[] stopResampling() {
    isResamplingActive = false;
    synchronized (recordedBytes) {
      byte[] data = recordedBytes.toByteArray();
      recordedBytes.reset();
      System.out.println("[Resampler] Recording stopped. Captured " + data.length + " bytes.");
      return data;
    }
  }

  public static void saveWavFile(byte[] pcmData, File targetFile) throws IOException {
    AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
    ByteArrayInputStream bais = new ByteArrayInputStream(pcmData);
    AudioInputStream ais = new AudioInputStream(bais, format, pcmData.length / 4);
    try {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, targetFile);
      System.out.println(
          "[Resampler] Saved WAV loop successfully: " + targetFile.getAbsolutePath());
    } finally {
      ais.close();
    }
  }

  private final FirmwareAudioEngine engine;
  private final PlaybackHandler playbackHandler;
  private SourceDataLine line;
  private boolean running = true;
  private static final int BLOCK_SIZE = 128;
  private final byte[] byteBuffer = new byte[BLOCK_SIZE * 4];

  public static volatile int monitorGainMul =
      org.deluge.project.PreferencesManager.getMonitorGainBoost();

  private double ticksPerSample = 0.005; // 120BPM default
  private double accumulatedTicks = 0;

  // Metronome beat tracking: 96 ticks per quarter note. -1 means "not yet clicked since stopped".
  private static final int TICKS_PER_QUARTER = 96;
  private static final int METRONOME_PHASE_DOWNBEAT = 128411753; // C: playback_handler.cpp:1024
  private static final int METRONOME_PHASE_BEAT = 50960238;
  private long lastMetronomeBeat = -1;

  private static final float[] visBufferL = new float[2048];
  private static final float[] visBufferR = new float[2048];
  private static int visWriteIdx = 0;
  private static final Object VIS_LOCK = new Object();
  private final float[] tempVisL = new float[BLOCK_SIZE];
  private final float[] tempVisR = new float[BLOCK_SIZE];

  public static float[] getLiveVisBufferL() {
    float[] copy = new float[2048];
    synchronized (VIS_LOCK) {
      int len = 2048;
      System.arraycopy(visBufferL, visWriteIdx, copy, 0, len - visWriteIdx);
      System.arraycopy(visBufferL, 0, copy, len - visWriteIdx, visWriteIdx);
    }
    return copy;
  }

  public static float[] getLiveVisBufferR() {
    float[] copy = new float[2048];
    synchronized (VIS_LOCK) {
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
      // Capture-only mode: render + resample still run, but never open/write the soundcard. Used by
      // tests (SwingDelugeAppE2ETest) so the suite is silent instead of blasting the speakers.
      if (!silentMode) {
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
      } else {
        System.out.println("[JavaAudioDriver] silentMode — capture only, no soundcard output.");
      }

      int peak = 0;
      int blockCounter = 0;

      int[] liveInputBlock = new int[BLOCK_SIZE * 2];
      while (running) {
        if (playbackHandler != null) {
          accumulatedTicks += ticksPerSample * BLOCK_SIZE;
          int toAdvance = (int) accumulatedTicks;
          if (toAdvance > 0) {
            playbackHandler.advanceTicks(toAdvance);
            accumulatedTicks -= toAdvance;
          }

          // Metronome: click on each quarter-note boundary while playing (high pitch on the bar
          // downbeat, lower on other beats — faithful to playback_handler.cpp:1019-1025).
          if (engine.metronomeEnabled && playbackHandler.isPlaying()) {
            long beat = (long) playbackHandler.lastSwungTickActioned / TICKS_PER_QUARTER;
            if (beat != lastMetronomeBeat) {
              engine.triggerMetronome(
                  (beat % 4 == 0) ? METRONOME_PHASE_DOWNBEAT : METRONOME_PHASE_BEAT);
              lastMetronomeBeat = beat;
            }
          } else if (!playbackHandler.isPlaying()) {
            lastMetronomeBeat = -1; // re-arm so the downbeat clicks on next start
          }
        }

        // Publish the microphone block to the engine's live-input bus (INPUT_* osc sources).
        // Available whenever the capture line is armed (threshold sampler / live monitor).
        AudioInputCaptureLine capture = AudioInputCaptureLine.getInstance();
        if (capture.isArmed() && capture.fillMonitorBlock(liveInputBlock, BLOCK_SIZE)) {
          org.deluge.firmware2.LiveInput.micPluggedIn = true;
          org.deluge.firmware2.LiveInput.currentBlock = liveInputBlock;
        } else {
          org.deluge.firmware2.LiveInput.currentBlock = null;
          org.deluge.firmware2.LiveInput.micPluggedIn = false;
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

          // Convert internal Q31 to float in [-1.0, 1.0] range
          float xL = (float) s.l / 2147483648.0f;
          float xR = (float) s.r / 2147483648.0f;

          // Apply post-engine linear volume boost in floating-point domain
          float boostedL = xL * monitorGainMul;
          float boostedR = xR * monitorGainMul;

          // High-fidelity analog-style soft-clipper (transistor/tape saturation model)
          // 100% linear and transparent up to -3dB (0.7), smooth asymptotic compression above that.
          float saturatedL = softClip(boostedL);
          float saturatedR = softClip(boostedR);

          // Convert back to 16-bit signed shorts [-32768, 32767]
          short left = (short) Math.max(-32768, Math.min(32767, saturatedL * 32767.0f));
          short right = (short) Math.max(-32768, Math.min(32767, saturatedR * 32767.0f));

          byteBuffer[i * 4] = (byte) (left & 0xFF);
          byteBuffer[i * 4 + 1] = (byte) ((left >> 8) & 0xFF);
          byteBuffer[i * 4 + 2] = (byte) (right & 0xFF);
          byteBuffer[i * 4 + 3] = (byte) ((right >> 8) & 0xFF);

          // Write to visualizer buffer (displays the loud, mastered output!)
          tempVisL[i] = saturatedL;
          tempVisR[i] = saturatedR;
        }

        synchronized (VIS_LOCK) {
          for (int i = 0; i < BLOCK_SIZE; i++) {
            visBufferL[visWriteIdx] = tempVisL[i];
            visBufferR[visWriteIdx] = tempVisR[i];
            visWriteIdx = (visWriteIdx + 1) % 2048;
          }
        }

        if (blockCounter % 200 == 0 && peak > 1000) {
          System.out.println("[JavaAudioDriver] LIVE SIGNAL PEAK: " + peak);
        }
        if (blockCounter % 200 == 0) peak = 0;
        blockCounter++;

        if (isResamplingActive) {
          synchronized (recordedBytes) {
            recordedBytes.write(byteBuffer, 0, byteBuffer.length);
          }
        }
        if (silentMode) {
          // No soundcard — pace roughly real-time so resample length stays sane and the CPU idles.
          try {
            Thread.sleep(2);
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
        } else {
          line.write(byteBuffer, 0, byteBuffer.length);
        }
      }

      if (line != null) {
        line.drain();
        line.close();
      }
    } catch (Exception e) {
      System.err.println("[JavaAudioDriver] Error: " + e.getMessage());
      e.printStackTrace();
    }
  }

  /**
   * High-fidelity, smooth analog soft-clipping saturation function. Maintains perfect linear
   * transparency up to 0.7 (-3dB), and curves smoothly towards 1.0.
   */
  private static float softClip(float x) {
    if (x > 0.7f) {
      return 0.7f + 0.3f * (float) Math.tanh((x - 0.7f) / 0.3f);
    }
    if (x < -0.7f) {
      return -0.7f + 0.3f * (float) Math.tanh((x + 0.7f) / 0.3f);
    }
    return x;
  }
}
