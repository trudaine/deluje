package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.Arpeggiator;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.deluge.model.ProjectModel;
import org.junit.jupiter.api.Test;

/**
 * A synced kit arpeggiator runs at the song's tempo.
 *
 * <p>C {@code ArpeggiatorSettings::getPhaseIncrement} reads {@code
 * playbackHandler.getTimePerInternalTickInverse()} (arpeggiator.cpp:1600-1601) — one song-wide
 * value, read at use time. {@code firmware2.Kit} has no handle on the playback handler, so {@link
 * FirmwareAudioEngine} pushes the song's value in every block. Sourcing it from drum 0's cached
 * copy instead left the kit arp on the fixed {@code 1 << 20} proxy for every kit whose first drum
 * had never been synced — at 120 BPM a 5.9x too-slow arp.
 */
class KitArpSongClockTest {

  private static final class RecKit extends FirmwareKit {
    final List<String> hits = new ArrayList<>();

    @Override
    protected void drumNoteOn(int row, int vel, int samplesLate) {
      super.drumNoteOn(row, vel, samplesLate);
      hits.add("ON" + row);
    }
  }

  @Test
  void aSyncedKitArpTakesItsRateFromTheSongNotFromDrumZero() {
    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.syncMasterEffects(project);
    // 120 BPM at the default input tick magnitude 2: 2^31 / (165375 / (120 * 4)) samples per
    // internal tick. Far from the 1 << 20 = 1048576 no-clock proxy.
    assertEquals(6233059, engine.songTimePerInternalTickInverse, "song tick inverse at 120 BPM");

    RecKit kit = new RecKit();
    kit.arpSettings.mode = Arpeggiator.ArpMode.ARP;
    kit.arpSettings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_16TH;
    // Drum 1 holds the note and drum 0 is never synced — the state that froze the arp's rate.
    FirmwareSound drum = kit.drumSounds.get(1);
    drum.oscTypes[0] = OscType.SAW;
    drum.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 0x7FFFFFFF;
    drum.paramNeutralValues[Param.GLOBAL_VOLUME_POST_FX] = 0x7FFFFFFF;
    drum.syncParamsToFw2();
    engine.sounds.add(kit);

    kit.triggerDrum(1, 100);
    kit.hits.clear();

    // One arp period is maxGate (1 << 24) / ((phaseIncrement >>> 8) * 128) blocks of 128 samples:
    // 86 at the song's rate, 512 at the 1 << 20 proxy. 180 blocks therefore retriggers twice at
    // the song's tempo and not once at the proxy's.
    for (int block = 0; block < 180; block++) {
      engine.renderBlock(128);
    }

    assertTrue(
        kit.hits.size() >= 2,
        "a synced kit arp must retrigger at the song's tempo; got " + kit.hits);
    assertEquals(
        6233059,
        kit.timePerInternalTickInverse,
        "the engine must push the song's tick inverse into the kit each block");
  }
}
