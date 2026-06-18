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
    boolean isSynth = currentTrack instanceof org.deluge.model.SynthTrackModel;
    boolean isKit = currentTrack instanceof org.deluge.model.KitTrackModel;
    java.io.File dir =
        isSynth
            ? org.deluge.project.PreferencesManager.getSynthsDir()
            : org.deluge.project.PreferencesManager.getKitsDir();

    cb.addItem("<Select Preset>");
    if (dir != null && dir.exists() && dir.isDirectory()) {
      java.io.File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".xml"));
      if (files != null) {
        for (java.io.File f : files) {
          String presetName = f.getName().substring(0, f.getName().length() - 4);
          cb.addItem(presetName);
        }
      }
    }

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
    ratioSlider.setToolTipText("Adjust FM modulator carrier harmonic ratio");
    p4.add(lAlgo);
    p4.add(lRatio);
    p4.add(ratioSlider);
    tabs.addTab("FM OPERATORS", p4);

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
          try {
            java.io.File file = new java.io.File(dir, selected + ".xml");
            if (file.exists() && trackIndex < tracks.size()) {
              org.deluge.model.TrackModel oldTrack = tracks.get(trackIndex);
              org.deluge.model.TrackModel newTrack = null;
              if (isSynth) {
                newTrack = org.deluge.xml.DelugeXmlParser.parseSynth(file);
              } else if (isKit) {
                newTrack = org.deluge.xml.DelugeXmlParser.parseKit(file);
              }
              if (newTrack != null) {
                newTrack.getClips().clear();
                for (org.deluge.model.ClipModel cm : oldTrack.getClips()) {
                  newTrack.addClip(cm);
                }
                newTrack.setColourHex(oldTrack.getColourHex());
                tracks.set(trackIndex, newTrack);
              }
            }
          } catch (Exception ex) {
            JOptionPane.showMessageDialog(
                this,
                "Failed to load preset:\n" + ex.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE);
          }
          dispose();
          onRefresh.run();
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
