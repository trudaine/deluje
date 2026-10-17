package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.firmware2.Arpeggiator;
import org.deluge.model.ArpModel;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * The ARP panel's octave modes reach the fw2 settings as the C enum: "ALT" is
 * ArpOctaveMode::ALTERNATE and "RAND" is RANDOM (C stringToArpOctaveMode, functions.cpp). They used
 * to map to UP_DOWN and UP. Also checks the SyncLevel the fw2 ratchet / ticks-per-period code reads
 * follows the model's division.
 */
class ArpModeMappingTest {

  private static Arpeggiator.Settings settingsFor(ArpModel arp) {
    SynthTrackModel synth = new SynthTrackModel("S");
    synth.setArp(arp);
    synth.addClip(new ClipModel("c", 1, 16));
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);
    FirmwareFactory.createSong(project);
    return ((FirmwareSound) synth.getClips().get(0).getSound()).arpSettings;
  }

  private static ArpModel on() {
    return ArpModel.defaultConfig().toBuilder().active(true).build();
  }

  @Test
  void octaveModesMapToTheCEnum() {
    assertEquals(
        Arpeggiator.ArpOctaveMode.ALTERNATE,
        settingsFor(on().toBuilder().octaveMode("ALT").build()).octaveMode);
    assertEquals(
        Arpeggiator.ArpOctaveMode.RANDOM,
        settingsFor(on().toBuilder().octaveMode("RAND").build()).octaveMode);
    assertEquals(
        Arpeggiator.ArpOctaveMode.UP_DOWN,
        settingsFor(on().toBuilder().octaveMode("UPDN").build()).octaveMode);
  }

  @Test
  void mpeYAndSyncLevelReachTheSettings() {
    Arpeggiator.Settings s = settingsFor(on().toBuilder().mpeVelocity(2).syncLevel(32).build());
    assertEquals(Arpeggiator.ArpMpeModSource.MPE_Y, s.mpeVelocity);
    // A 32nd at the default magnitude 2 is internal level log2(32) + 4 - 2 = 7.
    assertEquals(Arpeggiator.SyncLevel.SYNC_LEVEL_64TH, s.syncLevel);
    // Not synced → SYNC_LEVEL_NONE, the C free-rate path.
    assertEquals(
        Arpeggiator.SyncLevel.SYNC_LEVEL_NONE,
        settingsFor(on().toBuilder().syncLevel(0).build()).syncLevel);
  }
}
