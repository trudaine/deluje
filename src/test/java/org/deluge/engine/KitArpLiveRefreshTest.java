package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.firmware2.Arpeggiator;
import org.deluge.model.ArpModel;
import org.junit.jupiter.api.Test;

/**
 * Re-applying the same clip's kit model does not re-default its arpeggiator.
 *
 * <p>The C runs {@code ArpeggiatorSettings}' constructor once per {@code InstrumentClip} and only
 * re-points {@code Kit::getArpSettings} afterwards (kit.cpp:787-791). This port shares one Settings
 * per kit and re-defaults it on a clip switch — but {@code configureKitArp} is also the ordinary
 * live-param-refresh path, which {@code SwingKitConfigDialog} drives at 5 Hz
 * (SwingKitConfigDialog.java:90). Re-defaulting on every call re-randomized {@code notePattern} and
 * zeroed the locked randomizer arrays five times a second.
 */
class KitArpLiveRefreshTest {

  @Test
  void repeatedLiveApplyKeepsThePatternAndLockedRandomizerState() {
    FirmwareKit kit = new FirmwareKit();
    Object clip = new Object();
    ArpModel arp = ArpModel.defaultConfig().toBuilder().active(true).mode("arp").build();
    FirmwareFactory.configureKitArp(kit, arp, 0, clip);

    // Stand in for state a clip accumulates while it plays: a note order and a locked sequence.
    byte[] pattern = kit.arpSettings.notePattern.clone();
    kit.arpSettings.lockedNoteProbabilityValues[0] = 42;
    kit.arpSettings.lastLockedNoteProbabilityParameterValue = 12345;

    for (int refresh = 0; refresh < 5; refresh++) {
      FirmwareFactory.configureKitArp(kit, arp, 0, clip);
    }

    assertArrayEquals(
        pattern, kit.arpSettings.notePattern, "a live refresh must not re-randomize notePattern");
    assertEquals(
        42,
        kit.arpSettings.lockedNoteProbabilityValues[0],
        "a live refresh must not wipe the locked randomizer array");
    assertEquals(
        12345,
        kit.arpSettings.lastLockedNoteProbabilityParameterValue,
        "a live refresh must not wipe the locked randomizer snapshot");

    // A real clip switch still re-defaults, which is what kit.cpp:787-791 gives a new clip.
    FirmwareFactory.configureKitArp(kit, null, 0, new Object());
    assertEquals(Arpeggiator.ArpMode.OFF, kit.arpSettings.mode, "a clip without an arp plays OFF");
    assertEquals(
        0,
        kit.arpSettings.lockedNoteProbabilityValues[0],
        "a clip switch must start from constructor defaults");

    // ...and a clip switch is where the next clip's SAVED sequence gets installed.
    ArpModel saved =
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .randomizerLock(1)
            .lastLockedNoteProb(777)
            .lockedNoteProbArray("0700000000000000000000000000000E")
            .build();
    FirmwareFactory.configureKitArp(kit, saved, 0, new Object());
    assertEquals(777, kit.arpSettings.lastLockedNoteProbabilityParameterValue);
    assertEquals(
        7,
        kit.arpSettings.lockedNoteProbabilityValues[0],
        "a clip switch must install the new clip's saved locked sequence");
  }
}
