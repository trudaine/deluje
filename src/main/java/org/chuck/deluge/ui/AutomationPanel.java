package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.AutomationParam;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * AUTOMATION tab: per-param × per-step slider table. Operates on the active clip's automation data.
 */
public class AutomationPanel extends JPanel {

  public AutomationPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    super(new BorderLayout(4, 4));
    setBackground(SwingSynthConfigDialog.BG_CARD);

    ClipModel clip =
        model.getClips().isEmpty() ? null : model.getClips().get(model.getActiveClipIndex());
    int stepCount = (clip != null) ? clip.getStepCount() : 16;

    // ── Header with Clear All button ──
    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
    topBar.setBackground(SwingSynthConfigDialog.BG_CARD);
    topBar.add(
        SwingSynthConfigDialog.sectionLabel(
            "PER-STEP AUTOMATION — Active clip: " + (clip != null ? clip.getName() : "none")));

    JButton clearAllBtn = new JButton("Clear All");
    clearAllBtn.setToolTipText("Remove all automation data for the active clip");
    SwingSynthConfigDialog.attachHoverHelp(
        clearAllBtn,
        "<b>CLEAR ALL AUTOMATION:</b> Deletes all step automation data and envelope patterns for this active clip.");
    clearAllBtn.addActionListener(
        e -> {
          if (clip != null) {
            for (String param : AutomationParam.SYTH_PARAMS) {
              clip.clearAutomation(param);
            }
          }
        });
    topBar.add(clearAllBtn);

    if (clip == null) {
      clearAllBtn.setEnabled(false);
      JLabel warnLabel =
          new JLabel(
              "⚠️ NO ACTIVE CLIP: Create a sequence pattern clip in Song view to enable step automation!");
      warnLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
      warnLabel.setForeground(new Color(0xff, 0x99, 0x00));
      topBar.add(warnLabel);
    }
    add(topBar, BorderLayout.NORTH);

    // ── Scrollable table: rows = params, columns = steps ──
    JPanel tablePanel = new JPanel(new GridBagLayout());
    tablePanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(2, 4, 2, 4);
    c.anchor = GridBagConstraints.WEST;

    // Header row: corner + step numbers
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 1;
    tablePanel.add(SwingSynthConfigDialog.headerLabel("PARAM"), c);
    for (int s = 0; s < stepCount; s++) {
      c.gridx = s + 1;
      tablePanel.add(SwingSynthConfigDialog.headerLabel(String.valueOf(s + 1)), c);
    }

    // Param rows
    String[] paramNames = AutomationParam.SYTH_PARAMS;
    for (int p = 0; p < paramNames.length; p++) {
      final int paramIdx = p;
      String paramName = paramNames[p];
      int row = p + 1;

      // Param label + enable checkbox
      c.gridx = 0;
      c.gridy = row;
      c.gridwidth = 1;
      boolean paramHasData = clip != null && clip.hasAutomation(paramName);
      JCheckBox enableBox = new JCheckBox(paramName, paramHasData);
      enableBox.setForeground(paramHasData ? Color.CYAN : Color.LIGHT_GRAY);
      enableBox.setBackground(SwingSynthConfigDialog.BG_CARD);
      enableBox.setPreferredSize(new Dimension(140, 24));
      enableBox.addActionListener(
          ev -> {
            if (clip == null) return;
            if (enableBox.isSelected()) {
              for (int ss = 0; ss < clip.getStepCount(); ss++) {
                clip.setAutomation(paramName, ss, 0.0f);
              }
            } else {
              clip.clearAutomation(paramName);
            }
            enableBox.setForeground(enableBox.isSelected() ? Color.CYAN : Color.LIGHT_GRAY);
            Container parent = tablePanel.getParent();
            if (parent instanceof JViewport) {
              ((JViewport) parent).getParent().revalidate();
              ((JViewport) parent).getParent().repaint();
            }
          });
      SwingSynthConfigDialog.attachHoverHelp(
          enableBox,
          "<b>AUTOMATION FOR "
              + paramName.toUpperCase()
              + ":</b> Click to enable or disable step automation for this parameter across the active clip. Enabling creates individual step sliders.");
      tablePanel.add(enableBox, c);

      // Per-step sliders
      for (int s = 0; s < stepCount; s++) {
        final int stepIdx = s;
        boolean hasAuto = clip != null && clip.hasAutomation(paramName, s);
        int val = hasAuto ? (int) (clip.getAutomation(paramName, s) * 127) : 0;

        JSlider slider = new JSlider(0, 127, val);
        slider.setBackground(SwingSynthConfigDialog.BG_CARD);
        slider.setPreferredSize(new Dimension(70, 22));
        slider.setPaintTicks(false);
        slider.setPaintLabels(false);
        slider.setEnabled(hasAuto);

        JLabel valLabel = new JLabel(hasAuto ? String.valueOf(val) : "-");
        valLabel.setForeground(hasAuto ? Color.CYAN : Color.DARK_GRAY);
        valLabel.setPreferredSize(new Dimension(30, 20));

        slider.addChangeListener(
            ev -> {
              if (clip == null || !slider.isEnabled()) return;
              clip.setAutomation(paramName, stepIdx, slider.getValue() / 127.0f);
              valLabel.setText(String.valueOf(slider.getValue()));
            });

        SwingSynthConfigDialog.attachHoverHelp(
            slider,
            "<b>"
                + paramName.toUpperCase()
                + " STEP "
                + (stepIdx + 1)
                + ":</b> Adjust the value (0 to 127) of the "
                + paramName.toUpperCase()
                + " parameter specifically at sequencer step "
                + (stepIdx + 1)
                + ".");
        JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        cell.setBackground(SwingSynthConfigDialog.BG_CARD);
        cell.add(slider);
        cell.add(valLabel);

        c.gridx = s + 1;
        c.gridy = row;
        tablePanel.add(cell, c);
      }
    }

    JScrollPane scroll = new JScrollPane(tablePanel);
    scroll.setPreferredSize(new Dimension(900, 400));
    add(scroll, BorderLayout.CENTER);
  }
}
