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
    // pack() (used e.g. by the screenshot pipeline) must not collapse the dialog to a tiny
    // fragment — keep the designed size as the preferred size.
    setPreferredSize(new Dimension(600, 350));
    setLocationRelativeTo(owner);
    JPanel content = new JPanel(new GridLayout(3, 1, 20, 20));
    content.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
    content.add(new JLabel("Timeline Bar " + (barIndex + 1) + " Automation:"));
    lpfSweep = new JCheckBox("Enable Low-Pass Filter Sweep");
    content.add(lpfSweep);
    volumeFadeIn = new JCheckBox("Trigger Volume Fade-In");
    content.add(volumeFadeIn);
    setContentPane(content);
  }

  public boolean isLpfSweepEnabled() {
    return lpfSweep.isSelected();
  }

  public boolean isVolumeFadeInEnabled() {
    return volumeFadeIn.isSelected();
  }
}
