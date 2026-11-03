package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A drum slot added while the transport runs inherits the kit's playback-clock state.
 *
 * <p>The C keeps no per-arpeggiator copy: {@code Arpeggiator::render} reads {@code
 * playbackHandler.isEitherClockActive()} at use time (arpeggiator.cpp:1527), so a {@code Drum}
 * created mid-playback sees the live value immediately. This port caches the flag on each
 * arpeggiator and {@code PlaybackHandler.setArpClockActive} only refreshes it on transport start
 * and stop — so a kit expanded by {@code applyModelToLiveSound} while playing would leave the new
 * arpeggiators at {@code false}, free-running their synced rate from the render loop while also
 * taking sequencer arp ticks.
 */
class KitArpNewDrumClockStateTest {

  @Test
  void drumsAddedDuringPlaybackSeeTheRunningClock() {
    FirmwareKit kit = new FirmwareKit();
    // What PlaybackHandler.setArpClockActive(true) does at transport start.
    kit.arpeggiator.playbackClockActive = true;
    for (FirmwareSound drum : kit.drumSounds) {
      drum.fw2Sound.arpeggiator.playbackClockActive = true;
    }

    int before = kit.drumSounds.size();
    kit.ensureDrumCount(before + 4);

    for (int i = before; i < kit.drumSounds.size(); i++) {
      assertTrue(
          kit.drumSounds.get(i).fw2Sound.arpeggiator.playbackClockActive,
          "drum " + i + " was added while the transport was running");
    }
  }
}
