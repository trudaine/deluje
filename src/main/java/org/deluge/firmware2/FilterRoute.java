package org.deluge.firmware2;

/**
 * Faithful line-by-line port of {@code filter_config.h} FilterRoute enum. Specifies the routing
 * order between LowPass and HighPass filters (HP->LP, LP->HP, or Parallel).
 */
public enum FilterRoute {
  HIGH_TO_LOW,
  LOW_TO_HIGH,
  PARALLEL
}
