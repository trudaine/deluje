package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.model.ProjectModel;
import org.deluge.playback.PlaybackHandler;
import org.junit.jupiter.api.Test;

/**
 * MIDI clock OUT: the transport drives external gear as master clock — 0xFA on start, one 0xF8 per
 * 4 internal ticks (24 PPQN out of our 96 PPQN), 0xFC on stop.
 */
public class MidiClockOutTest {

  private static final class CountingSink implements PlaybackHandler.MidiClockSink {
    int starts, stops, clocks;

    @Override
    public void start() {
      starts++;
    }

    @Override
    public void stop() {
      stops++;
    }

    @Override
    public void clock() {
      clocks++;
    }
  }

  @Test
  void emitsStartClocksAndStop() {
    PlaybackHandler h = new PlaybackHandler();
    h.setProject(new ProjectModel()); // no tracks → advanceTicks just advances the clock
    CountingSink sink = new CountingSink();
    h.setMidiClockOut(sink);

    h.start();
    assertEquals(1, sink.starts, "0xFA not sent on start");
    assertEquals(0, sink.clocks, "clock sent before any tick");

    h.advanceTicks(96); // one quarter note → 24 MIDI clocks
    assertEquals(24, sink.clocks, "expected 24 clocks per quarter (96/4)");

    h.advanceTicks(96); // another quarter
    assertEquals(48, sink.clocks);

    h.stop();
    assertEquals(1, sink.stops, "0xFC not sent on stop");
  }

  @Test
  void restartResetsClockPhase() {
    PlaybackHandler h = new PlaybackHandler();
    h.setProject(new ProjectModel());
    CountingSink sink = new CountingSink();
    h.setMidiClockOut(sink);

    h.start();
    h.advanceTicks(50); // 50/4 = 12 clocks (ticks 4..48)
    assertEquals(12, sink.clocks);
    h.stop();

    // Restart from the top: clock phase resets, so the next quarter is again exactly 24.
    h.start();
    assertEquals(2, sink.starts);
    h.advanceTicks(96);
    assertEquals(12 + 24, sink.clocks);
  }
}
