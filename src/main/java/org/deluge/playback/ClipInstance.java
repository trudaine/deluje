package org.deluge.playback;

public class ClipInstance extends Positionable {
  public int length;
  public Clip clip;

  public ClipInstance(Clip clip, int pos, int length) {
    this.clip = clip;
    this.pos = pos;
    this.length = length;
  }
}
