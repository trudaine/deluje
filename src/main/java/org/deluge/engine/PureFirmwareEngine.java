package org.deluge.engine;

import org.deluge.BridgeContract;
import org.deluge.firmware2.Param;
import org.deluge.playback.PlaybackHandler;

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
  private volatile boolean running = false;

  private float currentBpm = 120.0f;

  // Last-seen song-param globals (NaN = never synced, so the first pass always applies them).
  private float lastSpVol = Float.NaN;
  private float lastSpLpfFreq = Float.NaN;
  private float lastSpLpfRes = Float.NaN;
  private float lastSpLpfMorph = Float.NaN;
  private float lastSpHpfFreq = Float.NaN;
  private float lastSpHpfRes = Float.NaN;
  private float lastSpHpfMorph = Float.NaN;

  public PureFirmwareEngine() {
    this.audioDriver = new JavaAudioDriver(audioEngine, playbackHandler);
  }

  public JavaAudioDriver getAudioDriver() {
    return audioDriver;
  }

  public void start(BridgeContract bridge) {
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
                      syncFromBridge(bridge);
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

  private long lastPlayStateVal = -1;

  private void syncFromBridge(BridgeContract bridge) {
    if (bridge == null) return;

    long play = bridge.getGlobalInt(BridgeContract.G_PLAY);
    if (play != lastPlayStateVal) {
      lastPlayStateVal = play;
    }

    if (play == 1L) {
      if (!playbackHandler.isPlaying()) {
        playbackHandler.start();
      }
      int stepTicks = 24;
      if (playbackHandler.getProject() != null
          && !playbackHandler.getProject().getClips().isEmpty()) {
        stepTicks = playbackHandler.getProject().getClips().get(0).isTripletMode() ? 32 : 24;
      }
      int currentStep = playbackHandler.lastSwungTickActioned / stepTicks;
      bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, (long) currentStep);
    } else {
      if (playbackHandler.isPlaying()) {
        playbackHandler.stop();
        for (var sound : audioEngine.sounds) {
          if (sound instanceof org.deluge.engine.FirmwareSound fs) {
            fs.releaseAllNotes();
          } else if (sound instanceof org.deluge.engine.FirmwareKit fk) {
            for (var ds : fk.drumSounds) {
              ds.releaseAllNotes();
            }
          }
        }
      }
      bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
    }

    float bpm = (float) bridge.getGlobalFloat(BridgeContract.G_BPM);
    if (bpm != currentBpm) {
      setBpm(bpm);
    }

    float masterVol = (float) bridge.getGlobalFloat(BridgeContract.G_MASTER_VOL);
    audioEngine.masterVolumeAdjustmentL = (int) (masterVol * 2147483647.0);
    audioEngine.masterVolumeAdjustmentR = audioEngine.masterVolumeAdjustmentL;

    // Sync master compressor settings
    float compThreshold = (float) bridge.getGlobalFloat(BridgeContract.G_MASTER_COMP);
    float compAttack = (float) bridge.getGlobalFloat(BridgeContract.G_MASTER_COMP_ATTACK);
    float compRelease = (float) bridge.getGlobalFloat(BridgeContract.G_MASTER_COMP_RELEASE);
    float compRatio = (float) bridge.getGlobalFloat(BridgeContract.G_MASTER_COMP_RATIO);
    float compBlend = (float) bridge.getGlobalFloat(BridgeContract.G_MASTER_COMP_BLEND);

    audioEngine.masterCompressor.setThresholdFloat(compThreshold);
    audioEngine.masterCompressor.setAttackFloat(compAttack);
    audioEngine.masterCompressor.setReleaseFloat(compRelease);
    audioEngine.masterCompressor.setRatioFloat(compRatio);
    audioEngine.masterCompressor.setBlendFloat(compBlend);

    // Sync master reverb model + room/damping/width from the song (previously left at defaults, so
    // the song's reverb settings had no effect in the pure engine).
    int reverbModel = (int) bridge.getGlobalInt(BridgeContract.G_REVERB_MODEL);
    // C reverb.hpp:15-19 — enum order is FREEVERB=0, MUTABLE=1, DIGITAL=2 (song.cpp:1423 casts
    // the raw XML int straight to this enum).
    audioEngine.masterReverb.setModel(
        switch (reverbModel) {
          case 1 -> org.deluge.firmware2.Reverb.Model.MUTABLE;
          case 2 -> org.deluge.firmware2.Reverb.Model.DIGITAL;
          default -> org.deluge.firmware2.Reverb.Model.FREEVERB;
        });
    audioEngine.masterReverb.setRoomSize(
        (float) bridge.getGlobalFloat(BridgeContract.G_REVERB_ROOM));
    audioEngine.masterReverb.setDamping(
        (float) bridge.getGlobalFloat(BridgeContract.G_REVERB_DAMP));
    audioEngine.masterReverb.setWidth((float) bridge.getGlobalFloat(BridgeContract.G_REVERB_WIDTH));

    // Sync master delay. The delay's internal tempo-sync is disabled (see FirmwareAudioEngine), so
    // we compute the delay time in seconds ourselves — tempo-synced when a sync level is set,
    // otherwise the free-running G_DELAY_TIME (scaled by the live rate) — and convert it to the
    // buffer's userDelayRate (inverse of DelayBuffer.getIdealBufferSizeFromRate).
    long syncLevel = bridge.getGlobalInt(BridgeContract.G_DELAY_SYNC_LEVEL);
    double delaySec;
    if (syncLevel > 0) {
      double stepSec = (currentBpm > 0 ? 60.0 / currentBpm : 0.5) / 4.0; // 16th-note step
      double syncFactor = Math.pow(2.0, syncLevel - 1);
      if (bridge.getGlobalInt(BridgeContract.G_DELAY_SYNC_TYPE) == 1) syncFactor *= 1.5; // triplet
      delaySec = syncFactor * stepSec;
    } else {
      delaySec = bridge.getGlobalFloat(BridgeContract.G_DELAY_TIME);
      double spRate = bridge.getGlobalFloat(BridgeContract.G_SP_DELAY_RATE);
      if (spRate > 0.001) delaySec *= spRate;
    }
    delaySec = Math.max(0.001, Math.min(2.0, delaySec));
    long rate = (long) (16384L * 16777216L / (delaySec * 44100.0));
    audioEngine.delayState.userDelayRate = (int) Math.min(rate, Integer.MAX_VALUE);

    double fb = bridge.getGlobalFloat(BridgeContract.G_DELAY_FB);
    double spFb = bridge.getGlobalFloat(BridgeContract.G_SP_DELAY_FEEDBACK);
    if (spFb > 0.001) fb *= Math.min(1.0, spFb);
    fb = Math.max(0.0, Math.min(1.0, fb));
    audioEngine.delayState.delayFeedbackAmount =
        Math.min((int) (fb * 2147483647.0), (1 << 30) - (1 << 26));

    audioEngine.masterDelay.pingPong = bridge.getGlobalInt(BridgeContract.G_DELAY_PINGPONG) != 0;
    audioEngine.masterDelay.analog = bridge.getGlobalInt(BridgeContract.G_DELAY_ANALOG) != 0;

    // Sync individual track params
    float spVol = (float) bridge.getGlobalFloat(BridgeContract.G_SP_VOLUME);
    if (spVol < 0.01f && System.currentTimeMillis() % 2000 < 50) {
      System.out.println("[PureFirmwareEngine] WARNING: G_SP_VOLUME is very low: " + spVol);
    }

    // Sync track mute states from the bridge globals to Java engine sounds
    org.deluge.model.ProjectModel project = playbackHandler.getProject();
    if (project != null) {
      for (int t = 0; t < project.getTracks().size(); t++) {
        org.deluge.model.ClipModel clip = project.getTracks().get(t).getActiveClip();
        boolean isMuted = bridge.getMute(t);
        if (clip != null && clip.getSound() instanceof org.deluge.engine.FirmwareSound fs) {
          fs.muted = isMuted;
        }
      }
    }

    // Arpeggiator clock: one step = arpDivision note (16 = 16th). gatePos accumulates a full step
    // (1<<24) over stepSamples, advancing by (phaseIncrement>>8) per sample → phaseInc =
    // 2^32/steps.
    double beatSec = (currentBpm > 0) ? 60.0 / currentBpm : 0.5;
    for (org.deluge.firmware2.GlobalEffectable sound : audioEngine.sounds) {
      if (sound instanceof org.deluge.engine.FirmwareSound fsArp) {
        int div = fsArp.arpDivision > 0 ? fsArp.arpDivision : 16;
        double rateMul = (fsArp.arpRateMultiplier > 0.01f) ? fsArp.arpRateMultiplier : 1.0;
        double stepSamples = (4.0 / div) * beatSec * 44100.0 / rateMul;
        fsArp.fw2Sound.arpPhaseIncrement =
            (stepSamples > 1) ? (int) (4294967296.0 / stepSamples) : 0;
        fsArp.currentBpm = currentBpm; // for granular grain timing
      }
    }

    // Song-param overrides (performance sliders). Only pushed into the sounds when a global
    // actually CHANGES: the previous unconditional write (every 20ms, every sound) clobbered the
    // per-track knobs from the patch — and would instantly undo the dialogs' live-apply edits.
    float spLpfFreq = (float) bridge.getGlobalFloat(BridgeContract.G_SP_LPF_FREQ);
    float spLpfRes = (float) bridge.getGlobalFloat(BridgeContract.G_SP_LPF_RES);
    float spLpfMorph = (float) bridge.getGlobalFloat(BridgeContract.G_SP_LPF_MORPH);
    float spHpfFreq = (float) bridge.getGlobalFloat(BridgeContract.G_SP_HPF_FREQ);
    float spHpfRes = (float) bridge.getGlobalFloat(BridgeContract.G_SP_HPF_RES);
    float spHpfMorph = (float) bridge.getGlobalFloat(BridgeContract.G_SP_HPF_MORPH);
    boolean spChanged =
        spVol != lastSpVol
            || spLpfFreq != lastSpLpfFreq
            || spLpfRes != lastSpLpfRes
            || spLpfMorph != lastSpLpfMorph
            || spHpfFreq != lastSpHpfFreq
            || spHpfRes != lastSpHpfRes
            || spHpfMorph != lastSpHpfMorph;
    if (spChanged) {
      lastSpVol = spVol;
      lastSpLpfFreq = spLpfFreq;
      lastSpLpfRes = spLpfRes;
      lastSpLpfMorph = spLpfMorph;
      lastSpHpfFreq = spHpfFreq;
      lastSpHpfRes = spHpfRes;
      lastSpHpfMorph = spHpfMorph;
      for (org.deluge.firmware2.GlobalEffectable sound : audioEngine.sounds) {
        if (sound instanceof org.deluge.engine.FirmwareSound fs) {
          fs.paramNeutralValues[Param.LOCAL_VOLUME] = (int) (spVol * 2147483647.0);
          fs.paramNeutralValues[Param.LOCAL_LPF_FREQ] = (int) (spLpfFreq / 20000.0f * 2147483647.0);
          fs.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = (int) (spLpfRes * 2147483647.0);
          fs.paramNeutralValues[Param.LOCAL_LPF_MORPH] = (int) (spLpfMorph * 2147483647.0);
          fs.paramNeutralValues[Param.LOCAL_HPF_FREQ] = (int) (spHpfFreq / 20000.0f * 2147483647.0);
          fs.paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = (int) (spHpfRes * 2147483647.0);
          fs.paramNeutralValues[Param.LOCAL_HPF_MORPH] = (int) (spHpfMorph * 2147483647.0);
        }
      }
    }
  }

  public void setProject(org.deluge.model.ProjectModel project) {
    playbackHandler.setProject(project);
    audioEngine.sounds.clear();
    // Sync sounds from song to engine
    for (var clip : project.getClips()) {
      if (clip.getSound() != null) {
        audioEngine.sounds.add((org.deluge.firmware2.GlobalEffectable) clip.getSound());
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
    if (audioThread != null) {
      try {
        audioThread.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (syncThread != null) {
      try {
        syncThread.join(2000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public FirmwareAudioEngine getAudioEngine() {
    return audioEngine;
  }

  public PlaybackHandler getPlaybackHandler() {
    return playbackHandler;
  }
}
