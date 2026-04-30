package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a single sequence (clip/pattern) within a Track. Holds a 2D grid of StepData. For a
 * Kit Track: rows = distinct sounds (e.g. Kick, Snare) For a Synth Track: rows = pitches (piano
 * roll)
 */
public class ClipModel {
  private String name;
  private int rowCount;
  private int stepCount;
  private final List<List<StepData>> grid = new ArrayList<>();
  private String color = "#00ffcc"; // Default color

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
  }

  public StepData getStep(int row, int step) {
    if (row >= 0 && row < rowCount && step >= 0 && step < stepCount) {
      return grid.get(row).get(step);
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
    if (row >= 0 && step >= 0) {
      grid.get(row).set(step, data);
      for (ClipListener l : listeners) {
        l.onStepChanged(row, step, data);
      }
    }
  }
}
