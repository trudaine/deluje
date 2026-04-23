package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Simulates the 64x8 Song launch pad dashboard. */
public class SwingSongModePanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  public SwingSongModePanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new GridLayout(8, 8, 5, 5));
    setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    for (int row = 0; row < 8; row++) {
      for (int col = 0; col < 8; col++) {
        JButton pad = new JButton("CLIP " + (row * 8 + col + 1));
        pad.setBackground(new Color(0x33, 0x33, 0x33));
        pad.setForeground(Color.DARK_GRAY);
        pad.setFocusPainted(false);

        final int trackIdx = row;
        final int clipIdx = col;

        pad.addActionListener(
            e -> {
              boolean wasPlaying = pad.getBackground().equals(new Color(0x00, 0xff, 0x00));
              if (wasPlaying) {
                pad.setBackground(new Color(0x33, 0x33, 0x33));
                pad.setForeground(Color.DARK_GRAY);
              } else {
                pad.setBackground(new Color(0x00, 0xff, 0x00));
                pad.setForeground(Color.BLACK);
              }
            });
        add(pad);
      }
    }
  }
}
