package org.deluge.ui;

import java.awt.Color;
import java.io.File;
import java.util.ArrayDeque;
import javax.swing.JButton;
import org.deluge.BridgeContract;
import org.deluge.model.ClipModel;
import org.deluge.model.Drum;
import org.deluge.model.KitTrackModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.StepData;

/**
 * Controller managing audio engine transport, playback states, resampling capture, tap tempo
 * calculation, and metronome volume.
 */
public class TransportController {
  private final SwingDelugeApp app;
  private final BridgeContract bridge;
  private final ArrayDeque<Long> tapTimes = new ArrayDeque<>();

  public TransportController(SwingDelugeApp app) {
    this.app = app;
    this.bridge = app.bridge;
  }

  public boolean isPlaying() {
    return bridge != null && bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L;
  }

  public void onPlayToggle() {
    long nextPlay = bridge.getGlobalInt(BridgeContract.G_PLAY) == 1L ? 0L : 1L;
    if (nextPlay == 1L) {
      app.syncHighFidelityEngine(app.getCurrentProject());
      if (app.clipPanel != null) {
        app.clipPanel.setPlayheadFollowMode(true);
      }
    }
    bridge.setGlobalInt(BridgeContract.G_PLAY, nextPlay);
    if (bridge != null) {
      bridge.setPlayState((int) nextPlay);
    }
    syncFaceplatePlaybackLeds();
  }

  public void onStop() {
    if (org.deluge.engine.JavaAudioDriver.isResamplingActive) {
      onResampleToggle(null);
    }
    bridge.setGlobalInt(BridgeContract.G_PLAY, 0L);
    if (bridge != null) {
      bridge.setPlayState(0);
    }

    try {
      Object fwEngineObj = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (fwEngineObj instanceof org.deluge.engine.FirmwareAudioEngine fwEngine) {
        fwEngine.panic();
      }
    } catch (Exception ex) {
      // ignore
    }
    syncFaceplatePlaybackLeds();
  }

  public void onLiveRecordToggle(JButton btn) {
    SwingGridPanel.isLiveRecordModeActive = !SwingGridPanel.isLiveRecordModeActive;
    app.getCurrentProject().setRecording(SwingGridPanel.isLiveRecordModeActive);
    if (SwingGridPanel.isLiveRecordModeActive) {
      if (btn != null) {
        btn.setBackground(new Color(0xd3, 0x2f, 0x2f));
        btn.setForeground(Color.WHITE);
        btn.setText("\u25CF RECORDING");
      }
      showOled("REC", "ON");
    } else {
      if (btn != null) {
        btn.setBackground(new Color(0x3a, 0x0c, 0x0c));
        btn.setForeground(new Color(0xff, 0x33, 0x33));
        btn.setText("\u25CF REC");
      }
      showOled("REC", "OFF");
    }
    syncFaceplatePlaybackLeds();
  }

  /** Shows transient feedback on the real, visible faceplate OLED. */
  private void showOled(String banner, String value) {
    if (app.hardwareTopPanel != null && app.hardwareTopPanel.getOledPanel() != null) {
      app.hardwareTopPanel.getOledPanel().showParamText(banner, value);
    }
  }

  // The faceplate PLAY/RECORD LEDs (SwingHardwareTopPanel.isPlaying/isRecording) are a display
  // copy separate from the real state here (bridge G_PLAY, SwingGridPanel.isLiveRecordModeActive)
  // -- there are multiple ways to reach these methods (the faceplate's own buttons, the global
  // "R" keyboard shortcut, ThresholdRecordDialog's triggerPlayToggle()), so the LEDs must be
  // resynced here on every real state change rather than only when the faceplate's own button
  // was the trigger.
  private void syncFaceplatePlaybackLeds() {
    if (app.hardwareTopPanel != null) {
      app.hardwareTopPanel.setPlaybackState(isPlaying(), SwingGridPanel.isLiveRecordModeActive);
    }
  }

  public void onResampleToggle(JButton btn) {
    if (!org.deluge.engine.JavaAudioDriver.isResamplingActive) {
      if (app.getPureEngine() != null && app.getPureEngine().getAudioEngine() != null) {
        app.getPureEngine().getAudioEngine().panic();
      }
      org.deluge.engine.JavaAudioDriver.startResampling();
      if (bridge.getGlobalInt(BridgeContract.G_PLAY) == 0L) {
        onPlayToggle();
      }
      if (btn != null) {
        btn.setBackground(new Color(0xff, 0xaa, 0x00));
        btn.setForeground(Color.WHITE);
        btn.setText("\u25CF SAMPLING");
      }
      showOled("LOOP", "REC");
    } else {
      byte[] pcmData = org.deluge.engine.JavaAudioDriver.stopResampling();
      if (btn != null) {
        btn.setBackground(new Color(0x3e, 0x27, 0x0c));
        btn.setForeground(new Color(0xff, 0xb3, 0x00));
        btn.setText("\u25CF RESAMPLE");
      }
      showOled("LOOP", "DONE");

      if (pcmData == null || pcmData.length < 100) {
        return;
      }

      try {
        File resampleDir =
            new File(org.deluge.project.PreferencesManager.getLibraryDir(), "SAMPLES/RESAMPLE");
        if (!resampleDir.exists()) {
          resampleDir.mkdirs();
        }
        String sampleName = "Resample_" + System.currentTimeMillis() + ".wav";
        File targetFile = new File(resampleDir, sampleName);
        org.deluge.engine.JavaAudioDriver.saveWavFile(pcmData, targetFile);

        KitTrackModel kitTrack =
            new KitTrackModel("Resample " + (app.getCurrentProject().getTracks().size() + 1));
        Drum drum = new SoundDrum(sampleName, "SAMPLES/RESAMPLE/" + sampleName);
        kitTrack.addDrum(drum);

        ClipModel clip = new ClipModel("CLIP 1", 1, 16);
        clip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
        clip.setStep(0, 4, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
        clip.setStep(0, 8, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
        clip.setStep(0, 12, StepData.of(true, 1.0f, 1.0f, 1.0f, 0));
        kitTrack.addClip(clip);

        app.getCurrentProject().addTrack(kitTrack);
        app.propagateCurrentModel();
        app.pushModelToBridge();
        app.syncHighFidelityEngine(app.getCurrentProject());

        if (app.clipPanel != null) {
          app.clipPanel.refresh();
        }
      } catch (Exception ex) {
        System.err.println("Failed to save and load master resample: " + ex.getMessage());
      }
    }
  }

  public void tapTempo() {
    long now = System.currentTimeMillis();
    if (!tapTimes.isEmpty() && (now - tapTimes.peekLast()) > 2000) {
      tapTimes.clear();
    }
    tapTimes.addLast(now);
    while (tapTimes.size() > 8) {
      tapTimes.removeFirst();
    }
    if (tapTimes.size() >= 2) {
      long[] arr = tapTimes.stream().mapToLong(Long::longValue).toArray();
      long totalGap = arr[arr.length - 1] - arr[0];
      double avgGap = totalGap / (double) (arr.length - 1);
      double bpm = 60000.0 / avgGap;
      bpm = Math.max(20.0, Math.min(999.0, bpm));
      if (app.getCurrentProject() != null) {
        app.getCurrentProject().setBpm((float) bpm);
      }
    }
  }

  public void onMasterVolumeChanged(float vol) {
    bridge.setGlobalFloat(BridgeContract.G_MASTER_VOL, vol);
  }

  /**
   * Momentary live stutter, held while the Q key (or a future hardware button) is down. C:
   * triggered on real hardware by pressing the gold knob mapped to UNPATCHED_STUTTER_RATE
   * (Sound::modEncoderButtonAction, sound.cpp:4449) -- calls the per-sound stutterer's
   * beginStutter/endStutter directly (model/mod_controllable/mod_controllable_audio.cpp:1299-1323),
   * not a sequencer-level flag. This used to just toggle a global bridge flag that SequencerClock
   * read to fake a step-repeat; replaced with the real firmware2.Stutterer (buffer-capture
   * loop/reverse/ping-pong) on the currently-edited track's live Sound.
   */
  public void setStutterActive(boolean active) {
    org.deluge.engine.FirmwareSound fs = currentTrackFirmwareSound();
    if (fs == null) {
      showOled("STUT", "NO SYNTH TRACK");
      return;
    }
    if (active) {
      org.deluge.firmware2.Stutterer.GLOBAL.beginStutter(
          fs.fw2Sound,
          fs.paramManager,
          currentStutterConfig(),
          fs.fw2Sound.timePerInternalTickInverse);
    } else {
      org.deluge.firmware2.Stutterer.GLOBAL.endStutter(fs.paramManager);
    }
    showOled("STUT", active ? "ON" : "OFF");
  }

  private org.deluge.engine.FirmwareSound currentTrackFirmwareSound() {
    if (app.clipPanel == null) return null;
    return org.deluge.engine.DroneLabGenerator.getActiveTrackSound(
        bridge, app.clipPanel.editedModelTrack);
  }

  /** Builds a Stutterer.Config from the current track's model.StutterConfig, if it's a synth. */
  private org.deluge.firmware2.Stutterer.Config currentStutterConfig() {
    org.deluge.firmware2.Stutterer.Config config = new org.deluge.firmware2.Stutterer.Config();
    org.deluge.model.ProjectModel project = app.getCurrentProject();
    int trackIndex = app.clipPanel.editedModelTrack;
    if (project != null
        && trackIndex >= 0
        && trackIndex < project.getTracks().size()
        && project.getTracks().get(trackIndex) instanceof org.deluge.model.SynthTrackModel st) {
      org.deluge.model.StutterConfig sc = st.getStutter();
      config.quantized = sc.isStutterQuantized();
      config.reversed = sc.isStutterReversed();
      config.pingPong = sc.isStutterPingPong();
    }
    return config;
  }

  public void toggleMetronome() {
    Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine) {
      engine.metronomeEnabled = !engine.metronomeEnabled;
      showOled("METRO", engine.metronomeEnabled ? "ON" : "OFF");
    }
  }
}
