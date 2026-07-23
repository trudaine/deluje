package org.deluge.model;

/** The filter models available in the Deluge (LPF and HPF). */
public enum FilterMode {
  LADDER_12,
  LADDER_24,
  SVF,
  DRIVE,
  SVF_BAND,
  SVF_NOTCH,
  // C filter_config.h:31 — filter bypassed; serialized as "Off" since firmware #4688. Keep last
  // so existing ordinals stay stable.
  OFF
}
