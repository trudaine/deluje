package org.deluge.firmware.modulation.automation;

public class ParamNode {
  public int pos;
  public int value;
  public boolean interpolated;

  public ParamNode() {}

  public ParamNode(int pos, int value, boolean interpolated) {
    this.pos = pos;
    this.value = value;
    this.interpolated = interpolated;
  }
}
