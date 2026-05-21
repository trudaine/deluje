package org.chuck.deluge.firmware.gui.menu;

import org.chuck.deluge.firmware.engine.FirmwareSound;
import org.chuck.deluge.firmware.modulation.params.Param;

/** Utility to build the bit-accurate Sound Editor menu structure. */
public class SoundEditor {
  public static Submenu createRootMenu(FirmwareSound sound) {
    Submenu root = new Submenu("SOUND");

    Submenu osc = new Submenu("OSCILLATORS");

    Submenu oscA = new Submenu("OSC A");
    oscA.addItem(new OscillatorTypeMenuItem("TYPE", sound, 0));
    oscA.addItem(new SampleBrowserMenu("LOAD SAMPLE", sound, 0));
    oscA.addItem(
        new IntegerRangeMenuItem(
            "VOLUME",
            127,
            0,
            127,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = (int) (v * 16909320.0);
            }));
    osc.addItem(oscA);

    Submenu oscB = new Submenu("OSC B");
    oscB.addItem(new OscillatorTypeMenuItem("TYPE", sound, 1));
    oscB.addItem(new SampleBrowserMenu("LOAD SAMPLE", sound, 1));
    oscB.addItem(
        new IntegerRangeMenuItem(
            "VOLUME",
            0,
            0,
            127,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = (int) (v * 16909320.0);
            }));
    osc.addItem(oscB);

    root.addItem(osc);

    Submenu filter = new Submenu("FILTER");
    filter.addItem(
        new IntegerRangeMenuItem(
            "LPF FREQ",
            64,
            0,
            127,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] = (int) (v * 16909320.0);
            }));
    filter.addItem(
        new IntegerRangeMenuItem(
            "LPF RES",
            0,
            0,
            127,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = (int) (v * 16909320.0);
            }));
    filter.addItem(
        new IntegerRangeMenuItem(
            "HPF FREQ",
            0,
            0,
            127,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_HPF_FREQ] = (int) (v * 16909320.0);
            }));
    root.addItem(filter);

    Submenu lfo = new Submenu("LFO");
    lfo.addItem(
        new IntegerRangeMenuItem(
            "LFO1 RATE",
            50,
            0,
            127,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_LFO_LOCAL_FREQ_1] = (int) (v * 16909320.0);
            }));
    lfo.addItem(
        new IntegerRangeMenuItem(
            "LFO2 RATE",
            50,
            0,
            127,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_LFO_LOCAL_FREQ_2] = (int) (v * 16909320.0);
            }));
    root.addItem(lfo);

    return root;
  }
}
