package org.deluge.ui;

import java.awt.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import org.deluge.model.tuning.ScalaScale;
import org.deluge.model.tuning.ScalaScaleParser;
import org.deluge.project.PreferencesManager;

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
  private JComboBox<String> monitorGainCombo;
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
  private JComboBox<String> syncModeCombo;
  private JComboBox<String> midiCombo;
  private javax.swing.Timer portScanTimer;
  private boolean isRebuildingCombo = false;
  private final java.util.Map<String, String> friendlyToRawMidi = new java.util.HashMap<>();
  private final java.util.Map<String, String> rawToFriendlyMidi = new java.util.HashMap<>();
  private JCheckBox advancedGridStyleCheck;
  private JComboBox<String> interactionModeCombo;
  private JComboBox<String> displayTypeCombo;
  private JLabel dirLabel;
  private JList<String> mappingList;
  private JButton browseBtn;
  private JButton saveBtn;
  private JButton cancelBtn;

  private JTextField scalaPathField;
  private JButton scalaBrowseBtn;
  private JButton scalaClearBtn;

  private JTabbedPane tabPane;
  private final org.deluge.midi.MidiService midiService;
  private JTable mappingTable;
  private JTextField learnParamField;
  private JButton learnBtn;
  private JLabel learnStatus;
  private JComboBox<org.deluge.midi.MidiDeviceDefinition> deviceCombo;
  private JCheckBox followEnable;
  private JComboBox<String>[] chCombos;
  private JComboBox<String>[] trCombos;

  public PreferencesDialog(
      java.awt.Frame owner,
      org.deluge.midi.MidiService midiService,
      Runnable onGridModeChanged,
      Runnable onLibraryChanged) {
    super(owner, "Preferences", true);
    this.midiService = midiService;
    this.onGridModeChanged = onGridModeChanged;
    this.onLibraryChanged = onLibraryChanged;

    initComponentsProgrammatic();
    loadCurrentPreferences();

    setSize(660, 600);
    setMinimumSize(new Dimension(580, 500));
    setLocationRelativeTo(owner);

    // Start background port-scanning timer for dynamic hot-plug support
    portScanTimer = new javax.swing.Timer(2000, e -> updateMidiPortsListDynamic());
    portScanTimer.start();

    // Clean up timer when dialog is closed
    addWindowListener(
        new java.awt.event.WindowAdapter() {
          @Override
          public void windowClosed(java.awt.event.WindowEvent e) {
            if (portScanTimer != null) {
              portScanTimer.stop();
            }
          }
        });
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
    tabPane = new JTabbedPane();
    tabPane.setBackground(BG_DARK);
    tabPane.setForeground(TEXT_LIGHT);
    tabPane.setFont(new Font("SansSerif", Font.BOLD, 11));
    tabPane.setBorder(BorderFactory.createEmptyBorder());

    // Tab panels setup
    tabPane.addTab("AUDIO / DSP", wrapInScrollPane(buildAudioPanel()));
    tabPane.addTab("MIDI SETTINGS", wrapInScrollPane(buildMidiPanel()));
    tabPane.addTab("SEQUENCER", wrapInScrollPane(buildSequencerPanel()));
    tabPane.addTab("SYSTEM / INTERFACE", wrapInScrollPane(buildSystemPanel()));

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
    DarkComboBoxRenderer.styleComponentTree(this);
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

    // Only clean values are offered: the output limiter starts hard-clipping above ~12x (a single
    // full-volume note already railed at the old 24x default — that was the "garbage" distortion).
    monitorGainCombo =
        new JComboBox<>(
            new String[] {
              "6x (Quiet)",
              "12x",
              "18x",
              "24x (Default)",
              "32x",
              "48x (Warm Sat)",
              "64x (Analog Tape)",
              "96x (Fat & Loud)",
              "128x (Extreme)"
            });
    styleComboBox(monitorGainCombo);
    addField(
        panel,
        "Desktop Volume Boost",
        monitorGainCombo,
        "Applies post-engine scaling for desktop output.",
        c,
        4);

    debugCheck = new JCheckBox("Enable Raw Engine Audio Debug Logging");
    styleCheckBox(debugCheck);
    addField(
        panel,
        "DSP Diagnostics",
        debugCheck,
        "Spits continuous sample rate buffer events stdout.",
        c,
        5);

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

    java.util.List<org.deluge.midi.MidiDeviceDefinition> devices =
        org.deluge.midi.MidiDeviceDefinitionLoader.loadAll();
    deviceCombo = new JComboBox<>();
    deviceCombo.addItem(null); // None
    for (var d : devices) {
      deviceCombo.addItem(d);
    }
    styleComboBox(deviceCombo);
    deviceCombo.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value == null) {
              setText("— None —");
            } else if (value instanceof org.deluge.midi.MidiDeviceDefinition d) {
              setText(d.getName() != null ? d.getName() : d.getId());
            }
            return this;
          }
        });
    deviceCombo.addActionListener(
        e -> {
          if (midiService != null) {
            org.deluge.midi.MidiDeviceDefinition selected =
                (org.deluge.midi.MidiDeviceDefinition) deviceCombo.getSelectedItem();
            midiService.setDeviceDefinition(selected);
            rebuildTableContent();
          }
        });
    addField(
        panel,
        "Device Profile Map",
        deviceCombo,
        "Load standard parameter mappings definition for specific keyboards.",
        c,
        1);

    gridModeCheck = new JCheckBox("Enable MIDI Pad Controller Mode");
    styleCheckBox(gridModeCheck);
    addField(
        panel,
        "MIDI Grid Mode",
        gridModeCheck,
        "Incoming note numbers map to grid matrix step coordinates.",
        c,
        2);

    // CC Mappings Table Section
    mappingTable = new JTable();
    styleTable(mappingTable);
    rebuildTableContent();

    JScrollPane tableScroll = new JScrollPane(mappingTable);
    tableScroll.setPreferredSize(new Dimension(100, 150));
    tableScroll.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
    tableScroll.setBackground(BG_DARK);
    tableScroll.getViewport().setBackground(BG_DARK);
    addField(
        panel,
        "Active CC Mappings",
        tableScroll,
        "Table showing JNI parameter variable and mapped MIDI CC.",
        c,
        3);

    // Learn Controls
    JPanel learnSection = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
    learnSection.setOpaque(false);

    learnParamField = new JTextField(15);
    learnParamField.setBackground(BG_DARK);
    learnParamField.setForeground(TEXT_LIGHT);
    learnParamField.setCaretColor(Color.WHITE);
    learnParamField.setFont(new Font("SansSerif", Font.PLAIN, 11));
    learnParamField.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BORDER_COLOR, 1),
            BorderFactory.createEmptyBorder(2, 4, 2, 4)));
    learnParamField.setToolTipText("Enter target parameter name (e.g. g_master_vol)");
    learnSection.add(learnParamField);

    learnBtn = new JButton("START LEARN");
    styleOutlineButton(learnBtn, new Color(0x1a, 0x24, 0x22), ACCENT_GREEN);
    learnSection.add(learnBtn);

    learnStatus = new JLabel("");
    learnStatus.setForeground(new Color(0xff, 0xcc, 0x00));
    learnStatus.setFont(new Font("SansSerif", Font.PLAIN, 10));
    learnSection.add(learnStatus);

    learnBtn.addActionListener(
        e -> {
          if (midiService == null) return;
          String param = learnParamField.getText().trim();
          if (param.isEmpty()) {
            learnStatus.setText("Enter target parameter name");
            return;
          }
          midiService.startLearn(param);
          learnStatus.setText("Sweeping CC knobs...");
          learnBtn.setEnabled(false);

          Timer timer =
              new Timer(
                  10000,
                  ev -> {
                    learnBtn.setEnabled(true);
                    if (midiService.isLearning()) {
                      midiService.cancelLearn();
                      learnStatus.setText("Learn timed out");
                    } else {
                      learnStatus.setText("Learned successfully!");
                      rebuildTableContent();
                    }
                  });
          timer.setRepeats(false);
          timer.start();
        });

    addField(
        panel,
        "MIDI CC Learn Controller",
        learnSection,
        "Sweep dynamic CC control values to automatically learn mappings.",
        c,
        4);

    // Follow Mode Config
    JPanel followPanel = buildFollowPanel();
    addField(
        panel,
        "MIDI Follow Mode",
        followPanel,
        "Assign MIDI channels to target playback track lines.",
        c,
        5);

    return panel;
  }

  private JPanel buildFollowPanel() {
    JPanel followPanel = new JPanel();
    followPanel.setLayout(new BoxLayout(followPanel, BoxLayout.Y_AXIS));
    followPanel.setOpaque(false);
    followPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(BORDER_COLOR),
            "MIDI Follow Mode Configuration",
            javax.swing.border.TitledBorder.LEFT,
            javax.swing.border.TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 10),
            TEXT_LIGHT));

    followEnable = new JCheckBox("Enable Track Follow Modes");
    styleCheckBox(followEnable);
    followEnable.setSelected(PreferencesManager.get("midi.follow.enabled", "true").equals("true"));
    followEnable.addActionListener(
        e -> {
          PreferencesManager.set("midi.follow.enabled", String.valueOf(followEnable.isSelected()));
        });
    followPanel.add(followEnable);
    followPanel.add(Box.createVerticalStrut(6));

    String[] midiChannels = {
      "1", "2", "3", "4", "5", "6", "7", "8",
      "9", "10", "11", "12", "13", "14", "15", "16"
    };
    String[] trackLabels = {
      "Track 1", "Track 2", "Track 3", "Track 4", "Track 5", "Track 6", "Track 7", "Track 8",
      "Track 9", "Track 10", "Track 11", "Track 12", "Track 13", "Track 14", "Track 15", "Track 16"
    };
    char[] followLabels = {'A', 'B', 'C'};

    chCombos = new JComboBox[3];
    trCombos = new JComboBox[3];

    for (int i = 0; i < 3; i++) {
      final char fLabel = followLabels[i];
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
      row.setOpaque(false);
      JLabel fl = new JLabel("Channel " + fLabel + ":");
      fl.setForeground(TEXT_LIGHT);
      fl.setFont(new Font("SansSerif", Font.BOLD, 10));

      JComboBox<String> chCombo = new JComboBox<>(midiChannels);
      int savedCh = Integer.parseInt(PreferencesManager.get("midi.follow.ch" + fLabel, "1"));
      chCombo.setSelectedIndex(savedCh - 1);
      styleComboBox(chCombo);
      chCombo.setPreferredSize(new Dimension(60, 22));
      chCombo.addActionListener(
          e -> {
            PreferencesManager.set(
                "midi.follow.ch" + fLabel, String.valueOf(chCombo.getSelectedIndex() + 1));
          });
      chCombos[i] = chCombo;

      JComboBox<String> trCombo = new JComboBox<>(trackLabels);
      int savedTr =
          Integer.parseInt(PreferencesManager.get("midi.follow.track" + fLabel, String.valueOf(i)));
      trCombo.setSelectedIndex(Math.min(savedTr, 15));
      styleComboBox(trCombo);
      trCombo.setPreferredSize(new Dimension(110, 22));
      trCombo.addActionListener(
          e -> {
            PreferencesManager.set(
                "midi.follow.track" + fLabel, String.valueOf(trCombo.getSelectedIndex()));
          });
      trCombos[i] = trCombo;

      JLabel midiChLbl = new JLabel("MIDI Ch:");
      midiChLbl.setForeground(TEXT_DIM);
      midiChLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
      JLabel trLbl = new JLabel("→ Track:");
      trLbl.setForeground(TEXT_DIM);
      trLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));

      row.add(fl);
      row.add(midiChLbl);
      row.add(chCombo);
      row.add(trLbl);
      row.add(trCombo);
      followPanel.add(row);
    }
    return followPanel;
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

    JPanel scalaSelectPanel = new JPanel(new BorderLayout(5, 0));
    scalaSelectPanel.setOpaque(false);

    scalaPathField = new JTextField();
    scalaPathField.setEditable(false);
    scalaPathField.setBackground(new Color(0x2d, 0x2d, 0x30));
    scalaPathField.setForeground(TEXT_LIGHT);
    scalaPathField.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1));
    scalaPathField.setFont(new Font("SansSerif", Font.PLAIN, 11));

    scalaBrowseBtn = new JButton("Browse SCL...");
    styleButton(scalaBrowseBtn, new Color(0x3e, 0x3e, 0x42), TEXT_LIGHT);
    scalaBrowseBtn.addActionListener(this::scalaBrowseBtnActionPerformed);

    scalaClearBtn = new JButton("Clear / 12-TET");
    styleButton(scalaClearBtn, new Color(0x6b, 0x24, 0x24), TEXT_LIGHT);
    scalaClearBtn.addActionListener(
        e -> {
          scalaPathField.setText("(no custom scale - 12-TET active)");
          PreferencesManager.set("scala.scale.path", "");
          ScalaScale.setActiveScale(null);
        });

    scalaSelectPanel.add(scalaPathField, BorderLayout.CENTER);

    JPanel btnSubPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
    btnSubPanel.setOpaque(false);
    btnSubPanel.add(scalaBrowseBtn);
    btnSubPanel.add(scalaClearBtn);
    scalaSelectPanel.add(btnSubPanel, BorderLayout.EAST);

    addField(
        panel,
        "Microtonal Tuning (Scala)",
        scalaSelectPanel,
        "Load standard Scala (.scl) microtonal cent/ratio scale templates.",
        c,
        5);

    syncModeCombo =
        new JComboBox<>(new String[] {"INTERNAL (DAC Clock)", "EXTERNAL (MIDI Clock Sync)"});
    styleComboBox(syncModeCombo);
    addField(
        panel,
        "Sequencer Sync Mode",
        syncModeCombo,
        "Determines if the playhead is driven by the DAC audio card or incoming MIDI clock ticks.",
        c,
        6);

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

    combo.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label =
                (JLabel)
                    super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
            label.setBackground(isSelected ? ACCENT_GREEN : BG_CARD);
            label.setForeground(isSelected ? new Color(0x0c, 0x38, 0x1f) : TEXT_LIGHT);
            label.setOpaque(true);
            return label;
          }
        });
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

  private JScrollPane wrapInScrollPane(JPanel panel) {
    JScrollPane scroll = new JScrollPane(panel);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    scroll.setBackground(BG_DARK);
    scroll.getViewport().setBackground(BG_DARK);
    scroll.getVerticalScrollBar().setUnitIncrement(12);
    return scroll;
  }

  private void styleOutlineButton(JButton btn, Color bg, Color fg) {
    btn.setContentAreaFilled(false);
    btn.setOpaque(true);
    btn.setFocusPainted(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setFont(new Font("SansSerif", Font.BOLD, 11));
    btn.setBorder(BorderFactory.createLineBorder(fg, 1));
    btn.setMargin(new Insets(3, 8, 3, 8));
  }

  private void styleTable(JTable table) {
    table.setBackground(BG_DARK);
    table.setForeground(TEXT_LIGHT);
    table.setFont(new Font("SansSerif", Font.PLAIN, 11));
    table.setRowHeight(20);
    table.setGridColor(BORDER_COLOR);
    table.getTableHeader().setBackground(BORDER_COLOR);
    table.getTableHeader().setForeground(TEXT_LIGHT);
    table.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
    table.setSelectionBackground(new Color(0x00, 0xff, 0xcc, 0x33));
    table.setSelectionForeground(Color.WHITE);
    table.setShowGrid(true);

    table.setDefaultRenderer(
        Object.class,
        new javax.swing.table.DefaultTableCellRenderer() {
          @Override
          public Component getTableCellRendererComponent(
              JTable t, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
            Component c =
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
            c.setBackground(BG_DARK);
            c.setForeground(TEXT_LIGHT);
            if (isSelected) {
              c.setBackground(t.getSelectionBackground());
              c.setForeground(t.getSelectionForeground());
            }
            return c;
          }
        });
  }

  private void rebuildTableContent() {
    if (midiService == null) return;
    java.util.Map<String, Integer> mappings = midiService.getMappings();
    String[][] tableData = new String[mappings.size()][3];
    int rowIdx = 0;
    for (var entry : mappings.entrySet()) {
      tableData[rowIdx][0] = entry.getKey();
      tableData[rowIdx][1] = "CC #" + entry.getValue();
      tableData[rowIdx][2] = "ACTIVE";
      rowIdx++;
    }
    if (tableData.length == 0) {
      tableData = new String[][] {{"— No midi mappings cabled —", "", ""}};
    }
    String[] cols = {"JNI Parameter Variable", "MIDI CC Signal", "Connection Status"};

    javax.swing.table.DefaultTableModel model =
        new javax.swing.table.DefaultTableModel(tableData, cols) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };
    mappingTable.setModel(model);
  }

  // --- ACTIONS & BUSINESS LOGIC PARSERS ---

  private void loadCurrentPreferences() {
    reverbCombo.setSelectedItem(PreferencesManager.get("reverb.model", "JCRev"));
    masterSatCheck.setSelected(PreferencesManager.isMasterSaturationEnabled());
    filterDriveCheck.setSelected(PreferencesManager.isFilterDriveEnabled());
    bitCrunchCheck.setSelected(PreferencesManager.isBitCrunchEnabled());
    visCheck.setSelected(Boolean.parseBoolean(PreferencesManager.get("show.visualizers", "true")));
    debugCheck.setSelected(Boolean.parseBoolean(PreferencesManager.get("debug.audio", "false")));

    int gainVal = PreferencesManager.getMonitorGainBoost(); // already clamped to [1,128]
    int gainIdx = 3; // default is 24x
    if (gainVal <= 6) gainIdx = 0;
    else if (gainVal <= 12) gainIdx = 1;
    else if (gainVal <= 18) gainIdx = 2;
    else if (gainVal <= 24) gainIdx = 3;
    else if (gainVal <= 32) gainIdx = 4;
    else if (gainVal <= 48) gainIdx = 5;
    else if (gainVal <= 64) gainIdx = 6;
    else if (gainVal <= 96) gainIdx = 7;
    else gainIdx = 8; // 128
    monitorGainCombo.setSelectedIndex(gainIdx);
    gridModeCheck.setSelected(
        Boolean.parseBoolean(PreferencesManager.get("midi.grid.mode", "false")));
    tooltipCheck.setSelected(Boolean.parseBoolean(PreferencesManager.get("show.tooltips", "true")));
    screenResCombo.setSelectedItem(PreferencesManager.get("screen.resolution", "QHD"));
    gridModeCombo.setSelectedItem(PreferencesManager.getGridMode().name());
    engineCombo.setSelectedItem(PreferencesManager.getSequencerEngine().name());

    String syncModeStr = PreferencesManager.get("sequencer.sync.mode", "INTERNAL");
    if ("EXTERNAL_MIDI".equals(syncModeStr)) {
      syncModeCombo.setSelectedIndex(1);
    } else {
      syncModeCombo.setSelectedIndex(0);
    }

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
    String[] ports = org.deluge.shadow.midi.MidiIn.list();
    midiCombo.removeAllItems();
    friendlyToRawMidi.clear();
    rawToFriendlyMidi.clear();
    midiCombo.addItem("None");
    friendlyToRawMidi.put("None", "None");
    rawToFriendlyMidi.put("None", "None");
    for (String p : ports) {
      String friendly = getFriendlyMidiPortName(p, true);
      friendlyToRawMidi.put(friendly, p);
      rawToFriendlyMidi.put(p, friendly);
      midiCombo.addItem(friendly);
    }
    midiCombo.setSelectedItem(getFriendlyPortName(PreferencesManager.get("midi.input", "None")));

    // SD Card root directory
    String libDir = PreferencesManager.getLibraryDir().getAbsolutePath();
    dirLabel.setText(libDir != null ? libDir : "(not set)");

    // Load active Scala path preference
    String scalaPath = PreferencesManager.get("scala.scale.path", "");
    if (!scalaPath.isEmpty()) {
      java.io.File file = new java.io.File(scalaPath);
      if (file.exists()) {
        scalaPathField.setText(file.getName());
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
          ScalaScale scale = ScalaScaleParser.parse(fis, file.getName());
          ScalaScale.setActiveScale(scale);
        } catch (Exception ignored) {
        }
      } else {
        scalaPathField.setText("(no custom scale - 12-TET active)");
      }
    } else {
      scalaPathField.setText("(no custom scale - 12-TET active)");
    }

    // Initialize deviceCombo selection based on loaded preference
    if (midiService != null) {
      org.deluge.midi.MidiDeviceDefinition currentDef = midiService.getDeviceDefinition();
      if (currentDef != null) {
        for (int i = 0; i < deviceCombo.getItemCount(); i++) {
          var item = deviceCombo.getItemAt(i);
          if (item != null && item.getId().equals(currentDef.getId())) {
            deviceCombo.setSelectedIndex(i);
            break;
          }
        }
      }
      rebuildTableContent();
    }

    // Now safely add the action listeners after loading is complete!
    midiCombo.addActionListener(
        e -> {
          if (isRebuildingCombo) return;
          if (midiService != null) {
            String selectedPort = getRawPortName((String) midiCombo.getSelectedItem());
            PreferencesManager.set("midi.input", selectedPort);

            // Restart MIDI connection
            midiService.stop();
            midiService.start();

            // Refresh device mapping based on the newly selected port
            org.deluge.midi.MidiDeviceDefinition currentDef = midiService.getDeviceDefinition();
            if (currentDef != null) {
              for (int i = 0; i < deviceCombo.getItemCount(); i++) {
                var item = deviceCombo.getItemAt(i);
                if (item != null && item.getId().equals(currentDef.getId())) {
                  deviceCombo.setSelectedIndex(i);
                  break;
                }
              }
            } else {
              deviceCombo.setSelectedIndex(0); // None
            }
            rebuildTableContent();
          }
        });
  }

  public void setMappings(java.util.Map<String, Integer> mappings) {
    rebuildTableContent();
  }

  public void selectMidiTab() {
    if (tabPane != null) {
      tabPane.setSelectedIndex(1);
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

  private void scalaBrowseBtnActionPerformed(java.awt.event.ActionEvent evt) {
    JFileChooser chooser = new JFileChooser(PreferencesManager.getLibraryDir());
    chooser.setFileFilter(
        new javax.swing.filechooser.FileNameExtensionFilter("Scala Scales (*.scl)", "scl"));
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      java.io.File file = chooser.getSelectedFile();
      try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
        // Test parsing immediately to catch formatting errors
        ScalaScale scale = ScalaScaleParser.parse(fis, file.getName());

        String path = file.getAbsolutePath();
        scalaPathField.setText(file.getName());
        PreferencesManager.set("scala.scale.path", path);
        ScalaScale.setActiveScale(scale);

        JOptionPane.showMessageDialog(
            this,
            "Successfully loaded scale: "
                + scale.getName()
                + "\n"
                + scale.getDescription()
                + " ("
                + scale.getStepsCount()
                + " steps)",
            "Tuning Scale Loaded",
            JOptionPane.INFORMATION_MESSAGE);
      } catch (Exception e) {
        JOptionPane.showMessageDialog(
            this,
            "Failed to parse Scala file: " + e.getMessage(),
            "Scale Parsing Error",
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private void saveBtnActionPerformed(java.awt.event.ActionEvent evt) {
    String oldRes = PreferencesManager.get("screen.resolution", "");
    String newRes = (String) screenResCombo.getSelectedItem();

    PreferencesManager.set("reverb.model", (String) reverbCombo.getSelectedItem());
    PreferencesManager.setMasterSaturationEnabled(masterSatCheck.isSelected());
    PreferencesManager.setFilterDriveEnabled(filterDriveCheck.isSelected());
    PreferencesManager.setBitCrunchEnabled(bitCrunchCheck.isSelected());
    PreferencesManager.set("midi.input", getRawPortName((String) midiCombo.getSelectedItem()));
    PreferencesManager.set("show.visualizers", String.valueOf(visCheck.isSelected()));
    PreferencesManager.set("debug.audio", String.valueOf(debugCheck.isSelected()));

    int selIdx = monitorGainCombo.getSelectedIndex();
    int gainVal = 24;
    if (selIdx == 0) gainVal = 6;
    else if (selIdx == 1) gainVal = 12;
    else if (selIdx == 2) gainVal = 18;
    else if (selIdx == 3) gainVal = 24;
    else if (selIdx == 4) gainVal = 32;
    else if (selIdx == 5) gainVal = 48;
    else if (selIdx == 6) gainVal = 64;
    else if (selIdx == 7) gainVal = 96;
    else if (selIdx == 8) gainVal = 128;
    PreferencesManager.setMonitorGainBoost(gainVal);
    org.deluge.engine.JavaAudioDriver.monitorGainMul = gainVal;
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

    int selectedSyncMode = syncModeCombo.getSelectedIndex();
    PreferencesManager.set(
        "sequencer.sync.mode", selectedSyncMode == 1 ? "EXTERNAL_MIDI" : "INTERNAL");

    if (midiService != null && midiService.getBridge() != null) {
      Object ph =
          midiService.getBridge().getGlobalObject(org.deluge.BridgeContract.G_PLAYBACK_HANDLER);
      if (ph instanceof org.deluge.firmware.playback.PlaybackHandler playbackHandler) {
        playbackHandler.setSyncMode(selectedSyncMode);
        System.out.println(
            "[Preferences] Dynamic Sync Mode applied: "
                + (selectedSyncMode == 1 ? "EXTERNAL" : "INTERNAL"));
      }
    }

    if (SwingDelugeApp.mainInstance != null && SwingDelugeApp.mainInstance.getTopBar() != null) {
      SwingDelugeApp.mainInstance.getTopBar().applyDisplayPreferences();
    }

    if (onGridModeChanged != null) onGridModeChanged.run();
    if (onLibraryChanged != null) onLibraryChanged.run();

    dispose();
    if (!newRes.equals(oldRes) && SwingDelugeApp.mainInstance != null) {
      // Apply the new resolution profile to the live window (clamped to the physical screen).
      SwingDelugeApp.mainInstance.applyWindowResolution();
    }
  }

  // ── Dynamic MIDI Port Hot-Plug Scanners ──

  private void updateMidiPortsListDynamic() {
    String[] currentPorts = org.deluge.shadow.midi.MidiIn.list();

    int comboItemCount = midiCombo.getItemCount();
    boolean changed = false;
    if (comboItemCount - 1 != currentPorts.length) {
      changed = true;
    } else {
      for (int i = 0; i < currentPorts.length; i++) {
        String item = midiCombo.getItemAt(i + 1);
        if (!currentPorts[i].equals(item)) {
          changed = true;
          break;
        }
      }
    }

    if (changed) {
      isRebuildingCombo = true;

      String selectedFriendly = (String) midiCombo.getSelectedItem();
      String selectedRaw = getRawPortName(selectedFriendly);

      midiCombo.removeAllItems();
      friendlyToRawMidi.clear();
      rawToFriendlyMidi.clear();

      midiCombo.addItem("None");
      friendlyToRawMidi.put("None", "None");
      rawToFriendlyMidi.put("None", "None");

      for (String p : currentPorts) {
        String friendly = getFriendlyMidiPortName(p, true);
        friendlyToRawMidi.put(friendly, p);
        rawToFriendlyMidi.put(p, friendly);
        midiCombo.addItem(friendly);
      }

      boolean found = false;
      if (selectedRaw != null) {
        String friendlyToSelect = rawToFriendlyMidi.get(selectedRaw);
        if (friendlyToSelect != null) {
          midiCombo.setSelectedItem(friendlyToSelect);
          found = true;
        }
      }
      if (!found) {
        midiCombo.setSelectedIndex(0); // None
      }

      isRebuildingCombo = false;
      updateDeviceComboForSelectedPort();
    }
  }

  private void updateDeviceComboForSelectedPort() {
    if (midiService == null) return;
    org.deluge.midi.MidiDeviceDefinition currentDef = midiService.getDeviceDefinition();
    if (currentDef != null) {
      for (int i = 0; i < deviceCombo.getItemCount(); i++) {
        var item = deviceCombo.getItemAt(i);
        if (item != null && item.getId().equals(currentDef.getId())) {
          deviceCombo.setSelectedIndex(i);
          break;
        }
      }
    } else {
      deviceCombo.setSelectedIndex(0); // None
    }
    rebuildTableContent();
  }

  public static String getFriendlyMidiPortName(String portName, boolean isInput) {
    if (portName == null || portName.equals("None")) return "None";
    try {
      javax.sound.midi.MidiDevice.Info[] infos = javax.sound.midi.MidiSystem.getMidiDeviceInfo();
      for (javax.sound.midi.MidiDevice.Info info : infos) {
        String name = info.getName();
        if (name != null && name.trim().equalsIgnoreCase(portName.trim())) {
          javax.sound.midi.MidiDevice dev = javax.sound.midi.MidiSystem.getMidiDevice(info);
          boolean matchDirection =
              isInput ? (dev.getMaxTransmitters() != 0) : (dev.getMaxReceivers() != 0);
          if (matchDirection) {
            String desc = info.getDescription();
            String vendor = info.getVendor();
            if (desc != null && !desc.isEmpty()) {
              return desc;
            }
            if (vendor != null && !vendor.isEmpty()) {
              return vendor + " - " + portName;
            }
          }
        }
      }
    } catch (Throwable t) {
      System.err.println("[DIAG friendly] Error matching port: " + t.getMessage());
    }
    return portName;
  }

  private String getFriendlyPortName(String rawName) {
    if (rawName == null || rawName.equals("None")) return "None";
    return rawToFriendlyMidi.getOrDefault(rawName, getFriendlyMidiPortName(rawName, true));
  }

  private String getRawPortName(String friendlyName) {
    if (friendlyName == null || friendlyName.equals("None")) return "None";
    return friendlyToRawMidi.getOrDefault(friendlyName, friendlyName);
  }
}
