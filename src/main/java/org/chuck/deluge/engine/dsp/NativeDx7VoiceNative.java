package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.FfmDx7Engine;

/**
 * Wraps the native DX7 JNI library (deluge_dsp_native.dll) for use in
 * {@code NativeJavaSequencer} — drop-in replacement for {@link NativeDx7Voice}
 * that uses native C++ code instead of the Java Dx7Engine.
 *
 * <p>Includes the same DSP chain as the DSL engine:
 * FfmDx7Engine (C++ DX7 engine) -> SVFilter -> HPF -> ADSR(gain).
 *
 * <p>The native engine processes 132-sample blocks internally and serves
 * per-sample output via buffering, making it far more efficient than the
 * Java Dx7Engine which does per-sample computation at ~50x CPU cost.
 */
public class NativeDx7VoiceNative {

    // Lifecycle: one native voice handle per instance
    private long nativeHandle = 0;

    private final float sampleRate;
    private final NativeAdsr adsr;
    private final NativeSVFilter svf;
    private final NativeHPF hpf;
    private int trackIdx = -1;
    private float outputGain = 1.0f;

    /** Whether this voice has been explicitly released (note-off sent). */
    private boolean released = false;

    /** Whether the native voice has finished producing audio. */
    private boolean nativeActive = false;

    static {
        // Ensure native library is loaded exactly once
        // FfmDx7Engine's static initializer handles loading; we just need
        // to ensure nativeInit() is called once.
        // Safe to call multiple times — the firmware's createInstance()
        // is a singleton lazy-init.
    }

    /**
     * Global native engine initializer — call once before creating any voices.
     * Safe to call multiple times (no-op after first).
     */
    public static synchronized void initNativeEngine() {
        FfmDx7Engine.init();
    }

    public NativeDx7VoiceNative(float sampleRate) {
        this.sampleRate = sampleRate;
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

    /**
     * Lazily create the native voice handle. Called on first trigger().
     */
    private void ensureVoice() {
        if (nativeHandle == 0) {
            nativeHandle = FfmDx7Engine.createVoice();
            if (nativeHandle == 0) {
                throw new RuntimeException("Failed to create native DX7 voice");
            }
        }
    }

    /**
     * Force-destroy the native voice handle. Called on clearTrack() to release
     * native resources. After this, trigger() will create a new handle.
     */
    private void destroyVoice() {
        if (nativeHandle != 0) {
            FfmDx7Engine.destroyVoice(nativeHandle);
            nativeHandle = 0;
        }
    }

    public void loadPatch(byte[] patch) {
        ensureVoice();
        FfmDx7Engine.loadPatch(nativeHandle, patch);
    }

    public void setOutputGain(float gain) {
        this.outputGain = gain;
    }

    /**
     * Set SVFilter parameters.
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

    public void setHpfFreq(double freq) {
        hpf.freq(Math.max(10.0, freq));
    }

    /**
     * Trigger the voice with note-on, velocity, gain, and DSP params.
     *
     * @param trackIdx  logical track index
     * @param midiNote  MIDI note number
     * @param velocity  MIDI velocity (0-127)
     * @param gain      overall gain factor (matching DSL's gainVal)
     * @param cutoff    filter cutoff frequency
     * @param resonance filter resonance
     * @param attack    envelope attack in seconds
     * @param decay     envelope decay in seconds
     * @param sustain   envelope sustain (0-1)
     * @param release   envelope release in seconds
     */
    public void trigger(int trackIdx, int midiNote, int velocity,
                        float gain, double cutoff, double resonance,
                        double attack, double decay, double sustain, double release) {
        this.trackIdx = trackIdx;
        this.released = false;

        ensureVoice();

        adsr.setParams(attack, decay, sustain, release);
        this.outputGain = gain;

        svf.freq(Math.max(20.0, Math.min(20000.0, cutoff)));
        svf.Q(Math.max(1.0, Math.min(10.0, resonance)));
        svf.morph(0.0);

        // Note-on with velocity
        FfmDx7Engine.noteOn(nativeHandle, midiNote, velocity);
        nativeActive = true;
        adsr.keyOn();
    }

    public void release() {
        if (!released && nativeHandle != 0) {
            FfmDx7Engine.noteOff(nativeHandle);
            released = true;
        }
        adsr.keyOff();
    }

    public void fastRelease() {
        if (!released && nativeHandle != 0) {
            FfmDx7Engine.noteOff(nativeHandle);
            released = true;
        }
        adsr.fastRelease();
    }

    /**
     * Compute one sample of output.
     *
     * Signal chain: FfmDx7Engine.tick() -> SVFilter -> HPF -> ADSR * outputGain
     */
    public float tick() {
        if (nativeHandle == 0) return 0f;

        float raw = FfmDx7Engine.tick(nativeHandle);
        float filtered = svf.tick(raw);
        float highpassed = hpf.tick(filtered);
        float env = adsr.tick();
        return highpassed * outputGain * env;
    }

    public int getTrackIdx() { return trackIdx; }

    /**
     * Returns true while this voice is producing sound.
     * The native voice may still be active (envelope releasing) even after
     * note-off. We combine native isActive with ADSR state.
     */
    public boolean isActive() {
        if (nativeHandle != 0) {
            nativeActive = FfmDx7Engine.isActive(nativeHandle);
        }
        return nativeActive || adsr.isActive();
    }

    /**
     * Clear track assignment and destroy native resources.
     */
    public void clearTrack() {
        this.trackIdx = -1;
        destroyVoice();
    }

    /**
     * Returns true only for voices that are both active AND currently assigned
     * to a track. This prevents orphaned voices from contributing to the mix.
     */
    public boolean isRendering() {
        return trackIdx >= 0 && (isActive());
    }
}
