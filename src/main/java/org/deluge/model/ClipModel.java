package org.deluge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a single sequence (clip/pattern) within a Track. Holds a 2D grid of StepData. For a
 * Kit Track: rows = distinct sounds (e.g. Kick, Snare) For a Synth Track: rows = pitches (piano
 * roll)
 */
public class ClipModel {

  /** Play mode determines how the clip behaves after being launched. */
  public enum PlayMode {
    /** Clip plays once then stops (default). */
    NORMAL,
    /** Clip auto-restarts at bar boundaries (green mode). */
    LOOP
  }

  /** Play direction determines the structural pathway of step playback. */
  public enum PlayDirection {
    FORWARD,
    REVERSE,
    PING_PONG,
    RANDOM
  }

  private String name;
  private int rowCount;
  private int stepCount;
  private final List<List<StepData>> grid = new ArrayList<>();
  private String color = "#00ffcc"; // Default color
  private PlayMode playMode = PlayMode.NORMAL;
  private PlayDirection playDirection = PlayDirection.FORWARD;
  private boolean tripletMode = false;
  private boolean isArrangementOnly = false;

  /**
   * Per-parameter per-step automation data. Maps param name (see {@link AutomationParam}) to a
   * float array of length {@code stepCount}. Values are normalized 0.0–1.0. Absent entry means "no
   * automation" — the engine uses the track-level static value for that param.
   */
  private final Map<String, float[]> automationData = new HashMap<>();

  /**
   * Per-noteRow sound parameter overrides. Maps row index → parameter name → normalized float
   * value. These are parsed from &lt;soundParams&gt; children of &lt;noteRow&gt; elements,
   * containing 35+ hex attributes that override the per-sound default parameters for that specific
   * pattern row.
   */
  private final Map<Integer, Map<String, Float>> rowSoundParams = new HashMap<>();

  /**
   * Per-noteRow step-by-step automation curves (for kit sub-drum lanes). Maps row index → parameter
   * name → float array of stepCount length.
   */
  private final Map<Integer, Map<String, float[]>> rowAutomationData = new HashMap<>();

  /**
   * Per-row ABSOLUTE note code (the Deluge noteRow "y" attribute, MIDI note number), set by the XML
   * parser for real-format songs whose noteRow list is sparse (one row per used pitch). -1 = unset
   * → the factory falls back to the UI's 128-row grid convention (pitch = rowCount-1-r).
   */
  private final Map<Integer, Integer> rowYNote = new HashMap<>();

  public void setRowYNote(int rowIndex, int yNote) {
    rowYNote.put(rowIndex, yNote);
  }

  /** Absolute MIDI note for this row, or -1 when the row follows the grid convention. */
  public int getRowYNote(int rowIndex) {
    return rowYNote.getOrDefault(rowIndex, -1);
  }

  public void setRowAutomation(int rowIndex, String paramName, int stepIndex, float value) {
    int r = getOrCreateResolvedRowIndex(rowIndex);
    Map<String, float[]> rowAutos =
        rowAutomationData.computeIfAbsent(r, k -> new HashMap<>());
    float[] array = rowAutos.computeIfAbsent(paramName, k -> new float[stepCount]);
    if (stepIndex >= 0 && stepIndex < stepCount) {
      array[stepIndex] = value;
    }
  }

  public float[] getRowAutomation(int rowIndex, String paramName) {
    int r = getResolvedRowIndex(rowIndex);
    if (r < 0) return null;
    Map<String, float[]> rowAutos = rowAutomationData.get(r);
    return rowAutos != null ? rowAutos.get(paramName) : null;
  }

  public Map<Integer, Map<String, float[]>> getRowAutomationData() {
    return rowAutomationData;
  }

  /**
   * Per-clip kit parameter overrides. Maps parameter name → normalized float value. Parsed from
   * &lt;kitParams&gt; child of &lt;instrumentClip isKitClip="true"&gt;. Mirrors the same attributes
   * as &lt;songParams&gt; but for kit tracks.
   */
  private final Map<String, Float> kitParams = new HashMap<>();

  /** Raw, unquantized high-resolution note events list per row index. */
  private final Map<Integer, List<HighResNote>> rawNoteEvents = new HashMap<>();

  public void setRawNoteEvents(int rowIndex, List<HighResNote> notes) {
    int r = getOrCreateResolvedRowIndex(rowIndex);
    if (notes == null) {
      rawNoteEvents.remove(r);
    } else {
      rawNoteEvents.put(r, new ArrayList<>(notes));
    }
  }

  public List<HighResNote> getRawNoteEvents(int rowIndex) {
    int r = getResolvedRowIndex(rowIndex);
    if (r < 0) return null;
    return rawNoteEvents.get(r);
  }

  public Map<Integer, List<HighResNote>> getRawNoteEventsMap() {
    return rawNoteEvents;
  }

  public ClipModel(String name, int rowCount, int stepCount) {
    this.name = name;
    this.rowCount = Math.max(1, rowCount);
    this.stepCount = Math.max(1, stepCount);
    initGrid();
  }

  public ClipModel deepCopy(String newName) {
    ClipModel copy = new ClipModel(newName, this.rowCount, this.stepCount);
    for (int r = 0; r < rowCount; r++) {
      for (int s = 0; s < stepCount; s++) {
        copy.setStep(r, s, this.getStep(r, s));
      }
    }
    // Deep-copy automation data
    for (Map.Entry<String, float[]> e : automationData.entrySet()) {
      copy.automationData.put(e.getKey(), e.getValue().clone());
    }
    // Deep-copy row sound params
    for (Map.Entry<Integer, Map<String, Float>> e : rowSoundParams.entrySet()) {
      copy.rowSoundParams.put(e.getKey(), new HashMap<>(e.getValue()));
    }
    // Deep-copy row automation data
    for (Map.Entry<Integer, Map<String, float[]>> e : rowAutomationData.entrySet()) {
      Map<String, float[]> copiedMap = new HashMap<>();
      for (Map.Entry<String, float[]> subEntry : e.getValue().entrySet()) {
        copiedMap.put(subEntry.getKey(), subEntry.getValue().clone());
      }
      copy.rowAutomationData.put(e.getKey(), copiedMap);
    }
    // Deep-copy kit params
    copy.kitParams.putAll(this.kitParams);
    copy.rowYNote.putAll(this.rowYNote);
    // Deep-copy raw high-resolution note events
    for (Map.Entry<Integer, List<HighResNote>> e : this.rawNoteEvents.entrySet()) {
      copy.rawNoteEvents.put(e.getKey(), new ArrayList<>(e.getValue()));
    }
    // Copy play mode
    copy.playMode = this.playMode;
    // Copy play direction
    copy.playDirection = this.playDirection;
    return copy;
  }

  private void initGrid() {
    grid.clear();
    for (int r = 0; r < rowCount; r++) {
      List<StepData> row = new ArrayList<>();
      for (int s = 0; s < stepCount; s++) {
        row.add(StepData.empty());
      }
      grid.add(row);
    }
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getColor() {
    return color;
  }

  public void setColor(String color) {
    this.color = color;
  }

  public PlayMode getPlayMode() {
    return playMode;
  }

  public void setPlayMode(PlayMode playMode) {
    this.playMode = playMode;
  }

  public PlayDirection getPlayDirection() {
    return playDirection;
  }

  public void setPlayDirection(PlayDirection playDirection) {
    this.playDirection = playDirection;
  }

  public int getRowCount() {
    return rowCount;
  }

  public void setRowCount(int rowCount) {
    if (rowCount <= 0 || rowCount == this.rowCount) return;
    int oldRowCount = this.rowCount;
    this.rowCount = rowCount;

    if (this.rowCount > oldRowCount) {
      for (int r = oldRowCount; r < this.rowCount; r++) {
        List<StepData> newRow = new ArrayList<>();
        for (int s = 0; s < stepCount; s++) {
          newRow.add(StepData.empty());
        }
        grid.add(newRow);
      }
    } else {
      while (grid.size() > this.rowCount) {
        grid.remove(grid.size() - 1);
      }
    }
  }

  public int getStepCount() {
    return stepCount;
  }

  public boolean isTripletMode() {
    return tripletMode;
  }

  public void setTripletMode(boolean triplet) {
    this.tripletMode = triplet;
  }

  public boolean isArrangementOnly() {
    return isArrangementOnly;
  }

  public void setArrangementOnly(boolean value) {
    this.isArrangementOnly = value;
  }

  public void setStepCount(int stepCount) {
    if (stepCount <= 0 || stepCount == this.stepCount) return;
    int oldStepCount = this.stepCount;
    this.stepCount = stepCount;

    for (List<StepData> row : grid) {
      if (this.stepCount > oldStepCount) {
        for (int s = oldStepCount; s < this.stepCount; s++) {
          row.add(StepData.empty());
        }
      } else {
        while (row.size() > this.stepCount) {
          row.remove(row.size() - 1);
        }
      }
    }

    // Resize automation arrays to match new step count
    resizeAutomationArrays(oldStepCount, this.stepCount);
  }

  private int getResolvedRowIndex(int row) {
    if (!rowYNote.isEmpty()) {
      int targetPitch = 127 - row;
      for (Map.Entry<Integer, Integer> entry : rowYNote.entrySet()) {
        if (entry.getValue() == targetPitch) {
          return entry.getKey();
        }
      }
      return -1; // not found in sparse rows
    }
    return row;
  }

  private int getOrCreateResolvedRowIndex(int row) {
    if (!rowYNote.isEmpty()) {
      int targetPitch = 127 - row;
      for (Map.Entry<Integer, Integer> entry : rowYNote.entrySet()) {
        if (entry.getValue() == targetPitch) {
          return entry.getKey();
        }
      }
      // Create new row dynamically
      int newRowIdx = grid.size();
      List<StepData> newRow = new ArrayList<>();
      for (int s = 0; s < stepCount; s++) {
        newRow.add(StepData.empty());
      }
      grid.add(newRow);
      rowCount = grid.size();
      rowYNote.put(newRowIdx, targetPitch);
      return newRowIdx;
    }

    if (row >= rowCount) {
      // Grow the grid to accommodate this row (synth piano roll)
      for (int r = rowCount; r <= row; r++) {
        List<StepData> newRow = new ArrayList<>();
        for (int s = 0; s < stepCount; s++) {
          newRow.add(StepData.empty());
        }
        grid.add(newRow);
      }
      rowCount = row + 1;
    }
    return row;
  }

  public StepData getStep(int row, int step) {
    int r = getResolvedRowIndex(row);
    if (r >= 0 && r < rowCount && step >= 0 && step < stepCount) {
      return grid.get(r).get(step);
    }
    return StepData.empty();
  }

  public interface ClipListener {
    void onStepChanged(int row, int step, StepData data);
  }

  private final java.util.List<ClipListener> listeners = new java.util.ArrayList<>();

  public void addClipListener(ClipListener l) {
    listeners.add(l);
  }

  public void setStep(int row, int step, StepData data) {
    if (step < 0 || step >= stepCount) return;
    int r = getOrCreateResolvedRowIndex(row);
    if (r >= 0 && r < rowCount) {
      grid.get(r).set(step, data);
      for (ClipListener l : listeners) {
        l.onStepChanged(r, step, data);
      }
    }
  }

  // ── Per-parameter automation data ──

  /**
   * Set an automation value for a parameter at a specific step. Range 0.0–1.0. Calling with 0.0 or
   * any valid value creates or updates the automation entry.
   */
  public void setAutomation(String paramName, int step, float value) {
    float[] arr = automationData.get(paramName);
    if (arr == null) {
      arr = new float[stepCount];
      // Initialise to -1 (no automation) for all steps
      java.util.Arrays.fill(arr, -1f);
      automationData.put(paramName, arr);
    }
    if (step >= 0 && step < arr.length) {
      arr[step] = Math.max(0.0f, Math.min(1.0f, value));
    }
  }

  /**
   * Get an automation value for a parameter at a specific step.
   *
   * @return 0.0–1.0 if automation data exists, or -1 if no automation is set for this param
   */
  public float getAutomation(String paramName, int step) {
    float[] arr = automationData.get(paramName);
    if (arr == null || step < 0 || step >= arr.length) return -1f;
    return arr[step];
  }

  /** Returns true if automation data exists for the given parameter at any step. */
  public boolean hasAutomation(String paramName) {
    return automationData.containsKey(paramName);
  }

  /** Returns true if automation data exists for the given parameter at the specific step. */
  public boolean hasAutomation(String paramName, int step) {
    float[] arr = automationData.get(paramName);
    return arr != null && step >= 0 && step < arr.length && arr[step] >= 0f;
  }

  /** Remove all automation data for the given parameter. */
  public void clearAutomation(String paramName) {
    automationData.remove(paramName);
  }

  /** Returns the set of all parameter names that have automation data. */
  public Set<String> getAutomatedParams() {
    return automationData.keySet();
  }

  /** Returns all automation data for a parameter (length = stepCount), or null if none. */
  public float[] getAutomationArray(String paramName) {
    return automationData.get(paramName);
  }

  // ── Per-noteRow sound parameter overrides ──

  /**
   * Set a sound parameter override for a specific row. The value is a normalized float (0.0-1.0)
   * derived from the XML hex attribute.
   */
  public void setRowSoundParam(int row, String paramName, float value) {
    int r = getOrCreateResolvedRowIndex(row);
    rowSoundParams.computeIfAbsent(r, k -> new HashMap<>()).put(paramName, value);
  }

  /**
   * Get a sound parameter override for a specific row.
   *
   * @return the value, or -1 if no override exists for this row+param combination.
   */
  public float getRowSoundParam(int row, String paramName) {
    int r = getResolvedRowIndex(row);
    if (r < 0) return -1f;
    Map<String, Float> rowParams = rowSoundParams.get(r);
    if (rowParams == null) return -1f;
    Float val = rowParams.get(paramName);
    return val != null ? val : -1f;
  }

  /** Returns true if the given row has any sound parameter overrides. */
  public boolean hasRowSoundParams(int row) {
    int r = getResolvedRowIndex(row);
    if (r < 0) return false;
    return rowSoundParams.containsKey(r) && !rowSoundParams.get(r).isEmpty();
  }

  /** Returns all parameter names that have overrides for a given row, or empty set. */
  public Set<String> getRowSoundParamNames(int row) {
    int r = getResolvedRowIndex(row);
    if (r < 0) return Set.of();
    Map<String, Float> rowParams = rowSoundParams.get(r);
    return rowParams != null ? rowParams.keySet() : Set.of();
  }

  // ── Per-clip kit parameter overrides ──

  public Map<String, Float> getKitParams() {
    return kitParams;
  }

  public void setKitParam(String param, float value) {
    kitParams.put(param, value);
  }

  public void setKitParams(Map<String, Float> params) {
    kitParams.clear();
    kitParams.putAll(params);
  }

  public float getKitParam(String param) {
    return kitParams.getOrDefault(param, -1f);
  }

  // ── Internal helpers called by stepCount setter ──

  void resizeAutomationArrays(int oldStepCount, int newStepCount) {
    for (Map.Entry<String, float[]> entry : automationData.entrySet()) {
      float[] old = entry.getValue();
      float[] updated = new float[newStepCount];
      java.util.Arrays.fill(updated, -1f);
      System.arraycopy(old, 0, updated, 0, Math.min(oldStepCount, newStepCount));
      entry.setValue(updated);
    }
  }
}
