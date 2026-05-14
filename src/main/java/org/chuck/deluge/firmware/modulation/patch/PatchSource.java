package org.chuck.deluge.firmware.modulation.patch;

public enum PatchSource {
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

  public static final int kNumPatchSources = NONE.ordinal();
  public static final int kFirstLocalSource = ENVELOPE_0.ordinal();
}
