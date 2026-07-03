package org.deluge.ui;

import java.awt.Color;
import java.util.List;
import org.deluge.model.ClipModel;
import org.deluge.model.StepData;

/**
 * Pure projection of the session (SONG) view onto a fixed {@code rows × cols} pad framebuffer.
 *
 * <p>Faithful extraction of the session render at {@code SwingGridPanel#refreshInPlace} (~line
 * 3905), which mirrors the C {@code session_view.cpp renderRow} "renderAsSingleRow": each track's
 * row shows its clip's step PATTERN — column {@code c} is lit when the clip has any active note at
 * step {@code scrollX + c} in ANY note row — in the clip's own colour ({@code fromHue(colourOffset
 * * -8/3)}, resolved by the caller and passed in per row).
 *
 * <p>Unlike the arranger, the SONG X axis is bounded (steps, 1:1 with column index plus a step
 * scroll); the Y axis is the finite track list. The pad carries the track colour whether or not the
 * step is active — {@code active} gates whether the LED lights, exactly as {@code pad.setActive} +
 * {@code pad.setBaseColor} do in the renderer.
 */
final class SongProjector {
  private SongProjector() {}

  /**
   * One session display row: the track's first clip (or null) and the resolved pad colour.
   *
   * @param clip the clip whose step pattern paints the row, or null (no clip -> row stays dark but
   *     keeps its colour)
   * @param colour the pad colour for this row (e.g. {@code DelugeColour.clipColour(colourOffset)})
   */
  record Row(ClipModel clip, Color colour) {}

  /**
   * @param rows per-display-row descriptors, top-to-bottom (already in the caller's bottom-up
   *     order)
   * @param rowCount framebuffer height ({@code gridMode.rows})
   * @param cols framebuffer width (main pad columns)
   * @param scrollX step scroll offset (column {@code c} shows step {@code scrollX + c})
   */
  static PadCell[][] project(List<Row> rows, int rowCount, int cols, int scrollX) {
    PadCell[][] out = new PadCell[rowCount][cols];
    for (int r = 0; r < rowCount; r++) {
      Row row = (r < rows.size()) ? rows.get(r) : null;
      for (int c = 0; c < cols; c++) {
        if (row == null) {
          out[r][c] = PadCell.EMPTY;
        } else {
          boolean active = row.clip() != null && stepActive(row.clip(), scrollX + c);
          out[r][c] = new PadCell(row.colour(), active, null);
        }
      }
    }
    return out;
  }

  /** True when any note row of {@code clip} has an active step at {@code step}. */
  private static boolean stepActive(ClipModel clip, int step) {
    if (step < 0 || step >= clip.getStepCount()) return false;
    for (int r = 0; r < clip.getRowCount(); r++) {
      StepData sd = clip.getStep(r, step);
      if (sd != null && sd.active()) return true;
    }
    return false;
  }
}
