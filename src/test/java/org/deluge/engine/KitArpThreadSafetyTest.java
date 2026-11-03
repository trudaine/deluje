package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.deluge.firmware2.Arpeggiator;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.junit.jupiter.api.Test;

/**
 * The kit arpeggiator's three entry points are mutually exclusive.
 *
 * <p>The C declares its {@code ArpReturnInstruction} on the stack of each of {@code
 * noteOnPreKitArp} (kit.cpp:1342), {@code setupAndRenderArpPreOutput} (kit.cpp:729) and {@code
 * doTickForwardForArp} (kit.cpp:1159) and shares one {@code arpeggiator} between them, which is
 * safe there because the audio render and the MIDI/pad handling run on one cooperative loop. Here a
 * live audition is on the Swing EDT, the render on the audio thread and the tick on the sequencer
 * thread, all against one {@code kitInstruction} — so the entry points take the Kit's monitor, and
 * a clip switch rewriting the Settings takes the same one.
 */
class KitArpThreadSafetyTest {

  /** Parks inside a note-on, i.e. half-way through {@code triggerDrum}'s critical section. */
  private static final class ParkingKit extends FirmwareKit {
    final CountDownLatch reachedNoteOn = new CountDownLatch(1);
    final CountDownLatch mayFinishNoteOn = new CountDownLatch(1);

    /**
     * Only the audition thread parks: parking the sequencer too would pass for the wrong reason.
     */
    volatile Thread parkOn;

    @Override
    protected void drumNoteOn(int row, int vel, int samplesLate) {
      if (Thread.currentThread() == parkOn) {
        reachedNoteOn.countDown();
        try {
          mayFinishNoteOn.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      super.drumNoteOn(row, vel, samplesLate);
    }
  }

  @Test
  void aTickCannotRunWhileALiveNoteOnIsInFlight() throws Exception {
    ParkingKit kit = new ParkingKit();
    kit.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    kit.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_16TH;
    // No transport: the arp outputs the audition immediately rather than holding it PENDING.
    kit.arpeggiator.playbackClockActive = false;
    FirmwareSound drum = kit.drumSounds.get(1);
    drum.oscTypes[0] = OscType.SAW;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 0x7FFFFFFF;
    drum.paramNeutralValues[Param.GLOBAL_VOLUME_POST_FX] = 0x7FFFFFFF;
    drum.syncParamsToFw2();

    Thread audition = new Thread(() -> kit.triggerDrum(1, 100), "audition");
    kit.parkOn = audition;
    audition.start();
    assertTrue(
        kit.reachedNoteOn.await(5, TimeUnit.SECONDS), "the audition must reach its drum note-on");

    CountDownLatch tickReturned = new CountDownLatch(1);
    Thread sequencer =
        new Thread(
            () -> {
              kit.doTickForwardForKitArp(0, false);
              tickReturned.countDown();
            },
            "sequencer");
    sequencer.start();

    // The tick resets and reads the same kitInstruction the audition is mid-way through filling.
    assertFalse(
        tickReturned.await(500, TimeUnit.MILLISECONDS),
        "a sequencer tick must not enter the kit arp while a live note-on holds it");

    kit.mayFinishNoteOn.countDown();
    assertTrue(tickReturned.await(5, TimeUnit.SECONDS), "the tick must proceed once released");
    audition.join(5000);
    sequencer.join(5000);
  }
}
