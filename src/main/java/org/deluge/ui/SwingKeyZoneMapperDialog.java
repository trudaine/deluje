package org.deluge.ui;

import java.awt.*;
import java.io.File;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.model.KeyZone;
import org.deluge.model.SynthTrackModel;
import org.deluge.project.PreferencesManager;

/**
 * A luxury, dark-neon Visual Multi-Sample Keyboard Zone Mapper Dialog. Houses the interactive piano
 * roll zone grid, toolbar actions (Add/Delete/Clear), and two-way synchronized detail parameter
 * controls (pitch, velocity, looping). Hot-swaps modified multisample mappings live into the active
 * audio engine.
 */
public class SwingKeyZoneMapperDialog extends JDialog {

  private final SynthTrackModel model;
  private final int oscIndex; // 0 = Osc A, 1 = Osc B
  private final int trackIndex;
  private final BridgeContract bridge;

  private final List<KeyZone> zones;
  private final SwingKeyZoneGridMap gridMap;

  // Detail Panel Fields
  private JTextField pathField;
  private JSpinner minPitchSpinner;
  private JSpinner maxPitchSpinner;
  private JSpinner minVelSpinner;
  private JSpinner maxVelSpinner;
  private JSpinner transposeSpinner;
  private JSpinner loopStartSpinner;
  private JSpinner loopEndSpinner;
  private JCheckBox loopCheckBox;

  private boolean isUpdatingFields = false;

  public SwingKeyZoneMapperDialog(
      Window owner, SynthTrackModel model, int oscIndex, int trackIndex, BridgeContract bridge) {
    super(
        owner,
        String.format("🔬 Multi-Sample Zone Mapper — Oscillator %s", (oscIndex == 0) ? "A" : "B"),
        ModalityType.MODELESS);

    this.model = model;
    this.oscIndex = oscIndex;
    this.trackIndex = trackIndex;
    this.bridge = bridge;

    // Get reference to the track's target zone list
    this.zones =
        (oscIndex == 0) ? model.getKeyZones().getOsc1Zones() : model.getKeyZones().getOsc2Zones();

    setSize(1000, 550);
    setMinimumSize(new Dimension(850, 450));
    setLocationRelativeTo(owner);
    getContentPane().setBackground(SwingSynthConfigDialog.BG_DARK);
    setLayout(new BorderLayout(8, 8));

    // 1. Top Toolbar Panel
    JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
    toolbar.setBackground(SwingSynthConfigDialog.BG_DARK);
    toolbar.setBorder(new EmptyBorder(10, 15, 4, 15));

    JLabel titleLabel = new JLabel("🔬 MULTI-SAMPLE KEYZONE ROLL");
    // Emoji-prefixed labels under-measure on some fonts, clipping the last glyph.
    titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 16));
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
    titleLabel.setForeground(SwingSynthConfigDialog.ACCENT_MINT);
    toolbar.add(titleLabel);

    JButton addZoneBtn = new JButton("Add Sample Zone...");
    styleButton(addZoneBtn, new Color(0x1e, 0x32, 0x22), new Color(0x00, 0xff, 0x66));
    addZoneBtn.addActionListener(e -> addSampleZone());
    toolbar.add(addZoneBtn);

    JButton deleteZoneBtn = new JButton("Delete Zone");
    styleButton(deleteZoneBtn, new Color(0x3c, 0x1e, 0x1e), new Color(0xff, 0x3b, 0x30));
    deleteZoneBtn.addActionListener(e -> deleteSelectedZone());
    toolbar.add(deleteZoneBtn);

    JButton clearAllBtn = new JButton("Clear All");
    styleButton(clearAllBtn, new Color(0x2d, 0x2d, 0x32), Color.LIGHT_GRAY);
    clearAllBtn.addActionListener(e -> clearAllZones());
    toolbar.add(clearAllBtn);

    add(toolbar, BorderLayout.NORTH);

    // 2. Center Scrollable Piano Roll Grid Map
    gridMap = new SwingKeyZoneGridMap(zones);
    JScrollPane scrollPane = new JScrollPane(gridMap);
    scrollPane.setBackground(SwingSynthConfigDialog.BG_DARK);
    scrollPane.getViewport().setBackground(SwingSynthConfigDialog.BG_DARK);
    scrollPane.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, new Color(0x2d, 0x2d, 0x32)));
    scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_ALWAYS);
    scrollPane.getHorizontalScrollBar().setUnitIncrement(15);
    add(scrollPane, BorderLayout.CENTER);

    // 3. Bottom Grid Details & Action Panel
    JPanel bottomPanel = new JPanel(new BorderLayout(0, 10));
    bottomPanel.setBackground(new Color(0x16, 0x16, 0x1a));
    bottomPanel.setBorder(new EmptyBorder(12, 15, 12, 15));

    JPanel detailsGrid = createDetailsPanel();
    bottomPanel.add(detailsGrid, BorderLayout.CENTER);

    JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
    actionsRow.setBackground(new Color(0x16, 0x16, 0x1a));

    JButton closeBtn = new JButton("Close & Apply");
    styleButton(closeBtn, new Color(0x2d, 0x2d, 0x32), Color.WHITE);
    closeBtn.addActionListener(e -> dispose());
    actionsRow.add(closeBtn);

    bottomPanel.add(actionsRow, BorderLayout.SOUTH);
    add(bottomPanel, BorderLayout.SOUTH);

    // Two-way synchronization hook
    gridMap.setOnSelectionChanged(this::syncFieldsFromSelection);
    gridMap.setOnZonesModified(this::triggerEngineHotSwap);

    // Select first zone as default if present
    if (!zones.isEmpty()) {
      gridMap.setSelectedZone(zones.get(0));
      syncFieldsFromSelection();
    } else {
      toggleFieldsEnabled(false);
    }
  }

  private JPanel createDetailsPanel() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x1e, 0x1e, 0x24));
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32), 1),
            new EmptyBorder(10, 12, 10, 12)));

    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(4, 6, 4, 6);
    c.weighty = 0.0;

    // ── Column 1: Sample Path ──
    c.gridx = 0;
    c.gridy = 0;
    c.gridwidth = 2;
    panel.add(SwingSynthConfigDialog.sectionLabel("SAMPLE SETTINGS"), c);

    c.gridy = 1;
    c.gridwidth = 1;
    panel.add(new JLabel("Path:"), c);

    c.gridx = 1;
    c.weightx = 1.0;
    pathField = new JTextField(15);
    pathField.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    pathField.setForeground(Color.WHITE);
    pathField.setEditable(false);
    panel.add(pathField, c);

    // ── Column 2: Pitch Boundaries ──
    c.gridx = 2;
    c.gridy = 0;
    c.weightx = 0.0;
    c.gridwidth = 2;
    panel.add(SwingSynthConfigDialog.sectionLabel("PITCH LIMITS"), c);

    c.gridy = 1;
    c.gridwidth = 1;
    panel.add(new JLabel("Min Pitch:"), c);

    c.gridx = 3;
    minPitchSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 127, 1));
    styleSpinner(minPitchSpinner);
    minPitchSpinner.addChangeListener(e -> updateSelectedZoneFromFields());
    panel.add(minPitchSpinner, c);

    c.gridx = 2;
    c.gridy = 2;
    panel.add(new JLabel("Max Pitch:"), c);

    c.gridx = 3;
    maxPitchSpinner = new JSpinner(new SpinnerNumberModel(127, 0, 127, 1));
    styleSpinner(maxPitchSpinner);
    maxPitchSpinner.addChangeListener(e -> updateSelectedZoneFromFields());
    panel.add(maxPitchSpinner, c);

    c.gridx = 2;
    c.gridy = 3;
    panel.add(new JLabel("Transpose:"), c);

    c.gridx = 3;
    transposeSpinner = new JSpinner(new SpinnerNumberModel(0, -48, 48, 1));
    styleSpinner(transposeSpinner);
    transposeSpinner.addChangeListener(e -> updateSelectedZoneFromFields());
    panel.add(transposeSpinner, c);

    // ── Column 3: Velocity Boundaries ──
    c.gridx = 4;
    c.gridy = 0;
    c.gridwidth = 2;
    panel.add(SwingSynthConfigDialog.sectionLabel("VELOCITY LAYERING"), c);

    c.gridy = 1;
    c.gridwidth = 1;
    panel.add(new JLabel("Min Vel:"), c);

    c.gridx = 5;
    minVelSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 127, 1));
    styleSpinner(minVelSpinner);
    minVelSpinner.addChangeListener(e -> updateSelectedZoneFromFields());
    panel.add(minVelSpinner, c);

    c.gridx = 4;
    c.gridy = 2;
    panel.add(new JLabel("Max Vel:"), c);

    c.gridx = 5;
    maxVelSpinner = new JSpinner(new SpinnerNumberModel(127, 0, 127, 1));
    styleSpinner(maxVelSpinner);
    maxVelSpinner.addChangeListener(e -> updateSelectedZoneFromFields());
    panel.add(maxVelSpinner, c);

    // ── Column 4: Loop Controls ──
    c.gridx = 6;
    c.gridy = 0;
    c.gridwidth = 3;
    panel.add(SwingSynthConfigDialog.sectionLabel("LOOP CONTROLS"), c);

    c.gridy = 1;
    c.gridwidth = 1;
    loopCheckBox = new JCheckBox("Looping Enabled");
    loopCheckBox.setBackground(new Color(0x1e, 0x1e, 0x24));
    loopCheckBox.setForeground(Color.WHITE);
    loopCheckBox.addActionListener(e -> updateSelectedZoneFromFields());
    panel.add(loopCheckBox, c);

    c.gridx = 7;
    panel.add(new JLabel("Start:"), c);

    c.gridx = 8;
    loopStartSpinner = new JSpinner(new SpinnerNumberModel(-1, -1, 9999999, 100));
    styleSpinner(loopStartSpinner);
    loopStartSpinner.addChangeListener(e -> updateSelectedZoneFromFields());
    panel.add(loopStartSpinner, c);

    c.gridx = 7;
    c.gridy = 2;
    panel.add(new JLabel("End:"), c);

    c.gridx = 8;
    loopEndSpinner = new JSpinner(new SpinnerNumberModel(-1, -1, 9999999, 100));
    styleSpinner(loopEndSpinner);
    loopEndSpinner.addChangeListener(e -> updateSelectedZoneFromFields());
    panel.add(loopEndSpinner, c);

    return panel;
  }

  private void addSampleZone() {
    JFileChooser fileChooser = new JFileChooser(PreferencesManager.getLibraryDir());
    fileChooser.setDialogTitle("Select WAV Samples to Map");
    fileChooser.setMultiSelectionEnabled(true);

    int result = fileChooser.showOpenDialog(this);
    if (result == JFileChooser.APPROVE_OPTION) {
      File[] files = fileChooser.getSelectedFiles();
      if (files == null || files.length == 0) return;

      int numFiles = files.length;
      int noteSpan = Math.max(1, 128 / numFiles);

      for (int i = 0; i < numFiles; i++) {
        KeyZone kz = new KeyZone();
        // Resolve relative to SD card if possible
        String absPath = files[i].getAbsolutePath();
        String relativePath = getRelativeSdPath(absPath);
        kz.samplePath = relativePath;

        // Distribute pitches evenly across keyboard
        kz.minPitch = i * noteSpan;
        kz.maxPitch = (i == numFiles - 1) ? 127 : (kz.minPitch + noteSpan - 1);
        kz.minVelocity = 0;
        kz.maxVelocity = 127;

        zones.add(kz);
      }

      // Automatically force Oscillator type to SAMPLE on the model!
      if (oscIndex == 0) {
        model.setOsc1Type("SAMPLE");
      } else {
        model.setOsc2Type("SAMPLE");
      }

      gridMap.revalidate();
      gridMap.repaint();

      // Select the newly added zone
      KeyZone newZone = zones.get(zones.size() - 1);
      gridMap.setSelectedZone(newZone);
      syncFieldsFromSelection();

      // Refresh engine and GUI details
      triggerEngineHotSwap();
    }
  }

  private String getRelativeSdPath(String absolutePath) {
    File libraryDir = PreferencesManager.getLibraryDir();
    if (libraryDir != null) {
      String sdPath = libraryDir.getAbsolutePath();
      if (absolutePath.startsWith(sdPath)) {
        String relative = absolutePath.substring(sdPath.length());
        if (relative.startsWith("/") || relative.startsWith("\\")) {
          relative = relative.substring(1);
        }
        return relative;
      }
    }
    return absolutePath;
  }

  private void deleteSelectedZone() {
    KeyZone selected = gridMap.getSelectedZone();
    if (selected != null) {
      zones.remove(selected);
      gridMap.setSelectedZone(zones.isEmpty() ? null : zones.get(0));
      gridMap.revalidate();
      gridMap.repaint();
      syncFieldsFromSelection();
      triggerEngineHotSwap();
    }
  }

  private void clearAllZones() {
    int confirm =
        JOptionPane.showConfirmDialog(
            this,
            "Are you sure you want to clear all multi-sample zones?",
            "Confirm Clear All",
            JOptionPane.YES_NO_OPTION);
    if (confirm == JOptionPane.YES_OPTION) {
      zones.clear();
      gridMap.setSelectedZone(null);
      gridMap.revalidate();
      gridMap.repaint();
      syncFieldsFromSelection();
      triggerEngineHotSwap();
    }
  }

  private void syncFieldsFromSelection() {
    KeyZone kz = gridMap.getSelectedZone();
    if (kz == null) {
      toggleFieldsEnabled(false);
      return;
    }

    toggleFieldsEnabled(true);
    isUpdatingFields = true;

    pathField.setText(new File(kz.samplePath).getName());
    minPitchSpinner.setValue(kz.minPitch);
    maxPitchSpinner.setValue(kz.maxPitch);
    transposeSpinner.setValue(kz.transpose);
    minVelSpinner.setValue(kz.minVelocity);
    maxVelSpinner.setValue(kz.maxVelocity);
    loopCheckBox.setSelected(kz.looping);
    loopStartSpinner.setValue(kz.startLoopPos);
    loopEndSpinner.setValue(kz.endLoopPos);

    isUpdatingFields = false;
  }

  private void updateSelectedZoneFromFields() {
    if (isUpdatingFields) return;

    KeyZone kz = gridMap.getSelectedZone();
    if (kz != null) {
      kz.minPitch = (Integer) minPitchSpinner.getValue();
      kz.maxPitch = (Integer) maxPitchSpinner.getValue();
      kz.transpose = (Integer) transposeSpinner.getValue();
      kz.minVelocity = (Integer) minVelSpinner.getValue();
      kz.maxVelocity = (Integer) maxVelSpinner.getValue();
      kz.looping = loopCheckBox.isSelected();
      kz.startLoopPos = (Integer) loopStartSpinner.getValue();
      kz.endLoopPos = (Integer) loopEndSpinner.getValue();

      gridMap.repaint();
      triggerEngineHotSwap();
    }
  }

  private void triggerEngineHotSwap() {
    // Hot-swap the running audio engine using virtual thread compilation!
    int trackIdx = this.trackIndex;
    FirmwareSound fs = getLiveSound(trackIdx);
    if (fs != null) {
      // Switch the active oscillator type to sample-playback
      if (oscIndex == 0) {
        fs.fw2Sound.oscTypes[0] = OscType.SAMPLE;
      } else {
        fs.fw2Sound.oscTypes[1] = OscType.SAMPLE;
      }
      org.deluge.engine.FirmwareFactory.loadOscResources(model, fs);
    }
  }

  private org.deluge.engine.FirmwareSound getLiveSound(int trackIndex) {
    try {
      Object eng = bridge.getGlobalObject(BridgeContract.G_FIRMWARE_ENGINE);
      if (eng instanceof org.deluge.engine.FirmwareAudioEngine engine) {
        if (trackIndex >= 0 && trackIndex < engine.sounds.size()) {
          var sound = engine.sounds.get(trackIndex);
          if (sound instanceof org.deluge.engine.FirmwareSound) {
            return (org.deluge.engine.FirmwareSound) sound;
          }
        }
      }
    } catch (Exception ignored) {
    }
    return null;
  }

  private void toggleFieldsEnabled(boolean enabled) {
    pathField.setEnabled(enabled);
    minPitchSpinner.setEnabled(enabled);
    maxPitchSpinner.setEnabled(enabled);
    transposeSpinner.setEnabled(enabled);
    minVelSpinner.setEnabled(enabled);
    maxVelSpinner.setEnabled(enabled);
    loopCheckBox.setEnabled(enabled);
    loopStartSpinner.setEnabled(enabled);
    loopEndSpinner.setEnabled(enabled);

    if (!enabled) {
      pathField.setText("");
    }
  }

  private void styleButton(JButton btn, Color bg, Color fg) {
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFocusPainted(false);
    btn.setOpaque(true);
    btn.setContentAreaFilled(true);
    btn.setBorder(BorderFactory.createLineBorder(fg.darker(), 1));
    btn.setPreferredSize(new Dimension(140, 26));
  }

  private void styleSpinner(JSpinner spinner) {
    spinner.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    spinner.setForeground(Color.WHITE);
    JComponent editor = spinner.getEditor();
    if (editor instanceof JSpinner.DefaultEditor) {
      JTextField tf = ((JSpinner.DefaultEditor) editor).getTextField();
      tf.setBackground(SwingSynthConfigDialog.BG_CONTROL);
      tf.setForeground(Color.WHITE);
      tf.setCaretColor(Color.WHITE);
    }
    spinner.setPreferredSize(new Dimension(80, 24));
  }
}
