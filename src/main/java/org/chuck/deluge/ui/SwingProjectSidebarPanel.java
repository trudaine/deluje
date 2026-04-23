package org.chuck.deluge.ui;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

public class SwingProjectSidebarPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> onSongLoaded;

  private final org.chuck.deluge.midi.MidiService midiService;

  public SwingProjectSidebarPanel(ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiService = midiService;



    setPreferredSize(new Dimension(400, 0));
    setBackground(new Color(0x25, 0x25, 0x25));
    setLayout(new BorderLayout());

    JTabbedPane tabs = new JTabbedPane();
    tabs.setBackground(new Color(0x33, 0x33, 0x33));
    tabs.setForeground(Color.WHITE);

    tabs.addTab("LIBRARY", createLibraryTab());
    tabs.addTab("EDITOR", createEditorTab());
    tabs.addTab("MIDI", createMidiTab());

    add(tabs, BorderLayout.CENTER);
  }

  private JComponent createLibraryTab() {
    javax.swing.tree.DefaultMutableTreeNode root =
        new javax.swing.tree.DefaultMutableTreeNode("SD CARD");

    addResourcesToTree(root, "KITS", "/KITS");
    addResourcesToTree(root, "SYNTHS", "/SYNTHS");
    addResourcesToTree(root, "SONGS", "/SONGS");

    JTree tree = new JTree(root);
    tree.setBackground(new Color(0x25, 0x25, 0x25));
    tree.setForeground(Color.WHITE);

    tree.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              javax.swing.tree.TreePath path = tree.getPathForLocation(e.getX(), e.getY());
              if (path != null) {
                javax.swing.tree.DefaultMutableTreeNode node =
                    (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.isLeaf()) {
                  String name = node.getUserObject().toString();
                  String internalDir = path.getParentPath().getLastPathComponent().toString();
                  String resourcePath = "/" + internalDir + "/" + name + ".xml";

                  System.out.println("Swing: Loading Preset: " + resourcePath);
                  try (java.io.InputStream is = getClass().getResourceAsStream(resourcePath)) {
                    if (is != null) {
                      if ("KITS".equals(internalDir)) {
                        org.chuck.deluge.model.KitTrackModel kit =
                            org.chuck.deluge.xml.DelugeXmlParser.parseKit(is, name);
                        int baseTrack = 0;
                        java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds =
                            kit.getSounds();
                        for (int i = 0; i < 8; i++) {
                          if (i < sounds.size()) {
                            vm.setGlobalString(
                                "g_sample_" + (baseTrack + i), sounds.get(i).getSamplePath());
                          }
                        }
                        vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                      } else if ("SONGS".equals(internalDir)) {
                        org.chuck.deluge.model.ProjectModel loadedProject =
                            org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, name);
                        int kitIdx = 0;
                        for (org.chuck.deluge.model.TrackModel track : loadedProject.getTracks()) {
                          if (track instanceof org.chuck.deluge.model.KitTrackModel kit) {
                            int baseTrack = kitIdx * 8;
                            java.util.List<org.chuck.deluge.model.KitTrackModel.KitSound> sounds =
                                kit.getSounds();
                            for (int i = 0; i < 8; i++) {
                              int trackId = baseTrack + i;
                              if (i < sounds.size()) {
                                vm.setGlobalString(
                                    "g_sample_" + trackId, sounds.get(i).getSamplePath());
                                bridge.setMute(trackId, false);
                                bridge.setTrackType(trackId, 0);
                              }
                            }
                            kitIdx++;
                          }
                        }
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(loadedProject);
                        }
                        vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                      } else if ("SYNTHS".equals(internalDir)) {
                        org.chuck.deluge.model.SynthTrackModel synth =
                            org.chuck.deluge.xml.DelugeXmlParser.parseSynth(is, name);
                        bridge.setTrackType(0, 1); // Set track 0 to Synth
                        vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
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

    return new JScrollPane(tree);
  }

  public void setOnSongLoaded(java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> callback) {
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
          try (java.util.stream.Stream<java.nio.file.Path> walk =
              java.nio.file.Files.walk(path, 1)) {
            walk.filter(p -> p.getFileName().toString().toUpperCase().endsWith(".XML"))
                .sorted(
                    java.util.Comparator.comparing(p -> p.getFileName().toString().toUpperCase()))
                .forEach(
                    p -> {
                      String name = p.getFileName().toString();
                      String displayName = name.substring(0, name.length() - 4);
                      folder.add(new javax.swing.tree.DefaultMutableTreeNode(displayName));
                    });
          }
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to scan resources for " + label + ": " + e.getMessage());
    }
  }

  private JComponent createEditorTab() {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBackground(new Color(0x25, 0x25, 0x25));
    panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

    // Oscillators Section
    JPanel oscBox = createSection("OSCILLATORS");
    oscBox.add(new JLabel("Osc 1 Vol:"));
    JSlider volSlider = new JSlider(0, 127, 64);
    oscBox.add(volSlider);
    panel.add(oscBox);

    // Filters Section
    JPanel filterBox = createSection("FILTERS");
    filterBox.add(new JLabel("LPF Cutoff:"));
    JSlider lpfSlider = new JSlider(0, 127, 64);
    lpfSlider.addChangeListener(
        e -> {
          if (bridge != null) {
            bridge.setFilterFreq(0, lpfSlider.getValue() / 127.0);
          }
        });
    filterBox.add(lpfSlider);
    panel.add(filterBox);

    // Envelopes
    JPanel envBox = createSection("ENVELOPES");
    envBox.add(new JLabel("Attack:"));
    JSlider attSlider = new JSlider(0, 100, 10);
    attSlider.addChangeListener(
        e -> {
          if (bridge != null) {
            bridge.setEnv(0, attSlider.getValue() / 100.0, 0.2, 0.8, 0.3);
          }
        });
    envBox.add(attSlider);
    panel.add(envBox);

    return new JScrollPane(panel);
  }

  private JComponent createMidiTab() {
    JPanel panel = new JPanel(new GridLayout(0, 2, 10, 10));
    panel.setBackground(new Color(0x25, 0x25, 0x25));
    panel.add(new JLabel("Param"));
    panel.add(new JLabel("Midi CC"));

    panel.add(new JLabel("LPF Cutoff:"));
    panel.add(new JButton("LEARN"));

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
