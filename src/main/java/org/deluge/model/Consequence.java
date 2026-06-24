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
      ProjectModel project,
      int trackIndex,
      int clipIndex,
      int row,
      int step,
      StepData oldData,
      StepData newData)
      implements Consequence {
    @Override
    public void undo() {
      project.getTracks().get(trackIndex).getClips().get(clipIndex).setStep(row, step, oldData);
    }

    @Override
    public void redo() {
      project.getTracks().get(trackIndex).getClips().get(clipIndex).setStep(row, step, newData);
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
      ProjectModel project,
      int trackIndex,
      int clipIndex,
      String paramName,
      int step,
      float oldValue,
      float newValue)
      implements Consequence {
    @Override
    public void undo() {
      project
          .getTracks()
          .get(trackIndex)
          .getClips()
          .get(clipIndex)
          .setAutomation(paramName, step, oldValue);
    }

    @Override
    public void redo() {
      project
          .getTracks()
          .get(trackIndex)
          .getClips()
          .get(clipIndex)
          .setAutomation(paramName, step, newValue);
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
      ProjectModel project,
      int trackIndex,
      String paramName,
      float oldValue,
      float newValue,
      long timestamp)
      implements Consequence {
    @Override
    public void undo() {
      apply(oldValue);
    }

    @Override
    public void redo() {
      apply(newValue);
    }

    private void apply(float value) {
      var track = project.getTracks().get(trackIndex);
      if (track instanceof SynthTrackModel synth) {
        switch (paramName) {
          case "unisonDetune" -> synth.setUnisonDetune(value / 100.0f);
          case "unisonSpread" -> synth.setUnisonStereoSpread(value / 100.0f);
          case "waveIndex" -> synth.setWaveIndex(value / 1000.0f);
          case "lpfCutoff" -> synth.setLpfFreq((value / 100.0f) * 20000.0f);
          case "lpfResonance" -> synth.setLpfRes((value / 100.0f) * 100.0f);
          case "filterDrive" -> synth.setFilterDrive(value / 100.0f);
          case "fmRatio" -> synth.setFmRatio(value / 100.0f);
          case "fmAmount" -> synth.setFmAmount(value / 100.0f);
          case "carrier1Fb" -> synth.setCarrier1Feedback(value / 100.0f);
          case "mod1Fb" -> synth.setModulator1Feedback(value / 100.0f);
          case "mod2Amt" -> synth.setModulator2Amount(value / 100.0f);
          case "mod2Fb" -> synth.setModulator2Feedback(value / 100.0f);
        }
      }
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
  record ProjectParamConsequence(
      ProjectModel project, String paramName, float oldValue, float newValue)
      implements Consequence {
    @Override
    public void undo() {
      apply(oldValue);
    }

    @Override
    public void redo() {
      apply(newValue);
    }

    private void apply(float value) {
      switch (paramName) {
        case "BPM", "bpm" -> project.setBpm(value);
        case "Swing", "swing" -> project.setSwing(value);
        case "Volume", "masterVolume" -> project.setMasterVolume(value);
        case "Pan", "masterPan" -> project.setMasterPan(value);
        case "reverbRoomSize" -> project.setReverbRoomSize(value);
        case "reverbDampening" -> project.setReverbDampening(value);
      }
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
      ProjectModel project, int operation, int index, TrackModel trackSnapshot, String description)
      implements Consequence {
    public static final int ADD = 0;
    public static final int REMOVE = 1;
    public static final int MOVE_UP = 2;
    public static final int MOVE_DOWN = 3;

    @Override
    public void undo() {
      apply(true);
    }

    @Override
    public void redo() {
      apply(false);
    }

    private void apply(boolean isUndo) {
      var tracks = project.getTracks();
      int idx = index;
      switch (operation) {
        case ADD -> {
          if (isUndo) {
            if (idx < tracks.size()) project.removeTrack(tracks.get(idx));
          } else {
            project.addTrack(idx, trackSnapshot);
          }
        }
        case REMOVE -> {
          if (isUndo) {
            project.addTrack(idx, trackSnapshot);
          } else {
            if (idx < tracks.size()) project.removeTrack(tracks.get(idx));
          }
        }
        case MOVE_UP -> {
          int swapIdx = isUndo ? idx + 1 : idx - 1;
          if (swapIdx >= 0 && swapIdx < tracks.size() && idx >= 0 && idx < tracks.size()) {
            project.moveTrackUp(Math.max(idx, swapIdx));
          }
        }
        case MOVE_DOWN -> {
          int swapIdx = isUndo ? idx - 1 : idx + 1;
          if (swapIdx >= 0 && swapIdx < tracks.size() && idx >= 0 && idx < tracks.size()) {
            project.moveTrackDown(Math.min(idx, swapIdx));
          }
        }
      }
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
      ProjectModel project,
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
      apply(true);
    }

    @Override
    public void redo() {
      apply(false);
    }

    private void apply(boolean isUndo) {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return;
      var track = tracks.get(trackIndex);
      var clips = track.getClips();
      int ci = clipIndex;
      switch (operation) {
        case ADD -> {
          if (isUndo) {
            if (ci >= 0 && ci < clips.size()) track.removeClip(clips.get(ci));
          } else {
            if (ci >= 0 && ci <= clips.size()) track.getClips().add(ci, clipSnapshot);
            else track.addClip(clipSnapshot);
          }
        }
        case REMOVE -> {
          if (isUndo) {
            if (ci >= 0 && ci <= clips.size()) track.getClips().add(ci, clipSnapshot);
            else track.addClip(clipSnapshot);
          } else {
            if (ci >= 0 && ci < clips.size()) track.removeClip(clips.get(ci));
          }
        }
        case DUPLICATE -> {
          if (isUndo) {
            if (ci >= 0 && ci < clips.size()) track.removeClip(clips.get(ci));
          } else {
            if (ci >= 0 && ci <= clips.size()) track.getClips().add(ci, clipSnapshot);
            else track.addClip(clipSnapshot);
          }
        }
        case RENAME -> {
          String name = isUndo ? previousName : newName;
          if (ci >= 0 && ci < clips.size()) clips.get(ci).setName(name);
        }
      }
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
      ProjectModel project,
      int trackIndex,
      int clipIndex,
      PatternModel.ClipSnapshot beforeSnapshot,
      PatternModel.ClipSnapshot afterSnapshot)
      implements Consequence {
    @Override
    public void undo() {
      apply(true);
    }

    @Override
    public void redo() {
      apply(false);
    }

    private void apply(boolean isUndo) {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return;
      var track = tracks.get(trackIndex);
      int ci = clipIndex;
      if (ci < 0 || ci >= track.getClips().size()) return;
      var clip = track.getClips().get(ci);
      var snap = isUndo ? beforeSnapshot : afterSnapshot;
      snap.applyTo(clip);
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
