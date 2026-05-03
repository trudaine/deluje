package org.chuck.deluge.engine.dsp;

/**
 * Pure Java implementation of a sample player (SndBuf).
 */
public class NativeSndBuf {
    private float[] samples;
    private double pos = 0;
    private double rate = 1.0;
    private boolean looping = false;
    private boolean active = false;
    private final float sampleRate;

    public NativeSndBuf(float sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setSamples(float[] samples) {
        this.samples = samples;
    }

    public void trigger() {
        this.pos = 0;
        this.active = true;
    }

    public void setRate(double rate) {
        this.rate = rate;
    }

    public void setLooping(boolean looping) {
        this.looping = looping;
    }

    public float tick() {
        if (!active || samples == null) return 0;

        int idx = (int) pos;
        if (idx >= samples.length) {
            if (looping) {
                pos = 0;
                idx = 0;
            } else {
                active = false;
                return 0;
            }
        }

        float out = samples[idx];
        pos += rate;
        
        return out;
    }

    public boolean isActive() {
        return active;
    }
}
