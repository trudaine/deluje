package org.chuck.deluge.ui.swing2;

import java.awt.*;
import javax.swing.*;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.model.ProjectModel;

public class Swing2ProjectSidebarPanel extends JPanel {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private java.util.function.Consumer<org.chuck.deluge.model.ProjectModel> onSongLoaded;

  private JSlider volSlider;
  private JSlider lpfSlider;
  private JSlider attSlider;

  private JTextArea scriptArea;
  private String activeScriptPath;
  private JTabbedPane tabs;
  private JPanel ckParamsBox;
  private ProjectModel projectModel;

  public Swing2ProjectSidebarPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

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
    tree.setRowHeight(30);

    javax.swing.tree.DefaultTreeCellRenderer renderer =
        new javax.swing.tree.DefaultTreeCellRenderer();
    renderer.setBackgroundNonSelectionColor(new Color(0x1f, 0x1f, 0x1f));
    renderer.setTextNonSelectionColor(Color.LIGHT_GRAY);
    renderer.setTextSelectionColor(Color.WHITE);
    renderer.setBackgroundSelectionColor(new Color(0x00, 0xff, 0xcc, 0x55));
    tree.setCellRenderer(renderer);

    tree.addMouseListener(
        new java.awt.event.MouseAdapter() {
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
                  java.io.InputStream is = getClass().getResourceAsStream(resourcePath);
                  if (is == null) {
                    is = getClass().getResourceAsStream(resourcePath.replace(".XML", ".xml"));
                  }
                  if (is == null) {
                    java.io.File f = new java.io.File("target/classes/SONGS/" + name + ".xml");
                    if (!f.exists())
                      f = new java.io.File("deluge/target/classes/SONGS/" + name + ".xml");
                    if (f.exists()) {
                      try {
                        is = new java.io.FileInputStream(f);
                      } catch (Exception ex) {
                      }
                    }
                  }
                  try {
                    if (is != null && "SONGS".equals(internalDir)) {
                      org.chuck.deluge.model.ProjectModel loaded =
                          org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, name);
                      if (onSongLoaded != null) onSongLoaded.accept(loaded);
                    }
                  } catch (Exception ex) {
                    ex.printStackTrace();
                  } finally {
                    try {
                      if (is != null) is.close();
                    } catch (Exception ex) {
                    }
                  }
                }
              }
            }
          }
        });

    JPanel wrapper = new JPanel(new BorderLayout());
    wrapper.add(new JScrollPane(tree), BorderLayout.CENTER);
    return wrapper;
  }

  private void addResourcesToTree(
      javax.swing.tree.DefaultMutableTreeNode root, String label, String internalDir) {
    javax.swing.tree.DefaultMutableTreeNode folder =
        new javax.swing.tree.DefaultMutableTreeNode(label);
    root.add(folder);
    try {
      java.net.URL url = getClass().getResource(internalDir);
      if (url != null) {
        java.net.URI uri = url.toURI();
        java.nio.file.Path path;
        if (uri.getScheme().equals("file")) {
          path = java.nio.file.Paths.get(uri);
          if (java.nio.file.Files.exists(path)) {
            buildDirectoryTree(folder, path, path);
          }
        }
      }
    } catch (Exception ex) {
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
                  if (fn.toUpperCase().endsWith(".XML") || fn.toUpperCase().endsWith(".CK")) {
                    int dotIdx = fn.lastIndexOf('.');
                    node.add(
                        new javax.swing.tree.DefaultMutableTreeNode(
                            dotIdx != -1 ? fn.substring(0, dotIdx) : fn));
                  }
                }
              });
    } catch (Exception ex) {
    }
  }

  private JComponent createEditorTab() {
    JPanel panel = new JPanel();
    panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
    panel.setBackground(new Color(0x25, 0x25, 0x25));

    JPanel oscBox = createSection("OSCILLATORS");
    JLabel osc1Label = new JLabel("Osc 1 Vol: 64");
    osc1Label.setForeground(Color.WHITE);
    volSlider = new JSlider(0, 127, 64);
    volSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    oscBox.add(osc1Label);
    oscBox.add(volSlider);
    panel.add(oscBox);

    JPanel filterBox = createSection("FILTERS");
    JLabel lpfLabel = new JLabel("LPF Cutoff: 64");
    lpfLabel.setForeground(Color.WHITE);
    lpfSlider = new JSlider(0, 127, 64);
    lpfSlider.setBackground(new Color(0x1f, 0x1f, 0x1f));
    filterBox.add(lpfLabel);
    filterBox.add(lpfSlider);
    panel.add(filterBox);

    return new JScrollPane(panel);
  }

  private JComponent createMidiTab() {
    JPanel panel = new JPanel(new BorderLayout());
    panel.setBackground(new Color(0x25, 0x25, 0x25));
    panel.add(new JLabel("MIDI mapping table"), BorderLayout.NORTH);
    return panel;
  }

  private JComponent createScriptTab() {
    return new JPanel();
  }

  private JComponent createProfilerTab() {
    return new JPanel();
  }

  private JComponent createSnippetsTab() {
    return new JPanel();
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

  public void setProjectModel(ProjectModel model) {
    this.projectModel = model;
  }

  public void setOnSongLoaded(java.util.function.Consumer<ProjectModel> callback) {
    this.onSongLoaded = callback;
  }
}
