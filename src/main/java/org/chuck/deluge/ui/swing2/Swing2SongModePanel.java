package org.chuck.deluge.ui.swing2;

import java.awt.*;
import javax.swing.*;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.TrackModel;

public class Swing2SongModePanel extends JPanel implements ProjectModel.ProjectListener {
  private ProjectModel model;
  private java.util.function.BiConsumer<Integer, Integer> onEditRequest;
  private JButton[][] variationPads = new JButton[8][8];

  private org.chuck.deluge.BridgeContract bridge;

  public Swing2SongModePanel(org.chuck.deluge.BridgeContract bridge) {
    this.bridge = bridge;
    setLayout(new java.awt.GridLayout(8, 1, 2, 2));
    setBackground(new Color(0x1a, 0x1a, 0x1a));
  }

  public void setProjectModel(ProjectModel model) {
    this.model = model;
    if (this.model != null) {
      this.model.addProjectListener(this);
      refreshUI();
    }
  }

  public void setOnEditRequest(java.util.function.BiConsumer<Integer, Integer> callback) {
    this.onEditRequest = callback;
  }

  private void refreshUI() {
    removeAll();
    String res = org.chuck.deluge.project.PreferencesManager.get("screen.resolution", "FHD");
    final int padSz = "FHD".equals(res) ? 90 : ("4K".equals(res) ? 180 : 120);

    for (int i = 0; i < 8; i++) {
      final int trkIdx = i;
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT));
      row.setBackground(new Color(0x2a, 0x2a, 0x2a));
      row.setPreferredSize(new Dimension(2200, padSz));
      row.setMinimumSize(new Dimension(2200, padSz));
      row.setMaximumSize(new Dimension(2200, padSz));

      if (i < model.getTracks().size()) {
        TrackModel track = model.getTracks().get(i);
        JLabel lbl = new JLabel(track.getName() + " : ");
        lbl.setPreferredSize(new Dimension(150, padSz));
        lbl.setForeground(Color.WHITE);
        row.add(lbl);

        for (int c = 0; c < 8; c++) {
          JButton pad = new JButton("C" + (c + 1));
          pad.setPreferredSize(new Dimension(padSz, padSz));
          pad.setMinimumSize(new Dimension(padSz, padSz));
          pad.setMaximumSize(new Dimension(padSz, padSz));
          pad.setBackground(new Color(0x33, 0x33, 0x33));
          variationPads[i][c] = pad;
          row.add(pad);
        }

        JButton muteBtn = new JButton("MUTE");
        muteBtn.setPreferredSize(new Dimension(padSz, padSz));
        muteBtn.setMinimumSize(new Dimension(padSz, padSz));
        muteBtn.setMaximumSize(new Dimension(padSz, padSz));
        muteBtn.setBackground(track.isMuted() ? Color.RED : new Color(0x33, 0x33, 0x33));
        muteBtn.addActionListener(
            e -> {
              boolean wasMuted = track.isMuted();
              track.setMuted(!wasMuted);
              if (bridge != null) bridge.setMute(trkIdx, !wasMuted);
              muteBtn.setBackground(!wasMuted ? Color.RED : new Color(0x33, 0x33, 0x33));
            });
        row.add(muteBtn);

        JButton editBtn = new JButton("EDIT");
        editBtn.setPreferredSize(new Dimension(padSz, padSz));
        editBtn.setMinimumSize(new Dimension(padSz, padSz));
        editBtn.setMaximumSize(new Dimension(padSz, padSz));
        editBtn.addActionListener(
            e -> {
              if (onEditRequest != null) {
                onEditRequest.accept(trkIdx, 0);
              }
            });
        row.add(editBtn);
      } else {
        JLabel lbl = new JLabel("EMPTY " + (i + 1) + " : ");
        lbl.setPreferredSize(new Dimension(150, padSz));
        lbl.setForeground(Color.DARK_GRAY);
        row.add(lbl);

        for (int c = 0; c < 8; c++) {
          JButton pad = new JButton();
          pad.setPreferredSize(new Dimension(padSz, padSz));
          pad.setMinimumSize(new Dimension(padSz, padSz));
          pad.setMaximumSize(new Dimension(padSz, padSz));
          pad.setBackground(new Color(0x22, 0x22, 0x22));
          pad.setEnabled(false);
          row.add(pad);
        }
      }

      add(row);
    }

    revalidate();
    repaint();
  }

  @Override
  public void onTrackListChanged() {
    refreshUI();
  }

  @Override
  public void onBpmChanged(float bpm) {
    // Ignored in arrangement list
  }

  public void updatePlayhead(int step) {
    int col = (step % 8);
    for (int i = 0; i < 8; i++) {
      for (int c = 0; c < 8; c++) {
        if (variationPads[i][c] != null) {
          if (c == col) {
            variationPads[i][c].setBackground(Color.WHITE);
          } else {
            variationPads[i][c].setBackground(new Color(0x33, 0x33, 0x33));
          }
        }
      }
    }
  }
}
