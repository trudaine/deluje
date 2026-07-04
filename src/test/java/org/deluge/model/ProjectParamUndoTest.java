package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the undo-stack re-entrancy fix: project-parameter setters push their own consequences, so
 * undo/redo (which call those setters) must not corrupt the stack.
 */
class ProjectParamUndoTest {

  @Test
  void projectParam_undoThenRedo_survivesReentrantSetterPush() {
    ProjectModel p = new ProjectModel();
    p.setReverbRoomSize(0.15f);
    p.setReverbRoomSize(0.85f);
    UndoRedoStack stack = p.getUndoRedoStack();

    assertTrue(stack.canUndo());
    stack.undo();
    assertEquals(0.15f, p.getReverbRoomSize(), 1e-6, "undo reverts to the earlier value");
    assertTrue(stack.canRedo(), "redo history survives the re-entrant push during undo");

    stack.redo();
    assertEquals(0.85f, p.getReverbRoomSize(), 1e-6, "redo re-applies the later value");
  }

  @Test
  void reverbWidth_isNowUndoable() {
    ProjectModel p = new ProjectModel();
    float orig = p.getReverbWidth();
    p.setReverbWidth(orig == 0.9f ? 0.3f : 0.9f);
    p.getUndoRedoStack().undo();
    assertEquals(orig, p.getReverbWidth(), 1e-6, "reverbWidth undo restores the original value");
  }
}
