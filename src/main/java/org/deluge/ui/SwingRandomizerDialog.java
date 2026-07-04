package org.deluge.ui;

import java.awt.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.*;
import org.deluge.BridgeContract;
import org.deluge.engine.DelugeatorRandomizer;
import org.deluge.engine.DelugeatorRandomizer.RandomizerSettings;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.KitSynthSerializer;
import org.deluge.project.PreferencesManager;
import org.deluge.xml.DelugeXmlExporter;

/**
 * A beautiful, highly-interactive wide-screen dialog implementing both the Delugeator Web
 * Randomizer (Tab 1) and the Super Kit Generator (Tab 2) for folders drum auto-assembly.
 */
public class SwingRandomizerDialog extends JDialog {

  private final BridgeContract bridge;

  private final ProjectModel projectModel;
  private final Frame parentFrame;

  // ── Tab 1: Synth Randomizer Fields ──
  private final String[] categoryNames = {
    "General",
    "Osc 1",
    "Osc 2",
    "Noise",
    "Envelope 1",
    "Envelope 2",
    "Lfo 1",
    "Lfo 2",
    "Effects",
    "ModFx",
    "Delay",
    "Filters",
    "EQ",
    "Compressor",
    "Arpeggiator",
    "Unison"
  };
  private final JCheckBox[] checkBoxes = new JCheckBox[16];
  private final JSlider[] sliders = new JSlider[16];
  private final JLabel[] valueLabels = new JLabel[16];
  private JComboBox<String> templateCombo;
  private JCheckBox hardcoreBox;
  private RandomnessGauge gaugeMeter;

  // ── Tab 2: Kit Generator Fields ──
  private final String[] kitLaneNames = {
    "Kick (Row A)", "Snare (Row B)", "Closed Hat (Row C)", "Open Hat (Row D)",
    "Clap (Row E)", "Rim / Side (Row F)", "Tom (Row G)", "Ride / Crash (Row H)",
    "Percussion 1 (Row I)", "Percussion 2 (Row J)", "Percussion 3 (Row K)", "Percussion 4 (Row L)",
    "Extra 1 (Row M)", "Extra 2 (Row N)", "Extra 3 (Row O)", "Extra 4 (Row P)"
  };
  private JTextField kitFolderField;
  private final JComboBox<Object>[] kitCombos = new JComboBox[16];
  private final JButton[] kitPlayBtns = new JButton[16];
  private JCheckBox kitAutoChokeBox;
  private JSlider kitVolSlider;
  private JLabel kitVolLabel;
  private final List<File> currentFolderFiles = new ArrayList<>();
  private String currentFolderPath = null;

  public SwingRandomizerDialog(
      Frame parent, final BridgeContract bridge, ProjectModel projectModel) {
    super(parent, "Delugeator Voice & Kit Generator Suite", false);
    this.parentFrame = parent;
    this.bridge = bridge;

    this.projectModel = projectModel;

    setSize(840, 940);
    setLocationRelativeTo(parent);
    setLayout(new BorderLayout(5, 5));
    getContentPane().setBackground(new Color(0x12, 0x12, 0x14));

    JTabbedPane tabPane = new JTabbedPane();
    tabPane.setBackground(new Color(0x1a, 0x1a, 0x1e));
    tabPane.setForeground(Color.WHITE);
    tabPane.setFont(new Font("SansSerif", Font.BOLD, 12));

    tabPane.addTab("🎲 Synth Randomizer", buildSynthRandomizerTab());
    tabPane.addTab("🥁 Kit Super-Generator", buildKitGeneratorTab());
    tabPane.addTab("🎹 Chord Progression Generator", buildChordsGeneratorTab());

    add(tabPane, BorderLayout.CENTER);

    applyTemplatePreset(); // Initial default
    DarkComboBoxRenderer.styleComponentTree(this);
  }

  // ── TAB 1: SYNTH RANDOMIZER BUILDER ──
  private JPanel buildSynthRandomizerTab() {
    JPanel tabContent = new JPanel(new BorderLayout(10, 10));
    tabContent.setBackground(new Color(0x12, 0x12, 0x14));

    // Top Templates Panel
    JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 8));
    topPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    topPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x2d, 0x2d, 0x32)));

    JLabel templateLabel = new JLabel("Randomization Preset Template:");
    templateLabel.setForeground(Color.WHITE);
    templateLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    topPanel.add(templateLabel);

    String[] templates = {
      "Custom (Keep Sliders)",
      "Mild Randomization (25%)",
      "Moderate Randomization (50%)",
      "Hard Randomization (75%)",
      "Wild / Brain-Crushing (100%)"
    };
    templateCombo = new JComboBox<>(templates);
    templateCombo.setSelectedIndex(2); // Moderate default
    templateCombo.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    templateCombo.setForeground(Color.WHITE);
    templateCombo.addActionListener(e -> applyTemplatePreset());
    topPanel.add(templateCombo);
    tabContent.add(topPanel, BorderLayout.NORTH);

    // Center scrollable grid
    JPanel slidersListPanel = new JPanel(new GridBagLayout());
    slidersListPanel.setBackground(new Color(0x12, 0x12, 0x14));
    GridBagConstraints c = new GridBagConstraints();
    c.fill = GridBagConstraints.HORIZONTAL;
    c.insets = new Insets(6, 12, 6, 12);
    c.weightx = 1.0;

    for (int i = 0; i < 16; i++) {
      c.gridy = i;
      final int idx = i;

      // Checkbox
      c.gridx = 0;
      c.weightx = 0.0;
      checkBoxes[i] = new JCheckBox(categoryNames[i], true);
      checkBoxes[i].setFont(new Font("SansSerif", Font.BOLD, 12));
      checkBoxes[i].setForeground(Color.WHITE);
      checkBoxes[i].setBackground(new Color(0x12, 0x12, 0x14));
      checkBoxes[i].setPreferredSize(new Dimension(150, 24));
      checkBoxes[i].addActionListener(
          e -> {
            sliders[idx].setEnabled(checkBoxes[idx].isSelected());
            updateAverageMeter();
          });
      slidersListPanel.add(checkBoxes[i], c);

      // Slider
      c.gridx = 1;
      c.weightx = 1.0;
      sliders[i] = new JSlider(0, 100, 50);
      sliders[i].setBackground(new Color(0x12, 0x12, 0x14));
      sliders[i].addChangeListener(
          e -> {
            valueLabels[idx].setText(sliders[idx].getValue() + "%");
            updateAverageMeter();
          });
      slidersListPanel.add(sliders[i], c);

      // Value label
      c.gridx = 2;
      c.weightx = 0.0;
      valueLabels[i] = new JLabel("50%");
      valueLabels[i].setFont(new Font("Monospaced", Font.BOLD, 12));
      valueLabels[i].setForeground(Color.CYAN);
      valueLabels[i].setPreferredSize(new Dimension(50, 20));
      slidersListPanel.add(valueLabels[i], c);
    }

    JScrollPane scroll = new JScrollPane(slidersListPanel);
    scroll.setBorder(null);
    scroll.setBackground(new Color(0x12, 0x12, 0x14));
    scroll.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    styleScrollBar(scroll.getVerticalScrollBar());
    styleScrollBar(scroll.getHorizontalScrollBar());
    tabContent.add(scroll, BorderLayout.CENTER);

    // South Controls Stack
    JPanel southPanel = new JPanel(new GridBagLayout());
    southPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    southPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)));
    GridBagConstraints cs = new GridBagConstraints();
    cs.fill = GridBagConstraints.HORIZONTAL;
    cs.weightx = 1.0;

    // Hardcore Mode row
    cs.gridx = 0;
    cs.gridy = 0;
    cs.gridwidth = 2;
    hardcoreBox =
        new JCheckBox(
            "☠️ HARDCORE MODE: Allow brain-crushing saturation and maximum feedback limit parameters!");
    hardcoreBox.setFont(new Font("SansSerif", Font.BOLD, 12));
    hardcoreBox.setForeground(new Color(0xff, 0x55, 0x55));
    hardcoreBox.setBackground(new Color(0x1a, 0x1a, 0x1e));
    southPanel.add(hardcoreBox, cs);

    // Dynamic Gauge
    cs.gridy = 1;
    gaugeMeter = new RandomnessGauge();
    gaugeMeter.setPreferredSize(new Dimension(750, 65));
    southPanel.add(gaugeMeter, cs);

    // Action buttons row
    cs.gridy = 2;
    cs.gridwidth = 1;
    cs.weightx = 0.5;
    cs.insets = new Insets(8, 0, 0, 8);

    JButton randOptionsBtn = new JButton("🎲 Randomize Mappings  ");
    randOptionsBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
    randOptionsBtn.setPreferredSize(new Dimension(350, 42));
    styleButton(randOptionsBtn, new Color(0x32, 0x2d, 0x4d), Color.WHITE);
    randOptionsBtn.addActionListener(e -> triggerRandomizeOptions());
    southPanel.add(randOptionsBtn, cs);

    cs.gridx = 1;
    cs.insets = new Insets(8, 8, 0, 0);
    JButton createSynthBtn = new JButton("⚡ Create & Load Random Synth  ");
    createSynthBtn.setFont(new Font("SansSerif", Font.BOLD, 13));
    createSynthBtn.setPreferredSize(new Dimension(350, 42));
    styleButton(createSynthBtn, new Color(0x0c, 0x38, 0x1f), Color.GREEN);
    createSynthBtn.addActionListener(e -> generateAndLoadSynth());
    southPanel.add(createSynthBtn, cs);

    tabContent.add(southPanel, BorderLayout.SOUTH);
    return tabContent;
  }

  // ── TAB 2: DRUM KIT SUPER-GENERATOR BUILDER ──
  private JPanel buildKitGeneratorTab() {
    JPanel tabContent = new JPanel(new BorderLayout(10, 10));
    tabContent.setBackground(new Color(0x12, 0x12, 0x14));

    // Top Directory Selector Panel
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
    JLabel folderLabel = new JLabel("WAV Samples Directory:");
    folderLabel.setForeground(Color.WHITE);
    folderLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
    topPanel.add(folderLabel, cTop);

    cTop.gridx = 1;
    cTop.weightx = 1.0;
    kitFolderField = new JTextField();
    kitFolderField.setEditable(false);
    kitFolderField.setBackground(SwingSynthConfigDialog.BG_CONTROL);
    kitFolderField.setForeground(Color.LIGHT_GRAY);
    kitFolderField.setPreferredSize(new Dimension(350, 26));
    topPanel.add(kitFolderField, cTop);

    cTop.gridx = 2;
    cTop.weightx = 0.0;
    JButton browseFolderBtn = new JButton("📁 Select Folder  ");
    browseFolderBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
    styleButton(browseFolderBtn, new Color(0x3a, 0x3a, 0x3e), Color.WHITE);
    browseFolderBtn.setPreferredSize(new Dimension(140, 26));
    browseFolderBtn.addActionListener(e -> selectSamplesDirectory());
    topPanel.add(browseFolderBtn, cTop);

    tabContent.add(topPanel, BorderLayout.NORTH);

    // Center grid lanes setup
    JPanel gridPanel = new JPanel(new GridBagLayout());
    gridPanel.setBackground(new Color(0x12, 0x12, 0x14));
    GridBagConstraints cLanes = new GridBagConstraints();
    cLanes.fill = GridBagConstraints.HORIZONTAL;
    cLanes.insets = new Insets(5, 12, 5, 12);
    cLanes.weightx = 1.0;

    for (int i = 0; i < 16; i++) {
      cLanes.gridy = i;
      final int idx = i;

      // Slot Label
      cLanes.gridx = 0;
      cLanes.weightx = 0.0;
      JLabel laneLabel = new JLabel(String.format("Slot %02d: %s", i + 1, kitLaneNames[i]));
      laneLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
      laneLabel.setForeground(Color.LIGHT_GRAY);
      laneLabel.setPreferredSize(new Dimension(180, 24));
      gridPanel.add(laneLabel, cLanes);

      // ComboBox selector
      cLanes.gridx = 1;
      cLanes.weightx = 1.0;
      kitCombos[i] = new JComboBox<>();
      kitCombos[i].setBackground(SwingSynthConfigDialog.BG_CONTROL);
      kitCombos[i].setForeground(Color.WHITE);
      kitCombos[i].setRenderer(new FileComboRenderer());
      DefaultComboBoxModel<Object> emptyModel = new DefaultComboBoxModel<>();
      emptyModel.addElement("[None Selected]");
      kitCombos[i].setModel(emptyModel);
      gridPanel.add(kitCombos[i], cLanes);

      // Audio Play preview button
      cLanes.gridx = 2;
      cLanes.weightx = 0.0;
      kitPlayBtns[i] = new JButton("▶");
      kitPlayBtns[i].setFont(new Font("SansSerif", Font.BOLD, 11));
      kitPlayBtns[i].setBackground(new Color(0x23, 0x23, 0x28));
      kitPlayBtns[i].setForeground(Color.GREEN);
      kitPlayBtns[i].setPreferredSize(new Dimension(40, 24));
      kitPlayBtns[i].setFocusable(false);
      kitPlayBtns[i].addActionListener(
          e -> {
            Object val = kitCombos[idx].getSelectedItem();
            if (val instanceof File) {
              previewSampleFile(((File) val).getAbsolutePath());
            }
          });
      gridPanel.add(kitPlayBtns[i], cLanes);
    }

    JScrollPane scroll = new JScrollPane(gridPanel);
    scroll.setBorder(null);
    scroll.setBackground(new Color(0x12, 0x12, 0x14));
    scroll.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scroll.getVerticalScrollBar().setUnitIncrement(16);
    styleScrollBar(scroll.getVerticalScrollBar());
    styleScrollBar(scroll.getHorizontalScrollBar());
    tabContent.add(scroll, BorderLayout.CENTER);

    // South Controls Stack
    JPanel southPanel = new JPanel(new GridBagLayout());
    southPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    southPanel.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)),
            BorderFactory.createEmptyBorder(12, 16, 12, 16)));
    GridBagConstraints csKit = new GridBagConstraints();
    csKit.fill = GridBagConstraints.HORIZONTAL;
    csKit.weightx = 1.0;

    // Parameters row (Auto-Choke and Volume)
    csKit.gridx = 0;
    csKit.gridy = 0;
    csKit.gridwidth = 1;
    csKit.weightx = 0.4;
    kitAutoChokeBox = new JCheckBox("Auto-Choke Hats (Exclusion Mute Group 1)");
    kitAutoChokeBox.setFont(new Font("SansSerif", Font.BOLD, 12));
    kitAutoChokeBox.setForeground(new Color(0x00, 0xff, 0xcc));
    kitAutoChokeBox.setBackground(new Color(0x1a, 0x1a, 0x1e));
    kitAutoChokeBox.setSelected(true);
    southPanel.add(kitAutoChokeBox, csKit);

    // Volume Slider panel
    csKit.gridx = 1;
    csKit.weightx = 0.6;
    JPanel volPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    volPanel.setBackground(new Color(0x1a, 0x1a, 0x1e));
    JLabel volTitle = new JLabel("Default Volume:");
    volTitle.setForeground(Color.LIGHT_GRAY);
    volTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
    volPanel.add(volTitle);

    kitVolSlider = new JSlider(0, 100, 80);
    kitVolSlider.setBackground(new Color(0x1a, 0x1a, 0x1e));
    kitVolSlider.setPreferredSize(new Dimension(150, 20));
    kitVolSlider.addChangeListener(e -> kitVolLabel.setText(kitVolSlider.getValue() + "%"));
    volPanel.add(kitVolSlider);

    kitVolLabel = new JLabel("80%");
    kitVolLabel.setForeground(Color.CYAN);
    kitVolLabel.setFont(new Font("Monospaced", Font.BOLD, 12));
    volPanel.add(kitVolLabel);

    southPanel.add(volPanel, csKit);

    // Giant Generate action button
    csKit.gridx = 0;
    csKit.gridy = 1;
    csKit.gridwidth = 2;
    csKit.weightx = 1.0;
    csKit.insets = new Insets(10, 0, 0, 0);

    JButton generateKitBtn = new JButton("⚡ Generate & Load Drum Kit live  ");
    generateKitBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
    generateKitBtn.setPreferredSize(new Dimension(750, 45));
    styleButton(generateKitBtn, new Color(0x0c, 0x38, 0x1f), Color.GREEN);
    generateKitBtn.addActionListener(e -> generateAndLoadKitTrack());
    southPanel.add(generateKitBtn, csKit);

    tabContent.add(southPanel, BorderLayout.SOUTH);
    return tabContent;
  }

  private void selectSamplesDirectory() {
    File startDir = new File(PreferencesManager.getSamplesDir());
    JFileChooser chooser = new JFileChooser(startDir);
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    chooser.setPreferredSize(new Dimension(800, 550));

    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File dir = chooser.getSelectedFile();
      currentFolderPath = dir.getAbsolutePath().replace('\\', '/');
      kitFolderField.setText(currentFolderPath);

      // Scan directory for audio files
      currentFolderFiles.clear();
      File[] files =
          dir.listFiles(
              (d, name) -> {
                String lc = name.toLowerCase();
                return lc.endsWith(".wav")
                    || lc.endsWith(".aif")
                    || lc.endsWith(".aiff")
                    || lc.endsWith(".flac");
              });

      if (files != null) {
        for (File f : files) {
          currentFolderFiles.add(f);
        }
      }

      // Populate comboboxes
      for (int i = 0; i < 16; i++) {
        DefaultComboBoxModel<Object> model = new DefaultComboBoxModel<>();
        model.addElement("[None Selected]");
        for (File f : currentFolderFiles) {
          model.addElement(f);
        }
        kitCombos[i].setModel(model);
      }

      // Run Smart Auto-Mapper selection routine
      java.util.Set<File> mapped = new java.util.HashSet<>();

      // Pass 1: exact keyword mapping guess
      for (int i = 0; i < 16; i++) {
        for (File f : currentFolderFiles) {
          if (mapped.contains(f)) continue;
          if (guessSlotIndex(f.getName(), i) == i) {
            kitCombos[i].setSelectedItem(f);
            mapped.add(f);
            break;
          }
        }
      }

      // Pass 2: fill remaining blank slots with unmatched files
      for (int i = 0; i < 16; i++) {
        if (kitCombos[i].getSelectedIndex() <= 0) {
          for (File f : currentFolderFiles) {
            if (!mapped.contains(f)) {
              kitCombos[i].setSelectedItem(f);
              mapped.add(f);
              break;
            }
          }
        }
      }
    }
  }

  private static int guessSlotIndex(String filename, int slotIndex) {
    String name = filename.toUpperCase();
    if (slotIndex == 0
        && (name.contains("KICK")
            || name.contains("BD")
            || name.contains("BASSDRUM")
            || name.contains("SUB"))) return 0;
    if (slotIndex == 1
        && (name.contains("SNARE")
            || name.contains("SD")
            || name.contains("RIM")
            || name.contains("STICK"))) return 1;
    if (slotIndex == 2
        && (name.contains("CLOSED")
            || name.contains("CLH")
            || name.contains("CL_HAT")
            || name.contains("HHC")
            || (name.contains("CH") && !name.contains("CHORUS") && !name.contains("CRASH"))))
      return 2;
    if (slotIndex == 3
        && (name.contains("OPEN")
            || name.contains("OPH")
            || name.contains("OH")
            || name.contains("OP_HAT")
            || name.contains("HHO"))) return 3;
    if (slotIndex == 4 && (name.contains("CLAP") || name.contains("CP") || name.contains("SNAP")))
      return 4;
    if (slotIndex == 5
        && (name.contains("RIM")
            || name.contains("SIDE")
            || name.contains("CLICK")
            || name.contains("PERC")
            || name.contains("TAMB")
            || name.contains("SHAKER"))) return 5;
    if (slotIndex == 6
        && (name.contains("TOM")
            || name.contains("FT")
            || name.contains("MT")
            || name.contains("HT")
            || name.contains("CONGA"))) return 6;
    if (slotIndex == 7
        && (name.contains("CRASH")
            || name.contains("RIDE")
            || name.contains("CYM")
            || name.contains("SPLASH")
            || name.contains("BELL")
            || name.contains("COWBELL")
            || name.contains("CB"))) return 7;
    return -1;
  }

  private void previewSampleFile(String path) {
    if (path == null || path.isBlank()) return;
    Thread.startVirtualThread(
        () -> {
          try {
            File file = new File(path);
            if (!file.exists()) return;
            try (javax.sound.sampled.AudioInputStream stream =
                javax.sound.sampled.AudioSystem.getAudioInputStream(file)) {
              javax.sound.sampled.Clip clip = javax.sound.sampled.AudioSystem.getClip();
              clip.open(stream);
              clip.start();
              Thread.sleep(1500); // play briefly
              clip.close();
            }
          } catch (Exception ex) {
            System.err.println("[KitGenerator] Preview failed: " + ex.getMessage());
          }
        });
  }

  private void generateAndLoadKitTrack() {
    if (currentFolderPath == null) {
      JOptionPane.showMessageDialog(
          this,
          "⚠️ Please select a valid WAV samples folder first!",
          "Error",
          JOptionPane.ERROR_MESSAGE);
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

      KitTrackModel newKit = new KitTrackModel("Auto Kit");
      newKit.addClip(new ClipModel("CLIP 1", 8, 16));
      activeTrackIdx = tracks.size();
      projectModel.addTrack(newKit);
      track = newKit;
      fallbackCreated = true;
    }

    KitTrackModel kit = (KitTrackModel) track;
    kit.getDrums().clear();

    float defaultVolume = kitVolSlider.getValue() / 100.0f;
    boolean autoChoke = kitAutoChokeBox.isSelected();

    // Assemble slots in place
    for (int i = 0; i < 16; i++) {
      Object val = kitCombos[i].getSelectedItem();
      String samplePath = "";
      if (val instanceof File) {
        samplePath = ((File) val).getAbsolutePath().replace('\\', '/');
      }

      String laneName = kitLaneNames[i].split(" ")[0]; // Just the basename like Kick, Snare
      SoundDrum sd = new SoundDrum(laneName, samplePath);
      sd.setVolume(defaultVolume);

      // Apply Hi-hat Choke logic (Mute Group 1)
      if (autoChoke && (i == 2 || i == 3) && !samplePath.isEmpty()) {
        sd.setMuteGroup(1);
      }

      kit.addDrum(sd);
    }

    // Set standard folder name kit preset title
    File folderFile = new File(currentFolderPath);
    String kitName = "Kit_" + folderFile.getName();
    kit.setName(kitName);

    File exportFile = new File(PreferencesManager.getKitsDir(), kitName + ".XML");
    try {
      KitSynthSerializer.saveKit(kit, exportFile);
    } catch (Exception ex) {
      System.err.println("[KitGenerator] Export XML kit failed: " + ex.getMessage());
    }

    // Dynamic Live Playback reload and synchronization
    SwingDelugeApp.mainInstance.pushModelToBridge();
    SwingDelugeApp.mainInstance.reloadSidebarLibraries();
    SwingDelugeApp.mainInstance.propagateCurrentModel();
    SwingDelugeApp.mainInstance.syncHighFidelityEngine(projectModel);

    if (fallbackCreated) {
      activeGrid.setEditedModelTrack(activeTrackIdx);
    }
    SwingDelugeApp.mainInstance.refreshGrids();

    dispose(); // Close generator suite dialog

    JOptionPane.showMessageDialog(
        parentFrame,
        "<html>🎉 <b>DRUM KIT ASSEMBLED AND LOADED LIVE!</b><br><br>"
            + "New custom drum kit <b>'"
            + kitName
            + "'</b> auto-mapped and cabled live successfully!<br><br>"
            + "WAV channels and auto-choke groups are now playing live in real-time!<br><br>"
            + "Kit XML preset exported directly to your Deluge library:<br>"
            + "📁 <code>"
            + exportFile.getAbsolutePath()
            + "</code></html>",
        "Kit Assembled!",
        JOptionPane.INFORMATION_MESSAGE);
  }

  // ── SYNTH RANDOMIZER ENGINE UTILITIES ──
  private void applyTemplatePreset() {
    int idx = templateCombo.getSelectedIndex();
    if (idx == 0) return; // Custom

    int percent = 50;
    if (idx == 1) percent = 25;
    else if (idx == 2) percent = 50;
    else if (idx == 3) percent = 75;
    else if (idx == 4) percent = 100;

    for (int i = 0; i < 16; i++) {
      checkBoxes[i].setSelected(true);
      sliders[i].setEnabled(true);
      sliders[i].setValue(percent);
      valueLabels[i].setText(percent + "%");
    }
    updateAverageMeter();
  }

  private void updateAverageMeter() {
    int sum = 0;
    int count = 0;
    for (int i = 0; i < 16; i++) {
      if (checkBoxes[i].isSelected()) {
        sum += sliders[i].getValue();
        count++;
      }
    }
    int average = count > 0 ? (sum / count) : 0;
    gaugeMeter.setAverage(average);

    boolean matchPreset = false;
    for (int presetIdx = 1; presetIdx <= 4; presetIdx++) {
      int targetPercent = presetIdx == 1 ? 25 : (presetIdx == 2 ? 50 : (presetIdx == 3 ? 75 : 100));
      boolean allCheckedMatch = true;
      for (int i = 0; i < 16; i++) {
        if (!checkBoxes[i].isSelected() || sliders[i].getValue() != targetPercent) {
          allCheckedMatch = false;
          break;
        }
      }
      if (allCheckedMatch) {
        templateCombo.setSelectedIndex(presetIdx);
        matchPreset = true;
        break;
      }
    }
    if (!matchPreset) {
      templateCombo.setSelectedIndex(0); // Custom
    }
  }

  private void triggerRandomizeOptions() {
    for (int i = 0; i < 16; i++) {
      boolean enable = Math.random() < 0.75;
      checkBoxes[i].setSelected(enable);
      sliders[i].setEnabled(enable);

      int randVal = (int) (Math.random() * 101);
      sliders[i].setValue(randVal);
      valueLabels[i].setText(randVal + "%");
    }
    updateAverageMeter();
  }

  private void generateAndLoadSynth() {
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

    if (!(track instanceof SynthTrackModel)) {
      int confirm =
          JOptionPane.showConfirmDialog(
              this,
              "💡 Active row channel is NOT a Synthesizer track. Would you like to automatically create and load a new Synth track channel?",
              "Create New Track?",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.QUESTION_MESSAGE);
      if (confirm != JOptionPane.YES_OPTION) return;

      SynthTrackModel newSynth = new SynthTrackModel("Random Synth");
      newSynth.addClip(new ClipModel("CLIP 1", 8, 16));
      activeTrackIdx = tracks.size();
      projectModel.addTrack(newSynth);
      track = newSynth;
      fallbackCreated = true;
    }

    RandomizerSettings settings = new RandomizerSettings();
    settings.generalChecked = checkBoxes[0].isSelected();
    settings.generalAmount = sliders[0].getValue() / 100.0;
    settings.osc1Checked = checkBoxes[1].isSelected();
    settings.osc1Amount = sliders[1].getValue() / 100.0;
    settings.osc2Checked = checkBoxes[2].isSelected();
    settings.osc2Amount = sliders[2].getValue() / 100.0;
    settings.noiseChecked = checkBoxes[3].isSelected();
    settings.noiseAmount = sliders[3].getValue() / 100.0;
    settings.env1Checked = checkBoxes[4].isSelected();
    settings.env1Amount = sliders[4].getValue() / 100.0;
    settings.env2Checked = checkBoxes[5].isSelected();
    settings.env2Amount = sliders[5].getValue() / 100.0;
    settings.lfo1Checked = checkBoxes[6].isSelected();
    settings.lfo1Amount = sliders[6].getValue() / 100.0;
    settings.lfo2Checked = checkBoxes[7].isSelected();
    settings.lfo2Amount = sliders[7].getValue() / 100.0;
    settings.effectsChecked = checkBoxes[8].isSelected();
    settings.effectsAmount = sliders[8].getValue() / 100.0;
    settings.modFxChecked = checkBoxes[9].isSelected();
    settings.modFxAmount = sliders[9].getValue() / 100.0;
    settings.delayChecked = checkBoxes[10].isSelected();
    settings.delayAmount = sliders[10].getValue() / 100.0;
    settings.filtersChecked = checkBoxes[11].isSelected();
    settings.filtersAmount = sliders[11].getValue() / 100.0;
    settings.eqChecked = checkBoxes[12].isSelected();
    settings.eqAmount = sliders[12].getValue() / 100.0;
    settings.compressorChecked = checkBoxes[13].isSelected();
    settings.compressorAmount = sliders[13].getValue() / 100.0;
    settings.arpeggiatorChecked = checkBoxes[14].isSelected();
    settings.arpeggiatorAmount = sliders[14].getValue() / 100.0;
    settings.unisonChecked = checkBoxes[15].isSelected();
    settings.unisonAmount = sliders[15].getValue() / 100.0;
    settings.hardcoreMode = hardcoreBox.isSelected();

    SynthTrackModel synth = (SynthTrackModel) track;
    // Snapshot for undo before randomizing (skip when we just created a fallback track — its
    // creation isn't itself on the undo stack, so there's nothing coherent to revert to).
    SynthTrackModel undoBefore =
        fallbackCreated
            ? null
            : org.deluge.model.Consequence.SynthRandomizeConsequence.snapshot(synth);
    DelugeatorRandomizer.randomizeSynth(synth, projectModel, settings);

    String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
    String synthName = "Random_" + timestamp;
    synth.setName(synthName);

    if (undoBefore != null) {
      projectModel
          .getUndoRedoStack()
          .push(
              new org.deluge.model.Consequence.SynthRandomizeConsequence(
                  projectModel,
                  activeTrackIdx,
                  undoBefore,
                  org.deluge.model.Consequence.SynthRandomizeConsequence.snapshot(synth)));
    }

    File exportFile = new File(PreferencesManager.getSynthsDir(), synthName + ".XML");
    try {
      DelugeXmlExporter.saveSynthPreset(synth, bridge, activeTrackIdx, exportFile);
    } catch (Exception ex) {
      System.err.println("[Randomizer] Export XML preset failed: " + ex.getMessage());
    }

    SwingDelugeApp.mainInstance.pushModelToBridge();
    SwingDelugeApp.mainInstance.reloadSidebarLibraries();
    SwingDelugeApp.mainInstance.propagateCurrentModel();
    SwingDelugeApp.mainInstance.syncHighFidelityEngine(projectModel);

    if (fallbackCreated) {
      activeGrid.setEditedModelTrack(activeTrackIdx);
    }
    SwingDelugeApp.mainInstance.refreshGrids();

    dispose();

    JOptionPane.showMessageDialog(
        parentFrame,
        "<html>🎉 <b>SYNTH RANDOMIZED LIVE AND EXPORTED SUCCESSFULLY!</b><br><br>"
            + "New custom preset <b>'"
            + synthName
            + "'</b> loaded live into active play track row channel! Parameters are now playing live in real-time!<br><br>"
            + "WAV preset patch XML file exported to your library folder:<br>"
            + "📁 <code>"
            + exportFile.getAbsolutePath()
            + "</code></html>",
        "Synth Randomized!",
        JOptionPane.INFORMATION_MESSAGE);
  }

  // ── STYLE SCROLLBAR METHOD ──
  public static void styleScrollBar(JScrollBar bar) {
    if (bar == null) return;
    bar.setUI(
        new javax.swing.plaf.basic.BasicScrollBarUI() {
          @Override
          protected void configureScrollBarColors() {
            this.thumbColor = new Color(0x55, 0x55, 0x5e);
            this.trackColor = new Color(0x1d, 0x1d, 0x22);
          }

          @Override
          protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
            g.setColor(trackColor);
            g.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
          }

          @Override
          protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isDragging ? new Color(0xff, 0xaa, 0x00) : thumbColor);
            g2.fillRoundRect(
                thumbBounds.x + 2,
                thumbBounds.y + 2,
                thumbBounds.width - 4,
                thumbBounds.height - 4,
                6,
                6);
            g2.dispose();
          }

          @Override
          protected JButton createDecreaseButton(int orientation) {
            return createZeroButton();
          }

          @Override
          protected JButton createIncreaseButton(int orientation) {
            return createZeroButton();
          }

          private JButton createZeroButton() {
            JButton jb = new JButton();
            jb.setPreferredSize(new Dimension(0, 0));
            jb.setMinimumSize(new Dimension(0, 0));
            jb.setMaximumSize(new Dimension(0, 0));
            return jb;
          }
        });
  }

  // ── CUSTOM COMBOBOX CELL RENDERER ──
  private static class FileComboRenderer extends DefaultListCellRenderer {
    @Override
    public Component getListCellRendererComponent(
        JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
      super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
      if (value instanceof File) {
        setText(((File) value).getName());
        setForeground(Color.WHITE);
      } else if (value == null || value instanceof String) {
        setText(value != null ? value.toString() : "[None Selected]");
        setForeground(Color.GRAY);
      }
      return this;
    }
  }

  // ── DYNAMIC RANDOMNESS GAUGE DRAW COMPONENT ──
  private static class RandomnessGauge extends JComponent {
    private int average = 50;

    public void setAverage(int value) {
      this.average = value;
      repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
      super.paintComponent(g);
      Graphics2D g2 = (Graphics2D) g.create();
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

      int w = getWidth();
      int h = getHeight();

      int barX = 15;
      int barY = 15;
      int barW = w - 30;
      int barH = 14;

      for (int px = 0; px < barW; px++) {
        float ratio = (float) px / barW;
        Color c;
        if (ratio < 0.25f) {
          c = new Color(0, 255, 33);
        } else if (ratio < 0.50f) {
          c = new Color(251, 255, 68);
        } else if (ratio < 0.75f) {
          c = new Color(255, 205, 58);
        } else {
          c = new Color(255, 0, 0);
        }
        g2.setColor(c);
        g2.fillRect(barX + px, barY, 1, barH);
      }

      g2.setColor(new Color(0x44, 0x44, 0x48));
      g2.drawRoundRect(barX - 1, barY - 1, barW + 2, barH + 2, 4, 4);

      int needleX = barX + (int) ((average / 100.0) * barW);
      int[] tx = {needleX, needleX - 6, needleX + 6};
      int[] ty = {barY + barH + 2, barY + barH + 12, barY + barH + 12};
      g2.setColor(Color.WHITE);
      g2.fillPolygon(tx, ty, 3);
      g2.setColor(Color.RED);
      g2.drawLine(needleX, barY - 3, needleX, barY + barH + 3);

      String levelName = "Moderate";
      if (average < 25) levelName = "Mild / Safe";
      else if (average < 50) levelName = "Ok-ish";
      else if (average < 75) levelName = "Hard Mode";
      else levelName = "WILD / BRAIN-CRUSHING! ☠️";

      g2.setColor(Color.WHITE);
      g2.setFont(new Font("SansSerif", Font.BOLD, 12));
      String statusStr = "Randomness average range status: " + average + "% (" + levelName + ")";
      FontMetrics fm = g2.getFontMetrics();
      g2.drawString(statusStr, (w - fm.stringWidth(statusStr)) / 2, barY + barH + 28);

      g2.dispose();
    }
  }

  private JPanel buildChordsGeneratorTab() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x12, 0x12, 0x14));
    panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

    GridBagConstraints gbc = new GridBagConstraints();
    gbc.insets = new Insets(8, 8, 8, 8);
    gbc.fill = GridBagConstraints.HORIZONTAL;

    // Row 0: Root note key selector
    gbc.gridx = 0;
    gbc.gridy = 0;
    JLabel rootLbl = new JLabel("Key Root Center:");
    rootLbl.setForeground(Color.WHITE);
    panel.add(rootLbl, gbc);

    gbc.gridx = 1;
    String[] roots = {
      "C (60)", "C# (61)", "D (62)", "D# (63)", "E (64)", "F (65)", "F# (66)", "G (67)", "G# (68)",
      "A (69)", "A# (70)", "B (71)"
    };
    JComboBox<String> rootCombo = new JComboBox<>(roots);
    rootCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
    rootCombo.setForeground(Color.WHITE);
    panel.add(rootCombo, gbc);

    // Row 1: Active Scale Info Indicator!
    gbc.gridx = 0;
    gbc.gridy = 1;
    JLabel scaleLbl = new JLabel("Active Scale Source:");
    scaleLbl.setForeground(Color.WHITE);
    panel.add(scaleLbl, gbc);

    gbc.gridx = 1;
    org.deluge.model.tuning.ScalaScale scale = org.deluge.model.tuning.ScalaScale.getActiveScale();
    String scaleInfo =
        (scale != null)
            ? (scale.getName() + " (" + scale.getStepsCount() + " steps)")
            : "12-TET Standard Scale (Equal Temperament)";
    JLabel scaleInfoLabel =
        new JLabel("<html><font color='#00ffcc'><b>" + scaleInfo + "</b></font></html>");
    panel.add(scaleInfoLabel, gbc);

    // Row 2: Chord Progression selection template
    gbc.gridx = 0;
    gbc.gridy = 2;
    JLabel progLbl = new JLabel("Chords Progression:");
    progLbl.setForeground(Color.WHITE);
    panel.add(progLbl, gbc);

    gbc.gridx = 1;
    String[] progressions = {
      "I - IV - V - I (Standard)",
      "ii - V - I (Jazz Cadence)",
      "I - V - vi - IV (Pop Cadence)",
      "i - bVI - bIII - bVII (Epic Minor)",
      "Custom (Comma-separated degrees below!)"
    };
    JComboBox<String> progressionCombo = new JComboBox<>(progressions);
    progressionCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
    progressionCombo.setForeground(Color.WHITE);
    panel.add(progressionCombo, gbc);

    // Row 3: Custom progression degree entries field
    gbc.gridx = 0;
    gbc.gridy = 3;
    JLabel customProgLbl = new JLabel("Custom Degree Steps:");
    customProgLbl.setForeground(Color.WHITE);
    panel.add(customProgLbl, gbc);

    gbc.gridx = 1;
    JTextField customField = new JTextField("0, 3, 4, 0"); // Default I - IV - V - I
    customField.setBackground(new Color(0x2d, 0x2d, 0x32));
    customField.setForeground(Color.WHITE);
    customField.setCaretColor(Color.WHITE);
    panel.add(customField, gbc);

    // Row 4: Voicing Style Selector
    gbc.gridx = 0;
    gbc.gridy = 4;
    JLabel voicingLbl = new JLabel("Chords Voicing:");
    voicingLbl.setForeground(Color.WHITE);
    panel.add(voicingLbl, gbc);

    gbc.gridx = 1;
    String[] voicings = {
      "Triads (3-note stack)",
      "7ths (4-note stack)",
      "Sus4 Diatonic",
      "Spread Open Pads (Root + 5th + Octave)"
    };
    JComboBox<String> voicingCombo = new JComboBox<>(voicings);
    voicingCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
    voicingCombo.setForeground(Color.WHITE);
    panel.add(voicingCombo, gbc);

    // Row 5: Rhythm Pattern Selector
    gbc.gridx = 0;
    gbc.gridy = 5;
    JLabel rhythmLbl = new JLabel("Rhythmic Style:");
    rhythmLbl.setForeground(Color.WHITE);
    panel.add(rhythmLbl, gbc);

    gbc.gridx = 1;
    String[] rhythms = {
      "Whole Notes (1 chord per bar)",
      "Half Notes (2 chords per bar)",
      "Staccato Stabs (1/16th short gates)",
      "Arpeggiated Wave (1/16th rolling waves)"
    };
    JComboBox<String> rhythmCombo = new JComboBox<>(rhythms);
    rhythmCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
    rhythmCombo.setForeground(Color.WHITE);
    panel.add(rhythmCombo, gbc);

    // Row 6: Target Track Selector combo
    gbc.gridx = 0;
    gbc.gridy = 6;
    JLabel trackLbl = new JLabel("Target Synth Track:");
    trackLbl.setForeground(Color.WHITE);
    panel.add(trackLbl, gbc);

    gbc.gridx = 1;
    ArrayList<String> synthTrackNames = new ArrayList<>();
    ArrayList<Integer> synthTrackIndices = new ArrayList<>();
    if (projectModel != null) {
      for (int i = 0; i < projectModel.getTracks().size(); i++) {
        TrackModel t = projectModel.getTracks().get(i);
        if (t instanceof SynthTrackModel) {
          synthTrackNames.add(t.getName() != null ? t.getName() : "Synth Track " + (i + 1));
          synthTrackIndices.add(i);
        }
      }
    }
    JComboBox<String> trackCombo = new JComboBox<>(synthTrackNames.toArray(new String[0]));
    trackCombo.setBackground(new Color(0x2d, 0x2d, 0x32));
    trackCombo.setForeground(Color.WHITE);
    panel.add(trackCombo, gbc);

    // Row 7: Action Button!
    gbc.gridx = 0;
    gbc.gridy = 7;
    gbc.gridwidth = 2;
    gbc.fill = GridBagConstraints.NONE;
    gbc.anchor = GridBagConstraints.CENTER;
    JButton generateBtn = new JButton("🎹 GENERATE DIATONIC PROGRESSION  ");
    generateBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
    styleButton(generateBtn, new Color(0x00, 0x4d, 0x3d), Color.WHITE);
    generateBtn.setPreferredSize(new Dimension(300, 30));
    generateBtn.addActionListener(
        e -> {
          if (synthTrackIndices.isEmpty()) {
            JOptionPane.showMessageDialog(
                this, "Error: No active Synth track channel exists to draw chords onto!");
            return;
          }

          int targetTrackIdx = synthTrackIndices.get(trackCombo.getSelectedIndex());
          SynthTrackModel track = (SynthTrackModel) projectModel.getTracks().get(targetTrackIdx);

          // Get or create active sequence clip!
          ClipModel clip;
          if (track.getClips().isEmpty()) {
            clip = new ClipModel("CHORD PROGRESSION", 8, 16);
            track.addClip(clip);
          } else {
            clip =
                track
                    .getClips()
                    .get(track.getActiveClipIndex() >= 0 ? track.getActiveClipIndex() : 0);
            // Clear all standard step cells in the grid to write fresh chord notes!
            for (int r = 0; r < clip.getRowCount(); r++) {
              for (int s = 0; s < clip.getStepCount(); s++) {
                clip.setStep(r, s, org.deluge.model.StepData.empty());
              }
            }
          }

          // 1. Resolve Root Note Center
          int baseRoot =
              60 + rootCombo.getSelectedIndex(); // standard MIDI pitch center starting at C4 (60)

          // 2. Parse degrees progression list
          ArrayList<Integer> degrees = new ArrayList<>();
          int selectedProg = progressionCombo.getSelectedIndex();
          if (selectedProg == 0) { // I - IV - V - I
            degrees.add(0);
            degrees.add(3);
            degrees.add(4);
            degrees.add(0);
          } else if (selectedProg == 1) { // ii - V - I
            degrees.add(1);
            degrees.add(4);
            degrees.add(0);
            degrees.add(0);
          } else if (selectedProg == 2) { // I - V - vi - IV
            degrees.add(0);
            degrees.add(4);
            degrees.add(5);
            degrees.add(3);
          } else if (selectedProg == 3) { // i - bVI - bIII - bVII
            degrees.add(0);
            degrees.add(5);
            degrees.add(2);
            degrees.add(6);
          } else { // Custom progression degrees CSV
            try {
              String[] parts = customField.getText().split(",");
              for (String p : parts) {
                degrees.add(Integer.parseInt(p.trim()));
              }
            } catch (Exception ex) {
              JOptionPane.showMessageDialog(this, "Error parsing custom degree integers list!");
              return;
            }
          }

          // If progression has less than 4 steps, repeat last index; if wider, crop to first 4
          // bars grid boundaries!
          while (degrees.size() < 4) {
            degrees.add(0);
          }

          org.deluge.model.tuning.ScalaScale activeScale =
              org.deluge.model.tuning.ScalaScale.getActiveScale();
          int scaleSteps = (activeScale != null) ? activeScale.getStepsCount() : 12;
          int spacing = Math.max(1, Math.round(scaleSteps / 7.0f)); // spacer for chord stacking

          int voicingType = voicingCombo.getSelectedIndex();
          int rhythmType = rhythmCombo.getSelectedIndex();

          // Standard 12-TET Major/Minor fallback diatonic key intervals maps
          int[] majorIntervals = {0, 2, 4, 5, 7, 9, 11};
          int[] minorIntervals = {0, 2, 3, 5, 7, 8, 10};
          boolean isMinorProg = (selectedProg == 3);

          // Loop through 4 bars grids steps columns
          for (int bar = 0; bar < 4; bar++) {
            int degree = degrees.get(bar);

            // Generate raw pitches for the chord stack
            ArrayList<Integer> chordPitches = new ArrayList<>();

            if (activeScale == null) {
              // Standard 12-TET Diatonic scale interval map!
              int[] intervals = isMinorProg ? minorIntervals : majorIntervals;
              int rootOffset = intervals[Math.abs(degree) % 7] + 12 * (degree / 7);
              int thirdOffset = intervals[Math.abs(degree + 2) % 7] + 12 * ((degree + 2) / 7);
              int fifthOffset = intervals[Math.abs(degree + 4) % 7] + 12 * ((degree + 4) / 7);
              int seventhOffset = intervals[Math.abs(degree + 6) % 7] + 12 * ((degree + 6) / 7);

              if (voicingType == 0) { // Triad
                chordPitches.add(baseRoot + rootOffset);
                chordPitches.add(baseRoot + thirdOffset);
                chordPitches.add(baseRoot + fifthOffset);
              } else if (voicingType == 1) { // 7ths
                chordPitches.add(baseRoot + rootOffset);
                chordPitches.add(baseRoot + thirdOffset);
                chordPitches.add(baseRoot + fifthOffset);
                chordPitches.add(baseRoot + seventhOffset);
              } else if (voicingType == 2) { // Sus4
                int susOffset = intervals[Math.abs(degree + 3) % 7] + 12 * ((degree + 3) / 7);
                chordPitches.add(baseRoot + rootOffset);
                chordPitches.add(baseRoot + susOffset);
                chordPitches.add(baseRoot + fifthOffset);
              } else { // Spread pads: root + 5th + octave up
                chordPitches.add(baseRoot + rootOffset);
                chordPitches.add(baseRoot + fifthOffset);
                chordPitches.add(baseRoot + rootOffset + 12);
              }
            } else {
              // Custom Microtonal scale layout: degrees are step pitch notes directly!
              int rootOffset = degree;
              int thirdOffset = degree + spacing;
              int fifthOffset = degree + 2 * spacing;
              int seventhOffset = degree + 3 * spacing;

              if (voicingType == 0) { // Triad
                chordPitches.add(baseRoot + rootOffset);
                chordPitches.add(baseRoot + thirdOffset);
                chordPitches.add(baseRoot + fifthOffset);
              } else if (voicingType == 1) { // 7ths
                chordPitches.add(baseRoot + rootOffset);
                chordPitches.add(baseRoot + thirdOffset);
                chordPitches.add(baseRoot + fifthOffset);
                chordPitches.add(baseRoot + seventhOffset);
              } else if (voicingType == 2) { // Sus4 (Diatonic step 3 spacing!)
                chordPitches.add(baseRoot + rootOffset);
                chordPitches.add(baseRoot + degree + (int) Math.round(spacing * 1.5));
                chordPitches.add(baseRoot + fifthOffset);
              } else { // Spread pads: root + 5th + octave degree steps count!
                chordPitches.add(baseRoot + rootOffset);
                chordPitches.add(baseRoot + fifthOffset);
                chordPitches.add(baseRoot + rootOffset + scaleSteps);
              }
            }

            // Apply rhythm pattern styles writes
            int barStartCol = bar * 4;
            if (rhythmType == 0) { // Whole Notes (1 chord per bar, gate = 4.0)
              for (int pitch : chordPitches) {
                int rIdx = Math.max(0, Math.min(127, 127 - pitch));
                clip.setStep(
                    rIdx, barStartCol, org.deluge.model.StepData.of(true, pitch, 1.0f, 4.0f, 0));
              }
            } else if (rhythmType == 1) { // Half Notes (2 chords per bar, gate = 2.0)
              for (int pitch : chordPitches) {
                int rIdx = Math.max(0, Math.min(127, 127 - pitch));
                clip.setStep(
                    rIdx, barStartCol, org.deluge.model.StepData.of(true, pitch, 1.0f, 2.0f, 0));
                clip.setStep(
                    rIdx,
                    barStartCol + 2,
                    org.deluge.model.StepData.of(true, pitch, 1.0f, 2.0f, 0));
              }
            } else if (rhythmType == 2) { // Stabs (4 staccato stabs, gate = 0.25)
              for (int sIdx = 0; sIdx < 4; sIdx++) {
                for (int pitch : chordPitches) {
                  int rIdx = Math.max(0, Math.min(127, 127 - pitch));
                  clip.setStep(
                      rIdx,
                      barStartCol + sIdx,
                      org.deluge.model.StepData.of(true, pitch, 1.0f, 0.25f, 0));
                }
              }
            } else { // Arpeggiated Wave (1/16th rolling arps waves!)
              for (int sIdx = 0; sIdx < 4; sIdx++) {
                int noteIndex = sIdx % chordPitches.size();
                int pitch = chordPitches.get(noteIndex);
                int rIdx = Math.max(0, Math.min(127, 127 - pitch));
                clip.setStep(
                    rIdx,
                    barStartCol + sIdx,
                    org.deluge.model.StepData.of(true, pitch, 1.0f, 1.0f, 0));
              }
            }
          }

          // Sync and force grid graphics repaints!
          if (parentFrame instanceof SwingDelugeApp app) {
            app.getActiveGridPanel().refresh();
          }
          JOptionPane.showMessageDialog(
              this, "Successfully generated chord progression on track: " + track.getName());
        });
    panel.add(generateBtn, gbc);

    return panel;
  }

  private void styleButton(JButton btn, Color bg, Color fg) {
    btn.setContentAreaFilled(false);
    btn.setOpaque(true);
    btn.setFocusPainted(false);
    btn.setBackground(bg);
    btn.setForeground(fg);
    btn.setBorder(BorderFactory.createLineBorder(fg, 1));
  }
}
