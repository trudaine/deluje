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
  private JTabbedPane tabs;
  private final int trackIndex;
  private final Timer liveApplyTimer;

  public void setSelectedTab(int index) {
    if (tabs != null && index >= 0 && index < tabs.getTabCount()) {
      tabs.setSelectedIndex(index);
    }
  }

  private final String DEFAULT_HELP_TEXT =
      "<html>💡 <b>QUICK HELP:</b> Hover over any drum sample control or FX slider to see its details and hardware mappings here!</html>";

  public SwingKitConfigDialog(
      Frame owner, KitTrackModel kit, ChuckVM vm, BridgeContract bridge, int trackIndex) {
    super(owner, "Kit Track Editor: " + kit.getName() + " (Track " + (trackIndex + 1) + ")", false);
    this.trackIndex = trackIndex;
    setSize(1280, 800);
    setLocationRelativeTo(owner);
    setLayout(new BorderLayout());
    getContentPane().setBackground(new Color(0x1a, 0x1a, 0x1a));

    List<Drum> sounds = kit.getDrums();
    tabs = new JTabbedPane();
    tabs.setBackground(new Color(0x25, 0x25, 0x25));
    tabs.setForeground(Color.WHITE);

    for (int i = 0; i < sounds.size(); i++) {
      tabs.addTab(
          sounds.get(i).getName(), buildSoundPanel((SoundDrum) sounds.get(i), i, kit, vm, bridge));
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
    styleButton(closeBtn, new Color(0x3a, 0x3a, 0x3e), Color.WHITE);
    closeBtn.addActionListener(e -> dispose());
    JPanel south = new JPanel();
    south.setBackground(new Color(0x25, 0x25, 0x25));
    south.add(closeBtn);
    southStack.add(south);

    add(southStack, BorderLayout.SOUTH);

    DarkComboBoxRenderer.styleComponentTree(this);

    // ── Live-apply ──
    liveApplyTimer = new Timer(200, e -> liveApplyToEngine(vm, kit));
    liveApplyTimer.start();
  }

  private void liveApplyToEngine(ChuckVM vm, KitTrackModel model) {
    try {
      Object engineObj = vm.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (engineObj instanceof org.chuck.deluge.firmware.engine.FirmwareAudioEngine engine
          && trackIndex < engine.sounds.size()
          && engine.sounds.get(trackIndex)
              instanceof org.chuck.deluge.firmware.engine.FirmwareKit kit) {
        org.chuck.deluge.firmware.engine.FirmwareFactory.applyModelToLiveSound(model, kit);
      }
    } catch (Exception ex) {
      // Never let a live-apply hiccup break the dialog (e.g. engine not running in tests).
    }
  }

  @Override
  public void dispose() {
    if (liveApplyTimer != null) {
      liveApplyTimer.stop();
    }
    super.dispose();
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

  private JPanel buildSoundPanel(
      SoundDrum sound, int idx, KitTrackModel kit, ChuckVM vm, BridgeContract bridge) {
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

    SwingWaveformPanel wavePanel = new SwingWaveformPanel(sound.getSamplePath());

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

    JButton browseBtn = new JButton("Change…");
    styleButton(browseBtn, new Color(0x33, 0x44, 0x55), Color.WHITE);
    browseBtn.setToolTipText(
        "Pick a sample for this drum — scoped, previewed and auditioned in place");
    // Commit logic, shared by the picker's "Replace" action.
    java.util.function.Consumer<java.io.File> replaceSample =
        f -> {
          String path = f.getAbsolutePath().replace('\\', '/');
          sound.setSamplePath(path);
          pathField.setText(path);
          wavePanel.setSamplePath(path);
          bridge.setSamplePath(idx, path);
          // Apply to the LIVE pure-engine kit drum so auditioning plays this sample (the bridge
          // path + G_LOAD_TRIGGER only feed the legacy DSL engine, not the pure engine).
          if (SwingDelugeApp.mainInstance != null) {
            SwingDelugeApp.mainInstance.applyKitDrumSampleLive(kit, idx, path);
          }
          if (vm != null) {
            try {
              vm.setGlobalString("g_sample_" + idx, path);
              vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
            } catch (Exception ex) {
              System.err.println("[KitConfig] sample load error: " + ex.getMessage());
            }
          }
        };
    browseBtn.addActionListener(
        e ->
            LibraryPicker.show(
                browseBtn,
                LibraryPicker.Scope.SAMPLES,
                sound.getSamplePath(),
                java.util.List.of(
                    new LibraryPicker.Action(
                        "Replace sample", new Color(0x00, 0x88, 0x66), replaceSample))));

    JPanel sampleRow = new JPanel(new BorderLayout(6, 0));
    sampleRow.setBackground(SwingSynthConfigDialog.BG_CARD);
    sampleRow.add(pathField, BorderLayout.CENTER);
    sampleRow.add(browseBtn, BorderLayout.EAST);
    cLeft.gridx = 1;
    cLeft.gridwidth = 2;
    leftPanel.add(sampleRow, cLeft);
    leftRow++;

    // Waveform Visualizer Screen
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 3;
    cLeft.insets = new Insets(8, 10, 8, 10);
    leftPanel.add(wavePanel, cLeft);
    cLeft.insets = new Insets(6, 10, 6, 10); // Restore normal row insets
    leftRow++;

    // Interactive Loop and Crop Markers Panel
    JPanel cropPanel = new JPanel(new GridBagLayout());
    cropPanel.setBackground(SwingSynthConfigDialog.BG_CARD);
    cropPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
            BorderFactory.createEmptyBorder(6, 8, 6, 8)));
    GridBagConstraints cc = new GridBagConstraints();
    cc.fill = GridBagConstraints.HORIZONTAL;
    cc.insets = new Insets(4, 6, 4, 6);
    cc.weightx = 1.0;

    // Start Slider row
    cc.gridy = 0;
    cc.gridx = 0;
    cc.weightx = 0.0;
    JLabel sl1 = label("Start:");
    sl1.setFont(new Font("SansSerif", Font.BOLD, 10));
    cropPanel.add(sl1, cc);

    cc.gridx = 1;
    cc.weightx = 1.0;
    JSlider startSlider = new JSlider(0, 100, 0);
    DarkSliderUI.styleSlider(startSlider, new Color(0x00, 0xe6, 0x76));
    cropPanel.add(startSlider, cc);

    cc.gridx = 2;
    cc.weightx = 0.0;
    JLabel startValLbl = new JLabel("0");
    startValLbl.setForeground(Color.CYAN);
    startValLbl.setFont(new Font("Monospaced", Font.BOLD, 10));
    cropPanel.add(startValLbl, cc);

    // End Slider row
    cc.gridy = 1;
    cc.gridx = 0;
    cc.weightx = 0.0;
    JLabel sl2 = label("End:");
    sl2.setFont(new Font("SansSerif", Font.BOLD, 10));
    cropPanel.add(sl2, cc);

    cc.gridx = 1;
    cc.weightx = 1.0;
    JSlider endSlider = new JSlider(0, 100, 100);
    DarkSliderUI.styleSlider(endSlider, new Color(0xff, 0x17, 0x44));
    cropPanel.add(endSlider, cc);

    cc.gridx = 2;
    cc.weightx = 0.0;
    JLabel endValLbl = new JLabel("0");
    endValLbl.setForeground(Color.CYAN);
    endValLbl.setFont(new Font("Monospaced", Font.BOLD, 10));
    cropPanel.add(endValLbl, cc);

    // Loop Start Slider row
    cc.gridy = 2;
    cc.gridx = 0;
    cc.weightx = 0.0;
    JLabel sl3 = label("Loop S:");
    sl3.setFont(new Font("SansSerif", Font.BOLD, 10));
    cropPanel.add(sl3, cc);

    cc.gridx = 1;
    cc.weightx = 1.0;
    JSlider loopStartSlider = new JSlider(0, 100, 0);
    DarkSliderUI.styleSlider(loopStartSlider, new Color(0x29, 0xb6, 0xf6));
    cropPanel.add(loopStartSlider, cc);

    cc.gridx = 2;
    cc.weightx = 0.0;
    JLabel loopStartValLbl = new JLabel("0");
    loopStartValLbl.setForeground(Color.CYAN);
    loopStartValLbl.setFont(new Font("Monospaced", Font.BOLD, 10));
    cropPanel.add(loopStartValLbl, cc);

    // Loop End Slider row
    cc.gridy = 3;
    cc.gridx = 0;
    cc.weightx = 0.0;
    JLabel sl4 = label("Loop E:");
    sl4.setFont(new Font("SansSerif", Font.BOLD, 10));
    cropPanel.add(sl4, cc);

    cc.gridx = 1;
    cc.weightx = 1.0;
    JSlider loopEndSlider = new JSlider(0, 100, 100);
    DarkSliderUI.styleSlider(loopEndSlider, new Color(0xec, 0x40, 0x7a));
    cropPanel.add(loopEndSlider, cc);

    cc.gridx = 2;
    cc.weightx = 0.0;
    JLabel loopEndValLbl = new JLabel("0");
    loopEndValLbl.setForeground(Color.CYAN);
    loopEndValLbl.setFont(new Font("Monospaced", Font.BOLD, 10));
    cropPanel.add(loopEndValLbl, cc);

    // Dynamic sliders listener triggers
    startSlider.addChangeListener(
        ev -> {
          startValLbl.setText(String.valueOf(startSlider.getValue()));
          wavePanel.setMarkers(
              startSlider.getValue(),
              endSlider.getValue(),
              loopStartSlider.getValue(),
              loopEndSlider.getValue());
        });
    endSlider.addChangeListener(
        ev -> {
          endValLbl.setText(String.valueOf(endSlider.getValue()));
          wavePanel.setMarkers(
              startSlider.getValue(),
              endSlider.getValue(),
              loopStartSlider.getValue(),
              loopEndSlider.getValue());
        });
    loopStartSlider.addChangeListener(
        ev -> {
          loopStartValLbl.setText(String.valueOf(loopStartSlider.getValue()));
          wavePanel.setMarkers(
              startSlider.getValue(),
              endSlider.getValue(),
              loopStartSlider.getValue(),
              loopEndSlider.getValue());
        });
    loopEndSlider.addChangeListener(
        ev -> {
          loopEndValLbl.setText(String.valueOf(loopEndSlider.getValue()));
          wavePanel.setMarkers(
              startSlider.getValue(),
              endSlider.getValue(),
              loopStartSlider.getValue(),
              loopEndSlider.getValue());
        });

    // Wire virtual threads decoder complete callback!
    wavePanel.setLoadListener(
        totalFrames -> {
          startSlider.setMaximum(totalFrames);
          endSlider.setMaximum(totalFrames);
          loopStartSlider.setMaximum(totalFrames);
          loopEndSlider.setMaximum(totalFrames);

          int sVal = sound.getStartSamplePos() >= 0 ? sound.getStartSamplePos() : 0;
          int eVal = sound.getEndSamplePos() > 0 ? sound.getEndSamplePos() : totalFrames;
          int lsVal = sound.getStartLoopPos() >= 0 ? sound.getStartLoopPos() : 0;
          int leVal = sound.getEndLoopPos() > 0 ? sound.getEndLoopPos() : totalFrames;

          startSlider.setValue(sVal);
          endSlider.setValue(eVal);
          loopStartSlider.setValue(lsVal);
          loopEndSlider.setValue(leVal);

          startValLbl.setText(String.valueOf(sVal));
          endValLbl.setText(String.valueOf(eVal));
          loopStartValLbl.setText(String.valueOf(lsVal));
          loopEndValLbl.setText(String.valueOf(leVal));

          wavePanel.setMarkers(sVal, eVal, lsVal, leVal);
        });

    // Action buttons row (Commit / Full size)
    cc.gridy = 4;
    cc.gridx = 0;
    cc.gridwidth = 3;
    cc.insets = new Insets(8, 0, 0, 0);
    JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
    btnRow.setBackground(SwingSynthConfigDialog.BG_CARD);

    JButton resetBtn = new JButton("Reset Loop Bounds");
    styleButton(resetBtn, new Color(0x3a, 0x3a, 0x3e), Color.WHITE);
    resetBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    resetBtn.addActionListener(
        ev -> {
          int total = wavePanel.getTotalFrames();
          if (total > 0) {
            startSlider.setValue(0);
            endSlider.setValue(total);
            loopStartSlider.setValue(0);
            loopEndSlider.setValue(total);
            wavePanel.setMarkers(0, total, 0, total);
          }
        });
    btnRow.add(resetBtn);

    JButton wtLabBtn = new JButton("🔬 Wavetable Laboratory...");
    styleButton(wtLabBtn, new Color(0x1e, 0x32, 0x3c), new Color(0x00, 0xff, 0xcc));
    wtLabBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    wtLabBtn.setToolTipText(
        "Open the 3D perspective waterfall wavetable laboratory editor for custom indices scans!");
    wtLabBtn.addActionListener(
        ev -> {
          Window owner = SwingUtilities.getWindowAncestor(btnRow);
          int trackIdx =
              (SwingDelugeApp.mainInstance != null
                      && SwingDelugeApp.mainInstance.getClipPanel() != null)
                  ? SwingDelugeApp.mainInstance.getClipPanel().getEditedModelTrack()
                  : 0;
          SwingWavetableDialog wtDlg =
              new SwingWavetableDialog(owner, sound, bridge, trackIdx, idx);
          wtDlg.setVisible(true);
        });
    btnRow.add(wtLabBtn);

    JButton commitBtn = new JButton("💾 Save & Apply Crop");
    styleButton(commitBtn, new Color(0x0c, 0x38, 0x1f), Color.GREEN);
    commitBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    commitBtn.addActionListener(
        ev -> {
          sound.setStartSamplePos(startSlider.getValue());
          sound.setEndSamplePos(endSlider.getValue());
          sound.setStartLoopPos(loopStartSlider.getValue());
          sound.setEndLoopPos(loopEndSlider.getValue());

          // Save to SD Kit Preset and push to ChucK synthesis bridges list!
          SwingDelugeApp.mainInstance.pushModelToBridge();
          SwingDelugeApp.mainInstance.propagateCurrentModel();
          SwingDelugeApp.mainInstance.syncHighFidelityEngine(
              SwingDelugeApp.mainInstance.getCurrentProject());
          SwingDelugeApp.mainInstance.refreshGrids();

          JOptionPane.showMessageDialog(
              this,
              "🎉 Loop points and crop boundaries saved and cabled live successfully!",
              "Success",
              JOptionPane.INFORMATION_MESSAGE);
        });
    btnRow.add(commitBtn);
    cropPanel.add(btnRow, cc);

    // Add cropPanel to Left Column!
    cLeft.gridx = 0;
    cLeft.gridy = leftRow;
    cLeft.gridwidth = 3;
    cLeft.insets = new Insets(4, 10, 8, 10);
    leftPanel.add(cropPanel, cLeft);
    cLeft.insets = new Insets(6, 10, 6, 10); // Restore normal row insets
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
    panel.add(leftPanel, cMain);

    cMain.gridx = 1;
    cMain.insets = new Insets(0, 15, 0, 0);
    panel.add(rightPanel, cMain);

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
    DarkSliderUI.styleSlider(slider, new Color(0xff, 0xb3, 0x00));
    slider.setToolTipText(tooltip);
    attachHoverHelp(slider, tooltip);

    JLabel valLabel = new JLabel(String.valueOf(initial));
    valLabel.setForeground(Color.CYAN);
    valLabel.setPreferredSize(new Dimension(55, 20));

    slider.addChangeListener(
        e -> {
          int newVal = slider.getValue();

          String paramName = null;
          String lblLower = labelText.toLowerCase();
          if (lblLower.contains("pitch")) paramName = "pitch";
          else if (lblLower.contains("attack")) paramName = "attack";
          else if (lblLower.contains("decay")) paramName = "decay";
          else if (lblLower.contains("sustain")) paramName = "sustain";
          else if (lblLower.contains("release")) paramName = "release";
          else if (lblLower.contains("volume") || lblLower.contains("level")) paramName = "volume";
          else if (lblLower.contains("pan")) paramName = "pan";

          if (SwingGridPanel.lockArmedTrack == trackIndex
              && SwingGridPanel.lockArmedStep != -1
              && paramName != null) {
            org.chuck.deluge.model.ProjectModel projectModel =
                SwingDelugeApp.mainInstance != null
                    ? SwingDelugeApp.mainInstance.getCurrentProject()
                    : null;
            if (projectModel != null) {
              org.chuck.deluge.model.TrackModel track = projectModel.getTracks().get(trackIndex);
              int activeClipIdx = track.getActiveClipIndex();
              if (activeClipIdx >= 0 && activeClipIdx < track.getClips().size()) {
                org.chuck.deluge.model.ClipModel clip = track.getClips().get(activeClipIdx);
                float normalized = (float) (newVal - min) / (max - min);
                int drumIdx = tabs.getSelectedIndex();
                if (drumIdx >= 0) {
                  clip.setRowAutomation(
                      drumIdx, paramName, SwingGridPanel.lockArmedStep, normalized);
                  SwingDelugeApp.mainInstance.fireProjectChanged();
                }
              }
            }
          } else {
            onChange.accept(newVal);
            if (SwingTopBarPanel.isAffectEntireActive && paramName != null) {
              org.chuck.deluge.model.ProjectModel projectModel =
                  SwingDelugeApp.mainInstance != null
                      ? SwingDelugeApp.mainInstance.getCurrentProject()
                      : null;
              if (projectModel != null) {
                org.chuck.deluge.model.TrackModel curTrack =
                    projectModel.getTracks().get(trackIndex);
                int drumIdx = tabs.getSelectedIndex();
                if (drumIdx >= 0
                    && curTrack instanceof org.chuck.deluge.model.KitTrackModel curKit) {
                  org.chuck.deluge.model.Drum curDrum = curKit.getDrums().get(drumIdx);
                  Object curVal = getProperty(curDrum, paramName);
                  if (curVal != null) {
                    // Faithful hardware AFFECT ENTIRE: apply the edit to every drum of THIS kit
                    // (all rows of the current kit), not to other tracks. See DelugeFirmware
                    // affect-entire semantics (kit edits broadcast across the kit's own rows).
                    java.util.List<org.chuck.deluge.model.Drum> drums = curKit.getDrums();
                    for (int d = 0; d < drums.size(); d++) {
                      if (d != drumIdx) {
                        setProperty(drums.get(d), paramName, curVal);
                      }
                    }
                    SwingDelugeApp.mainInstance.fireProjectChanged();
                  }
                }
              }
            }
          }

          valLabel.setText(String.valueOf(newVal));

          if (SwingDelugeApp.mainInstance != null) {
            String code = (paramName != null) ? paramName : labelText.replace(":", "").trim();
            if (code.length() > 4) code = code.substring(0, 4);
            SwingDelugeApp.mainInstance.updateHardwareLedDisplayTransient(
                code.toUpperCase(), String.valueOf(newVal));
          }
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

  private void styleButton(AbstractButton btn, Color bg, Color fg) {
    btn.setOpaque(true);
    btn.setBorderPainted(true);
    btn.setContentAreaFilled(true);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusable(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.brighter(), 1),
            BorderFactory.createEmptyBorder(4, 12, 4, 12)));
  }

  private static Object getProperty(Object obj, String propertyName) {
    if (obj == null || propertyName == null) return null;
    String getterName =
        "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
    try {
      java.lang.reflect.Method m = obj.getClass().getMethod(getterName);
      return m.invoke(obj);
    } catch (Exception e) {
      String isName =
          "is" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
      try {
        java.lang.reflect.Method m = obj.getClass().getMethod(isName);
        return m.invoke(obj);
      } catch (Exception ignored) {
      }
    }
    return null;
  }

  private static void setProperty(Object obj, String propertyName, Object value) {
    if (obj == null || propertyName == null) return;
    String setterName =
        "set" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
    for (java.lang.reflect.Method m : obj.getClass().getMethods()) {
      if (m.getName().equals(setterName) && m.getParameterCount() == 1) {
        try {
          Class<?> paramType = m.getParameterTypes()[0];
          if (paramType == float.class && value instanceof Number) {
            m.invoke(obj, ((Number) value).floatValue());
          } else if (paramType == double.class && value instanceof Number) {
            m.invoke(obj, ((Number) value).doubleValue());
          } else if (paramType == int.class && value instanceof Number) {
            m.invoke(obj, ((Number) value).intValue());
          } else if (paramType == boolean.class && value instanceof Boolean) {
            m.invoke(obj, value);
          } else if (paramType.isAssignableFrom(value.getClass())) {
            m.invoke(obj, value);
          }
          return;
        } catch (Exception ignored) {
        }
      }
    }
  }
}
