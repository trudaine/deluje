/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/GUIForms/JDialog.java to edit this template
 */
package org.chuck.deluge.ui;

import javax.swing.*;
import org.chuck.deluge.project.PreferencesManager;

/**
 * Modal preferences dialog: audio, MIDI, grid, screen, samples. Designed for NetBeans GUI Builder —
 * edit the .form file, not initComponents().
 */
public class PreferencesDialog extends JDialog {

  private static final java.util.logging.Logger logger =
      java.util.logging.Logger.getLogger(PreferencesDialog.class.getName());

  private final Runnable onGridModeChanged;
  private final Runnable onLibraryChanged;
  private final DefaultListModel<String> listModel = new DefaultListModel<>();
  private JCheckBox advancedGridStyleCheck;
  private JComboBox<String> interactionModeCombo;
  private JComboBox<String> displayTypeCombo;

  /**
   * Creates new form PreferencesDialog
   *
   * @param owner parent frame
   * @param onGridModeChanged callback invoked when grid mode is saved
   * @param onLibraryChanged callback invoked when samples/library directory changes
   */
  public PreferencesDialog(
      java.awt.Frame owner, Runnable onGridModeChanged, Runnable onLibraryChanged) {
    super(owner, true);
    this.onGridModeChanged = onGridModeChanged;
    this.onLibraryChanged = onLibraryChanged;
    initComponents();

    // Add advanced UI checkbox programmatically outside the fold
    JPanel advPanel = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 15, 2));
    advPanel.setBackground(new java.awt.Color(24, 24, 24));

    JLabel advLabel = new JLabel("Advanced Grid UI Style:");
    advLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 14));
    advLabel.setForeground(java.awt.Color.LIGHT_GRAY);
    advancedGridStyleCheck = new JCheckBox();
    advancedGridStyleCheck.setBackground(new java.awt.Color(24, 24, 24));

    JLabel shiftLabel = new JLabel("Shift Shortcut Style:");
    shiftLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 14));
    shiftLabel.setForeground(java.awt.Color.LIGHT_GRAY);
    interactionModeCombo = new JComboBox<>(new String[] {"Desktop Slider", "Hardware Rotary"});
    interactionModeCombo.setBackground(new java.awt.Color(34, 34, 34));
    interactionModeCombo.setForeground(java.awt.Color.WHITE);

    JLabel displayLabel = new JLabel("Screen Style:");
    displayLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 14));
    displayLabel.setForeground(java.awt.Color.LIGHT_GRAY);
    displayTypeCombo =
        new JComboBox<>(new String[] {"Show Both", "OLED Screen Only", "Classic LED Only"});
    displayTypeCombo.setBackground(new java.awt.Color(34, 34, 34));
    displayTypeCombo.setForeground(java.awt.Color.WHITE);

    advPanel.add(advLabel);
    advPanel.add(advancedGridStyleCheck);
    advPanel.add(new JSeparator(JSeparator.VERTICAL));
    advPanel.add(shiftLabel);
    advPanel.add(interactionModeCombo);
    advPanel.add(new JSeparator(JSeparator.VERTICAL));
    advPanel.add(displayLabel);
    advPanel.add(displayTypeCombo);

    // Wrap original content pane
    java.awt.Container contentPane = getContentPane();
    JPanel wrapper = new JPanel(new java.awt.BorderLayout());
    setContentPane(wrapper);
    wrapper.add(contentPane, java.awt.BorderLayout.CENTER);
    wrapper.add(advPanel, java.awt.BorderLayout.NORTH);

    setLocationRelativeTo(owner);
    loadCurrentPreferences();
  }

  /** Populate combo boxes and checkboxes from saved preferences. */
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

  /** Set the MIDI active mappings list. Call before setVisible(true). */
  public void setMappings(java.util.Map<String, Integer> mappings) {
    listModel.clear();
    if (mappings != null) {
      for (java.util.Map.Entry<String, Integer> entry : mappings.entrySet()) {
        listModel.addElement(entry.getKey() + " -> CC " + entry.getValue());
      }
    }
  }

  /**
   * This method is called from within the constructor to initialize the form. WARNING: Do NOT
   * modify this code. The content of this method is always regenerated by the Form Editor.
   */
  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    jScrollPane1 = new javax.swing.JScrollPane();
    mainPanel = new javax.swing.JPanel();
    reverbLabel = new javax.swing.JLabel();
    reverbCombo = new javax.swing.JComboBox<>();
    reverbHelp = new javax.swing.JLabel();
    midiLabel = new javax.swing.JLabel();
    midiCombo = new javax.swing.JComboBox<>();
    midiHelp = new javax.swing.JLabel();
    showVisLabel = new javax.swing.JLabel();
    visCheck = new javax.swing.JCheckBox();
    debugAudioLabel = new javax.swing.JLabel();
    debugCheck = new javax.swing.JCheckBox();
    midiGridLabel = new javax.swing.JLabel();
    gridModeCheck = new javax.swing.JCheckBox();
    showTooltipsLabel = new javax.swing.JLabel();
    tooltipCheck = new javax.swing.JCheckBox();
    screenResLabel = new javax.swing.JLabel();
    screenResCombo = new javax.swing.JComboBox<>();
    gridModeLabel = new javax.swing.JLabel();
    gridModeCombo = new javax.swing.JComboBox<>();
    gridHelp = new javax.swing.JLabel();
    engineLabel = new javax.swing.JLabel();
    engineCombo = new javax.swing.JComboBox<>();
    engineHelp = new javax.swing.JLabel();
    masterSatLabel = new javax.swing.JLabel();
    masterSatCheck = new javax.swing.JCheckBox();
    filterDriveLabel = new javax.swing.JLabel();
    filterDriveCheck = new javax.swing.JCheckBox();
    bitCrunchLabel = new javax.swing.JLabel();
    bitCrunchCheck = new javax.swing.JCheckBox();
    mappingsLabel = new javax.swing.JLabel();
    mappingsScroll = new javax.swing.JScrollPane();
    mappingList = new javax.swing.JList<>();
    samplesLabel = new javax.swing.JLabel();
    dirLabel = new javax.swing.JLabel();
    browseBtn = new javax.swing.JButton();
    saveBtn = new javax.swing.JButton();

    setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
    setTitle("Preferences");

    mainPanel.setBackground(new java.awt.Color(24, 24, 24));

    reverbLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    reverbLabel.setForeground(new java.awt.Color(224, 224, 224));
    reverbLabel.setText("Reverb Model:");

    reverbCombo.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    reverbCombo.setModel(
        new javax.swing.DefaultComboBoxModel<>(
            new String[] {"JCRev", "FreeVerb", "MVerb", "ProceduralReverb", "RingsReverb"}));

    reverbHelp.setFont(new java.awt.Font("SansSerif", java.awt.Font.ITALIC, 18));
    reverbHelp.setForeground(new java.awt.Color(128, 128, 128));
    reverbHelp.setText("Select acoustic model structure");

    midiLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    midiLabel.setForeground(new java.awt.Color(224, 224, 224));
    midiLabel.setText("MIDI Input:");

    midiCombo.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));

    midiHelp.setFont(new java.awt.Font("SansSerif", java.awt.Font.ITALIC, 18));
    midiHelp.setForeground(new java.awt.Color(128, 128, 128));
    midiHelp.setText("Requires application reboot to re-route");

    masterSatLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    masterSatLabel.setForeground(new java.awt.Color(224, 224, 224));
    masterSatLabel.setText("Master Saturation:");

    masterSatCheck.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    masterSatCheck.setText("");

    filterDriveLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    filterDriveLabel.setForeground(new java.awt.Color(224, 224, 224));
    filterDriveLabel.setText("Filter Drive (v1.3.1+):");

    filterDriveCheck.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    filterDriveCheck.setText("");

    bitCrunchLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    bitCrunchLabel.setForeground(new java.awt.Color(224, 224, 224));
    bitCrunchLabel.setText("14-bit DAC Crunch:");

    bitCrunchCheck.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    bitCrunchCheck.setText("");

    showVisLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    showVisLabel.setForeground(new java.awt.Color(224, 224, 224));
    showVisLabel.setText("Show Visualizers:");

    visCheck.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    visCheck.setText("");

    debugAudioLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    debugAudioLabel.setForeground(new java.awt.Color(224, 224, 224));
    debugAudioLabel.setText("Debug Audio:");

    debugCheck.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    debugCheck.setText("");

    midiGridLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    midiGridLabel.setForeground(new java.awt.Color(224, 224, 224));
    midiGridLabel.setText("MIDI Grid Mode:");

    gridModeCheck.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    gridModeCheck.setText("");

    showTooltipsLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    showTooltipsLabel.setForeground(new java.awt.Color(224, 224, 224));
    showTooltipsLabel.setText("Show Tooltips:");

    tooltipCheck.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    tooltipCheck.setText("");

    screenResLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    screenResLabel.setForeground(new java.awt.Color(224, 224, 224));
    screenResLabel.setText("Screen Resolution:");

    screenResCombo.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    screenResCombo.setModel(
        new javax.swing.DefaultComboBoxModel<>(new String[] {"FHD", "QHD", "4K"}));

    gridModeLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    gridModeLabel.setForeground(new java.awt.Color(224, 224, 224));
    gridModeLabel.setText("Grid Mode:");

    gridModeCombo.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    gridModeCombo.setModel(
        new javax.swing.DefaultComboBoxModel<>(
            new String[] {"GRID_8x16", "GRID_16x16", "GRID_24x16", "GRID_16x24"}));

    gridHelp.setFont(new java.awt.Font("SansSerif", java.awt.Font.ITALIC, 18));
    gridHelp.setForeground(new java.awt.Color(128, 128, 128));
    gridHelp.setText("Rows x Steps. Requires app restart for engine sync.");

    engineLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    engineLabel.setForeground(new java.awt.Color(224, 224, 224));
    engineLabel.setText("Sequencer Engine:");

    engineCombo.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    engineCombo.setModel(
        new javax.swing.DefaultComboBoxModel<>(new String[] {"CHUCK", "PURE_JAVA"}));

    engineHelp.setFont(new java.awt.Font("SansSerif", java.awt.Font.ITALIC, 18));
    engineHelp.setForeground(new java.awt.Color(128, 128, 128));
    engineHelp.setText("CHUCK (stable) vs PURE_JAVA (experimental). Requires restart.");

    mappingsLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    mappingsLabel.setForeground(new java.awt.Color(224, 224, 224));
    mappingsLabel.setText("Active Mappings:");

    mappingList.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 18));
    mappingList.setModel(
        new javax.swing.AbstractListModel<String>() {
          String[] strings = {"(empty)"};

          @Override
          public int getSize() {
            return strings.length;
          }

          @Override
          public String getElementAt(int i) {
            return strings[i];
          }
        });
    mappingsScroll.setViewportView(mappingList);

    samplesLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24));
    samplesLabel.setForeground(new java.awt.Color(224, 224, 224));
    samplesLabel.setText("SD Card Root:");

    dirLabel.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 20));
    dirLabel.setForeground(new java.awt.Color(0, 255, 255));
    dirLabel.setText("(not set)");

    browseBtn.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 20));
    browseBtn.setText("Browse...");
    browseBtn.addActionListener(this::browseBtnActionPerformed);

    saveBtn.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 28));
    saveBtn.setText("Save");
    saveBtn.addActionListener(this::saveBtnActionPerformed);

    javax.swing.GroupLayout mainPanelLayout = new javax.swing.GroupLayout(mainPanel);
    mainPanel.setLayout(mainPanelLayout);
    mainPanelLayout.setHorizontalGroup(
        mainPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                mainPanelLayout
                    .createSequentialGroup()
                    .addGap(20, 20, 20)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(gridModeLabel)
                            .addComponent(reverbLabel)
                            .addComponent(midiLabel)
                            .addComponent(masterSatLabel)
                            .addComponent(filterDriveLabel)
                            .addComponent(bitCrunchLabel)
                            .addComponent(showVisLabel)
                            .addComponent(debugAudioLabel)
                            .addComponent(midiGridLabel)
                            .addComponent(showTooltipsLabel)
                            .addComponent(screenResLabel)
                            .addComponent(engineLabel)
                            .addComponent(mappingsLabel)
                            .addComponent(samplesLabel))
                    .addGap(20, 20, 20)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(
                                        reverbCombo,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        280,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(reverbHelp)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(
                                        midiCombo,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        280,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(midiHelp)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(masterSatCheck)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(filterDriveCheck)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(bitCrunchCheck)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(visCheck)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(debugCheck)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(gridModeCheck)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(tooltipCheck)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(
                                        screenResCombo,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        280,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(
                                        gridModeCombo,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        280,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(gridHelp)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(
                                        engineCombo,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        280,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(engineHelp)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(
                                        mappingsScroll,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        280,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(dirLabel)
                                    .addGap(0, 0, Short.MAX_VALUE))
                            .addGroup(
                                mainPanelLayout
                                    .createSequentialGroup()
                                    .addComponent(browseBtn)
                                    .addGap(0, 0, Short.MAX_VALUE)))
                    .addGap(20, 20, 20)));
    mainPanelLayout.setVerticalGroup(
        mainPanelLayout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                mainPanelLayout
                    .createSequentialGroup()
                    .addGap(20, 20, 20)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(reverbLabel)
                            .addComponent(
                                reverbCombo,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(reverbHelp)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(masterSatLabel)
                            .addComponent(masterSatCheck))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(filterDriveLabel)
                            .addComponent(filterDriveCheck))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(bitCrunchLabel)
                            .addComponent(bitCrunchCheck))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(midiLabel)
                            .addComponent(
                                midiCombo,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(midiHelp)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(showVisLabel)
                            .addComponent(visCheck))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(debugAudioLabel)
                            .addComponent(debugCheck))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(midiGridLabel)
                            .addComponent(gridModeCheck))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(showTooltipsLabel)
                            .addComponent(tooltipCheck))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(screenResLabel)
                            .addComponent(
                                screenResCombo,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(gridModeLabel)
                            .addComponent(
                                gridModeCombo,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(gridHelp)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(engineLabel)
                            .addComponent(
                                engineCombo,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(engineHelp)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(mappingsLabel)
                            .addComponent(
                                mappingsScroll,
                                javax.swing.GroupLayout.PREFERRED_SIZE,
                                120,
                                javax.swing.GroupLayout.PREFERRED_SIZE))
                    .addGap(18, 18, 18)
                    .addGroup(
                        mainPanelLayout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.BASELINE)
                            .addComponent(samplesLabel)
                            .addComponent(dirLabel))
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(browseBtn)
                    .addGap(20, 20, 20)));

    jScrollPane1.setViewportView(mainPanel);

    javax.swing.GroupLayout layout = new javax.swing.GroupLayout(getContentPane());
    getContentPane().setLayout(layout);
    layout.setHorizontalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                javax.swing.GroupLayout.Alignment.TRAILING,
                layout
                    .createSequentialGroup()
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addGroup(
                        layout
                            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
                            .addComponent(
                                jScrollPane1,
                                javax.swing.GroupLayout.DEFAULT_SIZE,
                                680,
                                Short.MAX_VALUE)
                            .addGroup(
                                layout
                                    .createSequentialGroup()
                                    .addGap(0, 0, Short.MAX_VALUE)
                                    .addComponent(
                                        saveBtn,
                                        javax.swing.GroupLayout.PREFERRED_SIZE,
                                        200,
                                        javax.swing.GroupLayout.PREFERRED_SIZE)
                                    .addGap(0, 0, Short.MAX_VALUE)))
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)));
    layout.setVerticalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                layout
                    .createSequentialGroup()
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)
                    .addComponent(
                        jScrollPane1, javax.swing.GroupLayout.DEFAULT_SIZE, 520, Short.MAX_VALUE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.UNRELATED)
                    .addComponent(
                        saveBtn,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        50,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addContainerGap(javax.swing.GroupLayout.DEFAULT_SIZE, Short.MAX_VALUE)));

    pack();
  } // </editor-fold>//GEN-END:initComponents

  private void browseBtnActionPerformed(java.awt.event.ActionEvent evt) {
    JFileChooser chooser = new JFileChooser(PreferencesManager.getLibraryDir());
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      PreferencesManager.setLibraryDir(chooser.getSelectedFile().getAbsolutePath());
      dirLabel.setText(chooser.getSelectedFile().getAbsolutePath());
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

  // Variables declaration - do not modify//GEN-BEGIN:variables
  private javax.swing.JButton browseBtn;
  private javax.swing.JLabel debugAudioLabel;
  private javax.swing.JCheckBox debugCheck;
  private javax.swing.JLabel masterSatLabel;
  private javax.swing.JCheckBox masterSatCheck;
  private javax.swing.JLabel filterDriveLabel;
  private javax.swing.JCheckBox filterDriveCheck;
  private javax.swing.JLabel bitCrunchLabel;
  private javax.swing.JCheckBox bitCrunchCheck;
  private javax.swing.JLabel dirLabel;
  private javax.swing.JLabel gridHelp;
  private javax.swing.JCheckBox gridModeCheck;
  private javax.swing.JComboBox<String> gridModeCombo;
  private javax.swing.JLabel gridModeLabel;
  private javax.swing.JLabel engineLabel;
  private javax.swing.JComboBox<String> engineCombo;
  private javax.swing.JLabel engineHelp;
  private javax.swing.JScrollPane jScrollPane1;
  private javax.swing.JPanel mainPanel;
  private javax.swing.JLabel mappingsLabel;
  private javax.swing.JScrollPane mappingsScroll;
  private javax.swing.JList<String> mappingList;
  private javax.swing.JComboBox<String> midiCombo;
  private javax.swing.JLabel midiGridLabel;
  private javax.swing.JLabel midiHelp;
  private javax.swing.JLabel midiLabel;
  private javax.swing.JButton saveBtn;
  private javax.swing.JLabel reverbHelp;
  private javax.swing.JComboBox<String> reverbCombo;
  private javax.swing.JLabel reverbLabel;
  private javax.swing.JLabel samplesLabel;
  private javax.swing.JComboBox<String> screenResCombo;
  private javax.swing.JLabel screenResLabel;
  private javax.swing.JLabel showTooltipsLabel;
  private javax.swing.JLabel showVisLabel;
  private javax.swing.JCheckBox tooltipCheck;
  private javax.swing.JCheckBox visCheck;
  // End of variables declaration//GEN-END:variables
}
