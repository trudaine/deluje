package org.chuck.deluge.engine;

import static org.chuck.core.ChuckDSL.*;

import java.util.logging.Logger;
import org.chuck.core.ChuckVM;
import org.chuck.core.Shred;
import org.chuck.deluge.BridgeContract;

/**
 * Orchestrator for the Distributed Shred Engine. Sporks the Clock, FX Bus, and individual Track
 * Processors.
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

    // 2. Start Individual Track Processors
    for (int i = 0; i < BridgeContract.TRACKS; i++) {
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
    org.chuck.core.ChuckShred current = org.chuck.core.ChuckShred.CURRENT_SHRED.get();
    while (current != null && !current.isDone()) {
      advance(ms(100));
    }
  }
}
