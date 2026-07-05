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
          case "unisonDetune" -> synth.getUnison().setUnisonDetune(value / 100.0f);
          case "unisonSpread" -> synth.getUnison().setUnisonStereoSpread(value / 100.0f);
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
          case "noiseVol" -> synth.setNoiseVol(value / 100.0f);
          // Arpeggiator / Randomizer parameters
          case "arpRate" -> synth.setArp(synth.getArp().toBuilder().rate(value / 100.0f).build());
          case "arpGate" -> synth.setArp(synth.getArp().toBuilder().gate(value / 100.0f).build());
          case "arpStepRepeat" ->
              synth.setArp(synth.getArp().toBuilder().stepRepeat((int) value).build());
          case "arpRhythm" ->
              synth.setArp(synth.getArp().toBuilder().rhythmIndex((int) value).build());
          case "arpSeqLength" ->
              synth.setArp(synth.getArp().toBuilder().seqLength((int) value).build());
          case "arpOctaveSpread" ->
              synth.setArp(synth.getArp().toBuilder().octaveSpread(value / 100.0f).build());
          case "arpGateSpread" ->
              synth.setArp(synth.getArp().toBuilder().gateSpread(value / 100.0f).build());
          case "arpVelSpread" ->
              synth.setArp(synth.getArp().toBuilder().velSpread(value / 100.0f).build());
          case "arpRatchet" ->
              synth.setArp(synth.getArp().toBuilder().ratchetAmount((int) value).build());
          case "arpNoteProb" ->
              synth.setArp(synth.getArp().toBuilder().noteProbability(value / 100.0f).build());
          case "arpChordPoly" ->
              synth.setArp(synth.getArp().toBuilder().chordPolyphony((int) value).build());
          case "arpChordProb" ->
              synth.setArp(synth.getArp().toBuilder().chordProbability(value / 100.0f).build());
          case "arpChordType" ->
              synth.setArp(synth.getArp().toBuilder().chordType((int) value).build());
          case "arpNumOctaves" ->
              synth.setArp(synth.getArp().toBuilder().numOctaves((int) value).build());
          case "arpKitArp" -> synth.setArp(synth.getArp().toBuilder().kitArp((int) value).build());
          case "arpRandomizerLock" ->
              synth.setArp(synth.getArp().toBuilder().randomizerLock((int) value).build());
          // Gold encoder parameters (raw float values)
          case "goldVolume" -> synth.setVolume(value);
          case "goldPan" -> synth.setPan(value);
          case "goldLpfCutoff" -> synth.setLpfFreq(value);
          case "goldLpfResonance" -> synth.setLpfRes(value);
          case "goldEnv0Attack" -> {
            var env0 = synth.getEnv(0);
            synth.setEnv(0, new EnvelopeModel(value, env0.decay(), env0.sustain(), env0.release(), env0.target(), env0.amount()));
          }
          case "goldEnv0Release" -> {
            var env0 = synth.getEnv(0);
            synth.setEnv(0, new EnvelopeModel(env0.attack(), env0.decay(), env0.sustain(), value, env0.target(), env0.amount()));
          }
          case "goldDelaySyncLevel" -> synth.setDelaySyncLevel((int) value);
          case "goldDelayFeedback" -> synth.setDelayFeedbackQ31((int) value);
          case "goldReverbSend" -> synth.setReverbSend(value);
          case "goldHpfCutoff" -> synth.setHpfFreq(value);
          case "goldLfo0Rate" -> {
            var lfo0 = synth.getLfo(0);
            synth.setLfo(0, new LfoModel(value, lfo0.waveform(), lfo0.depth(), lfo0.target(), lfo0.isLocal(), lfo0.syncLevel(), lfo0.syncType()));
          }
          case "goldLfo0Depth" -> {
            var lfo0 = synth.getLfo(0);
            synth.setLfo(0, new LfoModel(lfo0.rateHz(), lfo0.waveform(), value, lfo0.target(), lfo0.isLocal(), lfo0.syncLevel(), lfo0.syncType()));
          }
          case "goldArpRate" -> synth.setArp(synth.getArp().toBuilder().rate(value).build());
          case "goldPortamento" -> synth.setPortamento(value);
          case "goldOscMix" -> synth.setOscMix(value);
          case "goldBitcrusher" -> synth.setClippingAmount((int) value);
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
        case "reverbWidth" -> project.setReverbWidth(value);
        case "reverbHpf" -> project.setReverbHpf(value);
        case "reverbPan" -> project.setReverbPan(value);
        case "reverbCompressorShape" -> project.setReverbCompressorShape(value);
        case "reverbCompressorVolume" -> project.setReverbCompressorVolume(value);
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

  /**
   * Doubling a clip's length. Undo is lossless because {@link ClipModel#doubleLength()} grows the
   * grid non-destructively (the original pattern stays in {@code [0, oldLength)}), so shrinking
   * back and rebuilding the note rows restores the pre-double state exactly.
   */
  record ClipLengthConsequence(ProjectModel project, int trackIndex, int clipIndex, int oldLength)
      implements Consequence {
    @Override
    public void undo() {
      ClipModel clip = clip();
      if (clip == null) return;
      clip.setStepCount(oldLength);
      clip.rebuildNotesFromGrid();
    }

    @Override
    public void redo() {
      ClipModel clip = clip();
      if (clip != null) clip.doubleLength();
    }

    private ClipModel clip() {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return null;
      var clips = tracks.get(trackIndex).getClips();
      if (clipIndex < 0 || clipIndex >= clips.size()) return null;
      return clips.get(clipIndex);
    }

    @Override
    public String getDescription() {
      return "Double clip length";
    }

    @Override
    public Category category() {
      return Category.CLIP_STRUCT;
    }
  }

  /**
   * A bulk clip-content change captured as before/after snapshots (deep copies). Used for edits
   * that can't be reversed incrementally — e.g. a typed clip-length change that discards notes on
   * shrink.
   */
  record ClipContentConsequence(
      ProjectModel project, int trackIndex, int clipIndex, ClipModel before, ClipModel after)
      implements Consequence {
    @Override
    public void undo() {
      ClipModel clip = clip();
      if (clip != null) clip.restoreFrom(before);
    }

    @Override
    public void redo() {
      ClipModel clip = clip();
      if (clip != null) clip.restoreFrom(after);
    }

    private ClipModel clip() {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return null;
      var clips = tracks.get(trackIndex).getClips();
      if (clipIndex < 0 || clipIndex >= clips.size()) return null;
      return clips.get(clipIndex);
    }

    @Override
    public String getDescription() {
      return "Edit clip content";
    }

    @Override
    public Category category() {
      return Category.CLIP_STRUCT;
    }
  }

  /**
   * A bulk synth-parameter change (e.g. the Delugeator randomizer) captured as before/after
   * snapshots. Each snapshot is a detached {@link SynthTrackModel} carrying the copied parameters,
   * arpeggiator, and name; restoring copies them back into the live track. The UI must force a DSP
   * engine rebuild after undo/redo (as it does for a preset swap), since the whole sound changes.
   */
  record SynthRandomizeConsequence(
      ProjectModel project, int trackIndex, SynthTrackModel before, SynthTrackModel after)
      implements Consequence {

    /** Detached snapshot of {@code src}'s parameters, arp, and name for later restore. */
    public static SynthTrackModel snapshot(SynthTrackModel src) {
      SynthTrackModel snap = new SynthTrackModel(src.getName());
      snap.copyParametersFrom(src); // copyParametersFrom does not include the arp
      snap.setArp(src.getArp());
      return snap;
    }

    @Override
    public void undo() {
      apply(before);
    }

    @Override
    public void redo() {
      apply(after);
    }

    private void apply(SynthTrackModel snap) {
      var tracks = project.getTracks();
      if (trackIndex < 0 || trackIndex >= tracks.size()) return;
      if (tracks.get(trackIndex) instanceof SynthTrackModel s) {
        s.copyParametersFrom(snap);
        s.setArp(snap.getArp());
        s.setName(snap.getName());
      }
    }

    @Override
    public String getDescription() {
      return "Randomize synth";
    }

    @Override
    public Category category() {
      return Category.SYNTH_PARAM;
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
