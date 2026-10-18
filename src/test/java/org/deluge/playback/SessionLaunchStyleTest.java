package org.deluge.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.model.ArmState;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * Tests for session {@code launchStyle} ({@code default} / {@code fill} / {@code once}) behavior
 * ported from {@code playback/mode/session.cpp} and {@code model/song/song.cpp}.
 */
class SessionLaunchStyleTest {

  private static ClipModel makeClip(String name, int steps, String launchStyle, int section) {
    ClipModel clip = new ClipModel(name, 8, steps);
    clip.setLaunchStyle(launchStyle);
    clip.setSection(section);
    return clip;
  }

  @Test
  void onceClip_playsThroughOnceAndStops() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track0 = new SynthTrackModel("Synth 0");
    ClipModel baseClip = makeClip("Base", 4, "default", 0); // 4 * 24 = 96 ticks
    ClipModel onceClip = makeClip("Once", 4, "once", 1); // 96 ticks
    onceClip.setActiveInSession(false);
    track0.addClip(baseClip);
    track0.addClip(onceClip);
    project.addTrack(track0);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // Advance 20 ticks into baseClip, then arm onceClip to launch at the next loop boundary (96).
    ph.advanceTicks(20);
    ph.toggleClipStatus(track0, onceClip, false);
    assertEquals(ArmState.ON_NORMAL, onceClip.getArmState());
    assertEquals(96L, ph.getLaunchEventAtSwungTickCount());

    // Advance to tick 96: onceClip launches and immediately re-arms itself to stop after 96 ticks.
    ph.advanceTicks(76);
    assertEquals(96, ph.lastSwungTickActioned);
    assertFalse(baseClip.isActiveInSession());
    assertTrue(onceClip.isActiveInSession());
    assertEquals(1, track0.getActiveClipIndex());
    assertEquals(ArmState.ON_NORMAL, onceClip.getArmState(), "ONCE clip arms itself to stop");
    assertEquals(192L, ph.getLaunchEventAtSwungTickCount(), "scheduled to stop after one loop");

    // Advance through the full 96-tick loop of onceClip to tick 192: onceClip stops.
    ph.advanceTicks(96);
    assertEquals(192, ph.lastSwungTickActioned);
    assertFalse(onceClip.isActiveInSession(), "ONCE clip stopped after one play-through");
    assertEquals(ArmState.OFF, onceClip.getArmState());
    assertFalse(ph.hasLaunchEvent());
  }

  @Test
  void fillClip_rightAlignsToLaunchEventAndStopsAtBoundary() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel mainTrack = new SynthTrackModel("Main");
    ClipModel mainA = makeClip("Main A", 8, "default", 0); // 192 ticks
    ClipModel mainB = makeClip("Main B", 8, "default", 1); // 192 ticks
    mainB.setActiveInSession(false);
    mainTrack.addClip(mainA);
    mainTrack.addClip(mainB);

    SynthTrackModel fillTrack = new SynthTrackModel("Fills");
    ClipModel fillClip = makeClip("Fill 1", 2, "fill", 1); // 48 ticks
    fillClip.setActiveInSession(false);
    fillTrack.addClip(fillClip);

    project.addTrack(mainTrack);
    project.addTrack(fillTrack);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // At tick 20, arm mainB (launches at tick 192) and arm fillClip (48 ticks long -> starts 144).
    ph.advanceTicks(20);
    ph.toggleClipStatus(mainTrack, mainB, false);
    ph.toggleClipStatus(fillTrack, fillClip, false);

    assertEquals(192L, ph.getLaunchEventAtSwungTickCount());
    assertEquals(144L, fillClip.getFillEventAtTickCount(), "192 - 48 = 144");
    assertEquals(ArmState.ON_NORMAL, fillClip.getArmState());

    // Advance to tick 143: fill has not started yet.
    ph.advanceTicks(123);
    assertFalse(fillClip.isActiveInSession());

    // Advance to tick 144: fill launch fires without disturbing mainA or mainB's armState.
    ph.advanceTicks(1);
    assertEquals(144, ph.lastSwungTickActioned);
    assertTrue(fillClip.isActiveInSession(), "fill clip started at fillEventAtTickCount");
    assertEquals(0, fillClip.lastProcessedPos, "fill clip started at pos 0");
    assertEquals(ArmState.ON_NORMAL, fillClip.getArmState(), "fill clip re-armed to stop at 192");
    assertTrue(mainA.isActiveInSession(), "non-fill active clip untouched by fill launch");
    assertEquals(ArmState.ON_NORMAL, mainB.getArmState(), "non-fill armed clip still armed");

    // Advance to tick 192: mainB launches and fillClip stops cleanly.
    ph.advanceTicks(48);
    assertEquals(192, ph.lastSwungTickActioned);
    assertFalse(fillClip.isActiveInSession(), "fill clip stopped at launchEvent");
    assertEquals(ArmState.OFF, fillClip.getArmState());
    assertTrue(mainB.isActiveInSession());
    assertFalse(mainA.isActiveInSession());
  }

  @Test
  void fillClip_startsImmediatelyMidClipWhenLaunchEventCloserThanMaxLength() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel mainTrack = new SynthTrackModel("Main");
    ClipModel mainA = makeClip("Main A", 4, "default", 0); // 96 ticks
    ClipModel mainB = makeClip("Main B", 4, "default", 1); // 96 ticks
    mainB.setActiveInSession(false);
    mainTrack.addClip(mainA);
    mainTrack.addClip(mainB);

    SynthTrackModel fillTrack = new SynthTrackModel("Fills");
    ClipModel fillClip = makeClip("Fill", 2, "fill", 1); // 48 ticks
    fillClip.setActiveInSession(false);
    fillTrack.addClip(fillClip);

    project.addTrack(mainTrack);
    project.addTrack(fillTrack);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // Advance to tick 70 (only 26 ticks until launch at 96, less than fill's 48-tick length).
    ph.advanceTicks(70);
    ph.toggleClipStatus(mainTrack, mainB, false);
    ph.scheduleFillClip(fillTrack, fillClip);

    // Fill starts immediately at pos = 48 - (96 - 70) = 22, armed to stop at tick 96.
    assertTrue(fillClip.isActiveInSession());
    assertEquals(22, fillClip.lastProcessedPos);
    assertEquals(ArmState.ON_NORMAL, fillClip.getArmState());

    ph.advanceTicks(26);
    assertEquals(96, ph.lastSwungTickActioned);
    assertFalse(fillClip.isActiveInSession());
    assertEquals(ArmState.OFF, fillClip.getArmState());
  }

  @Test
  void fillClip_refusesToStealTrackWithActiveNonFillClip() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Shared");
    ClipModel nonFill = makeClip("Playing", 4, "default", 0); // 96 ticks, active
    ClipModel fill = makeClip("Fill", 2, "fill", 1); // 48 ticks, same track
    fill.setActiveInSession(false);
    track.addClip(nonFill);
    track.addClip(fill);
    project.addTrack(track);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    ph.setLaunchEvent(96, 1);
    ph.scheduleFillClip(track, fill);

    // Since fillStartTime = 48 >= 0, it sets fillEventAtTickCount = 48, but at tick 48
    // doLaunch(true)
    // sees an active non-fill clip on the same track and disarms the fill clip
    // (session.cpp:325-329).
    ph.advanceTicks(48);
    assertTrue(nonFill.isActiveInSession(), "non-fill clip keeps the track");
    assertFalse(fill.isActiveInSession(), "fill clip refused to steal active non-fill track");
    assertEquals(ArmState.OFF, fill.getArmState());
  }

  @Test
  void toggleClipStatus_disarmsPendingFillAndInstantStopsPlayingFill() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel mainTrack = new SynthTrackModel("Main");
    ClipModel mainClip = makeClip("Main", 8, "default", 0); // 192 ticks
    mainTrack.addClip(mainClip);

    SynthTrackModel fillTrack = new SynthTrackModel("FillTrack");
    ClipModel fillClip = makeClip("Fill", 2, "fill", 0); // 48 ticks
    fillClip.setActiveInSession(false);
    fillTrack.addClip(fillClip);

    project.addTrack(mainTrack);
    project.addTrack(fillTrack);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    ph.setLaunchEvent(192, 1);
    ph.scheduleFillClip(fillTrack, fillClip);
    assertEquals(ArmState.ON_NORMAL, fillClip.getArmState());
    assertEquals(144L, fillClip.getFillEventAtTickCount());

    // Pressing an armed-to-start fill clip disarms it (session.cpp:1009-1013).
    ph.toggleClipStatus(fillTrack, fillClip, false);
    assertEquals(ArmState.OFF, fillClip.getArmState());
    assertEquals(0L, fillClip.getFillEventAtTickCount());

    // Schedule again and advance to tick 144 so fillClip is now playing on fillTrack.
    ph.setLaunchEvent(192, 1);
    ph.scheduleFillClip(fillTrack, fillClip);
    ph.advanceTicks(144);
    assertTrue(fillClip.isActiveInSession());
    assertEquals(ArmState.ON_NORMAL, fillClip.getArmState());

    // Pressing a playing fill clip stops it instantly even without Shift (session.cpp:1095).
    ph.toggleClipStatus(fillTrack, fillClip, false);
    assertFalse(fillClip.isActiveInSession());
    assertEquals(ArmState.OFF, fillClip.getArmState());
  }

  @Test
  void armSection_skipsFillInNormalPass_preservesOnceStopArm_andSchedulesSectionFills() {
    ProjectModel project = new ProjectModel();

    SynthTrackModel t0 = new SynthTrackModel("Track 0");
    ClipModel t0Sec0 = makeClip("T0 S0", 4, "default", 0); // 96 ticks, active
    ClipModel t0Sec1 = makeClip("T0 S1", 4, "default", 1); // 96 ticks, inactive
    t0Sec1.setActiveInSession(false);
    t0.addClip(t0Sec0);
    t0.addClip(t0Sec1);

    SynthTrackModel t1 = new SynthTrackModel("Track 1");
    ClipModel onceClip = makeClip("Once S1", 4, "once", 1); // active ONCE clip in section 1
    onceClip.setArmState(ArmState.ON_NORMAL); // already armed to stop
    t1.addClip(onceClip);

    SynthTrackModel t2 = new SynthTrackModel("Track 2");
    ClipModel fillClip = makeClip("Fill S1", 1, "fill", 1); // 24-tick fill in section 1
    fillClip.setActiveInSession(false);
    t2.addClip(fillClip);

    project.addTrack(t0);
    project.addTrack(t1);
    project.addTrack(t2);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();
    // Re-apply onceClip's stop-arming after start() so we test section.cpp:1737.
    onceClip.setArmState(ArmState.ON_NORMAL);

    ph.advanceTicks(10);
    ph.armSection(1, true);

    // t0Sec1 armed normally; onceClip kept its stop arming (session.cpp:1737); fillClip scheduled
    // via scheduleFillClips(1) for tick 96 - 24 = 72 (session.cpp:1668, 1691, 1974-1989).
    assertEquals(ArmState.ON_NORMAL, t0Sec1.getArmState());
    assertEquals(
        ArmState.ON_NORMAL, onceClip.getArmState(), "active ONCE clip stays armed to stop");
    assertEquals(72L, fillClip.getFillEventAtTickCount());
    assertEquals(ArmState.ON_NORMAL, fillClip.getArmState());
  }

  @Test
  void projectModel_skipsFillInLongestMultipleOrFactor_andPreservesFillOnceArmInSoloClear() {
    ProjectModel project = new ProjectModel();

    SynthTrackModel t0 = new SynthTrackModel("T0");
    ClipModel normal96 = makeClip("Normal 96", 4, "default", 0); // 96 ticks
    t0.addClip(normal96);

    SynthTrackModel t1 = new SynthTrackModel("T1");
    ClipModel fill192 = makeClip("Fill 192", 8, "fill", 0); // 192 ticks, active FILL
    t1.addClip(fill192);

    SynthTrackModel t2 = new SynthTrackModel("T2");
    ClipModel once96 = makeClip("Once 96", 4, "once", 0);
    t2.addClip(once96);

    project.addTrack(t0);
    project.addTrack(t1);
    project.addTrack(t2);

    // song.cpp:4450 — getLongestActiveClipWithMultipleOrFactorLength skips FILL clips.
    ClipModel longest = project.getLongestActiveClipWithMultipleOrFactorLength(96, true, null);
    assertSame(normal96, longest);
    assertNull(
        project.getLongestActiveClipWithMultipleOrFactorLength(96, true, normal96) == fill192
            ? fill192
            : null);

    // song.cpp:3293 — turnSoloingIntoJustPlaying(true) preserves armState on FILL and ONCE clips.
    normal96.setArmState(ArmState.ON_NORMAL);
    fill192.setArmState(ArmState.ON_NORMAL);
    once96.setArmState(ArmState.ON_NORMAL);

    project.turnSoloingIntoJustPlaying(true);

    assertEquals(ArmState.OFF, normal96.getArmState());
    assertEquals(ArmState.ON_NORMAL, fill192.getArmState());
    assertEquals(ArmState.ON_NORMAL, once96.getArmState());
  }

  @Test
  void armingAndDisarmingSiblingClip_preservesOnceAndFillStopArming() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth");
    ClipModel onceClip = makeClip("Once", 4, "once", 0); // 96 ticks, active
    ClipModel sibling = makeClip("Sibling", 4, "default", 1); // 96 ticks, inactive
    sibling.setActiveInSession(false);
    track.addClip(onceClip);
    track.addClip(sibling);
    project.addTrack(track);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // Launch onceClip instantly so it is active and armed to stop at tick 96.
    onceClip.setActiveInSession(false);
    ph.toggleClipStatus(track, onceClip, true);
    assertTrue(onceClip.isActiveInSession());
    assertEquals(ArmState.ON_NORMAL, onceClip.getArmState());
    assertEquals(96L, ph.getLaunchEventAtSwungTickCount());

    // Arm sibling, then disarm sibling: onceClip must keep its stop-arming (session.cpp:2008).
    ph.advanceTicks(16);
    ph.toggleClipStatus(track, sibling, false);
    assertEquals(ArmState.ON_NORMAL, sibling.getArmState());
    assertEquals(ArmState.ON_NORMAL, onceClip.getArmState(), "active ONCE keeps stop-arming");

    ph.toggleClipStatus(track, sibling, false);
    assertEquals(ArmState.OFF, sibling.getArmState());
    assertEquals(ArmState.ON_NORMAL, onceClip.getArmState(), "active ONCE still armed to stop");
    assertTrue(ph.hasLaunchEvent(), "launch event stays scheduled for active ONCE clip");

    ph.advanceTicks(80);
    assertEquals(96, ph.lastSwungTickActioned);
    assertFalse(onceClip.isActiveInSession(), "ONCE clip stopped on schedule at tick 96");
  }

  @Test
  void fillClip_startsAtPosZeroOnExactBoundaryTick() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel mainTrack = new SynthTrackModel("Main");
    ClipModel mainClip = makeClip("Main", 2, "default", 0); // 48 ticks
    mainTrack.addClip(mainClip);

    SynthTrackModel fillTrack = new SynthTrackModel("FillTrack");
    ClipModel fillClip = makeClip("Fill", 2, "fill", 1); // 48 ticks (maxLength == launchEvent)
    fillClip.setActiveInSession(false);
    fillTrack.addClip(fillClip);

    project.addTrack(mainTrack);
    project.addTrack(fillTrack);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // At tick 0 the launch point is now (pos 0): C armClipsToStartOrSoloWithQuantization
    // (session.cpp:1633-1643) turns a launch point reached < kAmountNoteOnLatenessAllowed ago into
    // a late start, FILL clips included (`launchStyle == DEFAULT || !doLateStart`). The late start
    // (:1862-1880) activates the clip at pos 0 with no launch event, so scheduleFillClip (:1909)
    // does not arm it to stop.
    ph.toggleClipStatus(fillTrack, fillClip, false);
    assertTrue(fillClip.isActiveInSession(), "fill starts immediately at tick 0");
    assertEquals(0, fillClip.lastProcessedPos, "fill starts at pos 0 with no 1-tick delay");
    assertEquals(ArmState.OFF, fillClip.getArmState());
    assertFalse(ph.hasLaunchEvent());
  }

  @Test
  void singleClipFillArm_disarmsInactiveSibling_andRejectedImmediateFillClearsOrphanLaunch() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel mainTrack = new SynthTrackModel("Main");
    ClipModel mainClip = makeClip("Main", 4, "default", 0); // 96 ticks, active
    ClipModel mainFill = makeClip("Main Fill", 4, "fill", 1); // 96 ticks, same track as active
    mainFill.setActiveInSession(false);
    mainTrack.addClip(mainClip);
    mainTrack.addClip(mainFill);

    SynthTrackModel fillTrack = new SynthTrackModel("FillTrack");
    ClipModel sibling = makeClip("Armed Sibling", 4, "default", 1);
    sibling.setActiveInSession(false);
    ClipModel fillClip = makeClip("Fill", 2, "fill", 1); // 48 ticks
    fillClip.setActiveInSession(false);
    fillTrack.addClip(sibling);
    fillTrack.addClip(fillClip);

    project.addTrack(mainTrack);
    project.addTrack(fillTrack);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // Case A: rejected immediate fill on mainTrack cleans up orphaned launchEventAtSwungTickCount.
    ph.advanceTicks(10); // 86 ticks til boundary < 96-tick mainFill -> immediate branch
    ph.toggleClipStatus(mainTrack, mainFill, false);
    assertFalse(mainFill.isActiveInSession());
    assertEquals(ArmState.OFF, mainFill.getArmState());
    assertFalse(ph.hasLaunchEvent(), "orphaned launchEvent cleared when immediate fill rejected");

    // Case B: single-clip FILL arm on fillTrack disarms another inactive armed clip on fillTrack.
    ph.toggleClipStatus(fillTrack, sibling, false);
    assertEquals(ArmState.ON_NORMAL, sibling.getArmState());
    ph.toggleClipStatus(fillTrack, fillClip, false);
    assertEquals(
        ArmState.OFF, sibling.getArmState(), "inactive sibling disarmed by single-clip FILL");
    assertEquals(ArmState.ON_NORMAL, fillClip.getArmState());
    assertEquals(48L, fillClip.getFillEventAtTickCount());
  }
}
