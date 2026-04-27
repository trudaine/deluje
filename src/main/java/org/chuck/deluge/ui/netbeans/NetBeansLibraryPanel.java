package org.chuck.deluge.ui.netbeans;

import java.awt.Color;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/** NetBeans-compatible Library Panel with extension stripping and double-click loading. */
public class NetBeansLibraryPanel extends javax.swing.JPanel {
  private MainViewModel viewModel;

  public NetBeansLibraryPanel() {
    initComponents();
    setupTreeListener();
    customizeTreeAppearance();
  }

  public void setViewModel(MainViewModel viewModel) {
    this.viewModel = viewModel;
    if (viewModel != null) {
      loadResources();
    }
  }

  private void customizeTreeAppearance() {
    libraryTree.setRowHeight(30);
    DefaultTreeCellRenderer renderer = new DefaultTreeCellRenderer();
    renderer.setBackgroundNonSelectionColor(new Color(0x1f, 0x1f, 0x1f));
    renderer.setTextNonSelectionColor(Color.LIGHT_GRAY);
    renderer.setTextSelectionColor(Color.WHITE);
    renderer.setBackgroundSelectionColor(new Color(0x00, 0xff, 0xcc, 0x55));
    libraryTree.setCellRenderer(renderer);
  }

  private void setupTreeListener() {
    libraryTree.addMouseListener(
        new MouseAdapter() {
          @Override
          public void mousePressed(MouseEvent e) {
            if (e.getClickCount() == 2) {
              TreePath path = libraryTree.getSelectionPath();
              if (path != null) {
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.isLeaf()) {
                  // Reconstruct path
                  StringBuilder pathBuilder = new StringBuilder();
                  // i=0 is "SD CARD", i=1 is category (KITS, etc)
                  for (int i = 1; i < path.getPathCount(); i++) {
                    pathBuilder.append("/").append(path.getPathComponent(i).toString());
                  }
                  String resourcePath = pathBuilder.toString();

                  // Fix path mapping for EXAMPLES
                  if (resourcePath.toUpperCase().startsWith("/EXAMPLES/")) {
                    resourcePath = "/examples" + resourcePath.substring(9);
                  }

                  // Restore extension if missing
                  if (!resourcePath.toLowerCase().endsWith(".xml")
                      && !resourcePath.toLowerCase().endsWith(".ck")) {
                    resourcePath += ".XML";
                  }

                  if (viewModel != null) {
                    viewModel.loadPreset(resourcePath);
                  }
                }
              }
            }
          }
        });
  }

  private void loadResources() {
    DefaultMutableTreeNode root = new DefaultMutableTreeNode("SD CARD");
    addResourcesToTree(root, "KITS", "/KITS");
    addResourcesToTree(root, "SYNTHS", "/SYNTHS");
    addResourcesToTree(root, "SONGS", "/SONGS");
    addResourcesToTree(root, "EXAMPLES", "/examples");
    libraryTree.setModel(new DefaultTreeModel(root));
  }

  private void addResourcesToTree(DefaultMutableTreeNode root, String label, String internalDir) {
    DefaultMutableTreeNode folder = new DefaultMutableTreeNode(label);
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
          buildDirectoryTree(folder, path);
        }
      }
    } catch (Exception e) {
      System.err.println(
          "NetBeansLibraryPanel: Failed to scan resources for " + label + ": " + e.getMessage());
    }
  }

  private void buildDirectoryTree(DefaultMutableTreeNode node, java.nio.file.Path currentPath) {
    try (java.util.stream.Stream<java.nio.file.Path> stream =
        java.nio.file.Files.list(currentPath)) {
      stream
          .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString().toUpperCase()))
          .forEach(
              p -> {
                if (java.nio.file.Files.isDirectory(p)) {
                  DefaultMutableTreeNode dirNode =
                      new DefaultMutableTreeNode(p.getFileName().toString());
                  node.add(dirNode);
                  buildDirectoryTree(dirNode, p);
                } else {
                  String fn = p.getFileName().toString();
                  String fnUpper = fn.toUpperCase();
                  if (fnUpper.endsWith(".XML") || fnUpper.endsWith(".CK")) {
                    int dotIdx = fn.lastIndexOf('.');
                    String displayName = (dotIdx != -1) ? fn.substring(0, dotIdx) : fn;
                    node.add(new DefaultMutableTreeNode(displayName));
                  }
                }
              });
    } catch (Exception ex) {
    }
  }

  @SuppressWarnings("unchecked")
  // <editor-fold defaultstate="collapsed" desc="Generated Code">//GEN-BEGIN:initComponents
  private void initComponents() {

    shuffleBtn = new javax.swing.JButton();
    treeScrollPane = new javax.swing.JScrollPane();
    libraryTree = new javax.swing.JTree();

    setBackground(new java.awt.Color(31, 31, 31));

    shuffleBtn.setBackground(new java.awt.Color(60, 63, 65));
    shuffleBtn.setForeground(new java.awt.Color(200, 200, 200));
    shuffleBtn.setText("🎲 SHUFFLE DRUM KIT");

    libraryTree.setBackground(new java.awt.Color(31, 31, 31));
    libraryTree.setForeground(new java.awt.Color(192, 192, 192));
    javax.swing.tree.DefaultMutableTreeNode treeNode1 =
        new javax.swing.tree.DefaultMutableTreeNode("SD CARD");
    libraryTree.setModel(new javax.swing.tree.DefaultTreeModel(treeNode1));
    treeScrollPane.setViewportView(libraryTree);

    javax.swing.GroupLayout layout = new javax.swing.GroupLayout(this);
    this.setLayout(layout);
    layout.setHorizontalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addComponent(shuffleBtn, javax.swing.GroupLayout.DEFAULT_SIZE, 250, Short.MAX_VALUE)
            .addComponent(treeScrollPane));
    layout.setVerticalGroup(
        layout
            .createParallelGroup(javax.swing.GroupLayout.Alignment.LEADING)
            .addGroup(
                layout
                    .createSequentialGroup()
                    .addComponent(
                        shuffleBtn,
                        javax.swing.GroupLayout.PREFERRED_SIZE,
                        35,
                        javax.swing.GroupLayout.PREFERRED_SIZE)
                    .addPreferredGap(javax.swing.LayoutStyle.ComponentPlacement.RELATED)
                    .addComponent(
                        treeScrollPane,
                        javax.swing.GroupLayout.DEFAULT_SIZE,
                        400,
                        Short.MAX_VALUE)));
  } // </editor-fold>//GEN-END:initComponents

  // Variables declaration - do not modify//GEN-BEGIN:variables
  private javax.swing.JTree libraryTree;
  private javax.swing.JButton shuffleBtn;
  private javax.swing.JScrollPane treeScrollPane;
  // End of variables declaration//GEN-END:variables
}
