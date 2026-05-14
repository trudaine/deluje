package org.chuck.deluge.engine.dsp;

import org.chuck.audio.ChuckUGen;
import org.chuck.deluge.firmware.modulation.Envelope;
import org.chuck.deluge.firmware.util.LookupTables;

/** Wrapper for the high-fidelity ported Envelope. */
public class FirmwareAdsr extends ChuckUGen {
  private final Envelope firmware;
  private int attack;
  private int decay;
  private int sustain;
  private int release;

  public FirmwareAdsr() {
    this.firmware = new Envelope();
    // default values
    set(0.01, 0.1, 0.7, 0.2);
  }

  public void set(double a, double d, double s, double r) {
    // Map seconds to firmware increments
    this.attack = (int) (1.0 / (a * 44100.0) * 8388608.0);
    this.decay = (int) (1.0 / (d * 44100.0) * 8388608.0);
    this.sustain = (int) (s * 2147483647.0);
    this.release = (int) (1.0 / (r * 44100.0) * 8388608.0);
  }

  public void keyOn() {
    firmware.noteOn(false);
  }

  public void keyOff() {
    firmware.unconditionalRelease(Envelope.EnvelopeStage.RELEASE, 1024);
  }

  public void forceMute() {
    firmware.unconditionalOff();
  }

  @Override
  protected float compute(float input, long systemTime) {
    int valQ31 = firmware.render(1, attack, decay, sustain, release, LookupTables.decayTableSmall8);
    if (valQ31 == -2147483648) return 0.0f;

    // render returns (lastValue - 1073741824) << 1 ?
    // Let's check Envelope.java render return: return (lastValue - 1073741824) << 1;
    // Wait, if lastValue is 2^31-1, then (2^31 - 2^30) * 2 = 2^31.
    // It's basically converting 0..1 range to -1..1 range if it was centered?
    // No, standard ADSR is 0..1.

    float envelopeValue = (float) ((valQ31 / 2147483648.0) + 1.0) / 2.0f;
    return input * envelopeValue;
  }
}
