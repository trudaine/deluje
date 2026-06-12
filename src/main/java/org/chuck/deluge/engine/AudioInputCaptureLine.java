package org.chuck.deluge.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.*;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.ui.SwingDelugeApp;

/**
 * High-performance audio input capture engine cabled to a decibel peak amplitude threshold trigger.
 * Spawns responsive Project Loom virtual threads to stream TargetDataLine microphone inputs,
 * auto-starts main workstation play clocks, and records loop PCM frames straight to track slots.
 */
public class AudioInputCaptureLine {
  private static volatile AudioInputCaptureLine instance = null;

  public static AudioInputCaptureLine getInstance() {
    if (instance == null) {
      synchronized (AudioInputCaptureLine.class) {
        if (instance == null) {
          instance = new AudioInputCaptureLine();
        }
      }
    }
    return instance;
  }

  private TargetDataLine inputLine = null;
  private final AtomicBoolean isRecording = new AtomicBoolean(false);
  private final AtomicBoolean isArmed = new AtomicBoolean(false);
  private float threshold = 0.05f; // Peak amplitude threshold (default ~ -26dB)
  private final ByteArrayOutputStream capturedStream = new ByteArrayOutputStream();
  private Thread captureThread = null;

  private int targetTrackIndex = 0;
  private int targetSlotIndex = 0;
  private Runnable onTriggerCallback = null;
  private Runnable onFinishedCallback = null;

  private float currentLivePeak = 0f;

  // ── Live-monitor ring ──
  // The capture thread publishes Q31 stereo frames here; JavaAudioDriver drains one block per
  // render into firmware2.LiveInput.currentBlock so the INPUT_L/R/STEREO oscillator sources can
  // monitor the microphone while the line is armed. Single-writer (capture thread) /
  // single-reader (audio thread) ring; frame counters increase monotonically.
  private static final int MONITOR_RING_FRAMES = 8192;
  private final int[] monitorRing = new int[MONITOR_RING_FRAMES * 2];
  private volatile long monitorFramesWritten = 0;
  private long monitorFramesRead = 0;

  private void publishToMonitorRing(byte[] pcm16Stereo, int bytesRead) {
    long w = monitorFramesWritten;
    for (int i = 0; i + 3 < bytesRead; i += 4) {
      int l = (short) ((pcm16Stereo[i + 1] << 8) | (pcm16Stereo[i] & 0xff)) << 16;
      int r = (short) ((pcm16Stereo[i + 3] << 8) | (pcm16Stereo[i + 2] & 0xff)) << 16;
      int pos = (int) (w % MONITOR_RING_FRAMES);
      monitorRing[pos * 2] = l;
      monitorRing[pos * 2 + 1] = r;
      w++;
    }
    monitorFramesWritten = w;
  }

  /**
   * Drains up to {@code numFrames} stereo Q31 frames into {@code stereoOut} (interleaved). Returns
   * true when a full block was available; false on underrun (the caller should publish no input for
   * this block). Call only from the audio thread.
   */
  public boolean fillMonitorBlock(int[] stereoOut, int numFrames) {
    long available = monitorFramesWritten - monitorFramesRead;
    if (available < numFrames) {
      return false;
    }
    if (available > MONITOR_RING_FRAMES) {
      // Overrun (audio thread stalled): skip ahead to the freshest data.
      monitorFramesRead = monitorFramesWritten - numFrames;
    }
    for (int i = 0; i < numFrames; i++) {
      int pos = (int) (monitorFramesRead % MONITOR_RING_FRAMES);
      stereoOut[i * 2] = monitorRing[pos * 2];
      stereoOut[i * 2 + 1] = monitorRing[pos * 2 + 1];
      monitorFramesRead++;
    }
    return true;
  }

  public void arm(
      float thresholdDb, int trackIndex, int slotIndex, Runnable onTrigger, Runnable onFinished) {
    this.threshold = (float) Math.pow(10.0, thresholdDb / 20.0);
    this.targetTrackIndex = trackIndex;
    this.targetSlotIndex = slotIndex;
    this.onTriggerCallback = onTrigger;
    this.onFinishedCallback = onFinished;

    this.monitorOnly.set(false);
    this.isArmed.set(true);
    this.isRecording.set(false);
    this.capturedStream.reset();
    this.currentLivePeak = 0f;

    startCaptureThread();
  }

  // ── Monitor-only mode ──
  // Opens the microphone and feeds the live-monitor ring WITHOUT the threshold-recording logic,
  // so INPUT_L/R/STEREO patches can monitor the input continuously (Settings → Monitor Audio
  // Input). Recording arm() takes over the same line if invoked while monitoring.
  private final AtomicBoolean monitorOnly = new AtomicBoolean(false);

  public boolean isMonitoring() {
    return isArmed.get() && monitorOnly.get();
  }

  public void startMonitoring() {
    if (isArmed.get()) {
      return; // already capturing (recording arm or a previous monitor start)
    }
    this.monitorOnly.set(true);
    this.isArmed.set(true);
    this.isRecording.set(false);
    this.currentLivePeak = 0f;
    startCaptureThread();
  }

  public void stopMonitoring() {
    if (monitorOnly.getAndSet(false)) {
      stop();
    }
  }

  public void stop() {
    isArmed.set(false);
    if (isRecording.getAndSet(false)) {
      finalizeRecording();
    }
    if (inputLine != null && inputLine.isOpen()) {
      inputLine.stop();
      inputLine.close();
    }
  }

  public boolean isRecording() {
    return isRecording.get();
  }

  public boolean isArmed() {
    return isArmed.get();
  }

  public float getLivePeak() {
    return currentLivePeak;
  }

  private void startCaptureThread() {
    try {
      AudioFormat format = new AudioFormat(44100, 16, 2, true, false);
      DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
      if (!AudioSystem.isLineSupported(info)) {
        System.err.println("[Capture] Microphone line TargetDataLine not supported by system!");
        return;
      }
      inputLine = (TargetDataLine) AudioSystem.getLine(info);
      inputLine.open(format, 4096);
      inputLine.start();

      captureThread =
          Thread.startVirtualThread(
              () -> {
                byte[] buffer = new byte[1024];
                System.out.println(
                    "[Capture] Audio input capture armed, listening below threshold limit...");

                while (isArmed.get()) {
                  int read = inputLine.read(buffer, 0, buffer.length);
                  if (read <= 0) continue;

                  // Feed the live-monitor ring (INPUT_* oscillator sources).
                  publishToMonitorRing(buffer, read);

                  // Read peak amplitude block levels
                  float maxPeak = 0f;
                  for (int i = 0; i < read; i += 2) {
                    short val = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xff));
                    float amp = Math.abs(val / 32768.0f);
                    if (amp > maxPeak) maxPeak = amp;
                  }
                  currentLivePeak = maxPeak;

                  if (monitorOnly.get()) {
                    continue; // monitor-only: no threshold-recording logic
                  }

                  if (!isRecording.get()) {
                    if (maxPeak >= threshold) {
                      // Threshold hit! Transition to active recording states!
                      isRecording.set(true);
                      System.out.println(
                          "[Capture] Threshold triggered! Peak raw amplitude=" + maxPeak);
                      if (onTriggerCallback != null) {
                        javax.swing.SwingUtilities.invokeLater(onTriggerCallback);
                      }
                      capturedStream.write(buffer, 0, read);
                    }
                  } else {
                    // Active recording - buffer stream bytes
                    capturedStream.write(buffer, 0, read);
                  }
                }
              });
    } catch (Exception ex) {
      System.err.println("[Capture] Failed to open TargetDataLine: " + ex.getMessage());
    }
  }

  private void finalizeRecording() {
    try {
      byte[] pcmData = capturedStream.toByteArray();
      if (pcmData.length == 0) return;

      // Save to SD library SAMPLES directory path
      String mountedRoot =
          org.chuck.deluge.project.PreferencesManager.getLibraryDir().getAbsolutePath();
      File samplesDir = new File(mountedRoot, "SAMPLES/RECORDED");
      if (!samplesDir.exists()) {
        samplesDir.mkdirs();
      }

      File targetWav = new File(samplesDir, "Rec_" + System.currentTimeMillis() + ".wav");
      JavaAudioDriver.saveWavFile(pcmData, targetWav);
      System.out.println("[Capture] Saved threshold recording to: " + targetWav.getAbsolutePath());

      // Update JApp project model kits targets references
      if (SwingDelugeApp.mainInstance != null) {
        ProjectModel project = SwingDelugeApp.mainInstance.getCurrentProject();
        if (project != null && targetTrackIndex < project.getTracks().size()) {
          org.chuck.deluge.model.TrackModel track = project.getTracks().get(targetTrackIndex);
          if (track instanceof org.chuck.deluge.model.KitTrackModel kitTrack) {
            if (targetSlotIndex < kitTrack.getDrums().size()) {
              org.chuck.deluge.model.Drum drum = kitTrack.getDrums().get(targetSlotIndex);
              if (drum instanceof org.chuck.deluge.model.SoundDrum sd) {
                sd.setSamplePath(targetWav.getAbsolutePath());
                System.out.println(
                    "[Capture] Successfully loaded recorded sample to slot: " + targetSlotIndex);
              }
            }
          }
        }
        if (onFinishedCallback != null) {
          javax.swing.SwingUtilities.invokeLater(onFinishedCallback);
        }
      }
    } catch (Exception ex) {
      System.err.println("[Capture] Failed to finalize WAV recording: " + ex.getMessage());
    }
  }
}
