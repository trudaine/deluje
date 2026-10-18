package org.deluge.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/** A muted track's clip keeps advancing, so un-muting brings it back in sync with the others. */
class MutedClipKeepsPositionTest {

  @Test
  void unmutedClipIsStillInSync() {
    ProjectModel p = new ProjectModel();
    SynthTrackModel a = new SynthTrackModel("A");
    ClipModel ca = new ClipModel("a", 1, 16);
    a.addClip(ca);
    a.setActiveClipIndex(0);
    SynthTrackModel b = new SynthTrackModel("B");
    ClipModel cb = new ClipModel("b", 1, 16);
    b.addClip(cb);
    b.setActiveClipIndex(0);
    p.addTrack(a);
    p.addTrack(b);
    PlaybackHandler pb = new PlaybackHandler();
    pb.setProject(p);
    pb.start();
    pb.advanceTicks(10);
    b.setMuted(true);
    pb.advanceTicks(50);
    b.setMuted(false);
    pb.advanceTicks(10);
    assertEquals(ca.lastProcessedPos, cb.lastProcessedPos);
    assertEquals(70, cb.lastProcessedPos);
  }
}
