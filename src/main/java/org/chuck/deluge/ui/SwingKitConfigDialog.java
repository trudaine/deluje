package org.chuck.deluge.ui;

import java.awt.*;
import java.util.List;
import javax.swing.*;
import org.chuck.core.ChuckArray;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.Drum;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.SoundDrum;

/** Swing dialog for editing all sounds in a Kit track (ADSR, Mute Group, Reverse, Pitch). */
public class SwingKitConfigDialog extends JDialog {

  public SwingKitConfigDialog(
      Frame owner, KitTrackModel kit, ChuckVM vm, BridgeContract bridge) {
    super(owner, "Kit Config: " + kit.getName(), false);
    setSize(1280, 550);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));

    List<Drum> sounds = kit.getDrums();
    JTabbedPane tabs = new JTabbedPane();
    tabs.setBackground(new Color(0x25, 0x25, 0x25));
    tabs.setForeground(Color.WHITE);

    for (int i = 0; i < sounds.size(); i++) {
      tabs.addTab(sounds.get(i).getName(), buildSoundPanel((SoundDrum) sounds.get(i), i, vm, bridge));
    }

    add(tabs, BorderLayout.CENTER);

    JButton closeBtn = new JButton("Close");
    closeBtn.addActionListener(e -> dispose());
    JPanel south = new JPanel();
    south.setBackground(new Color(0x25, 0x25, 0x25));
    south.add(closeBtn);
    add(south, BorderLayout.SOUTH);
  }

  private JPanel buildSoundPanel(SoundDrum sound, int idx, ChuckVM vm, BridgeContract bridge) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x22, 0x22, 0x22));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 10, 6, 10);
    c.anchor = GridBagConstraints.WEST;
    int row = 0;

    // ── Sample ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(tip(label("Sample:"), "WAV/AIFF/FLAC file loaded for this drum sound"), c);

    JTextField pathField = new JTextField(sound.getSamplePath(), 24);
    pathField.setEditable(false);
    pathField.setBackground(new Color(0x33, 0x33, 0x33));
    pathField.setForeground(Color.LIGHT_GRAY);
    pathField.setToolTipText("Full path to the sample file");

    JButton browseBtn = new JButton("Browse...");
    browseBtn.setBackground(new Color(0x33, 0x44, 0x55));
    browseBtn.setForeground(Color.WHITE);
    browseBtn.setToolTipText("Open file chooser rooted at your Samples library directory");
    browseBtn.addActionListener(e -> {
      java.io.File startDir = new java.io.File(
          org.chuck.deluge.project.PreferencesManager.getSamplesDir());
      String cur = sound.getSamplePath();
      if (cur != null && !cur.isBlank()) {
        java.io.File curFile = new java.io.File(cur);
        if (curFile.getParentFile() != null && curFile.getParentFile().exists())
          startDir = curFile.getParentFile();
      }
      JFileChooser chooser = new JFileChooser(startDir);
      chooser.setPreferredSize(new Dimension(900, 600));
      chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
          "Audio files", "wav", "aif", "aiff", "flac", "WAV", "AIF", "AIFF", "FLAC"));
      chooser.setAcceptAllFileFilterUsed(true);
      if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
        // Normalize to forward slashes — ChucK engine requires them on all platforms
        String path = chooser.getSelectedFile().getAbsolutePath().replace('\\', '/');
        sound.setSamplePath(path);
        pathField.setText(path);
        bridge.setSamplePath(idx, path);
        if (vm != null) {
          try {
            vm.setGlobalString("g_sample_" + idx, path);
            vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
          } catch (Exception ex) {
            System.err.println("[KitConfig] sample load error: " + ex.getMessage());
          }
        }
      }
    });

    JPanel sampleRow = new JPanel(new BorderLayout(6, 0));
    sampleRow.setBackground(new Color(0x22, 0x22, 0x22));
    sampleRow.add(pathField, BorderLayout.CENTER);
    sampleRow.add(browseBtn, BorderLayout.EAST);
    c.gridx = 1; c.gridwidth = 2;
    panel.add(sampleRow, c);
    row++;

    // ── Pitch ──
    row = addSlider(panel, c, row, "Pitch (ST):",
        "Transpose this sound in semitones (−24 to +24)",
        -24, 24, (int) sound.getPitchSemitones(),
        val -> {
          sound.setPitchSemitones(val);
          getKitArray(vm, BridgeContract.G_KIT_PITCH).setFloat(idx, val);
        });

    // ── ADSR ──
    ChuckArray atk = getKitArray(vm, BridgeContract.G_KIT_ATTACK);
    ChuckArray dec = getKitArray(vm, BridgeContract.G_KIT_DECAY);
    ChuckArray sus = getKitArray(vm, BridgeContract.G_KIT_SUSTAIN);
    ChuckArray rel = getKitArray(vm, BridgeContract.G_KIT_RELEASE);

    c.gridx = 0; c.gridy = row; c.gridwidth = 3;
    panel.add(sectionLabel("ADSR Envelope"), c);
    row++;

    row = addSlider(panel, c, row, "Attack (ms):",
        "Time to ramp from silence to full volume after the note triggers (0–2000 ms)",
        0, 2000, (int)(atk.getFloat(idx) * 1000),
        val -> atk.setFloat(idx, val / 1000f));

    row = addSlider(panel, c, row, "Decay (ms):",
        "Time to fall from peak level down to the sustain level (0–5000 ms)",
        0, 5000, (int)(dec.getFloat(idx) * 1000),
        val -> dec.setFloat(idx, val / 1000f));

    row = addSlider(panel, c, row, "Sustain (%):",
        "Volume level held while the note is held, after the decay phase (0–100%)",
        0, 100, (int)(sus.getFloat(idx) * 100),
        val -> sus.setFloat(idx, val / 100f));

    row = addSlider(panel, c, row, "Release (ms):",
        "Time to fade to silence after the note is released (0–5000 ms)",
        0, 5000, (int)(rel.getFloat(idx) * 1000),
        val -> rel.setFloat(idx, val / 1000f));

    // ── Mute Group ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(tip(label("Mute Group:"),
        "Sounds in the same group cut each other off — e.g. open/closed hi-hat"), c);
    c.gridx = 1; c.gridwidth = 2;
    JComboBox<String> muteCombo = new JComboBox<>(new String[]{"None", "1", "2", "3", "4"});
    muteCombo.setSelectedIndex(Math.max(0, Math.min(4, sound.getMuteGroup())));
    muteCombo.setBackground(new Color(0x33, 0x33, 0x33));
    muteCombo.setForeground(Color.WHITE);
    muteCombo.setToolTipText("Sounds in the same group cut each other off");
    muteCombo.addActionListener(e -> {
      int mg = muteCombo.getSelectedIndex();
      sound.setMuteGroup(mg);
      getKitArray(vm, BridgeContract.G_KIT_MUTE_GROUP).setInt(idx, (long) mg);
    });
    panel.add(muteCombo, c);
    row++;

    // ── Reverse ──
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(tip(label("Reverse:"), "Play the sample backwards"), c);
    c.gridx = 1; c.gridwidth = 2;
    JCheckBox reverseBox = new JCheckBox();
    reverseBox.setSelected(sound.isReverse());
    reverseBox.setBackground(new Color(0x22, 0x22, 0x22));
    reverseBox.setToolTipText("Play the sample backwards");
    reverseBox.addActionListener(e -> {
      sound.setReverse(reverseBox.isSelected());
      getKitArray(vm, BridgeContract.G_KIT_REVERSE).setInt(idx, reverseBox.isSelected() ? 1L : 0L);
    });
    panel.add(reverseBox, c);

    return panel;
  }

  private int addSlider(JPanel panel, GridBagConstraints c, int row,
      String labelText, String tooltip, int min, int max, int initial,
      java.util.function.IntConsumer onChange) {
    c.gridx = 0; c.gridy = row; c.gridwidth = 1;
    panel.add(tip(label(labelText), tooltip), c);
    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, initial)));
    slider.setBackground(new Color(0x22, 0x22, 0x22));
    slider.setToolTipText(tooltip);
    JLabel valLabel = new JLabel(String.valueOf(initial));
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(55, 20));
    slider.addChangeListener(e -> {
      onChange.accept(slider.getValue());
      valLabel.setText(String.valueOf(slider.getValue()));
    });
    c.gridx = 1; c.gridwidth = 1;
    panel.add(slider, c);
    c.gridx = 2; c.gridwidth = 1;
    panel.add(valLabel, c);
    return row + 1;
  }

  private static JComponent tip(JComponent comp, String text) {
    comp.setToolTipText(text);
    return comp;
  }

  private static ChuckArray getKitArray(ChuckVM vm, String key) {
    return (ChuckArray) vm.getGlobalObject(key);
  }

  private static JLabel label(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(Color.LIGHT_GRAY);
    return l;
  }

  private static JLabel sectionLabel(String text) {
    JLabel l = new JLabel(text);
    l.setForeground(new Color(0x00, 0xff, 0xcc));
    l.setFont(l.getFont().deriveFont(Font.BOLD));
    return l;
  }
}
