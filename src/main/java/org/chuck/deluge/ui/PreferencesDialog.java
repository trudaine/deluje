package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.chuck.deluge.project.PreferencesManager;

/**
 * Modal preferences dialog for Audio, MIDI, Sequencer, and Interface System settings.
 * Programmatically built with high-fidelity modern dark-neon Swing components, keeping layout
 * compact, elegant, and highly professional (fully replacing legacy NetBeans forms).
 */
public class PreferencesDialog extends JDialog {

  private static final java.util.logging.Logger LOG =
      java.util.logging.Logger.getLogger(PreferencesDialog.class.getName());

  // Luxury Dark-Neon Design System Palette
  private static final Color BG_DARK = new Color(0x12, 0x12, 0x14); // Deep slate background
  private static final Color BG_CARD = new Color(0x1a, 0x1a, 0x1e); // Compact slate containers
  private static final Color TEXT_LIGHT = new Color(0xe2, 0xe2, 0xe8); // Silver-gray text
  private static final Color TEXT_DIM = new Color(0x8a, 0x8a, 0x98); // Secondary sub-text
  private static final Color ACCENT_GREEN = new Color(0x00, 0xe6, 0x76); // Neon green active accent
  private static final Color ACCENT_CYAN = new Color(0x00, 0xb0, 0xff); // Neon cyan target preview
  private static final Color BORDER_COLOR = new Color(0x2d, 0x2d, 0x35); // Thin structural frame

  private final Runnable onGridModeChanged;
  private final Runnable onLibraryChanged;
  private final DefaultListModel<String> listModel = new DefaultListModel<>();

  // Retain identical field references for perfect caller compatibility
  private JComboBox<String> reverbCombo;
  private JCheckBox masterSatCheck;
  private JCheckBox filterDriveCheck;
  private JCheckBox bitCrunchCheck;
  private JCheckBox visCheck;
  private JCheckBox debugCheck;
  private JCheckBox gridModeCheck;
  private JCheckBox tooltipCheck;
  private JComboBox<String> screenResCombo;
  private JComboBox<String> gridModeCombo;
  private JComboBox<String> engineCombo;
  private JComboBox<String> midiCombo;
  private JCheckBox advancedGridStyleCheck;
  private JComboBox<String> interactionModeCombo;
  private JComboBox<String> displayTypeCombo;
  private JLabel dirLabel;
  private JList<String> mappingList;
  private JButton browseBtn;
  private JButton saveBtn;
  private JButton cancelBtn;

  public PreferencesDialog(
      java.awt.Frame owner, Runnable onGridModeChanged, Runnable onLibraryChanged) {
    super(owner, "Preferences", true);
    this.onGridModeChanged = onGridModeChanged;
    this.onLibraryChanged = onLibraryChanged;

    initComponentsProgrammatic();
    loadCurrentPreferences();

    setSize(640, 560);
    setMinimumSize(new Dimension(580, 500));
    setLocationRelativeTo(owner);
  }

  private void initComponentsProgrammatic() {
    // 1. Root Container Style
    JPanel mainContainer = new JPanel(new BorderLayout(0, 10));
    mainContainer.setBackground(BG_DARK);
    mainContainer.setBorder(new EmptyBorder(15, 15, 12, 15));
    setContentPane(mainContainer);

    // 2. Header Panel with Glowing Neon Accent Line
    JPanel headerPanel =
        new JPanel() {
          @Override
          protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            g.setColor(BORDER_COLOR);
            g.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            g.setColor(ACCENT_GREEN);
            g.fillRect(0, getHeight() - 2, 80, 2);
          }
        };
    headerPanel.setOpaque(false);
    headerPanel.setLayout(new BorderLayout(0, 2));
    headerPanel.setPreferredSize(new Dimension(100, 36));

    JLabel titleLabel = new JLabel("WORKSTATION PREFERENCES");
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
    titleLabel.setForeground(TEXT_LIGHT);
    headerPanel.add(titleLabel, BorderLayout.WEST);
    mainContainer.add(headerPanel, BorderLayout.NORTH);

    // 3. Tabbed Pane setup
    JTabbedPane tabPane = new JTabbedPane();
    tabPane.setBackground(BG_DARK);
    tabPane.setForeground(TEXT_LIGHT);
    tabPane.setFont(new Font("SansSerif", Font.BOLD, 11));
    tabPane.setBorder(BorderFactory.createEmptyBorder());

    // Tab panels setup
    tabPane.addTab("AUDIO / DSP", buildAudioPanel());
    tabPane.addTab("MIDI SETTINGS", buildMidiPanel());
    tabPane.addTab("SEQUENCER", buildSequencerPanel());
    tabPane.addTab("SYSTEM / INTERFACE", buildSystemPanel());

    mainContainer.add(tabPane, BorderLayout.CENTER);

    // 4. Bottom Actions Control Panel
    JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    actionsPanel.setOpaque(false);

    cancelBtn = new JButton("Cancel");
    styleButton(cancelBtn, new Color(0x3e, 0x3e, 0x42), TEXT_LIGHT);
    cancelBtn.addActionListener(e -> dispose());

    saveBtn = new JButton("Save Preferences");
    styleButton(saveBtn, ACCENT_GREEN, new Color(0x0c, 0x38, 0x1f));
    saveBtn.addActionListener(this::saveBtnActionPerformed);

    actionsPanel.add(cancelBtn);
    actionsPanel.add(saveBtn);
    mainContainer.add(actionsPanel, BorderLayout.SOUTH);
  }

  // --- TAB BUILDERS ---

  private JPanel buildAudioPanel() {
    JPanel panel = createTabContainer();
    GridBagConstraints c = createGridConstraints();

    reverbCombo =
        new JComboBox<>(
            new String[] {"JCRev", "FreeVerb", "MVerb", "ProceduralReverb", "RingsReverb"});
    styleComboBox(reverbCombo);
    addField(panel, "Reverb Model", reverbCombo, "Algorithmic hardware emulation bus.", c, 0);

    masterSatCheck = new JCheckBox("Enable Master Saturation Guard");
    styleCheckBox(masterSatCheck);
    addField(
        panel,
        "Master Saturation",
        masterSatCheck,
        "Summation dynamics safe-clipper engine.",
        c,
        1);

    filterDriveCheck = new JCheckBox("Enable Nonlinear Filter Drive");
    styleCheckBox(filterDriveCheck);
    addField(
        panel,
        "Filter Drive Boost",
        filterDriveCheck,
        "Warps state-variable filter charging loop feedback.",
        c,
        2);

    bitCrunchCheck = new JCheckBox("Enable Decimation Bit-Crusher");
    styleCheckBox(bitCrunchCheck);
    addField(
        panel,
        "Bit-Crusher DSP",
        bitCrunchCheck,
        "Applies real-time step resolution quantization.",
        c,
        3);

    debugCheck = new JCheckBox("Enable Raw Engine Audio Debug Logging");
    styleCheckBox(debugCheck);
    addField(
        panel,
        "DSP Diagnostics",
        debugCheck,
        "Spits continuous sample rate buffer events stdout.",
        c,
        4);

    return panel;
  }

  private JPanel buildMidiPanel() {
    JPanel panel = createTabContainer();
    GridBagConstraints c = createGridConstraints();

    midiCombo = new JComboBox<>();
    styleComboBox(midiCombo);
    addField(
        panel,
        "MIDI Input Device",
        midiCombo,
        "Select incoming hardware MIDI controller interface.",
        c,
        0);

    gridModeCheck = new JCheckBox("Enable MIDI Pad Controller Mode");
    styleCheckBox(gridModeCheck);
    addField(
        panel,
        "MIDI Grid Mode",
        gridModeCheck,
        "Incoming note numbers map to grid matrix step coordinates.",
        c,
        1);

    mappingList = new JList<>(listModel);
    mappingList.setBackground(BG_DARK);
    mappingList.setForeground(TEXT_LIGHT);
    mappingList.setFont(new Font("Monospaced", Font.PLAIN, 11));
    mappingList.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));

    JScrollPane scroll = new JScrollPane(mappingList);
    scroll.setPreferredSize(new Dimension(100, 130));
    scroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
    addField(
        panel,
        "Active MIDI CC Mappings",
        scroll,
        "Current dynamically learned control bindings.",
        c,
        2);

    return panel;
  }

  private JPanel buildSequencerPanel() {
    JPanel panel = createTabContainer();
    GridBagConstraints c = createGridConstraints();

    engineCombo = new JComboBox<>(new String[] {"HIGH_FIDELITY", "LEGACY"});
    styleComboBox(engineCombo);
    addField(
        panel, "Sequencer Engine", engineCombo, "Switch step tick timing accuracy loops.", c, 0);

    gridModeCombo =
        new JComboBox<>(new String[] {"GRID_8x16", "GRID_16x16", "GRID_24x16", "GRID_16x24"});
    styleComboBox(gridModeCombo);
    addField(
        panel,
        "Viewport Grid Mode",
        gridModeCombo,
        "Layout size boundaries visible inside active grid panel.",
        c,
        1);

    advancedGridStyleCheck = new JCheckBox("Enable High-Fidelity Advanced Style UI");
    styleCheckBox(advancedGridStyleCheck);
    addField(
        panel,
        "Advanced Grid UI",
        advancedGridStyleCheck,
        "Paints backlit color button indicators with notes tags.",
        c,
        2);

    interactionModeCombo =
        new JComboBox<>(new String[] {"Desktop Slider Popup", "Hardware Rotary Dial SELECT"});
    styleComboBox(interactionModeCombo);
    addField(
        panel,
        "Shift Interactions Mode",
        interactionModeCombo,
        "Controls how parameters modify on pad click hold.",
        c,
        3);

    displayTypeCombo =
        new JComboBox<>(
            new String[] {"Show Both Screens", "OLED Screen Only", "Classic LED Screen Only"});
    styleComboBox(displayTypeCombo);
    addField(
        panel,
        "Screen Display Style",
        displayTypeCombo,
        "Gates digital hardware segment indicators layouts.",
        c,
        4);

    return panel;
  }

  private JPanel buildSystemPanel() {
    JPanel panel = createTabContainer();
    GridBagConstraints c = createGridConstraints();

    visCheck = new JCheckBox("Enable GPU-Accelerated Real-time Scopes Visualizers");
    styleCheckBox(visCheck);
    addField(
        panel,
        "Waveform Visualizers",
        visCheck,
        "Calculates active FFT spectrum scopes stack at 60fps.",
        c,
        0);

    tooltipCheck = new JCheckBox("Show Live Hover Context Tooltips");
    styleCheckBox(tooltipCheck);
    addField(
        panel,
        "Context Tooltips",
        tooltipCheck,
        "Summons quick guidelines panels under cursor clicks.",
        c,
        1);

    screenResCombo = new JComboBox<>(new String[] {"QHD", "FHD", "Retina", "Default"});
    styleComboBox(screenResCombo);
    addField(
        panel,
        "Desktop UI Resolution",
        screenResCombo,
        "Desktop dimensions grid proportion scaling profiles.",
        c,
        2);

    // SD Card directory setup
    JPanel dirPanel = new JPanel(new BorderLayout(8, 0));
    dirPanel.setOpaque(false);

    dirLabel = new JLabel("(not set)");
    dirLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
    dirLabel.setForeground(TEXT_LIGHT);
    dirLabel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_COLOR, 1),
            BorderFactory.createEmptyBorder(4, 6, 4, 6)));

    browseBtn = new JButton("Browse...");
    styleButton(browseBtn, new Color(0x2a, 0x2a, 0x30), TEXT_LIGHT);
    browseBtn.setFont(new Font("SansSerif", Font.PLAIN, 11));
    browseBtn.addActionListener(this::browseBtnActionPerformed);

    dirPanel.add(dirLabel, BorderLayout.CENTER);
    dirPanel.add(browseBtn, BorderLayout.EAST);

    addField(
        panel,
        "SD Card Root Directory",
        dirPanel,
        "Target folder to load KITS / SYNTHS / SONGS assets.",
        c,
        3);

    return panel;
  }

  // --- STYLING HELPER UTILITIES ---

  private JPanel createTabContainer() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(BG_CARD);
    panel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_COLOR, 1),
            BorderFactory.createEmptyBorder(12, 12, 12, 12)));
    return panel;
  }

  private GridBagConstraints createGridConstraints() {
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(6, 6, 6, 6);
    c.fill = GridBagConstraints.HORIZONTAL;
    return c;
  }

  private void addField(
      JPanel panel,
      String labelText,
      Component comp,
      String descText,
      GridBagConstraints c,
      int row) {
    // Label Setup
    JLabel label = new JLabel(labelText);
    label.setFont(new Font("SansSerif", Font.BOLD, 12));
    label.setForeground(TEXT_LIGHT);

    c.gridy = row * 2;
    c.gridx = 0;
    c.weightx = 0.3;
    c.gridwidth = 1;
    panel.add(label, c);

    // Component Setup
    c.gridx = 1;
    c.weightx = 0.7;
    panel.add(comp, c);

    // Sub-text Description Setup
    if (descText != null && !descText.isEmpty()) {
      JLabel desc = new JLabel(descText);
      desc.setFont(new Font("SansSerif", Font.PLAIN, 10));
      desc.setForeground(TEXT_DIM);

      c.gridy = row * 2 + 1;
      c.gridx = 1;
      c.weightx = 0.7;
      c.insets = new Insets(0, 6, 8, 6);
      panel.add(desc, c);
    }

    // Reset insets
    c.insets = new Insets(6, 6, 6, 6);
  }

  private void styleComboBox(JComboBox<?> combo) {
    combo.setBackground(BG_DARK);
    combo.setForeground(TEXT_LIGHT);
    combo.setFont(new Font("SansSerif", Font.PLAIN, 12));
    combo.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_COLOR, 1),
            BorderFactory.createEmptyBorder(2, 2, 2, 2)));

    // Customize inner drop arrow button renderer list if applicable
    combo.setFocusable(false);
  }

  private void styleCheckBox(JCheckBox check) {
    check.setOpaque(false);
    check.setForeground(TEXT_LIGHT);
    check.setFont(new Font("SansSerif", Font.PLAIN, 12));
    check.setFocusPainted(false);
  }

  private void styleButton(JButton btn, Color bg, Color fg) {
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, 12));
    btn.setFocusPainted(false);
    btn.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(bg.darker(), 1),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
    btn.setCursor(new Cursor(Cursor.HAND_CURSOR));

    // Dynamic Hover Feedback Micro-Animations
    btn.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mouseEntered(java.awt.event.MouseEvent e) {
            btn.setBackground(bg.brighter());
          }

          @Override
          public void mouseExited(java.awt.event.MouseEvent e) {
            btn.setBackground(bg);
          }
        });
  }

  // --- ACTIONS & BUSINESS LOGIC PARSERS ---

  private void loadCurrentPreferences() {
    reverbCombo.setSelectedItem(PreferencesManager.get("reverb.model", "JCRev"));
    masterSatCheck.setSelected(PreferencesManager.isMasterSaturationEnabled());
    filterDriveCheck.setSelected(PreferencesManager.isFilterDriveEnabled());
    bitCrunchCheck.setSelected(PreferencesManager.isBitCrunchEnabled());
    visCheck.setSelected(Boolean.parseBoolean(PreferencesManager.get("show.visualizers", "true")));
    debugCheck.setSelected(Boolean.parseBoolean(PreferencesManager.get("debug.audio", "false")));
    gridModeCheck.setSelected(
        Boolean.parseBoolean(PreferencesManager.get("midi.grid.mode", "false")));
    tooltipCheck.setSelected(Boolean.parseBoolean(PreferencesManager.get("show.tooltips", "true")));
    screenResCombo.setSelectedItem(PreferencesManager.get("screen.resolution", "QHD"));
    gridModeCombo.setSelectedItem(PreferencesManager.getGridMode().name());
    engineCombo.setSelectedItem(PreferencesManager.getSequencerEngine().name());

    advancedGridStyleCheck.setSelected(
        PreferencesManager.getGridPanelType() == PreferencesManager.GridPanelType.ADVANCED);

    if (PreferencesManager.getShiftInteractionMode()
        == PreferencesManager.ShiftInteractionMode.POPUP_SLIDER) {
      interactionModeCombo.setSelectedIndex(0);
    } else {
      interactionModeCombo.setSelectedIndex(1);
    }

    PreferencesManager.DisplayType dt = PreferencesManager.getDisplayType();
    if (dt == PreferencesManager.DisplayType.BOTH) {
      displayTypeCombo.setSelectedIndex(0);
    } else if (dt == PreferencesManager.DisplayType.OLED_ONLY) {
      displayTypeCombo.setSelectedIndex(1);
    } else {
      displayTypeCombo.setSelectedIndex(2);
    }

    // MIDI input ports
    String[] ports = org.chuck.midi.MidiIn.list();
    midiCombo.removeAllItems();
    midiCombo.addItem("None");
    for (String p : ports) midiCombo.addItem(p);
    midiCombo.setSelectedItem(PreferencesManager.get("midi.input", "None"));

    // SD Card root directory
    String libDir = PreferencesManager.getLibraryDir().getAbsolutePath();
    dirLabel.setText(libDir != null ? libDir : "(not set)");
  }

  public void setMappings(java.util.Map<String, Integer> mappings) {
    listModel.clear();
    if (mappings != null) {
      for (java.util.Map.Entry<String, Integer> entry : mappings.entrySet()) {
        listModel.addElement(entry.getKey() + " -> CC " + entry.getValue());
      }
    }
  }

  private void browseBtnActionPerformed(java.awt.event.ActionEvent evt) {
    JFileChooser chooser = new JFileChooser(PreferencesManager.getLibraryDir());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      String path = chooser.getSelectedFile().getAbsolutePath();
      PreferencesManager.setLibraryDir(path);
      dirLabel.setText(path);
      if (onLibraryChanged != null) onLibraryChanged.run();
    }
  }

  private void saveBtnActionPerformed(java.awt.event.ActionEvent evt) {
    String oldRes = PreferencesManager.get("screen.resolution", "");
    String newRes = (String) screenResCombo.getSelectedItem();

    PreferencesManager.set("reverb.model", (String) reverbCombo.getSelectedItem());
    PreferencesManager.setMasterSaturationEnabled(masterSatCheck.isSelected());
    PreferencesManager.setFilterDriveEnabled(filterDriveCheck.isSelected());
    PreferencesManager.setBitCrunchEnabled(bitCrunchCheck.isSelected());
    PreferencesManager.set("midi.input", (String) midiCombo.getSelectedItem());
    PreferencesManager.set("show.visualizers", String.valueOf(visCheck.isSelected()));
    PreferencesManager.set("debug.audio", String.valueOf(debugCheck.isSelected()));
    PreferencesManager.set("midi.grid.mode", String.valueOf(gridModeCheck.isSelected()));
    PreferencesManager.set("show.tooltips", String.valueOf(tooltipCheck.isSelected()));
    PreferencesManager.set("screen.resolution", newRes);

    PreferencesManager.GridMode selectedMode =
        PreferencesManager.GridMode.fromString((String) gridModeCombo.getSelectedItem());
    PreferencesManager.setGridMode(selectedMode);

    PreferencesManager.SequencerEngine selectedEngine =
        PreferencesManager.SequencerEngine.fromString((String) engineCombo.getSelectedItem());
    PreferencesManager.setSequencerEngine(selectedEngine);

    PreferencesManager.GridPanelType panelType =
        advancedGridStyleCheck.isSelected()
            ? PreferencesManager.GridPanelType.ADVANCED
            : PreferencesManager.GridPanelType.LEGACY;
    PreferencesManager.setGridPanelType(panelType);

    PreferencesManager.setShiftInteractionMode(
        interactionModeCombo.getSelectedIndex() == 0
            ? PreferencesManager.ShiftInteractionMode.POPUP_SLIDER
            : PreferencesManager.ShiftInteractionMode.ROTARY_ENCODER);

    PreferencesManager.DisplayType dt = PreferencesManager.DisplayType.BOTH;
    if (displayTypeCombo.getSelectedIndex() == 1) dt = PreferencesManager.DisplayType.OLED_ONLY;
    else if (displayTypeCombo.getSelectedIndex() == 2) dt = PreferencesManager.DisplayType.LED_ONLY;
    PreferencesManager.setDisplayType(dt);

    if (SwingDelugeApp.mainInstance != null && SwingDelugeApp.mainInstance.getTopBar() != null) {
      SwingDelugeApp.mainInstance.getTopBar().applyDisplayPreferences();
    }

    if (onGridModeChanged != null) onGridModeChanged.run();
    if (onLibraryChanged != null) onLibraryChanged.run();

    dispose();
    if (!newRes.equals(oldRes)) {
      JOptionPane.showMessageDialog(
          this,
          "Screen proportions applied! Please restart application to fully engage desktop scaling docks.");
    }
  }
}
