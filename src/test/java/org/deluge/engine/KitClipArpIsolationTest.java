package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Arpeggiator;
import org.deluge.model.ArpModel;
import org.junit.jupiter.api.Test;

/**
 * A kit clip's arpeggiator is the clip's own, with no fallback to a sibling's.
 *
 * <p>C {@code Kit::getArpSettings} returns {@code &clip->arpSettings} (kit.cpp:787-791) and every
 * {@code InstrumentClip} owns one at constructor defaults, so a kit clip whose XML carries no
 * {@code <arpeggiator>} plays with the arp OFF. {@code KitTrackModel.arp} is only a copy of
 * whichever clip parsed first, so using it as a fallback let one clip's arp leak into another — in
 * the engine and, on save, into the file.
 */
class KitClipArpIsolationTest {

  @Test
  void aClipWithoutAnArpDoesNotInheritASiblingsAndIsNotSilencedByItsRandomizers() {
    FirmwareKit kit = new FirmwareKit();

    // Clip 1: an active arp whose note probability is 0 — the state that used to leak.
    ArpModel clip1Arp =
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .noteProbability(0.0f)
            .octaves(3)
            .build();
    FirmwareFactory.configureKitArp(kit, clip1Arp, 0);
    assertEquals(Arpeggiator.ArpMode.ARP, kit.arpSettings.mode, "clip 1 must arpeggiate");
    assertEquals(0, kit.arpSettings.noteProbability, "clip 1 sets 0% note probability");

    // Clip 2 carries no <arpeggiator>: the C gives it constructor defaults, i.e. the arp OFF.
    FirmwareFactory.configureKitArp(kit, null, 0);
    assertEquals(Arpeggiator.ArpMode.OFF, kit.arpSettings.mode, "clip 2 must default to OFF");
    assertEquals(
        0xFFFFFFFF,
        kit.arpSettings.noteProbability,
        "clip 2 must not inherit clip 1's 0% probability — it silenced every drum");
    assertFalse(kit.arpSettings.randomizerLock, "clip 2 must not inherit the randomizer lock");

    // And the hit actually sounds again.
    kit.triggerDrum(0, 100);
    assertTrue(
        kit.drumSounds.get(0).getActiveVoiceCount() > 0,
        "a drum must sound on a clip whose XML has no arpeggiator");
  }
}
