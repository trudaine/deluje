package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Lockstep guard for the bridge's silent landmine: {@code FirmwareSound.syncParamsToFw2} maps patch
 * cables with {@code fc.source = c.from.ordinal()}, i.e. it assumes {@code
 * org.chuck.deluge.firmware.modulation.patch.PatchSource} and {@code org.chuck.deluge.firmware2
 * .PatchSource} have IDENTICAL constants in IDENTICAL order. If anyone reorders/adds/removes a
 * constant in one enum but not the other, every patch cable silently re-sources to the wrong
 * modulator (no compiler error) — exactly the class of bug found in the 2026-06 Bridge audit
 * ({@code lfo1} had been mapped to the wrong source). This test turns that into a build failure.
 *
 * <p>Also pins the {@link org.chuck.deluge.firmware.modulation.patch.PatchCable.Polarity} →
 * firmware2 polarity-constant mapping the bridge relies on.
 */
class PatchSourceLockstepTest {

  @Test
  void patchSourceEnumsAreInLockstep() {
    var bridgeValues = org.chuck.deluge.firmware.modulation.patch.PatchSource.values();
    var fw2Values = org.chuck.deluge.firmware2.PatchSource.values();

    assertEquals(
        fw2Values.length,
        bridgeValues.length,
        "firmware and firmware2 PatchSource must have the same number of constants — the bridge"
            + " indexes fw2 sourceValues by firmware.PatchSource.ordinal()");

    for (var bridge : bridgeValues) {
      org.chuck.deluge.firmware2.PatchSource fw2 =
          org.chuck.deluge.firmware2.PatchSource.valueOf(bridge.name());
      assertNotNull(fw2, "firmware2.PatchSource missing constant " + bridge.name());
      assertEquals(
          bridge.ordinal(),
          fw2.ordinal(),
          "PatchSource '"
              + bridge.name()
              + "' has different ordinals (firmware="
              + bridge.ordinal()
              + ", firmware2="
              + fw2.ordinal()
              + ") — c.from.ordinal() would mis-source every cable using it");
    }
  }

  @Test
  void patchCablePolarityMappingHolds() {
    // syncParamsToFw2: c.polarity == UNIPOLAR ? PatchCable.UNIPOLAR : PatchCable.BIPOLAR
    assertEquals(
        2,
        org.chuck.deluge.firmware.modulation.patch.PatchCable.Polarity.values().length,
        "bridge polarity has exactly UNIPOLAR/BIPOLAR");
    org.chuck.deluge.firmware2.Patcher.PatchCable fc =
        new org.chuck.deluge.firmware2.Patcher.PatchCable();
    fc.polarity = org.chuck.deluge.firmware2.Patcher.PatchCable.UNIPOLAR;
    org.chuck.deluge.firmware2.Patcher.PatchCable fc2 =
        new org.chuck.deluge.firmware2.Patcher.PatchCable();
    fc2.polarity = org.chuck.deluge.firmware2.Patcher.PatchCable.BIPOLAR;
    assertEquals(
        1, fc.polarity, "fw2 UNIPOLAR constant value (bridge maps Polarity.UNIPOLAR to this)");
    assertEquals(
        0, fc2.polarity, "fw2 BIPOLAR constant value (bridge maps Polarity.BIPOLAR to this)");
  }
}
