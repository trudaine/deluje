package org.deluge.ui;

import java.awt.*;
import java.io.File;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import org.deluge.BridgeContract;
import org.deluge.project.PreferencesManager;
import org.deluge.xml.DelugeXmlParser;

public class LibrarySidebarTab extends JScrollPane {

  private final SwingProjectSidebarPanel parent;
  private DefaultMutableTreeNode libraryRoot;
  private JTree libraryTree;

  public LibrarySidebarTab(SwingProjectSidebarPanel parent) {
    this.parent = parent;

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

    JMenuItem localCopyItem = new JMenuItem("📋 Copy");
    localPopupMenu.add(localCopyItem);

    JMenuItem localPasteItem = new JMenuItem("📋 Paste");
    localPopupMenu.add(localPasteItem);

    SwingGridPanel.stylePopupMenu(localPopupMenu);

    localCopyItem.addActionListener(
        ev -> {
          javax.swing.tree.TreePath path = libraryTree.getSelectionPath();
          if (path != null) {
            File file = getLocalFileForPath(path);
            if (file != null && file.isFile()) {
              parent.localClipboardFile = file;
              parent.remoteClipboardPath = null;
              parent.isRemoteSource = false;
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
                && parent.isRemoteSource
                && parent.remoteClipboardPath != null) {
              int lastSlash = parent.remoteClipboardPath.lastIndexOf('/');
              String name =
                  lastSlash != -1
                      ? parent.remoteClipboardPath.substring(lastSlash + 1)
                      : "downloaded.XML";
              File destFile = new File(targetDir, name);
              downloadRemoteFileToLocal(parent.remoteClipboardPath, destFile);
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
                      file.isDirectory()
                          && parent.isRemoteSource
                          && parent.remoteClipboardPath != null);
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
                    if (parent.onPatternLoad != null) {
                      parent.onPatternLoad.accept(leafFile);
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
                                parent.bridge.setGlobalString(
                                    "g_sample_" + (baseTrack + i), sp != null ? sp : "");
                                parent.bridge.setSamplePath(baseTrack + i, sp != null ? sp : "");
                                parent.bridge.setMute(baseTrack + i, false);
                              }
                              javax.swing.SwingUtilities.invokeLater(
                                  () -> {
                                    if (parent.onTrackAdded != null) {
                                      parent.onTrackAdded.accept(kit);
                                    } else {
                                      org.deluge.model.ProjectModel mockProj =
                                          new org.deluge.model.ProjectModel();
                                      mockProj.addTrack(kit);
                                      if (parent.onSongLoaded != null) {
                                        parent.onSongLoaded.accept(mockProj, finalLeafFile);
                                      }
                                    }
                                    parent.bridge.broadcastGlobalEvent(
                                        BridgeContract.G_LOAD_TRIGGER);
                                  });
                            } else if ("SYNTHS".equals(finalCategory)) {
                              org.deluge.model.SynthTrackModel synth =
                                  org.deluge.xml.DelugeXmlParser.parseSynth(is, finalName);
                              javax.swing.SwingUtilities.invokeLater(
                                  () -> {
                                    if (parent.onTrackAdded != null) {
                                      parent.onTrackAdded.accept(synth);
                                    } else {
                                      org.deluge.model.ProjectModel mockProj =
                                          new org.deluge.model.ProjectModel();
                                      mockProj.addTrack(synth);
                                      if (parent.onSongLoaded != null) {
                                        parent.onSongLoaded.accept(mockProj, finalLeafFile);
                                      }
                                    }
                                    parent.bridge.broadcastGlobalEvent(
                                        BridgeContract.G_LOAD_TRIGGER);
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
                                      java.io.File sf =
                                          org.deluge.engine.FirmwareFactory.resolveSample(
                                              sp, libraryDir);
                                      if (sf == null || !sf.exists()) {
                                        missingFiles.add(sp);
                                      }
                                    }
                                    parent.bridge.setGlobalString(
                                        "g_sample_" + (engineRow + i), sp != null ? sp : "");
                                    parent.bridge.setMute(engineRow + i, false);
                                    parent.bridge.setTrackType(engineRow + i, 0);
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
                                          LibrarySidebarTab.this,
                                          sb.toString(),
                                          "Samples Not Found",
                                          JOptionPane.ERROR_MESSAGE);
                                    }
                                    if (parent.onSongLoaded != null) {
                                      parent.onSongLoaded.accept(loadedProject, finalLeafFile);
                                    }
                                    parent.bridge.broadcastGlobalEvent(
                                        BridgeContract.G_LOAD_TRIGGER);
                                  });
                            } else if ("PATTERNS".equals(finalCategory)) {
                              javax.swing.SwingUtilities.invokeLater(
                                  () -> {
                                    if (parent.onPatternLoad != null) {
                                      parent.onPatternLoad.accept(finalLeafFile);
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
                                    LibrarySidebarTab.this,
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

    setViewportView(libraryTree);
    setBackground(new Color(0x12, 0x12, 0x14));
    getViewport().setBackground(new Color(0x12, 0x12, 0x14));
    setBorder(BorderFactory.createEmptyBorder());
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
                        LibrarySidebarTab.this,
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
                        LibrarySidebarTab.this,
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
                      LibrarySidebarTab.this,
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
