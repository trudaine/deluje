package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;

/** Modal dialog for editing per-step properties (velocity, iterance, fill). */
public class StepPropertiesDialog extends JDialog {

  private final JSlider velSlider;
  private final JSpinner velSpin;
  private final JSpinner iterSpin;
  private final JSlider fillSlider;

  public StepPropertiesDialog(Frame owner) {
    this(owner, 80, 0, 0);
  }

  public StepPropertiesDialog(Frame owner, int currentVelocity) {
    this(owner, currentVelocity, 0, 0);
  }

  public StepPropertiesDialog(
      Frame owner, int currentVelocity, int currentIterance, int currentFill) {
    super(owner, "Step Properties", true);
    setSize(1600, 550);
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

    // Row 2: iterance / repeats
    gc.gridx = 0;
    gc.gridy = 1;
    JLabel l2 = new JLabel("Repeats:");
    l2.setFont(labelFont);
    add(l2, gc);
    gc.gridx = 1;
    JLabel iterDesc = new JLabel("Extra sub-triggers per step (0=none, 3=max)");
    iterDesc.setFont(new Font("SansSerif", Font.PLAIN, 14));
    add(iterDesc, gc);
    gc.gridx = 2;
    iterSpin = new JSpinner(new SpinnerNumberModel(currentIterance, 0, 3, 1));
    iterSpin.setPreferredSize(spinDim);
    add(iterSpin, gc);

    // Row 3: fill percentage
    gc.gridx = 0;
    gc.gridy = 2;
    JLabel l3 = new JLabel("Fill %:");
    l3.setFont(labelFont);
    add(l3, gc);
    gc.gridx = 1;
    fillSlider = new JSlider(0, 100, currentFill);
    fillSlider.setPreferredSize(sliderDim);
    fillSlider.setToolTipText("0 = regular step, 1-100 = fill-only step with probability");
    add(fillSlider, gc);
    gc.gridx = 2;
    JLabel fillPct = new JLabel("%");
    fillPct.setFont(labelFont);
    add(fillPct, gc);
  }

  public int getVelocity() {
    return velSlider.getValue();
  }

  public int getIterance() {
    return (Integer) iterSpin.getValue();
  }

  public int getFill() {
    return fillSlider.getValue();
  }
}
