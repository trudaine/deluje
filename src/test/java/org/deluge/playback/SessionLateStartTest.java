package org.deluge.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ArmState;
import org.deluge.model.ClipModel;
import org.deluge.model.NoteModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * C Session::armClipsToStartOrSoloWithQuantization + armClipToStartOrSoloUsingQuantization
 * (session.cpp:1626-1647, 1862-1880): a late start puts the clip at the synced live position and
 * resumes playback (catching the note under the play-head); a launch point reached less than 50 ms
 * ago starts late on its own.
 */
class SessionLateStartTest {

  static final class Rec extends FirmwareSound {
    final List<String> events = new ArrayList<>();

    @Override
    public void triggerNote(int note, int vel) {
      events.add("ON");
    }

    @Override
    public void triggerNoteLate(int note, int vel, int samplesLate) {
      events.add("LATE");
    }
  }

  private ProjectModel project;
  private ClipModel mainClip;
  private ClipModel newClip;
  private SynthTrackModel newTrack;
  private Rec newSound;
  private PlaybackHandler ph;

  private void setUp() {
    project = new ProjectModel();
    SynthTrackModel mainTrack = new SynthTrackModel("Main");
    mainClip = new ClipModel("m", 1, 4); // 96 ticks
    mainTrack.addClip(mainClip);
    mainTrack.setActiveClipIndex(0);
    newTrack = new SynthTrackModel("New");
    newClip = new ClipModel("n", 1, 4); // 96 ticks
    newSound = new Rec();
    newClip.setSound(newSound);
    newClip.getOrCreateRow(0).setPitch(60);
    newClip.getOrCreateRow(0).getNotes().add(new NoteModel(0, 24, 1.0f, 1.0f, 0));
    newClip.setActiveInSession(false);
    newTrack.addClip(newClip);
    project.addTrack(mainTrack);
    project.addTrack(newTrack);
    ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();
  }

  @Test
  void shiftLaunchStartsAtTheSyncedPosition() {
    setUp();
    ph.advanceTicks(40);
    ph.toggleClipStatus(newTrack, newClip, true);
    assertTrue(newClip.isActiveInSession());
    assertEquals(40, newClip.lastProcessedPos, "synced to Main, not restarted at 0");
    assertFalse(ph.hasLaunchEvent());
  }

  @Test
  void launchJustAfterTheBoundaryStartsLateAndCatchesTheNote() {
    setUp();
    ph.advanceTicks(3); // 3 ticks = 689 samples < 2205
    ph.toggleClipStatus(newTrack, newClip, false);
    assertTrue(newClip.isActiveInSession(), "starts now instead of a bar later");
    assertEquals(ArmState.OFF, newClip.getArmState());
    ph.advanceTicks(1);
    // A wave synth may start late only within 50 ms, and then from the note's start (C :2500-2510).
    assertEquals(List.of("ON"), newSound.events);
  }

  @Test
  void launchWellAfterTheBoundaryIsQuantized() {
    setUp();
    ph.advanceTicks(30); // 30 ticks = 6890 samples
    ph.toggleClipStatus(newTrack, newClip, false);
    assertFalse(newClip.isActiveInSession());
    assertEquals(ArmState.ON_NORMAL, newClip.getArmState());
    assertTrue(ph.hasLaunchEvent());
  }
}
