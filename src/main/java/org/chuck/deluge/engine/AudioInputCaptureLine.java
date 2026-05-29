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

  public void arm(
      float thresholdDb, int trackIndex, int slotIndex, Runnable onTrigger, Runnable onFinished) {
    this.threshold = (float) Math.pow(10.0, thresholdDb / 20.0);
    this.targetTrackIndex = trackIndex;
    this.targetSlotIndex = slotIndex;
    this.onTriggerCallback = onTrigger;
    this.onFinishedCallback = onFinished;

    this.isArmed.set(true);
    this.isRecording.set(false);
    this.capturedStream.reset();
    this.currentLivePeak = 0f;

    startCaptureThread();
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

                  // Read peak amplitude block levels
                  float maxPeak = 0f;
                  for (int i = 0; i < read; i += 2) {
                    short val = (short) ((buffer[i + 1] << 8) | (buffer[i] & 0xff));
                    float amp = Math.abs(val / 32768.0f);
                    if (amp > maxPeak) maxPeak = amp;
                  }
                  currentLivePeak = maxPeak;

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
