package org.deluge.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import javax.sound.sampled.*;
import org.deluge.firmware2.StereoSample;
import org.deluge.playback.PlaybackHandler;

/** Pure Java audio driver using javax.sound.sampled. */
public class JavaAudioDriver implements Runnable {
  public static volatile boolean isResamplingActive = false;

  /**
   * Capture-only: render + resample without opening/writing the soundcard. Tests set this true so
   * the suite is silent. Defaults from the {@code deluge.audio.silent} system property.
   */
  public static volatile boolean silentMode = Boolean.getBoolean("deluge.audio.silent");

  private static final ByteArrayOutputStream recordedBytes = new ByteArrayOutputStream();

  public volatile long blockCounter = 0;

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
    int sampleRate = 44100;
    short bitsPerSample = 16;
    short numChannels = 2;
    int dataSize = pcmData.length;
    int byteRate = sampleRate * numChannels * bitsPerSample / 8;

    try (java.io.FileOutputStream fos = new java.io.FileOutputStream(targetFile)) {
      // RIFF header
      fos.write("RIFF".getBytes());
      writeIntLE(fos, 36 + dataSize);
      fos.write("WAVE".getBytes());
      // fmt chunk
      fos.write("fmt ".getBytes());
      writeIntLE(fos, 16); // chunk size (PCM)
      writeShortLE(fos, (short) 1); // PCM format
      writeShortLE(fos, numChannels);
      writeIntLE(fos, sampleRate);
      writeIntLE(fos, byteRate);
      writeShortLE(fos, (short) (numChannels * bitsPerSample / 8)); // block align
      writeShortLE(fos, bitsPerSample);
      // data chunk
      fos.write("data".getBytes());
      writeIntLE(fos, dataSize);
      fos.write(pcmData);
    }
    System.out.println("[Resampler] Saved WAV loop successfully: " + targetFile.getAbsolutePath());
  }

  private static void writeIntLE(java.io.OutputStream os, int v) throws IOException {
    os.write(v & 0xFF);
    os.write((v >> 8) & 0xFF);
    os.write((v >> 16) & 0xFF);
    os.write((v >> 24) & 0xFF);
  }

  private static void writeShortLE(java.io.OutputStream os, short v) throws IOException {
    os.write(v & 0xFF);
    os.write((v >> 8) & 0xFF);
  }

  private final FirmwareAudioEngine engine;
  private final PlaybackHandler playbackHandler;
  private SourceDataLine line;
  private volatile boolean running = true;
  private static final int BLOCK_SIZE = 128;
  private final byte[] byteBuffer = new byte[BLOCK_SIZE * 4];

  public static volatile int monitorGainMul =
      org.deluge.project.PreferencesManager.getMonitorGainBoost();
  public static boolean debugPeak = false;

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
      this.blockCounter = 0;
      long startNano = System.nanoTime();

      int[] liveInputBlock = new int[BLOCK_SIZE * 2];
      while (running) {
        if (playbackHandler != null) {
          if (playbackHandler.getSyncMode() == 0) { // Only advance if INTERNAL sync mode
            accumulatedTicks += ticksPerSample * BLOCK_SIZE;
            int toAdvance = (int) accumulatedTicks;
            if (toAdvance > 0) {
              playbackHandler.advanceTicks(toAdvance);
              accumulatedTicks -= toAdvance;
            }
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

        // Phase 3: tell the engine the transport state + tick so audio tracks stream only while
        // playing (3a) and only within their arrangement range (3b part 2).
        engine.setTransportPlaying(playbackHandler != null && playbackHandler.isPlaying());
        if (playbackHandler != null) {
          engine.setTransportTick(playbackHandler.lastSwungTickActioned);
        }

        long start = System.nanoTime();
        engine.renderBlock(BLOCK_SIZE);
        long duration = System.nanoTime() - start;
        // Boot benchmark (deluge.bootbench): JVM-start-relative time to first audio block and to
        // steady state (block 200) — the JIT-warmup window the AOT cache is meant to shorten.
        if (Boolean.getBoolean("deluge.bootbench") && (blockCounter == 0 || blockCounter == 200)) {
          long up = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
          String line =
              System.getProperty("deluge.benchtag", "run")
                  + " block="
                  + blockCounter
                  + " uptime="
                  + up
                  + "ms\n";
          System.out.print("[BOOTBENCH] " + line);
          try {
            java.nio.file.Files.writeString(
                new java.io.File(System.getProperty("user.home"), ".deluge/bootbench.txt").toPath(),
                line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
          } catch (Exception ignored) {
          }
        }
        // C: AudioEngine::setDireness — feed the measured block render time to the adaptive
        // resampling-quality governor (sample interpolation drops to linear under sustained load).
        org.deluge.engine.FirmwareAudioEngine.updateDireness(
            duration, BLOCK_SIZE, blockCounter * (long) BLOCK_SIZE);
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

        if (debugPeak && blockCounter % 200 == 0 && peak > 1000) {
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
          // Precision real-time pacing loop using System.nanoTime() to prevent timing drift
          long targetNano = startNano + (blockCounter * 128L * 1000000000L) / 44100L;
          long now = System.nanoTime();
          long delayNs = targetNano - now;
          if (delayNs > 100000L) { // Only sleep if delay is > 100 microseconds
            try {
              Thread.sleep(delayNs / 1000000L, (int) (delayNs % 1000000L));
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
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
   * High-fidelity, ultra-fast analog soft-clipping saturation function. Maintains perfect linear
   * transparency up to 0.7 (-3dB), and curves smoothly towards 1.0 using a rational approximation
   * to eliminate heavy Math.tanh CPU overhead in the real-time audio thread.
   */
  public static float softClip(float x) {
    if (x > 0.7f) {
      float y = x - 0.7f;
      return 0.7f + y / (1.0f + y / 0.3f);
    }
    if (x < -0.7f) {
      float y = x + 0.7f;
      return -0.7f + y / (1.0f - y / 0.3f);
    }
    return x;
  }
}
