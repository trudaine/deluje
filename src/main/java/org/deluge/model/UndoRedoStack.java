package org.deluge.model;

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

  // True while an undo/redo is being applied. Many consequences restore state by calling model
  // setters that themselves push a consequence; without this guard those re-entrant pushes would
  // clear the redo history and corrupt the stack. Pushes are ignored while applying.
  private boolean applying = false;

  public interface UndoableAction {
    void undo();

    void redo();

    String getDescription();
  }

  public interface StackListener {
    void onActionPushed(UndoableAction action);
  }

  private StackListener listener;

  public void setListener(StackListener listener) {
    this.listener = listener;
  }

  public UndoRedoStack(int maxDepth) {
    this.maxDepth = maxDepth;
    this.undoStack = new ArrayDeque<>(maxDepth);
    this.redoStack = new ArrayDeque<>(maxDepth);
  }

  public void push(UndoableAction action) {
    if (applying) return; // ignore re-entrant pushes triggered by a consequence's own setters

    // A new action invalidates the redo history
    redoStack.clear();

    // Prevent overflow
    if (undoStack.size() >= maxDepth) {
      undoStack.removeLast(); // Remove oldest
    }

    undoStack.addFirst(action);

    if (listener != null) {
      listener.onActionPushed(action);
    }
  }

  public boolean canUndo() {
    return !undoStack.isEmpty();
  }

  public boolean canRedo() {
    return !redoStack.isEmpty();
  }

  public UndoableAction peekUndo() {
    return undoStack.peekFirst();
  }

  public UndoableAction peekRedo() {
    return redoStack.peekFirst();
  }

  /**
   * Replace the most recent undo entry (top of undo stack) with a new action. Used for coalescing —
   * e.g., during a slider drag, replace the last entry with updated newValue instead of pushing a
   * new one every frame.
   */
  public void replaceLast(UndoableAction action) {
    if (!undoStack.isEmpty()) {
      undoStack.removeFirst();
    }
    undoStack.addFirst(action);
  }

  /** Clear all undo and redo history. Call when loading a new project. */
  public void clear() {
    undoStack.clear();
    redoStack.clear();
  }

  /** Number of entries in the undo stack. */
  public int size() {
    return undoStack.size();
  }

  /** Description of the top undo action, or null if empty. */
  public String getUndoDescription() {
    UndoableAction a = undoStack.peekFirst();
    return a != null ? a.getDescription() : null;
  }

  /** Description of the top redo action, or null if empty. */
  public String getRedoDescription() {
    UndoableAction a = redoStack.peekFirst();
    return a != null ? a.getDescription() : null;
  }

  public void undo() {
    if (canUndo()) {
      UndoableAction action = undoStack.removeFirst();
      applying = true;
      try {
        action.undo();
      } finally {
        applying = false;
      }
      redoStack.addFirst(action);
    }
  }

  public void redo() {
    if (canRedo()) {
      UndoableAction action = redoStack.removeFirst();
      applying = true;
      try {
        action.redo();
      } finally {
        applying = false;
      }
      undoStack.addFirst(action);
    }
  }
}
