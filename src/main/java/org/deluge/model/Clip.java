package org.deluge.model;

import java.util.Arrays;

/**
 * Represents a single 16-step sequence for one track. Stores triggers, velocity, gate, pitch, and
 * probability.
 */
public class Clip {
  private final boolean[] triggers = new boolean[16];
  private final double[] velocity = new double[16];
  private final double[] gate = new double[16];
  private final int[] pitch = new int[16];
  private final double[] probability = new double[16];

  public Clip() {
    // Default values matching BridgeContract defaults
    Arrays.fill(velocity, 0.8);
    Arrays.fill(gate, 0.9);
    Arrays.fill(pitch, 0);
    Arrays.fill(probability, 1.0);
  }

  // Getters
  public boolean getTrigger(int step) {
    return triggers[step];
  }

  public double getVelocity(int step) {
    return velocity[step];
  }

  public double getGate(int step) {
    return gate[step];
  }

  public int getPitch(int step) {
    return pitch[step];
  }

  public double getProbability(int step) {
    return probability[step];
  }

  // Setters
  public void setTrigger(int step, boolean active) {
    triggers[step] = active;
  }

  public void setVelocity(int step, double v) {
    velocity[step] = v;
  }

  public void setGate(int step, double g) {
    gate[step] = g;
  }

  public void setPitch(int step, int p) {
    pitch[step] = p;
  }

  public void setProbability(int step, double pr) {
    probability[step] = pr;
  }

  /** Deep copy another clip into this one. */
  public void copyFrom(Clip other) {
    System.arraycopy(other.triggers, 0, this.triggers, 0, 16);
    System.arraycopy(other.velocity, 0, this.velocity, 0, 16);
    System.arraycopy(other.gate, 0, this.gate, 0, 16);
    System.arraycopy(other.pitch, 0, this.pitch, 0, 16);
    System.arraycopy(other.probability, 0, this.probability, 0, 16);
  }
}
