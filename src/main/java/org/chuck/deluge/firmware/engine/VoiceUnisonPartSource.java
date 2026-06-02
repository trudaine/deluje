package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.model.sample.Sample;

/**
 * Port of VoiceUnisonPartSource from the firmware. Ties an oscillator or sample playback to a
 * specific unison voice.
 */
public class VoiceUnisonPartSource {
  public long oscPos;
  public int phaseIncrementStoredValue;
  public int carrierFeedback;
  public boolean active;

  public VoiceSample voiceSample = null;
  public org.chuck.audio.util.Dx7Engine dxVoice = null;

  public VoiceUnisonPartSource() {}

  public void reset() {
    reset(0);
  }

  public void reset(int initialPhase) {
    if (initialPhase != -1) {
      oscPos = initialPhase;
    }
    phaseIncrementStoredValue = 0;
    carrierFeedback = 0;
    active = false;
    if (voiceSample != null) {
      voiceSample = null; // Re-solicited from engine usually
    }
  }

  public void noteOn(
      Sample sample,
      org.chuck.deluge.firmware.model.sample.SampleVoiceSettings settings,
      int samplesLate) {
    this.active = true;
    if (sample != null) {
      if (voiceSample == null) voiceSample = new VoiceSample();
      voiceSample.noteOn(sample, settings, samplesLate);
    }
  }

  public boolean render(
      int[] buffer, int numSamples, int phaseIncrement, Sample sample, int amplitude) {
    if (!active) return false;
    if (voiceSample != null) {
      voiceSample.render(buffer, numSamples, phaseIncrement, sample, amplitude);
      // Generic boundary completion gate
      if (!voiceSample.active) {
        active = false;
        return false;
      }
      return true;
    }
    return false;
  }
}
