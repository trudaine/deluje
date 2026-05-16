package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.StereoUGen;
import org.chuck.deluge.firmware.engine.FirmwareAudioEngine;
import org.chuck.deluge.firmware.playback.PlaybackHandler;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.util.Q31;

/** 
 * A UGen that acts as the output bridge for the Pure Java Firmware Engine.
 * It pulls samples from the FirmwareAudioEngine and pushes them into the ChucK stream.
 */
public class FirmwareEngineUGen extends StereoUGen {
  private final FirmwareAudioEngine engine;
  private final PlaybackHandler playbackHandler;
  private int currentSampleIdx = 0;
  private int lastNumSamples = 0;
  
  private double ticksPerSample = 0.005; // Default for 120BPM
  private double accumulatedTicks = 0;

  public FirmwareEngineUGen(FirmwareAudioEngine engine, PlaybackHandler playbackHandler) {
    this.engine = engine;
    this.playbackHandler = playbackHandler;
    updateBpm(120.0f);
  }

  public void updateBpm(float bpm) {
      // 96 PPQN, 4 beats per bar, 44100 samples per sec
      // ticksPerSec = (bpm / 60) * 96
      // ticksPerSample = ticksPerSec / 44100
      double ticksPerSec = (bpm / 60.0) * 96.0;
      this.ticksPerSample = ticksPerSec / 44100.0;
  }

  @Override
  protected void computeStereo(float left, float right, long systemTime) {
    if (currentSampleIdx >= lastNumSamples) {
      // 1. Advance the sequencer by the number of ticks in the last block
      if (playbackHandler != null && lastNumSamples > 0) {
          accumulatedTicks += ticksPerSample * lastNumSamples;
          int ticksToAdvance = (int) accumulatedTicks;
          if (ticksToAdvance > 0) {
              playbackHandler.advanceTicks(ticksToAdvance);
              accumulatedTicks -= ticksToAdvance;
          }
      }

      // 2. Trigger a block render in the firmware synthesis engine
      engine.renderBlock(128);
      lastNumSamples = 128;
      currentSampleIdx = 0;
    }

    StereoSample s = engine.masterBuffer[currentSampleIdx++];
    lastOutChannels[0] = Q31.toFloat(s.l);
    lastOutChannels[1] = Q31.toFloat(s.r);
  }

  @Override
  protected void computeStereo(float input, long systemTime) {
    computeStereo(input, input, systemTime);
  }
}
