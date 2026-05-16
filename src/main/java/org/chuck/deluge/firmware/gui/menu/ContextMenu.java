package org.chuck.deluge.firmware.gui.menu;

import org.chuck.deluge.firmware.hid.ActionResult;
import org.chuck.deluge.firmware.hid.Button;

/**
 * High-fidelity model for Context Menus (e.g. Save, Load, Clip Settings).
 * Compatible with the firmware's view-stack and Swing rendering.
 */
public abstract class ContextMenu extends MenuItem {
    public ContextMenu(String name) {
        super(name);
    }

    public abstract String[] getOptions();
    
    public abstract void onSelectOption(int index);

    /** Called when the 'Select' button is pressed. */
    public abstract ActionResult acceptCurrentOption();

    @Override
    public ActionResult enter() {
        return acceptCurrentOption();
    }
}
