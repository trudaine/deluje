package org.deluge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Represents a named pattern (a snapshot of sequences across tracks) for clip launching. */
public class PatternModel {
  private String id;
  private String name;
  private String category = "MELODIC";

  /**
   * A snapshot of a single clip/track within this pattern. Holds the full step grid, automation
   * data, row sound params, and kit params so the pattern can be saved to disk and reloaded
   * independently of the active project.
   */
  public static class ClipSnapshot {
    private int trackIndex;
    private String trackName;
    private int rowCount;
    private int stepCount;
    private final List<List<StepData>> grid = new ArrayList<>();
    private final Map<String, float[]> automationData = new HashMap<>();
    private final Map<String, List<org.deluge.modulation.automation.ParamNode>> automationCurves =
        new HashMap<>();
    private final List<PatchCable> patchCables = new ArrayList<>();
    private final List<org.deluge.modulation.midi.MidiParam> midiParams = new ArrayList<>();
    private final org.deluge.modulation.params.ExpressionParamSet expressionParams =
        new org.deluge.modulation.params.ExpressionParamSet();
    private int bendRange = -1;
    private int bendRangeMPE = -1;
    private final Map<Integer, Map<String, Float>> rowSoundParams = new HashMap<>();
    private final Map<Integer, Integer> rowBendRanges = new HashMap<>();
    private final Map<Integer, org.deluge.modulation.params.ExpressionParamSet>
        rowExpressionParams = new HashMap<>();
    private final Map<String, Float> kitParams = new HashMap<>();
    private final Map<String, String> rawKitParamHex = new java.util.LinkedHashMap<>();
    private String instrumentSlot = "";
    private float volumeOverride = -1f;
    private float panOverride = -1f;
    private String colourHex = "#00ffcc";

    public ClipSnapshot() {}

    public ClipSnapshot(int trackIndex, String trackName, int rowCount, int stepCount) {
      this.trackIndex = trackIndex;
      this.trackName = trackName != null ? trackName : "";
      this.rowCount = Math.max(0, rowCount);
      this.stepCount = Math.max(0, stepCount);
      for (int r = 0; r < this.rowCount; r++) {
        List<StepData> row = new ArrayList<>(this.stepCount);
        for (int s = 0; s < this.stepCount; s++) {
          row.add(StepData.empty());
        }
        this.grid.add(row);
      }
    }

    /**
     * Build a ClipSnapshot from an in-memory ClipModel and track info. Deep-copies all step,
     * automation, row sound param, and kit param data.
     */
    public static ClipSnapshot fromClipModel(ClipModel clip, int trackIndex, String trackName) {
      ClipSnapshot snap =
          new ClipSnapshot(trackIndex, trackName, clip.getRowCount(), clip.getStepCount());
      snap.colourHex = clip.getColor();
      // Deep-copy grid
      for (int r = 0; r < clip.getRowCount(); r++) {
        List<StepData> row = new ArrayList<>(clip.getStepCount());
        for (int s = 0; s < clip.getStepCount(); s++) {
          row.add(clip.getStep(r, s));
        }
        snap.grid.set(r, row);
      }
      // Deep-copy automation data
      for (String param : clip.getAutomatedParams()) {
        float[] arr = clip.getAutomationArray(param);
        if (arr != null) {
          snap.automationData.put(param, arr.clone());
        }
      }
      for (var curveEntry : clip.getAutomationCurves().entrySet()) {
        List<org.deluge.modulation.automation.ParamNode> copiedCurve =
            new ArrayList<>(curveEntry.getValue().size());
        for (var node : curveEntry.getValue()) {
          copiedCurve.add(
              new org.deluge.modulation.automation.ParamNode(
                  node.pos, node.value, node.interpolated));
        }
        snap.automationCurves.put(curveEntry.getKey(), copiedCurve);
      }
      for (PatchCable pc : clip.getPatchCables()) {
        snap.patchCables.add(pc.deepCopy());
      }
      for (org.deluge.modulation.midi.MidiParam mp : clip.getMidiParams()) {
        snap.midiParams.add(mp.deepCopy());
      }
      snap.expressionParams.copyFrom(clip.getExpressionParams());
      snap.bendRange = clip.getBendRange();
      snap.bendRangeMPE = clip.getBendRangeMPE();
      // Deep-copy row sound params
      for (int r = 0; r < clip.getRowCount(); r++) {
        if (clip.hasRowSoundParams(r)) {
          snap.rowSoundParams.put(r, new HashMap<>(clip.getRowSoundParamNames(r).size()));
          for (String pn : clip.getRowSoundParamNames(r)) {
            snap.rowSoundParams.get(r).put(pn, clip.getRowSoundParam(r, pn));
          }
        }
        NoteRowModel rowModel = clip.getNoteRowsMap().get(r);
        if (rowModel != null) {
          if (rowModel.getBendRange() != -1) {
            snap.rowBendRanges.put(r, rowModel.getBendRange());
          }
          if (rowModel.getExpressionParams().containsSomething()) {
            var copiedRowExpr = new org.deluge.modulation.params.ExpressionParamSet(true);
            copiedRowExpr.copyFrom(rowModel.getExpressionParams());
            snap.rowExpressionParams.put(r, copiedRowExpr);
          }
        }
      }
      // Deep-copy kit params
      snap.kitParams.putAll(clip.getKitParams());
      snap.rawKitParamHex.putAll(clip.getRawKitParamHex());
      return snap;
    }

    /** Apply this snapshot back into a ClipModel (destructive, overwrites existing data). */
    public void applyTo(ClipModel clip) {
      int rows = Math.max(clip.getRowCount(), rowCount);
      int steps = Math.max(clip.getStepCount(), stepCount);
      // Clear and rebuild grid
      for (int r = 0; r < rows; r++) {
        for (int s = 0; s < steps; s++) {
          StepData data =
              (r < rowCount && s < stepCount && r < grid.size() && s < grid.get(r).size())
                  ? grid.get(r).get(s)
                  : StepData.empty();
          clip.setStep(r, s, data);
        }
      }
      // Clear and apply automation, keeping unautomated steps at -1f
      clip.getAutomationData().clear();
      for (Map.Entry<String, float[]> e : automationData.entrySet()) {
        float[] src = e.getValue();
        if (src == null) continue;
        float[] dst = new float[clip.getStepCount()];
        java.util.Arrays.fill(dst, -1f);
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, dst.length));
        clip.getAutomationData().put(e.getKey(), dst);
      }
      clip.getAutomationCurves().clear();
      for (var curveEntry : automationCurves.entrySet()) {
        List<org.deluge.modulation.automation.ParamNode> copiedCurve =
            new ArrayList<>(curveEntry.getValue().size());
        for (var node : curveEntry.getValue()) {
          copiedCurve.add(
              new org.deluge.modulation.automation.ParamNode(
                  node.pos, node.value, node.interpolated));
        }
        clip.getAutomationCurves().put(curveEntry.getKey(), copiedCurve);
      }
      clip.getPatchCables().clear();
      for (PatchCable pc : patchCables) {
        clip.getPatchCables().add(pc.deepCopy());
      }
      clip.clearMidiParams();
      for (org.deluge.modulation.midi.MidiParam mp : midiParams) {
        clip.setMidiParam(mp.cc, mp.param.currentValue, mp.param.nodes);
      }
      clip.setBendRange(bendRange);
      clip.setBendRangeMPE(bendRangeMPE);
      clip.clearExpressionParams();
      for (int dim = 0;
          dim < org.deluge.modulation.params.ExpressionParamSet.NUM_DIMENSIONS;
          dim++) {
        var param = expressionParams.getParam(dim);
        if (param.containsSomething(0)) {
          clip.setExpressionParam(dim, param.currentValue, param.nodes);
        }
      }
      // Clear and apply row sound params
      for (int r = 0; r < rows; r++) {
        if (clip.hasRowSoundParams(r)) {
          clip.getRowSoundParamNames(r).clear();
        }
        NoteRowModel existingRow = clip.getNoteRowsMap().get(r);
        if (existingRow != null) {
          existingRow.setBendRange(-1);
          existingRow.clearExpressionParams();
        }
      }
      for (Map.Entry<Integer, Map<String, Float>> re : rowSoundParams.entrySet()) {
        int r = re.getKey();
        for (Map.Entry<String, Float> pe : re.getValue().entrySet()) {
          clip.setRowSoundParam(r, pe.getKey(), pe.getValue());
        }
      }
      for (Map.Entry<Integer, Integer> be : rowBendRanges.entrySet()) {
        clip.getOrCreateRow(be.getKey()).setBendRange(be.getValue());
      }
      for (Map.Entry<Integer, org.deluge.modulation.params.ExpressionParamSet> ee :
          rowExpressionParams.entrySet()) {
        NoteRowModel rowModel = clip.getOrCreateRow(ee.getKey());
        for (int dim = 0;
            dim < org.deluge.modulation.params.ExpressionParamSet.NUM_DIMENSIONS;
            dim++) {
          var param = ee.getValue().getParam(dim);
          if (param.containsSomething(0)) {
            rowModel.setExpressionParam(dim, param.currentValue, param.nodes);
          }
        }
      }
      // Apply kit params
      clip.setKitParams(kitParams);
      clip.getRawKitParamHex().putAll(rawKitParamHex);
    }

    // ── Getters / Setters ──

    public int getTrackIndex() {
      return trackIndex;
    }

    public void setTrackIndex(int v) {
      this.trackIndex = v;
    }

    public String getTrackName() {
      return trackName;
    }

    public void setTrackName(String v) {
      this.trackName = v;
    }

    public int getRowCount() {
      return rowCount;
    }

    public void setRowCount(int v) {
      this.rowCount = v;
    }

    public int getStepCount() {
      return stepCount;
    }

    public void setStepCount(int v) {
      this.stepCount = v;
    }

    public List<List<StepData>> getGrid() {
      return grid;
    }

    public Map<String, float[]> getAutomationData() {
      return automationData;
    }

    public Map<Integer, Map<String, Float>> getRowSoundParams() {
      return rowSoundParams;
    }

    public Map<String, Float> getKitParams() {
      return kitParams;
    }

    public String getInstrumentSlot() {
      return instrumentSlot;
    }

    public void setInstrumentSlot(String v) {
      this.instrumentSlot = v;
    }

    public float getVolumeOverride() {
      return volumeOverride;
    }

    public void setVolumeOverride(float v) {
      this.volumeOverride = v;
    }

    public float getPanOverride() {
      return panOverride;
    }

    public void setPanOverride(float v) {
      this.panOverride = v;
    }

    public String getColourHex() {
      return colourHex;
    }

    public void setColourHex(String v) {
      this.colourHex = v;
    }
  }

  private final List<ClipSnapshot> clipSnapshots = new ArrayList<>();

  public PatternModel(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public List<ClipSnapshot> getClipSnapshots() {
    return clipSnapshots;
  }

  public void addClipSnapshot(ClipSnapshot snap) {
    clipSnapshots.add(snap);
  }

  public void removeClipSnapshot(int index) {
    if (index >= 0 && index < clipSnapshots.size()) clipSnapshots.remove(index);
  }
}
