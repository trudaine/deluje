package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;

/** Modal dialog for editing per-step properties (velocity). */
public class StepPropertiesDialog extends JDialog {

  private final JSlider velSlider;
  private final JSpinner velSpin;

  public StepPropertiesDialog(Frame owner) {
    this(owner, 80);
  }

  public StepPropertiesDialog(Frame owner, int currentVelocity) {
    super(owner, "Step Properties", true);
    setSize(1600, 450);
    setLocationRelativeTo(owner);
    setLayout(new GridBagLayout());

    GridBagConstraints gc = new GridBagConstraints();
    gc.fill = GridBagConstraints.HORIZONTAL;
    gc.insets = new Insets(10, 15, 10, 15);
    Font labelFont = new Font("SansSerif", Font.BOLD, 18);
    Dimension sliderDim = new Dimension(1200, 50);
    Dimension spinDim = new Dimension(80, 40);

    gc.gridx = 0;
    gc.gridy = 0;
    JLabel l1 = new JLabel("Velocity:");
    l1.setFont(labelFont);
    add(l1, gc);
    gc.gridx = 1;
    velSlider = new JSlider(0, 100, currentVelocity);
    velSlider.setPreferredSize(sliderDim);
    add(velSlider, gc);
    gc.gridx = 2;
    velSpin = new JSpinner(new SpinnerNumberModel(currentVelocity, 0, 100, 1));
    velSpin.setPreferredSize(spinDim);
    add(velSpin, gc);
  }

  public int getVelocity() {
    return velSlider.getValue();
  }
}
