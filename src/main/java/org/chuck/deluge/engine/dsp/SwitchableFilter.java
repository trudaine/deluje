package org.chuck.deluge.engine.dsp;

import org.chuck.audio.ChuckUGen;
import org.chuck.audio.filter.SVFilter;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.firmware.dsp.filter.FirmwareFilter;
import org.chuck.deluge.firmware.hid.FirmwareDisplay;

/**
 * Filter that can switch between the legacy native ZDF SVF and the high-fidelity fixed-point
 * firmware port based on the G_HI_FI_MODE global.
 */
public class SwitchableFilter extends ChuckUGen {
  private final SVFilter nativeFilter;
  private final FirmwareSVFilter firmwareFilter;
  private final ChuckVM vm;

  public SwitchableFilter(float sampleRate, ChuckVM vm) {
    this.nativeFilter = new SVFilter(sampleRate);
    this.firmwareFilter = new FirmwareSVFilter();
    this.vm = vm;
  }

  public void freq(double f) {
      nativeFilter.freq(f);
      // Normalize f (0-20000) to 0-1 for firmware wrapper
      firmwareFilter.setConfig((float) (f / 22050.0), (float) nativeFilter.Q(), getFirmwareMode(), (float) nativeFilter.morph());
      FirmwareDisplay.get().displayNotification("FREQ", String.format("%.1f", f));
  }

  public void Q(double q) {
      nativeFilter.Q(q);
      firmwareFilter.setConfig((float) (nativeFilter.freq() / 22050.0), (float) q, getFirmwareMode(), (float) nativeFilter.morph());
      FirmwareDisplay.get().displayNotification("RES", String.format("%.2f", q));
  }

  public void morph(double m) {
      nativeFilter.morph(m);
      firmwareFilter.setConfig((float) (nativeFilter.freq() / 22050.0), (float) nativeFilter.Q(), getFirmwareMode(), (float) m);
      FirmwareDisplay.get().displayNotification("MORPH", String.format("%.2f", m));
  }

  public void drive(float d) {
    nativeFilter.drive(d);
    // Firmware SVFilter doesn't have explicit drive, but it's handled via saturation
  }

  public void notchMode(boolean b) {
    nativeFilter.notchMode(b);
    firmwareFilter.setConfig(
        (float) (nativeFilter.freq() / 22050.0),
        (float) nativeFilter.Q(),
        getFirmwareMode(),
        (float) nativeFilter.morph());
  }

  private FirmwareFilter.FilterMode getFirmwareMode() {
    if (nativeFilter.notchMode()) return FirmwareFilter.FilterMode.SVF_NOTCH;
    // The firmware SVF is morphing, we use SVF_BAND for BP if morph=0.5
    // But the SVFilter.java morphs LP -> BP -> HP.
    // We'll use SV_BAND as it's the closest ported match for now.
    return FirmwareFilter.FilterMode.SVF_BAND;
  }

  public void reset() {
    nativeFilter.reset();
    firmwareFilter.firmware.resetFilter();
  }

  @Override
  protected float compute(float input, long systemTime) {
    if (vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0) {
      return firmwareFilter.tick(input, systemTime);
    } else {
      return nativeFilter.tick(input, systemTime);
    }
  }
}
