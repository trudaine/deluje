package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.model.ArpModel;
import org.junit.jupiter.api.Test;

/**
 * The model's 0..1 arp gate maps onto the C's SIGNED q31 {@code UNPATCHED_ARP_GATE}.
 *
 * <p>The consumer adds 2^31 — {@code gateThreshold = arpSettings.gate + Integer.MIN_VALUE}
 * (Sound.java:1544, C sound.cpp:2468 / arpeggiator.cpp:1457) — and the C's {@code initParams}
 * default is 0 (mod_controllable_audio.cpp:113-127), so 0 is the 50% gate and {@code
 * Integer.MIN_VALUE} is 0%. Scaling by 2^31-1 instead put the whole range in the top half, and
 * because the result then matched {@code getUnpatchedArpDefault}'s 0 at neither end, {@code
 * FirmwareSound.syncParamsToFw2}'s "no override" test left it in place: a freshly configured drum
 * played a 75% gate, which changed to 50% after a save and reload.
 */
class ArpGateQ31MappingTest {

  /** C arpeggiator.cpp:1457 — gateThreshold >>> 8 against maxGate = 1 << 24. */
  private static int gatePercent(int settingsGate) {
    long threshold = (settingsGate + Integer.MIN_VALUE) & 0xFFFFFFFFL;
    return (int) Math.round(100.0 * (threshold >>> 8) / (1 << 24));
  }

  @Test
  void theDefaultGateIsFiftyPercentAndTheEndpointsAreZeroAndOneHundred() {
    org.deluge.firmware2.Arpeggiator.Settings s = new org.deluge.firmware2.Arpeggiator.Settings();

    FirmwareFactory.configureArpSettings(
        s, ArpModel.defaultConfig().toBuilder().gate(0.5f).build(), 0);
    assertEquals(0, s.gate, "0.5 is the param midpoint, the C's initParams default");
    assertEquals(50, gatePercent(s.gate), "a default gate must play 50%, not 75%");

    FirmwareFactory.configureArpSettings(
        s, ArpModel.defaultConfig().toBuilder().gate(0f).build(), 0);
    assertEquals(Integer.MIN_VALUE, s.gate);
    assertEquals(0, gatePercent(s.gate), "a zero gate must play 0%, not 50%");

    FirmwareFactory.configureArpSettings(
        s, ArpModel.defaultConfig().toBuilder().gate(1f).build(), 0);
    assertEquals(100, gatePercent(s.gate));
  }

  @Test
  void aDefaultDrumKeepsTheFiftyPercentGateThroughSyncParamsToFw2() {
    FirmwareKit kit = new FirmwareKit();
    org.deluge.model.SoundDrum sd = new org.deluge.model.SoundDrum("GateDrum");
    org.deluge.model.KitTrackModel model = new org.deluge.model.KitTrackModel("GateKit");
    model.getDrums().add(sd);
    FirmwareFactory.applyModelToLiveSound(model, kit);

    FirmwareSound drum = kit.drumSounds.get(0);
    drum.syncParamsToFw2();
    assertEquals(
        50,
        gatePercent(drum.fw2Sound.arpSettings.gate),
        "the drum's gate must survive syncParamsToFw2 at the param default");
  }
}
