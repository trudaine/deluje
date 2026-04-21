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

        // Persistent loop: wait for ticks regardless of play state
        while (true) {
            advance(tickEvent);
            
            // Sync current step to bridge
            int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
            if (step < 0) continue;

            int idx = trackId * 16 + step;

            ChuckArray pattern = (ChuckArray) vm.getGlobalObject(BridgeContract.G_PATTERN);
            ChuckArray mute = (ChuckArray) vm.getGlobalObject(BridgeContract.G_MUTE);
            ChuckArray trackType = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_TYPE);

            if (trackType == null || trackType.getInt(trackId) != 0) continue;
            if (mute == null || mute.getInt(trackId) != 0) continue;
            if (pattern == null || pattern.getInt(idx) == 0) continue;

            trigger(idx);
        }
    }

    private void trigger(int idx) {
        ChuckArray velocity = (ChuckArray) vm.getGlobalObject(BridgeContract.G_VELOCITY);
        ChuckArray trackLevel = (ChuckArray) vm.getGlobalObject(BridgeContract.G_TRACK_LEVEL);

        double vel = velocity != null ? velocity.getFloat(idx) : 0.8;
        double masterVol = vm.getGlobalFloat(BridgeContract.G_MASTER_VOL);

        buf.rate(1.0f);
        buf.pos(0L); 
        buf.gain((float) (vel * (trackLevel != null ? trackLevel.getFloat(trackId) : 0.7) * masterVol));

        if (vm.getLogLevel() >= 2) {
            vm.print("KIT trigger track: " + trackId + " step: " + (idx % 16) + "\n");
        }
    }

    private void loadSample() {
        String path = (String) vm.getGlobalObject("g_sample_" + trackId);
        if (path != null && !path.isEmpty()) {
            buf.read(path);
            if (buf.samples() == 0) {
                // Try case-insensitive extension fallback
                String altPath = path;
                if (path.endsWith(".wav")) altPath = path.substring(0, path.length() - 4) + ".WAV";
                else if (path.endsWith(".WAV")) altPath = path.substring(0, path.length() - 4) + ".wav";
                else if (path.endsWith(".XML")) altPath = path.substring(0, path.length() - 4) + ".xml";
                else if (path.endsWith(".xml")) altPath = path.substring(0, path.length() - 4) + ".XML";
                
                if (!altPath.equals(path)) {
                    buf.read(altPath);
                }
            }
            
            // Post-load setup
            if (buf.samples() > 0) {
                buf.rate(0);
                buf.pos(buf.samples());
            } else {
                logger.warning("Track " + trackId + ": Failed to load sample " + path);
            }
        }
    }
}
