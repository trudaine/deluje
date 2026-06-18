package org.deluge.firmware.model;

public enum SyncType {
  SYNC_TYPE_EVEN(0),
  SYNC_TYPE_TRIPLET(10),
  SYNC_TYPE_DOTTED(19);

  public final int value;

  SyncType(int value) {
    this.value = value;
  }
}
