package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pins {@link ClipModel#shiftNotesHorizontally(int)} and its undo via ClipContentConsequence. */
class ClipShiftTest {

  @Test
  void shiftRight_movesNoteOneStep() {
    ClipModel clip = new ClipModel("t", 4, 8);
    clip.setStep(0, 2, StepData.of(true, 0.5f, 1.0f, 1.0f, 60));

    clip.shiftNotesHorizontally(1);

    assertFalse(clip.getStep(0, 2).active(), "the note left step 2");
    assertTrue(clip.getStep(0, 3).active(), "and landed on step 3");
    assertEquals(0.5f, clip.getStep(0, 3).velocity(), 1e-6, "velocity carried");
  }

  @Test
  void shiftRight_wrapsPastTheEnd() {
    ClipModel clip = new ClipModel("t", 4, 8);
    clip.setStep(0, 7, StepData.of(true, 0.9f, 1.0f, 1.0f, 60)); // last step

    clip.shiftNotesHorizontally(1);

    assertFalse(clip.getStep(0, 7).active());
    assertTrue(clip.getStep(0, 0).active(), "the last note wrapped around to step 0");
    assertEquals(0.9f, clip.getStep(0, 0).velocity(), 1e-6);
  }

  @Test
  void shiftLeft_isInverseOfShiftRight() {
    ClipModel clip = new ClipModel("t", 4, 8);
    clip.setStep(1, 3, StepData.of(true, 0.6f, 1.0f, 1.0f, 61));
    clip.shiftNotesHorizontally(2);
    assertTrue(clip.getStep(1, 5).active());
    clip.shiftNotesHorizontally(-2);
    assertTrue(clip.getStep(1, 3).active(), "shifting back restores the original position");
    assertFalse(clip.getStep(1, 5).active());
  }

  @Test
  void shift_isUndoable() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth");
    ClipModel clip = new ClipModel("t", 4, 8);
    clip.setStep(0, 0, StepData.of(true, 0.5f, 1.0f, 1.0f, 60));
    track.addClip(clip);
    project.addTrack(track);

    ClipModel before = clip.deepCopy(clip.getName());
    clip.shiftNotesHorizontally(3);
    Consequence.ClipContentConsequence cons =
        new Consequence.ClipContentConsequence(
            project, 0, 0, before, clip.deepCopy(clip.getName()));

    assertTrue(clip.getStep(0, 3).active());
    cons.undo();
    assertTrue(clip.getStep(0, 0).active(), "undo restores the pre-shift positions");
    assertFalse(clip.getStep(0, 3).active());
    cons.redo();
    assertTrue(clip.getStep(0, 3).active(), "redo re-applies the shift");
  }
}
