package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;
import org.chuck.audio.util.Gain;
import org.chuck.audio.util.Pan2;
import org.chuck.audio.util.SndBuf;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckEvent;
import org.chuck.core.ChuckVM;
import org.chuck.core.Shred;
import org.chuck.deluge.BridgeContract;

import java.util.logging.Logger;

/**
 * A dedicated processor for a single sound in a Kit.
 * Manages sample playback, envelope, and per-step parameters.
 */
public class KitTrackProcessor implements Shred {
    private static final Logger logger = Logger.getLogger(KitTrackProcessor.class.getName());

    private final int trackId;
    private final ChuckVM vm;
    private final BridgeContract bridge;
    
    private SndBuf buf;
    private Pan2 pan;
    private Gain delaySend;
    private Gain reverbSend;
    private Gain modSend;

    public KitTrackProcessor(int trackId, ChuckVM vm, BridgeContract bridge) {
        this.trackId = trackId;
        this.vm = vm;
        this.bridge = bridge;
    }

    @Override
    public void shred() {
        buf = new SndBuf(sampleRate());
        pan = new Pan2();
        delaySend = new Gain();
        reverbSend = new Gain();
        modSend = new Gain();

        Gain trackMaster = new Gain();
        trackMaster.chuck(dac());

        buf.chuck(pan).chuck(trackMaster);

        // Send-based routing
        Gain gDelayIn = (Gain) vm.getGlobalObject("g_delay_in");
        Gain gReverbIn = (Gain) vm.getGlobalObject("g_reverb_in");
        Gain gModIn = (Gain) vm.getGlobalObject("g_mod_in");

        if (gDelayIn != null) pan.chuck(delaySend).chuck(gDelayIn);
        if (gReverbIn != null) pan.chuck(reverbSend).chuck(gReverbIn);
        if (gModIn != null) pan.chuck(modSend).chuck(gModIn);

        ChuckEvent tickEvent = (ChuckEvent) vm.getGlobalObject(BridgeContract.TICK_EVENT);
        ChuckEvent loadTrigger = (ChuckEvent) vm.getGlobalObject(BridgeContract.G_LOAD_TRIGGER);

        // Load listener
        vm.spork(() -> {
            while (true) {
                advance(loadTrigger);
                loadSample();
            }
        });

        loadSample();

        while (vm.getGlobalInt(BridgeContract.G_PLAY) == 1) {
            advance(tickEvent);
            if (vm.getGlobalInt(BridgeContract.G_PLAY) == 0) break;

            int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
            int idx = trackId * 16 + step;

            ChuckArray pattern = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
            ChuckArray mute = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);
            ChuckArray trackType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);

            if (trackType.getInt(trackId) != 0) continue;
            if (mute.getInt(trackId) != 0) continue;
            if (pattern.getInt(idx) == 0) continue;

            ChuckArray probability = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PROBABILITY);
            if (Math.random() > (double) probability.getFloat(idx)) continue;

            trigger(idx);
        }
    }

    private void trigger(int idx) {
        ChuckArray velocity = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
        ChuckArray stepStart = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_START);
        ChuckArray trackLevel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);
        ChuckArray stepPan = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_PAN);

        double vel = velocity.getFloat(idx);
        double masterVol = vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);
        double masterPan = vm.getGlobalFloat(BridgeContract.G_MASTER_PAN);

        buf.rate(1.0f);
        buf.pos((long) ((double) stepStart.getFloat(idx) * (double) buf.samples()));
        buf.gain((float) (vel * (double) trackLevel.getFloat(trackId) * masterVol * 0.8));
        
        pan.pan((float) Math.max(-1.0, Math.min(1.0, masterPan + (double) stepPan.getFloat(idx))));

        // Update Sends
        ChuckArray stepDelay = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_DELAY);
        ChuckArray stepReverb = (ChuckArray) vm.getGlobalObject(BridgeContract.G_STEP_REVERB);
        ChuckArray gDelaySendArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_DELAY_SEND);
        ChuckArray gReverbSendArr = (ChuckArray) vm.getGlobalObject(BridgeContract.G_REVERB_SEND);

        delaySend.gain((float) ((double) gDelaySendArr.getFloat(trackId) + (double) stepDelay.getFloat(idx)));
        reverbSend.gain((float) ((double) gReverbSendArr.getFloat(trackId) + (double) stepReverb.getFloat(idx)));

        if (vm.getLogLevel() >= 2) {
            vm.print("KIT trigger track: " + trackId + " step: " + (idx % 16) + "\n");
        }
    }

    private void loadSample() {
        String path = (String) vm.getGlobalObject("g_sample_" + trackId);
        if (path != null && !path.isEmpty()) {
            try {
                buf.read(path);
                buf.rate(0);
                buf.pos(buf.samples());
            } catch (Exception e) {
                logger.warning("Failed to load sample on Track " + trackId + ": " + path);
            }
        }
    }
}
