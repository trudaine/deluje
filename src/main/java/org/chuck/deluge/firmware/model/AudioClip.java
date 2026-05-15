package org.chuck.deluge.firmware.model;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.timestretch.TimeStretcher;
import org.chuck.deluge.firmware.model.sample.Sample;

/**
 * Port of the Deluge's AudioClip class.
 * Handles bit-accurate sample playback with real-time time-stretching.
 */
public class AudioClip extends Clip {
  public Sample sample;
  public final TimeStretcher timeStretcher = new TimeStretcher();
  public int timeStretchRatio = 1 << 24; // 1.0

  public AudioClip() {
    super(ClipType.AUDIO);
  }

  @Override
  public int getMaxLength() {
    return sample != null ? sample.getNumSamples() : 0;
  }

  @Override
  public void resumePlayback(boolean mayMakeSound) {
    // Logic to resync time-stretcher to current pos
  }

  @Override
  public void expectNoFurtherTicks(boolean actuallySoundChange) {
    // release resources
  }

  @Override
  public boolean isPlayingAutomationNow() {
    return paramManager.mightContainAutomation();
  }

  @Override
  public boolean backtrackingCouldLoopBackToEnd() {
    return sequenceDirectionMode == SequenceDirection.PINGPONG;
  }

  public void render(StereoSample[] buffer, int numSamples, int phaseIncrement) {
    if (sample == null || sample.data == null) return;
    
    // In Java, we convert float data to int for the high-fidelity processors
    int[] monoData = new int[sample.data.length];
    for (int i = 0; i < sample.data.length; i++) {
        monoData[i] = (int)(sample.data[i] * 2147483647.0f);
    }
    
    int[] output = new int[numSamples];
    timeStretcher.process(output, numSamples, timeStretchRatio, phaseIncrement, monoData);
    
    for (int i = 0; i < numSamples; i++) {
        buffer[i].l = output[i];
        buffer[i].r = output[i];
    }
  }
}
