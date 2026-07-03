package org.deluge.ui;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.deluge.BridgeContract;
import org.deluge.project.PreferencesManager;
import org.deluge.xml.DelugeXmlParser;

/**
 * A dedicated, sleek, and lightweight filesystem tree explorer for mounting SD card resources, Kit
 * and Synth presets, and multi-track songs. Removed all other misplaced tabs, turning this into a
 * clean and pure DAW side-explorer panel.
 */
public class SwingProjectSidebarPanel extends JPanel {

  private final BridgeContract bridge;

  private java.util.function.BiConsumer<org.deluge.model.ProjectModel, java.io.File> onSongLoaded;
  private java.util.function.Consumer<org.deluge.model.TrackModel> onTrackAdded;
  private java.util.function.Consumer<java.io.File> onPatternLoad;
  private Runnable onPatternSave;

  /**
   * Invoked after the SD-card root has been changed from this panel's header button, so the app can
   * refresh every view that mirrors the library (e.g. both sidebar instances). When unset, the
   * panel just reloads its own tree.
   */
  private Runnable onLibraryDirChanged;

  // Copy/Paste clipboard state
  private java.io.File localClipboardFile = null;
  private String remoteClipboardPath = null;
  private boolean isRemoteSource = false;

  private DefaultMutableTreeNode libraryRoot;
  private JTree libraryTree;
  private JButton changeDirButton;

  // Remote Hardware SD Card Tree components
  private DefaultMutableTreeNode hardwareRoot;
  private DefaultMutableTreeNode songsNode;
  private DefaultMutableTreeNode synthsNode;
  private DefaultMutableTreeNode kitsNode;
  private JTree hardwareTree;
  private JTabbedPane tabbedPane;
  private boolean hardwareRefreshedOnce = false;

  // Remote Hardware SD Card Table components
  private JTable remoteTable;
  private javax.swing.table.DefaultTableModel remoteTableModel;
  private JLabel currentFolderLabel;
  private String currentRemotePath = "/SONGS";
  private java.util.List<org.deluge.midi.RemoteFileEntry> currentRemoteEntries =
      new java.util.ArrayList<>();
  private JProgressBar transferProgressBar;

  public SwingProjectSidebarPanel(
      final BridgeContract bridge, org.deluge.midi.MidiService midiService) {
    this.bridge = bridge;

    setPreferredSize(new Dimension(300, 0));
    setBackground(new Color(0x12, 0x12, 0x14));
    setLayout(new BorderLayout());

    // Header segment Title + fast access to the SD-card root directory changer (same setting as
    // Preferences → "SD Card Root Directory" / File → "Set SD Card Root...").
    JPanel header = new JPanel(new BorderLayout());
    header.setBackground(new Color(0x18, 0x18, 0x1c));
    header.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
    JLabel titleLabel = new JLabel("📁 SD CARD EXPLORER", SwingConstants.CENTER);
    titleLabel.setForeground(new Color(0x00, 0xff, 0xcc));
    titleLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
    header.add(titleLabel, BorderLayout.CENTER);

    changeDirButton = new JButton("📂");
    changeDirButton.setFont(new Font("SansSerif", Font.PLAIN, 11));
    changeDirButton.setMargin(new Insets(0, 4, 0, 4));
    changeDirButton.setBackground(new Color(0x2a, 0x2a, 0x30));
    changeDirButton.setForeground(Color.LIGHT_GRAY);
    changeDirButton.setFocusPainted(false);
    changeDirButton.setBorder(
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(0x3a, 0x3a, 0x42), 1),
            BorderFactory.createEmptyBorder(2, 6, 2, 6)));
    updateChangeDirTooltip();
    changeDirButton.addActionListener(e -> chooseLibraryDir());
    header.add(changeDirButton, BorderLayout.EAST);
    add(header, BorderLayout.NORTH);

    // Tabbed panel for Local vs Physical Hardware SD Card explorer
    tabbedPane = new JTabbedPane();
    tabbedPane.setBackground(new Color(0x18, 0x18, 0x1c));
    tabbedPane.setForeground(Color.LIGHT_GRAY);
    tabbedPane.setFont(new Font("SansSerif", Font.BOLD, 10));

    JComponent libraryPane = createLibraryTab();
    tabbedPane.addTab("📁 LOCAL", libraryPane);

    JComponent hardwarePane = createHardwareTab();
    tabbedPane.addTab("📡 HARDWARE", hardwarePane);

    tabbedPane.addChangeListener(
        e -> {
          if (tabbedPane.getSelectedIndex() == 1 && !hardwareRefreshedOnce) {
            hardwareRefreshedOnce = true;
            refreshHardwareTree();
          }
        });

    add(tabbedPane, BorderLayout.CENTER);
  }

  /** Opens a directory chooser and applies the new SD-card root (PreferencesManager library). */
  private void chooseLibraryDir() {
    JFileChooser chooser = new JFileChooser(PreferencesManager.getLibraryDir());
    chooser.setDialogTitle("Set SD Card Root Directory");
    chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      PreferencesManager.setLibraryDir(chooser.getSelectedFile().getAbsolutePath());
      updateChangeDirTooltip();
      if (onLibraryDirChanged != null) {
        onLibraryDirChanged.run();
      } else {
        reloadLibrary();
      }
    }
  }

  private void updateChangeDirTooltip() {
    if (changeDirButton != null) {
      File dir = PreferencesManager.getLibraryDir();
      changeDirButton.setToolTipText(
          "Change SD card root directory (current: "
              + (dir != null ? dir.getAbsolutePath() : "(not set)")
              + ")");
    }
  }

  public void setOnLibraryDirChanged(Runnable r) {
    this.onLibraryDirChanged = r;
  }

  public void reloadLibrary() {
    updateChangeDirTooltip();
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
    libraryTree.setFont(new Font("SansSerif", Font.PLAIN, 16));
    libraryTree.setRowHeight(30);

    DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
    renderer.setFont(new Font("SansSerif", Font.PLAIN, 16));
    renderer.setBackgroundNonSelectionColor(new Color(0x12, 0x12, 0x14));
    renderer.setTextNonSelectionColor(Color.LIGHT_GRAY);
    renderer.setTextSelectionColor(Color.WHITE);
    renderer.setBackgroundSelectionColor(new Color(0x00, 0xff, 0xcc, 0x33));
    libraryTree.setCellRenderer(renderer);

    JPopupMenu localPopupMenu = new JPopupMenu();
    localPopupMenu.setBackground(new Color(0x1a, 0x1a, 0x20));
    localPopupMenu.setBorder(BorderFactory.createLineBorder(new Color(0x25, 0x25, 0x28)));

    JMenuItem localCopyItem = new JMenuItem("📋 Copy");
    localCopyItem.setForeground(Color.WHITE);
    localPopupMenu.add(localCopyItem);

    JMenuItem localPasteItem = new JMenuItem("📋 Paste");
    localPasteItem.setForeground(Color.WHITE);
    localPopupMenu.add(localPasteItem);

    localCopyItem.addActionListener(
        ev -> {
          javax.swing.tree.TreePath path = libraryTree.getSelectionPath();
          if (path != null) {
            File file = getLocalFileForPath(path);
            if (file != null && file.isFile()) {
              localClipboardFile = file;
              remoteClipboardPath = null;
              isRemoteSource = false;
            }
          }
        });

    localPasteItem.addActionListener(
        ev -> {
          javax.swing.tree.TreePath path = libraryTree.getSelectionPath();
          if (path != null) {
            File targetDir = getLocalFileForPath(path);
            if (targetDir != null
                && targetDir.isDirectory()
                && isRemoteSource
                && remoteClipboardPath != null) {
              int lastSlash = remoteClipboardPath.lastIndexOf('/');
              String name =
                  lastSlash != -1 ? remoteClipboardPath.substring(lastSlash + 1) : "downloaded.XML";
              File destFile = new File(targetDir, name);
              downloadRemoteFileToLocal(remoteClipboardPath, destFile);
            }
          }
        });

    libraryTree.addMouseListener(
        new java.awt.event.MouseAdapter() {
          private void handlePopup(java.awt.event.MouseEvent e) {
            if (e.isPopupTrigger()) {
              javax.swing.tree.TreePath path = libraryTree.getPathForLocation(e.getX(), e.getY());
              if (path != null) {
                libraryTree.setSelectionPath(path);
                File file = getLocalFileForPath(path);
                if (file != null) {
                  localCopyItem.setEnabled(file.isFile());
                  localPasteItem.setEnabled(
                      file.isDirectory() && isRemoteSource && remoteClipboardPath != null);
                  localPopupMenu.show(libraryTree, e.getX(), e.getY());
                }
              }
            }
          }

          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            handlePopup(e);
            if (e.getClickCount() == 2 && javax.swing.SwingUtilities.isLeftMouseButton(e)) {
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

                  final File finalLeafFile = leafFile;
                  final String finalName = name;
                  final String finalCategory = category;
                  Thread.startVirtualThread(
                      () -> {
                        try {
                          System.out.println(
                              "Swing: Loading Preset in background: "
                                  + finalLeafFile.getAbsolutePath());
                          try (java.io.InputStream is =
                              new java.io.FileInputStream(finalLeafFile)) {
                            if ("KITS".equals(finalCategory)) {
                              org.deluge.model.KitTrackModel kit =
                                  DelugeXmlParser.parseKit(is, finalName);
                              int baseTrack = 0;
                              java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
                              for (int i = 0; i < sounds.size(); i++) {
                                String sp =
                                    ((org.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
                                bridge.setGlobalString(
                                    "g_sample_" + (baseTrack + i), sp != null ? sp : "");
                                bridge.setSamplePath(baseTrack + i, sp != null ? sp : "");
                                bridge.setMute(baseTrack + i, false);
                              }
                              javax.swing.SwingUtilities.invokeLater(
                                  () -> {
                                    if (onTrackAdded != null) {
                                      onTrackAdded.accept(kit);
                                    } else {
                                      org.deluge.model.ProjectModel mockProj =
                                          new org.deluge.model.ProjectModel();
                                      mockProj.addTrack(kit);
                                      if (onSongLoaded != null) {
                                        onSongLoaded.accept(mockProj, finalLeafFile);
                                      }
                                    }
                                    bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                                  });
                            } else if ("SYNTHS".equals(finalCategory)) {
                              org.deluge.model.SynthTrackModel synth =
                                  org.deluge.xml.DelugeXmlParser.parseSynth(is, finalName);
                              javax.swing.SwingUtilities.invokeLater(
                                  () -> {
                                    if (onTrackAdded != null) {
                                      onTrackAdded.accept(synth);
                                    } else {
                                      org.deluge.model.ProjectModel mockProj =
                                          new org.deluge.model.ProjectModel();
                                      mockProj.addTrack(synth);
                                      if (onSongLoaded != null) {
                                        onSongLoaded.accept(mockProj, finalLeafFile);
                                      }
                                    }
                                    bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                                  });
                            } else if ("SONGS".equals(finalCategory)) {
                              org.deluge.model.ProjectModel loadedProject =
                                  DelugeXmlParser.parseSong(is, finalName);
                              int engineRow = 0;
                              java.io.File libraryDir = PreferencesManager.getLibraryDir();
                              java.util.ArrayList<String> missingFiles =
                                  new java.util.ArrayList<>();
                              for (org.deluge.model.TrackModel track : loadedProject.getTracks()) {
                                if (engineRow >= BridgeContract.TRACKS) break;
                                if (track instanceof org.deluge.model.KitTrackModel kit) {
                                  java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
                                  for (int i = 0; i < sounds.size(); i++) {
                                    String sp =
                                        ((org.deluge.model.SoundDrum) sounds.get(i))
                                            .getSamplePath();
                                    if (sp != null && !sp.isEmpty()) {
                                      // Use the shared, case-insensitive resolver (the Deluge saves
                                      // on case-insensitive FAT32, so "808 Clap.wav" may be
                                      // "808 Clap.WAV" on disk). A plain File.exists() is
                                      // case-sensitive on Linux and wrongly reported these missing.
                                      java.io.File sf =
                                          org.deluge.engine.FirmwareFactory.resolveSample(
                                              sp, libraryDir);
                                      if (sf == null || !sf.exists()) {
                                        missingFiles.add(sp);
                                      }
                                    }
                                    bridge.setGlobalString(
                                        "g_sample_" + (engineRow + i), sp != null ? sp : "");
                                    bridge.setMute(engineRow + i, false);
                                    bridge.setTrackType(engineRow + i, 0);
                                  }
                                }
                                engineRow++;
                              }
                              javax.swing.SwingUtilities.invokeLater(
                                  () -> {
                                    if (!missingFiles.isEmpty()) {
                                      StringBuilder sb =
                                          new StringBuilder("Missing sample files:\n");
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
                                      onSongLoaded.accept(loadedProject, finalLeafFile);
                                    }
                                    bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                                  });
                            } else if ("PATTERNS".equals(finalCategory)) {
                              javax.swing.SwingUtilities.invokeLater(
                                  () -> {
                                    if (onPatternLoad != null) {
                                      onPatternLoad.accept(finalLeafFile);
                                    }
                                  });
                            }
                          }
                        } catch (Exception ex) {
                          System.err.println(
                              "Error loading preset in background: " + ex.getMessage());
                          ex.printStackTrace();
                          javax.swing.SwingUtilities.invokeLater(
                              () -> {
                                JOptionPane.showMessageDialog(
                                    SwingProjectSidebarPanel.this,
                                    "Failed to load target preset:\n" + ex.getMessage(),
                                    "Load Error",
                                    JOptionPane.ERROR_MESSAGE);
                              });
                        }
                      });
                }
              }
            }
          }

          @Override
          public void mouseReleased(java.awt.event.MouseEvent e) {
            handlePopup(e);
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
      java.util.Arrays.sort(
          kids,
          (a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
          });
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

  public void setOnSongLoaded(
      java.util.function.BiConsumer<org.deluge.model.ProjectModel, java.io.File> l) {
    this.onSongLoaded = l;
  }

  public java.util.function.BiConsumer<org.deluge.model.ProjectModel, java.io.File>
      getOnSongLoaded() {
    return onSongLoaded;
  }

  public void setOnTrackAdded(java.util.function.Consumer<org.deluge.model.TrackModel> l) {
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

  // ── Remote Hardware SD Card Explorer ──

  private JComponent createHardwareTab() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBackground(new Color(0x12, 0x12, 0x14));

    // Header segment
    JPanel head = new JPanel(new BorderLayout());
    head.setBackground(new Color(0x15, 0x15, 0x18));
    head.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

    JLabel title = new JLabel("PHYSICAL SD CARD");
    title.setForeground(Color.LIGHT_GRAY);
    title.setFont(new Font("SansSerif", Font.BOLD, 9));
    head.add(title, BorderLayout.WEST);

    JButton refreshBtn = new JButton("🔄 REFRESH");
    refreshBtn.setFont(new Font("SansSerif", Font.BOLD, 9));
    refreshBtn.setBackground(new Color(0x2a, 0x2a, 0x30));
    refreshBtn.setForeground(new Color(0x00, 0xff, 0xcc));
    refreshBtn.setFocusPainted(false);
    refreshBtn.addActionListener(
        e -> {
          refreshHardwareTree();
          loadRemoteFolder(currentRemotePath);
        });
    head.add(refreshBtn, BorderLayout.EAST);
    panel.add(head, BorderLayout.NORTH);

    // 1. JTree Setup
    hardwareRoot = new DefaultMutableTreeNode("DELUGE HW");
    songsNode = new DefaultMutableTreeNode("SONGS");
    synthsNode = new DefaultMutableTreeNode("SYNTHS");
    kitsNode = new DefaultMutableTreeNode("KITS");
    hardwareRoot.add(songsNode);
    hardwareRoot.add(synthsNode);
    hardwareRoot.add(kitsNode);

    hardwareTree = new JTree(hardwareRoot);
    hardwareTree.setBackground(new Color(0x12, 0x12, 0x14));
    hardwareTree.setFont(new Font("SansSerif", Font.PLAIN, 16));
    hardwareTree.setRowHeight(30);

    DefaultTreeCellRenderer treeRenderer = new DefaultTreeCellRenderer();
    treeRenderer.setFont(new Font("SansSerif", Font.PLAIN, 16));
    treeRenderer.setBackgroundNonSelectionColor(new Color(0x12, 0x12, 0x14));
    treeRenderer.setTextNonSelectionColor(Color.LIGHT_GRAY);
    treeRenderer.setTextSelectionColor(Color.WHITE);
    treeRenderer.setBackgroundSelectionColor(new Color(0x00, 0xff, 0xcc, 0x33));
    hardwareTree.setCellRenderer(treeRenderer);

    // Tree Selection Listener
    hardwareTree.addTreeSelectionListener(
        e -> {
          javax.swing.tree.TreePath path = hardwareTree.getSelectionPath();
          if (path != null) {
            javax.swing.tree.DefaultMutableTreeNode node =
                (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent();
            String folder = node.getUserObject().toString();
            if ("DELUGE HW".equals(folder)) {
              loadRemoteFolder("/");
            } else if ("SONGS".equals(folder) || "SYNTHS".equals(folder) || "KITS".equals(folder)) {
              loadRemoteFolder("/" + folder);
            }
          }
        });

    JScrollPane treeScroll = new JScrollPane(hardwareTree);
    treeScroll.setBorder(BorderFactory.createEmptyBorder());

    // 2. JTable Setup
    String[] columnNames = {"Name", "Size", "Date Modified"};
    remoteTableModel =
        new javax.swing.table.DefaultTableModel(columnNames, 0) {
          @Override
          public boolean isCellEditable(int row, int column) {
            return false;
          }
        };
    remoteTable = new JTable(remoteTableModel);
    remoteTable.setBackground(new Color(0x15, 0x15, 0x18));
    remoteTable.setForeground(Color.LIGHT_GRAY);
    remoteTable.setGridColor(new Color(0x25, 0x25, 0x28));
    remoteTable.setFont(new Font("SansSerif", Font.PLAIN, 10));
    remoteTable.setRowHeight(22);
    remoteTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    remoteTable.setSelectionBackground(new Color(0x00, 0xff, 0xcc, 0x33));
    remoteTable.setSelectionForeground(Color.WHITE);
    remoteTable.setShowGrid(true);
    remoteTable.setShowHorizontalLines(true);
    remoteTable.setShowVerticalLines(false);

    // Custom header styling
    remoteTable.getTableHeader().setBackground(new Color(0x1a, 0x1a, 0x20));
    remoteTable.getTableHeader().setForeground(Color.LIGHT_GRAY);
    remoteTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 9));
    remoteTable
        .getTableHeader()
        .setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x25, 0x25, 0x28)));

    // Table Double-Click Listener
    remoteTable.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              int row = remoteTable.getSelectedRow();
              if (row >= 0 && row < currentRemoteEntries.size()) {
                org.deluge.midi.RemoteFileEntry entry = currentRemoteEntries.get(row);
                if (entry.isDirectory()) {
                  String nextPath =
                      "/".equals(currentRemotePath)
                          ? "/" + entry.name()
                          : currentRemotePath + "/" + entry.name();
                  loadRemoteFolder(nextPath);
                } else {
                  String cat = "SONGS";
                  if (currentRemotePath.contains("/SYNTHS")) cat = "SYNTHS";
                  else if (currentRemotePath.contains("/KITS")) cat = "KITS";
                  downloadAndLoadRemoteFile(cat, entry.name());
                }
              }
            }
          }
        });

    // 3. Right-Click Popup Menu for Table
    JPopupMenu popupMenu = new JPopupMenu();
    popupMenu.setBackground(new Color(0x1a, 0x1a, 0x20));
    popupMenu.setBorder(BorderFactory.createLineBorder(new Color(0x25, 0x25, 0x28)));

    JMenuItem downloadItem = new JMenuItem("📥 Download & Open");
    downloadItem.setForeground(Color.WHITE);
    downloadItem.addActionListener(
        e -> {
          int row = remoteTable.getSelectedRow();
          if (row >= 0 && row < currentRemoteEntries.size()) {
            org.deluge.midi.RemoteFileEntry entry = currentRemoteEntries.get(row);
            if (!entry.isDirectory()) {
              String cat = "SONGS";
              if (currentRemotePath.contains("/SYNTHS")) cat = "SYNTHS";
              else if (currentRemotePath.contains("/KITS")) cat = "KITS";
              downloadAndLoadRemoteFile(cat, entry.name());
            }
          }
        });
    popupMenu.add(downloadItem);

    JMenuItem uploadItem = new JMenuItem("📤 Upload Local File...");
    uploadItem.setForeground(Color.WHITE);
    uploadItem.addActionListener(e -> triggerLocalUpload());
    popupMenu.add(uploadItem);

    JMenuItem mkdirItem = new JMenuItem("📁 New Folder...");
    mkdirItem.setForeground(Color.WHITE);
    mkdirItem.addActionListener(e -> triggerNewFolder());
    popupMenu.add(mkdirItem);

    JMenuItem renameItem = new JMenuItem("✏️ Rename...");
    renameItem.setForeground(Color.WHITE);
    renameItem.addActionListener(e -> triggerRename());
    popupMenu.add(renameItem);

    JMenuItem deleteItem = new JMenuItem("❌ Delete");
    deleteItem.setForeground(Color.WHITE);
    deleteItem.addActionListener(e -> triggerDelete());
    popupMenu.add(deleteItem);

    JMenuItem copyItem = new JMenuItem("📋 Copy");
    copyItem.setForeground(Color.WHITE);
    copyItem.addActionListener(
        e -> {
          int row = remoteTable.getSelectedRow();
          if (row >= 0 && row < currentRemoteEntries.size()) {
            org.deluge.midi.RemoteFileEntry entry = currentRemoteEntries.get(row);
            if (!entry.isDirectory()) {
              remoteClipboardPath =
                  "/".equals(currentRemotePath)
                      ? "/" + entry.name()
                      : currentRemotePath + "/" + entry.name();
              isRemoteSource = true;
              localClipboardFile = null;
            }
          }
        });
    popupMenu.add(copyItem);

    JMenuItem pasteItem = new JMenuItem("📋 Paste");
    pasteItem.setForeground(Color.WHITE);
    pasteItem.addActionListener(
        e -> {
          if (isRemoteSource && remoteClipboardPath != null) {
            int slash = remoteClipboardPath.lastIndexOf('/');
            String name = slash != -1 ? remoteClipboardPath.substring(slash + 1) : "copy.XML";
            String to = "/".equals(currentRemotePath) ? "/" + name : currentRemotePath + "/" + name;
            if (to.equalsIgnoreCase(remoteClipboardPath)) {
              int extIdx = to.lastIndexOf('.');
              if (extIdx != -1) {
                to = to.substring(0, extIdx) + "_copy" + to.substring(extIdx);
              } else {
                to = to + "_copy";
              }
            }
            copyRemoteFileToRemote(remoteClipboardPath, to);
          } else if (!isRemoteSource && localClipboardFile != null) {
            uploadLocalFileToRemote(localClipboardFile, currentRemotePath);
          }
        });
    popupMenu.add(pasteItem);

    popupMenu.addPopupMenuListener(
        new javax.swing.event.PopupMenuListener() {
          @Override
          public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent ev) {
            boolean hasSelection = remoteTable.getSelectedRow() != -1;
            downloadItem.setEnabled(hasSelection);
            renameItem.setEnabled(hasSelection);
            deleteItem.setEnabled(hasSelection);
            copyItem.setEnabled(hasSelection);

            boolean canPaste = (localClipboardFile != null) || (remoteClipboardPath != null);
            pasteItem.setEnabled(canPaste);
          }

          @Override
          public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent ev) {}

          @Override
          public void popupMenuCanceled(javax.swing.event.PopupMenuEvent ev) {}
        });

    // Bind popup menu
    remoteTable.setComponentPopupMenu(popupMenu);

    JScrollPane tableScroll = new JScrollPane(remoteTable);
    tableScroll.setBorder(BorderFactory.createEmptyBorder());

    // Table container panel with title label showing current path
    JPanel tablePanel = new JPanel(new BorderLayout());
    tablePanel.setBackground(new Color(0x12, 0x12, 0x14));

    JPanel tableHeader = new JPanel(new BorderLayout());
    tableHeader.setBackground(new Color(0x15, 0x15, 0x18));
    tableHeader.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));

    currentFolderLabel = new JLabel("📂 /SONGS");
    currentFolderLabel.setForeground(Color.WHITE);
    currentFolderLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
    tableHeader.add(currentFolderLabel, BorderLayout.WEST);
    tablePanel.add(tableHeader, BorderLayout.NORTH);
    tablePanel.add(tableScroll, BorderLayout.CENTER);

    // Progress bar for async transfers
    transferProgressBar = new JProgressBar(0, 100);
    transferProgressBar.setBackground(new Color(0x12, 0x12, 0x14));
    transferProgressBar.setForeground(new Color(0x00, 0xff, 0xcc));
    transferProgressBar.setStringPainted(true);
    transferProgressBar.setFont(new Font("SansSerif", Font.BOLD, 9));
    transferProgressBar.setVisible(false);
    tablePanel.add(transferProgressBar, BorderLayout.SOUTH);

    // 4. Split Pane holding both Tree (top) and Table (bottom)
    JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, tablePanel);
    splitPane.setDividerLocation(150);
    splitPane.setBackground(new Color(0x12, 0x12, 0x14));
    splitPane.setBorder(BorderFactory.createEmptyBorder());

    panel.add(splitPane, BorderLayout.CENTER);

    // Initial folder load
    Timer initialLoadTimer = new Timer(800, ev -> loadRemoteFolder("/SONGS"));
    initialLoadTimer.setRepeats(false);
    initialLoadTimer.start();

    return panel;
  }

  private void refreshHardwareTree() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      return;
    }

    songsNode.removeAllChildren();
    synthsNode.removeAllChildren();
    kitsNode.removeAllChildren();
    ((javax.swing.tree.DefaultTreeModel) hardwareTree.getModel()).reload();

    fileSync.listSongs(
        "/SONGS",
        new org.deluge.midi.DelugeFileSyncService.FileListCallback() {
          @Override
          public void onSuccess(java.util.List<String> files) {
            SwingUtilities.invokeLater(
                () -> {
                  for (String f : files) {
                    songsNode.add(new DefaultMutableTreeNode(f));
                  }
                  ((javax.swing.tree.DefaultTreeModel) hardwareTree.getModel()).reload(songsNode);
                });
          }

          @Override
          public void onFailure(Throwable t) {
            System.err.println("[Sidebar] Failed to fetch remote songs: " + t.getMessage());
          }
        });

    Timer t1 =
        new Timer(
            250,
            ev -> {
              if (fileSync.isTransferActive()) return;
              fileSync.listSongs(
                  "/SYNTHS",
                  new org.deluge.midi.DelugeFileSyncService.FileListCallback() {
                    @Override
                    public void onSuccess(java.util.List<String> files) {
                      SwingUtilities.invokeLater(
                          () -> {
                            for (String f : files) {
                              synthsNode.add(new DefaultMutableTreeNode(f));
                            }
                            ((javax.swing.tree.DefaultTreeModel) hardwareTree.getModel())
                                .reload(synthsNode);
                          });
                    }

                    @Override
                    public void onFailure(Throwable t) {
                      System.err.println(
                          "[Sidebar] Failed to fetch remote synths: " + t.getMessage());
                    }
                  });
            });
    t1.setRepeats(false);
    t1.start();

    Timer t2 =
        new Timer(
            500,
            ev -> {
              if (fileSync.isTransferActive()) return;
              fileSync.listSongs(
                  "/KITS",
                  new org.deluge.midi.DelugeFileSyncService.FileListCallback() {
                    @Override
                    public void onSuccess(java.util.List<String> files) {
                      SwingUtilities.invokeLater(
                          () -> {
                            for (String f : files) {
                              kitsNode.add(new DefaultMutableTreeNode(f));
                            }
                            ((javax.swing.tree.DefaultTreeModel) hardwareTree.getModel())
                                .reload(kitsNode);
                          });
                    }

                    @Override
                    public void onFailure(Throwable t) {
                      System.err.println(
                          "[Sidebar] Failed to fetch remote kits: " + t.getMessage());
                    }
                  });
            });
    t2.setRepeats(false);
    t2.start();
  }

  private void downloadAndLoadRemoteFile(String category, String name) {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    String remotePath = "/" + category + "/" + name;

    // Where the downloaded file is saved locally (mirrors the Deluge's SONGS/KITS/SYNTHS layout).
    File destDir =
        switch (category) {
          case "SONGS" -> PreferencesManager.getSongsDir();
          case "KITS" -> PreferencesManager.getKitsDir();
          case "SYNTHS" -> PreferencesManager.getSynthsDir();
          default -> new File(PreferencesManager.getLibraryDir(), category);
        };
    File destFile = new File(destDir, name);

    JDialog progress = new JDialog(SwingDelugeApp.mainInstance, "Downloading", true);
    progress.setLayout(new BorderLayout(0, 8));
    ((JComponent) progress.getContentPane())
        .setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

    JLabel statusLbl = new JLabel("Downloading " + name + " from Deluge…");
    statusLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
    progress.add(statusLbl, BorderLayout.NORTH);

    JProgressBar bar = new JProgressBar(0, 100);
    bar.setIndeterminate(true);
    bar.setStringPainted(true);
    bar.setString("Connecting…");
    bar.setPreferredSize(new Dimension(360, 22));
    progress.add(bar, BorderLayout.CENTER);

    JLabel destLbl = new JLabel("Saving to: " + destFile.getAbsolutePath());
    destLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
    destLbl.setForeground(new Color(0x88, 0x88, 0x90));
    destLbl.setToolTipText(destFile.getAbsolutePath());
    progress.add(destLbl, BorderLayout.SOUTH);

    progress.pack();
    progress.setLocationRelativeTo(SwingDelugeApp.mainInstance);

    var progressCb =
        (org.deluge.midi.DelugeFileSyncService.ProgressCallback)
            (done, total) ->
                SwingUtilities.invokeLater(
                    () -> {
                      if (total > 0) {
                        bar.setIndeterminate(false);
                        int pct = (int) Math.min(100, (done * 100L) / total);
                        bar.setValue(pct);
                        bar.setString(String.format("%d%%  (%,d / %,d bytes)", pct, done, total));
                      }
                    });

    fileSync.downloadFileAsync(
        remotePath,
        new org.deluge.midi.DelugeFileSyncService.FileDownloadCallback() {
          @Override
          public void onSuccess(byte[] content) {
            // Persist the downloaded file to the local library before loading it.
            try {
              destDir.mkdirs();
              java.nio.file.Files.write(destFile.toPath(), content);
            } catch (Exception saveEx) {
              System.err.println(
                  "[Sidebar] Warning: could not save downloaded file to "
                      + destFile.getAbsolutePath()
                      + ": "
                      + saveEx.getMessage());
            }
            SwingUtilities.invokeLater(
                () -> {
                  progress.dispose();
                  try (java.io.ByteArrayInputStream bis =
                      new java.io.ByteArrayInputStream(content)) {
                    if ("SONGS".equals(category)) {
                      org.deluge.model.ProjectModel loadedProject =
                          DelugeXmlParser.parseSong(bis, name);
                      java.io.File libraryDir = PreferencesManager.getLibraryDir();
                      java.util.ArrayList<String> missingFiles = new java.util.ArrayList<>();
                      int engineRow = 0;
                      for (org.deluge.model.TrackModel track : loadedProject.getTracks()) {
                        if (engineRow >= BridgeContract.TRACKS) break;
                        if (track instanceof org.deluge.model.KitTrackModel kit) {
                          java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
                          for (int i = 0; i < sounds.size(); i++) {
                            String sp =
                                ((org.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
                            if (sp != null && !sp.isEmpty()) {
                              java.io.File sf = new java.io.File(sp);
                              if (!sf.exists() && libraryDir != null) {
                                sf = new java.io.File(libraryDir, sp);
                              }
                              if (!sf.exists()) {
                                missingFiles.add(sp);
                              }
                            }
                            bridge.setGlobalString(
                                "g_sample_" + (engineRow + i), sp != null ? sp : "");
                            bridge.setMute(engineRow + i, false);
                            bridge.setTrackType(engineRow + i, 0);
                          }
                        }
                        engineRow++;
                      }
                      if (onSongLoaded != null) {
                        onSongLoaded.accept(loadedProject, destFile);
                      }
                    } else if ("SYNTHS".equals(category)) {
                      org.deluge.model.SynthTrackModel synth =
                          DelugeXmlParser.parseSynth(bis, name);
                      if (onTrackAdded != null) {
                        onTrackAdded.accept(synth);
                      } else {
                        org.deluge.model.ProjectModel mockProj =
                            new org.deluge.model.ProjectModel();
                        mockProj.addTrack(synth);
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(mockProj, destFile);
                        }
                      }
                      bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    } else if ("KITS".equals(category)) {
                      org.deluge.model.KitTrackModel kit = DelugeXmlParser.parseKit(bis, name);
                      int baseTrack = 0;
                      java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
                      for (int i = 0; i < sounds.size(); i++) {
                        String sp = ((org.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
                        bridge.setGlobalString("g_sample_" + (baseTrack + i), sp != null ? sp : "");
                        bridge.setSamplePath(baseTrack + i, sp != null ? sp : "");
                        bridge.setMute(baseTrack + i, false);
                      }
                      if (onTrackAdded != null) {
                        onTrackAdded.accept(kit);
                      } else {
                        org.deluge.model.ProjectModel mockProj =
                            new org.deluge.model.ProjectModel();
                        mockProj.addTrack(kit);
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(mockProj, destFile);
                        }
                      }
                      bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    }
                  } catch (Exception ex) {
                    ex.printStackTrace();
                    JOptionPane.showMessageDialog(
                        SwingDelugeApp.mainInstance,
                        "Failed to load/parse remote file:\n" + ex.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                  }
                });
          }

          @Override
          public void onFailure(Throwable t) {
            t.printStackTrace();
            SwingUtilities.invokeLater(
                () -> {
                  progress.dispose();
                  JOptionPane.showMessageDialog(
                      SwingDelugeApp.mainInstance,
                      "Failed to download remote file:\n" + t.getMessage(),
                      "Error",
                      JOptionPane.ERROR_MESSAGE);
                });
          }
        },
        progressCb);

    progress.setVisible(true);
  }

  private void loadRemoteFolder(String remotePath) {
    if (SwingDelugeApp.mainInstance == null) return;
    var midiService = SwingDelugeApp.mainInstance.getMidiService();
    if (midiService == null) return;
    var fileSync = midiService.getFileSyncService();
    if (fileSync == null) return;
    if (fileSync.isTransferActive()) {
      return;
    }
    this.currentRemotePath = remotePath;
    currentFolderLabel.setText("📂 " + remotePath);

    fileSync.listDirectory(
        remotePath,
        new org.deluge.midi.DelugeFileSyncService.DirectoryListCallback() {
          @Override
          public void onSuccess(java.util.List<org.deluge.midi.RemoteFileEntry> entries) {
            SwingUtilities.invokeLater(
                () -> {
                  currentRemoteEntries = entries;
                  remoteTableModel.setRowCount(0);
                  for (org.deluge.midi.RemoteFileEntry entry : entries) {
                    String sizeStr = entry.isDirectory() ? "<DIR>" : formatFileSize(entry.size());
                    String dateStr = formatDate(entry.lastModifiedMillis());
                    remoteTableModel.addRow(new Object[] {entry.name(), sizeStr, dateStr});
                  }
                });
          }

          @Override
          public void onFailure(Throwable t) {
            System.err.println("[Sidebar] Failed to list remote directory: " + t.getMessage());
          }
        });
  }

  private void triggerLocalUpload() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    JFileChooser chooser = new JFileChooser();
    chooser.setDialogTitle("Select File to Upload");
    int result = chooser.showOpenDialog(this);
    if (result == JFileChooser.APPROVE_OPTION) {
      java.io.File localFile = chooser.getSelectedFile();
      try {
        byte[] content = java.nio.file.Files.readAllBytes(localFile.toPath());
        String remoteFilePath =
            "/".equals(currentRemotePath)
                ? "/" + localFile.getName()
                : currentRemotePath + "/" + localFile.getName();

        transferProgressBar.setVisible(true);
        transferProgressBar.setIndeterminate(true);
        transferProgressBar.setString("Uploading " + localFile.getName() + "...");

        fileSync.uploadFileAsync(
            remoteFilePath,
            content,
            new org.deluge.midi.DelugeFileSyncService.FileUploadCallback() {
              @Override
              public void onSuccess() {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      loadRemoteFolder(currentRemotePath);
                      JOptionPane.showMessageDialog(
                          SwingProjectSidebarPanel.this,
                          "File uploaded successfully!",
                          "Success",
                          JOptionPane.INFORMATION_MESSAGE);
                    });
              }

              @Override
              public void onFailure(Throwable t) {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      JOptionPane.showMessageDialog(
                          SwingProjectSidebarPanel.this,
                          "Upload failed: " + t.getMessage(),
                          "Error",
                          JOptionPane.ERROR_MESSAGE);
                    });
              }
            });
      } catch (Exception ex) {
        JOptionPane.showMessageDialog(
            this,
            "Failed to read local file: " + ex.getMessage(),
            "Error",
            JOptionPane.ERROR_MESSAGE);
      }
    }
  }

  private void triggerNewFolder() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    String name = JOptionPane.showInputDialog(this, "Enter folder name:");
    if (name != null && !name.trim().isEmpty()) {
      String remotePath =
          "/".equals(currentRemotePath) ? "/" + name.trim() : currentRemotePath + "/" + name.trim();

      transferProgressBar.setVisible(true);
      transferProgressBar.setIndeterminate(true);
      transferProgressBar.setString("Creating folder " + name.trim() + "...");

      fileSync.createDirectoryAsync(
          remotePath,
          System.currentTimeMillis(),
          new org.deluge.midi.DelugeFileSyncService.FileOpCallback() {
            @Override
            public void onSuccess() {
              SwingUtilities.invokeLater(
                  () -> {
                    transferProgressBar.setVisible(false);
                    loadRemoteFolder(currentRemotePath);
                  });
            }

            @Override
            public void onFailure(Throwable t) {
              SwingUtilities.invokeLater(
                  () -> {
                    transferProgressBar.setVisible(false);
                    JOptionPane.showMessageDialog(
                        SwingProjectSidebarPanel.this,
                        "Failed to create folder: " + t.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                  });
            }
          });
    }
  }

  private void triggerRename() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    int row = remoteTable.getSelectedRow();
    if (row >= 0 && row < currentRemoteEntries.size()) {
      org.deluge.midi.RemoteFileEntry entry = currentRemoteEntries.get(row);
      String newName = JOptionPane.showInputDialog(this, "Enter new name:", entry.name());
      if (newName != null && !newName.trim().isEmpty()) {
        String from =
            "/".equals(currentRemotePath)
                ? "/" + entry.name()
                : currentRemotePath + "/" + entry.name();
        String to =
            "/".equals(currentRemotePath)
                ? "/" + newName.trim()
                : currentRemotePath + "/" + newName.trim();

        transferProgressBar.setVisible(true);
        transferProgressBar.setIndeterminate(true);
        transferProgressBar.setString("Renaming " + entry.name() + "...");

        fileSync.renameAsync(
            from,
            to,
            new org.deluge.midi.DelugeFileSyncService.FileOpCallback() {
              @Override
              public void onSuccess() {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      loadRemoteFolder(currentRemotePath);
                    });
              }

              @Override
              public void onFailure(Throwable t) {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      JOptionPane.showMessageDialog(
                          SwingProjectSidebarPanel.this,
                          "Failed to rename: " + t.getMessage(),
                          "Error",
                          JOptionPane.ERROR_MESSAGE);
                    });
              }
            });
      }
    }
  }

  private void triggerDelete() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    int row = remoteTable.getSelectedRow();
    if (row >= 0 && row < currentRemoteEntries.size()) {
      org.deluge.midi.RemoteFileEntry entry = currentRemoteEntries.get(row);
      int confirm =
          JOptionPane.showConfirmDialog(
              this,
              "Are you sure you want to delete " + entry.name() + "?",
              "Confirm Delete",
              JOptionPane.YES_NO_OPTION,
              JOptionPane.WARNING_MESSAGE);
      if (confirm == JOptionPane.YES_OPTION) {
        String path =
            "/".equals(currentRemotePath)
                ? "/" + entry.name()
                : currentRemotePath + "/" + entry.name();

        transferProgressBar.setVisible(true);
        transferProgressBar.setIndeterminate(true);
        transferProgressBar.setString("Deleting " + entry.name() + "...");

        fileSync.deleteAsync(
            path,
            new org.deluge.midi.DelugeFileSyncService.FileOpCallback() {
              @Override
              public void onSuccess() {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      loadRemoteFolder(currentRemotePath);
                    });
              }

              @Override
              public void onFailure(Throwable t) {
                SwingUtilities.invokeLater(
                    () -> {
                      transferProgressBar.setVisible(false);
                      JOptionPane.showMessageDialog(
                          SwingProjectSidebarPanel.this,
                          "Failed to delete: " + t.getMessage(),
                          "Error",
                          JOptionPane.ERROR_MESSAGE);
                    });
              }
            });
      }
    }
  }

  private static String formatFileSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    int exp = (int) (Math.log(bytes) / Math.log(1024));
    char pre = "KMGTPE".charAt(exp - 1);
    return String.format("%.1f %cB", bytes / Math.pow(1024, exp), pre);
  }

  private static String formatDate(long millis) {
    if (millis <= 0) return "---";
    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm");
    return sdf.format(new java.util.Date(millis));
  }

  // ── Copy/Paste Helpers ──

  private File getLocalFileForPath(javax.swing.tree.TreePath path) {
    if (path == null || path.getPathCount() < 2) return null;
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
    if (baseDir == null) return null;

    StringBuilder relBuilder = new StringBuilder();
    for (int i = 2; i < path.getPathCount(); i++) {
      if (i > 2) relBuilder.append(File.separator);
      relBuilder.append(path.getPathComponent(i).toString());
    }
    String relPath = relBuilder.toString();

    // Check if it's a directory
    File directDir = new File(baseDir, relPath);
    if (directDir.isDirectory()) {
      return directDir;
    }

    String[] tryExts = {".XML", ".xml", ".ck"};
    for (String ext : tryExts) {
      File candidate = new File(baseDir, relPath + ext);
      if (candidate.isFile()) {
        return candidate;
      }
    }
    return directDir; // fallback
  }

  private void downloadRemoteFileToLocal(String remotePath, File destFile) {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }

    String name = destFile.getName();

    JDialog progress = new JDialog(SwingDelugeApp.mainInstance, "Downloading", true);
    progress.setLayout(new BorderLayout(0, 8));
    ((JComponent) progress.getContentPane())
        .setBorder(BorderFactory.createEmptyBorder(14, 18, 14, 18));

    JLabel statusLbl = new JLabel("Downloading " + name + " from Deluge…");
    statusLbl.setFont(new Font("SansSerif", Font.BOLD, 12));
    progress.add(statusLbl, BorderLayout.NORTH);

    JProgressBar bar = new JProgressBar(0, 100);
    bar.setIndeterminate(true);
    bar.setStringPainted(true);
    bar.setString("Connecting…");
    bar.setPreferredSize(new Dimension(360, 22));
    progress.add(bar, BorderLayout.CENTER);

    JLabel destLbl = new JLabel("Saving to: " + destFile.getAbsolutePath());
    destLbl.setFont(new Font("SansSerif", Font.PLAIN, 10));
    destLbl.setForeground(new Color(0x88, 0x88, 0x90));
    progress.add(destLbl, BorderLayout.SOUTH);

    progress.pack();
    progress.setLocationRelativeTo(SwingDelugeApp.mainInstance);

    var progressCb =
        (org.deluge.midi.DelugeFileSyncService.ProgressCallback)
            (done, total) ->
                SwingUtilities.invokeLater(
                    () -> {
                      if (total > 0) {
                        bar.setIndeterminate(false);
                        int pct = (int) Math.min(100, (done * 100L) / total);
                        bar.setValue(pct);
                        bar.setString(String.format("%d%%  (%,d / %,d bytes)", pct, done, total));
                      }
                    });

    fileSync.downloadFileAsync(
        remotePath,
        new org.deluge.midi.DelugeFileSyncService.FileDownloadCallback() {
          @Override
          public void onSuccess(byte[] content) {
            try {
              destFile.getParentFile().mkdirs();
              java.nio.file.Files.write(destFile.toPath(), content);
              SwingUtilities.invokeLater(
                  () -> {
                    progress.dispose();
                    reloadLibrary();
                    JOptionPane.showMessageDialog(
                        SwingProjectSidebarPanel.this,
                        "File downloaded successfully!",
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
                  });
            } catch (Exception saveEx) {
              saveEx.printStackTrace();
              SwingUtilities.invokeLater(
                  () -> {
                    progress.dispose();
                    JOptionPane.showMessageDialog(
                        SwingProjectSidebarPanel.this,
                        "Failed to save downloaded file:\n" + saveEx.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                  });
            }
          }

          @Override
          public void onFailure(Throwable t) {
            t.printStackTrace();
            SwingUtilities.invokeLater(
                () -> {
                  progress.dispose();
                  JOptionPane.showMessageDialog(
                      SwingProjectSidebarPanel.this,
                      "Failed to download remote file:\n" + t.getMessage(),
                      "Error",
                      JOptionPane.ERROR_MESSAGE);
                });
          }
        },
        progressCb);

    progress.setVisible(true);
  }

  private void uploadLocalFileToRemote(File localFile, String remotePath) {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }
    try {
      byte[] content = java.nio.file.Files.readAllBytes(localFile.toPath());
      String remoteFilePath =
          "/".equals(remotePath)
              ? "/" + localFile.getName()
              : remotePath + "/" + localFile.getName();

      transferProgressBar.setVisible(true);
      transferProgressBar.setIndeterminate(true);
      transferProgressBar.setString("Uploading " + localFile.getName() + "...");

      fileSync.uploadFileAsync(
          remoteFilePath,
          content,
          new org.deluge.midi.DelugeFileSyncService.FileUploadCallback() {
            @Override
            public void onSuccess() {
              SwingUtilities.invokeLater(
                  () -> {
                    transferProgressBar.setVisible(false);
                    loadRemoteFolder(currentRemotePath);
                    JOptionPane.showMessageDialog(
                        SwingProjectSidebarPanel.this,
                        "File uploaded successfully!",
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE);
                  });
            }

            @Override
            public void onFailure(Throwable t) {
              SwingUtilities.invokeLater(
                  () -> {
                    transferProgressBar.setVisible(false);
                    JOptionPane.showMessageDialog(
                        SwingProjectSidebarPanel.this,
                        "Upload failed: " + t.getMessage(),
                        "Error",
                        JOptionPane.ERROR_MESSAGE);
                  });
            }
          });
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(
          this,
          "Failed to read local file: " + ex.getMessage(),
          "Error",
          JOptionPane.ERROR_MESSAGE);
    }
  }

  private void copyRemoteFileToRemote(String fromPath, String toPath) {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      JOptionPane.showMessageDialog(
          this, "A file transfer is already active.", "Warning", JOptionPane.WARNING_MESSAGE);
      return;
    }

    transferProgressBar.setVisible(true);
    transferProgressBar.setIndeterminate(true);
    transferProgressBar.setString("Copying " + fromPath + " to " + toPath + "...");

    fileSync.copyAsync(
        fromPath,
        toPath,
        System.currentTimeMillis(),
        new org.deluge.midi.DelugeFileSyncService.FileOpCallback() {
          @Override
          public void onSuccess() {
            SwingUtilities.invokeLater(
                () -> {
                  transferProgressBar.setVisible(false);
                  loadRemoteFolder(currentRemotePath);
                  JOptionPane.showMessageDialog(
                      SwingProjectSidebarPanel.this,
                      "File copied successfully!",
                      "Success",
                      JOptionPane.INFORMATION_MESSAGE);
                });
          }

          @Override
          public void onFailure(Throwable t) {
            SwingUtilities.invokeLater(
                () -> {
                  transferProgressBar.setVisible(false);
                  JOptionPane.showMessageDialog(
                      SwingProjectSidebarPanel.this,
                      "Copy failed: " + t.getMessage(),
                      "Error",
                      JOptionPane.ERROR_MESSAGE);
                });
          }
        });
  }
}
