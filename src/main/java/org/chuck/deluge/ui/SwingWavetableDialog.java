package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.chuck.deluge.BridgeContract;

/**
 * A beautiful, spacious, and fully resizeable dark JDialog laboratory editor for scanning and
 * wiggling Wavetable indices with real-time visual feedback and JNI hot-swaps.
 */
public class SwingWavetableDialog extends JDialog {

  private org.chuck.deluge.model.SoundDrum soundDrum = null;
  private final BridgeContract bridge;
  private final int trackIndex;
  private final int oscIndex; // 1 = Osc 1, 2 = Osc 2

  private final SwingWavetableVisualizer visualizer;
  private final JSlider positionSlider;
  private final JLabel positionLabel;
  private final JComboBox<String> cycleSizeCombo;

  public SwingWavetableDialog(
      Frame owner,
      org.chuck.deluge.model.SoundDrum sound,
      BridgeContract bridge,
      int trackIndex,
      int slotIndex) {
    super(
        owner,
        String.format(
            "Wavetable Index Laboratory — Drum Slot %s (Track %d)",
            sound.getName(), trackIndex + 1),
        false);
    this.soundDrum = sound;
    this.bridge = bridge;
    this.trackIndex = trackIndex;
    this.oscIndex = 1; // Default Osc 1 for drum kits!

    setSize(900, 480);
    setMinimumSize(new Dimension(750, 400));
    setLocationRelativeTo(owner);
    setResizable(true);
    getContentPane().setBackground(SwingSynthConfigDialog.BG_DARK);
    setLayout(new BorderLayout(10, 10));

    // ── Header Title ──
    JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
    headerPanel.setBackground(SwingSynthConfigDialog.BG_DARK);
    headerPanel.setBorder(new EmptyBorder(10, 10, 2, 10));

    String samplePath = sound.getSamplePath();
    String fileName =
        (samplePath != null && !samplePath.isBlank())
            ? new java.io.File(samplePath).getName()
            : "none";

    JLabel titleLabel =
        new JLabel(
            String.format(
                "🔬 DRUM WAVETABLE LABORATORY — Active File: %s", fileName.toUpperCase()));
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
    titleLabel.setForeground(SwingSynthConfigDialog.ACCENT_MINT);
    headerPanel.add(titleLabel);
    add(headerPanel, BorderLayout.NORTH);

    // ── Center Wavetable Visualizer ──
    visualizer = new SwingWavetableVisualizer();
    int initialPW = sound.getWavetableIndexPct();
    visualizer.setActiveIndexPct(initialPW);

    visualizer.loadWavetable(samplePath, 2048);

    add(visualizer, BorderLayout.CENTER);

    // ── Bottom Control Panel Row ──
    JPanel bottomPanel = new JPanel(new GridBagLayout());
    bottomPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    bottomPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            new EmptyBorder(12, 20, 12, 20)));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 10, 4, 10);

    // Column 1: Cycle Size
    c.gridx = 0;
    c.gridy = 0;
    c.weightx = 0.0;
    JLabel cycleLabel = new JLabel("CYCLE SIZE (SAMPLES):");
    cycleLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
    cycleLabel.setForeground(Color.LIGHT_GRAY);
    bottomPanel.add(cycleLabel, c);

    c.gridx = 0;
    c.gridy = 1;
    String[] sizes = {"256", "512", "1024", "2048", "4096"};
    cycleSizeCombo = new JComboBox<>(sizes);
    cycleSizeCombo.setSelectedItem("2048");
    cycleSizeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    cycleSizeCombo.setForeground(Color.WHITE);
    cycleSizeCombo.setPreferredSize(new Dimension(150, 26));
    cycleSizeCombo.setFocusable(false);
    cycleSizeCombo.addActionListener(
        e -> {
          String selected = (String) cycleSizeCombo.getSelectedItem();
          if (selected != null) {
            int newSize = Integer.parseInt(selected);
            visualizer.setCycleSize(newSize);
          }
        });
    bottomPanel.add(cycleSizeCombo, c);

    // Column 2: Position scan slider
    c.gridx = 1;
    c.gridy = 0;
    c.weightx = 1.0;
    JLabel sliderTitle = new JLabel("WAVETABLE POSITION SCAN INDEX (0 - 100%):");
    sliderTitle.setFont(new Font("SansSerif", Font.BOLD, 10));
    sliderTitle.setForeground(Color.LIGHT_GRAY);
    bottomPanel.add(sliderTitle, c);

    c.gridx = 1;
    c.gridy = 1;
    positionSlider = new JSlider(0, 100, initialPW);
    DarkSliderUI.styleSlider(positionSlider, new Color(0xff, 0xb3, 0x00));
    bottomPanel.add(positionSlider, c);

    // Column 3: Value indicator badge
    c.gridx = 2;
    c.gridy = 1;
    c.weightx = 0.0;
    positionLabel = new JLabel(String.format("%03d%%", initialPW));
    positionLabel.setFont(new Font("Monospaced", Font.BOLD, 16));
    positionLabel.setForeground(Color.CYAN);
    positionLabel.setPreferredSize(new Dimension(60, 24));
    positionLabel.setHorizontalAlignment(SwingConstants.CENTER);
    bottomPanel.add(positionLabel, c);

    // Column 4: Close Button
    c.gridx = 3;
    c.gridy = 1;
    JButton applyBtn = new JButton("Apply & Close");
    applyBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
    styleButton(applyBtn, new Color(0x1e, 0x32, 0x22), new Color(0x00, 0xff, 0x66));
    applyBtn.addActionListener(e -> dispose());
    bottomPanel.add(applyBtn, c);

    add(bottomPanel, BorderLayout.SOUTH);

    // Connect slider for real-time JNI and visual updates!
    positionSlider.addChangeListener(
        e -> {
          int val = positionSlider.getValue();
          positionLabel.setText(String.format("%03d%%", val));
          visualizer.setActiveIndexPct(val);

          // Save to SoundDrum model
          sound.setWavetableIndexPct(val);

          if (SwingDelugeApp.mainInstance != null) {
            SwingDelugeApp.mainInstance.pushModelToBridge();
            SwingDelugeApp.mainInstance.propagateCurrentModel();
            SwingDelugeApp.mainInstance.syncHighFidelityEngine(
                SwingDelugeApp.mainInstance.getCurrentProject());
          }
        });

    DarkComboBoxRenderer.styleComponentTree(this);
  }

  private void styleButton(AbstractButton btn, Color bg, Color fg) {
    btn.setOpaque(true);
    btn.setBorderPainted(true);
    btn.setContentAreaFilled(true);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusable(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.brighter(), 1),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)));
  }
}
