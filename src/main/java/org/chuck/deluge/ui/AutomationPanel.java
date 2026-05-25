package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.AutomationParam;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.SynthTrackModel;

/**
 * AUTOMATION tab: per-parameter x per-step step sliders matrix table interface. Operates on the
 * active clip's step automation registers with smooth, non-adjusting JNI bridges.
 */
public class AutomationPanel extends JPanel {

  public AutomationPanel(SynthTrackModel model, BridgeContract bridge, int trackIndex) {
    super(new BorderLayout(6, 6));
    setBackground(SwingSynthConfigDialog.BG_CARD);
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    ClipModel clip =
        model.getClips().isEmpty() ? null : model.getClips().get(model.getActiveClipIndex());
    int stepCount = (clip != null) ? clip.getStepCount() : 16;

    // ── Header with Clear All Button ──
    JPanel topBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
    topBar.setBackground(SwingSynthConfigDialog.BG_CARD);
    topBar.add(
        SwingSynthConfigDialog.sectionLabel(
            "🎛️ STEP AUTOMATION MATRIX — Active Clip: "
                + (clip != null ? clip.getName() : "none")));

    JButton clearAllBtn = new JButton("Clear All Automation");
    styleButton(clearAllBtn, new Color(0x3e, 0x0c, 0x0c), new Color(0xff, 0x55, 0x55));
    clearAllBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
    SwingSynthConfigDialog.attachHoverHelp(
        clearAllBtn,
        "<b>CLEAR ALL AUTOMATION:</b> Deletes all step automation registers and patterns data for this active clip.");
    clearAllBtn.addActionListener(
        e -> {
          if (clip != null) {
            int confirm =
                JOptionPane.showConfirmDialog(
                    this,
                    "⚠️ Are you sure you want to clear all step automation registers for this clip?",
                    "Clear Automation?",
                    JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
              for (String param : AutomationParam.SYTH_PARAMS) {
                clip.clearAutomation(param);
              }
              // Force JApp rebuild / refresh!
              SwingDelugeApp.mainInstance.pushModelToBridge();
              SwingDelugeApp.mainInstance.propagateCurrentModel();
              SwingDelugeApp.mainInstance.syncHighFidelityEngine(
                  SwingDelugeApp.mainInstance.getCurrentProject());
              JOptionPane.showMessageDialog(
                  this,
                  "🎉 All step automation data cleared successfully!",
                  "Cleared",
                  JOptionPane.INFORMATION_MESSAGE);
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

    // ── Scrollable Table Matrix Panel ──
    JPanel tablePanel = new JPanel(new GridBagLayout());
    tablePanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(3, 6, 3, 6);
    c.anchor = GridBagConstraints.WEST;

    // Header row: Param label + Step index columns
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 1;
    tablePanel.add(SwingSynthConfigDialog.headerLabel("PARAMETER SELECTOR"), c);
    for (int s = 0; s < stepCount; s++) {
      c.gridx = s + 1;
      JLabel stepLbl = SwingSynthConfigDialog.headerLabel("STEP " + String.format("%02d", s + 1));
      stepLbl.setHorizontalAlignment(SwingConstants.CENTER);
      tablePanel.add(stepLbl, c);
    }

    // Parameter rows
    String[] paramNames = AutomationParam.SYTH_PARAMS;
    for (int p = 0; p < paramNames.length; p++) {
      final String paramName = paramNames[p];
      int row = p + 1;

      // Local JSliders and JLabels state trackers row arrays!
      final JSlider[] rowSliders = new JSlider[stepCount];
      final JLabel[] rowLabels = new JLabel[stepCount];

      // Param enable checkbox
      c.gridx = 0;
      c.gridy = row;
      c.gridwidth = 1;
      boolean paramHasData = clip != null && clip.hasAutomation(paramName);

      JCheckBox enableBox = new JCheckBox(paramName, paramHasData);
      enableBox.setFont(new Font("SansSerif", Font.BOLD, 12));
      enableBox.setForeground(paramHasData ? Color.CYAN : Color.LIGHT_GRAY);
      enableBox.setBackground(SwingSynthConfigDialog.BG_CARD);
      enableBox.setPreferredSize(new Dimension(150, 24));
      enableBox.setFocusable(false);

      enableBox.addActionListener(
          ev -> {
            if (clip == null) return;
            boolean isSelected = enableBox.isSelected();
            if (isSelected) {
              for (int ss = 0; ss < clip.getStepCount(); ss++) {
                clip.setAutomation(paramName, ss, 0.5f); // Start with neutral 50% default!
              }
            } else {
              clip.clearAutomation(paramName);
            }

            enableBox.setForeground(isSelected ? Color.CYAN : Color.LIGHT_GRAY);

            // Toggle JSliders and JLabels states live on screen instantly!
            for (int s = 0; s < stepCount; s++) {
              JSlider sl = rowSliders[s];
              JLabel vl = rowLabels[s];
              if (sl != null && vl != null) {
                sl.setEnabled(isSelected);
                vl.setEnabled(isSelected);
                if (isSelected) {
                  sl.setValue(64); // Reset JSlider back to neutral middle!
                  vl.setText("64");
                  vl.setForeground(Color.CYAN);
                } else {
                  vl.setText("-");
                  vl.setForeground(Color.DARK_GRAY);
                }
              }
            }

            // Live JNI Sync
            SwingDelugeApp.mainInstance.pushModelToBridge();
            SwingDelugeApp.mainInstance.propagateCurrentModel();
            SwingDelugeApp.mainInstance.syncHighFidelityEngine(
                SwingDelugeApp.mainInstance.getCurrentProject());
          });

      SwingSynthConfigDialog.attachHoverHelp(
          enableBox,
          "<b>AUTOMATION FOR "
              + paramName.toUpperCase()
              + ":</b> Click to enable or disable step automation for this parameter across the active clip. Enabling creates individual step sliders.");
      tablePanel.add(enableBox, c);

      // Per-step columns JSliders loop
      for (int s = 0; s < stepCount; s++) {
        final int stepIdx = s;
        boolean hasAuto = clip != null && clip.hasAutomation(paramName, s);
        int val = hasAuto ? (int) (clip.getAutomation(paramName, s) * 127) : 0;

        JSlider slider = new JSlider(0, 127, val);
        slider.setPreferredSize(new Dimension(80, 22));
        slider.setPaintTicks(false);
        slider.setPaintLabels(false);
        slider.setEnabled(hasAuto);
        rowSliders[s] = slider;

        JLabel valLabel = new JLabel(hasAuto ? String.valueOf(val) : "-");
        valLabel.setFont(new Font("Monospaced", Font.BOLD, 11));
        valLabel.setForeground(hasAuto ? Color.CYAN : Color.DARK_GRAY);
        valLabel.setPreferredSize(new Dimension(28, 20));
        valLabel.setHorizontalAlignment(SwingConstants.CENTER);
        rowLabels[s] = valLabel;

        slider.addChangeListener(
            ev -> {
              valLabel.setText(String.valueOf(slider.getValue()));
              // Drag-and-release performance safety (blocks JNI flood timings queue starvation)
              if (!slider.getValueIsAdjusting()) {
                if (clip != null && slider.isEnabled()) {
                  clip.setAutomation(paramName, stepIdx, slider.getValue() / 127.0f);
                  // Propagate steps updates to active sequencer registers
                  SwingDelugeApp.mainInstance.pushModelToBridge();
                  SwingDelugeApp.mainInstance.propagateCurrentModel();
                  SwingDelugeApp.mainInstance.syncHighFidelityEngine(
                      SwingDelugeApp.mainInstance.getCurrentProject());
                }
              }
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

        JPanel cell = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        cell.setBackground(new Color(0x1a, 0x1a, 0x1e));
        cell.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0x2d, 0x2d, 0x32)));
        cell.add(slider);
        cell.add(valLabel);

        c.gridx = s + 1;
        c.gridy = row;
        tablePanel.add(cell, c);
      }
    }

    JScrollPane scroll = new JScrollPane(tablePanel);
    scroll.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1));
    scroll.setBackground(new Color(0x12, 0x12, 0x14));
    scroll.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    scroll.getHorizontalScrollBar().setUnitIncrement(16);
    SwingRandomizerDialog.styleScrollBar(scroll.getVerticalScrollBar());
    SwingRandomizerDialog.styleScrollBar(scroll.getHorizontalScrollBar());
    add(scroll, BorderLayout.CENTER);

    DarkComboBoxRenderer.styleComponentTree(this);
  }

  private void styleButton(AbstractButton btn, Color bg, Color fg) {
    btn.setOpaque(true);
    btn.setBorderPainted(true);
    btn.setContentAreaFilled(true);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusable(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.brighter(), 1),
            BorderFactory.createEmptyBorder(3, 8, 3, 8)));
  }
}
