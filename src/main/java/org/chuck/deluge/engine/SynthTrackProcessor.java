package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;
import org.chuck.audio.util.Gain;
import org.chuck.audio.util.Pan2;
import org.chuck.audio.filter.SVFilter;
import org.chuck.audio.util.MorphingWavetable;
import org.chuck.audio.util.DelugeAdsr;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckEvent;
import org.chuck.core.ChuckVM;
import org.chuck.core.Shred;
import org.chuck.deluge.BridgeContract;

import java.util.logging.Logger;

/**
 * A dedicated processor for a Synth track.
 * Manages wavetable synthesis, filtering, and envelopes.
 */
public class SynthTrackProcessor implements Shred {
    private static final Logger logger = Logger.getLogger(SynthTrackProcessor.class.getName());

    private final int trackId;
    private final ChuckVM vm;
    private final BridgeContract bridge;
    
    private MorphingWavetable osc;
    private SVFilter filter;
    private DelugeAdsr env;
    private Pan2 pan;
    private Gain delaySend;
    private Gain reverbSend;

    public SynthTrackProcessor(int trackId, ChuckVM vm, BridgeContract bridge) {
        this.trackId = trackId;
        this.vm = vm;
        this.bridge = bridge;
    }

    @Override
    public void shred() {
        osc = new MorphingWavetable(sampleRate());
        filter = new SVFilter(sampleRate());
        env = new DelugeAdsr(sampleRate());
        pan = new Pan2();
        delaySend = new Gain();
        reverbSend = new Gain();

        Gain trackMaster = new Gain();
        trackMaster.chuck(dac());

        osc.chuck(filter).chuck(env).chuck(pan).chuck(trackMaster);
        
        env.set(0.05, 0.2, 0.5, 0.3);

        // Send-based routing
        Gain gDelayIn = (Gain) vm.getGlobalObject("g_delay_in");
        Gain gReverbIn = (Gain) vm.getGlobalObject("g_reverb_in");
        if (gDelayIn != null) pan.chuck(delaySend).chuck(gDelayIn);
        if (gReverbIn != null) pan.chuck(reverbSend).chuck(gReverbIn);

        ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.TICK_EVENT);

        while (true) {
            advance(tickEvent);
            if (vm.getGlobalInt(BridgeContract.G_PLAY) != 1) continue;

            int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
            int idx = trackId * BridgeContract.STEPS + step;

            ChuckArray trackType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);
            if (trackType == null || trackType.getInt(trackId) != 1) continue;

            updateParams(idx);

            ChuckArray pattern = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
            if (pattern == null || pattern.getInt(idx) == 0) {
                env.keyOff();
                continue;
            }

            // Probability check
            ChuckArray probability = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PROBABILITY);
            if (probability != null && Math.random() > (double) probability.getFloat(idx)) continue;

            trigger(idx);
        }
    }

    private void updateParams(int idx) {
        ChuckArray gFilterArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_FILTER);
        ChuckArray stepFilter = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_FILTER);
        ChuckArray stepRes = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_RES);
        ChuckArray stepPan = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_PAN);

        double masterPan = vm.getGlobalFloat(BridgeContract.G_MASTER_PAN);

        // Filter Cutoff (Normalized 0-1 to Hz)
        double cutoff = 0.5;
        if (gFilterArr != null && stepFilter != null) {
            cutoff = (double) (gFilterArr.getFloat(trackId * 2) + stepFilter.getFloat(idx)) * 20000.0;
        }
        filter.freq(Math.max(20.0, Math.min(20000.0, cutoff)));
        
        // Resonance (Normalized to Q 1-10)
        double q = 1.0;
        if (gFilterArr != null && stepRes != null) {
            q = (double) (gFilterArr.getFloat(trackId * 2 + 1) + stepRes.getFloat(idx)) * 4.0 + 1.0;
        }
        filter.Q(Math.max(1.0, Math.min(10.0, q)));

        pan.pan((float) Math.max(-1.0, Math.min(1.0, masterPan + (stepPan != null ? stepPan.getFloat(idx) : 0.0))));
    }

    private void trigger(int idx) {
        ChuckArray pitchArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PITCH);
        ChuckArray velocityArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
        ChuckArray trackLevelArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);
        
        double masterVol = vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);

        int pitch = pitchArr != null ? (int) pitchArr.getInt(idx) : 0;
        osc.freq((float) mtof(pitch + 60));
        
        double vel = velocityArr != null ? velocityArr.getFloat(idx) : 0.7;
        double trackLevel = trackLevelArr != null ? trackLevelArr.getFloat(trackId) : 0.7;
        
        env.gain((float) ((double) vel * (double) trackLevel * masterVol * 0.8));
        env.keyOn();

        if (vm.getLogLevel() >= 2) {
            vm.print("SYNTH trigger track: " + trackId + " step: " + (idx % 16) + "\n");
        }
    }

    private double mtof(double m) {
        return 440.0 * Math.pow(2.0, (m - 69.0) / 12.0);
    }
}
