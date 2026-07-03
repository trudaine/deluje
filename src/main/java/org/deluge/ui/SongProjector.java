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
   * The final LED colour lighting column {@code step}, or null if no note reaches it. Mirrors
   * {@code NoteRow::renderRow} (note_row.cpp:1881) at one-step-per-square resolution, with the
   * highest-index note row winning (the C render-order overwrite):
   *
   * <ul>
   *   <li><b>Head</b> (a note starts at this step): the pitch's main colour {@code fromHue((pitch +
   *       colourOffset) * -8/3)} scaled by velocity {@code * (65 + v + v/2)/255} (v = 0..127) —
   *       {@code getMainColourFromY} × the velocity fraction at note_row.cpp:1967.
   *   <li><b>Tail</b> (a note starting earlier extends past this step's start): {@code
   *       forTail(mainColour)} (DelugePadButton.getTailColor).
   *   <li>Otherwise this row draws nothing here.
   * </ul>
   *
   * Shared by {@link #project} (unit-tested) and the per-cell session render so both agree.
   */
  static Color noteColourAt(ClipModel clip, int colourOffset, int step) {
    if (step < 0 || step >= clip.getStepCount()) return null;
    Color colour = null;
    int rowCount = clip.getRowCount();
    for (int r = 0; r < rowCount; r++) {
      Color draw = rowDrawAt(clip, colourOffset, r, step);
      if (draw != null) colour = draw; // higher-index row overwrites
    }
    return colour;
  }

  /** What note row {@code r} draws at {@code step}: head colour, tail colour, or null. */
  private static Color rowDrawAt(ClipModel clip, int colourOffset, int r, int step) {
    Color main = DelugeColour.fromHue((clip.getRowYNote(r) + colourOffset) * -8 / 3);
    StepData here = clip.getStep(r, step);
    if (here != null && here.active()) {
      int v = Math.round(here.velocity() * 127f); // model velocity is 0..1
      int num = 65 + v + v / 2; // note_row.cpp:1967
      return scaleToward(main, num);
    }
    // Tail: the nearest earlier note in this row that extends past the start of this square.
    for (int s = step - 1; s >= 0; s--) {
      StepData sd = clip.getStep(r, s);
      if (sd != null && sd.active()) {
        return (s + sd.gate() > step) ? DelugePadButton.getTailColor(main) : null;
      }
    }
    return null;
  }

  /** {@code RGB::adjustFractional(num<<8, 255<<8)} — channel * num / 255, clamped. */
  private static Color scaleToward(Color c, int num) {
    return new Color(
        Math.min(255, c.getRed() * num / 255),
        Math.min(255, c.getGreen() * num / 255),
        Math.min(255, c.getBlue() * num / 255));
  }
}
