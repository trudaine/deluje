package org.chuck.deluge.firmware.gui.menu;

import org.chuck.deluge.firmware.engine.FirmwareSound;

/**
 * Utility to build the bit-accurate Sound Editor menu structure.
 */
public class SoundEditor {
    public static Submenu createRootMenu(FirmwareSound sound) {
        Submenu root = new Submenu("SOUND");
        
        Submenu osc = new Submenu("OSCILLATORS");
        osc.addItem(new ToggleMenuItem("OSC1 SYNC", false));
        osc.addItem(new IntegerRangeMenuItem("OSC1 PW", 50, 0, 100));
        osc.addItem(new IntegerRangeMenuItem("OSC2 DETUNE", 0, -50, 50));
        root.addItem(osc);
        
        Submenu filter = new Submenu("FILTER");
        filter.addItem(new IntegerRangeMenuItem("LPF FREQ", 64, 0, 127));
        filter.addItem(new IntegerRangeMenuItem("LPF RES", 0, 0, 127));
        root.addItem(filter);
        
        Submenu lfo = new Submenu("LFO");
        lfo.addItem(new IntegerRangeMenuItem("LFO1 RATE", 50, 0, 127));
        lfo.addItem(new IntegerRangeMenuItem("LFO2 RATE", 50, 0, 127));
        root.addItem(lfo);
        
        return root;
    }
}
