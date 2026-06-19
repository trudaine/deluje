package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Regression test for the arp rhythm value scaling (value_scaling.cpp:18/60-62): the stored
 * settings.rhythm is the raw uint32 menu value; the index must round-trip for all 51 patterns
 * (arpeggiator_rhythms.h) — this is what the factory now wires from XML to fw2.
 */
class ArpRhythmMappingTest {

  @Test
  void menuValueRoundTripsForAllRhythmIndexes() {
    for (int idx = 0; idx <= Functions.K_MAX_MENU_VALUE; idx++) {
      int raw = Functions.computeFinalValueForUnsignedMenuItem(idx);
      int back = Functions.computeCurrentValueForUnsignedMenuItem(raw);
      assertEquals(idx, back, "rhythm index " + idx + " should round-trip through the raw value");
    }
  }
}
