package org.deluge.ui;

import java.awt.*;
import javax.swing.*;

/** Modal dialog for configuring bar-level automation in Arrangement view. */
public class BarAutomationDialog extends JDialog {

  private final JCheckBox lpfSweep;
  private final JCheckBox volumeFadeIn;

  public BarAutomationDialog(Frame owner, int barIndex) {
    super(owner, "Bar Automation", true);
    setSize(600, 350);
    setLocationRelativeTo(owner);
    setLayout(new GridLayout(3, 1, 20, 20));
    add(new JLabel("  Timeline Bar " + (barIndex + 1) + " Automation:"));
    lpfSweep = new JCheckBox("Enable Low-Pass Filter Sweep");
    add(lpfSweep);
    volumeFadeIn = new JCheckBox("Trigger Volume Fade-In");
    add(volumeFadeIn);
  }

  public boolean isLpfSweepEnabled() {
    return lpfSweep.isSelected();
  }

  public boolean isVolumeFadeInEnabled() {
    return volumeFadeIn.isSelected();
  }
}
