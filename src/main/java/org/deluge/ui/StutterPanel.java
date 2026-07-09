package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.ProjectModel;
import org.deluge.model.StutterConfig;
import org.deluge.model.SynthTrackModel;

/**
 * STUTTER tab: 100% C++ Hardware Parity performance stutter modes (Quantized grid lock, Reversed
 * slice playback, Ping-Pong alternation) and rate control.
 */
public class StutterPanel extends JPanel {

  private final JCheckBox quantizeCheck;
  private final JCheckBox reverseCheck;
  private final JCheckBox pingPongCheck;

  public StutterPanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(8, 10, 8, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("STUTTER PERFORMANCE FX"), c);
    row++;

    StutterConfig st = model.getStutter();

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Rate:",
            "Stutter repetition rate (0-100). Controls slice sub-division frequency.",
            0,
            100,
            (int) (st.getStutterRate() * 100),
            val -> st.setStutterRate(val / 100f),
            "",
            "stutterRate",
            projectModel,
            trackIndex);

    // Hardware Mode Toggles
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel mLab = new JLabel("Modes:");
    mLab.setFont(new Font("SansSerif", Font.BOLD, 12));
    mLab.setForeground(Color.WHITE);
    add(mLab, c);

    c.gridx = 1;
    c.gridwidth = 2;
    JPanel modesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 2));
    modesPanel.setBackground(SwingSynthConfigDialog.BG_CARD);

    quantizeCheck = createCheckBox("Quantize to Grid", st.isStutterQuantized());
    quantizeCheck.setToolTipText(
        "QUANTIZE: Lock stutter slice boundaries to song sequencer grid divisions");
    quantizeCheck.addActionListener(e -> st.setStutterQuantized(quantizeCheck.isSelected()));
    modesPanel.add(quantizeCheck);

    reverseCheck = createCheckBox("Reversed Playback", st.isStutterReversed());
    reverseCheck.setToolTipText("REVERSED: Play audio slices backward while stuttering");
    reverseCheck.addActionListener(e -> st.setStutterReversed(reverseCheck.isSelected()));
    modesPanel.add(reverseCheck);

    pingPongCheck = createCheckBox("Ping-Pong Bounce", st.isStutterPingPong());
    pingPongCheck.setToolTipText(
        "PING PONG: Alternate forward and backward playback across stutter slices");
    pingPongCheck.addActionListener(e -> st.setStutterPingPong(pingPongCheck.isSelected()));
    modesPanel.add(pingPongCheck);

    add(modesPanel, c);
  }

  private JCheckBox createCheckBox(String label, boolean initial) {
    JCheckBox cb = new JCheckBox(label, initial);
    cb.setBackground(SwingSynthConfigDialog.BG_CARD);
    cb.setForeground(Color.WHITE);
    cb.setFont(new Font("SansSerif", Font.PLAIN, 12));
    cb.setFocusPainted(false);
    return cb;
  }

  public JCheckBox getQuantizeCheck() {
    return quantizeCheck;
  }

  public JCheckBox getReverseCheck() {
    return reverseCheck;
  }

  public JCheckBox getPingPongCheck() {
    return pingPongCheck;
  }
}
