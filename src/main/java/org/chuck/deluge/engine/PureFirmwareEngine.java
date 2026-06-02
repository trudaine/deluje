package org.chuck.deluge.engine;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.playback.PlaybackHandler;

/**
 * High-fidelity 'Pure Java' Deluge workstation engine. This class coordinates the high-fidelity
 * synthesis engine and the sequencer, running entirely independent of ChucK and the DSL.
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
    audioThread =
        Thread.ofPlatform()
            .name("DelugeAudio")
            .start(
                () -> {
                  try {
                    Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                  } catch (SecurityException se) {
                    // Safe ignore in sandboxed environments
                  }
                  audioDriver.run();
                });

    // ── Start Bridge Sync Thread ──
    syncThread =
        Thread.ofVirtual()
            .name("DelugeSync")
            .start(
                () -> {
                  while (running) {
                    try {
                      syncFromBridge(vm);
                    } catch (Exception e) {
                      System.err.println("[PureFirmwareEngine] Sync Error: " + e.getMessage());
                    }
                    try {
                      Thread.sleep(20);
                    } catch (InterruptedException e) {
                      break;
                    }
                  }
                });

    System.out.println("[PureFirmwareEngine] Workstation Started (Pure Java Mode)");
  }

  private void syncFromBridge(ChuckVM vm) {
    if (vm == null) return;

    long play = vm.getGlobalInt(BridgeContract.G_PLAY);
    if (play == 1L) {
      if (!playbackHandler.isPlaying()) {
        playbackHandler.start();
      }
      int stepTicks = 24;
      if (playbackHandler.getSong() != null && !playbackHandler.getSong().clips.isEmpty()) {
        stepTicks = playbackHandler.getSong().clips.get(0).tripletMode ? 32 : 24;
      }
      int currentStep = playbackHandler.lastSwungTickActioned / stepTicks;
      vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, (long) currentStep);
    } else {
      if (playbackHandler.isPlaying()) {
        playbackHandler.stop();
        for (var sound : audioEngine.sounds) {
          if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound fs) {
            fs.releaseAllNotes();
          } else if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareKit fk) {
            for (var ds : fk.drumSounds) {
              ds.releaseAllNotes();
            }
          }
        }
      }
      vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
    }

    float bpm = (float) vm.getGlobalFloat(BridgeContract.G_BPM);
    if (bpm != currentBpm) {
      setBpm(bpm);
    }

    float masterVol = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);
    audioEngine.masterVolumeAdjustmentL = (int) (masterVol * 2147483647.0);
    audioEngine.masterVolumeAdjustmentR = audioEngine.masterVolumeAdjustmentL;

    // Sync master compressor settings
    float compThreshold = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP);
    float compAttack = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP_ATTACK);
    float compRelease = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP_RELEASE);
    float compRatio = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP_RATIO);
    float compBlend = (float) vm.getGlobalFloat(BridgeContract.G_MASTER_COMP_BLEND);

    audioEngine.masterCompressor.setThresholdFloat(compThreshold);
    audioEngine.masterCompressor.setAttackFloat(compAttack);
    audioEngine.masterCompressor.setReleaseFloat(compRelease);
    audioEngine.masterCompressor.setRatioFloat(compRatio);
    audioEngine.masterCompressor.setBlendFloat(compBlend);

    // Sync master reverb model + room/damping/width from the song (previously left at defaults, so
    // the song's reverb settings had no effect in the pure engine).
    int reverbModel = (int) vm.getGlobalInt(BridgeContract.G_REVERB_MODEL);
    audioEngine.masterReverb.setModel(
        switch (reverbModel) {
          case 1 -> org.chuck.deluge.firmware.dsp.reverb.ReverbContainer.Model.DIGITAL;
          case 2 -> org.chuck.deluge.firmware.dsp.reverb.ReverbContainer.Model.MUTABLE;
          default -> org.chuck.deluge.firmware.dsp.reverb.ReverbContainer.Model.FREEVERB;
        });
    audioEngine.masterReverb.setRoomSize((float) vm.getGlobalFloat(BridgeContract.G_REVERB_ROOM));
    audioEngine.masterReverb.setDamping((float) vm.getGlobalFloat(BridgeContract.G_REVERB_DAMP));
    audioEngine.masterReverb.setWidth((float) vm.getGlobalFloat(BridgeContract.G_REVERB_WIDTH));

    // Sync master delay. The delay's internal tempo-sync is disabled (see FirmwareAudioEngine), so
    // we compute the delay time in seconds ourselves — tempo-synced when a sync level is set,
    // otherwise the free-running G_DELAY_TIME (scaled by the live rate) — and convert it to the
    // buffer's userDelayRate (inverse of DelayBuffer.getIdealBufferSizeFromRate).
    long syncLevel = vm.getGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL);
    double delaySec;
    if (syncLevel > 0) {
      double stepSec = (currentBpm > 0 ? 60.0 / currentBpm : 0.5) / 4.0; // 16th-note step
      double syncFactor = Math.pow(2.0, syncLevel - 1);
      if (vm.getGlobalInt(BridgeContract.G_DELAY_SYNC_TYPE) == 1) syncFactor *= 1.5; // triplet
      delaySec = syncFactor * stepSec;
    } else {
      delaySec = vm.getGlobalFloat(BridgeContract.G_DELAY_TIME);
      double spRate = vm.getGlobalFloat(BridgeContract.G_SP_DELAY_RATE);
      if (spRate > 0.001) delaySec *= spRate;
    }
    delaySec = Math.max(0.001, Math.min(2.0, delaySec));
    long rate = (long) (16384L * 16777216L / (delaySec * 44100.0));
    audioEngine.delayState.userDelayRate = (int) Math.min(rate, Integer.MAX_VALUE);

    double fb = vm.getGlobalFloat(BridgeContract.G_DELAY_FB);
    double spFb = vm.getGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK);
    if (spFb > 0.001) fb *= Math.min(1.0, spFb);
    fb = Math.max(0.0, Math.min(1.0, fb));
    audioEngine.delayState.delayFeedbackAmount =
        Math.min((int) (fb * 2147483647.0), (1 << 30) - (1 << 26));

    audioEngine.masterDelay.pingPong = vm.getGlobalInt(BridgeContract.G_DELAY_PINGPONG) != 0;
    audioEngine.masterDelay.analog = vm.getGlobalInt(BridgeContract.G_DELAY_ANALOG) != 0;

    // Sync individual track params
    float spVol = (float) vm.getGlobalFloat(BridgeContract.G_SP_VOLUME);
    if (spVol < 0.01f && System.currentTimeMillis() % 2000 < 50) {
      System.out.println("[PureFirmwareEngine] WARNING: G_SP_VOLUME is very low: " + spVol);
    }

    for (org.chuck.deluge.firmware.engine.GlobalEffectable sound : audioEngine.sounds) {
      if (sound instanceof org.chuck.deluge.firmware.engine.FirmwareSound fs) {
        fs.paramNeutralValues[Param.LOCAL_VOLUME] = (int) (spVol * 2147483647.0);
        fs.paramNeutralValues[Param.LOCAL_LPF_FREQ] =
            (int) (vm.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ) / 20000.0f * 2147483647.0);
        fs.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] =
            (int) (vm.getGlobalFloat(BridgeContract.G_SP_LPF_RES) * 2147483647.0);
        fs.paramNeutralValues[Param.LOCAL_LPF_MORPH] =
            (int) (vm.getGlobalFloat(BridgeContract.G_SP_LPF_MORPH) * 2147483647.0);
        fs.paramNeutralValues[Param.LOCAL_HPF_FREQ] =
            (int) (vm.getGlobalFloat(BridgeContract.G_SP_HPF_FREQ) / 20000.0f * 2147483647.0);
        fs.paramNeutralValues[Param.LOCAL_HPF_RESONANCE] =
            (int) (vm.getGlobalFloat(BridgeContract.G_SP_HPF_RES) * 2147483647.0);
        fs.paramNeutralValues[Param.LOCAL_HPF_MORPH] =
            (int) (vm.getGlobalFloat(BridgeContract.G_SP_HPF_MORPH) * 2147483647.0);
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
    // The driver drives the renderBlock() calls and advances ticks
    audioThread =
        Thread.ofPlatform()
            .name("DelugeAudio")
            .start(
                () -> {
                  try {
                    Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
                  } catch (SecurityException se) {
                    // Safe ignore in sandboxed environments
                  }
                  audioDriver.run();
                });

    System.out.println("[PureFirmwareEngine] Workstation Started (Pure Java Mode)");
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
