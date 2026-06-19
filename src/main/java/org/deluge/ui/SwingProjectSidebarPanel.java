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

  private java.util.function.Consumer<org.deluge.model.ProjectModel> onSongLoaded;
  private java.util.function.Consumer<org.deluge.model.TrackModel> onTrackAdded;
  private java.util.function.Consumer<java.io.File> onPatternLoad;
  private Runnable onPatternSave;

  /**
   * Invoked after the SD-card root has been changed from this panel's header button, so the app can
   * refresh every view that mirrors the library (e.g. both sidebar instances). When unset, the
   * panel just reloads its own tree.
   */
  private Runnable onLibraryDirChanged;

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
                      org.deluge.model.KitTrackModel kit = DelugeXmlParser.parseKit(is, name);
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
                          onSongLoaded.accept(mockProj);
                        }
                      }
                      bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    } else if ("SYNTHS".equals(category)) {
                      org.deluge.model.SynthTrackModel synth =
                          org.deluge.xml.DelugeXmlParser.parseSynth(is, name);
                      if (onTrackAdded != null) {
                        onTrackAdded.accept(synth);
                      } else {
                        org.deluge.model.ProjectModel mockProj =
                            new org.deluge.model.ProjectModel();
                        mockProj.addTrack(synth);
                        if (onSongLoaded != null) {
                          onSongLoaded.accept(mockProj);
                        }
                      }
                      bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
                    } else if ("SONGS".equals(category)) {
                      org.deluge.model.ProjectModel loadedProject =
                          DelugeXmlParser.parseSong(is, name);
                      int engineRow = 0;
                      java.io.File libraryDir = PreferencesManager.getLibraryDir();
                      java.util.ArrayList<String> missingFiles = new java.util.ArrayList<>();
                      for (org.deluge.model.TrackModel track : loadedProject.getTracks()) {
                        if (engineRow >= BridgeContract.TRACKS) break;
                        if (track instanceof org.deluge.model.KitTrackModel kit) {
                          java.util.List<org.deluge.model.Drum> sounds = kit.getDrums();
                          for (int i = 0; i < sounds.size(); i++) {
                            String sp =
                                ((org.deluge.model.SoundDrum) sounds.get(i)).getSamplePath();
                            if (sp != null && !sp.isEmpty()) {
                              java.io.File sf = new java.io.File(sp);
                              if (!sf.exists()) {
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
                      bridge.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
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

  public void setOnSongLoaded(java.util.function.Consumer<org.deluge.model.ProjectModel> l) {
    this.onSongLoaded = l;
  }

  public java.util.function.Consumer<org.deluge.model.ProjectModel> getOnSongLoaded() {
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
    refreshBtn.addActionListener(e -> refreshHardwareTree());
    head.add(refreshBtn, BorderLayout.EAST);
    panel.add(head, BorderLayout.NORTH);

    hardwareRoot = new DefaultMutableTreeNode("DELUGE HW");
    songsNode = new DefaultMutableTreeNode("SONGS");
    synthsNode = new DefaultMutableTreeNode("SYNTHS");
    kitsNode = new DefaultMutableTreeNode("KITS");
    hardwareRoot.add(songsNode);
    hardwareRoot.add(synthsNode);
    hardwareRoot.add(kitsNode);

    hardwareTree = new JTree(hardwareRoot);
    hardwareTree.setBackground(new Color(0x12, 0x12, 0x14));
    hardwareTree.setFont(new Font("SansSerif", Font.PLAIN, 10));
    hardwareTree.setRowHeight(20);

    DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
    renderer.setFont(new Font("SansSerif", Font.PLAIN, 10));
    renderer.setBackgroundNonSelectionColor(new Color(0x12, 0x12, 0x14));
    renderer.setTextNonSelectionColor(Color.LIGHT_GRAY);
    renderer.setTextSelectionColor(Color.WHITE);
    renderer.setBackgroundSelectionColor(new Color(0x00, 0xff, 0xcc, 0x33));
    hardwareTree.setCellRenderer(renderer);

    hardwareTree.addMouseListener(
        new java.awt.event.MouseAdapter() {
          @Override
          public void mousePressed(java.awt.event.MouseEvent e) {
            if (e.getClickCount() == 2) {
              javax.swing.tree.TreePath path = hardwareTree.getSelectionPath();
              if (path != null) {
                javax.swing.tree.DefaultMutableTreeNode node =
                    (javax.swing.tree.DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.isLeaf()) {
                  String name = node.getUserObject().toString();
                  String category = path.getPathComponent(1).toString();
                  downloadAndLoadRemoteFile(category, name);
                }
              }
            }
          }
        });

    JScrollPane scroll = new JScrollPane(hardwareTree);
    scroll.setBorder(BorderFactory.createEmptyBorder());
    panel.add(scroll, BorderLayout.CENTER);

    return panel;
  }

  private void refreshHardwareTree() {
    if (SwingDelugeApp.mainInstance == null) return;
    var fileSync = SwingDelugeApp.mainInstance.getMidiService().getFileSyncService();
    if (fileSync.isTransferActive()) {
      System.out.println("[Sidebar] Skipping hardware refresh: active file transfer in progress.");
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
              System.out.println(
                  "[Sidebar] Saved downloaded " + name + " -> " + destFile.getAbsolutePath());
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
                        onSongLoaded.accept(loadedProject);
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
                          onSongLoaded.accept(mockProj);
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
                          onSongLoaded.accept(mockProj);
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
}
