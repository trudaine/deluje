package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/** EQ tab: bass and treble shelving cut/boost. */
public class EqPanel extends JPanel {

  public EqPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("SHELVING EQ"), c); row++;

    row = SwingSynthConfigDialog.addSlider(this, c, row, "Bass:",
        "Bass shelving EQ cut/boost (-100% = -12dB cut, 0% = flat, +100% = +12dB boost)",
        -100, 100, (int)(bridge.getEqBass(trackIndex) * 100),
        val -> bridge.setEqBass(trackIndex, val / 100f), "%", "eqBass",
        projectModel, trackIndex);
    row = SwingSynthConfigDialog.addSlider(this, c, row, "Treble:",
        "Treble shelving EQ cut/boost (-100% = -12dB cut, 0% = flat, +100% = +12dB boost)",
        -100, 100, (int)(bridge.getEqTreble(trackIndex) * 100),
        val -> bridge.setEqTreble(trackIndex, val / 100f), "%", "eqTreble",
        projectModel, trackIndex);
  }
}
