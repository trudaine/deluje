package org.chuck.deluge.engine.dsp;

import org.chuck.audio.ChuckUGen;
import org.chuck.audio.fx.Dyno;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Compressor that can switch between ChucK's Dyno and the high-fidelity firmware port. */
public class SwitchableCompressor extends ChuckUGen {
  private final Dyno nativeComp;
  private final FirmwareCompressor firmwareComp;
  private final ChuckVM vm;

  public SwitchableCompressor(float sampleRate, ChuckVM vm) {
    this.nativeComp = new Dyno(sampleRate);
    this.nativeComp.compressor();
    this.firmwareComp = new FirmwareCompressor();
    this.vm = vm;
  }

  public void threshold(float t) {
    nativeComp.thresh(t);
    firmwareComp.setThreshold(t);
  }

  public void ratio(float r) {
    nativeComp.ratio(r);
    firmwareComp.setRatio(r);
  }

  public void compressor() {
    nativeComp.compressor();
  }

  public void attackTime(float ms) {
    nativeComp.attackTime(ms * 44.1);
  } // approximated mapping

  public void releaseTime(float ms) {
    nativeComp.releaseTime(ms * 44.1);
  }

  public void dryWet(float v) {
    nativeComp.dryWet(v);
  }

  @Override
  protected float compute(float input, long systemTime) {
    if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
      return firmwareComp.tick(input, systemTime);
    } else {
      return nativeComp.tick(input, systemTime);
    }
  }
}
