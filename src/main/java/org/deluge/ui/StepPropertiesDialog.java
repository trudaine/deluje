package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;

/**
 * Modal dialog for editing per-step properties (velocity, iterance, fill, probability, gate,
 * nudge).
 */
public class StepPropertiesDialog extends JDialog {

  private final JSlider velSlider;
  private final JSpinner velSpin;
  private final JSpinner iterSpin;
  private final JSlider fillSlider;
  private final JSlider probSlider;
  private final JSpinner probSpin;
  private final JSpinner gateSpin;
  private final JSlider nudgeSlider;
  private final JSpinner nudgeSpin;
  private final JComboBox<String> condCombo;
  private final JPanel condCustomPanel;
  private final JSpinner condDivisorSpin;
  private final JCheckBox[] condChecks = new JCheckBox[8];
  private org.deluge.model.Iterance currentPlayCondition =
      new org.deluge.model.Iterance((byte) 1, (byte) 1);
  private boolean isSyncingUI = false;

  private boolean confirmed = false;

  public StepPropertiesDialog(Frame owner) {
    this(owner, 80, 0, 0, 100, 0.9, 0, new org.deluge.model.Iterance((byte) 1, (byte) 1));
  }

  public StepPropertiesDialog(Frame owner, int currentVelocity) {
    this(
        owner,
        currentVelocity,
        0,
        0,
        100,
        0.9,
        0,
        new org.deluge.model.Iterance((byte) 1, (byte) 1));
  }

  public StepPropertiesDialog(
      Frame owner, int currentVelocity, int currentIterance, int currentFill) {
    this(
        owner,
        currentVelocity,
        currentIterance,
        currentFill,
        100,
        0.9,
        0,
        new org.deluge.model.Iterance((byte) 1, (byte) 1));
  }

  public StepPropertiesDialog(
      Frame owner,
      int currentVelocity,
      int currentIterance,
      int currentFill,
      int currentProbability,
      double currentGate,
      int currentNudge) {
    this(
        owner,
        currentVelocity,
        currentIterance,
        currentFill,
        currentProbability,
        currentGate,
        currentNudge,
        new org.deluge.model.Iterance((byte) 1, (byte) 1));
  }

  public StepPropertiesDialog(
      Frame owner,
      int currentVelocity,
      int currentIterance,
      int currentFill,
      int currentProbability,
      double currentGate,
      int currentNudge,
      org.deluge.model.Iterance initialCondition) {
    super(owner, "Step Properties", true);
    if (initialCondition != null) {
      this.currentPlayCondition =
          new org.deluge.model.Iterance(initialCondition.divisor, initialCondition.iteranceStep);
    }

    // Set modern slate background and size
    getContentPane().setBackground(SwingSynthConfigDialog.BG_DARK);
    setSize(760, 610);
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
    gc.insets = new Insets(6, 12, 6, 12);
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

    // Row 4: Probability
    gc.gridx = 0;
    gc.gridy = 3;
    gc.weightx = 0.1;
    JLabel l4 = new JLabel("Probability:");
    l4.setFont(labelFont);
    l4.setForeground(Color.WHITE);
    gridPanel.add(l4, gc);

    gc.gridx = 1;
    gc.weightx = 0.8;
    probSlider = new JSlider(0, 100, currentProbability);
    probSlider.setBackground(SwingSynthConfigDialog.BG_CARD);
    probSlider.setForeground(SwingSynthConfigDialog.ACCENT_BLUE);
    gridPanel.add(probSlider, gc);

    gc.gridx = 2;
    gc.weightx = 0.1;
    probSpin = new JSpinner(new SpinnerNumberModel(currentProbability, 0, 100, 1));
    probSpin.setPreferredSize(new Dimension(65, 30));
    probSpin.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    probSpin.setForeground(Color.WHITE);
    probSlider.addChangeListener(e -> probSpin.setValue(probSlider.getValue()));
    probSpin.addChangeListener(e -> probSlider.setValue((Integer) probSpin.getValue()));
    gridPanel.add(probSpin, gc);

    // Row 5: Gate (Length)
    gc.gridx = 0;
    gc.gridy = 4;
    gc.weightx = 0.1;
    JLabel l5 = new JLabel("Gate (Length):");
    l5.setFont(labelFont);
    l5.setForeground(Color.WHITE);
    gridPanel.add(l5, gc);

    gc.gridx = 1;
    gc.weightx = 0.8;
    JLabel gateDesc = new JLabel("Step duration length (e.g. 0.05 to 192.0 steps)");
    gateDesc.setFont(new Font("SansSerif", Font.PLAIN, 12));
    gateDesc.setForeground(Color.LIGHT_GRAY);
    gridPanel.add(gateDesc, gc);

    gc.gridx = 2;
    gc.weightx = 0.1;
    gateSpin = new JSpinner(new SpinnerNumberModel(currentGate, 0.01, 192.0, 0.25));
    gateSpin.setPreferredSize(new Dimension(65, 30));
    gateSpin.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    gateSpin.setForeground(Color.WHITE);
    gridPanel.add(gateSpin, gc);

    // Row 6: Nudge (Microtiming)
    gc.gridx = 0;
    gc.gridy = 5;
    gc.weightx = 0.1;
    JLabel l6 = new JLabel("Nudge %:");
    l6.setFont(labelFont);
    l6.setForeground(Color.WHITE);
    gridPanel.add(l6, gc);

    gc.gridx = 1;
    gc.weightx = 0.8;
    nudgeSlider = new JSlider(0, 99, currentNudge);
    nudgeSlider.setBackground(SwingSynthConfigDialog.BG_CARD);
    nudgeSlider.setForeground(SwingSynthConfigDialog.ACCENT_BLUE);
    nudgeSlider.setToolTipText("Microtiming offset forward (0 to 99% of step)");
    gridPanel.add(nudgeSlider, gc);

    gc.gridx = 2;
    gc.weightx = 0.1;
    nudgeSpin = new JSpinner(new SpinnerNumberModel(currentNudge, 0, 99, 1));
    nudgeSpin.setPreferredSize(new Dimension(65, 30));
    nudgeSpin.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    nudgeSpin.setForeground(Color.WHITE);
    nudgeSlider.addChangeListener(e -> nudgeSpin.setValue(nudgeSlider.getValue()));
    nudgeSpin.addChangeListener(e -> nudgeSlider.setValue((Integer) nudgeSpin.getValue()));
    gridPanel.add(nudgeSpin, gc);

    // Row 7: Play Condition / Iterance (C++ Hardware Parity: 1of1, 1of2, 1of4, Custom bitmask)
    gc.gridx = 0;
    gc.gridy = 6;
    gc.weightx = 0.1;
    JLabel l7 = new JLabel("Condition:");
    l7.setFont(labelFont);
    l7.setForeground(Color.WHITE);
    gridPanel.add(l7, gc);

    gc.gridx = 1;
    gc.weightx = 0.8;
    String[] presets = {
      "Always (1 of 1)",
      "1st of 2 (1 of 2)",
      "2nd of 2 (2 of 2)",
      "1st of 3 (1 of 3)",
      "1st of 4 (1 of 4)",
      "2nd of 4 (2 of 4)",
      "3rd of 4 (3 of 4)",
      "4th of 4 (4 of 4)",
      "1st of 8 (1 of 8)",
      "8th of 8 (8 of 8)",
      "Custom (Cycle Bitmask)"
    };
    condCombo = new JComboBox<>(presets);
    condCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    condCombo.setForeground(Color.WHITE);
    condCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
    gridPanel.add(condCombo, gc);

    gc.gridx = 2;
    gc.weightx = 0.1;
    JLabel condTag = new JLabel("Iterance");
    condTag.setFont(new Font("SansSerif", Font.BOLD, 10));
    condTag.setForeground(Color.LIGHT_GRAY);
    gridPanel.add(condTag, gc);

    // Row 8: Custom Bitmask Panel (1..8 cycle checks)
    gc.gridx = 1;
    gc.gridy = 7;
    gc.gridwidth = 2;
    condCustomPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    condCustomPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    JLabel divLab = new JLabel("Cycles:");
    divLab.setForeground(Color.LIGHT_GRAY);
    divLab.setFont(new Font("SansSerif", Font.PLAIN, 11));
    condCustomPanel.add(divLab);

    condDivisorSpin = new JSpinner(new SpinnerNumberModel(4, 1, 8, 1));
    condDivisorSpin.setPreferredSize(new Dimension(42, 24));
    condCustomPanel.add(condDivisorSpin);

    condCustomPanel.add(new JLabel(" Steps:"));
    for (int i = 0; i < 8; i++) {
      condChecks[i] = new JCheckBox(String.valueOf(i + 1));
      condChecks[i].setBackground(SwingSynthConfigDialog.BG_CARD);
      condChecks[i].setForeground(Color.WHITE);
      condChecks[i].setFont(new Font("SansSerif", Font.PLAIN, 10));
      condChecks[i].setFocusPainted(false);
      condChecks[i].addActionListener(e -> syncFromCustomCheckboxes());
      condCustomPanel.add(condChecks[i]);
    }
    condDivisorSpin.addChangeListener(e -> syncFromCustomCheckboxes());
    gridPanel.add(condCustomPanel, gc);
    gc.gridwidth = 1;

    // Set initial dropdown state matching currentPlayCondition
    syncUIFromPlayCondition();

    condCombo.addActionListener(
        e -> {
          if (isSyncingUI) return;
          String sel = (String) condCombo.getSelectedItem();
          if (sel != null && !sel.equals("Custom (Cycle Bitmask)")) {
            condCustomPanel.setVisible(false);
            applyPresetToPlayCondition(sel);
          } else {
            condCustomPanel.setVisible(true);
            syncFromCustomCheckboxes();
          }
        });

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

  public int getProbability() {
    return probSlider.getValue();
  }

  public double getGate() {
    return (Double) gateSpin.getValue();
  }

  public int getNudge() {
    return nudgeSlider.getValue();
  }

  private void syncUIFromPlayCondition() {
    isSyncingUI = true;
    try {
      int div = currentPlayCondition.divisor & 0xFF;
      int mask = currentPlayCondition.iteranceStep & 0xFF;
      String matchedPreset = null;
      if (div == 1 && mask == 0b1) matchedPreset = "Always (1 of 1)";
      else if (div == 2 && mask == 0b1) matchedPreset = "1st of 2 (1 of 2)";
      else if (div == 2 && mask == 0b10) matchedPreset = "2nd of 2 (2 of 2)";
      else if (div == 3 && mask == 0b1) matchedPreset = "1st of 3 (1 of 3)";
      else if (div == 4 && mask == 0b1) matchedPreset = "1st of 4 (1 of 4)";
      else if (div == 4 && mask == 0b10) matchedPreset = "2nd of 4 (2 of 4)";
      else if (div == 4 && mask == 0b100) matchedPreset = "3rd of 4 (3 of 4)";
      else if (div == 4 && mask == 0b1000) matchedPreset = "4th of 4 (4 of 4)";
      else if (div == 8 && mask == 0b1) matchedPreset = "1st of 8 (1 of 8)";
      else if (div == 8 && mask == 0b10000000) matchedPreset = "8th of 8 (8 of 8)";

      for (int i = 0; i < 8; i++) {
        if (condChecks[i] != null) {
          condChecks[i].setSelected((mask & (1 << i)) != 0);
        }
      }
      condDivisorSpin.setValue(Math.max(1, Math.min(8, div)));

      if (matchedPreset != null) {
        condCombo.setSelectedItem(matchedPreset);
        condCustomPanel.setVisible(false);
      } else {
        condCombo.setSelectedItem("Custom (Cycle Bitmask)");
        condCustomPanel.setVisible(true);
      }
    } finally {
      isSyncingUI = false;
    }
  }

  private void applyPresetToPlayCondition(String sel) {
    switch (sel) {
      case "Always (1 of 1)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 1, (byte) 0b1);
      case "1st of 2 (1 of 2)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 2, (byte) 0b1);
      case "2nd of 2 (2 of 2)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 2, (byte) 0b10);
      case "1st of 3 (1 of 3)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 3, (byte) 0b1);
      case "1st of 4 (1 of 4)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 4, (byte) 0b1);
      case "2nd of 4 (2 of 4)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 4, (byte) 0b10);
      case "3rd of 4 (3 of 4)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 4, (byte) 0b100);
      case "4th of 4 (4 of 4)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 4, (byte) 0b1000);
      case "1st of 8 (1 of 8)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 8, (byte) 0b1);
      case "8th of 8 (8 of 8)" ->
          currentPlayCondition = new org.deluge.model.Iterance((byte) 8, (byte) 0b10000000);
    }
  }

  private void syncFromCustomCheckboxes() {
    if (isSyncingUI) return;
    int div = (Integer) condDivisorSpin.getValue();
    int mask = 0;
    for (int i = 0; i < 8; i++) {
      if (condChecks[i] != null && condChecks[i].isSelected()) {
        mask |= (1 << i);
      }
    }
    currentPlayCondition = new org.deluge.model.Iterance((byte) div, (byte) mask);
  }

  public org.deluge.model.Iterance getPlayCondition() {
    return currentPlayCondition;
  }

  public int getPlayConditionInt() {
    return currentPlayCondition.toInt();
  }
}
