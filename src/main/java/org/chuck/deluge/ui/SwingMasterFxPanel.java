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

/**
 * Bottom MASTER FX panel containing master volume, transpose, scale selector, and playback status.
 * Used in the main layout's bottom row. The status counter label is exposed so the playback timer
 * can update it.
 */
public class SwingMasterFxPanel extends JPanel {

  private final JSlider masterVolSlider;
  private final JLabel statusCounter;

  /**
   * @param vm     ChucK VM for bridge writes
   * @param topBar top bar panel for two-way master vol sync
   */
  public SwingMasterFxPanel(ChuckVM vm, SwingTopBarPanel topBar) {
    setLayout(new FlowLayout(FlowLayout.LEFT, 15, 5));
    setBackground(new Color(0x25, 0x25, 0x25));
    setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), "MASTER FX", 0, 0, null, Color.WHITE));

    // ── Master Volume ──
    JLabel bVolLabel = new JLabel("Master Vol:");
    bVolLabel.setForeground(Color.WHITE);
    masterVolSlider = new JSlider(0, 100, 70);
    masterVolSlider.addChangeListener(
        e -> {
          double v = masterVolSlider.getValue() / 100.0;
          vm.setGlobalFloat(BridgeContract.G_MASTER_VOL, v);
          if (topBar != null && topBar.getMasterVol() != masterVolSlider.getValue()) {
            topBar.setMasterVol(masterVolSlider.getValue());
          }
        });
    add(bVolLabel);
    add(masterVolSlider);

    // ── Transpose ──
    JLabel transLabel = new JLabel("Transpose:");
    transLabel.setForeground(Color.WHITE);
    JSlider transSlider = new JSlider(-24, 24, 0);
    transSlider.setSnapToTicks(true);
    transSlider.setMajorTickSpacing(12);
    transSlider.setPaintTicks(true);
    add(transLabel);
    add(transSlider);

    // ── Scale Selector ──
    JLabel scaleLabel = new JLabel("Scale:");
    scaleLabel.setForeground(Color.WHITE);
    JComboBox<String> scaleCombo =
        new JComboBox<>(new String[] {"Major", "Minor", "Pentatonic", "Chromatic"});
    add(scaleLabel);
    add(scaleCombo);

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
