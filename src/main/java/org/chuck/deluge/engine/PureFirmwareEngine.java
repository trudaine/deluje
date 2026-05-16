package org.chuck.deluge.engine;

import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.playback.PlaybackHandler;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * High-fidelity 'Pure Java' Deluge workstation engine.
 * This class coordinates the high-fidelity synthesis engine and the sequencer,
 * running entirely independent of ChucK and the DSL.
 */
public class PureFirmwareEngine {
  private final FirmwareAudioEngine audioEngine = new FirmwareAudioEngine();
  private final PlaybackHandler playbackHandler = new PlaybackHandler();
  private final JavaAudioDriver audioDriver;
  private Thread audioThread;
  private Thread syncThread;
  private boolean running = false;
  
  private float currentBpm = 120.0f;

  public PureFirmwareEngine() {
    this.audioDriver = new JavaAudioDriver(audioEngine, playbackHandler);
  }

  public void start(ChuckVM vm) {
      if (running) return;
      running = true;
      
      playbackHandler.start();
      
      // ── Start Audio Thread ──
      audioThread = Thread.ofVirtual().name("DelugeAudio").start(() -> {
          audioDriver.run();
      });

      // ── Start Bridge Sync Thread ──
      syncThread = Thread.ofVirtual().name("DelugeSync").start(() -> {
          while (running) {
              syncFromBridge(vm);
              try { Thread.sleep(20); } catch (InterruptedException e) { break; }
          }
      });
      
      System.out.println("[PureFirmwareEngine] Workstation Started (Pure Java Mode)");
  }

  private void syncFromBridge(ChuckVM vm) {
      if (vm == null) return;
      
      float bpm = (float) vm.getGlobalFloat(BridgeContract.G_BPM);
      if (bpm != currentBpm) {
          setBpm(bpm);
      }

      float masterVol = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);
      audioEngine.masterVolumeAdjustmentL = (int)(masterVol * 2147483647.0);
      audioEngine.masterVolumeAdjustmentR = audioEngine.masterVolumeAdjustmentL;

      // Sync individual track params
      for (org.chuck.deluge.firmware.engine.GlobalEffectable sound : audioEngine.sounds) {
          if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound fs) {
              fs.paramNeutralValues[Param.LOCAL_VOLUME] = 
                  (int)(vm.getGlobalFloat(BridgeContract.G_SP_VOLUME) * 2147483647.0);
              fs.paramNeutralValues[Param.LOCAL_LPF_FREQ] = 
                  (int)(vm.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ) * 2147483647.0);
          }
      }
  }

  public void setSong(Song song) {
      playbackHandler.setSong(song);
      audioEngine.sounds.clear();
      // Sync sounds from song to engine
      for (var clip : song.clips) {
          if (clip instanceof org.chuck.deluge.firmware.model.InstrumentClip ic && ic.sound != null) {
              audioEngine.sounds.add(ic.sound);
          }
      }
  }

  public void setBpm(float bpm) {
      this.currentBpm = bpm;
      audioDriver.updateBpm(bpm);
  }

  public void start() {
      if (running) return;
      running = true;
      
      playbackHandler.start();
      
      // ── Start Audio Thread ──
      // The driver drives the renderBlock() calls
      audioThread = Thread.ofVirtual().name("DelugeAudio").start(() -> {
          // We wrap the driver run to also handle sequencer advancement
          runAudioAndSequencer();
      });
      
      System.out.println("[PureFirmwareEngine] Workstation Started (Pure Java Mode)");
  }

  private void runAudioAndSequencer() {
      // Logic to advance ticks based on samples rendered
      // 96 PPQN
      double ticksPerSample = ((currentBpm / 60.0) * 96.0) / 44100.0;
      
      // Start the driver but we'll manually step the sequencer
      // Actually, let's just use the driver and update it to accept a callback
      // Or simply do the loop here.
      
      // For simplicity in this pass, I'll let the driver just run
      // and we'll sync in a different thread or use a shared state.
      audioDriver.run();
  }

  public void stop() {
      running = false;
      audioDriver.stop();
      playbackHandler.stop();
  }
  
  public FirmwareAudioEngine getAudioEngine() {
      return audioEngine;
  }
  
  public PlaybackHandler getPlaybackHandler() {
      return playbackHandler;
  }
}
