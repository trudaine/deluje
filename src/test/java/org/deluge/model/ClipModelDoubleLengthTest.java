package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pins {@link ClipModel#doubleLength()}: the clip's pattern is copied into the new second half. */
class ClipModelDoubleLengthTest {

  @Test
  void doubleLength_copiesPatternIntoSecondHalf() {
    ClipModel clip = new ClipModel("t", 4, 8);
    clip.setStep(0, 0, StepData.of(true, 0.5f, 1.0f, 1.0f, 60));
    clip.setStep(2, 3, StepData.of(true, 0.9f, 1.0f, 1.0f, 62));

    clip.doubleLength();

    assertEquals(16, clip.getStepCount(), "length doubles 8 -> 16");

    // Originals stay put.
    assertTrue(clip.getStep(0, 0).active());
    assertTrue(clip.getStep(2, 3).active());

    // Copied into the second half at +8.
    assertTrue(clip.getStep(0, 8).active(), "step (0,0) mirrored to (0,8)");
    assertEquals(0.5f, clip.getStep(0, 8).velocity(), 1e-6, "velocity carried across");
    assertTrue(clip.getStep(2, 11).active(), "step (2,3) mirrored to (2,11)");
    assertEquals(0.9f, clip.getStep(2, 11).velocity(), 1e-6);

    // Empty stays empty.
    assertFalse(clip.getStep(1, 8).active(), "an empty lane stays empty in the copy");
    assertFalse(clip.getStep(0, 9).active());
  }

  @Test
  void doubleLength_onEmptyClipJustDoublesLength() {
    ClipModel clip = new ClipModel("t", 4, 16);
    clip.doubleLength();
    assertEquals(32, clip.getStepCount());
  }

  @Test
  void clipContentConsequence_undoRestoresNotesLostToShrink() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth");
    ClipModel clip = new ClipModel("t", 4, 16);
    clip.setStep(0, 0, StepData.of(true, 0.7f, 1.0f, 1.0f, 60));
    clip.setStep(2, 12, StepData.of(true, 0.9f, 1.0f, 1.0f, 62)); // lives past step 8
    track.addClip(clip);
    project.addTrack(track);

    ClipModel before = clip.deepCopy(clip.getName());
    clip.setStepCount(8); // shrink drops the note at step 12
    ClipModel after = clip.deepCopy(clip.getName());
    Consequence.ClipContentConsequence cons =
        new Consequence.ClipContentConsequence(project, 0, 0, before, after);

    assertEquals(8, clip.getStepCount());
    assertFalse(clip.getStep(2, 12).active(), "note beyond the new end is gone after shrink");

    cons.undo();
    assertEquals(16, clip.getStepCount(), "undo restores the original length");
    assertTrue(clip.getStep(0, 0).active());
    assertTrue(clip.getStep(2, 12).active(), "the discarded note is restored by undo");

    cons.redo();
    assertEquals(8, clip.getStepCount());
    assertFalse(clip.getStep(2, 12).active());
  }

  @Test
  void clipLengthConsequence_undoRedoRestoresState() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth");
    ClipModel clip = new ClipModel("t", 4, 8);
    clip.setStep(0, 0, StepData.of(true, 0.5f, 1.0f, 1.0f, 60));
    track.addClip(clip);
    project.addTrack(track);

    int oldLen = clip.getStepCount();
    clip.doubleLength();
    Consequence.ClipLengthConsequence cons =
        new Consequence.ClipLengthConsequence(project, 0, track.getActiveClipIndex(), oldLen);

    assertEquals(16, clip.getStepCount());
    assertTrue(clip.getStep(0, 8).active(), "doubled copy present before undo");

    cons.undo();
    assertEquals(8, clip.getStepCount(), "undo shrinks back to the original length");
    assertTrue(clip.getStep(0, 0).active(), "original content survives undo");
    assertFalse(clip.getStep(0, 8).active(), "the copied half is gone after undo");

    cons.redo();
    assertEquals(16, clip.getStepCount(), "redo re-doubles");
    assertTrue(clip.getStep(0, 8).active(), "the copy is restored on redo");
  }
}
