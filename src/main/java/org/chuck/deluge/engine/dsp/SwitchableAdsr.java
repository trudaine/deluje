package org.chuck.deluge.engine.dsp;

import org.chuck.audio.ChuckUGen;
import org.chuck.audio.util.DelugeAdsr;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * ADSR that can switch between the conversion-to-float DelugeAdsr and the high-fidelity
 * bit-accurate FirmwareAdsr.
 */
public class SwitchableAdsr extends ChuckUGen {
  private final DelugeAdsr nativeAdsr;
  private final FirmwareAdsr firmwareAdsr;
  private final ChuckVM vm;

  public SwitchableAdsr(float sampleRate, ChuckVM vm) {
    this.nativeAdsr = new DelugeAdsr(sampleRate);
    this.firmwareAdsr = new FirmwareAdsr();
    this.vm = vm;
  }

  public void set(double a, double d, double s, double r) {
    nativeAdsr.set(a, d, s, r);
    firmwareAdsr.set(a, d, s, r);
  }

  public void keyOn() {
    nativeAdsr.keyOn();
    firmwareAdsr.keyOn();
  }

  public void keyOff() {
    nativeAdsr.keyOff();
    firmwareAdsr.keyOff();
  }

  public void forceMute() {
    nativeAdsr.forceMute();
    firmwareAdsr.forceMute();
  }

  @Override
  protected float compute(float input, long systemTime) {
    if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
      return firmwareAdsr.tick(input, systemTime);
    } else {
      return nativeAdsr.tick(input, systemTime);
    }
  }
}
