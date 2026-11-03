package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.deluge.firmware2.Arpeggiator;
import org.deluge.model.ArpModel;
import org.junit.jupiter.api.Test;

/**
 * A saved arp's note pattern and locked randomizer sequences reach the DSP.
 *
 * <p>The C reads all eleven hex blobs straight into {@code ArpeggiatorSettings}
 * (arpeggiator.cpp:1663-1739, two hex chars per byte via {@code readTagOrAttributeValueHexBytes},
 * Deserializer.cpp:648) and writes them back on every save (`:1884-1919`). Here they round-tripped
 * through {@code ArpModel} and the serializers but never reached {@code Settings}, so {@code
 * randomizerLock} was honoured as a flag while the sequence it locks was regenerated on each load,
 * and PATTERN note mode always played a freshly randomized order.
 */
class ArpSavedSequenceRestoreTest {

  private static byte[] bytes(int... v) {
    byte[] b = new byte[Arpeggiator.RANDOMIZER_LOCK_MAX_SAVED_VALUES];
    for (int i = 0; i < v.length && i < b.length; i++) {
      b[i] = (byte) v[i];
    }
    return b;
  }

  @Test
  void savedPatternAndLockedArraysAreRestored() {
    // 16 bytes, two hex chars each, exactly as writeAttributeHexBytes emits them.
    String patternHex = "000102030405060708090A0B0C0D0E0F";
    String lockHex = "1F0000000000000000000000000000FF";

    ArpModel arp =
        ArpModel.defaultConfig().toBuilder()
            .active(true)
            .mode("arp")
            .randomizerLock(1)
            .notePattern(patternHex)
            .lastLockedNoteProb(123456)
            .lockedNoteProbArray(lockHex)
            .lastLockedOctaveSpread(-99)
            .lockedOctaveSpreadArray(lockHex)
            .build();

    Arpeggiator.Settings s = new Arpeggiator.Settings();
    FirmwareFactory.configureArpSettings(s, arp, 0, /* restoreSavedSequences= */ true);

    assertArrayEquals(
        bytes(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F),
        s.notePattern,
        "the saved note pattern must reach Settings");
    assertEquals(123456, s.lastLockedNoteProbabilityParameterValue);
    assertArrayEquals(
        bytes(0x1F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF),
        s.lockedNoteProbabilityValues,
        "the saved locked note-probability sequence must reach Settings");
    assertEquals(-99, s.lastLockedSpreadOctaveParameterValue);
    assertArrayEquals(
        bytes(0x1F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xFF),
        s.lockedSpreadOctaveValues,
        "the last of the ten arrays must be wired too, not just the first");
  }

  @Test
  void anAbsentPatternLeavesTheGeneratedOne() {
    // C arpeggiator.cpp:69 — the constructor generates a pattern, and :1737 only overwrites it when
    // the attribute is present. ArpModel carries null for "the file had no notePattern".
    Arpeggiator.Settings s = new Arpeggiator.Settings();
    s.generateNewNotePattern();
    byte[] generated = s.notePattern.clone();

    FirmwareFactory.configureArpSettings(
        s,
        ArpModel.defaultConfig().toBuilder().active(true).notePattern(null).build(),
        0,
        /* restoreSavedSequences= */ true);

    assertArrayEquals(
        generated, s.notePattern, "an absent pattern must not zero the generated one");
    boolean allZero = true;
    for (byte b : generated) {
      allZero &= b == 0;
    }
    assertFalse(allZero, "the generated pattern must not be all zeros, or this proves nothing");
  }
}
