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
    setSize(760, 420);
    setLocationRelativeTo(owner);

    getContentPane().setBackground(new Color(0x1d, 0x1d, 0x20));
    JTabbedPane tabs = new JTabbedPane();
    tabs.setFont(new Font("SansSerif", Font.BOLD, 12));
    tabs.setBackground(new Color(0x1d, 0x1d, 0x20));
    tabs.setForeground(Color.LIGHT_GRAY);
    // Keep the selected tab readable regardless of how the look-and-feel paints its pill
    // (light or dark): the accent color reads on both.
    tabs.addChangeListener(
        e -> {
          for (int i = 0; i < tabs.getTabCount(); i++) {
            tabs.setForegroundAt(
                i, i == tabs.getSelectedIndex() ? new Color(0x00, 0xcc, 0xa4) : Color.LIGHT_GRAY);
          }
        });

    // Tab 1: Presets
    JPanel p1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
    p1.setBackground(new Color(0x2b, 0x2b, 0x2b));
    JLabel lP = new JLabel("Hot-Swap Patch Preset:");
    lP.setFont(new Font("SansSerif", Font.BOLD, 13));
    lP.setForeground(Color.WHITE);
    cb = new JComboBox<>();
    cb.setFont(new Font("SansSerif", Font.PLAIN, 12));
    cb.setPreferredSize(new Dimension(340, 30));
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
    styleActionButton(cloneBtn, new Color(0x00, 0xff, 0xcc));
    cloneBtn.setToolTipText("Create duplicate pattern clip variant in next free slot");
    JButton clearBtn = new JButton("Export MIDI Sequence");
    styleActionButton(clearBtn, new Color(0xff, 0xb3, 0x00));
    clearBtn.setToolTipText("Export active sequence steps to standard MIDI file");
    p2.add(cloneBtn);
    p2.add(clearBtn);
    tabs.addTab("CLIPBOARD", p2);

    // Tab 3 + 4: Mixer (split across two tab placements)
    JPanel p3 = new JPanel(new GridBagLayout());
    p3.setBackground(new Color(0x2b, 0x2b, 0x2b));
    GridBagConstraints gcm = new GridBagConstraints();
    gcm.fill = GridBagConstraints.HORIZONTAL;
    gcm.insets = new Insets(14, 18, 14, 18);

    gcm.gridx = 0;
    gcm.gridy = 0;
    JLabel vL = new JLabel("Channel Volume:");
    vL.setFont(new Font("SansSerif", Font.BOLD, 13));
    vL.setForeground(Color.WHITE);
    p3.add(vL, gcm);
    gcm.gridx = 1;
    org.deluge.model.SynthTrackModel synthTrack =
        (currentTrack instanceof org.deluge.model.SynthTrackModel st) ? st : null;
    volumeSlider =
        new JSlider(0, 100, synthTrack != null ? (int) (synthTrack.getVolume() * 100) : 80);
    volumeSlider.setPreferredSize(new Dimension(340, 36));
    DarkSliderUI.styleSlider(volumeSlider, new Color(0xff, 0xb3, 0x00));
    volumeSlider.setToolTipText("Adjust master channel volume level (0-100%)");
    volumeSlider.setEnabled(synthTrack != null);
    volumeSlider.addChangeListener(
        ev -> {
          if (synthTrack != null) {
            synthTrack.setVolume(volumeSlider.getValue() / 100f);
            pushLiveSound(synthTrack, trackIndex);
          }
        });
    p3.add(volumeSlider, gcm);

    gcm.gridx = 0;
    gcm.gridy = 1;
    JLabel pL = new JLabel("Channel Panning:");
    pL.setFont(new Font("SansSerif", Font.BOLD, 13));
    pL.setForeground(Color.WHITE);
    p3.add(pL, gcm);
    gcm.gridx = 1;
    panSlider =
        new JSlider(0, 100, synthTrack != null ? (int) ((synthTrack.getPan() + 1f) * 50) : 50);
    panSlider.setPreferredSize(new Dimension(340, 36));
    DarkSliderUI.styleSlider(panSlider, new Color(0x00, 0xff, 0xcc));
    panSlider.setToolTipText("Adjust stereo pan position (Left-Right)");
    panSlider.setEnabled(synthTrack != null);
    panSlider.addChangeListener(
        ev -> {
          if (synthTrack != null) {
            synthTrack.setPan(panSlider.getValue() / 50f - 1f);
            pushLiveSound(synthTrack, trackIndex);
          }
        });
    p3.add(panSlider, gcm);
    // NOTE: this tab used to be added TWICE with the same panel — Swing re-parents the panel to
    // the second tab, so the tab bar showed two MIXER entries with the first one empty.
    tabs.addTab("MIXER", p3);

    // Tab 4: FM Operators
    JPanel p4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
    p4.setBackground(new Color(0x2b, 0x2b, 0x2b));
    JLabel lAlgo = new JLabel("FM Routing: Osc 2 (modulator) ➔ Osc 1 (carrier) ➔ Output");
    lAlgo.setFont(new Font("SansSerif", Font.BOLD, 13));
    lAlgo.setForeground(Color.ORANGE);
    JLabel lRatio = new JLabel("Modulator Ratio (Harmonics):");
    lRatio.setFont(new Font("SansSerif", Font.BOLD, 13));
    lRatio.setForeground(Color.WHITE);
    ratioSlider =
        new JSlider(25, 400, synthTrack != null ? (int) (synthTrack.getFmRatio() * 100) : 100);
    ratioSlider.setPreferredSize(new Dimension(300, 36));
    DarkSliderUI.styleSlider(ratioSlider, new Color(0xff, 0x99, 0x33));
    ratioSlider.setToolTipText("FM modulator/carrier frequency ratio (0.25–4.00)");
    ratioSlider.setEnabled(synthTrack != null);
    ratioSlider.addChangeListener(
        ev -> {
          if (synthTrack != null) {
            synthTrack.setFmRatio(ratioSlider.getValue() / 100f);
            pushLiveSound(synthTrack, trackIndex);
          }
        });
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
    lLeft.setFont(new Font("SansSerif", Font.BOLD, 13));
    lLeft.setForeground(Color.WHITE);
    p5.add(lLeft, gcS);

    gcS.gridx = 1;
    String[] colActions = {"VELOCITY", "MOD", "PITCH", "NONE"};
    JComboBox<String> leftColCombo = new JComboBox<>(colActions);
    leftColCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
    leftColCombo.setPreferredSize(new Dimension(220, 30));
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
    lRight.setFont(new Font("SansSerif", Font.BOLD, 13));
    lRight.setForeground(Color.WHITE);
    p5.add(lRight, gcS);

    gcS.gridx = 1;
    JComboBox<String> rightColCombo = new JComboBox<>(colActions);
    rightColCombo.setFont(new Font("SansSerif", Font.PLAIN, 12));
    rightColCombo.setPreferredSize(new Dimension(220, 30));
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
              // Deep copy — adding the same ClipModel reference made "clone" edits mutate the
              // original clip too.
              org.deluge.model.ClipModel src = tModel.getClips().get(0);
              tModel.addClip(src.deepCopy(src.getName() + " (variant)"));
            }
          }
          dispose();
          onRefresh.run();
        });

    clearBtn.addActionListener(
        ev -> {
          if (SwingDelugeApp.mainInstance != null
              && SwingDelugeApp.mainInstance.getClipPanel() != null) {
            SwingDelugeApp.mainInstance.getClipPanel().convertTrackToMidi(trackIndex);
          }
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
    // Apply the selection foreground once for the initially-selected tab (the change listener
    // only fires on selection changes).
    for (int i = 0; i < tabs.getTabCount(); i++) {
      tabs.setForegroundAt(
          i, i == tabs.getSelectedIndex() ? new Color(0x00, 0xcc, 0xa4) : Color.LIGHT_GRAY);
    }
    DarkComboBoxRenderer.styleComponentTree(this);
  }

  /** Push edited model params into the live firmware sound (same path SynthParamRack uses). */
  private static void pushLiveSound(org.deluge.model.SynthTrackModel track, int trackIndex) {
    if (SwingDelugeApp.mainInstance == null) return;
    try {
      Object eng =
          SwingDelugeApp.mainInstance.bridge.getGlobalObject(
              org.deluge.BridgeContract.G_FIRMWARE_ENGINE);
      if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine
          && trackIndex >= 0
          && trackIndex < engine.sounds.size()
          && engine.sounds.get(trackIndex) instanceof org.deluge.engine.FirmwareSound fs) {
        org.deluge.engine.FirmwareFactory.applyModelToLiveSound(track, fs);
      }
    } catch (Exception ignored) {
      // engine not running (e.g. tests) — the model edit still stands
    }
  }

  /** House-style action button: dark face, accent text, compact size. */
  private static void styleActionButton(JButton btn, Color accent) {
    btn.setFont(new Font("SansSerif", Font.BOLD, 13));
    btn.setPreferredSize(new Dimension(220, 44));
    btn.setBackground(new Color(0x2d, 0x2d, 0x32));
    btn.setForeground(accent);
    btn.setFocusPainted(false);
    btn.setOpaque(true);
    btn.setBorder(BorderFactory.createLineBorder(accent.darker(), 1));
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
