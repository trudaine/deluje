package org.chuck.deluge.ui;

import java.awt.*;
import java.util.Arrays;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/** HPF tab: cutoff, resonance, morph, mode, FM amount. */
public class HpfPanel extends JPanel {

  public HpfPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("HIGH-PASS FILTER"), c); row++;

    // HPF cutoff: 0-100 maps to 0-20000 Hz
    row = SwingSynthConfigDialog.addSlider(this, c, row, "Cutoff:",
        "HPF cutoff frequency (0–20000 Hz). Higher = more low-end removed.",
        0, 100, (int)(bridge.getHpfFreq(trackIndex) / 200.0f),
        val -> bridge.setHpfFreq(trackIndex, val / 100.0f * 200.0f), "Hz", "hpfCutoff",
        projectModel, trackIndex);

    // HPF resonance: 0-100
    row = SwingSynthConfigDialog.addSlider(this, c, row, "Resonance:",
        "HPF resonance/emphasis (0–100%). Higher = more pronounced peak at cutoff.",
        0, 100, (int)(bridge.getHpfRes(trackIndex) * 100),
        val -> bridge.setHpfRes(trackIndex, val / 100.0f), "%", "hpfResonance",
        projectModel, trackIndex);

    // HPF morph: 0-50 maps to 0-1
    row = SwingSynthConfigDialog.addSlider(this, c, row, "Morph:",
        "HPF morph/contour (0–100%). Changes filter character.",
        0, 50, (int)(bridge.getHpfMorph(trackIndex) * 50),
        val -> bridge.setHpfMorph(trackIndex, val / 50.0f), "", "hpfMorph",
        projectModel, trackIndex);

    // HPF mode combo
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Mode:"), c);
    c.gridx = 1; c.gridwidth = 2;
    String[] modeNames = Arrays.stream(org.chuck.deluge.model.FilterMode.values())
        .map(Enum::name).toArray(String[]::new);
    JComboBox<String> hpfModeCombo = new JComboBox<>(modeNames);
    hpfModeCombo.setSelectedIndex(bridge.getHpfMode(trackIndex));
    hpfModeCombo.setBackground(new Color(0x33, 0x33, 0x33));
    hpfModeCombo.setForeground(Color.WHITE);
    hpfModeCombo.addActionListener(ev -> {
      int idx = hpfModeCombo.getSelectedIndex();
      bridge.setHpfMode(trackIndex, idx);
    });
    add(hpfModeCombo, c); row++;

    // HPF FM amount: 0-100
    row = SwingSynthConfigDialog.addSlider(this, c, row, "FM:",
        "HPF FM amount (0–100%). Modulates HPF cutoff with the filter envelope.",
        0, 100, (int)(bridge.getHpfFm(trackIndex) * 100),
        val -> bridge.setHpfFm(trackIndex, val / 100.0f), "%", "hpfFm",
        projectModel, trackIndex);
  }
}
