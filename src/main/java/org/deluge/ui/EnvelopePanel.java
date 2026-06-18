package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;

/** ENVELOPE tab: 4 sub-panels (ENV 0-3) with ADSR sliders + Target combo + Amount. */
public class EnvelopePanel extends JPanel {

  private static final String[] ENV_TARGETS = {"NONE", "VOLUME", "FILTER", "PITCH", "PAN"};

  public EnvelopePanel(
      SynthTrackModel model, BridgeContract bridge, int trackIndex, ProjectModel projectModel) {
    super(new BorderLayout(4, 4));
    setBackground(SwingSynthConfigDialog.BG_CARD);

    JTabbedPane envTabs = new JTabbedPane();
    envTabs.setBackground(new Color(0x25, 0x25, 0x25));
    envTabs.setForeground(Color.WHITE);

    for (int e = 0; e < 4; e++) {
      final int envIdx = e;
      EnvelopeModel env = model.getEnv(e);

      // We wrap the sliders and other controls in a slidersPanel
      JPanel slidersPanel = new JPanel(new GridBagLayout());
      slidersPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
      GridBagConstraints c = new GridBagConstraints();
      c.fill = GridBagConstraints.HORIZONTAL;
      c.insets = new Insets(4, 8, 4, 8);
      c.anchor = GridBagConstraints.WEST;
      int row = 0;

      // Create the graph component
      EnvelopeGraphComponent graph = new EnvelopeGraphComponent(model, envIdx);

      // ADSR sliders (dynamically fetching latest state from model to avoid captured-state
      // overwrite bugs)
      row =
          SwingSynthConfigDialog.addSlider(
              slidersPanel,
              c,
              row,
              "Attack:",
              "Time to reach peak level after note-on (" + envIdx + ")",
              1,
              2000,
              (int) (env.attack() * 1000),
              val -> {
                EnvelopeModel currentEnv = model.getEnv(envIdx);
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        val / 1000f,
                        currentEnv.decay(),
                        currentEnv.sustain(),
                        currentEnv.release(),
                        currentEnv.target(),
                        currentEnv.amount()));
                graph.repaint();
              },
              "ms",
              "env" + envIdx + "Attack",
              projectModel,
              trackIndex);

      row =
          SwingSynthConfigDialog.addSlider(
              slidersPanel,
              c,
              row,
              "Decay:",
              "Time to fall from peak to sustain level (" + envIdx + ")",
              0,
              5000,
              (int) (env.decay() * 1000),
              val -> {
                EnvelopeModel currentEnv = model.getEnv(envIdx);
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        currentEnv.attack(),
                        val / 1000f,
                        currentEnv.sustain(),
                        currentEnv.release(),
                        currentEnv.target(),
                        currentEnv.amount()));
                graph.repaint();
              },
              "ms",
              "env" + envIdx + "Decay",
              projectModel,
              trackIndex);

      row =
          SwingSynthConfigDialog.addSlider(
              slidersPanel,
              c,
              row,
              "Sustain:",
              "Level held while note is held (" + envIdx + ")",
              0,
              100,
              (int) (env.sustain() * 100),
              val -> {
                EnvelopeModel currentEnv = model.getEnv(envIdx);
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        currentEnv.attack(),
                        currentEnv.decay(),
                        val / 100f,
                        currentEnv.release(),
                        currentEnv.target(),
                        currentEnv.amount()));
                graph.repaint();
              },
              "%",
              "env" + envIdx + "Sustain",
              projectModel,
              trackIndex);

      row =
          SwingSynthConfigDialog.addSlider(
              slidersPanel,
              c,
              row,
              "Release:",
              "Time to fade to silence after note-off (" + envIdx + ")",
              0,
              5000,
              (int) (env.release() * 1000),
              val -> {
                EnvelopeModel currentEnv = model.getEnv(envIdx);
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        currentEnv.attack(),
                        currentEnv.decay(),
                        currentEnv.sustain(),
                        val / 1000f,
                        currentEnv.target(),
                        currentEnv.amount()));
                graph.repaint();
              },
              "ms",
              "env" + envIdx + "Release",
              projectModel,
              trackIndex);

      // Target Combo
      c.gridx = 0;
      c.gridy = row;
      c.gridwidth = 1;
      slidersPanel.add(SwingSynthConfigDialog.label("Target:"), c);
      c.gridx = 1;
      c.gridwidth = 2;
      JComboBox<String> targetCombo = new JComboBox<>(ENV_TARGETS);
      targetCombo.setSelectedItem(env.target());
      targetCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      targetCombo.setForeground(Color.WHITE);
      targetCombo.addActionListener(
          ev -> {
            String sel = (String) targetCombo.getSelectedItem();
            EnvelopeModel currentEnv = model.getEnv(envIdx);
            model.setEnv(
                envIdx,
                new EnvelopeModel(
                    currentEnv.attack(),
                    currentEnv.decay(),
                    currentEnv.sustain(),
                    currentEnv.release(),
                    sel,
                    currentEnv.amount()));
            graph.repaint();
          });
      slidersPanel.add(targetCombo, c);
      row++;

      // Amount
      row =
          SwingSynthConfigDialog.addSlider(
              slidersPanel,
              c,
              row,
              "Amount:",
              "Depth of envelope modulation (0–100%)",
              0,
              100,
              (int) (env.amount() * 100),
              val -> {
                EnvelopeModel currentEnv = model.getEnv(envIdx);
                model.setEnv(
                    envIdx,
                    new EnvelopeModel(
                        currentEnv.attack(),
                        currentEnv.decay(),
                        currentEnv.sustain(),
                        currentEnv.release(),
                        currentEnv.target(),
                        val / 100f));
                graph.repaint();
              },
              "%",
              "env" + envIdx + "Amount",
              projectModel,
              trackIndex);

      // Combine sliders and graph in a modern split layout
      JPanel tabContentPanel = new JPanel(new BorderLayout(16, 16));
      tabContentPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
      tabContentPanel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

      // Sliders on the left
      tabContentPanel.add(slidersPanel, BorderLayout.CENTER);

      // Beautiful glassmorphic titled wrapper for the visualizer on the right
      JPanel graphWrapper = new JPanel(new BorderLayout());
      graphWrapper.setBackground(SwingSynthConfigDialog.BG_CARD);
      graphWrapper.setBorder(
          BorderFactory.createTitledBorder(
              BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1, true),
              "Envelope Curve",
              javax.swing.border.TitledBorder.LEFT,
              javax.swing.border.TitledBorder.TOP,
              new Font("SansSerif", Font.BOLD, 11),
              Color.LIGHT_GRAY));
      graphWrapper.add(graph, BorderLayout.CENTER);
      tabContentPanel.add(graphWrapper, BorderLayout.EAST);

      envTabs.addTab("ENV " + e, tabContentPanel);
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
