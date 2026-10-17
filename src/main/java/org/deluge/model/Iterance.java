package org.deluge.model;

/**
 * Port of the Deluge's Iterance (play condition) class (C model/iterance/iterance.h,
 * model/iterance/iterance.cpp, model/iterance/iterance_migration.cpp). Manages playback check logic
 * based on repeats (e.g. FIRST, LAST, 1:2, 2:4).
 */
public class Iterance {
  /** 0, 0 = OFF / unrestricted (C definitions_cxx.hpp:712 kDefaultIteranceValue). */
  public static final Iterance OFF = new Iterance((byte) 0, (byte) 0);

  /** 0, 1 = play on first iteration only (C definitions_cxx.hpp:716 kFirstIteranceValue). */
  public static final Iterance FIRST = new Iterance((byte) 0, (byte) 1);

  /** 0, 2 = play on last iteration only (C definitions_cxx.hpp:717 kLastIteranceValue). */
  public static final Iterance LAST = new Iterance((byte) 0, (byte) 2);

  /** 1, 1 = "1of1" custom default (C definitions_cxx.hpp:711 kCustomIteranceValue). */
  public static final Iterance CUSTOM_DEFAULT = new Iterance((byte) 1, (byte) 1);

  /** C definitions_cxx.hpp:708-710. */
  public static final int NUM_PROBABILITY_VALUES = 20;

  public static final int NUM_ITERANCE_PRESETS = 37;

  /** Alias of {@link #NUM_ITERANCE_PRESETS} (C kNumIterancePresets). */
  public static final int NUM_PRESETS = NUM_ITERANCE_PRESETS;

  public static final int CUSTOM_ITERANCE_PRESET = NUM_ITERANCE_PRESETS + 1;

  /** C lookuptables.cpp:506 iterancePresets — FIRST, LAST, then 1of2 through 8of8 (37 entries). */
  public static final Iterance[] PRESETS = buildPresets();

  private static Iterance[] buildPresets() {
    Iterance[] table = new Iterance[NUM_ITERANCE_PRESETS];
    table[0] = FIRST;
    table[1] = LAST;
    int idx = 2;
    for (int div = 2; div <= 8; div++) {
      for (int step = 0; step < div; step++) {
        table[idx++] = new Iterance((byte) div, (byte) (1 << step));
      }
    }
    return table;
  }

  public byte divisor;
  public byte iteranceStep; // std::bitset<8> in the C

  /** Defaults to OFF, like a C note's {@code iterance} (note.h, kDefaultIteranceValue). */
  public Iterance() {
    this.divisor = 0;
    this.iteranceStep = 0;
  }

  public Iterance(byte divisor, byte iteranceStep) {
    this.divisor = divisor;
    this.iteranceStep = iteranceStep;
  }

  public boolean passesCheck(int repeatCount) {
    return passesCheck(repeatCount, false);
  }

  /**
   * C iterance.h:46-57. Divisor 0 is not a divisor: step 1 is overloaded as FIRST ({@code
   * repeatCount == 0}) and every other step as LAST ({@code ending}) — the C's {@code else}.
   */
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

  /** C iterance.cpp:58-60. */
  public int toInt() {
    return ((divisor & 0xFF) << 8) | (iteranceStep & 0xFF);
  }

  /** Decodes a 16-bit packed iterance value with firmware validation (C iterance.cpp:62-70). */
  public static Iterance fromInt(int value) {
    int div = (value >> 8) & 0xFF;
    int step = value & 0xFF;
    if ((div == 0 && step != 1 && step != 2) || div > 8) {
      return new Iterance(OFF.divisor, OFF.iteranceStep);
    }
    return new Iterance((byte) div, (byte) step);
  }

  /** C iterance.cpp:94-105 — 1-based preset index (1..37) or 38 for custom. */
  public static Iterance fromPresetIndex(int presetIndex) {
    if (presetIndex > 0 && presetIndex <= NUM_ITERANCE_PRESETS) {
      Iterance p = PRESETS[presetIndex - 1];
      return new Iterance(p.divisor, p.iteranceStep);
    } else if (presetIndex == CUSTOM_ITERANCE_PRESET) {
      return new Iterance(CUSTOM_DEFAULT.divisor, CUSTOM_DEFAULT.iteranceStep);
    }
    return new Iterance(OFF.divisor, OFF.iteranceStep);
  }

  /**
   * Legacy C1.2 probability byte migration (C iterance_migration.cpp:26-36). Prepended FIRST/LAST
   * shifted old 1-based preset index {@code probability - 20} to array index {@code probability -
   * 19} (2..36).
   */
  public static Iterance fromLegacyProbabilityByte(int probability) {
    int idx = probability - NUM_PROBABILITY_VALUES + 1;
    if (idx < 2 || idx >= NUM_ITERANCE_PRESETS) {
      return new Iterance(OFF.divisor, OFF.iteranceStep);
    }
    Iterance p = PRESETS[idx];
    return new Iterance(p.divisor, p.iteranceStep);
  }

  /**
   * Legacy C1.3-nightly 26-char preset index migration (C iterance_migration.cpp:38-51). Shifts
   * non-zero preset index by +2 to skip prepended FIRST and LAST.
   */
  public static Iterance fromLegacyPresetIndex(int presetIndex) {
    if (presetIndex == 0) {
      return new Iterance(OFF.divisor, OFF.iteranceStep);
    }
    int newIndex = presetIndex + 2;
    if (newIndex <= NUM_ITERANCE_PRESETS) {
      Iterance p = PRESETS[newIndex - 1];
      return new Iterance(p.divisor, p.iteranceStep);
    }
    if (newIndex == CUSTOM_ITERANCE_PRESET) {
      return new Iterance(CUSTOM_DEFAULT.divisor, CUSTOM_DEFAULT.iteranceStep);
    }
    return new Iterance(OFF.divisor, OFF.iteranceStep);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof Iterance other)) return false;
    return divisor == other.divisor && iteranceStep == other.iteranceStep;
  }

  @Override
  public int hashCode() {
    return toInt();
  }
}
