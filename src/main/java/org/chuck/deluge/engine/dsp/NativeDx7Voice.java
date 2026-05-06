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
    private final NativeAdsr adsr;
    private final NativeSVFilter svf;
    private final NativeHPF hpf;
    private int trackIdx = -1;
    private float outputGain = 1.0f;

    public NativeDx7Voice(float sampleRate) {
        this.sampleRate = sampleRate;
        this.engine = new Dx7Engine(sampleRate);
        this.adsr = new NativeAdsr(sampleRate);
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
     * <p>Note: filter params (cutoff, resonance) are passed for compatibility
     * but the caller should call setFilterParams() before trigger() to configure
     * the SVFilter. The HPF is preset to 20 Hz.
     *
     * @param trackIdx  logical track index
     * @param midiNote  MIDI note number
     * @param velocity  MIDI velocity (0-127)
     * @param gain      overall gain factor (matching DSL's gainVal: clipVel * trkLvl * 0.8)
     * @param cutoff    filter cutoff frequency in Hz (applied to SVFilter)
     * @param resonance filter resonance (applied to SVFilter)
     * @param attack    envelope attack time in seconds
     * @param decay     envelope decay time in seconds
     * @param sustain   envelope sustain level (0-1)
     * @param release   envelope release time in seconds
     */
    public void trigger(int trackIdx, int midiNote, int velocity,
                        float gain, double cutoff, double resonance,
                        double attack, double decay, double sustain, double release) {
        this.trackIdx = trackIdx;
        adsr.setParams(attack, decay, sustain, release);
        this.outputGain = gain;
        // Apply filter params on trigger to match DSL per-step filter updates.
        // DSL formula: fil[u].freq(clamp(20..20000)) and fil[u].Q(clamp(1..10)).
        // svf.morph=0 => LPF, matching DSL's default for LADDER_12/LADDER_24 filter modes.
        svf.freq(Math.max(20.0, Math.min(20000.0, cutoff)));
        svf.Q(Math.max(1.0, Math.min(10.0, resonance)));
        svf.morph(0.0);
        engine.noteOn(midiNote, velocity);
        adsr.keyOn();
    }

    public void release() {
        adsr.keyOff();
        engine.noteOff();
    }

    public void fastRelease() {
        adsr.fastRelease();
        engine.noteOff();
    }

    /**
     * Compute one sample of output.
     *
     * Signal chain: Dx7Engine.tick() -> SVFilter.compute() -> HPF.compute() -> ADSR * outputGain
     *
     * This exactly matches the DSL chain's gain structure:
     * dx7_out -> fil.compute() -> hpf.compute() -> env.compute() * env.gain
     * where env.compute = input * env_value and env.tick multiplies by gain.
     * So: dx7_out * SVF(raw) * HPF(SVF_out) * env_value * gainVal
     *
     * The native equivalent:
     * engine.tick() -> svf.compute() -> hpf.compute() * adsr.tick() * outputGain
     */
    public float tick() {
        float raw = engine.tick();
        float filtered = svf.tick(raw);
        float highpassed = hpf.tick(filtered);
        float env = adsr.tick();
        return highpassed * outputGain * env;
    }

    public int getTrackIdx() { return trackIdx; }

    /**
     * Returns true while this voice is producing sound.
     * The voice is active while the Dx7Engine has operator activity OR the
     * ADSR envelope has not fully completed its release phase.
     */
    public boolean isActive() {
        return engine.isActive() || adsr.isActive();
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
        return trackIdx >= 0 && (engine.isActive() || adsr.isActive());
    }
}
