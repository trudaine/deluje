package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware.util.Q31;

/**
 * High-fidelity settings-driven sample voice playback engine. Supports start/end points, loops,
 * direction shifts, and transposition rate calculations.
 */
public class VoiceSample {
  public long playPosBig; // 32:32 fixed-point position
  public int playDirection = 1;
  public boolean looping = false;
  public int startSample = 0;
  public int endSample = 0;
  public int loopStartSamples = -1;
  public int loopEndSamples = -1;
  public int loopMode = 0; // 0 = OFF, 1 = ON, 2 = ONCE
  public double sampleRateScale = 1.0;
  public boolean active = true;

  public void noteOn(
      Sample sample,
      org.chuck.deluge.firmware.model.sample.SampleVoiceSettings settings,
      int samplesLate) {
    this.active = true;
    if (sample == null || sample.data == null) {
      this.active = false;
      return;
    }

    int totalSamples = sample.getNumSamples();
    this.startSample = (int) (((long) settings.startPoint * totalSamples) / 65535L);
    this.endSample = (int) (((long) settings.endPoint * totalSamples) / 65535L);
    if (this.endSample <= this.startSample) {
      this.endSample = totalSamples;
    }
    if (this.endSample > totalSamples) {
      this.endSample = totalSamples;
    }

    this.loopStartSamples = (int) (((long) settings.loopStart * totalSamples) / 65535L);
    this.loopEndSamples = (int) (((long) settings.loopEnd * totalSamples) / 65535L);
    this.loopMode = settings.loopMode;
    this.looping = (settings.loopMode != 0);

    this.playDirection = settings.reverse ? -1 : 1;

    // Calculate rate scale factoring original speed and semi-tone pitch adjustments
    double originalFreq = 440.0 * Math.pow(2.0, (sample.midiNoteFromFile - 69) / 12.0);
    double baseRateScale = 44100.0 / originalFreq;
    double transposeFactor = Math.pow(2.0, settings.transpose / 12.0);
    this.sampleRateScale = baseRateScale * transposeFactor;

    // Set initial position
    if (settings.reverse) {
      this.playPosBig = (long) (this.endSample - 1 - samplesLate) << 32;
    } else {
      this.playPosBig = (long) (this.startSample + samplesLate) << 32;
    }
  }

  public void render(
      int[] buffer, int numSamples, int phaseIncrement, Sample sample, int amplitude) {
    if (!active || sample == null || sample.data == null) return;

    float[] data = sample.data;
    int numChannels = sample.numChannels;
    int totalSamples = sample.getNumSamples();

    long inc = (long) (phaseIncrement * sampleRateScale);
    if (inc == 0) inc = 0x100000000L; // Safety fallback

    for (int i = 0; i < numSamples; i++) {
      int intPos = (int) (playPosBig >> 32);
      long frac = playPosBig & 0xFFFFFFFFL;

      // Check boundaries based on direction and loop settings
      if (playDirection == 1) {
        if (intPos < startSample || intPos >= endSample - 1) {
          if (looping) {
            int loopStart = (loopStartSamples != -1) ? loopStartSamples : startSample;
            playPosBig = (long) loopStart << 32;
            intPos = loopStart;
          } else {
            active = false;
            break;
          }
        }
      } else {
        if (intPos <= startSample || intPos >= endSample) {
          if (looping) {
            int loopEnd = (loopEndSamples != -1) ? loopEndSamples : endSample;
            playPosBig = (long) (loopEnd - 1) << 32;
            intPos = loopEnd - 1;
          } else {
            active = false;
            break;
          }
        }
      }

      // Linear interpolation
      float s0 = data[intPos * numChannels];
      float s1 = data[Math.min(totalSamples - 1, (intPos + 1) * numChannels)];
      float out = s0 + (s1 - s0) * (float) (frac / 4294967296.0);

      int valQ31 = (int) (out * 2147483647.0);
      int wet = Q31.mult(valQ31, amplitude);

      buffer[i] = Q31.addSaturate(buffer[i], wet);

      playPosBig += inc * playDirection;
    }
  }
}
