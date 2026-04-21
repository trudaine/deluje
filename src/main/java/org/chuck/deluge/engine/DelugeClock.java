package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;
import org.chuck.core.ChuckEvent;
import org.chuck.core.ChuckVM;
import org.chuck.core.Shred;
import org.chuck.deluge.BridgeContract;

/**
 * The Master Clock Shred.
 * High-priority heartbeat that drives the entire project.
 */
public class DelugeClock implements Shred {
    private final ChuckVM vm;
    private final BridgeContract bridge;

    public DelugeClock(ChuckVM vm, BridgeContract bridge) {
        this.vm = vm;
        this.bridge = bridge;
    }

    @Override
    public void shred() {
        int step = 0;
        ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.TICK_EVENT);
        if (tickEvent == null) return;

        // Initial alignment: sync to next sample border to minimize jitter
        double stepDurSamples = getStepDurationSamples(0);
        double align = stepDurSamples - (now() % stepDurSamples);
        advance(samp(align));

        while (vm.getGlobalInt(BridgeContract.G_PLAY) == 1) {
            int stutterOn = (int) vm.getGlobalInt(BridgeContract.G_STUTTER_ON);
            
            if (stutterOn == 0) {
                // Normal playback
                vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, step % 16);
                tickEvent.broadcast(vm);
                
                advance(samp(getStepDurationSamples(step)));
                step++;
            } else {
                // Stutter/Repeat mode
                float stutterDiv = (float) vm.getGlobalFloat(BridgeContract.G_STUTTER_DIV);
                if (stutterDiv < 1.0f) stutterDiv = 1.0f;
                
                tickEvent.broadcast(vm);
                advance(samp(getStepDurationSamples(step) / stutterDiv));
            }
        }
    }

    private double getStepDurationSamples(int step) {
        float bpm = (float) vm.getGlobalFloat(BridgeContract.G_BPM);
        if (bpm < 1.0f) bpm = 120.0f;
        float swing = (float) vm.getGlobalFloat(BridgeContract.G_SWING);
        swing = Math.max(0.0f, Math.min(1.0f, swing));
        
        double baseSec = 60.0 / bpm / 4.0; // 16th note
        double base = baseSec * sampleRate();
        
        // Apply Swing (8th note swing logic)
        if (step % 2 == 0) {
            return base * (1.0 + (swing - 0.5) * 0.4);
        } else {
            return base * (1.0 - (swing - 0.5) * 0.4);
        }
    }
}
