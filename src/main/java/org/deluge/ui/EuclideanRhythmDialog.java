package org.deluge.ui;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;
import javax.swing.event.ChangeListener;
import org.deluge.BridgeContract;

/** Modal dialog for generating Euclidean rhythm patterns on a selected grid row. */
public class EuclideanRhythmDialog extends JDialog {

  private final BridgeContract bridge;
  private final int baseTrack;
  private final int stepCount;
  private final Runnable onApply;
  private final EuclideanWheelPanel wheelPanel;
  private boolean[] currentPattern;

  /**
   * @param owner parent frame
   * @param bridge contract for writing steps
   * @param baseTrack track row index to write to
   * @param stepCount number of steps in the pattern (e.g. 16)
   * @param rowName display name for the target row
   * @param onApply callback after pattern is written
   */
  public EuclideanRhythmDialog(
      Frame owner,
      BridgeContract bridge,
      int baseTrack,
      int stepCount,
      String rowName,
      Runnable onApply) {
    super(owner, "Euclidean Rhythm Generator", true);
    this.bridge = bridge;
    this.baseTrack = baseTrack;
    this.stepCount = stepCount;
    this.onApply = onApply;

    setSize(600, 520);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));

    // ── Controls panel (GridBagLayout) ──
    JPanel controls = new JPanel(new GridBagLayout());
    controls.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    controls.add(sectionLabel("Euclidean Rhythm Generator"), c);
    row++;

    // Target row
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    controls.add(tip(label("Target Row:"), "The grid row this pattern will be written to"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JLabel rowLabel = label(rowName != null ? rowName : "Row " + (baseTrack + 1));
    controls.add(rowLabel, c);
    row++;

    // Pulses (K)
    JSpinner pulsesSpinner = new JSpinner(new SpinnerNumberModel(4, 1, 16, 1));
    pulsesSpinner.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    ((JSpinner.DefaultEditor) pulsesSpinner.getEditor())
        .getTextField()
        .setBackground(SwingSynthConfigDialog.BG_CONTROL);
    ((JSpinner.DefaultEditor) pulsesSpinner.getEditor()).getTextField().setForeground(Color.WHITE);
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    controls.add(
        tip(label("Pulses (K):"), "Number of active hits to distribute across the steps"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    controls.add(pulsesSpinner, c);
    row++;

    // Steps (N)
    JSpinner stepsSpinner = new JSpinner(new SpinnerNumberModel(16, 1, 16, 1));
    stepsSpinner.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    ((JSpinner.DefaultEditor) stepsSpinner.getEditor())
        .getTextField()
        .setBackground(SwingSynthConfigDialog.BG_CONTROL);
    ((JSpinner.DefaultEditor) stepsSpinner.getEditor()).getTextField().setForeground(Color.WHITE);
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    controls.add(tip(label("Steps (N):"), "Total number of steps in the sequence"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    controls.add(stepsSpinner, c);
    row++;

    // Rotation
    JSpinner rotSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 15, 1));
    rotSpinner.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    ((JSpinner.DefaultEditor) rotSpinner.getEditor())
        .getTextField()
        .setBackground(SwingSynthConfigDialog.BG_CONTROL);
    ((JSpinner.DefaultEditor) rotSpinner.getEditor()).getTextField().setForeground(Color.WHITE);
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    controls.add(tip(label("Rotation:"), "Shift all hits forward by this many steps"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    controls.add(rotSpinner, c);
    row++;

    // Circular wheel preview
    wheelPanel = new EuclideanWheelPanel();
    currentPattern = euclidean(4, 16, 0);
    wheelPanel.setPattern(currentPattern);
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    c.fill = GridBagConstraints.BOTH;
    c.weightx = 1.0;
    c.weighty = 1.0;
    controls.add(wheelPanel, c);

    add(controls, BorderLayout.CENTER);

    // ── Shared change listener ──
    ChangeListener updater =
        e -> {
          int K = (Integer) pulsesSpinner.getValue();
          int N = (Integer) stepsSpinner.getValue();
          int rot = (Integer) rotSpinner.getValue();
          currentPattern = euclidean(K, N, rot);
          wheelPanel.setPattern(currentPattern);
          wheelPanel.setStepCount(N);
          wheelPanel.repaint();
        };
    pulsesSpinner.addChangeListener(updater);
    stepsSpinner.addChangeListener(updater);
    rotSpinner.addChangeListener(updater);

    // ── South buttons ──
    JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
    south.setBackground(new Color(0x25, 0x25, 0x25));

    JButton applyBtn = new JButton("Apply");
    applyBtn.setBackground(new Color(0x33, 0x44, 0x55));
    applyBtn.setForeground(Color.WHITE);
    applyBtn.addActionListener(
        e -> {
          applyPattern();
          if (onApply != null) onApply.run();
          dispose();
        });
    south.add(applyBtn);

    JButton closeBtn = new JButton("Close");
    closeBtn.addActionListener(e -> dispose());
    south.add(closeBtn);

    add(south, BorderLayout.SOUTH);
  }

  /** Write the current Euclidean pattern to the bridge step grid. */
  private void applyPattern() {
    if (bridge == null || currentPattern == null) return;
    // Clear all steps first
    for (int s = 0; s < stepCount; s++) {
      bridge.setStep(baseTrack, s, false);
    }
    // Write active steps
    for (int s = 0; s < stepCount && s < currentPattern.length; s++) {
      if (currentPattern[s]) {
        bridge.setStep(baseTrack, s, true);
        bridge.setVelocity(baseTrack, s, 0.8);
      }
    }
  }

  // ── Euclidean algorithm (even distribution, matching firmware) ──

  /**
   * Distribute {@code pulses} hits as evenly as possible across {@code steps} positions, then apply
   * rotation. Mirrors the firmware's {@code editNumEuclideanEvents()} logic: {@code pos = (n *
   * numStepsAvailable) / N * squareWidth}.
   */
  static boolean[] euclidean(int pulses, int steps, int rotation) {
    boolean[] pattern = new boolean[steps];
    if (pulses <= 0) return pattern;
    if (pulses >= steps) {
      Arrays.fill(pattern, true);
      return pattern;
    }
    for (int n = 0; n < pulses; n++) {
      int pos = (int) ((long) n * steps / pulses);
      pos = (pos + rotation) % steps;
      pattern[pos] = true;
    }
    return pattern;
  }

  // ── Circular wheel preview ──

  static class EuclideanWheelPanel extends JPanel {
    private boolean[] pattern = new boolean[0];
    private int stepCount = 16;

    void setPattern(boolean[] p) {
      this.pattern = p != null ? p : new boolean[0];
    }

    void setStepCount(int n) {
      this.stepCount = Math.max(1, n);
    }

    EuclideanWheelPanel() {
      setBackground(SwingSynthConfigDialog.BG_CARD);
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g;
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      int w = getWidth();
      int h = getHeight();
      int cx = w / 2;
      int cy = h / 2;
      int radius = Math.min(w, h) / 2 - 40;
      if (radius < 20) return;

      // Draw the reference circle ring
      g2.setColor(new Color(0x55, 0x55, 0x55));
      g2.setStroke(new BasicStroke(2));
      g2.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

      int n = Math.min(stepCount, pattern.length);
      if (n == 0) return;

      for (int s = 0; s < n; s++) {
        double angle = Math.toRadians(-90 + 360.0 * s / n); // start at 12 o'clock
        int dotX = cx + (int) (radius * Math.cos(angle));
        int dotY = cy + (int) (radius * Math.sin(angle));

        boolean active = pattern[s];
        int dotSize = active ? 20 : 12;

        // Dot fill
        g2.setColor(active ? new Color(0x00, 0xff, 0xcc) : new Color(0x55, 0x55, 0x55));
        g2.fillOval(dotX - dotSize / 2, dotY - dotSize / 2, dotSize, dotSize);

        if (active) {
          // Outline for active dots
          g2.setColor(Color.WHITE);
          g2.setStroke(new BasicStroke(2));
          g2.drawOval(dotX - dotSize / 2, dotY - dotSize / 2, dotSize, dotSize);
        }

        // Label step 1
        if (s == 0) {
          g2.setColor(Color.LIGHT_GRAY);
          g2.setFont(new Font("SansSerif", Font.PLAIN, 11));
          g2.drawString("1", dotX - 3, dotY + dotSize / 2 + 14);
        }
      }
    }
  }

  // ── UI helpers (same pattern as SwingSynthConfigDialog / SwingKitConfigDialog) ──

  private static JLabel label(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(Color.LIGHT_GRAY);
    return l;
  }

  private static JLabel sectionLabel(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(new Color(0x00, 0xff, 0xcc));
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    return l;
  }

  private static JComponent tip(JComponent comp, String text) {
    comp.setToolTipText(text);
    return comp;
  }
}
