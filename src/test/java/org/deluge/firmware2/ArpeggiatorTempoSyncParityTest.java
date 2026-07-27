package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test for arpeggiator tempo synchronization and clock wiring (§4.2vicesquinquies).
 * Verifies that tempo-synced arpeggiators (e.g. 159 80s Bass Rhythm and 112 Hard Tech Beat) derive their
 * phase increment from the song's actual timePerInternalTickInverse rather than fixed standalone proxies,
 * maintaining 100% C-compatibility with arpeggiator.cpp:1402-1422 and sound.cpp:2378.
 */
public class ArpeggiatorTempoSyncParityTest {

  @Test
  public void testSyncedPhaseIncrementFormula() {
    Arpeggiator.Settings settings = new Arpeggiator.Settings();
    settings.syncLevel = Arpeggiator.SyncLevel.SYNC_LEVEL_16TH; // ordinal 5 (16th notes)
    settings.syncType = Arpeggiator.SyncType.EVEN;

    int arpRate = 0; // Unused when syncLevel != NONE
    int proxyTickInverse = 1 << 20; // 1048576 (old standalone proxy)
    int actual120BpmTickInverse = 6233062; // 120 BPM actual timePerInternalTickInverse

    int proxyInc = settings.getPhaseIncrement(arpRate, proxyTickInverse);
    int actualInc = settings.getPhaseIncrement(arpRate, actual120BpmTickInverse);

    // rightShiftAmount = 9 - 5 = 4
    assertEquals(1048576 >> 4, proxyInc, "Proxy phase increment must match right-shifted 2^20");
    assertEquals(6233062 >> 4, actualInc, "Actual phase increment must match right-shifted timePerInternalTickInverse");
    assertTrue(
        actualInc > proxyInc * 5,
        "Actual 120 BPM tempo-synced increment is ~5.9x faster than the legacy static proxy");
  }

  @Test
  public void testArpeggiatorSongTempoWiring() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("TestArp");
    synth.setArp(synth.getArp().toBuilder().mode("UP").syncLevel(5).build()); // 16th notes

    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();

    fs.syncParamsToFw2();

    assertNotEquals(
        0,
        fs.fw2Sound.arpPhaseIncrement,
        "syncParamsToFw2 must populate fw2Sound.arpPhaseIncrement for synced arpeggiators");

    int expectedInc = fs.fw2Sound.arpSettings.getPhaseIncrement(0, fs.fw2Sound.timePerInternalTickInverse);
    assertEquals(
        expectedInc,
        fs.fw2Sound.arpPhaseIncrement,
        "fw2Sound.arpPhaseIncrement must exactly match C++ arpeggiator.cpp:1408-1421 tempo formula");
  }
}
