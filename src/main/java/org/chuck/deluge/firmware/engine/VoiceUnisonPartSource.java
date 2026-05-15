package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.model.sample.Sample;

/**
 * Port of VoiceUnisonPartSource from the firmware.
 * Ties an oscillator or sample playback to a specific unison voice.
 */
public class VoiceUnisonPartSource {
  public int oscPos;
  public int phaseIncrementStoredValue;
  public int carrierFeedback;
  public boolean active;

  public VoiceSample voiceSample = null;
  public Object dxVoice = null;

  public VoiceUnisonPartSource() {}

  public void reset() {
    oscPos = 0;
    phaseIncrementStoredValue = 0;
    carrierFeedback = 0;
    active = false;
    if (voiceSample != null) {
      voiceSample = null; // Re-solicited from engine usually
    }
  }

  public void noteOn(Sample sample, int samplesLate) {
    this.active = true;
    if (sample != null) {
      if (voiceSample == null) voiceSample = new VoiceSample();
      voiceSample.noteOn(sample, samplesLate);
    }
  }

  public void render(int[] buffer, int numSamples, int phaseIncrement, Sample sample) {
    if (!active) return;
    if (voiceSample != null) {
      voiceSample.render(buffer, numSamples, phaseIncrement, sample);
    }
  }
}
