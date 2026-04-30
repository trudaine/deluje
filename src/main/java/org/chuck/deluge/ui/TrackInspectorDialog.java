package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;

/** Modal dialog with PRESETS / CLIPBOARD / MIXER / FM OPERATORS tabs for track inspection. */
public class TrackInspectorDialog extends JDialog {

  private final JComboBox<String> cb;
  private final JSlider volumeSlider;
  private final JSlider panSlider;
  private final JSlider ratioSlider;

  public TrackInspectorDialog(
      Frame owner, int trackIndex, java.util.List<org.chuck.deluge.model.TrackModel> tracks, Runnable onRefresh) {
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
    p1.add(lP);
    p1.add(cb);
    tabs.addTab("PRESETS", p1);

    // Tab 2: Clipboard
    JPanel p2 = new JPanel(new FlowLayout(FlowLayout.CENTER, 40, 50));
    p2.setBackground(new Color(0x2b, 0x2b, 0x2b));
    JButton cloneBtn = new JButton("Clone Clip Variant");
    cloneBtn.setFont(new Font("SansSerif", Font.BOLD, 24));
    cloneBtn.setPreferredSize(new Dimension(300, 80));
    JButton clearBtn = new JButton("Export MIDI Sequence");
    clearBtn.setFont(new Font("SansSerif", Font.BOLD, 24));
    clearBtn.setPreferredSize(new Dimension(300, 80));
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
    volumeSlider.addChangeListener(ev -> System.out.println("Track " + trackIndex + " Vol: " + volumeSlider.getValue()));
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
    p3.add(panSlider, gcm);
    tabs.addTab("MIXER", p3);

    // Tab 4: FM Operators
    JPanel p4 = new JPanel(new FlowLayout(FlowLayout.LEFT, 30, 30));
    p4.setBackground(new Color(0x2b, 0x2b, 0x2b));
    JLabel lAlgo = new JLabel("Algorithm Map: [Op 4] \u279e [Op 3] \u279e [Op 2] \u279e Output");
    lAlgo.setFont(new Font("SansSerif", Font.BOLD, 20));
    lAlgo.setForeground(Color.ORANGE);
    JLabel lRatio = new JLabel("Modulator Ratio (Harmonics):");
    lRatio.setFont(new Font("SansSerif", Font.BOLD, 18));
    lRatio.setForeground(Color.WHITE);
    ratioSlider = new JSlider(1, 10, 1);
    ratioSlider.setPreferredSize(new Dimension(300, 50));
    p4.add(lAlgo);
    p4.add(lRatio);
    p4.add(ratioSlider);
    tabs.addTab("FM OPERATORS", p4);

    cloneBtn.addActionListener(ev -> {
      if (trackIndex < tracks.size()) {
        org.chuck.deluge.model.TrackModel tModel = tracks.get(trackIndex);
        if (!tModel.getClips().isEmpty()) {
          tModel.addClip(tModel.getClips().get(0));
        }
      }
      dispose();
      onRefresh.run();
    });

    cb.addActionListener(ev -> {
      if (trackIndex < tracks.size()) {
        tracks.get(trackIndex).setName((String) cb.getSelectedItem());
      }
      dispose();
      onRefresh.run();
    });

    add(tabs);
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
