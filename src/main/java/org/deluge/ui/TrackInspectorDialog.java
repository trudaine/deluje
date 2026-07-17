package org.deluge.ui;

import java.awt.*;
import javax.swing.*;

/** Modal dialog with PRESETS / CLIPBOARD / MIXER / FM OPERATORS tabs for track inspection. */
public class TrackInspectorDialog extends JDialog {

  private final JComboBox<String> cb;
  private final JSlider volumeSlider;
  private final JSlider panSlider;
  private final JSlider ratioSlider;

  public TrackInspectorDialog(
      Frame owner,
      int trackIndex,
      java.util.List<org.deluge.model.TrackModel> tracks,
      Runnable onRefresh) {
    super(owner, "Track Inspector", true);
    setSize(900, 550);
    setLocationRelativeTo(owner);

    JTabbedPane tabs = new JTabbedPane();
    tabs.setFont(new Font("SansSerif", Font.BOLD, 22));

    // Tab 1: Presets
    JPanel p1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
    p1.setBackground(new Color(0x2b, 0x2b, 0x2b));
    JLabel lP = new JLabel("Hot-Swap Patch Preset:");
    lP.setFont(new Font("SansSerif", Font.BOLD, 18));
    lP.setForeground(Color.WHITE);
    cb = new JComboBox<>();
    cb.setFont(new Font("SansSerif", Font.PLAIN, 18));
    cb.setPreferredSize(new Dimension(400, 45));
    cb.setToolTipText("Select synth or kit patch preset to hot-swap track sound");

    org.deluge.model.TrackModel currentTrack =
        (trackIndex < tracks.size()) ? tracks.get(trackIndex) : null;
    org.deluge.model.ClipModel firstClip =
        (currentTrack != null && !currentTrack.getClips().isEmpty())
            ? currentTrack.getClips().get(0)
            : null;
    boolean isSynth = currentTrack instanceof org.deluge.model.SynthTrackModel;
    boolean isKit = currentTrack instanceof org.deluge.model.KitTrackModel;
    java.io.File dir =
        isSynth
            ? org.deluge.project.PreferencesManager.getSynthsDir()
            : org.deluge.project.PreferencesManager.getKitsDir();

    cb.addItem("<Select Preset>");
    cb.setEnabled(false);
    Thread.ofVirtual()
        .start(
            () -> {
              if (dir != null && dir.exists() && dir.isDirectory()) {
                java.io.File[] files =
                    dir.listFiles((d, name) -> name.toLowerCase().endsWith(".xml"));
                if (files != null) {
                  java.util.List<String> presetNames = new java.util.ArrayList<>();
                  for (java.io.File f : files) {
                    presetNames.add(f.getName().substring(0, f.getName().length() - 4));
                  }
                  javax.swing.SwingUtilities.invokeLater(
                      () -> {
                        for (String presetName : presetNames) {
                          cb.addItem(presetName);
                        }
                        cb.setEnabled(true);
                      });
                } else {
                  javax.swing.SwingUtilities.invokeLater(() -> cb.setEnabled(true));
                }
              } else {
                javax.swing.SwingUtilities.invokeLater(() -> cb.setEnabled(true));
              }
            });

    p1.add(lP);
    p1.add(cb);
    tabs.addTab("PRESETS", p1);

    // Tab 2: Clipboard
    JPanel p2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 40, 50));
    p2.setBackground(new Color(0x2b, 0x2b, 0x2b));
    JButton cloneBtn = new JButton("Clone Clip Variant");
    cloneBtn.setFont(new Font("SansSerif", Font.BOLD, 24));
    cloneBtn.setPreferredSize(new Dimension(300, 80));
    cloneBtn.setToolTipText("Create duplicate pattern clip variant in next free slot");
    JButton clearBtn = new JButton("Export MIDI Sequence");
    clearBtn.setFont(new Font("SansSerif", Font.BOLD, 24));
    clearBtn.setPreferredSize(new Dimension(300, 80));
    clearBtn.setToolTipText("Export active sequence steps to standard MIDI file");
    p2.add(cloneBtn);
    p2.add(clearBtn);
    tabs.addTab("CLIPBOARD", p2);

    // Tab 3 + 4: Mixer (split across two tab placements)
    JPanel p3 = new JPanel(new GridBagLayout());
    p3.setBackground(new Color(0x2b, 0x2b, 0x2b));
    GridBagConstraints gcm = new GridBagConstraints();
    gcm.fill = GridBagConstraints.HORIZONTAL;
    gcm.insets = new Insets(25, 25, 25, 25);

    gcm.gridx = 0;
    gcm.gridy = 0;
    JLabel vL = new JLabel("Channel Volume:");
    vL.setFont(new Font("SansSerif", Font.BOLD, 20));
    vL.setForeground(Color.WHITE);
    p3.add(vL, gcm);
    gcm.gridx = 1;
    volumeSlider = new JSlider(0, 100, 80);
    volumeSlider.setPreferredSize(new Dimension(400, 50));
    DarkSliderUI.styleSlider(volumeSlider, new Color(0xff, 0xb3, 0x00));
    volumeSlider.setToolTipText("Adjust master channel volume level (0-100%)");
    volumeSlider.addChangeListener(
        ev -> System.out.println("Track " + trackIndex + " Vol: " + volumeSlider.getValue()));
    p3.add(volumeSlider, gcm);
    tabs.addTab("MIXER", p3);

    gcm.gridx = 0;
    gcm.gridy = 1;
    JLabel pL = new JLabel("Channel Panning:");
    pL.setFont(new Font("SansSerif", Font.BOLD, 20));
    pL.setForeground(Color.WHITE);
    p3.add(pL, gcm);
    gcm.gridx = 1;
    panSlider = new JSlider(0, 100, 50);
    panSlider.setPreferredSize(new Dimension(400, 50));
    DarkSliderUI.styleSlider(panSlider, new Color(0x00, 0xff, 0xcc));
    panSlider.setToolTipText("Adjust stereo pan position (Left-Right)");
    p3.add(panSlider, gcm);
    tabs.addTab("MIXER", p3);

    // Tab 4: FM Operators
    JPanel p4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
    p4.setBackground(new Color(0x2b, 0x2b, 0x2b));
    JLabel lAlgo = new JLabel("Algorithm Map: [Op 4] ➔ [Op 3] ➔ [Op 2] ➔ Output");
    lAlgo.setFont(new Font("SansSerif", Font.BOLD, 20));
    lAlgo.setForeground(Color.ORANGE);
    JLabel lRatio = new JLabel("Modulator Ratio (Harmonics):");
    lRatio.setFont(new Font("SansSerif", Font.BOLD, 18));
    lRatio.setForeground(Color.WHITE);
    ratioSlider = new JSlider(1, 10, 1);
    ratioSlider.setPreferredSize(new Dimension(300, 50));
    DarkSliderUI.styleSlider(ratioSlider, new Color(0xff, 0x99, 0x33));
    ratioSlider.setToolTipText("Adjust FM modulator carrier harmonic ratio");
    p4.add(lAlgo);
    p4.add(lRatio);
    p4.add(ratioSlider);
    tabs.addTab("FM OPERATORS", p4);

    // Tab 5: Grid Shortcuts
    JPanel p5 = new JPanel(new GridBagLayout());
    p5.setBackground(new Color(0x2b, 0x2b, 0x2b));
    GridBagConstraints gcS = new GridBagConstraints();
    gcS.fill = GridBagConstraints.HORIZONTAL;
    gcS.insets = new Insets(20, 20, 20, 20);

    gcS.gridx = 0;
    gcS.gridy = 0;
    JLabel lLeft = new JLabel("Left Column Action:");
    lLeft.setFont(new Font("SansSerif", Font.BOLD, 18));
    lLeft.setForeground(Color.WHITE);
    p5.add(lLeft, gcS);

    gcS.gridx = 1;
    String[] colActions = {"VELOCITY", "MOD", "PITCH", "NONE"};
    JComboBox<String> leftColCombo = new JComboBox<>(colActions);
    leftColCombo.setFont(new Font("SansSerif", Font.PLAIN, 18));
    leftColCombo.setPreferredSize(new Dimension(250, 40));
    if (firstClip != null) {
      leftColCombo.setSelectedItem(firstClip.getLeftCol());
    }
    leftColCombo.addActionListener(
        ev -> {
          if (firstClip != null) {
            firstClip.setLeftCol((String) leftColCombo.getSelectedItem());
          }
        });
    p5.add(leftColCombo, gcS);

    gcS.gridx = 0;
    gcS.gridy = 1;
    JLabel lRight = new JLabel("Right Column Action:");
    lRight.setFont(new Font("SansSerif", Font.BOLD, 18));
    lRight.setForeground(Color.WHITE);
    p5.add(lRight, gcS);

    gcS.gridx = 1;
    JComboBox<String> rightColCombo = new JComboBox<>(colActions);
    rightColCombo.setFont(new Font("SansSerif", Font.PLAIN, 18));
    rightColCombo.setPreferredSize(new Dimension(250, 40));
    if (firstClip != null) {
      rightColCombo.setSelectedItem(firstClip.getRightCol());
    }
    rightColCombo.addActionListener(
        ev -> {
          if (firstClip != null) {
            firstClip.setRightCol((String) rightColCombo.getSelectedItem());
          }
        });
    p5.add(rightColCombo, gcS);

    tabs.addTab("GRID SHORTCUTS", p5);
    cloneBtn.addActionListener(
        ev -> {
          if (trackIndex < tracks.size()) {
            org.deluge.model.TrackModel tModel = tracks.get(trackIndex);
            if (!tModel.getClips().isEmpty()) {
              tModel.addClip(tModel.getClips().get(0));
            }
          }
          dispose();
          onRefresh.run();
        });

    cb.addActionListener(
        ev -> {
          String selected = (String) cb.getSelectedItem();
          if (selected == null || selected.equals("<Select Preset>")) return;
          cb.setEnabled(false);
          Thread.ofVirtual()
              .start(
                  () -> {
                    try {
                      java.io.File file = new java.io.File(dir, selected + ".xml");
                      if (file.exists() && trackIndex < tracks.size()) {
                        org.deluge.model.TrackModel oldTrack = tracks.get(trackIndex);
                        final org.deluge.model.TrackModel newTrack;
                        if (isSynth) {
                          newTrack = org.deluge.xml.DelugeXmlParser.parseSynth(file);
                        } else if (isKit) {
                          newTrack = org.deluge.xml.DelugeXmlParser.parseKit(file);
                        } else {
                          newTrack = null;
                        }
                        if (newTrack != null) {
                          newTrack.getClips().clear();
                          for (org.deluge.model.ClipModel cm : oldTrack.getClips()) {
                            newTrack.addClip(cm);
                          }
                          newTrack.setColourHex(oldTrack.getColourHex());
                          javax.swing.SwingUtilities.invokeLater(
                              () -> {
                                tracks.set(trackIndex, newTrack);
                                dispose();
                                onRefresh.run();
                              });
                        } else {
                          javax.swing.SwingUtilities.invokeLater(() -> cb.setEnabled(true));
                        }
                      } else {
                        javax.swing.SwingUtilities.invokeLater(
                            () -> {
                              dispose();
                              onRefresh.run();
                            });
                      }
                    } catch (Exception ex) {
                      javax.swing.SwingUtilities.invokeLater(
                          () -> {
                            cb.setEnabled(true);
                            JOptionPane.showMessageDialog(
                                this,
                                "Failed to load preset:\n" + ex.getMessage(),
                                "Error",
                                JOptionPane.ERROR_MESSAGE);
                          });
                    }
                  });
        });

    add(tabs);
    DarkComboBoxRenderer.styleComponentTree(this);
  }

  public String getSelectedPreset() {
    return (String) cb.getSelectedItem();
  }

  public int getVolume() {
    return volumeSlider.getValue();
  }

  public int getPan() {
    return panSlider.getValue();
  }

  public int getRatio() {
    return ratioSlider.getValue();
  }
}
