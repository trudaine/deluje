package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

public class SwingProjectSidebarPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> onSongLoaded;

  private JSlider volSlider;
  private JSlider lpfSlider;
  private JSlider attSlider;

  private final org.chuck.deluge.midi.MidiService midiService;

  private JTextArea scriptArea;
  private String activeScriptPath;
  private JTabbedPane tabs;
  private JPanel ckParamsBox;

  public SwingProjectSidebarPanel(
      ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiService = midiService;

    setPreferredSize(new Dimension(400, 0));
    setBackground(new Color(0x25, 0x25, 0x25));
    setLayout(new BorderLayout());

    tabs = new JTabbedPane();

    tabs.setBackground(new Color(0x33, 0x33, 0x33));
    tabs.setForeground(Color.WHITE);

    tabs.addTab("LIBRARY", createLibraryTab());
    tabs.addTab("EDITOR", createEditorTab());
    tabs.addTab("MIDI", createMidiTab());
    tabs.addTab("SCRIPT", createScriptTab());
    tabs.addTab("PROFILER", createProfilerTab());
    tabs.addTab("SNIPPETS", createSnippetsTab());

    add(tabs, BorderLayout.CENTER);
  }

  private JComponent createLibraryTab() {
    javax.swing.tree.DefaultMutableTreeNode root =
        new javax.swing.tree.DefaultMutableTreeNode("SD CARD");

    addResourcesToTree(root, "KITS", "/KITS");
    addResourcesToTree(root, "SYNTHS", "/SYNTHS");
    addResourcesToTree(root, "SONGS", "/SONGS");
    addResourcesToTree(root, "EXAMPLES", "/examples");

    JTree tree = new JTree(root);
    tree.setBackground(new Color(0x1f, 0x1f, 0x1f));
    tree.setRowHeight(30); // More vertical spacing

    javax.swing.tree.DefaultTreeCellRenderer renderer =
        new javax.swing.tree.DefaultTreeCellRenderer();
    renderer.setBackgroundNonSelectionColor(new Color(0x1f, 0x1f, 0x1f));
    renderer.setTextNonSelectionColor(Color.LIGHT_GRAY);
    renderer.setTextSelectionColor(Color.WHITE);
    renderer.setBackgroundSelectionColor(new Color(0x00, 0xff, 0xcc, 0x55));
    tree.setCellRenderer(renderer);

    tree.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              javax.swing.tree.TreePath path = tree.getSelectionPath();
              if (path != null) {

                javax.swing.tree.DefaultMutableTreeNode node =
                    (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.isLeaf()) {
                  String name = node.getUserObject().toString();
                  String internalDir = path.getParentPath().getLastPathComponent().toString();
                  StringBuilder pathBuilder = new StringBuilder();
                  for (int i = 1; i < path.getPathCount(); i++) {

                    pathBuilder.append("/").append(path.getPathComponent(i).toString());
                  }
                  String resourcePath = pathBuilder.toString();
                  if (resourcePath.startsWith("/EXAMPLES/")) {
                    resourcePath = "/examples" + resourcePath.substring(9);
                  }
                  if (!resourcePath.toLowerCase().endsWith(".xml")
                      && !resourcePath.toLowerCase().endsWith(".ck")) {
                    resourcePath += ".XML";
                  }

                  if (resourcePath.toLowerCase().endsWith(".ck")) {

                    System.out.println("Swing: Loading ChucK script text: " + resourcePath);
                    activeScriptPath = resourcePath;
                    try (java.io.InputStream is = getClass().getResourceAsStream(resourcePath)) {
                      if (is != null) {
                        String content =
                            new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        if (scriptArea != null) {
                          scriptArea.setText(content);
                          tabs.setSelectedIndex(1); // Focus EDITOR tab to see sliders!

                          if (ckParamsBox != null) {
                            ckParamsBox.removeAll();
                            java.util.regex.Pattern p =
                                java.util.regex.Pattern.compile(
                                    "global\\s+float\\s+([a-zA-Z0-9_]+)");
                            java.util.regex.Matcher m = p.matcher(content);
                            while (m.find()) {
                              String varName = m.group(1);
                              JLabel varLabel = new JLabel(varName + ": 50");
                              varLabel.setForeground(Color.WHITE);
                              JSlider slider = new JSlider(0, 100, 50);
                              slider.setBackground(new Color(0x1f, 0x1f, 0x1f));
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
                      }
                    } catch (Exception ex) {
                      ex.printStackTrace();
                    }
                    return;
                  }

                  System.out.println("Swing: Loading Preset: " + resourcePath);
                  try (java.io.InputStream is =
                      getClass().getResourceAsStream(resourcePath) != null
                          ? getClass().getResourceAsStream(resourcePath)
                          : getClass().getResourceAsStream(resourcePath.replace(".XML", ".xml"))) {
                    if (is != null) {
                      if ("KITS".equals(internalDir)) {
                        org.chuck.deluge.model.KitTrackModel kit =
                            org.chuck.deluge.xml.DelugeXmlParser.parseKit(is, name);

                        int baseTrack = 0;
                        java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds =
                            kit.getSounds();
                        for (int i = 0; i < sounds.size(); i++) {
                          {
                            String sp = sounds.get(i).getSamplePath();
                            // Set raw resource path — engine resolves casing and loads to temp
                            if (sp != null && !sp.startsWith("/")) sp = "/" + sp;
                            vm.setGlobalString("g_sample_" + (baseTrack + i), sp != null ? sp : "");
                            bridge.setMute(baseTrack + i, false);
                          }
                        }

                        org.chuck.deluge.model.ProjectModel mockProj =
                            new org.chuck.deluge.model.ProjectModel();
                        mockProj.addTrack(kit);
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(mockProj);
                        }

                        vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);

                      } else if ("SONGS".equals(internalDir)) {
                        org.chuck.deluge.model.ProjectModel loadedProject =
                            org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, name);

                        // Each track gets a sequential engine row; set raw resource paths
                        int engineRow = 0;
                        for (org.chuck.deluge.model.TrackModel track : loadedProject.getTracks()) {
                          if (engineRow >= org.chuck.deluge.BridgeContract.TRACKS) break;
                          if (track instanceof org.chuck.deluge.model.KitTrackModel kit) {
                            java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds =
                                kit.getSounds();
                            for (int i = 0; i < sounds.size(); i++) {
                              String sp = sounds.get(i).getSamplePath();
                              if (sp != null && !sp.startsWith("/")) sp = "/" + sp;
                              vm.setGlobalString(
                                  "g_sample_" + (engineRow + i), sp != null ? sp : "");
                              bridge.setMute(engineRow + i, false);
                              bridge.setTrackType(engineRow + i, 0);
                            }
                          }
                          engineRow++;
                        }
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(loadedProject);
                        }
                        vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                      } else if ("SYNTHS".equals(internalDir)) {
                        org.chuck.deluge.model.SynthTrackModel synth =
                            org.chuck.deluge.xml.DelugeXmlParser.parseSynth(is, name);

                        org.chuck.deluge.model.ProjectModel mockProj =
                            new org.chuck.deluge.model.ProjectModel();
                        mockProj.addTrack(synth);
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(mockProj);
                        }

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

    JButton shuffleBtn = new JButton("🎲 SHUFFLE DRUM KIT");
    shuffleBtn.setBackground(new Color(0x33, 0x33, 0x33));
    shuffleBtn.setForeground(Color.WHITE);
    shuffleBtn.setFont(new Font("SansSerif", Font.BOLD, 14));

    shuffleBtn.addActionListener(
        e -> {
          // Dynamic sample discovery from the object model should be implemented here.
          System.out.println("Shuffle requested. Implement dynamic discovery of samples.");
        });

    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.add(shuffleBtn, BorderLayout.NORTH);
    wrapper.add(new JScrollPane(tree), BorderLayout.CENTER);

    return wrapper;
  }

  public java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> getOnSongLoaded() {
    return onSongLoaded;
  }

  public void setOnSongLoaded(
      java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> callback) {
    this.onSongLoaded = callback;
  }

  private void addResourcesToTree(
      javax.swing.tree.DefaultMutableTreeNode root, String label, String internalDir) {
    javax.swing.tree.DefaultMutableTreeNode folder =
        new javax.swing.tree.DefaultMutableTreeNode(label);
    root.add(folder);

    try {
      java.net.URL url = getClass().getResource(internalDir);
      if (url == null) {
        String classPath = getClass().getName().replace(".", "/") + ".class";
        url = getClass().getClassLoader().getResource(classPath);
      }

      if (url != null) {
        java.net.URI uri = url.toURI();
        java.nio.file.Path path;
        java.nio.file.FileSystem fs = null;

        if (uri.getScheme().equals("jar")) {
          try {
            fs = java.nio.file.FileSystems.getFileSystem(uri);
          } catch (Exception e) {
            fs = java.nio.file.FileSystems.newFileSystem(uri, java.util.Collections.emptyMap());
          }
          path = fs.getPath(internalDir);
        } else if (uri.getScheme().equals("file")) {
          path = java.nio.file.Paths.get(uri);
          if (!uri.toString().endsWith(internalDir)) {
            path =
                path.getParent()
                    .resolve(internalDir.startsWith("/") ? internalDir.substring(1) : internalDir);
          }
        } else {
          return;
        }

        if (java.nio.file.Files.exists(path)) {
          buildDirectoryTree(folder, path, path);
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to scan resources for " + label + ": " + e.getMessage());
    }
  }

  private void buildDirectoryTree(
      javax.swing.tree.DefaultMutableTreeNode node,
      java.nio.file.Path rootPath,
      java.nio.file.Path currentPath) {
    try (java.util.stream.Stream<java.nio.file.Path> stream =
        java.nio.file.Files.list(currentPath)) {
      stream
          .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString().toUpperCase()))
          .forEach(
              p -> {
                if (java.nio.file.Files.isDirectory(p)) {
                  javax.swing.tree.DefaultMutableTreeNode dirNode =
                      new javax.swing.tree.DefaultMutableTreeNode(p.getFileName().toString());
                  node.add(dirNode);
                  buildDirectoryTree(dirNode, rootPath, p);
                } else {
                  String fn = p.getFileName().toString();
                  String fnUpper = fn.toUpperCase();
                  if (fnUpper.endsWith(".XML") || fnUpper.endsWith(".CK")) {
                    int dotIdx = fn.lastIndexOf('.');
                    String displayName = dotIdx != -1 ? fn.substring(0, dotIdx) : fn;
                    node.add(new javax.swing.tree.DefaultMutableTreeNode(displayName));
                  }
                }
              });
    } catch (Exception ex) {
      // ignore
    }
  }

  private JComponent createEditorTab() {

    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBackground(new Color(0x25, 0x25, 0x25));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    ckParamsBox = createSection("CHUCK PARAMS");
    panel.add(ckParamsBox);
    panel.add(Box.createVerticalStrut(15));

    // Oscillators Section
    JPanel oscBox = createSection("OSCILLATORS");

    JLabel osc1TypeLabel = new JLabel("Osc 1 Type:");
    osc1TypeLabel.setForeground(Color.WHITE);
    JComboBox<String> osc1TypeCombo =
        new JComboBox<>(new String[] {"Sine", "Saw", "Square", "Triangle", "Noise"});
    osc1TypeCombo.setBackground(new Color(0x1f, 0x1f, 0x1f));
    osc1TypeCombo.setMaximumSize(new Dimension(180, 28));
    oscBox.add(osc1TypeLabel);
    oscBox.add(osc1TypeCombo);

    oscBox.add(Box.createVerticalStrut(5));

    JLabel osc1Label = new JLabel("Osc 1 Vol: 64");
    osc1Label.setForeground(Color.WHITE);
    volSlider = new JSlider(0, 127, 64);

    volSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    volSlider.addChangeListener(e -> osc1Label.setText("Osc 1 Vol: " + volSlider.getValue()));
    oscBox.add(osc1Label);
    oscBox.add(volSlider);
    oscBox.add(Box.createVerticalStrut(10));

    JLabel osc2TypeLabel = new JLabel("Osc 2 Type:");
    osc2TypeLabel.setForeground(Color.WHITE);
    JComboBox<String> osc2TypeCombo =
        new JComboBox<>(new String[] {"Sine", "Saw", "Square", "Triangle", "Noise"});
    osc2TypeCombo.setBackground(new Color(0x1f, 0x1f, 0x1f));
    osc2TypeCombo.setMaximumSize(new Dimension(180, 28));
    oscBox.add(osc2TypeLabel);
    oscBox.add(osc2TypeCombo);

    oscBox.add(Box.createVerticalStrut(5));

    JLabel osc2Label = new JLabel("Osc 2 Vol: 0");
    osc2Label.setForeground(Color.WHITE);
    JSlider vol2Slider = new JSlider(0, 127, 0);
    vol2Slider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    vol2Slider.addChangeListener(e -> osc2Label.setText("Osc 2 Vol: " + vol2Slider.getValue()));
    oscBox.add(osc2Label);
    oscBox.add(vol2Slider);

    JLabel pwLabel = new JLabel("Pulse Width: 50");
    pwLabel.setForeground(Color.WHITE);
    JSlider pwSlider = new JSlider(0, 100, 50);
    pwSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    pwSlider.addChangeListener(e -> pwLabel.setText("Pulse Width: " + pwSlider.getValue()));
    oscBox.add(pwLabel);
    oscBox.add(pwSlider);
    oscBox.add(Box.createVerticalStrut(10));

    panel.add(oscBox);
    panel.add(Box.createVerticalStrut(15));

    // Modulators
    JPanel modBox = createSection("MODULATORS");
    JLabel mod1Label = new JLabel("Mod 1 Amount: 0");
    mod1Label.setForeground(Color.WHITE);
    JSlider mod1Slider = new JSlider(0, 127, 0);
    mod1Slider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    mod1Slider.addChangeListener(e -> mod1Label.setText("Mod 1 Amount: " + mod1Slider.getValue()));
    modBox.add(mod1Label);
    modBox.add(mod1Slider);
    panel.add(modBox);
    panel.add(Box.createVerticalStrut(15));

    // Filters Section
    JPanel filterBox = createSection("FILTERS");
    JLabel lpfLabel = new JLabel("LPF Cutoff: 64");
    lpfLabel.setForeground(Color.WHITE);
    lpfSlider = new JSlider(0, 127, 64);

    lpfSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    lpfSlider.addChangeListener(
        e -> {
          lpfLabel.setText("LPF Cutoff: " + lpfSlider.getValue());
          if (bridge != null) {
            bridge.setFilterFreq(0, lpfSlider.getValue() / 127.0);
          }
        });
    filterBox.add(lpfLabel);
    filterBox.add(lpfSlider);
    panel.add(filterBox);
    panel.add(Box.createVerticalStrut(15));

    // Envelopes
    JPanel envBox = createSection("ENVELOPES");

    JLabel attLabel = new JLabel("Attack: 10");
    attLabel.setForeground(Color.WHITE);
    attSlider = new JSlider(0, 100, 10);

    attSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    attSlider.addChangeListener(
        e -> {
          attLabel.setText("Attack: " + attSlider.getValue());
          if (bridge != null) {
            bridge.setEnv(0, attSlider.getValue() / 100.0, 0.2, 0.8, 0.3);
          }
        });
    envBox.add(attLabel);
    envBox.add(attSlider);
    envBox.add(Box.createVerticalStrut(5));

    JLabel decLabel = new JLabel("Decay: 20");
    decLabel.setForeground(Color.WHITE);
    JSlider decSlider = new JSlider(0, 100, 20);
    decSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    decSlider.addChangeListener(e -> decLabel.setText("Decay: " + decSlider.getValue()));
    envBox.add(decLabel);
    envBox.add(decSlider);
    envBox.add(Box.createVerticalStrut(5));

    JLabel susLabel = new JLabel("Sustain: 80");
    susLabel.setForeground(Color.WHITE);
    JSlider susSlider = new JSlider(0, 100, 80);
    susSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    susSlider.addChangeListener(e -> susLabel.setText("Sustain: " + susSlider.getValue()));
    envBox.add(susLabel);
    envBox.add(susSlider);
    envBox.add(Box.createVerticalStrut(5));

    JLabel relLabel = new JLabel("Release: 30");
    relLabel.setForeground(Color.WHITE);
    JSlider relSlider = new JSlider(0, 100, 30);
    relSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    relSlider.addChangeListener(e -> relLabel.setText("Release: " + relSlider.getValue()));
    envBox.add(relLabel);
    envBox.add(relSlider);

    panel.add(envBox);

    // Distortions
    JPanel distBox = createSection("DISTORTIONS");
    JLabel bitLabel = new JLabel("Bitcrush Bits: 16");
    bitLabel.setForeground(Color.WHITE);
    JSlider bitSlider = new JSlider(1, 16, 16);
    bitSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    bitSlider.addChangeListener(e -> bitLabel.setText("Bitcrush Bits: " + bitSlider.getValue()));
    distBox.add(bitLabel);
    distBox.add(bitSlider);
    panel.add(distBox);
    panel.add(Box.createVerticalStrut(15));

    // Master FX section
    JPanel fxBox = createSection("MASTER FX");
    fxBox.add(new JLabel("Decimations:"));
    fxBox.add(new JSlider(0, 100, 0));
    panel.add(fxBox);

    return new JScrollPane(panel);
  }

  private JComponent createMidiTab() {
    JPanel panel = new JPanel(new GridBagLayout());
    panel.setBackground(new Color(0x25, 0x25, 0x25));
    panel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));

    GridBagConstraints c = new GridBagConstraints();
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(8, 5, 8, 5);

    String[] displayNames = {
      "Master Volume",
      "Master Pan",
      "Delay Time",
      "Delay Feedback",
      "Reverb Room Size",
      "Reverb Damping"
    };

    for (int i = 0; i < displayNames.length; i++) {
      c.gridx = 0;
      c.gridy = i;
      JLabel label = new JLabel(displayNames[i] + ":");
      label.setForeground(Color.WHITE);
      panel.add(label, c);

      c.gridx = 1;
      JButton learnBtn = new JButton("LEARN");
      learnBtn.addActionListener(
          e -> {
            learnBtn.setText("WAITING...");
          });
      panel.add(learnBtn, c);
    }

    String[][] data = {
      {"Master Volume", "CC #7", "ACTIVE"},
      {"Master Pan", "None", "LEARN"},
      {"Delay Time", "CC #14", "ACTIVE"},
      {"Delay Feedback", "None", "LEARN"},
      {"Reverb Room", "CC #21", "ACTIVE"},
      {"Reverb Damp", "None", "LEARN"}
    };
    String[] cols = {"Parameter", "Routing", "State"};
    JTable table = new JTable(data, cols);
    table.setBackground(new Color(0x1f, 0x1f, 0x1f));
    table.setForeground(Color.WHITE);
    table.setFont(new Font("SansSerif", Font.PLAIN, 16));
    table.setRowHeight(30);

    JPanel wrapper = new JPanel(new BorderLayout(0, 20));
    wrapper.setBackground(new Color(0x25, 0x25, 0x25));
    wrapper.add(new JScrollPane(panel), BorderLayout.NORTH);
    wrapper.add(new JScrollPane(table), BorderLayout.CENTER);

    return wrapper;
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
    panel.setBackground(new Color(0x25, 0x25, 0x25));

    scriptArea = new JTextArea();
    scriptArea.setBackground(new Color(0x1f, 0x1f, 0x1f));
    scriptArea.setForeground(Color.GREEN);
    scriptArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
    scriptArea.setCaretColor(Color.WHITE);

    JTextArea logArea = new JTextArea("Console logs: Ready");
    logArea.setBackground(Color.BLACK);
    logArea.setForeground(Color.CYAN);
    logArea.setEditable(false);

    JButton reloadBtn = new JButton("💾 SAVE & RELOAD");
    reloadBtn.setBackground(new Color(0x33, 0x66, 0x33));
    reloadBtn.setForeground(Color.WHITE);

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

    panel.add(reloadBtn, BorderLayout.NORTH);

    JPanel centerSplit = new JPanel(new GridLayout(2, 1, 5, 5));
    centerSplit.add(new JScrollPane(scriptArea));
    centerSplit.add(new JScrollPane(logArea));
    panel.add(centerSplit, BorderLayout.CENTER);

    return panel;
  }

  private JComponent createProfilerTab() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBackground(new Color(0x25, 0x25, 0x25));

    DefaultListModel<String> model = new DefaultListModel<>();
    model.addElement("Shred 1: engine.ck (Master Clock)");
    model.addElement("Shred 2: custom_fm.ck (Active)");
    JList<String> list = new JList<>(model);
    list.setBackground(new Color(0x1f, 0x1f, 0x1f));
    list.setForeground(Color.CYAN);

    JButton killBtn = new JButton("KILL SELECTED SHRED");
    killBtn.setBackground(new Color(0xaa, 0x33, 0x33));
    killBtn.setForeground(Color.WHITE);
    killBtn.addActionListener(
        e -> {
          int idx = list.getSelectedIndex();
          if (idx != -1) {
            model.remove(idx);
          }
        });

    panel.add(killBtn, BorderLayout.SOUTH);
    panel.add(new JScrollPane(list), BorderLayout.CENTER);
    return panel;
  }

  private JComponent createSnippetsTab() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBackground(new Color(0x25, 0x25, 0x25));

    String[] snippets = {"SawOsc Synth", "Pulse Lead", "Noise Percussion"};
    JList<String> list = new JList<>(snippets);
    list.setBackground(new Color(0x1f, 0x1f, 0x1f));
    list.setForeground(Color.WHITE);

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

    panel.add(new JLabel("Double-click snippet to insert:"), BorderLayout.NORTH);
    panel.add(new JScrollPane(list), BorderLayout.CENTER);
    return panel;
  }

  private JPanel createSection(String title) {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBackground(new Color(0x33, 0x33, 0x33));
    panel.setBorder(
        BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(Color.GRAY), title, 0, 0, null, Color.WHITE));
    return panel;
  }
}
