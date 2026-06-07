package org.chuck.deluge.firmware2;

/**
 * Faithful port of the Deluge C {@code PatchSource} enum ({@code src/definitions_cxx.hpp:298-324}).
 *
 * <p>The C declares {@code enum class PatchSource : uint8_t}. The enumerator ORDER is load-bearing:
 * cables store {@code from} and the patcher indexes {@code
 * sourceValues[util::to_underlying(source)]}, so the Java ordinals must match the C exactly. {@code
 * SOON}/{@code NOT_AVAILABLE} are GUI-shortcut sentinels (254/255 in C); kept here only for
 * completeness with explicit values.
 */
public enum PatchSource {
  // C order — ordinals 0..15
  LFO_GLOBAL_1,
  LFO_GLOBAL_2,
  SIDECHAIN,
  ENVELOPE_0,
  ENVELOPE_1,
  ENVELOPE_2,
  ENVELOPE_3,
  LFO_LOCAL_1,
  LFO_LOCAL_2,
  X,
  Y,
  AFTERTOUCH,
  VELOCITY,
  NOTE,
  RANDOM,
  NONE;

  /** C: {@code util::to_underlying(source)} — the raw enum value used to index sourceValues. */
  public int value() {
    return ordinal();
  }

  // C: constexpr PatchSource kLastPatchSource = PatchSource::NONE;
  public static final PatchSource kLastPatchSource = NONE;
  // C: constexpr int32_t kNumPatchSources = static_cast<int32_t>(kLastPatchSource);  // == 15
  public static final int kNumPatchSources = kLastPatchSource.ordinal();
  // C: constexpr PatchSource kFirstLocalSource = PatchSource::ENVELOPE_0;            // == 3
  public static final int kFirstLocalSource = ENVELOPE_0.ordinal();
}
