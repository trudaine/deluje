package org.deluge.model;

import java.util.List;

/**
 * A single undoable mutation to the project model. Extends {@link UndoRedoStack.UndoableAction} so
 * existing stack infrastructure works unchanged.
 */
public interface Consequence extends UndoRedoStack.UndoableAction {

  Category category();

  enum Category {
    STEP,
    AUTOMATION,
    SYNTH_PARAM,
    PROJECT_PARAM,
    TRACK_STRUCT,
    CLIP_STRUCT,
    PATTERN_LOAD,
  }

  // ── Record implementations ──

  /** A single grid pad toggle. */
  record StepConsequence(
      int trackIndex, int clipIndex, int row, int step, StepData oldData, StepData newData)
      implements Consequence {
    @Override
    public void undo() {
      // Handled by SwingGridPanel via re-sync after undo
    }

    @Override
    public void redo() {
      // Handled by SwingGridPanel via re-sync after redo
    }

    @Override
    public String getDescription() {
      return "Toggle step " + (step + 1) + ":" + (row + 1);
    }

    @Override
    public Category category() {
      return Category.STEP;
    }
  }

  /** An automation point set or cleared. */
  record AutomationConsequence(
      int trackIndex, int clipIndex, String paramName, int step, float oldValue, float newValue)
      implements Consequence {
    @Override
    public void undo() {
      // Handled by SwingGridPanel
    }

    @Override
    public void redo() {
      // Handled by SwingGridPanel
    }

    @Override
    public String getDescription() {
      return "Edit automation " + paramName + " step " + (step + 1);
    }

    @Override
    public Category category() {
      return Category.AUTOMATION;
    }
  }

  /** A single synth/kit parameter slider change. {@code timestamp} enables coalescing. */
  record SynthParamConsequence(
      int trackIndex, String paramName, float oldValue, float newValue, long timestamp)
      implements Consequence {
    @Override
    public void undo() {
      // Handled by SwingSynthConfigDialog
    }

    @Override
    public void redo() {
      // Handled by SwingSynthConfigDialog
    }

    @Override
    public String getDescription() {
      return "Change " + paramName;
    }

    @Override
    public Category category() {
      return Category.SYNTH_PARAM;
    }
  }

  /** A project-level parameter change (BPM, swing, master volume, etc.). */
  record ProjectParamConsequence(String paramName, float oldValue, float newValue)
      implements Consequence {
    @Override
    public void undo() {
      // Handled by ProjectModel.set* → listener → pushModelToBridge
    }

    @Override
    public void redo() {
      // Handled by ProjectModel.set* → listener → pushModelToBridge
    }

    @Override
    public String getDescription() {
      return "Change " + paramName;
    }

    @Override
    public Category category() {
      return Category.PROJECT_PARAM;
    }
  }

  /** Add, remove, or reorder a track. */
  record TrackStructureConsequence(
      int operation, int index, TrackModel trackSnapshot, String description)
      implements Consequence {
    public static final int ADD = 0;
    public static final int REMOVE = 1;
    public static final int MOVE_UP = 2;
    public static final int MOVE_DOWN = 3;

    @Override
    public void undo() {
      // Handled by SwingDelugeApp
    }

    @Override
    public void redo() {
      // Handled by SwingDelugeApp
    }

    @Override
    public String getDescription() {
      return description;
    }

    @Override
    public Category category() {
      return Category.TRACK_STRUCT;
    }
  }

  /** Clip add, delete, duplicate, or rename. */
  record ClipStructureConsequence(
      int trackIndex,
      int clipIndex,
      int operation,
      ClipModel clipSnapshot,
      String previousName,
      String newName)
      implements Consequence {
    public static final int ADD = 0;
    public static final int REMOVE = 1;
    public static final int DUPLICATE = 2;
    public static final int RENAME = 3;

    @Override
    public void undo() {
      // Handled by SwingDelugeApp
    }

    @Override
    public void redo() {
      // Handled by SwingDelugeApp
    }

    @Override
    public String getDescription() {
      return switch (operation) {
        case ADD -> "Add clip";
        case REMOVE -> "Remove clip";
        case DUPLICATE -> "Duplicate clip";
        case RENAME -> "Rename clip to " + newName;
        default -> "Clip operation";
      };
    }

    @Override
    public Category category() {
      return Category.CLIP_STRUCT;
    }
  }

  /** Batch of consequences undone/redone as one. */
  record CompoundConsequence(String description, List<Consequence> children)
      implements Consequence {
    @Override
    public void undo() {
      for (int i = children.size() - 1; i >= 0; i--) {
        children.get(i).undo();
      }
    }

    @Override
    public void redo() {
      for (Consequence c : children) {
        c.redo();
      }
    }

    @Override
    public String getDescription() {
      return description;
    }

    @Override
    public Category category() {
      return Category.PATTERN_LOAD;
    }
  }

  /** Pattern load — applies/reverts a clip snapshot. */
  record PatternLoadConsequence(
      int trackIndex,
      int clipIndex,
      PatternModel.ClipSnapshot beforeSnapshot,
      PatternModel.ClipSnapshot afterSnapshot)
      implements Consequence {
    @Override
    public void undo() {
      // Revert clip to before-snapshot
    }

    @Override
    public void redo() {
      // Apply after-snapshot
    }

    @Override
    public String getDescription() {
      return "Load pattern";
    }

    @Override
    public Category category() {
      return Category.PATTERN_LOAD;
    }
  }
}
