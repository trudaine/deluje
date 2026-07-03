package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.AutomationParam;
import org.deluge.model.ClipModel;

/** Concrete AutomationGridPanel subclass for Parameter Automation view mode. */
public class AutomationGridPanel extends SwingGridPanel {

  public AutomationGridPanel(BridgeContract bridge) {
    super(bridge);
  }

  @Override
  public void rebuildUIComponents() {
    stopAuditionIfNeeded();
    refreshInProgress = true;
    removeAll();

    voiceRowCount = 8;
    this.stepCount = gridMode.columns;
    this.columnCount = this.stepCount;

    ClipModel autoClip = null;
    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      org.deluge.model.TrackModel t = projectModel.getTracks().get(editedModelTrack);
      int acIdx = t.getActiveClipIndex();
      if (acIdx >= 0 && acIdx < t.getClips().size()) {
        autoClip = t.getClips().get(acIdx);
      }
    }
    final ClipModel fAutoClip = autoClip;

    int padSz = cachedPadSz;
    int lw = currentLabelWidth();
    int rowW = getGridWidth(padSz, lw);

    JPanel autoHeader = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    autoHeader.setBackground(new Color(0x15, 0x15, 0x15));
    autoHeader.setMaximumSize(new Dimension(rowW, 32));
    autoHeader.setAlignmentX(Component.LEFT_ALIGNMENT);

    int topLw = currentLabelWidth();
    int leftOffset = automationController.isOverviewMode() ? (topLw + 17) : (topLw + 91);
    autoHeader.add(Box.createRigidArea(new Dimension(leftOffset, 1)));

    JLabel autoLabel = new JLabel("AUTO");
    autoLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
    autoLabel.setForeground(new Color(0x00, 0xff, 0xcc));
    autoHeader.add(autoLabel);

    JToggleButton overviewToggle =
        new JToggleButton("OVERVIEW", automationController.isOverviewMode());
    overviewToggle.setFont(new Font("SansSerif", Font.PLAIN, 11));
    overviewToggle.setMargin(new Insets(0, 4, 0, 4));
    overviewToggle.addActionListener(
        e -> {
          automationController.setOverviewMode(overviewToggle.isSelected());
          overviewToggle.setText(automationController.isOverviewMode() ? "OVERVIEW" : "EDITOR");
          refresh();
        });
    autoHeader.add(overviewToggle);

    if (!automationController.isOverviewMode()) {
      automationParamCombo = new JComboBox<>(AutomationParam.SYTH_PARAMS);
      automationParamCombo.setSelectedItem(automationController.getSelectedParam());
      automationParamCombo.addActionListener(
          e -> {
            String selected = (String) automationParamCombo.getSelectedItem();
            if (selected != null) {
              automationController.setSelectedParam(selected);
              refresh();
            }
          });
      automationParamCombo.setToolTipText("Select automation parameter");
      autoHeader.add(automationParamCombo);

      JButton interpBtn = new JButton("Interp");
      interpBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
      interpBtn.setMargin(new Insets(0, 4, 0, 4));
      interpBtn.setToolTipText("Linear interpolate between automated steps");
      interpBtn.addActionListener(
          e ->
              automationController.interpolateAutomation(
                  fAutoClip, automationController.getSelectedParam()));
      autoHeader.add(interpBtn);

      JButton clearAutoBtn = new JButton("Clear");
      clearAutoBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
      clearAutoBtn.setMargin(new Insets(0, 4, 0, 4));
      clearAutoBtn.addActionListener(
          e -> {
            if (fAutoClip != null) {
              fAutoClip.clearAutomation(automationController.getSelectedParam());
              refresh();
            }
          });
      autoHeader.add(clearAutoBtn);
    } else {
      JButton interpAllBtn = new JButton("Interp All");
      interpAllBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
      interpAllBtn.setMargin(new Insets(0, 4, 0, 4));
      interpAllBtn.setToolTipText("Interpolate all automated params");
      interpAllBtn.addActionListener(
          e -> {
            if (fAutoClip != null) {
              for (String param : fAutoClip.getAutomatedParams()) {
                automationController.interpolateAutomation(fAutoClip, param);
              }
              refresh();
            }
          });
      autoHeader.add(interpAllBtn);

      JButton clearAllBtn = new JButton("Clear All");
      clearAllBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
      clearAllBtn.setMargin(new Insets(0, 4, 0, 4));
      clearAllBtn.addActionListener(
          e -> {
            if (fAutoClip != null) {
              for (String param : fAutoClip.getAutomatedParams()) {
                fAutoClip.clearAutomation(param);
              }
              refresh();
            }
          });
      autoHeader.add(clearAllBtn);
    }

    if (projectModel != null && editedModelTrack < projectModel.getTracks().size()) {
      JLabel trackLabel = new JLabel("" + projectModel.getTracks().get(editedModelTrack).getName());
      trackLabel.setForeground(Color.LIGHT_GRAY);
      trackLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
      autoHeader.add(Box.createHorizontalStrut(10));
      autoHeader.add(trackLabel);
    }

    if (autoClip != null) {
      int autoCount = autoClip.getAutomatedParams().size();
      JLabel countLabel = new JLabel(" [" + autoCount + " auto'd]");
      countLabel.setForeground(new Color(0x88, 0xcc, 0x88));
      countLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
      autoHeader.add(countLabel);
    }

    add(autoHeader);

    automationController.buildAutomationView(this, autoClip, padSz);

    revalidate();
    repaint();
    refreshInProgress = false;
  }

  @Override
  public void refreshInPlace() {
    repaint();
  }
}
