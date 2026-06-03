package org.chuck.deluge.ui;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.project.PreferencesManager;
import org.chuck.deluge.xml.DelugeXmlParser;

/**
 * A dedicated, sleek, and lightweight filesystem tree explorer for mounting SD card resources, Kit
 * and Synth presets, and multi-track songs. Removed all other misplaced tabs, turning this into a
 * clean and pure DAW side-explorer panel.
 */
public class SwingProjectSidebarPanel extends JPanel {

  private final ChuckVM vm;
  private final BridgeContract bridge;

  private java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> onSongLoaded;
  private java.util.function.Consumer<org.chuck.deluge.model.TrackModel> onTrackAdded;
  private java.util.function.Consumer<java.io.File> onPatternLoad;
  private Runnable onPatternSave;

  private DefaultMutableTreeNode libraryRoot;
  private JTree libraryTree;

  public SwingProjectSidebarPanel(
      ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.bridge = bridge;

    setPreferredSize(new Dimension(300, 0));
    setBackground(new Color(0x12, 0x12, 0x14));
    setLayout(new BorderLayout());

    // Header segment Title
    JPanel header = new JPanel(new BorderLayout());
    header.setBackground(new Color(0x18, 0x18, 0x1c));
    header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
    JLabel titleLabel = new JLabel("📁 SD CARD EXPLORER", SwingConstants.CENTER);
    titleLabel.setForeground(new Color(0x00, 0xff, 0xcc));
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
    header.add(titleLabel, BorderLayout.CENTER);
    add(header, BorderLayout.NORTH);

    // Direct scrollable Library Tree JComponent
    JComponent libraryPane = createLibraryTab();
    add(libraryPane, BorderLayout.CENTER);
  }

  public void reloadLibrary() {
    if (libraryRoot == null || libraryTree == null) return;
    libraryRoot.removeAllChildren();

    addDirsToTree(libraryRoot, "KITS", PreferencesManager.getKitsDir());
    addDirsToTree(libraryRoot, "SYNTHS", PreferencesManager.getSynthsDir());
    addDirsToTree(libraryRoot, "SONGS", PreferencesManager.getSongsDir());
    addDirsToTree(libraryRoot, "MIDI_DEVICES", PreferencesManager.getMidiDevicesDir());
    addDirsToTree(libraryRoot, "PATTERNS", PreferencesManager.getPatternsDir());

    File examplesDir = new File(PreferencesManager.getLibraryDir(), "EXAMPLES");
    if (examplesDir.isDirectory()) {
      addDirsToTree(libraryRoot, "EXAMPLES", examplesDir);
    }

    ((javax.swing.tree.DefaultTreeModel) libraryTree.getModel()).reload();
  }

  private JComponent createLibraryTab() {
    libraryRoot = new DefaultMutableTreeNode("SD CARD");
    addDirsToTree(libraryRoot, "KITS", PreferencesManager.getKitsDir());
    addDirsToTree(libraryRoot, "SYNTHS", PreferencesManager.getSynthsDir());
    addDirsToTree(libraryRoot, "SONGS", PreferencesManager.getSongsDir());
    addDirsToTree(libraryRoot, "MIDI_DEVICES", PreferencesManager.getMidiDevicesDir());
    addDirsToTree(libraryRoot, "PATTERNS", PreferencesManager.getPatternsDir());

    File examplesDir = new File(PreferencesManager.getLibraryDir(), "EXAMPLES");
    if (examplesDir.isDirectory()) {
      addDirsToTree(libraryRoot, "EXAMPLES", examplesDir);
    }

    libraryTree = new JTree(libraryRoot);
    libraryTree.setBackground(new Color(0x12, 0x12, 0x14));
    libraryTree.setFont(new Font("SansSerif", Font.PLAIN, 10));
    libraryTree.setRowHeight(20);

    DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
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
                  String category = path.getPathComponent(1).toString();
                  File baseDir;
                  switch (category) {
                    case "KITS":
                      baseDir = PreferencesManager.getKitsDir();
                      break;
                    case "SYNTHS":
                      baseDir = PreferencesManager.getSynthsDir();
                      break;
                    case "SONGS":
                      baseDir = PreferencesManager.getSongsDir();
                      break;
                    case "MIDI_DEVICES":
                      baseDir = PreferencesManager.getMidiDevicesDir();
                      break;
                    case "PATTERNS":
                      baseDir = PreferencesManager.getPatternsDir();
                      break;
                    case "EXAMPLES":
                      baseDir = new File(PreferencesManager.getLibraryDir(), "EXAMPLES");
                      break;
                    default:
                      baseDir = null;
                  }
                  if (baseDir == null) return;

                  StringBuilder relBuilder = new StringBuilder();
                  for (int i = 2; i < path.getPathCount(); i++) {
                    if (i > 2) relBuilder.append(File.separator);
                    relBuilder.append(path.getPathComponent(i).toString());
                  }
                  String relPath = relBuilder.toString();

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
                    if (onPatternLoad != null) {
                      onPatternLoad.accept(leafFile);
                    }
                    return;
                  }

                  System.out.println("Swing: Loading Preset: " + leafFile.getAbsolutePath());
                  try (java.io.InputStream is = new java.io.FileInputStream(leafFile)) {
                    if ("KITS".equals(category)) {
                      org.chuck.deluge.model.KitTrackModel kit = DelugeXmlParser.parseKit(is, name);
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
                      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    } else if ("SONGS".equals(category)) {
                      org.chuck.deluge.model.ProjectModel loadedProject =
                          DelugeXmlParser.parseSong(is, name);
                      int engineRow = 0;
                      java.io.File libraryDir = PreferencesManager.getLibraryDir();
                      java.util.ArrayList<String> missingFiles = new java.util.ArrayList<>();
                      for (org.chuck.deluge.model.TrackModel track : loadedProject.getTracks()) {
                        if (engineRow >= BridgeContract.TRACKS) break;
                        if (track instanceof org.chuck.deluge.model.KitTrackModel kit) {
                          java.util.List<org.chuck.deluge.model.Drum> sounds = kit.getDrums();
                          for (int i = 0; i < sounds.size(); i++) {
                            String sp =
                                ((org.chuck.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
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
                        JOptionPane.showMessageDialog(
                            SwingProjectSidebarPanel.this,
                            sb.toString(),
                            "Samples Not Found",
                            JOptionPane.ERROR_MESSAGE);
                      }
                      if (onSongLoaded != null) {
                        onSongLoaded.accept(loadedProject);
                      }
                      vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    } else if ("PATTERNS".equals(category)) {
                      if (onPatternLoad != null) {
                        onPatternLoad.accept(leafFile);
                      }
                    }
                  } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(
                        SwingProjectSidebarPanel.this,
                        "Failed to load target preset:\n" + ex.getMessage(),
                        "Load Error",
                        JOptionPane.ERROR_MESSAGE);
                  }
                }
              }
            }
          }
        });

    JScrollPane scrollPane = new JScrollPane(libraryTree);
    scrollPane.setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    return scrollPane;
  }

  private void addDirsToTree(DefaultMutableTreeNode root, String name, File dir) {
    if (dir == null || !dir.isDirectory()) return;
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(name);
    root.add(node);
    File[] kids = dir.listFiles();
    if (kids != null) {
      for (File k : kids) {
        if (k.isDirectory()) {
          addDirsToTree(node, k.getName(), k);
        } else {
          String fn = k.getName();
          if (fn.toLowerCase().endsWith(".xml") || fn.toLowerCase().endsWith(".ck")) {
            String label = fn.substring(0, fn.length() - 4);
            node.add(new DefaultMutableTreeNode(label));
          }
        }
      }
    }
  }

  public void setOnSongLoaded(java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> l) {
    this.onSongLoaded = l;
  }

  public java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> getOnSongLoaded() {
    return onSongLoaded;
  }

  public void setOnTrackAdded(java.util.function.Consumer<org.chuck.deluge.model.TrackModel> l) {
    this.onTrackAdded = l;
  }

  public void setOnPatternLoad(java.util.function.Consumer<java.io.File> l) {
    this.onPatternLoad = l;
  }

  public void setOnPatternSave(Runnable r) {
    this.onPatternSave = r;
  }

  public void updateFocusTrack(int trackId) {
    // Shield
  }
}
