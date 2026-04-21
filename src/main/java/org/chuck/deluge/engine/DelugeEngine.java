package org.chuck.deluge.engine;

import org.chuck.core.ChuckVM;
import org.chuck.core.Shred;
import org.chuck.deluge.BridgeContract;
import java.util.logging.Logger;
import static org.chuck.core.ChuckDSL.*;

/**
 * Orchestrator for the Distributed Shred Engine.
 * Sporks the Clock, FX Bus, and individual Track Processors.
 */
public class DelugeEngine implements Shred {
    private static final Logger logger = Logger.getLogger(DelugeEngine.class.getName());

    private final ChuckVM vm;
    private final BridgeContract bridge;
    
    public DelugeEngine(ChuckVM vm, BridgeContract bridge) {
        this.vm = vm;
        this.bridge = bridge;
    }

    @Override
    public void shred() {
        logger.info("Starting Distributed Deluge Engine...");

        // 1. Start Global FX Bus
        vm.spork(new DelugeFxBus(vm, bridge)::shred);

        // 2. Start Individual Track Processors (8 Tracks)
        for (int i = 0; i < 8; i++) {
            KitTrackProcessor kitProc = new KitTrackProcessor(i, vm, bridge);
            SynthTrackProcessor synthProc = new SynthTrackProcessor(i, vm, bridge);
            vm.spork(kitProc::shred);
            vm.spork(synthProc::shred);
        }

        // 3. Start Transport Monitor (Handles Play/Stop and Clock lifecycle)
        vm.spork(this::transportMonitor);

        logger.info("Deluge Engine (Distributed) initialized.");
    }

    private void transportMonitor() {
        boolean lastPlay = false;
        int clockShredId = -1;

        while (true) {
            boolean play = vm.getGlobalInt(BridgeContract.G_PLAY) == 1;
            if (play != lastPlay) {
                lastPlay = play;
                if (play) {
                    logger.info("TRANSPORT: Play");
                    DelugeClock clock = new DelugeClock(vm, bridge);
                    clockShredId = vm.spork(clock::shred);
                } else {
                    logger.info("TRANSPORT: Stop");
                    if (clockShredId != -1) {
                        vm.removeShred(clockShredId);
                        clockShredId = -1;
                    }
                    vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1);
                }
            }
            advance(ms(10));
        }
    }
}
