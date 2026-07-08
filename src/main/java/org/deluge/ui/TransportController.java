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
  }

  public void onStop() {
    if (org.deluge.engine.JavaAudioDriver.isResamplingActive) {
      if (app.topBar != null) {
        app.topBar.stopRecordingIfActive();
      }
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
      if (app.topBar != null && app.topBar.getParamReadout() != null) {
        app.topBar.getParamReadout().printTransient("REC", "ON");
      }
    } else {
      if (btn != null) {
        btn.setBackground(new Color(0x3a, 0x0c, 0x0c));
        btn.setForeground(new Color(0xff, 0x33, 0x33));
        btn.setText("\u25CF REC");
      }
      if (app.topBar != null && app.topBar.getParamReadout() != null) {
        app.topBar.getParamReadout().printTransient("REC", "OFF");
      }
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
      btn.setBackground(new Color(0xff, 0xaa, 0x00));
      btn.setForeground(Color.WHITE);
      btn.setText("\u25CF SAMPLING");
      if (app.topBar != null && app.topBar.getParamReadout() != null) {
        app.topBar.getParamReadout().printTransient("LOOP", "REC");
      }
    } else {
      byte[] pcmData = org.deluge.engine.JavaAudioDriver.stopResampling();
      btn.setBackground(new Color(0x3e, 0x27, 0x0c));
      btn.setForeground(new Color(0xff, 0xb3, 0x00));
      btn.setText("\u25CF RESAMPLE");
      if (app.topBar != null && app.topBar.getParamReadout() != null) {
        app.topBar.getParamReadout().printTransient("LOOP", "DONE");
      }

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

  public void toggleMetronome() {
    if (app.topBar != null) {
      app.topBar.toggleMetronome();
    }
  }
}
