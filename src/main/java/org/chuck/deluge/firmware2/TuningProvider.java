package org.chuck.deluge.firmware2;

/**
 * Neutral hook that lets the host engine supply a custom (microtonal) tuning to the otherwise
 * faithfully C-ported {@link Voice} pitch math, without firmware2 depending on the upper
 * bridge/model layer.
 *
 * <p>The C firmware uses only the static global {@code noteIntervalTable[12]} / {@code
 * noteFrequencyTable[12]} (see {@code voice.cpp}). Microtuning is an engine-level extension: when a
 * {@code Sound} has no provider set, {@link Voice} falls back to the static {@link LookupTables}
 * and behaves exactly like the C. When a provider is set, the same pitch math runs against the
 * provider's per-octave tables instead. This keeps firmware2 free of any upward dependency on
 * {@code firmware.model}.
 */
public interface TuningProvider {

  /**
   * The octave index for the given note code (firmware uses floor division by notes-per-octave).
   */
  int octaveOf(int noteCode);

  /** The note index within its octave for the given note code (floor modulo notes-per-octave). */
  int noteWithinOctaveOf(int noteCode);

  /** Q30 interval ratio for a note within the octave (mirrors {@code noteIntervalTable[n]}). */
  int noteIntervalRatio(int noteWithinOctave);

  /**
   * Absolute frequency phase increment for a note within the octave ({@code
   * noteFrequencyTable[n]}).
   */
  int noteFrequencyRatio(int noteWithinOctave);
}
