package org.chuck.deluge.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;

/**
 * Bottom MASTER FX panel containing master volume, transpose, scale selector, and playback status.
 * Re-designed to be ultra-compact (54px height) with custom-styled, high-contrast controls.
 */
public class SwingMasterFxPanel extends JPanel {

  private final JSlider masterVolSlider;
  private final JLabel statusCounter;
  private final ProjectModel projectModel;
  private final JSlider threshSlider;
  private final JSlider attackSlider;
  private final JSlider releaseSlider;
  private final JSlider ratioSlider;
  private final JSlider blendSlider;

  /**
   * @param vm ChucK VM for bridge writes
   * @param projectModel project model for MVC-bound controls
   * @param topBar top bar panel for two-way master vol sync
   */
  public SwingMasterFxPanel(ChuckVM vm, ProjectModel projectModel, SwingTopBarPanel topBar) {
    this.projectModel = projectModel;
    setLayout(new FlowLayout(FlowLayout.LEFT, 12, 4));
    setBackground(new Color(0x12, 0x12, 0x14)); // Dark charcoal matching top bar
    setBorder(
        BorderFactory.createMatteBorder(
            1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32))); // Clean top divider

    // ── Status Counter ──
    statusCounter = new JLabel("1:1:1");
    statusCounter.setForeground(new Color(0x00, 0xff, 0x66)); // neon green
    statusCounter.setFont(new Font("Monospaced", Font.BOLD, 18));
    add(statusCounter);

    // ── Transpose ──
    JLabel transLabel = new JLabel("TRANSPOSE:");
    styleLabel(transLabel, true);
    JSlider transSlider = new JSlider(-24, 24, projectModel.getTranspose());
    styleSlider(transSlider, 70);
    transSlider.setSnapToTicks(true);
    transSlider.addChangeListener(
        e -> {
          if (!transSlider.getValueIsAdjusting()) {
            projectModel.setTranspose(transSlider.getValue());
          }
        });
    add(transLabel);
    add(transSlider);

    // ── Scale Selector ──
    JLabel scaleLabel = new JLabel("SCALE:");
    styleLabel(scaleLabel, true);
    JComboBox<String> scaleCombo =
        new JComboBox<>(new String[] {"Major", "Minor", "Pentatonic", "Chromatic"});
    scaleCombo.setFont(new Font("SansSerif", Font.PLAIN, 10));
    scaleCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
    scaleCombo.setForeground(Color.WHITE);
    scaleCombo.setPreferredSize(new Dimension(80, 20));
    scaleCombo.setFocusable(false);
    add(scaleLabel);
    add(scaleCombo);

    // ── High Fidelity Mode Checkbox ──
    JCheckBox hiFiCheck = new JCheckBox("HI-FI");
    hiFiCheck.setForeground(Color.LIGHT_GRAY);
    hiFiCheck.setFont(new Font("SansSerif", Font.BOLD, 10));
    hiFiCheck.setOpaque(false);
    hiFiCheck.setFocusable(false);
    hiFiCheck.setSelected(vm.getGlobalInt(BridgeContract.G_HI_FI_MODE) != 0);
    hiFiCheck.addActionListener(
        e -> {
          boolean selected = hiFiCheck.isSelected();
          vm.setGlobalInt(BridgeContract.G_HI_FI_MODE, selected ? 1L : 0L);
          System.out.println("[UI] High Fidelity Mode: " + selected);
          org.chuck.deluge.firmware.hid.FirmwareDisplay.get()
              .displayPopup(selected ? "HI-FI ON" : "HI-FI OFF");
        });
    add(hiFiCheck);

    // ── Master Compressor Sub-Panel ──
    JPanel compPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    compPanel.setBackground(new Color(0x18, 0x18, 0x1c));
    compPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)),
            "MASTER COMPRESSOR",
            0,
            0,
            new Font("SansSerif", Font.BOLD, 9),
            Color.LIGHT_GRAY));

    JLabel threshLabel = new JLabel("THRESH:");
    styleLabel(threshLabel, false);
    threshSlider = new JSlider(0, 100, (int) (projectModel.getCompressorThreshold() * 100));
    styleSlider(threshSlider, 70);
    threshSlider.addChangeListener(
        e -> projectModel.setCompressorThreshold(threshSlider.getValue() / 100.0f));
    compPanel.add(threshLabel);
    compPanel.add(threshSlider);

    JLabel attackLabel = new JLabel("ATTACK:");
    styleLabel(attackLabel, false);
    attackSlider = new JSlider(0, 100, (int) (projectModel.getCompressorAttack() * 100));
    styleSlider(attackSlider, 70);
    attackSlider.addChangeListener(
        e -> projectModel.setCompressorAttack(attackSlider.getValue() / 100.0f));
    compPanel.add(attackLabel);
    compPanel.add(attackSlider);

    JLabel releaseLabel = new JLabel("RELEASE:");
    styleLabel(releaseLabel, false);
    releaseSlider = new JSlider(0, 100, (int) (projectModel.getCompressorRelease() * 100));
    styleSlider(releaseSlider, 70);
    releaseSlider.addChangeListener(
        e -> projectModel.setCompressorRelease(releaseSlider.getValue() / 100.0f));
    compPanel.add(releaseLabel);
    compPanel.add(releaseSlider);

    JLabel ratioLabel = new JLabel("RATIO:");
    styleLabel(ratioLabel, false);
    ratioSlider = new JSlider(0, 100, (int) (projectModel.getCompressorRatio() * 100));
    styleSlider(ratioSlider, 70);
    ratioSlider.addChangeListener(
        e -> projectModel.setCompressorRatio(ratioSlider.getValue() / 100.0f));
    compPanel.add(ratioLabel);
    compPanel.add(ratioSlider);

    JLabel blendLabel = new JLabel("BLEND:");
    styleLabel(blendLabel, false);
    blendSlider = new JSlider(0, 100, (int) (projectModel.getCompressorBlend() * 100));
    styleSlider(blendSlider, 70);
    blendSlider.addChangeListener(
        e -> projectModel.setCompressorBlend(blendSlider.getValue() / 100.0f));
    compPanel.add(blendLabel);
    compPanel.add(blendSlider);

    add(compPanel);

    // ── Master Volume ──
    JLabel bVolLabel = new JLabel("VOLUME:");
    styleLabel(bVolLabel, true);
    masterVolSlider = new JSlider(0, 100, (int) (projectModel.getMasterVolume() * 100));
    styleSlider(masterVolSlider, 80);
    masterVolSlider.addChangeListener(
        e -> {
          double v = masterVolSlider.getValue() / 100.0;
          projectModel.setMasterVolume((float) v);
          if (topBar != null && topBar.getMasterVol() != masterVolSlider.getValue()) {
            topBar.setMasterVol(masterVolSlider.getValue());
          }
        });
    add(bVolLabel);
    add(masterVolSlider);
  }

  private void styleSlider(JSlider slider, int width) {
    slider.setBackground(new Color(0x12, 0x12, 0x14));
    slider.setForeground(new Color(0x00, 0xff, 0xcc));
    slider.setPreferredSize(new Dimension(width, 18));
    slider.setMinimumSize(new Dimension(width, 18));
    slider.setMaximumSize(new Dimension(width, 18));
    slider.setPaintTicks(false);
    slider.setPaintLabels(false);
    slider.setOpaque(false);
    slider.setFocusable(false);

    slider.setUI(
        new javax.swing.plaf.basic.BasicSliderUI(slider) {
          @Override
          public void paintTrack(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = trackRect.y + (trackRect.height / 2) - 2;

            // Background track: visible light white/gray!
            g2.setColor(new Color(0x66, 0x66, 0x6e));
            g2.fillRoundRect(trackRect.x, cy, trackRect.width, 4, 2, 2);

            // Active segment: glowing cyan!
            int thumbPos = thumbRect.x + (thumbRect.width / 2);
            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.fillRoundRect(trackRect.x, cy, Math.max(0, thumbPos - trackRect.x), 4, 2, 2);
            g2.dispose();
          }

          @Override
          public void paintThumb(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(
                java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(Color.WHITE);
            g2.fillOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);

            g2.setColor(new Color(0x00, 0xff, 0xcc));
            g2.drawOval(
                thumbRect.x + 2, thumbRect.y + 2, thumbRect.width - 4, thumbRect.height - 4);
            g2.dispose();
          }
        });
  }

  private void styleLabel(JLabel label, boolean bold) {
    label.setForeground(Color.LIGHT_GRAY);
    label.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, 10));
  }

  /** Current master volume slider value. */
  public int getMasterVol() {
    return masterVolSlider.getValue();
  }

  /** Programmatically set the master volume slider value (e.g. for top-slider sync). */
  public void setMasterVol(int value) {
    masterVolSlider.setValue(value);
  }

  /** The status counter label, updated by the playback timer. */
  public JLabel getStatusCounter() {
    return statusCounter;
  }

  /** Synchronizes master compressor sliders with new float parameter values. */
  public void updateCompressorUI(
      float thresh, float attack, float release, float ratio, float blend) {
    threshSlider.setValue((int) (thresh * 100));
    attackSlider.setValue((int) (attack * 100));
    releaseSlider.setValue((int) (release * 100));
    ratioSlider.setValue((int) (ratio * 100));
    blendSlider.setValue((int) (blend * 100));
  }
}
