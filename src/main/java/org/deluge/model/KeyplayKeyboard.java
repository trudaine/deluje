package org.deluge.model;

/**
 * Pure musical logic utility for the Deluge's isomorphic keyboard (KEYPLAY) and velocity-drums
 * layouts.
 *
 * <p>This class contains no GUI dependencies and mirrors the C++ firmware's layout mappings ({@code
 * isomorphic.h} and {@code velocity_drums.cpp}) exactly.
 */
public final class KeyplayKeyboard {
  private KeyplayKeyboard() {}

  /**
   * The base MIDI note for the isomorphic keyboard layout (default 50 = D3). In the C++ firmware,
   * this is derived from: {@code scrollOffset = 60 - (kDisplayHeight >> 2) * 5 = 50}
   * (state_data.h:26-29).
   */
  public static final int BASE_NOTE = 50;

  /**
   * The row interval for the isomorphic keyboard layout (default 5 semitones, i.e. a fourth). In
   * the C++ firmware, this is {@code kDefaultIsometricRowInterval = 5} (isomorphic.h:44).
   */
  public static final int ROW_INTERVAL = 5;

  /**
   * Maps a grid pad coordinate to a MIDI note in the isomorphic keyboard layout.
   *
   * <p>Formula: {@code note = BASE_NOTE + colId + (7 - trk) * ROW_INTERVAL} where {@code trk} is
   * the row index from the top (0-7) and {@code colId} is the column (0-15). The bottom-left pad
   * (row 7, col 0) is MIDI 50, and each row up increases the pitch by 5 semitones.
   *
   * @param trk the visual row index from the top (0 to 7)
   * @param colId the column index (0 to 15)
   * @return the mapped MIDI note number
   */
  public static int getNote(int trk, int colId) {
    return BASE_NOTE + colId + (7 - trk) * ROW_INTERVAL;
  }

  /**
   * Maps a grid pad coordinate to a drum slot index in the velocity-drums layout.
   *
   * <p>Formula: {@code drumIndex = colId + (7 - trk) * 16} where {@code trk} is the row index from
   * the top (0-7) and {@code colId} is the column (0-15). This lays out the drum slots sequentially
   * from left-to-right, bottom-to-top, in rows of 16.
   *
   * @param trk the visual row index from the top (0 to 7)
   * @param colId the column index (0 to 15)
   * @return the mapped drum slot index (0 to 127)
   */
  public static int getDrumIndex(int trk, int colId) {
    return colId + (7 - trk) * 16;
  }

  /**
   * Maps a coordinate in folded in-key scale mode to a MIDI note.
   */
  public static int getNoteInKey(
      int trk,
      int colId,
      int[] scaleNotes,
      int rootNote,
      int rowInterval,
      int scrollOffset) {
    int scaleNoteCount = scaleNotes.length;
    int y = 7 - trk;
    int padIndex = scrollOffset + colId + y * rowInterval;
    int octave = padIndex / scaleNoteCount;
    int octaveNoteIndex = padIndex % scaleNoteCount;
    return octave * 12 + rootNote + scaleNotes[octaveNoteIndex];
  }

  /**
   * Maps a pad coordinate to a MIDI note, automatically handling scaleModeEnabled.
   */
  public static int getNote(
      int trk,
      int colId,
      boolean scaleModeEnabled,
      String key,
      String scaleName) {
    if (!scaleModeEnabled) {
      return BASE_NOTE + colId + (7 - trk) * ROW_INTERVAL;
    } else {
      int rootNote = ScaleMapper.getKeyMidiOffset(key);
      int[] scaleNotes = ScaleMapper.scaleTypeFromName(scaleName).getIntervals();
      int rowInterval = 3; // Default 3 scale degrees
      int scrollOffset = scaleNotes.length * 3; // Default 3 octaves offset
      return getNoteInKey(trk, colId, scaleNotes, rootNote, rowInterval, scrollOffset);
    }
  }
}
