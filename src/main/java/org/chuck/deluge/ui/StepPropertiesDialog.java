package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/** Modal dialog for editing per-step properties (velocity, iterance, fill). */
public class StepPropertiesDialog extends JDialog {

  private final JSlider velSlider;
  private final JSpinner velSpin;
  private final JSpinner iterSpin;
  private final JSlider fillSlider;

  private boolean confirmed = false;

  public StepPropertiesDialog(Frame owner) {
    this(owner, 80, 0, 0);
  }

  public StepPropertiesDialog(Frame owner, int currentVelocity) {
    this(owner, currentVelocity, 0, 0);
  }

  public StepPropertiesDialog(
      Frame owner, int currentVelocity, int currentIterance, int currentFill) {
    super(owner, "Step Properties", true);

    // Set modern slate background and size
    getContentPane().setBackground(SwingSynthConfigDialog.BG_DARK);
    setSize(750, 450);
    setLocationRelativeTo(owner);

    // Set standard title layout
    JPanel mainContainer = new JPanel(new BorderLayout(15, 15));
    mainContainer.setBorder(new EmptyBorder(20, 25, 20, 25));
    mainContainer.setBackground(SwingSynthConfigDialog.BG_DARK);

    JLabel titleLabel = new JLabel("STEP PARAMETER PROPERTIES", SwingConstants.CENTER);
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
    titleLabel.setForeground(SwingSynthConfigDialog.ACCENT_MINT);
    mainContainer.add(titleLabel, BorderLayout.NORTH);

    // Form grid layout inside a card sub-panel
    JPanel gridPanel = new JPanel(new GridBagLayout());
    gridPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    gridPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x30), 1),
            new EmptyBorder(15, 20, 15, 20)));

    GridBagConstraints gc = new GridBagConstraints();
    gc.fill = GridBagConstraints.HORIZONTAL;
    gc.insets = new Insets(8, 12, 8, 12);
    gc.anchor = GridBagConstraints.WEST;
    Font labelFont = new Font("SansSerif", Font.BOLD, 13);

    // Row 1: Velocity
    gc.gridx = 0;
    gc.gridy = 0;
    gc.weightx = 0.1;
    JLabel l1 = new JLabel("Velocity:");
    l1.setFont(labelFont);
    l1.setForeground(Color.WHITE);
    gridPanel.add(l1, gc);

    gc.gridx = 1;
    gc.weightx = 0.8;
    velSlider = new JSlider(0, 100, currentVelocity);
    velSlider.setBackground(SwingSynthConfigDialog.BG_CARD);
    velSlider.setForeground(SwingSynthConfigDialog.ACCENT_BLUE);
    gridPanel.add(velSlider, gc);

    gc.gridx = 2;
    gc.weightx = 0.1;
    velSpin = new JSpinner(new SpinnerNumberModel(currentVelocity, 0, 100, 1));
    velSpin.setPreferredSize(new Dimension(65, 30));
    velSpin.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    velSpin.setForeground(Color.WHITE);
    // Link slider and spinner together for immediate state sync!
    velSlider.addChangeListener(e -> velSpin.setValue(velSlider.getValue()));
    velSpin.addChangeListener(e -> velSlider.setValue((Integer) velSpin.getValue()));
    gridPanel.add(velSpin, gc);

    // Row 2: Repeats / Sub-triggers (Iterance)
    gc.gridx = 0;
    gc.gridy = 1;
    gc.weightx = 0.1;
    JLabel l2 = new JLabel("Repeats:");
    l2.setFont(labelFont);
    l2.setForeground(Color.WHITE);
    gridPanel.add(l2, gc);

    gc.gridx = 1;
    gc.weightx = 0.8;
    JLabel iterDesc = new JLabel("Sub-trigger steps (0=none, 3=max triplets)");
    iterDesc.setFont(new Font("SansSerif", Font.PLAIN, 12));
    iterDesc.setForeground(Color.LIGHT_GRAY);
    gridPanel.add(iterDesc, gc);

    gc.gridx = 2;
    gc.weightx = 0.1;
    iterSpin = new JSpinner(new SpinnerNumberModel(currentIterance, 0, 3, 1));
    iterSpin.setPreferredSize(new Dimension(65, 30));
    iterSpin.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    iterSpin.setForeground(Color.WHITE);
    gridPanel.add(iterSpin, gc);

    // Row 3: Fill Probability %
    gc.gridx = 0;
    gc.gridy = 2;
    gc.weightx = 0.1;
    JLabel l3 = new JLabel("Fill %:");
    l3.setFont(labelFont);
    l3.setForeground(Color.WHITE);
    gridPanel.add(l3, gc);

    gc.gridx = 1;
    gc.weightx = 0.8;
    fillSlider = new JSlider(0, 100, currentFill);
    fillSlider.setBackground(SwingSynthConfigDialog.BG_CARD);
    fillSlider.setForeground(SwingSynthConfigDialog.ACCENT_BLUE);
    fillSlider.setToolTipText("0 = regular step, 1-100 = fill-only step with probability");
    gridPanel.add(fillSlider, gc);

    gc.gridx = 2;
    gc.weightx = 0.1;
    JLabel fillPct = new JLabel("Fill-only");
    fillPct.setFont(new Font("SansSerif", Font.BOLD, 10));
    fillPct.setForeground(Color.LIGHT_GRAY);
    gridPanel.add(fillPct, gc);

    mainContainer.add(gridPanel, BorderLayout.CENTER);

    // Action buttons bar at the bottom
    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 5));
    buttonPanel.setBackground(SwingSynthConfigDialog.BG_DARK);

    JButton cancelBtn = new JButton("CANCEL");
    styleButton(cancelBtn, new Color(0x32, 0x1e, 0x1e), new Color(0xff, 0x33, 0x33));
    cancelBtn.addActionListener(
        e -> {
          confirmed = false;
          dispose();
        });

    JButton applyBtn = new JButton("APPLY");
    styleButton(applyBtn, new Color(0x1e, 0x32, 0x22), new Color(0x00, 0xff, 0x66));
    applyBtn.addActionListener(
        e -> {
          confirmed = true;
          dispose();
        });

    buttonPanel.add(cancelBtn);
    buttonPanel.add(applyBtn);
    mainContainer.add(buttonPanel, BorderLayout.SOUTH);

    add(mainContainer);
  }

  private void styleButton(JButton btn, Color bg, Color fg) {
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusPainted(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(fg, 1), BorderFactory.createEmptyBorder(6, 15, 6, 15)));
    btn.setFont(new Font("SansSerif", Font.BOLD, 12));
    btn.setOpaque(true);
  }

  public boolean isConfirmed() {
    return confirmed;
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
