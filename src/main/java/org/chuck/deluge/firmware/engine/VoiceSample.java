package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware.util.Q31;

/**
 * Port of VoiceSample from the firmware.
 * Manages the current playback position and buffer access for a sample-based voice.
 */
public class VoiceSample {
    public long playPosBig; // 24-bit fractional part
    public int playDirection = 1;
    public boolean looping = false;
    public int loopStartSamples = -1;
    public int loopEndSamples = -1;

    public void noteOn(Sample sample, int samplesLate) {
        this.playPosBig = (long)samplesLate << 24;
        if (sample != null) {
            this.loopStartSamples = sample.fileLoopStartSamples;
            this.loopEndSamples = sample.fileLoopEndSamples;
            this.looping = (loopStartSamples != -1);
        }
    }

    public void render(int[] buffer, int numSamples, int phaseIncrement, Sample sample, int amplitude) {
        if (sample == null || sample.data == null) return;

        float[] data = sample.data;
        int numChannels = sample.numChannels;
        int maxSample = sample.getNumSamples();

        for (int i = 0; i < numSamples; i++) {
            int intPos = (int)(playPosBig >> 24);
            int frac = (int)(playPosBig & 0xFFFFFF);

            if (intPos < 0 || intPos >= maxSample - 1) {
                if (looping) {
                    playPosBig = (long)loopStartSamples << 24;
                    intPos = loopStartSamples;
                } else {
                    break; 
                }
            }

            // Simple linear interpolation for now (firmware uses 16-point sinc for high-quality)
            float s0 = data[intPos * numChannels];
            float s1 = data[(intPos + 1) * numChannels];
            float out = s0 + (s1 - s0) * (frac / 16777216.0f);

            int valQ31 = (int)(out * 2147483647.0f);
            buffer[i] = Q31.addSaturate(buffer[i], Q31.mult(valQ31, amplitude));

            playPosBig += (long)phaseIncrement * playDirection;
        }
    }
}
