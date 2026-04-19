package org.chuck.deluge.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class UndoRedoStackTest {

  static class MockAction implements UndoRedoStack.UndoableAction {
    private final String id;
    public boolean undone = false;
    public boolean redone = false;

    public MockAction(String id) {
      this.id = id;
    }

    @Override
    public void undo() {
      undone = true;
      redone = false;
    }

    @Override
    public void redo() {
      redone = true;
      undone = false;
    }

    @Override
    public String getDescription() {
      return id;
    }
  }

  @Test
  void testUndoRedoBasic() {
    UndoRedoStack stack = new UndoRedoStack(64);
    MockAction a1 = new MockAction("A1");
    MockAction a2 = new MockAction("A2");

    stack.push(a1);
    stack.push(a2);

    assertTrue(stack.canUndo());
    assertFalse(stack.canRedo());

    stack.undo(); // Undoes A2
    assertTrue(a2.undone);
    assertFalse(a1.undone);

    stack.undo(); // Undoes A1
    assertTrue(a1.undone);

    assertFalse(stack.canUndo());
    assertTrue(stack.canRedo());

    stack.redo(); // Redoes A1
    assertTrue(a1.redone);

    stack.redo(); // Redoes A2
    assertTrue(a2.redone);
  }

  @Test
  void testMaxDepth() {
    UndoRedoStack stack = new UndoRedoStack(3);
    MockAction a1 = new MockAction("A1");
    MockAction a2 = new MockAction("A2");
    MockAction a3 = new MockAction("A3");
    MockAction a4 = new MockAction("A4");

    stack.push(a1);
    stack.push(a2);
    stack.push(a3);
    stack.push(a4); // Pushes A1 out

    stack.undo(); // A4
    stack.undo(); // A3
    stack.undo(); // A2

    assertFalse(stack.canUndo()); // A1 is gone
  }
}
