package org.deluge.playback;

public enum SyncLevel {
  SYNC_LEVEL_NONE(0),
  SYNC_LEVEL_WHOLE(1),
  SYNC_LEVEL_2ND(2),
  SYNC_LEVEL_4TH(3),
  SYNC_LEVEL_8TH(4),
  SYNC_LEVEL_16TH(5),
  SYNC_LEVEL_32ND(6),
  SYNC_LEVEL_64TH(7),
  SYNC_LEVEL_128TH(8),
  SYNC_LEVEL_256TH(9);

  public final int value;

  SyncLevel(int value) {
    this.value = value;
  }
}
