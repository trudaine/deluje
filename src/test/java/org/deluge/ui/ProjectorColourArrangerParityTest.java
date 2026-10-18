package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.util.List;
import org.deluge.BridgeContract;
import org.deluge.model.ArrangerClip;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.junit.jupiter.api.Test;

class ProjectorColourArrangerParityTest {

  @Test
  void arrangementProjector_drawsHeadWhenClipStartsMidColumn() {
    int zoom = 192; // 192 ticks per column
    ClipModel clipA = new ClipModel("A", 8, 16);
    clipA.setSection(0);
    clipA.setLoopLength(zoom);

    ClipModel clipB = new ClipModel("B", 8, 16);
    clipB.setSection(1);
    clipB.setLoopLength(zoom);

    // Clip A starts at tick 48 (mid-column 0) and lasts 2 columns
    ArrangerClip acMid = new ArrangerClip(0, clipA, 48, 2 * zoom);
    int[] rowToTrack = {0};

    PadCell[][] cells = ArrangementProjector.project(List.of(acMid), rowToTrack, 1, 16, 0, zoom);
    assertTrue(cells[0][0].active(), "mid-column start still lights column 0");
    assertEquals(
        DelugeColour.sectionColour(0),
        cells[0][0].colour(),
        "mid-column start renders HEAD colour (C arranger_view.cpp:2375)");

    // When two clips overlap in column 1, the latest startTicks < squareEndPos wins
    ArrangerClip acEarly = new ArrangerClip(0, clipA, 0, 2 * zoom);
    ArrangerClip acLate = new ArrangerClip(0, clipB, zoom + 24, zoom);
    PadCell[][] overlap =
        ArrangementProjector.project(List.of(acEarly, acLate), rowToTrack, 1, 16, 0, zoom);
    assertEquals(
        DelugeColour.sectionColour(1),
        overlap[0][1].colour(),
        "latest startTicks < colEnd wins (C arranger_view.cpp:2348)");
  }

  @Test
  void songProjector_kitUsesRowIndexPitchAndWrapsHeldNoteTail() {
    // 1. Kit clip uses yNote = row index r (C instrument_clip.cpp:1487-1489)
    ClipModel kitClip = new ClipModel("Kit", 8, 8);
    kitClip.setIsKit(true);
    kitClip.setRowYNote(2, 60); // melodic pitch should be ignored when isKit is true
    kitClip.setStep(2, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 60));

    PadCell[][] kitCells =
        SongProjector.project(List.of(new SongProjector.Row(kitClip, 0)), 1, 8, 0);
    Color expectedKitMain = DelugeColour.fromHue(2 * -8 / 3);
    int num = 65 + 127 + 127 / 2;
    Color expectedKitHead =
        new Color(
            Math.min(255, expectedKitMain.getRed() * num / 255),
            Math.min(255, expectedKitMain.getGreen() * num / 255),
            Math.min(255, expectedKitMain.getBlue() * num / 255));
    assertEquals(expectedKitHead, kitCells[0][0].colour());

    // 2. Wrapped note tail (C note_row.cpp:1976-1991): note at step 6 with gate 4.0 wraps 2 steps
    // into steps 0 and 1 (6 + 4 - 8 = 2 > 0, 1).
    ClipModel melodic = new ClipModel("Synth", 4, 8);
    melodic.setRowYNote(0, 60);
    melodic.setStep(0, 6, StepData.of(true, 1.0f, 4.0f, 1.0f, 60));

    PadCell[][] wrappedCells =
        SongProjector.project(List.of(new SongProjector.Row(melodic, 0)), 1, 8, 0);
    Color expectedTail = DelugePadButton.getTailColor(DelugeColour.fromHue(60 * -8 / 3));
    assertEquals(expectedTail, wrappedCells[0][0].colour(), "wrapped tail at step 0");
    assertEquals(expectedTail, wrappedCells[0][1].colour(), "wrapped tail at step 1");
    assertNull(wrappedCells[0][2].colour(), "wrapped tail ends before step 2");

    // 3. Row with allowNoteTails == false (e.g. zero-sustain percussive sound, C note_row.cpp:1984)
    // draws neither normal nor wrapped tails.
    org.deluge.engine.FirmwareSound oneShotSound = new org.deluge.engine.FirmwareSound();
    oneShotSound.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_ENV_0_SUSTAIN] =
        Integer.MIN_VALUE;
    oneShotSound.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_ENV_0_DECAY] = 0;
    oneShotSound.paramNeutralValues[org.deluge.firmware2.Param.LOCAL_ENV_0_RELEASE] = 0;
    melodic.setSound(oneShotSound);

    PadCell[][] oneShotCells =
        SongProjector.project(List.of(new SongProjector.Row(melodic, 0)), 1, 8, 0);
    assertNull(oneShotCells[0][0].colour(), "one-shot row must not draw wrapped tail at step 0");
    assertNull(oneShotCells[0][7].colour(), "one-shot row must not draw normal tail at step 7");
    assertTrue(oneShotCells[0][6].active(), "one-shot row still draws note head at step 6");
  }

  @Test
  void delugeColour_sectionColourSupportsAll24HardwareSections() {
    // Sections 16..23 come from defaultClipSectionColours[16..23] (C session.cpp:78-85),
    // not repeating sections 0..7.
    for (int s = 16; s < 24; s++) {
      assertNotEquals(
          DelugeColour.sectionColour(s - 16),
          DelugeColour.sectionColour(s),
          "section " + s + " must not wrap to section " + (s - 16));
    }
    assertEquals(
        DelugePadButton.getTailColor(new Color(255, 44, 50)),
        DelugeColour.sectionColour(16),
        "section 16 = pink.forTail()");
    assertEquals(
        DelugePadButton.getTailColor(new Color(128, 0, 128)),
        DelugeColour.sectionColour(23),
        "section 23 = magenta.forTail()");
    // Section 24 wraps to section 0
    assertEquals(DelugeColour.sectionColour(0), DelugeColour.sectionColour(24));
  }

  @Test
  void arrangerPlaybackScheduler_ignoresOutOfBoundsCaptureTrackIndex() {
    BridgeContract bridge = new BridgeContract();
    ProjectModel project = new ProjectModel();
    ArrangerPlaybackScheduler scheduler = new ArrangerPlaybackScheduler(bridge, project);
    try {
      scheduler.setCaptureActive(true);
      ClipModel clip = new ClipModel("Clip", 8, 16);

      assertDoesNotThrow(() -> scheduler.notifyClipLaunched(-1, clip));
      assertDoesNotThrow(() -> scheduler.notifyClipLaunched(BridgeContract.TRACKS, clip));
      assertDoesNotThrow(() -> scheduler.notifyClipStopped(-1));
      assertDoesNotThrow(() -> scheduler.notifyClipStopped(BridgeContract.TRACKS));
    } finally {
      scheduler.shutdown();
    }
  }
}
