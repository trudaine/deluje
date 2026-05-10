package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.Dx7Engine;

/**
 * Wraps Dx7Engine for use in NativeJavaSequencer without ChucK dependency.
 *
 * <p>Includes DSP chain matching the DSL engine exactly:
 * Dx7Engine -> SVFilter -> HPF -> env(gain). This ensures the spectral
 * content and amplitude match the DSL engine.
 *
 * <p>The DSL chain routes Dx7Engine through SVFilter -> HPF -> env(gain) -> Pan.
 * The SVFilter (ZDF topology with tanh saturation) significantly shapes the
 * Dx7Engine's high-amplitude output (up to +/-2.3), attenuating pathologically
 * loud harmonics. The HPF removes subsonic content below 20 Hz. These filters
 * are the primary reason for the 4x amplitude difference between the raw
 * Dx7Engine output and the DSL engine's output.
 *
 * <p>Filter state (SVFilter ic1eq/ic2eq, HPF x1/x2/y1/y2) persists across
 * note triggers, matching the DSL behavior where filter state is only reset
 * during initialization, not per voice trigger.
 */
public class NativeDx7Voice {
    private final Dx7Engine engine;
    private final float sampleRate;
    private final NativeSVFilter svf;
    private final NativeHPF hpf;
    private int trackIdx = -1;
    private float outputGain = 1.0f;

    public NativeDx7Voice(float sampleRate) {
        this.sampleRate = sampleRate;
        this.engine = new Dx7Engine(sampleRate);
        this.svf = new NativeSVFilter(sampleRate);
        this.hpf = new NativeHPF(sampleRate);
        // Default filter settings matching DSL initialization
        svf.freq(5000);
        svf.Q(1.0);
        svf.morph(0.0);
        hpf.freq(20.0);
        hpf.Q(0.707);
    }

    public void loadPatch(byte[] patch) {
        engine.loadPatch(patch);
    }

    public void setOutputGain(float gain) {
        this.outputGain = gain;
    }

    /**
     * Set SVFilter parameters.
     *
     * @param cutoff  filter cutoff frequency in Hz
     * @param resonance filter resonance (Q factor)
     * @param morph   morph from LP (0) through BP (0.5) to HP (1.0)
     */
    public void setFilterParams(double cutoff, double resonance, double morph) {
        svf.freq(Math.max(20.0, Math.min(20000.0, cutoff)));
        svf.Q(Math.max(1.0, Math.min(10.0, resonance)));
        svf.morph(Math.max(0.0, Math.min(1.0, morph)));
    }

    public void setHpfParams(double freq, double res) {
        hpf.freq(freq);
        hpf.Q(Math.max(0.1, res));
    }

    /**
     * Set HPF cutoff frequency.
     */
    public void setHpfFreq(double freq) {
        hpf.freq(Math.max(10.0, freq));
    }

    /**
     * Trigger the voice with note-on, gain, and DSP chain params.
     *
     * <p>Note: ADSR params (attack/decay/sustain/release) are accepted for API
     * compatibility but ignored — the Dx7Engine's per-operator envelopes handle
     * all amplitude shaping. Only filter params and gain are applied.
     *
     * @param trackIdx  logical track index
     * @param midiNote  MIDI note number
     * @param velocity  MIDI velocity (0-127)
     * @param gain      overall gain factor (applied directly as outputGain)
     * @param cutoff    filter cutoff frequency in Hz (applied to SVFilter)
     * @param resonance filter resonance (applied to SVFilter)
     * @param attack    ignored (DX7 internal envelopes)
     * @param decay     ignored
     * @param sustain   ignored
     * @param release   ignored
     */
    public void trigger(int trackIdx, int midiNote, int velocity,
                        float gain, double cutoff, double resonance,
                        double attack, double decay, double sustain, double release) {
        this.trackIdx = trackIdx;
        this.outputGain = gain;
        // Apply filter params on trigger to match DSL per-step filter updates.
        svf.freq(Math.max(20.0, Math.min(20000.0, cutoff)));
        svf.Q(Math.max(1.0, Math.min(10.0, resonance)));
        svf.morph(0.0);
        engine.noteOn(midiNote, velocity);
    }

    public void release() {
        engine.noteOff();
    }

    public void fastRelease() {
        engine.noteOff();
    }

    /**
     * Compute one sample of output.
     *
     * Signal chain: Dx7Engine.tick() -> SVFilter.compute() -> HPF.compute() * outputGain
     *
     * NOTE: No ADSR in this chain. The Dx7Engine's per-operator logarithmic envelopes
     * (dexed/msfa) handle all amplitude shaping for attack, decay, sustain, and release.
     * The ADSR multiply in the DSL chain (env[i][0] DelugeAdsr) is also bypassed for DX7 tracks.
     * outputGain applies track-level volume without envelope shaping.
     */
    public float tick() {
        float raw = engine.tick();
        float filtered = svf.tick(raw);
        float highpassed = hpf.tick(filtered);
        return highpassed * outputGain;
    }

    public int getTrackIdx() { return trackIdx; }

    /**
     * Returns true while this voice is producing sound.
     * The voice is active only while the Dx7Engine has operator activity.
     */
    public boolean isActive() {
        return engine.isActive();
    }

    /**
     * Clear track assignment to stop rendering in the render loop.
     * Called when the voice is recycled by the allocator after release completes.
     */
    public void clearTrack() {
        this.trackIdx = -1;
    }

    /**
     * Returns true only for voices that are both active AND currently assigned
     * to a track. This prevents orphaned voices (released but still rendering
     * via isActive()) from contributing to the mix.
     */
    public boolean isRendering() {
        return trackIdx >= 0 && engine.isActive();
    }
}
