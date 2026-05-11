package org.chuck.deluge.model;

/** A gate/trigger-output drum on a Kit track — sends a trigger pulse rather than playing a sample. */
public class GateDrum extends Drum {

  public enum GateType { TRIGGER, GATE }
  public enum Polarity { POSITIVE, NEGATIVE }

  private GateType gateType = GateType.TRIGGER;
  private float triggerDurationMs = 10.0f;
  private Polarity polarity = Polarity.POSITIVE;
  private float pulseWidthMs = 1.0f;

  public GateDrum(String name) {
    super(name);
  }

  public GateType getGateType() { return gateType; }
  public void setGateType(GateType v) { this.gateType = v; }

  public float getTriggerDurationMs() { return triggerDurationMs; }
  public void setTriggerDurationMs(float v) { this.triggerDurationMs = Math.max(0.1f, v); }

  public Polarity getPolarity() { return polarity; }
  public void setPolarity(Polarity v) { this.polarity = v; }

  public float getPulseWidthMs() { return pulseWidthMs; }
  public void setPulseWidthMs(float v) { this.pulseWidthMs = Math.max(0.1f, v); }
}
