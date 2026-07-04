package org.deluge.ui;

import java.awt.*;
import java.io.File;
import java.util.List;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.TrackModel;
import org.deluge.project.KitSynthSerializer;
import org.deluge.project.PreferencesManager;

/**
 * A spectacular, high-fidelity visual breakbeat loop auto-slicer and kit splitter JDialog. Allows
 * choosing a WAV loop, selecting slices count (4, 8, 16), viewing slice-grid dividers visually, and
 * automatic MPC-style track split generation and live hot-swaps!
 */
public class SwingAudioSlicerDialog extends JDialog {

  private final Frame parentFrame;
  private final BridgeContract bridge;

  private final ProjectModel projectModel;

  private JTextField pathField;
  private SwingWaveformPanel wavePanel;
  private JComboBox<String> sliceCountCombo;
  private JCheckBox autoChokeBox;
  private JSlider volSlider;
  private JLabel volLabel;

  private String currentFilePath = null;

  public SwingAudioSlicerDialog(
      Frame parent, final BridgeContract bridge, ProjectModel projectModel) {
    super(parent, "Delugeator Universal Audio Slicer & Kit Splitter", false);
    this.parentFrame = parent;
    this.bridge = bridge;

    this.projectModel = projectModel;

    setSize(800, 500);
    setLocationRelativeTo(parent);
    setLayout(new BorderLayout(10, 10));
    getContentPane().setBackground(new Color(0x12, 0x12, 0x14));

    // ── TOP PANEL (File Selector) ──
    JPanel topPanel = new JPanel(new GridBagLayout());
    topPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    topPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(8, 12, 8, 12)));
    GridBagConstraints cTop = new GridBagConstraints();
    cTop.fill = GridBagConstraints.HORIZONTAL;
    cTop.insets = new Insets(4, 4, 4, 4);

    cTop.gridx = 0;
    cTop.weightx = 0.0;
    JLabel pathLabel = new JLabel("Target Loop WAV:");
    pathLabel.setForeground(Color.WHITE);
    pathLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    topPanel.add(pathLabel, cTop);

    cTop.gridx = 1;
    cTop.weightx = 1.0;
    pathField = new JTextField();
    pathField.setEditable(false);
    pathField.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    pathField.setForeground(Color.LIGHT_GRAY);
    pathField.setPreferredSize(new Dimension(400, 26));
    topPanel.add(pathField, cTop);

    cTop.gridx = 2;
    cTop.weightx = 0.0;
    JButton browseBtn = new JButton("📁 Select WAV  ");
    browseBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
    styleButton(browseBtn, new Color(0x3a, 0x3a, 0x3e), Color.WHITE);
    browseBtn.setPreferredSize(new Dimension(140, 26));
    browseBtn.addActionListener(e -> selectWavLoopFile());
    topPanel.add(browseBtn, cTop);

    add(topPanel, BorderLayout.NORTH);

    // ── CENTER PANEL (Waveform visualizer canvas) ──
    JPanel centerPanel = new JPanel(new BorderLayout(5, 5));
    centerPanel.setBackground(new Color(0x12, 0x12, 0x14));
    centerPanel.setBorder(BorderFactory.createEmptyBorder(10, 15, 10, 15));

    wavePanel = new SwingWaveformPanel(null);
    wavePanel.setPreferredSize(new Dimension(760, 180));
    centerPanel.add(wavePanel, BorderLayout.CENTER);

    add(centerPanel, BorderLayout.CENTER);

    // ── SOUTH PANEL (Slices configurations & Action buttons) ──
    JPanel southPanel = new JPanel(new GridBagLayout());
    southPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    southPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)));
    GridBagConstraints cSouth = new GridBagConstraints();
    cSouth.fill = GridBagConstraints.HORIZONTAL;
    cSouth.weightx = 1.0;
    cSouth.insets = new Insets(4, 8, 4, 8);

    // Row 0: Slices combo & Volume
    cSouth.gridy = 0;
    cSouth.gridx = 0;
    cSouth.weightx = 0.4;
    JPanel comboRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    comboRow.setBackground(new Color(0x1a, 0x1a, 0x1e));
    JLabel comboLabel = new JLabel("Slices Count:");
    comboLabel.setForeground(Color.WHITE);
    comboLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    comboRow.add(comboLabel);

    String[] sliceOptions = {"4 Slices", "8 Slices", "16 Slices"};
    sliceCountCombo = new JComboBox<>(sliceOptions);
    sliceCountCombo.setSelectedIndex(1); // 8 slices default
    sliceCountCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    sliceCountCombo.setForeground(Color.WHITE);
    sliceCountCombo.addActionListener(e -> updateActiveSlicesGrid());
    comboRow.add(sliceCountCombo);
    southPanel.add(comboRow, cSouth);

    cSouth.gridx = 1;
    cSouth.weightx = 0.6;
    JPanel volRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    volRow.setBackground(new Color(0x1a, 0x1a, 0x1e));
    JLabel volLabelTitle = new JLabel("Default Volume:");
    volLabelTitle.setForeground(Color.LIGHT_GRAY);
    volLabelTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
    volRow.add(volLabelTitle);

    volSlider = new JSlider(0, 100, 80);
    DarkSliderUI.styleSlider(volSlider, Color.CYAN);
    volSlider.setPreferredSize(new Dimension(150, 20));
    volSlider.addChangeListener(e -> volLabel.setText(volSlider.getValue() + "%"));
    volRow.add(volSlider);

    volLabel = new JLabel("80%");
    volLabel.setForeground(Color.CYAN);
    volLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
    volRow.add(volLabel);
    southPanel.add(volRow, cSouth);

    // Row 1: Auto choke Hats
    cSouth.gridy = 1;
    cSouth.gridx = 0;
    cSouth.gridwidth = 2;
    cSouth.insets = new Insets(8, 8, 8, 8);
    autoChokeBox =
        new JCheckBox("Auto-Choke Slices (Map all drum sound slots to shared Mute Group 1)");
    autoChokeBox.setFont(new Font("SansSerif", Font.BOLD, 12));
    autoChokeBox.setForeground(new Color(0x00, 0xff, 0xcc));
    autoChokeBox.setBackground(new Color(0x1a, 0x1a, 0x1e));
    autoChokeBox.setSelected(true);
    southPanel.add(autoChokeBox, cSouth);

    // Row 2: Slice Action button
    cSouth.gridy = 2;
    cSouth.insets = new Insets(8, 0, 0, 0);
    JButton sliceActionBtn = new JButton("✂️ Slice & Load Across Kit Rows live  ");
    sliceActionBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
    sliceActionBtn.setPreferredSize(new Dimension(750, 44));
    styleButton(sliceActionBtn, new Color(0x0c, 0x38, 0x1f), Color.GREEN);
    sliceActionBtn.addActionListener(e -> generateAndSplitKitTrack());
    southPanel.add(sliceActionBtn, cSouth);

    add(southPanel, BorderLayout.SOUTH);

    // Calibrate load hook EDT listener
    wavePanel.setLoadListener(totalFrames -> updateActiveSlicesGrid());
    DarkComboBoxRenderer.styleComponentTree(this);
  }

  private void selectWavLoopFile() {
    File startDir = new File(PreferencesManager.getSamplesDir());
    JFileChooser chooser = new JFileChooser(startDir);
    chooser.setPreferredSize(new Dimension(900, 600));
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter(
            "Audio files", "wav", "aif", "aiff", "flac", "WAV", "AIF", "AIFF", "FLAC"));

    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File file = chooser.getSelectedFile();
      currentFilePath = file.getAbsolutePath().replace('\\', '/');
      pathField.setText(currentFilePath);
      wavePanel.setSamplePath(currentFilePath);
    }
  }

  private void updateActiveSlicesGrid() {
    int idx = sliceCountCombo.getSelectedIndex();
    int count = idx == 0 ? 4 : (idx == 1 ? 8 : 16);
    wavePanel.setSlicesGrid(count);
  }

  private void generateAndSplitKitTrack() {
    if (currentFilePath == null) {
      JOptionPane.showMessageDialog(
          this,
          "⚠️ Please select a target loop WAV file first!",
          "Error",
          JOptionPane.ERROR_MESSAGE);
      return;
    }

    int totalFrames = wavePanel.getTotalFrames();
    if (totalFrames <= 0) {
      JOptionPane.showMessageDialog(
          this,
          "⚠️ Waiting for the waveform to complete background Loom parsing...",
          "Wait",
          JOptionPane.WARNING_MESSAGE);
      return;
    }

    SwingGridPanel activeGrid = SwingDelugeApp.mainInstance.getActiveGridPanel();
    if (activeGrid == null) {
      JOptionPane.showMessageDialog(
          this,
          "⚠️ Please focus a project workspace grid panel first!",
          "Error",
          JOptionPane.ERROR_MESSAGE);
      return;
    }

    int activeTrackIdx = activeGrid.getEditedModelTrack();
    List<TrackModel> tracks = projectModel.getTracks();
    if (activeTrackIdx < 0 || activeTrackIdx >= tracks.size()) {
      JOptionPane.showMessageDialog(
          this, "⚠️ Please select a valid track channel row!", "Error", JOptionPane.ERROR_MESSAGE);
      return;
    }

    TrackModel track = tracks.get(activeTrackIdx);
    boolean fallbackCreated = false;

    // Fallback: If not a Kit track, automatically add a new Kit track!
    if (!(track instanceof KitTrackModel)) {
      int confirm =
          JOptionPane.showConfirmDialog(
              this,
              "💡 Active row channel is NOT a Drum Kit track. Would you like to automatically create and load a new Kit track channel?",
              "Create New Track?",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.QUESTION_MESSAGE);
      if (confirm != JOptionPane.YES_OPTION) return;

      KitTrackModel newKit = new KitTrackModel("Slices Kit");
      newKit.addClip(new ClipModel("CLIP 1", 8, 16));
      activeTrackIdx = tracks.size();
      projectModel.addTrack(newKit);
      track = newKit;
      fallbackCreated = true;
    }

    KitTrackModel kit = (KitTrackModel) track;
    kit.getDrums().clear();

    int idxCombo = sliceCountCombo.getSelectedIndex();
    int slices = idxCombo == 0 ? 4 : (idxCombo == 1 ? 8 : 16);
    int step = totalFrames / slices;

    float defaultVolume = volSlider.getValue() / 100.0f;
    boolean autoChoke = autoChokeBox.isSelected();

    File file = new File(currentFilePath);
    String baseName = file.getName().replace(".wav", "").replace(".WAV", "");

    // Populate kit rows sequentially with the sliced segments!
    for (int i = 0; i < 16; i++) {
      String soundName = String.format("%s_SL%02d", baseName, i + 1);
      SoundDrum sd = new SoundDrum(soundName);

      if (i < slices) {
        sd.setSamplePath(currentFilePath);
        sd.setStartSamplePos(i * step);
        sd.setEndSamplePos((i + 1) * step);
        sd.setVolume(defaultVolume);
        if (autoChoke) {
          sd.setMuteGroup(1);
        }
      }
      kit.addDrum(sd);
    }

    // Rename track to match the sliced breakbeat name!
    String trackTitle = "Slices_" + baseName;
    if (trackTitle.length() > 20) {
      trackTitle = trackTitle.substring(0, 20);
    }
    kit.setName(trackTitle);

    File exportFile = new File(PreferencesManager.getKitsDir(), trackTitle + ".XML");
    try {
      KitSynthSerializer.saveKit(kit, exportFile);
    } catch (Exception ex) {
      System.err.println("[KitSlicer] Export XML slices kit failed: " + ex.getMessage());
    }

    // Real-time playback reloading pipeline
    SwingDelugeApp.mainInstance.pushModelToBridge();
    SwingDelugeApp.mainInstance.reloadSidebarLibraries();
    SwingDelugeApp.mainInstance.propagateCurrentModel();
    SwingDelugeApp.mainInstance.syncHighFidelityEngine(projectModel);

    if (fallbackCreated) {
      activeGrid.setEditedModelTrack(activeTrackIdx);
    }
    SwingDelugeApp.mainInstance.refreshGrids();

    dispose(); // Close slicer dialog on completion

    JOptionPane.showMessageDialog(
        parentFrame,
        "<html>🎉 <b>AUDIO BREAKBEAT SLICED AND LOADED LIVE!</b><br><br>"
            + "WAV loop file sliced into <b>"
            + slices
            + " equal segments</b> and loaded sequentially across the drum rows!<br><br>"
            + "MPC-style slices and shared hi-hat choke configurations are now active and playing live!<br><br>"
            + "Kit XML slice preset exported to your SD card library:<br>"
            + "📁 <code>"
            + exportFile.getAbsolutePath()
            + "</code></html>",
        "Breakbeat Sliced!",
        JOptionPane.INFORMATION_MESSAGE);
  }

  private void styleButton(AbstractButton btn, Color bg, Color fg) {
    btn.setContentAreaFilled(false);
    btn.setOpaque(true);
    btn.setFocusPainted(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setBorder(BorderFactory.createLineBorder(fg, 1));
  }
}
