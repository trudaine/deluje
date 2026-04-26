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
  private JButton[][] pads = new JButton[8][18];
  private boolean[] queuedMuteArmed = new boolean[8];
  private boolean[] queuedMuteTargetState = new boolean[8];

  public enum GridViewMode {
    CLIP,
    SONG
  }

  private GridViewMode viewMode = GridViewMode.SONG; // Default to SONG, can set to CLIP

  public SwingSongModePanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;
    this.projectModel = new org.chuck.deluge.model.ProjectModel();

    setBackground(new Color(0x1a, 0x1a, 0x1a));
    setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
  }

  public void setViewMode(GridViewMode mode) {
    this.viewMode = mode;
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
    if (step % 16 == 0) {
      for (int t = 0; t < 8; t++) {
        if (queuedMuteArmed[t]) {
          boolean targetState = queuedMuteTargetState[t];
          for (int i = 0; i < 8; i++) {
            bridge.setMute(t * 8 + i, targetState);
          }
          queuedMuteArmed[t] = false;
          if (pads[t][16] != null) {
            pads[t][16].setBackground(targetState ? Color.RED : new Color(0x33, 0x33, 0x33));
          }
        }
      }
    }

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
      JPanel rowPanel = new JPanel();
      rowPanel.setLayout(new BoxLayout(rowPanel, BoxLayout.X_AXIS));
      rowPanel.setBackground(new Color(0x22, 0x22, 0x22));

      final int currentTrack = t;
      String trackName = (t < tracks.size()) ? tracks.get(t).getName() : "EMPTY " + (t + 1);
      JLabel label = new JLabel(trackName);
      label.setPreferredSize(new Dimension(150, 30));
      label.setMinimumSize(new Dimension(150, 30));
      label.setMaximumSize(new Dimension(150, 30));
      label.setForeground(Color.LIGHT_GRAY);
      label.setCursor(new Cursor(Cursor.HAND_CURSOR));
      label.addMouseListener(
          new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
              if (onEditRequest != null) {
                onEditRequest.accept(currentTrack, 0);
              }
            }
          });
      rowPanel.add(label);
      rowPanel.add(Box.createHorizontalStrut(10));

      for (int c = 0; c < 18; c++) {

        final int slot = c;
        JButton clipBtn = new JButton();
        clipBtn.setPreferredSize(new Dimension(120, 120));
        clipBtn.setMinimumSize(new Dimension(120, 120));
        clipBtn.setMaximumSize(new Dimension(120, 120));

        pads[t][c] = clipBtn;

        if (viewMode == GridViewMode.CLIP) {
          clipBtn.setText(
              "<html><font size='3'>Pi:"
                  + (currentTrack * 8)
                  + "<br>Ve:0.8<br>Pr:1.0<br>Ga:1</font></html>");
        } else {
          clipBtn.setText("PAD " + (c + 1));
        }

        boolean hasClip = false;
        if (t < tracks.size()) {
          org.chuck.deluge.model.TrackModel track = tracks.get(t);
          if (c < track.getClips().size()) {
            hasClip = true;
          }
        }

        if (c == 16) {
          clipBtn.setText("MUTE");
          clipBtn.setBackground(
              bridge.getMute(currentTrack * 8) ? Color.RED : new Color(0x33, 0x33, 0x33));
          clipBtn.addActionListener(
              e -> {
                boolean isMuted = bridge.getMute(currentTrack * 8);
                for (int i = 0; i < 8; i++) {
                  bridge.setMute(currentTrack * 8 + i, !isMuted);
                }
                clipBtn.setBackground(!isMuted ? Color.RED : new Color(0x33, 0x33, 0x33));
              });
        } else if (c == 17) {
          clipBtn.setText("EDIT");
          clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
          clipBtn.addActionListener(
              e -> {
                if (onEditRequest != null) {
                  onEditRequest.accept(currentTrack, 0);
                }
              });
        } else {
          if (hasClip) {
            clipBtn.setBackground(new Color(0x00, 0xff, 0xcc));
          } else {
            clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
          }

          if (viewMode == GridViewMode.CLIP) {
            clipBtn.addMouseListener(
                new java.awt.event.MouseAdapter() {
                  @Override
                  public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                      JDialog dialog =
                          new JDialog(
                              (Frame)
                                  javax.swing.SwingUtilities.getWindowAncestor(
                                      SwingSongModePanel.this),
                              "Step Properties",
                              true);
                      dialog.setSize(1600, 450);
                      dialog.setLocationRelativeTo(SwingSongModePanel.this);
                      dialog.setLayout(new GridBagLayout());

                      GridBagConstraints gc = new GridBagConstraints();
                      gc.fill = GridBagConstraints.HORIZONTAL;
                      gc.insets = new Insets(10, 15, 10, 15);

                      Font labelFont = new Font("SansSerif", Font.BOLD, 18);
                      Dimension sliderDim = new Dimension(1200, 50);
                      Dimension spinDim = new Dimension(80, 40);

                      gc.gridx = 0;
                      gc.gridy = 0;
                      JLabel l1 = new JLabel("Velocity:");
                      l1.setFont(labelFont);
                      dialog.add(l1, gc);
                      gc.gridx = 1;
                      JSlider velSlider = new JSlider(0, 100, 80);
                      velSlider.setPreferredSize(sliderDim);
                      dialog.add(velSlider, gc);
                      gc.gridx = 2;
                      JSpinner velSpin = new JSpinner(new SpinnerNumberModel(80, 0, 100, 1));
                      velSpin.setPreferredSize(spinDim);
                      dialog.add(velSpin, gc);

                      dialog.setVisible(true);
                    }
                  }
                });
          }

          clipBtn.addActionListener(
              e -> {
                if (viewMode == GridViewMode.SONG) {
                  boolean isActive = clipBtn.getBackground().equals(new Color(0x00, 0xff, 0xcc));
                  if (isActive) {
                    clipBtn.setBackground(new Color(0x33, 0x33, 0x33));
                    for (int i = 0; i < 8; i++) {
                      bridge.setMute(currentTrack * 8 + i, true);
                    }
                  } else {
                    clipBtn.setBackground(Color.ORANGE);

                    Timer timer = new Timer(100, null);
                    final boolean[] flashState = {false};
                    timer.addActionListener(
                        ev -> {
                          int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
                          if (step == 0) {
                            clipBtn.setBackground(new Color(0x00, 0xff, 0xcc)); // Playing
                            for (int i = 0; i < 8; i++) {
                              bridge.setMute(currentTrack * 8 + i, false);
                            }
                            timer.stop();
                          } else {
                            flashState[0] = !flashState[0];
                            clipBtn.setBackground(flashState[0] ? Color.ORANGE : Color.LIGHT_GRAY);
                          }
                        });
                    timer.start();
                  }

                } else {
                  // Toggle Step sequence on/off
                  boolean stepState = bridge.getStep(currentTrack * 8, slot);
                  bridge.setStep(currentTrack * 8, slot, !stepState);
                  clipBtn.setBackground(
                      !stepState ? new Color(0x00, 0xff, 0xcc) : new Color(0x33, 0x33, 0x33));
                }
              });
        }

        if (c == 16) {
          rowPanel.add(Box.createHorizontalStrut(20)); // Separator
        }
        rowPanel.add(clipBtn);
        rowPanel.add(Box.createHorizontalStrut(5));
      }

      add(rowPanel);
    }

    if (viewMode == GridViewMode.CLIP) {
      JPanel pianoRoll = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
      pianoRoll.setBackground(new Color(0x1a, 0x1a, 0x1a));
      pianoRoll.add(Box.createHorizontalStrut(160)); // Align with label offset
      for (int k = 0; k < 28; k++) {
        JButton key = new JButton();
        key.setPreferredSize(new Dimension(60, 100));
        key.setBackground(Color.WHITE);
        pianoRoll.add(key);
      }
      add(Box.createVerticalStrut(10));
      add(pianoRoll);
    }

    revalidate();
    repaint();

    Timer playheadTimer =
        new Timer(
            100,
            e -> {
              int currentStep = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
              if (currentStep >= 0) {
                int activeCol = (currentStep % 16) / 2;
                for (int t = 0; t < 8; t++) {
                  for (int c = 0; c < 8; c++) {
                    if (pads[t][c] != null) {
                      if (c == activeCol) {
                        pads[t][c].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 4));
                      } else {
                        pads[t][c].setBorder(UIManager.getBorder("Button.border"));
                      }
                    }
                  }
                }
              }
            });
    playheadTimer.start();
  }
}
