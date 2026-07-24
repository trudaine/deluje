package org.deluge.ui;

import java.awt.*;
import javax.swing.*;

/** Modal dialog for configuring bar-level automation in Arrangement view. */
public class BarAutomationDialog extends JDialog {

  private final JCheckBox lpfSweep;
  private final JCheckBox volumeFadeIn;
  private boolean confirmed = false;

  public BarAutomationDialog(Frame owner, int barIndex) {
    super(owner, "Bar Automation", true);
    setSize(600, 350);
    // pack() (used e.g. by the screenshot pipeline) must not collapse the dialog to a tiny
    // fragment — keep the designed size as the preferred size.
    setPreferredSize(new Dimension(600, 350));
    setLocationRelativeTo(owner);
    JPanel content = new JPanel(new BorderLayout(0, 10));
    content.setBorder(BorderFactory.createEmptyBorder(16, 20, 12, 20));
    JPanel checks = new JPanel(new GridLayout(3, 1, 20, 20));
    checks.add(new JLabel("Timeline Bar " + (barIndex + 1) + " Automation:"));
    lpfSweep = new JCheckBox("Enable Low-Pass Filter Sweep");
    checks.add(lpfSweep);
    volumeFadeIn = new JCheckBox("Trigger Volume Fade-In");
    checks.add(volumeFadeIn);
    content.add(checks, BorderLayout.CENTER);

    JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    JButton okBtn = new JButton("Apply");
    okBtn.addActionListener(
        e -> {
          confirmed = true;
          dispose();
        });
    JButton cancelBtn = new JButton("Cancel");
    cancelBtn.addActionListener(e -> dispose());
    buttons.add(okBtn);
    buttons.add(cancelBtn);
    content.add(buttons, BorderLayout.SOUTH);
    setContentPane(content);
  }

  /** True when the user pressed Apply (the dialog is modal — read after setVisible returns). */
  public boolean isConfirmed() {
    return confirmed;
  }

  public boolean isLpfSweepEnabled() {
    return lpfSweep.isSelected();
  }

  public boolean isVolumeFadeInEnabled() {
    return volumeFadeIn.isSelected();
  }
}
