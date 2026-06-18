package org.deluge.ui;

import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.deluge.midi.MidiToProjectCompiler;
import org.deluge.midi.MidiToProjectCompiler.TrackImportConfig;
import org.deluge.model.ProjectModel;
import org.deluge.project.PreferencesManager;

/**
 * A premium, interactive MIDI Import Wizard. Allows users to audit MIDI tracks, map them to
 * physical Deluge Synth Presets, assign colors, and configure pitch-splitting before loading the
 * arrangement directly into the workstation sequencer.
 */
public class SwingMidiImportDialog extends JDialog {

  private final Color BG_DARK = new Color(0x12, 0x12, 0x14);
  private final Color PANEL_DARK = new Color(0x1a, 0x1a, 0x1c);
  private final Color GLOW_CYAN = new Color(0x00, 0xff, 0xcc);
  private final Color TEXT_WHITE = Color.WHITE;
  private final Color TEXT_MUTED = new Color(0x88, 0x88, 0x90);

  private final File midiFile;
  private final Frame owner;

  private List<TrackImportConfig> trackConfigs;
  private List<String> availablePresets;
  private JPanel cardsPanel;
  private boolean importSuccessful = false;
  private ProjectModel compiledProject = null;

  public SwingMidiImportDialog(Frame owner, File midiFile) {
    super(owner, "MIDI Import Wizard", true);
    this.owner = owner;
    this.midiFile = midiFile;
    loadPresets();
    initializeUI();
    loadMidiMetadata();
  }

  private void loadPresets() {
    availablePresets = new ArrayList<>();
    // Baseline core presets
    availablePresets.add("073 Piano");
    availablePresets.add("074 Electric Piano");
    availablePresets.add("076 Organ");
    availablePresets.add("078 House");
    availablePresets.add("000 Rich Saw Bass");
    availablePresets.add("001 Sync Bass");
    availablePresets.add("002 Basic Square Bass");
    availablePresets.add("005 Sweet Mono Bass");
    availablePresets.add("006 Vaporwave Bass");
    availablePresets.add("019 Fizzy Strings");
    availablePresets.add("026 PW Organ");
    availablePresets.add("030 Distant Porta");
    availablePresets.add("040 Spacer Leader");

    // Scan user's physical synths directory
    File synthsDir = PreferencesManager.getSynthsDir();
    if (synthsDir.isDirectory()) {
      File[] files = synthsDir.listFiles((dir, name) -> name.toLowerCase().endsWith(".xml"));
      if (files != null) {
        for (File f : files) {
          String name = f.getName().replace(".XML", "").replace(".xml", "");
          if (!availablePresets.contains(name)) {
            availablePresets.add(name);
          }
        }
      }
    }
    Collections.sort(availablePresets);
  }

  private void initializeUI() {
    setSize(920, 600);
    setLocationRelativeTo(owner);
    getContentPane().setBackground(BG_DARK);
    setLayout(new BorderLayout(10, 10));

    // ── Header Banner ────────────────────────────────────────────────────────
    JPanel headerPanel = new JPanel(new BorderLayout());
    headerPanel.setBackground(PANEL_DARK);
    headerPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2a, 0x2a, 0x30)),
            new EmptyBorder(12, 16, 12, 16)));

    JLabel titleLabel = new JLabel("MIDI IMPORT WIZARD");
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 15));
    titleLabel.setForeground(GLOW_CYAN);

    JLabel subLabel =
        new JLabel("File: " + midiFile.getName() + "  (" + (midiFile.length() / 1024) + " KB)");
    subLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    subLabel.setForeground(TEXT_MUTED);

    JPanel titleTextPanel = new JPanel(new GridLayout(2, 1, 2, 2));
    titleTextPanel.setOpaque(false);
    titleTextPanel.add(titleLabel);
    titleTextPanel.add(subLabel);

    headerPanel.add(titleTextPanel, BorderLayout.WEST);
    add(headerPanel, BorderLayout.NORTH);

    // ── Center Scroll Deck ───────────────────────────────────────────────────
    cardsPanel = new JPanel();
    cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
    cardsPanel.setBackground(BG_DARK);

    JScrollPane scrollPane = new JScrollPane(cardsPanel);
    scrollPane.setBorder(new EmptyBorder(10, 10, 10, 10));
    scrollPane.setBackground(BG_DARK);
    scrollPane.getViewport().setBackground(BG_DARK);
    scrollPane.getVerticalScrollBar().setUnitIncrement(16);
    add(scrollPane, BorderLayout.CENTER);

    // ── Bottom Action Deck ───────────────────────────────────────────────────
    JPanel bottomPanel = new JPanel(new BorderLayout());
    bottomPanel.setBackground(PANEL_DARK);
    bottomPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2a, 0x2a, 0x30)),
            new EmptyBorder(12, 16, 12, 16)));

    JPanel selectionControls = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
    selectionControls.setOpaque(false);

    JButton allBtn = new JButton("SELECT ALL");
    styleButton(allBtn, new Color(0x2a, 0x2a, 0x32), TEXT_WHITE);
    allBtn.addActionListener(e -> setAllSelected(true));

    JButton noneBtn = new JButton("SELECT NONE");
    styleButton(noneBtn, new Color(0x2a, 0x2a, 0x32), TEXT_WHITE);
    noneBtn.addActionListener(e -> setAllSelected(false));

    selectionControls.add(allBtn);
    selectionControls.add(noneBtn);

    JPanel actionButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    actionButtons.setOpaque(false);

    JButton cancelBtn = new JButton("CANCEL");
    styleButton(cancelBtn, new Color(0x3a, 0x1a, 0x1a), new Color(0xff, 0x88, 0x88));
    cancelBtn.addActionListener(e -> dispose());

    JButton importBtn = new JButton("IMPORT INTO WORKSTATION");
    styleButton(importBtn, new Color(0x1a, 0x3a, 0x2a), GLOW_CYAN);
    importBtn.addActionListener(e -> executeImport());

    actionButtons.add(cancelBtn);
    actionButtons.add(importBtn);

    bottomPanel.add(selectionControls, BorderLayout.WEST);
    bottomPanel.add(actionButtons, BorderLayout.EAST);
    add(bottomPanel, BorderLayout.SOUTH);
  }

  private void styleButton(JButton btn, Color bg, Color fg) {
    btn.setFocusable(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, 11));
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.brighter(), 1),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
    btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
  }

  private void loadMidiMetadata() {
    try {
      trackConfigs = MidiToProjectCompiler.parseMidiMetadata(midiFile);
      rebuildCardsDeck();
    } catch (Exception e) {
      JOptionPane.showMessageDialog(
          this,
          "Failed to parse MIDI file:\n" + e.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
      dispose();
    }
  }

  private void rebuildCardsDeck() {
    cardsPanel.removeAll();
    for (TrackImportConfig config : trackConfigs) {
      cardsPanel.add(createTrackConfigCard(config));
      cardsPanel.add(Box.createRigidArea(new Dimension(0, 10)));
    }
    cardsPanel.revalidate();
    cardsPanel.repaint();
  }

  private JPanel createTrackConfigCard(TrackImportConfig config) {
    JPanel card = new JPanel(new GridBagLayout());
    card.setBackground(PANEL_DARK);
    card.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x2a, 0x2a, 0x30), 1),
            new EmptyBorder(12, 16, 12, 16)));

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.fill = GridBagConstraints.BOTH;
    gbc.anchor = GridBagConstraints.WEST;
    gbc.insets = new Insets(4, 4, 4, 4);

    // ── Column 1: Import Checkbox & Name ──
    gbc.gridx = 0;
    gbc.gridy = 0;
    gbc.weightx = 0.35;
    JCheckBox importCb = new JCheckBox(config.trackName);
    importCb.setFont(new Font("SansSerif", Font.BOLD, 13));
    importCb.setForeground(TEXT_WHITE);
    importCb.setSelected(config.importEnabled);
    importCb.setOpaque(false);
    importCb.addActionListener(
        e -> {
          config.importEnabled = importCb.isSelected();
        });
    card.add(importCb, gbc);

    // ── Column 2: Mapped Preset Combo ──
    gbc.gridx = 1;
    gbc.weightx = 0.25;
    JComboBox<String> presetCombo = new JComboBox<>(availablePresets.toArray(new String[0]));
    presetCombo.setBackground(BG_DARK);
    presetCombo.setForeground(TEXT_WHITE);
    presetCombo.setSelectedItem(config.mappedPresetName);
    presetCombo.addActionListener(
        e -> {
          config.mappedPresetName = (String) presetCombo.getSelectedItem();
        });
    card.add(presetCombo, gbc);

    // ── Column 3: Color Palette Selection ──
    gbc.gridx = 2;
    gbc.weightx = 0.15;
    String[] colorNames = MidiToProjectCompiler.COLOR_PALETTE.keySet().toArray(new String[0]);
    JComboBox<String> colorCombo = new JComboBox<>(colorNames);
    colorCombo.setBackground(BG_DARK);
    colorCombo.setForeground(TEXT_WHITE);

    // Find current color name
    String defaultName = "Cyan";
    for (Map.Entry<String, String> entry : MidiToProjectCompiler.COLOR_PALETTE.entrySet()) {
      if (entry.getValue().equalsIgnoreCase(config.colorHex)) {
        defaultName = entry.getKey();
        break;
      }
    }
    colorCombo.setSelectedItem(defaultName);
    colorCombo.addActionListener(
        e -> {
          String selectedName = (String) colorCombo.getSelectedItem();
          config.colorHex = MidiToProjectCompiler.COLOR_PALETTE.get(selectedName);
        });
    card.add(colorCombo, gbc);

    // ── Column 4: Pitch Splitter Panel ──
    gbc.gridx = 3;
    gbc.weightx = 0.25;
    JPanel splitterPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
    splitterPanel.setOpaque(false);

    JCheckBox splitCb = new JCheckBox("Split Bass");
    splitCb.setFont(new Font("SansSerif", Font.PLAIN, 11));
    splitCb.setForeground(TEXT_MUTED);
    splitCb.setSelected(config.splitEnabled);
    splitCb.setOpaque(false);

    // Expandable Split settings
    JPanel splitSettings = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
    splitSettings.setOpaque(false);
    splitSettings.setVisible(config.splitEnabled);

    JSpinner splitPointSpinner = new JSpinner(new SpinnerNumberModel(60, 21, 108, 1));
    splitPointSpinner.setPreferredSize(new Dimension(48, 22));
    splitPointSpinner.setValue(config.splitPoint);
    splitPointSpinner.addChangeListener(
        e -> {
          config.splitPoint = (Integer) splitPointSpinner.getValue();
        });

    JComboBox<String> splitPresetCombo = new JComboBox<>(availablePresets.toArray(new String[0]));
    splitPresetCombo.setBackground(BG_DARK);
    splitPresetCombo.setForeground(TEXT_WHITE);
    splitPresetCombo.setSelectedItem(config.splitPresetName);
    splitPresetCombo.addActionListener(
        e -> {
          config.splitPresetName = (String) splitPresetCombo.getSelectedItem();
        });

    splitSettings.add(new JLabel("at"));
    splitSettings.add(splitPointSpinner);
    splitSettings.add(splitPresetCombo);

    splitCb.addActionListener(
        e -> {
          config.splitEnabled = splitCb.isSelected();
          splitSettings.setVisible(splitCb.isSelected());
          card.revalidate();
        });

    splitterPanel.add(splitCb);
    splitterPanel.add(splitSettings);
    card.add(splitterPanel, gbc);

    return card;
  }

  private void setAllSelected(boolean val) {
    for (TrackImportConfig c : trackConfigs) {
      c.importEnabled = val;
    }
    rebuildCardsDeck();
  }

  private void executeImport() {
    boolean anySelected = false;
    for (TrackImportConfig c : trackConfigs) {
      if (c.importEnabled) {
        anySelected = true;
        break;
      }
    }

    if (!anySelected) {
      JOptionPane.showMessageDialog(
          this,
          "Please select at least one track to import.",
          "No Tracks Selected",
          JOptionPane.WARNING_MESSAGE);
      return;
    }

    try {
      compiledProject = MidiToProjectCompiler.compileMidi(midiFile, trackConfigs);
      importSuccessful = true;
      dispose();
    } catch (Exception e) {
      JOptionPane.showMessageDialog(
          this,
          "Compilation failed:\n" + e.getMessage(),
          "Import Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  public boolean isImportSuccessful() {
    return importSuccessful;
  }

  public ProjectModel getCompiledProject() {
    return compiledProject;
  }
}
