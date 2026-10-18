package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.firmware2.Arpeggiator.ArpMode;
import org.deluge.firmware2.Arpeggiator.SyncLevel;
import org.deluge.firmware2.Arpeggiator.SyncType;
import org.junit.jupiter.api.Test;

/**
 * C SoundInstrument::doTickForwardForArp (sound_instrument.cpp:413-456): a synced arp switches its
 * notes on from the sequencer tick, on multiples of ticksPerPeriod = 3 << (9 - syncLevel)
 * (arpeggiator.cpp:1561-1572).
 */
class SyncedArpTickTest {

  @Test
  void syncedArpNoteOnsLandOnThePeriodGrid() {
    Sound s = new Sound();
    s.arpSettings.mode = ArpMode.ARP;
    s.arpSettings.syncLevel = SyncLevel.SYNC_LEVEL_16TH;
    s.arpSettings.syncType = SyncType.EVEN;
    s.arpeggiator.playbackClockActive = true;
    int period = 3 << (9 - SyncLevel.SYNC_LEVEL_16TH.ordinal());

    Arpeggiator.ArpReturnInstruction in = new Arpeggiator.ArpReturnInstruction();
    s.arpeggiator.noteOn(s.arpSettings, 60, 100, in, Arpeggiator.MIDI_CHANNEL_NONE, null);

    int noteOns = 0;
    for (int pos = 0; pos < period * 4; pos++) {
      int before = s.voices.size();
      int ticksTilNext = s.doTickForwardForArp(pos, false);
      boolean started = s.voices.size() > before || (pos % period == 0 && !s.voices.isEmpty());
      if (pos % period == 0) {
        assertEquals(period, ticksTilNext, "at a period boundary the next event is a period away");
        assertTrue(started, "synced arp must switch a note on at pos " + pos);
        noteOns++;
      } else {
        assertEquals(period - (pos % period), ticksTilNext);
        assertEquals(before, s.voices.size(), "no note-on off the grid at pos " + pos);
      }
    }
    assertEquals(4, noteOns);
  }
}
