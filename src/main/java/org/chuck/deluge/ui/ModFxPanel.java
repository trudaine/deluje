package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/** MOD FX tab: type, rate, depth, feedback, offset. */
public class ModFxPanel extends JPanel {

  public ModFxPanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    // ── Section header ──
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("MODULATION FX"), c);
    row++;

    // Type dropdown
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Type:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<String> typeCombo =
        new JComboBox<>(new String[] {"NONE", "CHORUS", "FLANGER", "PHASER"});
    typeCombo.setSelectedItem(model.getModFxType());
    typeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    typeCombo.setForeground(Color.WHITE);
    typeCombo.addActionListener(
        ev -> {
          String sel = (String) typeCombo.getSelectedItem();
          int ordinal =
              sel != null
                  ? switch (sel) {
                    case "NONE" -> 0;
                    case "CHORUS" -> 1;
                    case "FLANGER" -> 2;
                    case "PHASER" -> 3;
                    default -> 0;
                  }
                  : 0;
          model.setModFxType(sel);
          bridge.setModFxType(trackIndex, ordinal);
        });
    add(typeCombo, c);
    row++;

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Rate:",
            "Modulation FX rate (0-100%). Higher = faster modulation.",
            0,
            100,
            (int) (bridge.getModFxRate(trackIndex) * 100),
            val -> {
              model.setModFxRate(val / 100f);
              bridge.setModFxRate(trackIndex, val / 100f);
            },
            "%",
            "modFxRate",
            projectModel,
            trackIndex);

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Depth:",
            "Modulation FX depth (0-100%). Higher = more pronounced effect.",
            0,
            100,
            (int) (bridge.getModFxDepth(trackIndex) * 100),
            val -> {
              model.setModFxDepth(val / 100f);
              bridge.setModFxDepth(trackIndex, val / 100f);
            },
            "%",
            "modFxDepth",
            projectModel,
            trackIndex);

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Feedback:",
            "Modulation FX feedback (0-100%). Higher = more resonant, intense character.",
            0,
            100,
            (int) (bridge.getModFxFeedback(trackIndex) * 100),
            val -> {
              model.setModFxFeedback(val / 100f);
              bridge.setModFxFeedback(trackIndex, val / 100f);
            },
            "%",
            "modFxFeedback",
            projectModel,
            trackIndex);

    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Offset:",
            "Modulation FX offset (0-100%). Shifts the LFO phase for stereo variation.",
            0,
            100,
            (int) (bridge.getModFxOffset(trackIndex) * 100),
            val -> {
              model.setModFxOffset(val / 100f);
              bridge.setModFxOffset(trackIndex, val / 100f);
            },
            "%",
            "modFxOffset",
            projectModel,
            trackIndex);
  }
}
