package org.deluge.firmware.model.note;

import org.deluge.firmware.model.Positionable;
import org.deluge.firmware.model.iterance.Iterance;

public class Note extends Positionable {
  public int length;
  public byte velocity;
  public byte probability;
  public Iterance iterance = new Iterance();
  public byte fill;
  public byte lift;

  public Note() {}

  public void setLength(int newLength) {
    this.length = newLength;
  }

  public int getLength() {
    return length;
  }

  public void setVelocity(int newVelocity) {
    this.velocity = (byte) newVelocity;
  }

  public int getVelocity() {
    return velocity & 0xFF;
  }

  public void setLift(int newLift) {
    this.lift = (byte) newLift;
  }

  public int getLift() {
    return lift & 0xFF;
  }

  public void setProbability(int newProbability) {
    this.probability = (byte) newProbability;
  }

  public int getProbability() {
    return probability & 0xFF;
  }

  public void setIterance(Iterance newIterance) {
    this.iterance = newIterance;
  }

  public Iterance getIterance() {
    return iterance;
  }

  public void setFill(int newFill) {
    this.fill = (byte) newFill;
  }

  public int getFill() {
    return fill & 0xFF;
  }

  public boolean isDrone(int effectiveLength) {
    return (pos == 0 && length == effectiveLength);
  }
}
