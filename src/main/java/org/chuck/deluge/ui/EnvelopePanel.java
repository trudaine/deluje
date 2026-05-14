package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SynthTrackModel;

/** ENVELOPE tab: 4 sub-panels (ENV 0-3) with ADSR sliders + Target combo + Amount. */
public class EnvelopePanel extends JPanel {

  private static final String[] ENV_TARGETS = {"NONE", "VOLUME", "FILTER", "PITCH", "PAN"};

  public EnvelopePanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new BorderLayout(4, 4));
    setBackground(new Color(0x22, 0x22, 0x22));

    JTabbedPane envTabs = new JTabbedPane();
    envTabs.setBackground(new Color(0x25, 0x25, 0x25));
    envTabs.setForeground(Color.WHITE);

    for (int e = 0; e < 4; e++) {
      final int envIdx = e;
      EnvelopeModel env = model.getEnv(e);
      JPanel panel = new JPanel(new GridBagLayout());
      panel.setBackground(new Color(0x22, 0x22, 0x22));
      GridBagConstraints c = new GridBagConstraints();
      c.fill = GridBagConstraints.HORIZONTAL;
      c.insets = new Insets(6, 10, 6, 10);
      c.anchor = GridBagConstraints.WEST;
      int row = 0;

      // ADSR sliders
      row =
          SwingSynthConfigDialog.addSlider(
              panel,
              c,
              row,
              "Attack:",
              "Time to reach peak level after note-on (" + envIdx + ")",
              1,
              2000,
              (int) (env.attack() * 1000),
              val -> {
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        val / 1000f,
                        env.decay(),
                        env.sustain(),
                        env.release(),
                        env.target(),
                        env.amount()));
              },
              "ms",
              "env" + envIdx + "Attack",
              projectModel,
              trackIndex);
      row =
          SwingSynthConfigDialog.addSlider(
              panel,
              c,
              row,
              "Decay:",
              "Time to fall from peak to sustain level (" + envIdx + ")",
              0,
              5000,
              (int) (env.decay() * 1000),
              val -> {
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        env.attack(),
                        val / 1000f,
                        env.sustain(),
                        env.release(),
                        env.target(),
                        env.amount()));
              },
              "ms",
              "env" + envIdx + "Decay",
              projectModel,
              trackIndex);
      row =
          SwingSynthConfigDialog.addSlider(
              panel,
              c,
              row,
              "Sustain:",
              "Level held while note is held (" + envIdx + ")",
              0,
              100,
              (int) (env.sustain() * 100),
              val -> {
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        env.attack(),
                        env.decay(),
                        val / 100f,
                        env.release(),
                        env.target(),
                        env.amount()));
              },
              "%",
              "env" + envIdx + "Sustain",
              projectModel,
              trackIndex);
      row =
          SwingSynthConfigDialog.addSlider(
              panel,
              c,
              row,
              "Release:",
              "Time to fade to silence after note-off (" + envIdx + ")",
              0,
              5000,
              (int) (env.release() * 1000),
              val -> {
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        env.attack(),
                        env.decay(),
                        env.sustain(),
                        val / 1000f,
                        env.target(),
                        env.amount()));
              },
              "ms",
              "env" + envIdx + "Release",
              projectModel,
              trackIndex);

      // Target
      c.gridx = 0;
      c.gridy = row;
      c.gridwidth = 1;
      panel.add(SwingSynthConfigDialog.label("Target:"), c);
      c.gridx = 1;
      c.gridwidth = 2;
      JComboBox<String> targetCombo = new JComboBox<>(ENV_TARGETS);
      targetCombo.setSelectedItem(env.target());
      targetCombo.setBackground(new Color(0x33, 0x33, 0x33));
      targetCombo.setForeground(Color.WHITE);
      targetCombo.addActionListener(
          ev -> {
            String sel = (String) targetCombo.getSelectedItem();
            model.setEnv(
                envIdx,
                new EnvelopeModel(
                    env.attack(), env.decay(), env.sustain(), env.release(), sel, env.amount()));
          });
      panel.add(targetCombo, c);
      row++;

      // Amount
      row =
          SwingSynthConfigDialog.addSlider(
              panel,
              c,
              row,
              "Amount:",
              "Depth of envelope modulation (0–100%)",
              0,
              100,
              (int) (env.amount() * 100),
              val -> {
                float amt = val / 100f;
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        env.attack(),
                        env.decay(),
                        env.sustain(),
                        env.release(),
                        env.target(),
                        amt));
              },
              "%",
              "env" + envIdx + "Amount",
              projectModel,
              trackIndex);

      envTabs.addTab("ENV " + e, panel);
    }

    add(envTabs, BorderLayout.CENTER);

    JLabel note =
        new JLabel(
            "<html><i>Default: ENV0→Volume, ENV1→Filter, ENV2→Pitch, ENV3→Pan. "
                + "Set Target & Amount to override per envelope via patch cable.</i></html>");
    note.setForeground(Color.GRAY);
    note.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
    add(note, BorderLayout.SOUTH);
  }
}
