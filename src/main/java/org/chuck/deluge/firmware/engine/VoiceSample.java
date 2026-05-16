package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of VoiceSample from the firmware. Manages the current playback position and buffer access
 * for a sample-based voice.
 */
public class VoiceSample {
  public long playPosBig; // 32:32 fixed-point position
  public int playDirection = 1;
  public boolean looping = false;
  public int loopStartSamples = -1;
  public int loopEndSamples = -1;

  public void noteOn(Sample sample, int samplesLate) {
    this.playPosBig = (long) samplesLate << 32;
    if (sample != null) {
      this.loopStartSamples = sample.fileLoopStartSamples;
      this.loopEndSamples = sample.fileLoopEndSamples;
      this.looping = (loopStartSamples != -1);
    }
  }

  public void render(
      int[] buffer, int numSamples, int phaseIncrement, Sample sample, int amplitude) {
    if (sample == null || sample.data == null) return;

    float[] data = sample.data;
    int numChannels = sample.numChannels;
    int maxSample = sample.getNumSamples();

    // ── Bit-Accurate Rate Calculation ──
    // Translate oscillator cycle-increment (pInc) to sample-increment (sInc).
    // Oscillator pInc = (Freq / 44100) * 2^32.
    // We want sInc = (Freq / OriginalSampleFreq) * 2^32.
    // So sInc = pInc * (44100 / OriginalSampleFreq).

    double originalFreq = 440.0 * Math.pow(2.0, (sample.midiNoteFromFile - 69) / 12.0);
    double rateScale = 44100.0 / originalFreq;
    long inc = (long) (phaseIncrement * rateScale);
    if (inc == 0) inc = 0x100000000L; // Safety fallback

    for (int i = 0; i < numSamples; i++) {
      int intPos = (int) (playPosBig >> 32);
      long frac = playPosBig & 0xFFFFFFFFL;

      if (intPos < 0 || intPos >= maxSample - 1) {
        if (looping) {
          playPosBig = (long) loopStartSamples << 32;
          intPos = loopStartSamples;
        } else {
          break;
        }
      }

      // High-fidelity linear interpolation
      float s0 = data[intPos * numChannels];
      float s1 = data[(intPos + 1) * numChannels];
      float out = s0 + (s1 - s0) * (float) (frac / 4294967296.0);

      int valQ31 = (int) (out * 2147483647.0);
      int wet = Q31.mult(valQ31, amplitude);

      // Additive saturation into voice buffer (No shift here, handled by FirmwareVoice)
      buffer[i] = Q31.addSaturate(buffer[i], wet);

      playPosBig += inc * playDirection;
    }
  }
}
