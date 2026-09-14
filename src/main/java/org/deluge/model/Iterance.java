package org.deluge.model;

/**
 * Port of the Deluge's Iterance play condition (model/iterance/iterance.h, iterance.cpp): a note
 * plays on chosen repeats of its clip — "1of2", "3of4", or custom step sets.
 *
 * <p>{@code divisor} 1..8 with {@code iteranceStep} a bitset of which repeats play. {@code divisor
 * == 0} is not a divisor: it overloads step 1 as FIRST and step 2 as LAST, and {@code {0, 0}} is
 * {@link #DEFAULT} — condition OFF (definitions_cxx.hpp:712).
 */
public class Iterance {
  public byte divisor;
  public byte iteranceStep; // std::bitset<8> in the C

  /** C: definitions_cxx.hpp:709 kNumIterancePresets. */
  public static final int NUM_PRESETS = 37;

  /** C: definitions_cxx.hpp:712 kDefaultIteranceValue = Iterance{0, 0} — OFF. */
  public static final Iterance DEFAULT = new Iterance((byte) 0, (byte) 0);

  /** C: definitions_cxx.hpp:711 kCustomIteranceValue = Iterance{1, 1} — "1of1". */
  public static final Iterance CUSTOM = new Iterance((byte) 1, (byte) 1);

  /** C: definitions_cxx.hpp:710 kCustomIterancePreset. */
  public static final int CUSTOM_PRESET = NUM_PRESETS + 1;

  /** C: util/lookuptables/lookuptables.cpp:506-515 iterancePresets. */
  static final Iterance[] PRESETS = buildPresets();

  private static Iterance[] buildPresets() {
    Iterance[] p = new Iterance[NUM_PRESETS];
    int i = 0;
    p[i++] = new Iterance((byte) 0, (byte) 1); // first
    p[i++] = new Iterance((byte) 0, (byte) 2); // last
    for (int divisor = 2; divisor <= 8; divisor++) {
      for (int step = 0; step < divisor; step++) {
        p[i++] = new Iterance((byte) divisor, (byte) (1 << step));
      }
    }
    return p;
  }

  /** Defaults to OFF, like a C note's {@code iterance} (note.h). */
  public Iterance() {
    this.divisor = 0;
    this.iteranceStep = 0;
  }

  public Iterance(byte divisor, byte iteranceStep) {
    this.divisor = divisor;
    this.iteranceStep = iteranceStep;
  }

  /** C: iterance.h:46-57. */
  public boolean passesCheck(int repeatCount, boolean ending) {
    // these aren't valid divisors so overloaded to be first/last instead
    if (divisor == 0) {
      if ((iteranceStep & 0xFF) == 1) {
        return repeatCount == 0;
      } else {
        return ending;
      }
    }
    return (iteranceStep & (1 << (repeatCount % (divisor & 0xFF)))) != 0;
  }

  /** C {@code iterance != kDefaultIteranceValue} (instrument_clip.cpp:870). */
  public boolean isDefault() {
    return divisor == 0 && iteranceStep == 0;
  }

  /** C: iterance.cpp:57-59. */
  public int toInt() {
    return ((divisor & 0xFF) << 8) | (iteranceStep & 0xFF);
  }

  /** C: iterance.cpp:61-69 — including its guard against garbage. */
  public static Iterance fromInt(int value) {
    int divisor = (value >> 8) & 0xFF;
    int step = value & 0xFF;
    // guard against garbage
    if ((divisor == 0 && step != 1 && step != 2) || divisor > 8) {
      return new Iterance(DEFAULT.divisor, DEFAULT.iteranceStep);
    }
    return new Iterance((byte) divisor, (byte) step);
  }

  /** C: iterance.cpp:94-106. */
  public static Iterance fromPresetIndex(int presetIndex) {
    Iterance src;
    if (presetIndex > 0 && presetIndex <= NUM_PRESETS) {
      src = PRESETS[presetIndex - 1];
    } else if (presetIndex == CUSTOM_PRESET) {
      // Reset custom iterance to 1of1
      src = CUSTOM;
    } else {
      // Default: Off
      src = DEFAULT;
    }
    return new Iterance(src.divisor, src.iteranceStep);
  }
}
