package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareSound;
import org.deluge.project.ProjectSerializer;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Verifies per-{@link NoteRowModel} {@link SequenceDirection} parity against C++ {@code NoteRow}
 * ({@code note_row.cpp:65, 2022-2117, 3260, 3583-3585, 4335-4337, 4453-4480}), {@code
 * ModelStackWithNoteRow} ({@code model_stack.cpp:63-107}), and {@code InstrumentClip::setPos}
 * ({@code instrument_clip.cpp:379-436}).
 */
class NoteRowSequenceDirectionParityTest {

  static final class RecordingSound extends FirmwareSound {
    final List<Integer> triggeredPitches = new ArrayList<>();
    final List<Integer> triggeredTicks = new ArrayList<>();
    int currentTick;

    @Override
    public void triggerNote(int note, int vel) {
      triggeredPitches.add(note);
      triggeredTicks.add(currentTick);
    }

    @Override
    public void releaseNote(int note) {}
  }

  private static NoteModel addNote(ClipModel clip, int rowIndex, int pitch, int pos, int len) {
    NoteRowModel row = clip.getOrCreateRow(rowIndex);
    row.setPitch(pitch);
    NoteModel note = new NoteModel();
    note.setPos(pos);
    note.setLength(len);
    note.setVelocity(100);
    note.setProbabilityValue(NoteModel.NUM_PROBABILITY_VALUES);
    note.setIterance(new Iterance());
    note.setFill(NoteModel.FILL_MODE_OFF);
    row.getNotes().add(note);
    return note;
  }

  private static void runTicks(ClipModel clip, RecordingSound sound, int totalTicks) {
    clip.setPos(0);
    sound.currentTick = 0;
    clip.processCurrentPos(0);
    for (int t = 1; t < totalTicks; t++) {
      sound.currentTick = t;
      clip.incrementPos(1);
      clip.processCurrentPos(1);
    }
  }

  @Test
  void reverseRowOnForwardClipPlaysBackwardsWithoutIndependentLength() {
    // 96-tick (4-step) FORWARD clip. Row 0 obeys parent (FORWARD); Row 1 overrides to REVERSE
    // without an independent length (loopLengthIfIndependent == 0, C note_row.cpp:4335-4337).
    ClipModel clip = new ClipModel("c", 2, 4);
    RecordingSound sound = new RecordingSound();
    clip.setSound(sound);

    addNote(clip, 0, 60, 0, 6);
    addNote(clip, 0, 60, 24, 6);
    addNote(clip, 0, 60, 48, 6);
    addNote(clip, 0, 60, 72, 6);

    NoteRowModel revRow = clip.getOrCreateRow(1);
    revRow.setSequenceDirectionMode(SequenceDirection.REVERSE);
    addNote(clip, 1, 72, 0, 6);
    addNote(clip, 1, 72, 24, 6);
    addNote(clip, 1, 72, 48, 6);
    addNote(clip, 1, 72, 72, 6);

    assertTrue(revRow.hasIndependentPlayPos());

    // Run 1 full 96-tick pass (ticks 0..95).
    // Forward row triggers at ticks 0, 24, 48, 72.
    // Reverse row (allowingNoteTails = true on default synth) triggers at note right-edges:
    // pos 72+6=78 -> tick 96-78=18; pos 48+6=54 -> tick 96-54=42; pos 24+6=30 -> tick 96-30=66;
    // pos 0+6=6 -> tick 96-6=90.
    runTicks(clip, sound, 96);

    List<Integer> fwdTicks = new ArrayList<>();
    List<Integer> revTicks = new ArrayList<>();
    for (int i = 0; i < sound.triggeredPitches.size(); i++) {
      if (sound.triggeredPitches.get(i) == 60) {
        fwdTicks.add(sound.triggeredTicks.get(i));
      } else if (sound.triggeredPitches.get(i) == 72) {
        revTicks.add(sound.triggeredTicks.get(i));
      }
    }

    assertEquals(List.of(0, 24, 48, 72), fwdTicks);
    assertEquals(List.of(18, 42, 66, 90), revTicks);
    assertEquals(0, revRow.getRepeatCount(clip.repeatCount));
  }

  @Test
  void pingpongRowOnForwardClipBouncesAndDrivesIteranceFromRowRepeatCount() {
    // 96-tick FORWARD clip. Row 0 has sequenceDirection = PINGPONG (loopLengthIfIndependent == 0).
    // A note at pos 0 with 1of2 iterance fires on pass 0 (forward) and pass 2 (forward), while a
    // note at pos 72 with 2of2 iterance fires on pass 1 (reverse) and pass 3 (reverse).
    ClipModel clip = new ClipModel("c", 1, 4);
    RecordingSound sound = new RecordingSound();
    clip.setSound(sound);

    NoteRowModel row = clip.getOrCreateRow(0);
    row.setSequenceDirectionMode(SequenceDirection.PINGPONG);
    addNote(clip, 0, 60, 0, 6).setIterance(new Iterance((byte) 2, (byte) 0b01)); // 1of2
    addNote(clip, 0, 60, 72, 6).setIterance(new Iterance((byte) 2, (byte) 0b10)); // 2of2

    // 4 passes * 96 ticks = 384 ticks.
    // Pass 0 (forward, ticks 0..95): pos 0 (1of2) fires at tick 0; pos 72 (2of2) skipped.
    // Pass 1 (reverse, ticks 96..191): pos 72+6=78 -> 96 + (96-78) = tick 114 (2of2 fires); pos 0
    // skipped.
    // Pass 2 (forward, ticks 192..287): pos 0 (1of2) fires at tick 192.
    // Pass 3 (reverse, ticks 288..383): pos 72+6=78 -> 288 + 18 = tick 306 (2of2 fires).
    runTicks(clip, sound, 384);

    assertEquals(List.of(0, 114, 192, 306), sound.triggeredTicks);
    assertEquals(3, row.getRepeatCount(clip.repeatCount));
  }

  @Test
  void setPosOnReverseClipPassesOriginalPosToForwardRow() {
    // C clip.h:58, instrument_clip.cpp:379-436 — Clip::setPos takes int32_t newPos by value, so
    // InstrumentClip::setPos passes the original newPos (not the clip's mirrored pos) to rows.
    ClipModel clip = new ClipModel("c", 1, 4); // 96 ticks
    clip.setPlayDirection(ClipModel.PlayDirection.REVERSE);

    NoteRowModel fwdRow = clip.getOrCreateRow(0);
    fwdRow.setSequenceDirectionMode(SequenceDirection.FORWARD);

    clip.setPos(24);
    // Clip is REVERSE, so its lastProcessedPos mirrors to 96 - 24 = 72.
    assertEquals(72, clip.lastProcessedPos);
    // Row is FORWARD, so its lastProcessedPosIfIndependent stays at the original 24.
    assertEquals(24, fwdRow.getLastProcessedPosIfIndependent());
    assertFalse(
        fwdRow.isCurrentlyPlayingReversed(
            clip.currentlyPlayingReversed, clip.sequenceDirectionMode));
  }

  @Test
  void clipDirectionFlipMidPlaybackAppliesToObeyParentRowButNotExplicitRow() {
    // C model_stack.cpp:63-76 — a row with sequenceDirectionMode == OBEY_PARENT and
    // loopLengthIfIndependent > 0 follows clip.currentlyPlayingReversed when the clip is not
    // PINGPONG; a row with explicit sequenceDirectionMode == FORWARD keeps moving forward.
    ClipModel clip = new ClipModel("c", 2, 4); // 96 ticks, FORWARD
    NoteRowModel obeyRow = clip.getOrCreateRow(0);
    obeyRow.setLoopLengthIfIndependent(48);

    NoteRowModel explicitFwdRow = clip.getOrCreateRow(1);
    explicitFwdRow.setLoopLengthIfIndependent(48);
    explicitFwdRow.setSequenceDirectionMode(SequenceDirection.FORWARD);

    clip.setPos(0);
    clip.processCurrentPos(0);
    for (int t = 0; t < 10; t++) {
      clip.incrementPos(1);
      clip.processCurrentPos(1);
    }

    // Flip parent clip to REVERSE mid-playback (clip.cpp:1041-1055), which flushes the 10 quiet
    // ticks into both independent rows before flipping clip.currentlyPlayingReversed.
    clip.setPlayDirection(ClipModel.PlayDirection.REVERSE);
    assertEquals(10, obeyRow.getLastProcessedPosIfIndependent());
    assertEquals(10, explicitFwdRow.getLastProcessedPosIfIndependent());
    assertTrue(
        obeyRow.isCurrentlyPlayingReversed(
            clip.currentlyPlayingReversed, clip.sequenceDirectionMode));
    assertFalse(
        explicitFwdRow.isCurrentlyPlayingReversed(
            clip.currentlyPlayingReversed, clip.sequenceDirectionMode));

    // Next tick (ticksTilNextNoteRowEvent was reset to 0 by setPlayDirection -> expectEvent):
    // obey-parent row moves backwards (10 -> 9); explicit-forward row moves forwards (10 -> 11).
    clip.incrementPos(1);
    clip.processCurrentPos(1);
    assertEquals(9, obeyRow.getLastProcessedPosIfIndependent());
    assertEquals(11, explicitFwdRow.getLastProcessedPosIfIndependent());
  }

  @Test
  void setRowSequenceDirectionModeMirrorsOnDirectionFlipAndCarriesOnPingpong() {
    // C note_row.cpp:4453-4480 — NoteRow::setSequenceDirectionMode mirrors
    // lastProcessedPosIfIndependent when switching to a non-pingpong mode that flips the playing
    // direction, and keeps reversedBefore without mirroring when switching to PINGPONG.
    ClipModel clip = new ClipModel("c", 1, 4); // 96 ticks, FORWARD
    NoteRowModel row = clip.getOrCreateRow(0);
    addNote(clip, 0, 60, 0, 6);

    clip.setPos(0);
    clip.processCurrentPos(0);
    for (int t = 0; t < 20; t++) {
      clip.incrementPos(1);
      clip.processCurrentPos(1);
    }
    assertEquals(20, clip.lastProcessedPos);
    assertFalse(row.hasIndependentPlayPos());

    // Switch row from OBEY_PARENT (forward at 20) to REVERSE -> mirrors to 96 - 20 = 76.
    clip.setRowSequenceDirectionMode(0, SequenceDirection.REVERSE, false);
    assertTrue(row.hasIndependentPlayPos());
    assertTrue(
        row.isCurrentlyPlayingReversed(clip.currentlyPlayingReversed, clip.sequenceDirectionMode));
    assertEquals(76, row.getLastProcessedPosIfIndependent());

    // Switch row from REVERSE (at 76) to PINGPONG -> keeps reversedBefore = true and pos = 76.
    clip.setRowSequenceDirectionMode(0, SequenceDirection.PINGPONG, false);
    assertTrue(
        row.isCurrentlyPlayingReversed(clip.currentlyPlayingReversed, clip.sequenceDirectionMode));
    assertEquals(76, row.getLastProcessedPosIfIndependent());

    // Switch row from PINGPONG (reversed at 76) back to OBEY_PARENT (clip is FORWARD) -> flips
    // reversed (true -> false) and mirrors 96 - 76 = 20.
    clip.setRowSequenceDirectionMode(0, SequenceDirection.OBEY_PARENT, false);
    assertFalse(row.hasIndependentPlayPos());
    assertFalse(
        row.isCurrentlyPlayingReversed(clip.currentlyPlayingReversed, clip.sequenceDirectionMode));
    assertEquals(20, row.getLastProcessedPosIfIndependent());
  }

  @Test
  void xmlAndSnapshotRoundTripPreserveRowSequenceDirectionMode() throws Exception {
    SynthTrackModel synth = new SynthTrackModel("S");
    ClipModel clip = new ClipModel("c", 3, 4);
    clip.setPlayDirection(ClipModel.PlayDirection.REVERSE);

    // Row 0: OBEY_PARENT (omitted in XML, C note_row.cpp:3583-3585).
    clip.setStep(0, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 60));
    clip.getOrCreateRow(0).setPitch(60);

    // Row 1: explicit FORWARD on a REVERSE clip, with independent length 48.
    clip.setStep(1, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 62));
    NoteRowModel row1 = clip.getOrCreateRow(1);
    row1.setPitch(62);
    row1.setLoopLengthIfIndependent(48);
    row1.setSequenceDirectionMode(SequenceDirection.FORWARD);

    // Row 2: explicit PINGPONG.
    clip.setStep(2, 0, StepData.of(true, 0.8f, 1.0f, 1.0f, 64));
    NoteRowModel row2 = clip.getOrCreateRow(2);
    row2.setPitch(64);
    row2.setSequenceDirectionMode(SequenceDirection.PINGPONG);

    synth.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);

    String xml = ProjectSerializer.serializeToString(project);
    assertTrue(xml.contains("sequenceDirection=\"forward\""));
    assertTrue(xml.contains("sequenceDirection=\"pingpong\""));
    assertFalse(xml.contains("sequenceDirection=\"none\""));
    assertFalse(xml.contains("sequenceDirection=\"obeyParent\""));

    ProjectModel parsed =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rt");
    ClipModel parsedClip = parsed.getTracks().get(0).getClips().get(0);
    assertEquals(
        SequenceDirection.OBEY_PARENT, parsedClip.getOrCreateRow(0).getSequenceDirectionMode());
    assertEquals(
        SequenceDirection.FORWARD, parsedClip.getOrCreateRow(1).getSequenceDirectionMode());
    assertEquals(48, parsedClip.getOrCreateRow(1).getLoopLengthIfIndependent());
    assertEquals(
        SequenceDirection.PINGPONG, parsedClip.getOrCreateRow(2).getSequenceDirectionMode());

    // PatternModel.ClipSnapshot round-trip.
    PatternModel.ClipSnapshot snap = PatternModel.ClipSnapshot.fromClipModel(parsedClip, 0, "S");
    ClipModel restoredViaSnap = new ClipModel("dst", 3, 4);
    snap.applyTo(restoredViaSnap);
    assertEquals(
        SequenceDirection.OBEY_PARENT,
        restoredViaSnap.getOrCreateRow(0).getSequenceDirectionMode());
    assertEquals(
        SequenceDirection.FORWARD, restoredViaSnap.getOrCreateRow(1).getSequenceDirectionMode());
    assertEquals(48, restoredViaSnap.getOrCreateRow(1).getLoopLengthIfIndependent());
    assertEquals(
        SequenceDirection.PINGPONG, restoredViaSnap.getOrCreateRow(2).getSequenceDirectionMode());

    // ClipModel.deepCopy + restoreFrom round-trip, including across a live playDirection change.
    ClipModel deepCopied = parsedClip.deepCopy("copy");
    parsedClip.setPlayDirection(ClipModel.PlayDirection.FORWARD);
    parsedClip.getOrCreateRow(1).setSequenceDirectionMode(SequenceDirection.OBEY_PARENT);
    parsedClip.restoreFrom(deepCopied);
    assertEquals(ClipModel.PlayDirection.REVERSE, parsedClip.getPlayDirection());
    assertEquals(
        SequenceDirection.FORWARD, parsedClip.getOrCreateRow(1).getSequenceDirectionMode());
  }

  @Test
  void emptyClipWithRowMetadataSerializesAndReloadsNoteRows() throws Exception {
    // C instrument_clip.cpp:2441-2445, note_row.cpp:3564-3585 — <noteRows> is written even when a
    // clip has zero active notes, so per-row sequenceDirection, length, and muted survive save.
    SynthTrackModel synth = new SynthTrackModel("S");
    ClipModel clip = new ClipModel("empty_meta", 2, 4);

    NoteRowModel row0 = clip.getOrCreateRow(0);
    row0.setPitch(60);
    row0.setLoopLengthIfIndependent(48);
    row0.setSequenceDirectionMode(SequenceDirection.REVERSE);

    NoteRowModel row1 = clip.getOrCreateRow(1);
    row1.setPitch(62);
    row1.setMuted(true);

    synth.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);

    String xml = ProjectSerializer.serializeToString(project);
    assertTrue(xml.contains("<noteRows>"));
    assertTrue(xml.contains("sequenceDirection=\"reverse\""));
    assertTrue(xml.contains("length=\"48\""));
    assertTrue(xml.contains("muted=\"1\""));

    ProjectModel parsed =
        DelugeXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "rt");
    ClipModel parsedClip = parsed.getTracks().get(0).getClips().get(0);
    assertEquals(
        SequenceDirection.REVERSE, parsedClip.getOrCreateRow(0).getSequenceDirectionMode());
    assertEquals(48, parsedClip.getOrCreateRow(0).getLoopLengthIfIndependent());
    assertTrue(parsedClip.getOrCreateRow(1).isMuted());
  }

  @Test
  void liveSnapshotAndRestoreReconcileIndependentRowPhaseAcrossStepCountChange() {
    // 4-step (96-tick) FORWARD clip with a REVERSE row (loopLengthIfIndependent == 0).
    ClipModel clip = new ClipModel("live", 1, 4);
    NoteRowModel row = clip.getOrCreateRow(0);
    row.setPitch(60);
    row.setSequenceDirectionMode(SequenceDirection.REVERSE);

    ClipModel fourStepSnapshot = clip.deepCopy("snap4");
    PatternModel.ClipSnapshot patternSnapshot =
        PatternModel.ClipSnapshot.fromClipModel(clip, 0, "S");

    // Lengthen clip to 8 steps (192 ticks) and advance 20 ticks into live playback.
    clip.setStepCount(8);
    clip.setPos(0);
    clip.processCurrentPos(0);
    for (int t = 0; t < 20; t++) {
      clip.incrementPos(1);
      clip.processCurrentPos(1);
    }

    // Undo back to 4 steps (96 ticks): effective row length changed from 192 -> 96, so the
    // REVERSE row must re-derive its position from linear tick 20 as 96 - 20 = 76 (not copy the
    // 192-tick row's 192 - 20 = 172).
    clip.restoreFrom(fourStepSnapshot);
    NoteRowModel restoredRow = clip.getOrCreateRow(0);
    assertEquals(76, restoredRow.getLastProcessedPosIfIndependent());
    assertTrue(
        restoredRow.isCurrentlyPlayingReversed(
            clip.currentlyPlayingReversed, clip.sequenceDirectionMode));

    // Clear the row's direction to OBEY_PARENT, then apply PatternModel.ClipSnapshot while live at
    // tick 20: applyTo must reconcile the restored REVERSE row to 96 - 20 = 76 and reversed = true.
    restoredRow.setSequenceDirectionMode(SequenceDirection.OBEY_PARENT);
    patternSnapshot.applyTo(clip);
    assertEquals(76, clip.getOrCreateRow(0).getLastProcessedPosIfIndependent());
    assertTrue(
        clip.getOrCreateRow(0)
            .isCurrentlyPlayingReversed(clip.currentlyPlayingReversed, clip.sequenceDirectionMode));
  }
}
