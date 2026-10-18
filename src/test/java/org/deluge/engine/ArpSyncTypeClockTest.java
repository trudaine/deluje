package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.BridgeContract;
import org.deluge.firmware2.Arpeggiator;
import org.junit.jupiter.api.Test;

/**
 * The bridge arp clock applies the sync type like C ArpeggiatorSettings::getPhaseIncrement
 * (arpeggiator.cpp:1605-1610): a triplet's increment is 3/2 of the straight one, a dotted note's
 * 2/3 (issue #34).
 */
class ArpSyncTypeClockTest {

  private static int incrementFor(Arpeggiator.SyncType type) {
    PureFirmwareEngine engine = new PureFirmwareEngine();
    FirmwareSound sound = new FirmwareSound();
    sound.arpDivision = 16;
    sound.arpSettings.syncType = type;
    engine.getAudioEngine().sounds.add(sound);
    BridgeContract bridge = new BridgeContract();
    bridge.setGlobalFloat(BridgeContract.G_BPM, 120.0);
    engine.syncFromBridge(bridge);
    bridge.shutdown();
    return sound.fw2Sound.arpPhaseIncrement;
  }

  @Test
  void tripletAndDottedScaleTheStraightIncrement() {
    long even = incrementFor(Arpeggiator.SyncType.EVEN);
    long triplet = incrementFor(Arpeggiator.SyncType.TRIPLET);
    long dotted = incrementFor(Arpeggiator.SyncType.DOTTED);
    // 16th at 120 BPM: 5512.5 samples per step.
    assertEquals((long) (4294967296.0 / 5512.5), even);
    assertEquals(even * 3 / 2, triplet, 1);
    assertEquals(even * 2 / 3, dotted, 1);
  }
}
