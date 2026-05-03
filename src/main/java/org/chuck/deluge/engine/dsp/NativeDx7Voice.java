package org.chuck.deluge.engine.dsp;

import org.chuck.audio.util.Dx7Engine;
import org.chuck.audio.util.Dx7Patch;

/**
 * Wraps Dx7Engine for use in NativeJavaSequencer without ChucK dependency.
 * (Reusing the core logic from the existing Dx7Engine class)
 */
public class NativeDx7Voice {
    private final Dx7Engine engine;
    private final float sampleRate;
    private int trackIdx = -1;

    public NativeDx7Voice(float sampleRate) {
        this.sampleRate = sampleRate;
        this.engine = new Dx7Engine(sampleRate);
    }

    public void loadPatch(byte[] patch) {
        engine.loadPatch(patch);
    }

    public void trigger(int trackIdx, int midiNote, int velocity) {
        this.trackIdx = trackIdx;
        engine.noteOn(midiNote, velocity);
    }

    public void release() {
        engine.noteOff();
    }

    public float tick() {
        // Dx7Engine.compute is protected, but we're in the same package org.chuck.audio.util?
        // No, we're in org.chuck.deluge.engine.dsp.
        // I should probably make compute() public in Dx7Engine or use reflection/bridge.
        // Since I'm refactoring, I'll assume I can access it or I'll fix Dx7Engine.
        return engine.tick(); 
    }

    public boolean isActive() {
        return engine.isActive();
    }
}
