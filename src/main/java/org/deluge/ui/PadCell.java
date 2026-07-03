package org.deluge.ui;

import java.awt.Color;

/**
 * One resolved pad in the grid framebuffer — exactly what the physical LED shows, nothing more.
 *
 * <p>A {@code PadCell[rows][cols]} array is a <em>projection</em> of an underlying (possibly
 * unbounded) model onto the fixed-size pad matrix of the current {@code gridMode}. It carries only
 * on-screen state ({@code colour}, {@code active}, {@code text}); it must NOT carry model data
 * (tick positions, pitches, sample offsets) — those stay in the model, and the scroll/zoom that map
 * the unbounded timeline onto the finite columns live in the projector, not here. This keeps the
 * array size-agnostic (16×16, 24×16, 8×18, …) and makes colour parity unit-testable per view.
 *
 * @param colour the LED colour (already dimmed/blurred to its final value; the renderer blits it
 *     verbatim without re-applying intensity)
 * @param active whether the pad is lit (a clip/instance/note occupies it)
 * @param text optional label for cells that show text (e.g. an empty-row placeholder); may be null
 */
record PadCell(Color colour, boolean active, String text) {
  static final PadCell EMPTY = new PadCell(null, false, null);

  static PadCell of(Color colour, boolean active) {
    return new PadCell(colour, active, null);
  }
}
