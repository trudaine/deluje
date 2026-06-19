package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.BridgeContract;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/** Verifies clip-note copy/paste reproduces the source grid into the target clip. */
public class ClipNoteClipboardTest {

  private SwingGridPanel panel(ProjectModel project) throws Exception {
    System.setProperty("chuck.audio.dummy", "true");
    BridgeContract bridge = new BridgeContract();

    SwingGridPanel p = new SwingGridPanel(bridge);
    p.setProjectModel(project);
    p.setEditedModelTrack(0);
    p.setActiveClipId(0);
    return p;
  }

  @Test
  public void copyThenPasteReproducesTheGrid() throws Exception {
    // Source project: one synth track, one clip with a couple of active steps.
    ProjectModel src = new ProjectModel();
    SynthTrackModel st = new SynthTrackModel("Synth");
    ClipModel clip = new ClipModel("Clip", 8, 16);
    clip.setStep(2, 0, StepData.of(true, 0.9f, 1.0f, 1.0f, 60));
    clip.setStep(5, 4, StepData.of(true, 0.5f, 0.5f, 0.8f, 62));
    st.addClip(clip);
    src.addTrack(st);

    SwingGridPanel p = panel(src);
    assertTrue(p.copyClipNotes(), "copy succeeds with a valid clip");

    // Paste into a fresh empty clip and confirm the active steps came across.
    ProjectModel dst = new ProjectModel();
    SynthTrackModel dt = new SynthTrackModel("Synth2");
    ClipModel empty = new ClipModel("Empty", 8, 16);
    dt.addClip(empty);
    dst.addTrack(dt);

    SwingGridPanel p2 = panel(dst);
    assertTrue(p2.pasteClipNotes(), "paste succeeds");

    assertTrue(empty.getStep(2, 0).active(), "copied step (2,0) pasted");
    assertTrue(empty.getStep(5, 4).active(), "copied step (5,4) pasted");
    assertFalse(empty.getStep(0, 0).active(), "untouched cell stays empty");
  }

  @Test
  public void pasteWithoutCopyOnFreshClipboardIsSafe() throws Exception {
    // Note: the clipboard is static; this test only asserts the no-clip guard path is safe.
    ProjectModel proj = new ProjectModel();
    SwingGridPanel p = new SwingGridPanel(new BridgeContract());
    p.setProjectModel(proj);
    p.setEditedModelTrack(0);
    // No tracks -> getEditedActiveClip() returns null -> paste is a safe no-op.
    assertFalse(p.pasteClipNotes(), "paste with no target clip is a safe no-op");
  }
}
