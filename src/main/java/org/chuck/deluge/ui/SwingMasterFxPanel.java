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
}
