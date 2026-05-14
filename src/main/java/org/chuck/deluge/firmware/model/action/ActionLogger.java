package org.chuck.deluge.firmware.model.action;

import java.util.Stack;

public class ActionLogger {
  public enum ActionType {
    PARAM_UNAUTOMATED_VALUE_CHANGE,
    SWING_CHANGE,
    TEMPO_CHANGE,
    NOTE_ADD,
    NOTE_REMOVE,
    CLIP_CLONE
  }

  private final Stack<ActionType> undoStack = new Stack<>();
  private final Stack<ActionType> redoStack = new Stack<>();

  public void recordAction(ActionType type) {
    undoStack.push(type);
    redoStack.clear();
  }

  public void undo() {
    if (!undoStack.isEmpty()) {
      redoStack.push(undoStack.pop());
      // apply undo logic...
    }
  }

  public void redo() {
    if (!redoStack.isEmpty()) {
      undoStack.push(redoStack.pop());
      // apply redo logic...
    }
  }

  public void deleteAllLogs() {
    undoStack.clear();
    redoStack.clear();
  }
}
