package org.chuck.deluge.ui;

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;

/**
 * Bottom MASTER FX panel containing master volume, transpose, scale selector, and playback status.
 * Uses ProjectModel for master volume so changes flow through MVC (model → listener → bridge). Used
 * in the main layout's bottom row. The status counter label is exposed so the playback timer can
 * update it.
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
    setLayout(new FlowLayout(FlowLayout.LEFT, 15, 5));
    setBackground(new Color(0x25, 0x25, 0x25));
    setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "MASTER FX", 0, 0, null, Color.WHITE));

    // ── Master Volume ──
    JLabel bVolLabel = new JLabel("Master Vol:");
    bVolLabel.setForeground(Color.WHITE);
    masterVolSlider = new JSlider(0, 100, (int) (projectModel.getMasterVolume() * 100));
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

    // ── Transpose ──
    JLabel transLabel = new JLabel("Transpose:");
    transLabel.setForeground(Color.WHITE);
    JSlider transSlider = new JSlider(-24, 24, projectModel.getTranspose());
    transSlider.setSnapToTicks(true);
    transSlider.setMajorTickSpacing(12);
    transSlider.setPaintTicks(true);
    transSlider.addChangeListener(
        e -> {
          if (!transSlider.getValueIsAdjusting()) {
            projectModel.setTranspose(transSlider.getValue());
          }
        });
    add(transLabel);
    add(transSlider);

    // ── Scale Selector ──
    JLabel scaleLabel = new JLabel("Scale:");
    scaleLabel.setForeground(Color.WHITE);
    JComboBox<String> scaleCombo =
        new JComboBox<>(new String[] {"Major", "Minor", "Pentatonic", "Chromatic"});
    add(scaleLabel);
    add(scaleCombo);

    // ── High Fidelity Mode ──
    javax.swing.JCheckBox hiFiCheck = new javax.swing.JCheckBox("Hi-Fi");
    hiFiCheck.setForeground(Color.WHITE);
    hiFiCheck.setBackground(new Color(0x25, 0x25, 0x25));
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

    // ── Status Counter ──
    statusCounter = new JLabel("1:1:1");
    statusCounter.setForeground(Color.GREEN);
    statusCounter.setFont(new Font("Monospaced", Font.BOLD, 24));
    add(statusCounter);

    // ── Master Compressor Sub-Panel ──
    JPanel compPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
    compPanel.setBackground(new Color(0x2E, 0x2E, 0x2E));
    compPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY),
            "MASTER COMPRESSOR",
            0,
            0,
            null,
            Color.WHITE));

    JLabel threshLabel = new JLabel("Thresh:");
    threshLabel.setForeground(Color.WHITE);
    threshSlider = new JSlider(0, 100, (int) (projectModel.getCompressorThreshold() * 100));
    threshSlider.addChangeListener(
        e -> {
          projectModel.setCompressorThreshold(threshSlider.getValue() / 100.0f);
        });
    compPanel.add(threshLabel);
    compPanel.add(threshSlider);

    JLabel attackLabel = new JLabel("Attack:");
    attackLabel.setForeground(Color.WHITE);
    attackSlider = new JSlider(0, 100, (int) (projectModel.getCompressorAttack() * 100));
    attackSlider.addChangeListener(
        e -> {
          projectModel.setCompressorAttack(attackSlider.getValue() / 100.0f);
        });
    compPanel.add(attackLabel);
    compPanel.add(attackSlider);

    JLabel releaseLabel = new JLabel("Release:");
    releaseLabel.setForeground(Color.WHITE);
    releaseSlider = new JSlider(0, 100, (int) (projectModel.getCompressorRelease() * 100));
    releaseSlider.addChangeListener(
        e -> {
          projectModel.setCompressorRelease(releaseSlider.getValue() / 100.0f);
        });
    compPanel.add(releaseLabel);
    compPanel.add(releaseSlider);

    JLabel ratioLabel = new JLabel("Ratio:");
    ratioLabel.setForeground(Color.WHITE);
    ratioSlider = new JSlider(0, 100, (int) (projectModel.getCompressorRatio() * 100));
    ratioSlider.addChangeListener(
        e -> {
          projectModel.setCompressorRatio(ratioSlider.getValue() / 100.0f);
        });
    compPanel.add(ratioLabel);
    compPanel.add(ratioSlider);

    JLabel blendLabel = new JLabel("Blend:");
    blendLabel.setForeground(Color.WHITE);
    blendSlider = new JSlider(0, 100, (int) (projectModel.getCompressorBlend() * 100));
    blendSlider.addChangeListener(
        e -> {
          projectModel.setCompressorBlend(blendSlider.getValue() / 100.0f);
        });
    compPanel.add(blendLabel);
    compPanel.add(blendSlider);

    add(compPanel);
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
