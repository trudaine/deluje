package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.FilterMode;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;

/** HPF tab: cutoff, resonance, morph, mode, FM amount. */
public class HpfPanel extends JPanel {

  // C gui/menu_item/filter/mode.h:65-72, filter_config.h:29-40 — only SVF_BAND, SVF_NOTCH,
  // HPLADDER (represented as FilterMode.LADDER_12 in the model), and OFF exist on the HPF slot.
  private static final String[] HPF_MODE_LABELS = {"SVF_BAND", "SVF_NOTCH", "HPLADDER", "OFF"};
  private static final FilterMode[] HPF_MODES = {
    FilterMode.SVF_BAND, FilterMode.SVF_NOTCH, FilterMode.LADDER_12, FilterMode.OFF
  };

  static int hpfModeToComboIndex(FilterMode mode) {
    if (mode == null) return 3;
    return switch (mode) {
      case SVF, SVF_BAND -> 0;
      case SVF_NOTCH -> 1;
      case LADDER_12, LADDER_24, DRIVE -> 2;
      case OFF -> 3;
    };
  }

  public HpfPanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new GridBagLayout());
    setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 3;
    add(SwingSynthConfigDialog.sectionLabel("HIGH-PASS FILTER"), c);
    row++;

    // HPF cutoff: 0-100 maps to 0-20000 Hz
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Cutoff:",
            "HPF cutoff frequency (0–20000 Hz). Higher = more low-end removed.",
            0,
            100,
            (int) (model.getHpfFreq() / 200.0f),
            val -> model.setHpfFreq(val * 200.0f),
            "Hz",
            "hpfCutoff",
            projectModel,
            trackIndex);

    // HPF resonance: 0-100
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Resonance:",
            "HPF resonance/emphasis (0–100%). Higher = more pronounced peak at cutoff.",
            0,
            100,
            (int) (model.getHpfRes() * 100),
            val -> model.setHpfRes(val / 100.0f),
            "%",
            "hpfResonance",
            projectModel,
            trackIndex);

    // HPF morph: 0-50 maps to 0-1
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "Morph:",
            "HPF morph/contour (0–100%). Changes filter character.",
            0,
            50,
            (int) (model.getHpfMorph() * 50),
            val -> model.setHpfMorph(val / 50.0f),
            "",
            "hpfMorph",
            projectModel,
            trackIndex);

    // HPF mode combo (C gui/menu_item/filter/mode.h:65-72)
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    add(SwingSynthConfigDialog.label("Mode:"), c);
    c.gridx = 1;
    c.gridwidth = 2;
    JComboBox<String> hpfModeCombo = new JComboBox<>(HPF_MODE_LABELS);
    hpfModeCombo.setSelectedIndex(hpfModeToComboIndex(model.getHpfMode()));
    hpfModeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    hpfModeCombo.setForeground(Color.WHITE);
    hpfModeCombo.addActionListener(
        ev -> {
          int idx = hpfModeCombo.getSelectedIndex();
          if (idx >= 0 && idx < HPF_MODES.length) {
            model.setHpfMode(HPF_MODES[idx]);
            if (SwingDelugeApp.mainInstance != null) {
              SwingDelugeApp.mainInstance.fireProjectChanged();
            }
          }
        });
    add(hpfModeCombo, c);
    row++;

    // HPF FM amount: 0-100
    row =
        SwingSynthConfigDialog.addSlider(
            this,
            c,
            row,
            "FM:",
            "HPF FM amount (0–100%). Modulates HPF cutoff with the filter envelope.",
            0,
            100,
            (int) (model.getHpfFm() * 100),
            val -> model.setHpfFm(val / 100.0f),
            "%",
            "hpfFm",
            projectModel,
            trackIndex);
  }
}
