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

/**
 * A beautiful, 2-column wide-screen optimized layout for Drum Kit voice and FX parameters,
 * preventing vertical scrolling and providing an integrated quick help status guide.
 */
public class SwingKitConfigDialog extends JDialog {

  private JLabel helpLabel;
  private final String DEFAULT_HELP_TEXT =
      "<html>💡 <b>QUICK HELP:</b> Hover over any drum sample control or FX slider to see its details and hardware mappings here!</html>";

  public SwingKitConfigDialog(Frame owner, KitTrackModel kit, ChuckVM vm, BridgeContract bridge) {
    super(owner, "Kit Sound Editor: " + kit.getName(), false);
    setSize(1280, 800);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));

    List<Drum> sounds = kit.getDrums();
    JTabbedPane tabs = new JTabbedPane();
    tabs.setBackground(new Color(0x25, 0x25, 0x25));
    tabs.setForeground(Color.WHITE);

    for (int i = 0; i < sounds.size(); i++) {
      tabs.addTab(
          sounds.get(i).getName(), buildSoundPanel((SoundDrum) sounds.get(i), i, vm, bridge));
    }

    add(tabs, BorderLayout.CENTER);

    // ── Composite South Panel Stack (Help Bar + Close button) ──
    JPanel southStack = new JPanel();
    southStack.setLayout(new BoxLayout(southStack, BoxLayout.Y_AXIS));

    JPanel helpBarPanel = new JPanel(new BorderLayout());
    helpBarPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    helpBarPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(6, 16, 6, 16)));
    helpBarPanel.setPreferredSize(new Dimension(1200, 48));
    helpBarPanel.setMinimumSize(new Dimension(100, 48));
    helpBarPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 48));

    helpLabel = new JLabel(DEFAULT_HELP_TEXT);
    helpLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    helpLabel.setForeground(Color.LIGHT_GRAY);
    helpLabel.setVerticalAlignment(SwingConstants.TOP);
    helpBarPanel.add(helpLabel, BorderLayout.CENTER);
    southStack.add(helpBarPanel);

    JButton closeBtn = new JButton("Close");
    closeBtn.addActionListener(e -> dispose());
    JPanel south = new JPanel();
    south.setBackground(new Color(0x25, 0x25, 0x25));
    south.add(closeBtn);
    southStack.add(south);

    add(southStack, BorderLayout.SOUTH);

    DarkComboBoxRenderer.styleComponentTree(this);
  }

  public void attachHoverHelp(JComponent comp, String helpText) {
    if (comp == null || helpText == null || helpText.isEmpty()) return;
    comp.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseEntered(java.awt.event.MouseEvent e) {
            if (helpLabel != null) {
              helpLabel.setText("<html>💡 " + helpText + "</html>");
            }
          }

          @Override
          public void mouseExited(java.awt.event.MouseEvent e) {
            if (helpLabel != null) {
              helpLabel.setText(DEFAULT_HELP_TEXT);
            }
          }
        });
  }

  private JPanel buildSoundPanel(SoundDrum sound, int idx, ChuckVM vm, BridgeContract bridge) {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(SwingSynthConfigDialog.BG_CARD);

    // Create left and right sub-panels for parallel 2-column layout
    JPanel leftPanel = new JPanel(new GridBagLayout());
    leftPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints cLeft = new GridBagConstraints();
    cLeft.fill = GridBagConstraints.HORIZONTAL;
    cLeft.insets = new Insets(6, 10, 6, 10);
    cLeft.anchor = GridBagConstraints.WEST;
    int leftRow = 0;

    JPanel rightPanel = new JPanel(new GridBagLayout());
    rightPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    GridBagConstraints cRight = new GridBagConstraints();
    cRight.fill = GridBagConstraints.HORIZONTAL;
    cRight.insets = new Insets(6, 10, 6, 10);
    cRight.anchor = GridBagConstraints.WEST;
    int rightRow = 0;

    // ── Left Column: SAMPLE & UTILITY ──

    // Sample Row
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 1;
    JLabel sampleLabel = label("Sample:");
    String sampleHelp =
        "<b>DRUM SAMPLE:</b> WAV/AIFF/FLAC sample file loaded for this drum sound slot. — <i>Physical Deluge:</i> Press shift + first top encoder in file browser.";
    sampleLabel.setToolTipText(sampleHelp);
    attachHoverHelp(sampleLabel, sampleHelp);
    leftPanel.add(sampleLabel, cLeft);

    JTextField pathField = new JTextField(sound.getSamplePath(), 20);
    pathField.setEditable(false);
    pathField.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    pathField.setForeground(Color.LIGHT_GRAY);
    pathField.setToolTipText(sampleHelp);
    attachHoverHelp(pathField, sampleHelp);

    JButton browseBtn = new JButton("Browse...");
    browseBtn.setBackground(new Color(0x33, 0x44, 0x55));
    browseBtn.setForeground(Color.WHITE);
    browseBtn.setToolTipText("Open file chooser rooted at your Samples library directory");
    browseBtn.addActionListener(
        e -> {
          java.io.File startDir =
              new java.io.File(org.chuck.deluge.project.PreferencesManager.getSamplesDir());
          String cur = sound.getSamplePath();
          if (cur != null && !cur.isBlank()) {
            java.io.File curFile = new java.io.File(cur);
            if (curFile.getParentFile() != null && curFile.getParentFile().exists())
              startDir = curFile.getParentFile();
          }
          JFileChooser chooser = new JFileChooser(startDir);
          chooser.setPreferredSize(new Dimension(900, 600));
          chooser.setFileFilter(
              new javax.swing.filechooser.FileNameExtensionFilter(
                  "Audio files", "wav", "aif", "aiff", "flac", "WAV", "AIF", "AIFF", "FLAC"));
          chooser.setAcceptAllFileFilterUsed(true);
          if (chooser.showOpenDialog(panel) == JFileChooser.APPROVE_OPTION) {
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
    sampleRow.setBackground(SwingSynthConfigDialog.BG_CARD);
    sampleRow.add(pathField, BorderLayout.CENTER);
    sampleRow.add(browseBtn, BorderLayout.EAST);
    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    leftPanel.add(sampleRow, cLeft);
    leftRow++;

    // Pitch
    String pitchHelp =
        "<b>DRUM PITCH:</b> Transpose this drum sound sample pitch in semitones (−24 to +24 semitones). — <i>Physical Deluge:</i> Turn SELECT/transpose dial.";
    leftRow =
        addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Pitch (ST):",
            pitchHelp,
            -24,
            24,
            (int) sound.getPitchSemitones(),
            val -> {
              sound.setPitchSemitones(val);
              getKitArray(vm, BridgeContract.G_KIT_PITCH).setFloat(idx, val);
            });

    // LPF Header
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 3;
    leftPanel.add(sectionLabel("LOW-PASS FILTER"), cLeft);
    leftRow++;

    String morphHelp =
        "<b>LPF MORPH:</b> State-variable filter morph: 0% = fully low-pass, 100% = fully high-pass. — <i>Physical Deluge:</i> Turn LPF CUTOFF dynamic shortcut knob.";
    leftRow =
        addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Morph (0-50):",
            morphHelp,
            0,
            50,
            (int) (sound.getLpfMorph() * 50),
            val -> {
              float morph = val / 50.0f;
              sound.setLpfMorph(morph);
              getKitArray(vm, BridgeContract.G_KIT_LPF_MORPH).setFloat(idx, morph);
            });

    // ADSR Header
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 3;
    leftPanel.add(sectionLabel("ADSR ENVELOPE"), cLeft);
    leftRow++;

    ChuckArray atk = getKitArray(vm, BridgeContract.G_KIT_ATTACK);
    ChuckArray dec = getKitArray(vm, BridgeContract.G_KIT_DECAY);
    ChuckArray sus = getKitArray(vm, BridgeContract.G_KIT_SUSTAIN);
    ChuckArray rel = getKitArray(vm, BridgeContract.G_KIT_RELEASE);

    String atkHelp =
        "<b>ENV ATTACK:</b> Ramps up the volume envelope speed from silence to peak after a note is triggered (0 to 2000 ms). — <i>Physical Deluge:</i> Hold shift + turn ATTACK gold shortcut dial.";
    leftRow =
        addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Attack (ms):",
            atkHelp,
            0,
            2000,
            (int) (atk.getFloat(idx) * 1000),
            val -> atk.setFloat(idx, val / 1000f));

    String decHelp =
        "<b>ENV DECAY:</b> Time to decay from peak down to the sustain level (0 to 5000 ms). — <i>Physical Deluge:</i> Hold shift + turn DECAY gold shortcut dial.";
    leftRow =
        addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Decay (ms):",
            decHelp,
            0,
            5000,
            (int) (dec.getFloat(idx) * 1000),
            val -> dec.setFloat(idx, val / 1000f));

    String susHelp =
        "<b>ENV SUSTAIN:</b> Level of volume held while the note remains pressed (0% to 100%). — <i>Physical Deluge:</i> Hold shift + turn SUSTAIN gold shortcut dial.";
    leftRow =
        addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Sustain (%):",
            susHelp,
            0,
            100,
            (int) (sus.getFloat(idx) * 100),
            val -> sus.setFloat(idx, val / 100f));

    String relHelp =
        "<b>ENV RELEASE:</b> Time to fade the volume down to absolute silence after the note is released (0 to 5000 ms). — <i>Physical Deluge:</i> Hold shift + turn RELEASE gold shortcut dial.";
    leftRow =
        addSlider(
            leftPanel,
            cLeft,
            leftRow,
            "Release (ms):",
            relHelp,
            0,
            5000,
            (int) (rel.getFloat(idx) * 1000),
            val -> rel.setFloat(idx, val / 1000f));

    // UTILITY Header
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 3;
    leftPanel.add(sectionLabel("UTILITY & GROUPS"), cLeft);
    leftRow++;

    // Mute Group
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 1;
    JLabel muteLabel = label("Mute Group:");
    String muteHelp =
        "<b>MUTE GROUP:</b> Group drum sounds together so they cut each other off (e.g. open hi-hat muting closed hi-hat). — <i>Physical Deluge:</i> Set standard voice priority groups.";
    muteLabel.setToolTipText(muteHelp);
    attachHoverHelp(muteLabel, muteHelp);
    leftPanel.add(muteLabel, cLeft);

    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    JComboBox<String> muteCombo = new JComboBox<>(new String[] {"None", "1", "2", "3", "4"});
    muteCombo.setSelectedIndex(Math.max(0, Math.min(4, sound.getMuteGroup())));
    muteCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    muteCombo.setForeground(Color.WHITE);
    muteCombo.setToolTipText(muteHelp);
    attachHoverHelp(muteCombo, muteHelp);
    muteCombo.addActionListener(
        e -> {
          int mg = muteCombo.getSelectedIndex();
          sound.setMuteGroup(mg);
          getKitArray(vm, BridgeContract.G_KIT_MUTE_GROUP).setInt(idx, (long) mg);
        });
    leftPanel.add(muteCombo, cLeft);
    leftRow++;

    // Reverse Checkbox
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 1;
    JLabel reverseLabel = label("Reverse:");
    String reverseHelp =
        "<b>REVERSE PLAYBACK:</b> Reverse the sample audio playback direction. — <i>Physical Deluge:</i> Press shift + click REVERSE button.";
    reverseLabel.setToolTipText(reverseHelp);
    attachHoverHelp(reverseLabel, reverseHelp);
    leftPanel.add(reverseLabel, cLeft);

    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    JCheckBox reverseBox = new JCheckBox();
    reverseBox.setSelected(sound.isReverse());
    reverseBox.setBackground(SwingSynthConfigDialog.BG_CARD);
    reverseBox.setToolTipText(reverseHelp);
    attachHoverHelp(reverseBox, reverseHelp);
    reverseBox.addActionListener(
        e -> {
          sound.setReverse(reverseBox.isSelected());
          getKitArray(vm, BridgeContract.G_KIT_REVERSE)
              .setInt(idx, reverseBox.isSelected() ? 1L : 0L);
        });
    leftPanel.add(reverseBox, cLeft);
    leftRow++;

    // ── Right Column: DELAY, REVERB & SIDECHAIN ──

    // Delay Header
    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 3;
    rightPanel.add(sectionLabel("DELAY FX"), cRight);
    rightRow++;

    // Ping Pong
    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    JLabel pingpongLabel = label("Ping-Pong:");
    String pingpongHelp =
        "<b>DELAY PING-PONG:</b> Alternate the echo repeats between left and right channels for immersive stereo field. — <i>Physical Deluge:</i> Set delay mode paths.";
    pingpongLabel.setToolTipText(pingpongHelp);
    attachHoverHelp(pingpongLabel, pingpongHelp);
    rightPanel.add(pingpongLabel, cRight);

    cRight.gridx = 1;
    cRight.gridwidth = 2;
    JComboBox<String> pingpongCombo = new JComboBox<>(new String[] {"Off", "On"});
    pingpongCombo.setSelectedIndex(Math.max(0, Math.min(1, sound.getDelayPingPong())));
    pingpongCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    pingpongCombo.setForeground(Color.WHITE);
    pingpongCombo.setToolTipText(pingpongHelp);
    attachHoverHelp(pingpongCombo, pingpongHelp);
    pingpongCombo.addActionListener(
        e -> {
          int v = pingpongCombo.getSelectedIndex();
          sound.setDelayPingPong(v);
          bridge.setKitDelayPingpong(idx, v);
        });
    rightPanel.add(pingpongCombo, cRight);
    rightRow++;

    // Analog mode delay
    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    JLabel analogLabel = label("Analog:");
    String analogHelp =
        "<b>DELAY MODE:</b> Selects digital high-fidelity delay or vintage analog-modeled bucket brigade delay line simulation. — <i>Physical Deluge:</i> Set delay tape options.";
    analogLabel.setToolTipText(analogHelp);
    attachHoverHelp(analogLabel, analogHelp);
    rightPanel.add(analogLabel, cRight);

    cRight.gridx = 1;
    cRight.gridwidth = 2;
    JComboBox<String> analogCombo = new JComboBox<>(new String[] {"Digital", "Analog"});
    analogCombo.setSelectedIndex(Math.max(0, Math.min(1, sound.getDelayAnalog())));
    analogCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    analogCombo.setForeground(Color.WHITE);
    analogCombo.setToolTipText(analogHelp);
    attachHoverHelp(analogCombo, analogHelp);
    analogCombo.addActionListener(
        e -> {
          int v = analogCombo.getSelectedIndex();
          sound.setDelayAnalog(v);
          bridge.setKitDelayAnalog(idx, v);
        });
    rightPanel.add(analogCombo, cRight);
    rightRow++;

    // Reverb Header
    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 3;
    rightPanel.add(sectionLabel("REVERB"), cRight);
    rightRow++;

    String reverbHelp =
        "<b>REVERB SEND:</b> Send ratio level of this drum channel signal to the global algorithmic reverb tank (0% to 100%). — <i>Physical Deluge:</i> Turn dynamic reverb gold shortcut knob.";
    rightRow =
        addSlider(
            rightPanel,
            cRight,
            rightRow,
            "Amount (0-100):",
            reverbHelp,
            0,
            100,
            (int) (sound.getReverbAmount() * 100),
            val -> {
              float amt = val / 100f;
              sound.setReverbAmount(amt);
              bridge.setKitReverbAmount(idx, amt);
            });

    // Compressor Header
    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 3;
    rightPanel.add(sectionLabel("COMPRESSOR"), cRight);
    rightRow++;

    String thresholdHelp =
        "<b>COMPRESSOR THRESHOLD:</b> Signal level at which compression begins. Lower levels trigger stronger dynamic compression. — <i>Physical Deluge:</i> Set kit compression parameters.";
    rightRow =
        addSlider(
            rightPanel,
            cRight,
            rightRow,
            "Threshold (0-100):",
            thresholdHelp,
            0,
            100,
            (int) (sound.getCompressorThreshold() * 100),
            val -> {
              float t = val / 100f;
              sound.setCompressorThreshold(t);
              bridge.setKitCompThreshold(idx, t);
            });

    String compSyncHelp =
        "<b>COMPRESSOR SYNC:</b> Lock-sync the compression trigger to tempo divisions for sidechain-style ducking effects. — <i>Physical Deluge:</i> Set sync level.";
    rightRow =
        addSlider(
            rightPanel,
            cRight,
            rightRow,
            "Sync Level (0-100):",
            compSyncHelp,
            0,
            100,
            sound.getCompressorSyncLevel(),
            val -> {
              sound.setCompressorSyncLevel(val);
              bridge.setKitCompSyncLevel(idx, val);
            });

    // Sidechain Header
    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 3;
    rightPanel.add(sectionLabel("SIDECHAIN DUCKING"), cRight);
    rightRow++;

    String scSyncHelp =
        "<b>SIDECHAIN SYNC:</b> Amount of volume ducking applied by the sidechain trigger signal (0% to 100%). — <i>Physical Deluge:</i> Set sync level.";
    rightRow =
        addSlider(
            rightPanel,
            cRight,
            rightRow,
            "Sync Level (0-100):",
            scSyncHelp,
            0,
            100,
            sound.getSidechainSyncLevel(),
            val -> {
              sound.setSidechainSyncLevel(val);
              bridge.setKitSidechainSyncLevel(idx, val);
            });

    // Sync Type combo
    cRight.gridx = 0;
    cRight.gridy = rightRow;
    cRight.gridwidth = 1;
    JLabel scTypeLabel = label("Sync Type:");
    String scTypeHelp =
        "<b>SIDECHAIN BEAT SYNC:</b> Locks the ducking trigger rate to project BPM divisions (triplets, 8th, 16th notes, etc.). — <i>Physical Deluge:</i> Set sidechain sync rate.";
    scTypeLabel.setToolTipText(scTypeHelp);
    attachHoverHelp(scTypeLabel, scTypeHelp);
    rightPanel.add(scTypeLabel, cRight);

    cRight.gridx = 1;
    cRight.gridwidth = 2;
    JComboBox<String> scTypeCombo =
        new JComboBox<>(new String[] {"Off", "1/1", "1/2", "1/4", "1/8", "1/16", "1/32"});
    scTypeCombo.setSelectedIndex(Math.max(0, Math.min(6, sound.getSidechainSyncType())));
    scTypeCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    scTypeCombo.setForeground(Color.WHITE);
    scTypeCombo.setToolTipText(scTypeHelp);
    attachHoverHelp(scTypeCombo, scTypeHelp);
    scTypeCombo.addActionListener(
        e -> {
          int v = scTypeCombo.getSelectedIndex();
          sound.setSidechainSyncType(v);
          bridge.setKitSidechainSyncType(idx, v);
        });
    rightPanel.add(scTypeCombo, cRight);
    rightRow++;

    String scAtkHelp =
        "<b>SIDECHAIN ATTACK:</b> How fast the volume ducks down to the minimum level when triggered. — <i>Physical Deluge:</i> Set sidechain envelope attack.";
    rightRow =
        addSlider(
            rightPanel,
            cRight,
            rightRow,
            "Attack (0-100):",
            scAtkHelp,
            0,
            100,
            (int) (sound.getSidechainAttack() * 100),
            val -> {
              float a = val / 100f;
              sound.setSidechainAttack(a);
              bridge.setKitSidechainAttack(idx, a);
            });

    String scRelHelp =
        "<b>SIDECHAIN RELEASE:</b> How fast the volume recovers back to normal levels after a trigger. — <i>Physical Deluge:</i> Set sidechain envelope release.";
    rightRow =
        addSlider(
            rightPanel,
            cRight,
            rightRow,
            "Release (0-100):",
            scRelHelp,
            0,
            100,
            (int) (sound.getSidechainRelease() * 100),
            val -> {
              float r = val / 100f;
              sound.setSidechainRelease(r);
              bridge.setKitSidechainRelease(idx, r);
            });

    // ── Main side-by-side assembly ──
    GridBagConstraints cMain = new GridBagConstraints();
    cMain.fill = GridBagConstraints.BOTH;
    cMain.weightx = 0.5;
    cMain.weighty = 1.0;
    cMain.gridy = 0;

    cMain.gridx = 0;
    cMain.insets = new Insets(0, 0, 0, 15);
    add(leftPanel, cMain);

    cMain.gridx = 1;
    cMain.insets = new Insets(0, 15, 0, 0);
    add(rightPanel, cMain);

    return panel;
  }

  private int addSlider(
      JPanel panel,
      GridBagConstraints c,
      int row,
      String labelText,
      String tooltip,
      int min,
      int max,
      int initial,
      java.util.function.IntConsumer onChange) {
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 1;
    JLabel lbl = label(labelText);
    lbl.setToolTipText(tooltip);
    attachHoverHelp(lbl, tooltip);
    panel.add(lbl, c);

    JSlider slider = new JSlider(min, max, Math.max(min, Math.min(max, initial)));
    slider.setBackground(SwingSynthConfigDialog.BG_CARD);
    slider.setToolTipText(tooltip);
    attachHoverHelp(slider, tooltip);

    JLabel valLabel = new JLabel(String.valueOf(initial));
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(55, 20));

    slider.addChangeListener(
        e -> {
          onChange.accept(slider.getValue());
          valLabel.setText(String.valueOf(slider.getValue()));
        });

    c.gridx = 1;
    c.gridwidth = 1;
    panel.add(slider, c);
    c.gridx = 2;
    c.gridwidth = 1;
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
