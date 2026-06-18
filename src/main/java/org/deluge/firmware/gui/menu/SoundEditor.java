package org.deluge.firmware.gui.menu;

import org.deluge.firmware.engine.FirmwareSound;
import org.deluge.firmware2.Param;

/** Utility to build the bit-accurate Sound Editor menu structure. */
public class SoundEditor {
  public static Submenu createRootMenu(FirmwareSound sound) {
    Submenu root = new Submenu("SOUND");

    Submenu osc = new Submenu("OSCILLATORS");

    Submenu oscA = new Submenu("OSC A");
    oscA.addItem(new OscillatorTypeMenuItem("TYPE", sound, 0));
    oscA.addItem(new SampleBrowserMenu("LOAD SAMPLE", sound, 0));
    oscA.addItem(createSampleSettingsMenu("SAMPLE SETTINGS", sound, 0));
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
    oscB.addItem(createSampleSettingsMenu("SAMPLE SETTINGS", sound, 1));
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

    // LPF Submenu
    Submenu lpf = new Submenu("LOWPASS (LPF)");
    lpf.addItem(
        new IntegerRangeMenuItem(
            "LPF FREQ",
            (int) (sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] * 100.0 / 2147483647.0),
            0,
            100,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] = (int) (v * 21474836.47);
            }));
    lpf.addItem(
        new IntegerRangeMenuItem(
            "LPF RES",
            (int) (sound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] * 100.0 / 2147483647.0),
            0,
            100,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = (int) (v * 21474836.47);
            }));
    lpf.addItem(
        new IntegerRangeMenuItem(
            "LPF MORPH",
            (int) (sound.paramNeutralValues[Param.LOCAL_LPF_MORPH] * 100.0 / 2147483647.0),
            0,
            100,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_LPF_MORPH] = (int) (v * 21474836.47);
            }));
    lpf.addItem(
        new IntegerRangeMenuItem(
            "LPF MODE",
            lpfModeToIndex(sound.fw2Sound.lpfMode),
            0,
            4,
            (v) -> {
              if (v == 0) return "12dB";
              if (v == 1) return "24dB";
              if (v == 2) return "DRIVE";
              if (v == 3) return "SVF BAND";
              return "SVF NOTCH";
            },
            (v) -> {
              sound.fw2Sound.lpfMode = indexToLpfMode(v);
            }));
    filter.addItem(lpf);

    // HPF Submenu
    Submenu hpf = new Submenu("HIGHPASS (HPF)");
    hpf.addItem(
        new IntegerRangeMenuItem(
            "HPF FREQ",
            (int) (sound.paramNeutralValues[Param.LOCAL_HPF_FREQ] * 100.0 / 2147483647.0),
            0,
            100,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_HPF_FREQ] = (int) (v * 21474836.47);
            }));
    hpf.addItem(
        new IntegerRangeMenuItem(
            "HPF RES",
            (int) (sound.paramNeutralValues[Param.LOCAL_HPF_RESONANCE] * 100.0 / 2147483647.0),
            0,
            100,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = (int) (v * 21474836.47);
            }));
    hpf.addItem(
        new IntegerRangeMenuItem(
            "HPF MORPH",
            (int) (sound.paramNeutralValues[Param.LOCAL_HPF_MORPH] * 100.0 / 2147483647.0),
            0,
            100,
            (v) -> {
              sound.paramNeutralValues[Param.LOCAL_HPF_MORPH] = (int) (v * 21474836.47);
            }));
    hpf.addItem(
        new IntegerRangeMenuItem(
            "HPF MODE",
            hpfModeToIndex(sound.fw2Sound.hpfMode),
            0,
            3,
            (v) -> {
              if (v == 0) return "OFF";
              if (v == 1) return "24dB";
              if (v == 2) return "SVF BAND";
              return "SVF NOTCH";
            },
            (v) -> {
              sound.fw2Sound.hpfMode = indexToHpfMode(v);
            }));
    filter.addItem(hpf);

    // Routing Item
    filter.addItem(
        new IntegerRangeMenuItem(
            "ROUTING",
            routeToCode(sound.getFilterRoute()),
            0,
            2,
            (v) -> {
              if (v == 0) return "HPF LPF";
              if (v == 1) return "LPF HPF";
              return "PARALLEL";
            },
            (v) -> {
              sound.setFilterRoute(v);
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

  private static Submenu createSampleSettingsMenu(String name, FirmwareSound sound, int oscIndex) {
    Submenu menu = new Submenu(name);
    org.deluge.firmware.model.sample.SampleVoiceSettings settings = sound.sampleSettings[oscIndex];

    // 1. START POINT
    menu.addItem(
        new IntegerRangeMenuItem(
            "START POINT",
            settings.startPoint * 100 / 65535,
            0,
            100,
            (v) -> {
              settings.startPoint = (int) (v * 655.35);
            }));

    // 2. END POINT
    menu.addItem(
        new IntegerRangeMenuItem(
            "END POINT",
            settings.endPoint * 100 / 65535,
            0,
            100,
            (v) -> {
              settings.endPoint = (int) (v * 655.35);
            }));

    // 3. LOOP START
    menu.addItem(
        new IntegerRangeMenuItem(
            "LOOP START",
            settings.loopStart * 100 / 65535,
            0,
            100,
            (v) -> {
              settings.loopStart = (int) (v * 655.35);
            }));

    // 4. LOOP END
    menu.addItem(
        new IntegerRangeMenuItem(
            "LOOP END",
            settings.loopEnd * 100 / 65535,
            0,
            100,
            (v) -> {
              settings.loopEnd = (int) (v * 655.35);
            }));

    // 5. REPEAT (LOOP MODE)
    menu.addItem(
        new IntegerRangeMenuItem(
            "REPEAT",
            settings.loopMode,
            0,
            2,
            (v) -> {
              if (v == 0) return "OFF";
              if (v == 1) return "ON";
              return "ONCE";
            },
            (v) -> {
              settings.loopMode = v;
            }));

    // 6. REVERSE
    menu.addItem(
        new ToggleMenuItem(
            "REVERSE",
            settings.reverse,
            (v) -> {
              settings.reverse = v;
            }));

    // 7. TIMESTRETCH
    menu.addItem(
        new ToggleMenuItem(
            "TIMESTRETCH",
            settings.timestretch,
            (v) -> {
              settings.timestretch = v;
            }));

    // 8. TRANSPOSE
    menu.addItem(
        new IntegerRangeMenuItem(
            "TRANSPOSE",
            settings.transpose,
            -24,
            24,
            (v) -> {
              settings.transpose = v;
            }));

    // 9. INTERPOLATION
    menu.addItem(
        new IntegerRangeMenuItem(
            "INTERPOLATION",
            settings.interpolationMode,
            0,
            2,
            (v) -> {
              if (v == 0) return "NAIVE";
              if (v == 1) return "LINEAR";
              return "SINC";
            },
            (v) -> {
              settings.interpolationMode = v;
            }));

    // 10. PITCH/SPEED MODE
    menu.addItem(
        new IntegerRangeMenuItem(
            "PITCH SPEED",
            settings.pitchSpeedMode,
            0,
            1,
            (v) -> {
              if (v == 0) return "PITCH";
              return "SPEED";
            },
            (v) -> {
              settings.pitchSpeedMode = v;
            }));

    return menu;
  }

  private static int lpfModeToIndex(org.deluge.firmware2.FilterSet.FilterMode mode) {
    if (mode == org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_12DB) return 0;
    if (mode == org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB) return 1;
    if (mode == org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE) return 2;
    if (mode == org.deluge.firmware2.FilterSet.FilterMode.SVF_BAND) return 3;
    return 4;
  }

  private static org.deluge.firmware2.FilterSet.FilterMode indexToLpfMode(int index) {
    if (index == 0) return org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_12DB;
    if (index == 1) return org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB;
    if (index == 2) return org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE;
    if (index == 3) return org.deluge.firmware2.FilterSet.FilterMode.SVF_BAND;
    return org.deluge.firmware2.FilterSet.FilterMode.SVF_NOTCH;
  }

  private static int hpfModeToIndex(org.deluge.firmware2.FilterSet.FilterMode mode) {
    if (mode == org.deluge.firmware2.FilterSet.FilterMode.OFF) return 0;
    if (mode == org.deluge.firmware2.FilterSet.FilterMode.HPLADDER) return 1;
    if (mode == org.deluge.firmware2.FilterSet.FilterMode.SVF_BAND) return 2;
    return 3;
  }

  private static org.deluge.firmware2.FilterSet.FilterMode indexToHpfMode(int index) {
    if (index == 0) return org.deluge.firmware2.FilterSet.FilterMode.OFF;
    if (index == 1) return org.deluge.firmware2.FilterSet.FilterMode.HPLADDER;
    if (index == 2) return org.deluge.firmware2.FilterSet.FilterMode.SVF_BAND;
    return org.deluge.firmware2.FilterSet.FilterMode.SVF_NOTCH;
  }

  private static int routeToCode(org.deluge.firmware2.FilterRoute route) {
    if (route == org.deluge.firmware2.FilterRoute.LOW_TO_HIGH) return 1;
    if (route == org.deluge.firmware2.FilterRoute.PARALLEL) return 2;
    return 0;
  }
}
