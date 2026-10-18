package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareSound;
import org.junit.jupiter.api.Test;

/**
 * C NoteRow::processCurrentPos (note_row.cpp:2187-2386): a note sounds when the processed position
 * equals its pos and stops at pos + length — including the notes at pos 0, which the playback
 * start's zero-increment processCurrentPos sounds. Reversed with note tails, a note starts at its
 * right edge and stops at its pos.
 */
class NoteRowSearchScanTimingTest {

  static final class PosRecordingSound extends FirmwareSound {
    final List<String> events = new ArrayList<>();
    ClipModel clip;

    @Override
    public void triggerNote(int note, int vel) {
      events.add("ON@" + clip.lastProcessedPos);
    }

    @Override
    public void releaseNote(int note) {
      events.add("OFF@" + clip.lastProcessedPos);
    }
  }

  private static List<String> run(ClipModel.PlayDirection dir, int ticks, int... posLen) {
    ClipModel clip = new ClipModel("t", 4, 4); // 96 ticks
    PosRecordingSound sound = new PosRecordingSound();
    sound.clip = clip;
    clip.setSound(sound);
    NoteRowModel row = clip.getOrCreateRow(0);
    row.setPitch(60);
    for (int i = 0; i < posLen.length; i += 2) {
      row.getNotes().add(new NoteModel(posLen[i], posLen[i + 1], 1.0f, 1.0f, 0));
    }
    clip.setPlayDirection(dir);

    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("S");
    track.addClip(clip);
    track.setActiveClipIndex(0);
    project.addTrack(track);
    org.deluge.playback.PlaybackHandler pb = new org.deluge.playback.PlaybackHandler();
    pb.setProject(project);
    pb.start();
    for (int i = 0; i < ticks; i++) {
      pb.advanceTicks(1);
    }
    return sound.events;
  }

  @Test
  void forwardNotesSoundOnTheirExactPos() {
    assertEquals(
        List.of("ON@0", "OFF@6", "ON@24", "OFF@30", "ON@50", "OFF@56"),
        run(ClipModel.PlayDirection.FORWARD, 95, 0, 6, 24, 6, 50, 6));
  }

  @Test
  void unsortedNotesAreSearchedInPosOrder() {
    assertEquals(
        List.of("ON@0", "OFF@6", "ON@24", "OFF@30", "ON@50", "OFF@56"),
        run(ClipModel.PlayDirection.FORWARD, 95, 50, 6, 0, 6, 24, 6));
  }

  @Test
  void reversedTailedNoteStartsAtItsRightEdge() {
    // Default synth sustains (tails allowed): [24, 36) starts at 36 and stops at 24.
    assertEquals(List.of("ON@36", "OFF@24"), run(ClipModel.PlayDirection.REVERSE, 95, 24, 12));
  }
}
