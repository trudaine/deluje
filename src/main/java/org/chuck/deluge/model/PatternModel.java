package org.chuck.deluge.model;

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
   * A snapshot of a single clip/track within this pattern. Holds the full step grid,
   * automation data, row sound params, and kit params so the pattern can be saved to
   * disk and reloaded independently of the active project.
   */
  public static class ClipSnapshot {
    private int trackIndex;
    private String trackName;
    private int rowCount;
    private int stepCount;
    private final List<List<StepData>> grid = new ArrayList<>();
    private final Map<String, float[]> automationData = new HashMap<>();
    private final Map<Integer, Map<String, Float>> rowSoundParams = new HashMap<>();
    private final Map<String, Float> kitParams = new HashMap<>();
    private String instrumentSlot = "";
    private float volumeOverride = -1f;
    private float panOverride = -1f;
    private String colourHex = "#00ffcc";

    public ClipSnapshot() {}

    public ClipSnapshot(int trackIndex, String trackName, int rowCount, int stepCount) {
      this.trackIndex = trackIndex;
      this.trackName = trackName;
      this.rowCount = rowCount;
      this.stepCount = stepCount;
    }

    /**
     * Build a ClipSnapshot from an in-memory ClipModel and track info.
     * Deep-copies all step, automation, row sound param, and kit param data.
     */
    public static ClipSnapshot fromClipModel(ClipModel clip, int trackIndex, String trackName) {
      ClipSnapshot snap = new ClipSnapshot(trackIndex, trackName, clip.getRowCount(), clip.getStepCount());
      snap.colourHex = clip.getColor();
      // Deep-copy grid
      for (int r = 0; r < clip.getRowCount(); r++) {
        List<StepData> row = new ArrayList<>();
        for (int s = 0; s < clip.getStepCount(); s++) {
          row.add(clip.getStep(r, s));
        }
        snap.grid.add(row);
      }
      // Deep-copy automation data
      for (String param : clip.getAutomatedParams()) {
        float[] arr = clip.getAutomationArray(param);
        if (arr != null) {
          snap.automationData.put(param, arr.clone());
        }
      }
      // Deep-copy row sound params
      for (int r = 0; r < clip.getRowCount(); r++) {
        if (clip.hasRowSoundParams(r)) {
          snap.rowSoundParams.put(r, new HashMap<>(clip.getRowSoundParamNames(r).size()));
          for (String pn : clip.getRowSoundParamNames(r)) {
            snap.rowSoundParams.get(r).put(pn, clip.getRowSoundParam(r, pn));
          }
        }
      }
      // Deep-copy kit params
      snap.kitParams.putAll(clip.getKitParams());
      return snap;
    }

    /** Apply this snapshot back into a ClipModel (destructive, overwrites existing data). */
    public void applyTo(ClipModel clip) {
      int rows = Math.max(clip.getRowCount(), rowCount);
      int steps = Math.max(clip.getStepCount(), stepCount);
      // Clear and rebuild grid
      for (int r = 0; r < rows; r++) {
        for (int s = 0; s < steps; s++) {
          StepData data = (r < rowCount && s < stepCount && r < grid.size() && s < grid.get(r).size())
              ? grid.get(r).get(s)
              : StepData.empty();
          clip.setStep(r, s, data);
        }
      }
      // Apply automation
      for (Map.Entry<String, float[]> e : automationData.entrySet()) {
        float[] arr = e.getValue();
        for (int s = 0; s < Math.min(arr.length, clip.getStepCount()); s++) {
          if (arr[s] >= 0f) {
            clip.setAutomation(e.getKey(), s, arr[s]);
          }
        }
      }
      // Apply row sound params
      for (Map.Entry<Integer, Map<String, Float>> re : rowSoundParams.entrySet()) {
        int r = re.getKey();
        for (Map.Entry<String, Float> pe : re.getValue().entrySet()) {
          clip.setRowSoundParam(r, pe.getKey(), pe.getValue());
        }
      }
      // Apply kit params
      clip.setKitParams(kitParams);
    }

    // ── Getters / Setters ──

    public int getTrackIndex() { return trackIndex; }
    public void setTrackIndex(int v) { this.trackIndex = v; }
    public String getTrackName() { return trackName; }
    public void setTrackName(String v) { this.trackName = v; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int v) { this.rowCount = v; }
    public int getStepCount() { return stepCount; }
    public void setStepCount(int v) { this.stepCount = v; }
    public List<List<StepData>> getGrid() { return grid; }
    public Map<String, float[]> getAutomationData() { return automationData; }
    public Map<Integer, Map<String, Float>> getRowSoundParams() { return rowSoundParams; }
    public Map<String, Float> getKitParams() { return kitParams; }
    public String getInstrumentSlot() { return instrumentSlot; }
    public void setInstrumentSlot(String v) { this.instrumentSlot = v; }
    public float getVolumeOverride() { return volumeOverride; }
    public void setVolumeOverride(float v) { this.volumeOverride = v; }
    public float getPanOverride() { return panOverride; }
    public void setPanOverride(float v) { this.panOverride = v; }
    public String getColourHex() { return colourHex; }
    public void setColourHex(String v) { this.colourHex = v; }
  }

  private final List<ClipSnapshot> clipSnapshots = new ArrayList<>();

  public PatternModel(String id, String name) {
    this.id = id;
    this.name = name;
  }

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }
  public String getName() { return name; }
  public void setName(String name) { this.name = name; }

  public String getCategory() { return category; }
  public void setCategory(String category) { this.category = category; }

  public List<ClipSnapshot> getClipSnapshots() { return clipSnapshots; }

  public void addClipSnapshot(ClipSnapshot snap) { clipSnapshots.add(snap); }

  public void removeClipSnapshot(int index) {
    if (index >= 0 && index < clipSnapshots.size()) clipSnapshots.remove(index);
  }
}
