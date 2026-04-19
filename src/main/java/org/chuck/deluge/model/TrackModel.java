package org.chuck.deluge.model;

import java.util.Arrays;

/** Base abstract model for a Sequencer Track (Kit or Synth). */
public abstract class TrackModel {
  private String name;
  private final TrackType type;
  private boolean muted = false;
  private float volume = 1.0f;
  private float pan = 0.5f;
  private int stepCount = 16;

  private StepData[] steps;

  public TrackModel(String name, TrackType type, int initialCapacity) {
    this.name = name;
    this.type = type;
    this.steps = new StepData[initialCapacity];
    for (int i = 0; i < initialCapacity; i++) {
      this.steps[i] = StepData.empty();
    }
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public TrackType getType() {
    return type;
  }

  public boolean isMuted() {
    return muted;
  }

  public void setMuted(boolean muted) {
    this.muted = muted;
  }

  public float getVolume() {
    return volume;
  }

  public void setVolume(float volume) {
    this.volume = Math.max(0.0f, volume);
  }

  public float getPan() {
    return pan;
  }

  public void setPan(float pan) {
    this.pan = Math.max(0.0f, Math.min(1.0f, pan));
  }

  public int getStepCount() {
    return stepCount;
  }

  public void setStepCount(int stepCount) {
    this.stepCount = Math.max(1, stepCount);
    if (this.stepCount > this.steps.length) {
      StepData[] newSteps = Arrays.copyOf(this.steps, this.stepCount);
      for (int i = this.steps.length; i < this.stepCount; i++) {
        newSteps[i] = StepData.empty();
      }
      this.steps = newSteps;
    }
  }

  public StepData getStep(int index) {
    if (index >= 0 && index < steps.length) {
      return steps[index];
    }
    return StepData.empty();
  }

  public void setStep(int index, StepData data) {
    if (index >= 0 && index < steps.length) {
      steps[index] = data;
    }
  }
}
