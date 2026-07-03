package org.deluge.ui;

import java.awt.Color;
import java.util.List;
import org.deluge.model.ClipModel;
import org.deluge.model.StepData;

/**
 * Pure projection of the session (SONG) view onto a fixed {@code rows × cols} pad framebuffer.
 *
 * <p>Faithful to the hardware's {@code session_view.cpp renderRow} → {@code
 * InstrumentClip::renderAsSingleRow}: each track's row shows its clip's step PATTERN, and every lit
 * column is coloured by the <b>per-pitch rainbow of the note there</b> — {@code
 * getMainColourFromY(yNote) = fromHue((pitch + colourOffset) * -8/3)} (instrument_clip.cpp:1234) —
 * NOT a flat track colour. When several note rows have a note in the same column the higher-index
 * row wins, mirroring the C's render-order overwrite. Columns with no note are unlit (black), as
 * the firmware memsets the row to 0 and only writes lit columns.
 *
 * <p>Unlike the arranger, the SONG X axis is bounded (steps, 1:1 with column index plus a step
 * scroll); the Y axis is the finite track list.
 */
final class SongProjector {
  private SongProjector() {}

  /**
   * One session display row: the track's first clip (or null) and the clip's colour offset (the
   * track {@code colourOffset}, added to each note's pitch before {@code fromHue}).
   *
   * @param clip the clip whose pattern paints the row, or null (empty row)
   * @param colourOffset the clip/track colour offset fed into {@code getMainColourFromY}
   */
  record Row(ClipModel clip, int colourOffset) {}

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
        Color colour =
            (row == null || row.clip() == null)
                ? null
                : noteColourAt(row.clip(), row.colourOffset(), scrollX + c);
        out[r][c] = colour == null ? PadCell.EMPTY : PadCell.of(colour, true);
      }
    }
    return out;
  }

  /**
   * The per-pitch rainbow colour of the note lighting column {@code step}, or null if no note. The
   * highest-index note row with an active step wins (mirrors the C render-order overwrite); its
   * colour is {@code fromHue((pitch + colourOffset) * -8/3)} — {@code
   * InstrumentClip::getMainColourFromY}. Shared by {@link #project} (unit-tested) and the per-cell
   * session render so both agree.
   */
  static Color noteColourAt(ClipModel clip, int colourOffset, int step) {
    if (step < 0 || step >= clip.getStepCount()) return null;
    Color colour = null;
    int rowCount = clip.getRowCount();
    for (int r = 0; r < rowCount; r++) {
      StepData sd = clip.getStep(r, step);
      if (sd != null && sd.active()) {
        int pitch = clip.getRowYNote(r);
        colour = DelugeColour.fromHue((pitch + colourOffset) * -8 / 3);
      }
    }
    return colour;
  }

  /** True when any note row of {@code clip} has an active step at {@code step}. */
  static boolean stepActive(ClipModel clip, int step) {
    if (step < 0 || step >= clip.getStepCount()) return false;
    for (int r = 0; r < clip.getRowCount(); r++) {
      StepData sd = clip.getStep(r, step);
      if (sd != null && sd.active()) return true;
    }
    return false;
  }
}
