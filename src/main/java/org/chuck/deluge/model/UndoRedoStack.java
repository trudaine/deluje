package org.chuck.deluge.model;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * A generic Undo/Redo stack for the Deluge application. Manages a history of UndoableAction objects
 * with a maximum depth.
 */
public class UndoRedoStack {
  private final Deque<UndoableAction> undoStack;
  private final Deque<UndoableAction> redoStack;
  private final int maxDepth;

  public interface UndoableAction {
    void undo();

    void redo();

    String getDescription();
  }

  public UndoRedoStack(int maxDepth) {
    this.maxDepth = maxDepth;
    this.undoStack = new ArrayDeque<>(maxDepth);
    this.redoStack = new ArrayDeque<>(maxDepth);
  }

  public void push(UndoableAction action) {
    // A new action invalidates the redo history
    redoStack.clear();

    // Prevent overflow
    if (undoStack.size() >= maxDepth) {
      undoStack.removeLast(); // Remove oldest
    }

    undoStack.addFirst(action);
  }

  public boolean canUndo() {
    return !undoStack.isEmpty();
  }

  public boolean canRedo() {
    return !redoStack.isEmpty();
  }

  public void undo() {
    if (canUndo()) {
      UndoableAction action = undoStack.removeFirst();
      action.undo();
      redoStack.addFirst(action);
      System.out.println("Undo: " + action.getDescription());
    }
  }

  public void redo() {
    if (canRedo()) {
      UndoableAction action = redoStack.removeFirst();
      action.redo();
      undoStack.addFirst(action);
      System.out.println("Redo: " + action.getDescription());
    }
  }
}
