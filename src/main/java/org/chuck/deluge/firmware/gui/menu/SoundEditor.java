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

  private static Submenu createSampleSettingsMenu(String name, FirmwareSound sound, int oscIndex) {
    Submenu menu = new Submenu(name);
    org.chuck.deluge.firmware.model.sample.SampleVoiceSettings settings =
        sound.sampleSettings[oscIndex];

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
}
