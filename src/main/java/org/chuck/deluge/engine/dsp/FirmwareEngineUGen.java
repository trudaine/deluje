package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.StereoUGen;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.playback.PlaybackHandler;
import org.chuck.deluge.firmware.util.Q31;

/**
 * A UGen that acts as the output bridge for the Pure Java Firmware Engine. It pulls samples from
 * the active global FirmwareAudioEngine and pushes them into the ChucK stream.
 */
public class FirmwareEngineUGen extends StereoUGen {
  private final ChuckVM vm;
  private final FirmwareAudioEngine defaultEngine;
  private final PlaybackHandler defaultPlaybackHandler;
  private int currentSampleIdx = 0;
  private int lastNumSamples = 0;

  private double ticksPerSample = 0.005; // Default for 120BPM
  private double accumulatedTicks = 0;

  public FirmwareEngineUGen(
      ChuckVM vm, FirmwareAudioEngine defaultEngine, PlaybackHandler defaultPlaybackHandler) {
    this.vm = vm;
    this.defaultEngine = defaultEngine;
    this.defaultPlaybackHandler = defaultPlaybackHandler;
    updateBpm(120.0f);
  }

  public void updateBpm(float bpm) {
    double ticksPerSec = (bpm / 60.0) * 96.0;
    this.ticksPerSample = ticksPerSec / 44100.0;
  }

  @Override
  protected void computeStereo(float left, float right, long systemTime) {
    // Dynamically look up the active global engine and playback handler from the VM
    Object engineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
    FirmwareAudioEngine activeEngine =
        (engineObj instanceof FirmwareAudioEngine)
            ? (FirmwareAudioEngine) engineObj
            : defaultEngine;

    Object handlerObj = vm.getGlobalObject(BridgeContract.G_PLAYBACK_HANDLER);
    PlaybackHandler activePlaybackHandler =
        (handlerObj instanceof PlaybackHandler)
            ? (PlaybackHandler) handlerObj
            : defaultPlaybackHandler;

    if (currentSampleIdx >= lastNumSamples) {
      // 1. Advance the sequencer by the number of ticks in the last block
      if (activePlaybackHandler != null && lastNumSamples > 0) {
        accumulatedTicks += ticksPerSample * lastNumSamples;
        int ticksToAdvance = (int) accumulatedTicks;
        if (ticksToAdvance > 0) {
          activePlaybackHandler.advanceTicks(ticksToAdvance);
          accumulatedTicks -= ticksToAdvance;
        }
      }

      // 2. Trigger a block render in the active firmware synthesis engine
      if (activeEngine != null) {
        activeEngine.renderBlock(128);
      }
      lastNumSamples = 128;
      currentSampleIdx = 0;
    }

    if (activeEngine != null && currentSampleIdx < activeEngine.masterBuffer.length) {
      StereoSample s = activeEngine.masterBuffer[currentSampleIdx++];
      lastOutChannels[0] = Q31.toFloat(s.l);
      lastOutChannels[1] = Q31.toFloat(s.r);
    } else {
      lastOutChannels[0] = 0.0f;
      lastOutChannels[1] = 0.0f;
    }
  }

  @Override
  protected void computeStereo(float input, long systemTime) {
    computeStereo(input, input, systemTime);
  }
}
