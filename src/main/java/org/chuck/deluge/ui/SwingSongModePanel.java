package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Simulates the 64x8 Song launch pad dashboard. */
public class SwingSongModePanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private org.chuck.deluge.model.ProjectModel projectModel;
  private java.util.function.BiConsumer<Integer, Integer> onEditRequest;

  public SwingSongModePanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = new org.chuck.deluge.model.ProjectModel();

    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setPreferredSize(new Dimension(760, 400));
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

    refresh();
  }

  public void setProjectModel(org.chuck.deluge.model.ProjectModel model) {
    this.projectModel = model;
    refresh();
  }

  public void setOnEditRequest(java.util.function.BiConsumer<Integer, Integer> callback) {
    this.onEditRequest = callback;
  }

  public void updatePlayhead(int step) {
    Component[] rows = getComponents();
    for (int t = 0; t < Math.min(rows.length, 8); t++) {
      if (rows[t] instanceof JPanel rowPanel) {
        Component[] comps = rowPanel.getComponents();
        for (int c = 0; c < 8; c++) {
          if (c + 1 < comps.length && comps[c + 1] instanceof JButton pad) {
            boolean isTriggered = (bridge != null) && bridge.getStep(t * 8 + c, step % 16);
            if (isTriggered) {
              pad.setBackground(Color.WHITE);
            } else if (pad.getBackground().equals(Color.WHITE)) {
              pad.setBackground(new Color(0x00, 0xff, 0xcc)); // back to active green
            }
          }
        }
      }
    }
  }


  public void refresh() {
    removeAll();
    java.util.List<org.chuck.deluge.model.TrackModel> tracks = projectModel.getTracks();

    for (int t = 0; t < 8; t++) {
      JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));

      String trackName = (t < tracks.size()) ? tracks.get(t).getName() : "EMPTY " + (t + 1);
      JLabel label = new JLabel(trackName);
      label.setPreferredSize(new Dimension(150, 30));
      label.setForeground(Color.LIGHT_GRAY);
      rowPanel.add(label);

      final int currentTrack = t;
      for (int c = 0; c < 8; c++) {
        final int slot = c;
        JButton clipBtn = new JButton("PAD " + (c + 1));
        clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
        clipBtn.addActionListener(e -> {
          clipBtn.setBackground(Color.ORANGE); // Armed/Queued
          
          Timer timer = new Timer(100, null);
          final boolean[] flashState = {false};
          timer.addActionListener(ev -> {
            int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
            if (step == 0) {
              clipBtn.setBackground(new Color(0x00, 0xff, 0xcc)); // Playing
              timer.stop();
            } else {
              flashState[0] = !flashState[0];
              clipBtn.setBackground(flashState[0] ? Color.ORANGE : Color.LIGHT_GRAY);
            }
          });
          timer.start();

        });
        rowPanel.add(clipBtn);
      }


      JButton editBtn = new JButton("E");
      editBtn.setToolTipText("Open Clip Editor view for this track");
      editBtn.addActionListener(e -> {
        if (onEditRequest != null) {
          onEditRequest.accept(currentTrack, 0);
        }
      });
      rowPanel.add(editBtn);

      JButton colorBtn = new JButton("C");
      colorBtn.setToolTipText("Change clip pad color");
      colorBtn.addActionListener(e -> {
        Color chosen = JColorChooser.showDialog(this, "Select Pad Color", new Color(0x00, 0xff, 0xcc));
        if (chosen != null) {
          colorBtn.setBackground(chosen);
        }
      });
      rowPanel.add(colorBtn);


      JButton muteBtn = new JButton("M");
      muteBtn.addActionListener(e -> {
        if (muteBtn.getBackground().equals(Color.YELLOW)) {
          muteBtn.setBackground(UIManager.getColor("Button.background"));
          bridge.setMute(currentTrack * 8, false);
        } else {
          muteBtn.setBackground(Color.YELLOW);
          bridge.setMute(currentTrack * 8, true);
        }
      });
      rowPanel.add(muteBtn);

      add(rowPanel);
    }
    revalidate();
    repaint();
  }

}
