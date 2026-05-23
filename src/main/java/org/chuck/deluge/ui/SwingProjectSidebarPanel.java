package org.chuck.deluge.ui;

import java.awt.*;
import java.io.*;
import java.nio.file.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.tree.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

public class SwingProjectSidebarPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> onSongLoaded;
  private java.util.function.Consumer<org.chuck.deluge.model.TrackModel> onTrackAdded;
  private java.util.function.Consumer<java.io.File> onPatternLoad;
  private Runnable onPatternSave;

  private JSlider volSlider;
  private JSlider lpfSlider;
  private JSlider attSlider;

  private final org.chuck.deluge.midi.MidiService midiService;

  private JTextArea scriptArea;
  private String activeScriptPath;
  private JTabbedPane tabs;
  private JPanel ckParamsBox;
  private DefaultMutableTreeNode libraryRoot;
  private JTree libraryTree;

  public SwingProjectSidebarPanel(
      ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiService = midiService;

    setPreferredSize(new Dimension(300, 0));
    setBackground(new Color(0x12, 0x12, 0x14));
    setLayout(new BorderLayout());

    tabs = new JTabbedPane();
    tabs.setBackground(new Color(0x12, 0x12, 0x14));
    tabs.setForeground(Color.LIGHT_GRAY);
    tabs.setFont(new Font("SansSerif", Font.BOLD, 10));

    tabs.addTab("LIBRARY", createLibraryTab());
    tabs.addTab("EDITOR", createEditorTab());
    tabs.addTab("MIDI", createMidiTab());
    tabs.addTab("SCRIPT", createScriptTab());
    tabs.addTab("PROFILER", createProfilerTab());
    tabs.addTab("SNIPPETS", createSnippetsTab());

    add(tabs, BorderLayout.CENTER);
  }

  /** Reload the library tree from preferences directories. Call after samples dir changes. */
  public void reloadLibrary() {
    File kitsDir = org.chuck.deluge.project.PreferencesManager.getKitsDir();
    File synthsDir = org.chuck.deluge.project.PreferencesManager.getSynthsDir();
    File songsDir = org.chuck.deluge.project.PreferencesManager.getSongsDir();
    File midiDevicesDir = org.chuck.deluge.project.PreferencesManager.getMidiDevicesDir();
    File patternsDir = org.chuck.deluge.project.PreferencesManager.getPatternsDir();
    System.out.println(
        "reloadLibrary: kitsDir="
            + kitsDir
            + " exists="
            + (kitsDir != null && kitsDir.isDirectory()));
    System.out.println(
        "reloadLibrary: synthsDir="
            + synthsDir
            + " exists="
            + (synthsDir != null && synthsDir.isDirectory()));
    System.out.println(
        "reloadLibrary: songsDir="
            + songsDir
            + " exists="
            + (songsDir != null && songsDir.isDirectory()));
    libraryRoot.removeAllChildren();
    addDirsToTree(libraryRoot, "KITS", kitsDir);
    addDirsToTree(libraryRoot, "SYNTHS", synthsDir);
    addDirsToTree(libraryRoot, "SONGS", songsDir);
    addDirsToTree(libraryRoot, "MIDI_DEVICES", midiDevicesDir);
    addDirsToTree(libraryRoot, "PATTERNS", patternsDir);
    File examplesDir =
        new File(org.chuck.deluge.project.PreferencesManager.getLibraryDir(), "EXAMPLES");
    if (examplesDir.isDirectory()) {
      addDirsToTree(libraryRoot, "EXAMPLES", examplesDir);
    }
    if (libraryTree != null) {
      ((DefaultTreeModel) libraryTree.getModel()).reload();
      // Expand root and all category nodes so content is visible immediately
      for (int i = 0; i < libraryTree.getRowCount(); i++) {
        libraryTree.expandRow(i);
      }
    }
  }

  private JComponent createLibraryTab() {
    libraryRoot = new DefaultMutableTreeNode("SD CARD");
    addDirsToTree(libraryRoot, "KITS", org.chuck.deluge.project.PreferencesManager.getKitsDir());
    addDirsToTree(
        libraryRoot, "SYNTHS", org.chuck.deluge.project.PreferencesManager.getSynthsDir());
    addDirsToTree(libraryRoot, "SONGS", org.chuck.deluge.project.PreferencesManager.getSongsDir());
    addDirsToTree(
        libraryRoot,
        "MIDI_DEVICES",
        org.chuck.deluge.project.PreferencesManager.getMidiDevicesDir());
    addDirsToTree(
        libraryRoot, "PATTERNS", org.chuck.deluge.project.PreferencesManager.getPatternsDir());
    File examplesDir =
        new File(org.chuck.deluge.project.PreferencesManager.getLibraryDir(), "EXAMPLES");
    if (examplesDir.isDirectory()) {
      addDirsToTree(libraryRoot, "EXAMPLES", examplesDir);
    }

    libraryTree = new JTree(libraryRoot);
    libraryTree.setBackground(new Color(0x12, 0x12, 0x14));
    libraryTree.setFont(new Font("SansSerif", Font.PLAIN, 10));
    libraryTree.setRowHeight(20);

    javax.swing.tree.DefaultTreeCellRenderer renderer =
        new javax.swing.tree.DefaultTreeCellRenderer();
    renderer.setFont(new Font("SansSerif", Font.PLAIN, 10));
    renderer.setBackgroundNonSelectionColor(new Color(0x12, 0x12, 0x14));
    renderer.setTextNonSelectionColor(Color.LIGHT_GRAY);
    renderer.setTextSelectionColor(Color.WHITE);
    renderer.setBackgroundSelectionColor(new Color(0x00, 0xff, 0xcc, 0x33));
    libraryTree.setCellRenderer(renderer);

    libraryTree.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              javax.swing.tree.TreePath path = libraryTree.getSelectionPath();
              if (path != null) {

                javax.swing.tree.DefaultMutableTreeNode node =
                    (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.isLeaf()) {
                  String name = node.getUserObject().toString();
                  // Determine base directory from the top-level category
                  String category = path.getPathComponent(1).toString();
                  File baseDir;
                  switch (category) {
                    case "KITS":
                      baseDir = org.chuck.deluge.project.PreferencesManager.getKitsDir();
                      break;
                    case "SYNTHS":
                      baseDir = org.chuck.deluge.project.PreferencesManager.getSynthsDir();
                      break;
                    case "SONGS":
                      baseDir = org.chuck.deluge.project.PreferencesManager.getSongsDir();
                      break;
                    case "MIDI_DEVICES":
                      baseDir = org.chuck.deluge.project.PreferencesManager.getMidiDevicesDir();
                      break;
                    case "PATTERNS":
                      baseDir = org.chuck.deluge.project.PreferencesManager.getPatternsDir();
                      break;
                    case "EXAMPLES":
                      baseDir =
                          new File(
                              org.chuck.deluge.project.PreferencesManager.getLibraryDir(),
                              "EXAMPLES");
                      break;
                    default:
                      baseDir = null;
                  }
                  if (baseDir == null) return;

                  // Build relative path from category node down (skip root and category)
                  StringBuilder relBuilder = new StringBuilder();
                  for (int i = 2; i < path.getPathCount(); i++) {
                    if (i > 2) relBuilder.append(File.separator);
                    relBuilder.append(path.getPathComponent(i).toString());
                  }
                  String relPath = relBuilder.toString();

                  // Resolve file with extension fallbacks
                  File leafFile = null;
                  String[] tryExts = {".XML", ".xml", ".ck"};
                  for (String ext : tryExts) {
                    File candidate = new File(baseDir, relPath + ext);
                    if (candidate.isFile()) {
                      leafFile = candidate;
                      break;
                    }
                  }
                  if (leafFile == null) return;

                  String fileName = leafFile.getName().toLowerCase();

                  if (fileName.endsWith(".ck")) {
                    System.out.println(
                        "Swing: Loading ChucK script: " + leafFile.getAbsolutePath());
                    activeScriptPath = leafFile.getAbsolutePath();
                    try {
                      String content =
                          new String(
                              java.nio.file.Files.readAllBytes(leafFile.toPath()),
                              java.nio.charset.StandardCharsets.UTF_8);
                      if (scriptArea != null) {
                        scriptArea.setText(content);
                        tabs.setSelectedIndex(1);
                        if (ckParamsBox != null) {
                          ckParamsBox.removeAll();
                          java.util.regex.Pattern p =
                              java.util.regex.Pattern.compile("global\\s+float\\s+([a-zA-Z0-9_]+)");
                          java.util.regex.Matcher m = p.matcher(content);
                          while (m.find()) {
                            String varName = m.group(1);
                            JLabel varLabel = new JLabel(varName + ": 50");
                            styleLabel(varLabel, false);
                            JSlider slider = new JSlider(0, 100, 50);
                            styleSlider(slider);
                            slider.addChangeListener(
                                ev -> {
                                  varLabel.setText(varName + ": " + slider.getValue());
                                  if (vm != null) {
                                    vm.setGlobalFloat(varName, slider.getValue() / 100.0);
                                  }
                                });
                            ckParamsBox.add(varLabel);
                            ckParamsBox.add(slider);
                            ckParamsBox.add(Box.createVerticalStrut(5));
                          }
                          ckParamsBox.revalidate();
                          ckParamsBox.repaint();
                        }
                      }
                    } catch (Exception ex) {
                      ex.printStackTrace();
                    }
                    return;
                  }

                  System.out.println("Swing: Loading Preset: " + leafFile.getAbsolutePath());
                  try (java.io.InputStream is = new java.io.FileInputStream(leafFile)) {
                    if ("KITS".equals(category)) {
                      org.chuck.deluge.model.KitTrackModel kit =
                          org.chuck.deluge.xml.DelugeXmlParser.parseKit(is, name);
                      int baseTrack = 0;
                      java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
                      for (int i = 0; i < sounds.size(); i++) {
                        String sp =
                            ((org.chuck.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
                        vm.setGlobalString("g_sample_" + (baseTrack + i), sp != null ? sp : "");
                        bridge.setSamplePath(baseTrack + i, sp != null ? sp : "");
                        bridge.setMute(baseTrack + i, false);
                      }
                      if (onTrackAdded != null) {
                        onTrackAdded.accept(kit);
                      } else {
                        org.chuck.deluge.model.ProjectModel mockProj =
                            new org.chuck.deluge.model.ProjectModel();
                        mockProj.addTrack(kit);
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(mockProj);
                        }
                      }
                      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    } else if ("SONGS".equals(category)) {
                      org.chuck.deluge.model.ProjectModel loadedProject =
                          org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, name);
                      int engineRow = 0;
                      java.io.File libraryDir =
                          org.chuck.deluge.project.PreferencesManager.getLibraryDir();
                      java.util.ArrayList<String> missingFiles = new java.util.ArrayList<>();
                      for (org.chuck.deluge.model.TrackModel track : loadedProject.getTracks()) {
                        if (engineRow >= org.chuck.deluge.BridgeContract.TRACKS) break;
                        if (track instanceof org.chuck.deluge.model.KitTrackModel kit) {
                          java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
                          for (int i = 0; i < sounds.size(); i++) {
                            String sp =
                                ((org.chuck.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
                            // Check if sample file exists on disk
                            if (sp != null && !sp.isEmpty()) {
                              java.io.File sf = new java.io.File(sp);
                              if (!sf.exists()) {
                                sf = new java.io.File(libraryDir, sp);
                              }
                              if (!sf.exists()) {
                                missingFiles.add(sp);
                              }
                            }
                            vm.setGlobalString("g_sample_" + (engineRow + i), sp != null ? sp : "");
                            bridge.setMute(engineRow + i, false);
                            bridge.setTrackType(engineRow + i, 0);
                          }
                        }
                        engineRow++;
                      }
                      if (!missingFiles.isEmpty()) {
                        StringBuilder sb = new StringBuilder("Missing sample files:\n");
                        for (String mf : missingFiles) {
                          sb.append("  - ").append(mf).append("\n");
                        }
                        System.err.println(sb.toString());
                        javax.swing.JOptionPane.showMessageDialog(
                            SwingProjectSidebarPanel.this,
                            sb.toString(),
                            "Samples Not Found",
                            javax.swing.JOptionPane.ERROR_MESSAGE);
                      }
                      if (onSongLoaded != null) {
                        onSongLoaded.accept(loadedProject);
                      }
                      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    } else if ("SYNTHS".equals(category)) {
                      org.chuck.deluge.model.SynthTrackModel synth =
                          org.chuck.deluge.xml.DelugeXmlParser.parseSynth(is, name);
                      if (onTrackAdded != null) {
                        onTrackAdded.accept(synth);
                      } else {
                        org.chuck.deluge.model.ProjectModel mockProj =
                            new org.chuck.deluge.model.ProjectModel();
                        mockProj.addTrack(synth);
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(mockProj);
                        }
                      }
                    } else if ("PATTERNS".equals(category)) {
                      if (onPatternLoad != null) {
                        onPatternLoad.accept(leafFile);
                      }
                    }
                  } catch (Exception ex) {
                    ex.printStackTrace();
                  }
                }
              }
            }
          }
        });

    JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
    buttonPanel.setBackground(new Color(0x12, 0x12, 0x14));

    JButton shuffleBtn = new JButton("🎲 SHUFFLE KIT");
    shuffleBtn.setContentAreaFilled(false);
    shuffleBtn.setOpaque(true);
    shuffleBtn.setFocusPainted(false);
    shuffleBtn.setBackground(new Color(0x1e, 0x32, 0x32));
    shuffleBtn.setForeground(new Color(0x00, 0xff, 0xcc));
    shuffleBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    shuffleBtn.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xff, 0xcc), 1));
    shuffleBtn.setMargin(new Insets(3, 8, 3, 8));

    shuffleBtn.addActionListener(
        e -> {
          System.out.println("Shuffle requested. Implement dynamic discovery of samples.");
        });
    buttonPanel.add(shuffleBtn);

    JButton savePatternBtn = new JButton("💾 SAVE PATTERN");
    savePatternBtn.setContentAreaFilled(false);
    savePatternBtn.setOpaque(true);
    savePatternBtn.setFocusPainted(false);
    savePatternBtn.setBackground(new Color(0x1e, 0x32, 0x22));
    savePatternBtn.setForeground(new Color(0x33, 0xff, 0x33));
    savePatternBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    savePatternBtn.setBorder(BorderFactory.createLineBorder(new Color(0x33, 0xff, 0x33), 1));
    savePatternBtn.setMargin(new Insets(3, 8, 3, 8));
    savePatternBtn.addActionListener(
        e -> {
          if (onPatternSave != null) onPatternSave.run();
        });
    buttonPanel.add(savePatternBtn);

    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.setBackground(new Color(0x12, 0x12, 0x14));
    wrapper.add(buttonPanel, BorderLayout.NORTH);
    JScrollPane scrollPane = new JScrollPane(libraryTree);
    scrollPane.setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0x2d, 0x2d, 0x32)));
    wrapper.add(scrollPane, BorderLayout.CENTER);

    return wrapper;
  }

  public java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> getOnSongLoaded() {
    return onSongLoaded;
  }

  public void setOnSongLoaded(
      java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> callback) {
    this.onSongLoaded = callback;
  }

  public void setOnTrackAdded(
      java.util.function.Consumer<org.chuck.deluge.model.TrackModel> callback) {
    this.onTrackAdded = callback;
  }

  /** Callback invoked when user double-clicks a PATTERN file to load it into the active clip. */
  public void setOnPatternLoad(java.util.function.Consumer<java.io.File> callback) {
    this.onPatternLoad = callback;
  }

  /** Callback invoked when user clicks "Save Pattern" button. */
  public void setOnPatternSave(Runnable callback) {
    this.onPatternSave = callback;
  }

  /** Recursively add directories and XML/CK files from a filesystem directory to the tree. */
  private void addDirsToTree(javax.swing.tree.DefaultMutableTreeNode root, String label, File dir) {
    if (dir == null || !dir.isDirectory()) return;
    javax.swing.tree.DefaultMutableTreeNode folder =
        new javax.swing.tree.DefaultMutableTreeNode(label);
    root.add(folder);
    buildFileTree(folder, dir);
  }

  private void buildFileTree(javax.swing.tree.DefaultMutableTreeNode node, File dir) {
    File[] entries = dir.listFiles();
    if (entries == null) return;
    java.util.Arrays.sort(
        entries, (a, b) -> a.getName().toUpperCase().compareTo(b.getName().toUpperCase()));
    for (File f : entries) {
      if (f.isDirectory()) {
        javax.swing.tree.DefaultMutableTreeNode sub =
            new javax.swing.tree.DefaultMutableTreeNode(f.getName());
        node.add(sub);
        buildFileTree(sub, f);
      } else {
        String fn = f.getName().toUpperCase();
        if (fn.endsWith(".XML") || fn.endsWith(".CK")) {
          int dot = fn.lastIndexOf('.');
          String display = dot != -1 ? f.getName().substring(0, dot) : f.getName();
          node.add(new javax.swing.tree.DefaultMutableTreeNode(display));
        }
      }
    }
  }

  private JComponent createEditorTab() {

    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBackground(new Color(0x12, 0x12, 0x14));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    ckParamsBox = createSection("CHUCK PARAMS");
    panel.add(ckParamsBox);
    panel.add(Box.createVerticalStrut(15));

    // Oscillators Section
    JPanel oscBox = createSection("OSCILLATORS");

    JLabel osc1TypeLabel = new JLabel("Osc 1 Type:");
    styleLabel(osc1TypeLabel, false);
    JComboBox<String> osc1TypeCombo =
        new JComboBox<>(new String[] {"Sine", "Saw", "Square", "Triangle", "Noise"});
    styleCombo(osc1TypeCombo);
    oscBox.add(osc1TypeLabel);
    oscBox.add(osc1TypeCombo);

    oscBox.add(Box.createVerticalStrut(5));

    JLabel osc1Label = new JLabel("Osc 1 Vol: 64");
    styleLabel(osc1Label, false);
    volSlider = new JSlider(0, 127, 64);
    styleSlider(volSlider);

    volSlider.addChangeListener(e -> osc1Label.setText("Osc 1 Vol: " + volSlider.getValue()));
    oscBox.add(osc1Label);
    oscBox.add(volSlider);
    oscBox.add(Box.createVerticalStrut(10));

    JLabel osc2TypeLabel = new JLabel("Osc 2 Type:");
    styleLabel(osc2TypeLabel, false);
    JComboBox<String> osc2TypeCombo =
        new JComboBox<>(new String[] {"Sine", "Saw", "Square", "Triangle", "Noise"});
    styleCombo(osc2TypeCombo);
    oscBox.add(osc2TypeLabel);
    oscBox.add(osc2TypeCombo);

    oscBox.add(Box.createVerticalStrut(5));

    JLabel osc2Label = new JLabel("Osc 2 Vol: 0");
    styleLabel(osc2Label, false);
    JSlider vol2Slider = new JSlider(0, 127, 0);
    styleSlider(vol2Slider);
    vol2Slider.addChangeListener(e -> osc2Label.setText("Osc 2 Vol: " + vol2Slider.getValue()));
    oscBox.add(osc2Label);
    oscBox.add(vol2Slider);

    JLabel pwLabel = new JLabel("Pulse Width: 50");
    styleLabel(pwLabel, false);
    JSlider pwSlider = new JSlider(0, 100, 50);
    styleSlider(pwSlider);
    pwSlider.addChangeListener(e -> pwLabel.setText("Pulse Width: " + pwSlider.getValue()));
    oscBox.add(pwLabel);
    oscBox.add(pwSlider);
    oscBox.add(Box.createVerticalStrut(10));

    panel.add(oscBox);
    panel.add(Box.createVerticalStrut(15));

    // Modulators
    JPanel modBox = createSection("MODULATORS");
    JLabel mod1Label = new JLabel("Mod 1 Amount: 0");
    styleLabel(mod1Label, false);
    JSlider mod1Slider = new JSlider(0, 127, 0);
    styleSlider(mod1Slider);
    mod1Slider.addChangeListener(e -> mod1Label.setText("Mod 1 Amount: " + mod1Slider.getValue()));
    modBox.add(mod1Label);
    modBox.add(mod1Slider);
    panel.add(modBox);
    panel.add(Box.createVerticalStrut(15));

    // Filters Section
    JPanel filterBox = createSection("FILTERS");
    JLabel lpfLabel = new JLabel("LPF Cutoff: 64");
    styleLabel(lpfLabel, false);
    lpfSlider = new JSlider(0, 127, 64);
    styleSlider(lpfSlider);

    lpfSlider.addChangeListener(
        e -> {
          lpfLabel.setText("LPF Cutoff: " + lpfSlider.getValue());
          if (bridge != null) {
            bridge.setFilterFreq(0, lpfSlider.getValue() / 127.0);
            org.chuck.deluge.firmware.hid.FirmwareDisplay.get()
                .displayNotification("LPF FREQ", String.valueOf(lpfSlider.getValue()));
          }
        });
    filterBox.add(lpfLabel);
    filterBox.add(lpfSlider);
    panel.add(filterBox);
    panel.add(Box.createVerticalStrut(15));

    // Envelopes
    JPanel envBox = createSection("ENVELOPES");

    JLabel attLabel = new JLabel("Attack: 10");
    styleLabel(attLabel, false);
    attSlider = new JSlider(0, 100, 10);
    styleSlider(attSlider);

    attSlider.addChangeListener(
        e -> {
          attLabel.setText("Attack: " + attSlider.getValue());
          if (bridge != null) {
            bridge.setEnv(0, 0, attSlider.getValue() / 100.0, 0.2, 0.8, 0.3);
            org.chuck.deluge.firmware.hid.FirmwareDisplay.get()
                .displayNotification("ENV1 ATT", String.valueOf(attSlider.getValue()));
          }
        });
    envBox.add(attLabel);
    envBox.add(attSlider);
    envBox.add(Box.createVerticalStrut(5));

    JLabel decLabel = new JLabel("Decay: 20");
    styleLabel(decLabel, false);
    JSlider decSlider = new JSlider(0, 100, 20);
    styleSlider(decSlider);
    decSlider.addChangeListener(e -> decLabel.setText("Decay: " + decSlider.getValue()));
    envBox.add(decLabel);
    envBox.add(decSlider);
    envBox.add(Box.createVerticalStrut(5));

    JLabel susLabel = new JLabel("Sustain: 80");
    styleLabel(susLabel, false);
    JSlider susSlider = new JSlider(0, 100, 80);
    styleSlider(susSlider);
    susSlider.addChangeListener(e -> susLabel.setText("Sustain: " + susSlider.getValue()));
    envBox.add(susLabel);
    envBox.add(susSlider);
    envBox.add(Box.createVerticalStrut(5));

    JLabel relLabel = new JLabel("Release: 30");
    styleLabel(relLabel, false);
    JSlider relSlider = new JSlider(0, 100, 30);
    styleSlider(relSlider);
    relSlider.addChangeListener(e -> relLabel.setText("Release: " + relSlider.getValue()));
    envBox.add(relLabel);
    envBox.add(relSlider);

    panel.add(envBox);
    panel.add(Box.createVerticalStrut(15));

    // Distortions
    JPanel distBox = createSection("DISTORTIONS");
    JLabel bitLabel = new JLabel("Bitcrush Bits: 16");
    styleLabel(bitLabel, false);
    JSlider bitSlider = new JSlider(1, 16, 16);
    styleSlider(bitSlider);
    bitSlider.addChangeListener(e -> bitLabel.setText("Bitcrush Bits: " + bitSlider.getValue()));
    distBox.add(bitLabel);
    distBox.add(bitSlider);
    panel.add(distBox);
    panel.add(Box.createVerticalStrut(15));

    // Master FX section
    JPanel fxBox = createSection("MASTER FX");
    JLabel decimationsLabel = new JLabel("Decimations:");
    styleLabel(decimationsLabel, false);
    fxBox.add(decimationsLabel);
    JSlider decimSlider = new JSlider(0, 100, 0);
    styleSlider(decimSlider);
    fxBox.add(decimSlider);
    panel.add(fxBox);

    JScrollPane scrollPane = new JScrollPane(panel);
    scrollPane.setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    return scrollPane;
  }

  private JComponent createMidiTab() {
    if (midiService == null) {
      JLabel noService = new JLabel("MIDI service not available (testing mode)");
      noService.setForeground(new Color(0x88, 0x88, 0x88));
      noService.setHorizontalAlignment(SwingConstants.CENTER);
      JPanel p = new JPanel(new BorderLayout());
      p.setBackground(new Color(0x12, 0x12, 0x14));
      p.add(noService, BorderLayout.CENTER);
      return p;
    }

    JPanel outer = new JPanel(new BorderLayout(0, 10));
    outer.setBackground(new Color(0x12, 0x12, 0x14));
    outer.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

    // ── Device Selection ──
    java.util.List<org.chuck.deluge.midi.MidiDeviceDefinition> devices =
        org.chuck.deluge.midi.MidiDeviceDefinitionLoader.loadAll();
    JComboBox<org.chuck.deluge.midi.MidiDeviceDefinition> deviceCombo = new JComboBox<>();
    deviceCombo.addItem(null); // "None"
    for (var d : devices) {
      deviceCombo.addItem(d);
    }
    // Select current device if set
    org.chuck.deluge.midi.MidiDeviceDefinition currentDef =
        midiService != null ? midiService.getDeviceDefinition() : null;
    if (currentDef != null) {
      for (int i = 0; i < deviceCombo.getItemCount(); i++) {
        var item = deviceCombo.getItemAt(i);
        if (item != null && item.getId().equals(currentDef.getId())) {
          deviceCombo.setSelectedIndex(i);
          break;
        }
      }
    }
    deviceCombo.addActionListener(
        e -> {
          org.chuck.deluge.midi.MidiDeviceDefinition selected =
              (org.chuck.deluge.midi.MidiDeviceDefinition) deviceCombo.getSelectedItem();
          midiService.setDeviceDefinition(selected);
          rebuildMidiTable(outer);
        });
    styleCombo(deviceCombo);
    deviceCombo.setRenderer(
        new DefaultListCellRenderer() {
          @Override
          public Component getListCellRendererComponent(
              JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value == null) {
              setText("— None —");
            } else if (value instanceof org.chuck.deluge.midi.MidiDeviceDefinition d) {
              setText(d.getName() != null ? d.getName() : d.getId());
            }
            return this;
          }
        });

    JPanel devicePanel = new JPanel(new BorderLayout(5, 0));
    devicePanel.setBackground(new Color(0x12, 0x12, 0x14));
    JLabel deviceLabel = new JLabel("Device:");
    styleLabel(deviceLabel, false);
    devicePanel.add(deviceLabel, BorderLayout.WEST);
    devicePanel.add(deviceCombo, BorderLayout.CENTER);

    // ── CC Mapping Table ──
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
      tableData = new String[][] {{"— No mappings —", "", ""}};
    }
    String[] cols = {"Parameter", "CC", "State"};
    JTable mappingTable = new JTable(tableData, cols);
    mappingTable.setBackground(new Color(0x18, 0x18, 0x1c));
    mappingTable.setForeground(Color.WHITE);
    mappingTable.setFont(new Font("SansSerif", Font.PLAIN, 10));
    mappingTable.setRowHeight(20);
    mappingTable.setGridColor(new Color(0x2d, 0x2d, 0x32));
    mappingTable.getTableHeader().setBackground(new Color(0x2d, 0x2d, 0x32));
    mappingTable.getTableHeader().setForeground(Color.LIGHT_GRAY);
    mappingTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 10));

    JPanel learnSection = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5));
    learnSection.setBackground(new Color(0x12, 0x12, 0x14));
    JTextField learnParamField = new JTextField(15);
    learnParamField.setBackground(new Color(0x18, 0x18, 0x1c));
    learnParamField.setForeground(Color.WHITE);
    learnParamField.setCaretColor(Color.WHITE);
    learnParamField.setFont(new Font("SansSerif", Font.PLAIN, 10));
    learnParamField.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));
    learnParamField.setToolTipText("Parameter name to learn (e.g. g_master_vol)");
    JButton learnBtn = new JButton("LEARN");
    learnBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    learnBtn.setBackground(new Color(0x1e, 0x32, 0x32));
    learnBtn.setForeground(new Color(0x00, 0xff, 0xcc));
    learnBtn.setBorder(BorderFactory.createLineBorder(new Color(0x00, 0xff, 0xcc), 1));
    learnBtn.setFocusPainted(false);
    learnBtn.setContentAreaFilled(false);
    learnBtn.setOpaque(true);
    learnBtn.setMargin(new Insets(3, 8, 3, 8));

    JLabel learnStatus = new JLabel("");
    learnStatus.setForeground(new Color(0xff, 0xcc, 0x00));
    learnStatus.setFont(new Font("SansSerif", Font.PLAIN, 10));

    learnBtn.addActionListener(
        e -> {
          String param = learnParamField.getText().trim();
          if (param.isEmpty()) {
            learnStatus.setText("Enter a param name");
            return;
          }
          midiService.startLearn(param);
          learnStatus.setText("Waiting for CC on " + param + "...");
          learnBtn.setEnabled(false);
          // Re-enable after timeout
          Timer timer =
              new Timer(
                  10000,
                  ev -> {
                    learnBtn.setEnabled(true);
                    if (midiService.isLearning()) {
                      learnStatus.setText("Learn timed out");
                    } else {
                      learnStatus.setText("Learned!");
                      rebuildMidiTable(outer);
                    }
                  });
          timer.setRepeats(false);
          timer.start();
        });
    JLabel learnLabel = new JLabel("Learn:");
    styleLabel(learnLabel, false);
    learnSection.add(learnLabel);
    learnSection.add(learnParamField);
    learnSection.add(learnBtn);
    learnSection.add(learnStatus);

    JPanel tableSection = new JPanel(new BorderLayout(0, 5));
    tableSection.setBackground(new Color(0x12, 0x12, 0x14));
    JScrollPane tableScroll = new JScrollPane(mappingTable);
    tableScroll.setBackground(new Color(0x12, 0x12, 0x14));
    tableScroll.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    tableScroll.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));
    tableSection.add(tableScroll, BorderLayout.CENTER);
    tableSection.add(learnSection, BorderLayout.SOUTH);

    // ── Follow Mode Controls ──
    JPanel followPanel = new JPanel();
    followPanel.setLayout(new BoxLayout(followPanel, BoxLayout.Y_AXIS));
    followPanel.setBackground(new Color(0x18, 0x18, 0x1c));
    followPanel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)),
            "MIDI Follow Mode",
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font("SansSerif", Font.BOLD, 10),
            Color.LIGHT_GRAY));

    JCheckBox followEnable = new JCheckBox("Enabled");
    followEnable.setForeground(Color.LIGHT_GRAY);
    followEnable.setBackground(new Color(0x18, 0x18, 0x1c));
    followEnable.setFont(new Font("SansSerif", Font.PLAIN, 10));
    followEnable.setSelected(
        org.chuck.deluge.project.PreferencesManager.get("midi.follow.enabled", "true")
            .equals("true"));
    followEnable.addActionListener(
        e -> {
          org.chuck.deluge.project.PreferencesManager.set(
              "midi.follow.enabled", String.valueOf(followEnable.isSelected()));
        });
    followPanel.add(followEnable);

    String[] midiChannels = {
      "1", "2", "3", "4", "5", "6", "7", "8",
      "9", "10", "11", "12", "13", "14", "15", "16"
    };
    String[] trackLabels = {
      "Track 1",
      "Track 2",
      "Track 3",
      "Track 4",
      "Track 5",
      "Track 6",
      "Track 7",
      "Track 8",
      "Track 9",
      "Track 10",
      "Track 11",
      "Track 12",
      "Track 13",
      "Track 14",
      "Track 15",
      "Track 16"
    };
    char[] followLabels = {'A', 'B', 'C'};
    for (int i = 0; i < 3; i++) {
      final char fLabel = followLabels[i];
      JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
      row.setBackground(new Color(0x18, 0x18, 0x1c));
      JLabel fl = new JLabel("Channel " + fLabel + ":");
      styleLabel(fl, true);
      JComboBox<String> chCombo = new JComboBox<>(midiChannels);
      int savedCh =
          Integer.parseInt(
              org.chuck.deluge.project.PreferencesManager.get("midi.follow.ch" + fLabel, "1"));
      chCombo.setSelectedIndex(savedCh - 1);
      styleCombo(chCombo);
      chCombo.setMaximumSize(new Dimension(50, 20));
      chCombo.setPreferredSize(new Dimension(50, 20));
      chCombo.addActionListener(
          e -> {
            org.chuck.deluge.project.PreferencesManager.set(
                "midi.follow.ch" + fLabel, String.valueOf(chCombo.getSelectedIndex() + 1));
          });
      JComboBox<String> trCombo = new JComboBox<>(trackLabels);
      int savedTr =
          Integer.parseInt(
              org.chuck.deluge.project.PreferencesManager.get(
                  "midi.follow.track" + fLabel, String.valueOf(i)));
      trCombo.setSelectedIndex(Math.min(savedTr, 15));
      styleCombo(trCombo);
      trCombo.setMaximumSize(new Dimension(85, 20));
      trCombo.setPreferredSize(new Dimension(85, 20));
      trCombo.addActionListener(
          e -> {
            org.chuck.deluge.project.PreferencesManager.set(
                "midi.follow.track" + fLabel, String.valueOf(trCombo.getSelectedIndex()));
          });
      JLabel midiChLbl = new JLabel("MIDI Ch:");
      styleLabel(midiChLbl, false);
      JLabel trLbl = new JLabel("→ Track:");
      styleLabel(trLbl, false);
      row.add(fl);
      row.add(midiChLbl);
      row.add(chCombo);
      row.add(trLbl);
      row.add(trCombo);
      followPanel.add(row);
    }

    // ── Assemble ──
    JPanel north = new JPanel(new BorderLayout(0, 10));
    north.setBackground(new Color(0x12, 0x12, 0x14));
    north.add(devicePanel, BorderLayout.NORTH);
    north.add(tableSection, BorderLayout.CENTER);

    outer.add(north, BorderLayout.NORTH);
    outer.add(followPanel, BorderLayout.SOUTH);

    JScrollPane scrollPane = new JScrollPane(outer);
    scrollPane.setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    return scrollPane;
  }

  /** Rebuilds the MIDI tab table section after device/learn changes. */
  private void rebuildMidiTable(JPanel outer) {
    Component[] kids = outer.getComponents();
    for (int i = 0; i < kids.length; i++) {
      if (kids[i] instanceof JScrollPane) {
        JScrollPane sp = (JScrollPane) kids[i];
        JViewport vp = sp.getViewport();
        if (vp.getView() instanceof JPanel inner) {
          Component[] innerKids = inner.getComponents();
          for (int j = 0; j < innerKids.length; j++) {
            if (innerKids[j] instanceof JPanel
                && ((JPanel) innerKids[j]).getComponentCount() > 0
                && ((JPanel) innerKids[j]).getComponent(0) instanceof JScrollPane) {
              // This is the table section — rebuild it
              rebuildTableContent((JPanel) innerKids[j]);
              break;
            }
          }
        }
        break;
      }
    }
  }

  private void rebuildTableContent(JPanel tableSection) {
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
      tableData = new String[][] {{"— No mappings —", "", ""}};
    }
    String[] cols = {"Parameter", "CC", "State"};
    JTable freshTable = new JTable(tableData, cols);
    freshTable.setBackground(new Color(0x18, 0x18, 0x1c));
    freshTable.setForeground(Color.WHITE);
    freshTable.setFont(new Font("SansSerif", Font.PLAIN, 10));
    freshTable.setRowHeight(20);
    freshTable.setGridColor(new Color(0x2d, 0x2d, 0x32));
    freshTable.getTableHeader().setBackground(new Color(0x2d, 0x2d, 0x32));
    freshTable.getTableHeader().setForeground(Color.LIGHT_GRAY);
    freshTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 10));

    // Replace scrollpane view
    Component[] kids = tableSection.getComponents();
    for (Component k : kids) {
      if (k instanceof JScrollPane) {
        ((JScrollPane) k).setViewportView(freshTable);
        break;
      }
    }
  }

  public void updateFocusTrack(int trackId) {
    if (vm == null) return;
    try {
      Object filterObj = vm.getGlobalObject(BridgeContract.G_FILTER);
      if (filterObj instanceof org.chuck.core.ChuckArray arr) {
        double freq = arr.getFloat(trackId * 2);
        if (lpfSlider != null) {
          lpfSlider.setValue((int) (freq * 127));
        }
      }
    } catch (Exception ex) {
    }
  }

  private JComponent createScriptTab() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBackground(new Color(0x12, 0x12, 0x14));

    scriptArea = new JTextArea();
    scriptArea.setBackground(new Color(0x18, 0x18, 0x1c));
    scriptArea.setForeground(Color.GREEN);
    scriptArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
    scriptArea.setCaretColor(Color.WHITE);

    JTextArea logArea = new JTextArea("Console logs: Ready");
    logArea.setBackground(new Color(0x12, 0x12, 0x14));
    logArea.setForeground(Color.CYAN);
    logArea.setFont(new Font("Monospaced", Font.PLAIN, 10));
    logArea.setEditable(false);

    JButton reloadBtn = new JButton("💾 SAVE & RELOAD");
    reloadBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    reloadBtn.setBackground(new Color(0x1e, 0x32, 0x22));
    reloadBtn.setForeground(new Color(0x33, 0xff, 0x33));
    reloadBtn.setBorder(BorderFactory.createLineBorder(new Color(0x33, 0xff, 0x33), 1));
    reloadBtn.setFocusPainted(false);
    reloadBtn.setContentAreaFilled(false);
    reloadBtn.setOpaque(true);
    reloadBtn.setMargin(new Insets(3, 8, 3, 8));

    reloadBtn.addActionListener(
        e -> {
          if (scriptArea == null) return;
          JFileChooser chooser = new JFileChooser();
          chooser.setDialogTitle("Save ChucK Script Externally");
          if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            java.io.File outFile = chooser.getSelectedFile();
            try (java.io.FileWriter writer = new java.io.FileWriter(outFile)) {
              writer.write(scriptArea.getText());
              logArea.setText(
                  "[Compiler]: Script saved externally to:\n" + outFile.getAbsolutePath());
            } catch (Exception ex) {
              logArea.setText("[Error]: Failed to write script:\n" + ex.getMessage());
            }
          }
        });

    JPanel btnWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
    btnWrapper.setBackground(new Color(0x12, 0x12, 0x14));
    btnWrapper.add(reloadBtn);
    panel.add(btnWrapper, BorderLayout.NORTH);

    JPanel centerSplit = new JPanel(new GridLayout(2, 1, 5, 5));
    centerSplit.setBackground(new Color(0x12, 0x12, 0x14));
    JScrollPane scriptScroll = new JScrollPane(scriptArea);
    scriptScroll.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));
    JScrollPane logScroll = new JScrollPane(logArea);
    logScroll.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));
    centerSplit.add(scriptScroll);
    centerSplit.add(logScroll);
    panel.add(centerSplit, BorderLayout.CENTER);

    return panel;
  }

  private JComponent createProfilerTab() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBackground(new Color(0x12, 0x12, 0x14));

    DefaultListModel<String> model = new DefaultListModel<>();
    model.addElement("Shred 1: DelugeEngineDSL (Master Clock)");
    model.addElement("Shred 2: custom_fm.ck (Active)");
    JList<String> list = new JList<>(model);
    list.setBackground(new Color(0x18, 0x18, 0x1c));
    list.setForeground(Color.CYAN);
    list.setFont(new Font("SansSerif", Font.PLAIN, 10));

    JButton killBtn = new JButton("KILL SELECTED SHRED");
    killBtn.setFont(new Font("SansSerif", Font.BOLD, 10));
    killBtn.setBackground(new Color(0x4a, 0x1a, 0x1a));
    killBtn.setForeground(new Color(0xff, 0x55, 0x55));
    killBtn.setBorder(BorderFactory.createLineBorder(new Color(0xff, 0x55, 0x55), 1));
    killBtn.setFocusPainted(false);
    killBtn.setContentAreaFilled(false);
    killBtn.setOpaque(true);
    killBtn.setMargin(new Insets(3, 8, 3, 8));
    killBtn.addActionListener(
        e -> {
          int idx = list.getSelectedIndex();
          if (idx != -1) {
            model.remove(idx);
          }
        });

    JPanel btnWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
    btnWrapper.setBackground(new Color(0x12, 0x12, 0x14));
    btnWrapper.add(killBtn);

    panel.add(btnWrapper, BorderLayout.SOUTH);
    JScrollPane scrollPane = new JScrollPane(list);
    scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));
    panel.add(scrollPane, BorderLayout.CENTER);
    return panel;
  }

  private JComponent createSnippetsTab() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBackground(new Color(0x12, 0x12, 0x14));

    String[] snippets = {"SawOsc Synth", "Pulse Lead", "Noise Percussion"};
    JList<String> list = new JList<>(snippets);
    list.setBackground(new Color(0x18, 0x18, 0x1c));
    list.setForeground(Color.WHITE);
    list.setFont(new Font("SansSerif", Font.PLAIN, 10));

    list.addMouseListener(
        new java.awt.event.MouseAdapter() {
          public void mouseClicked(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              String val = list.getSelectedValue();
              if ("SawOsc Synth".equals(val) && scriptArea != null) {
                scriptArea.append("\nSawOsc osc => ADSR env => dac;\n");
              }
            }
          }
        });

    JLabel infoLabel = new JLabel("Double-click snippet to insert:");
    styleLabel(infoLabel, true);
    JPanel infoWrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
    infoWrapper.setBackground(new Color(0x12, 0x12, 0x14));
    infoWrapper.add(infoLabel);

    panel.add(infoWrapper, BorderLayout.NORTH);
    JScrollPane scrollPane = new JScrollPane(list);
    scrollPane.setBorder(BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)));
    panel.add(scrollPane, BorderLayout.CENTER);
    return panel;
  }

  private JPanel createSection(String title) {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBackground(new Color(0x18, 0x18, 0x1c));
    panel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(0x2d, 0x2d, 0x32)),
            title,
            0,
            0,
            new Font("SansSerif", Font.BOLD, 9),
            Color.LIGHT_GRAY));
    return panel;
  }

  private void styleLabel(JLabel label, boolean bold) {
    label.setForeground(Color.LIGHT_GRAY);
    label.setFont(new Font("SansSerif", bold ? Font.BOLD : Font.PLAIN, 10));
  }

  private void styleSlider(JSlider slider) {
    slider.setBackground(new Color(0x18, 0x18, 0x1c));
    slider.setForeground(new Color(0x00, 0xff, 0xcc));
    slider.setPreferredSize(new Dimension(180, 18));
    slider.setMinimumSize(new Dimension(180, 18));
    slider.setMaximumSize(new Dimension(180, 18));
    slider.setPaintTicks(false);
    slider.setPaintLabels(false);
  }

  private void styleCombo(JComboBox<?> combo) {
    combo.setFont(new Font("SansSerif", Font.PLAIN, 10));
    combo.setBackground(new Color(0x2d, 0x2d, 0x32));
    combo.setForeground(Color.WHITE);
    combo.setMaximumSize(new Dimension(180, 22));
    combo.setPreferredSize(new Dimension(180, 22));
    combo.setFocusable(false);
  }
}
