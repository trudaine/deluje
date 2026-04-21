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
        advance(ms(10)); // Stagger initialization

        // 2. Start Individual Track Processors (8 Tracks)
        for (int i = 0; i < 8; i++) {
            KitTrackProcessor kitProc = new KitTrackProcessor(i, vm, bridge);
            SynthTrackProcessor synthProc = new SynthTrackProcessor(i, vm, bridge);
            vm.spork(kitProc::shred);
            vm.spork(synthProc::shred);
            advance(ms(5)); // Stagger initialization
        }

        // 3. Start Master Clock (Persistent)
        vm.spork(new DelugeClock(vm, bridge)::shred);

        logger.info("Deluge Engine (Distributed) initialized.");
        
        // Keep orchestrator alive
        while(true) advance(ms(100));
    }
}
