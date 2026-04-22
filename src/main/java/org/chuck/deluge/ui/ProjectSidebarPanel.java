package org.chuck.deluge.ui;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.stream.Stream;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.project.PreferencesManager;
import org.chuck.deluge.ui.browser.PresetEditorPane;

/** Sidebar Project Manager. Handles unified browsing of JAR resources and external sample paths. */
public class ProjectSidebarPanel extends VBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private TreeView<String> projectTree;
  private java.util.function.Consumer<Integer> onClipSelected;
  private PresetEditorPane editorPane;

  public static class LibraryItem {
    public final String name;
    public final String resourcePath;
    public final File file;
    public final boolean isDirectory;

    public LibraryItem(String name, String resourcePath, File file, boolean isDirectory) {
      this.name = name;
      this.resourcePath = resourcePath;
      this.file = file;
      this.isDirectory = isDirectory;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private java.util.function.Consumer<LibraryItem> onPresetRequest;

  public ProjectSidebarPanel(ChuckVM vm, BridgeContract bridge) {
    this.vm = vm;
    this.bridge = bridge;

    setMinWidth(250);
    setMaxWidth(300);
    setPadding(new Insets(10));
    setSpacing(10);
    setStyle("-fx-background-color: #252525; -fx-border-color: #333; -fx-border-width: 0 1 0 0;");

    Label title = new Label("PROJECT MANAGER");
    title.setStyle("-fx-text-fill: #aaa; -fx-font-weight: bold; -fx-font-size: 14;");
    getChildren().add(title);

    TabPane tabs = new TabPane();
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

    editorPane = new PresetEditorPane();
    Tab libraryTab = new Tab("LIBRARY", createLibraryBrowser(tabs, editorPane));
    Tab editorTab = new Tab("EDITOR", editorPane);

    tabs.getTabs().addAll(libraryTab, editorTab);
    getChildren().add(tabs);
    VBox.setVgrow(tabs, javafx.scene.layout.Priority.ALWAYS);
  }

  private VBox createProjectTree() {
    VBox box = new VBox(5);
    TreeItem<String> root = new TreeItem<>("Project: New Song");
    root.setExpanded(true);

    for (int i = 0; i < 8; i++) {
      TreeItem<String> track = new TreeItem<>("Track " + (i + 1));
      track.getChildren().add(new TreeItem<>("Clip 1 (Active)"));
      track.getChildren().add(new TreeItem<>("[+] Add Clip"));
      root.getChildren().add(track);
    }

    projectTree = new TreeView<>(root);
    projectTree.setStyle(
        "-fx-background-color: #252525; -fx-control-inner-background: #252525; -fx-text-fill: white;");

    projectTree.setOnMouseClicked(
        e -> {
          if (e.getClickCount() == 2) {
            TreeItem<String> selected = projectTree.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue().contains("Clip")) {
              handleClipSelection(selected);
            }
          }
        });

    box.getChildren().add(projectTree);
    VBox.setVgrow(projectTree, javafx.scene.layout.Priority.ALWAYS);
    return box;
  }

  private void handleClipSelection(TreeItem<String> item) {
    if (item.getValue().equals("[+] Add Clip")) {
      int trackIdx = projectTree.getRoot().getChildren().indexOf(item.getParent());
      int newClipId = item.getParent().getChildren().size();
      item.getParent()
          .getChildren()
          .add(item.getParent().getChildren().size() - 1, new TreeItem<>("Clip " + newClipId));
    } else {
      if (onClipSelected != null) {
        int trackIdx = projectTree.getRoot().getChildren().indexOf(item.getParent());
        onClipSelected.accept(trackIdx);
      }
    }
  }

  private VBox createLibraryBrowser(javafx.scene.control.TabPane tabs, PresetEditorPane editorPane) {
    VBox box = new VBox(5);
    TreeItem<LibraryItem> root = new TreeItem<>(new LibraryItem("SD CARD", null, null, true));
    root.setExpanded(true);

    // 1. Internal Resources from Fat Jar
    addResourcesToTree(root, "KITS", "/KITS");
    addResourcesToTree(root, "SYNTHS", "/SYNTHS");
    addResourcesToTree(root, "SONGS", "/SONGS");

    // 2. External Samples from User Preference
    String externalPath = PreferencesManager.getSamplesDir();
    if (externalPath != null && !externalPath.isEmpty()) {
      File extDir = new File(externalPath);
      if (extDir.exists()) {
        addFilesToTree(root, extDir, "EXTERNAL SAMPLES");
      }
    }

    TreeView<LibraryItem> tree = new TreeView<>(root);
    tree.setStyle("-fx-background-color: #252525; -fx-control-inner-background: #252525;");

    tree.setCellFactory(
        tv ->
            new javafx.scene.control.TreeCell<LibraryItem>() {
              @Override
              protected void updateItem(LibraryItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                  setText(null);
                  setStyle("-fx-text-fill: white;");
                } else {
                  setText(item.name);
                  setStyle("-fx-text-fill: " + (item.isDirectory ? "#aaa;" : "white;"));
                }
              }
            });

    tree.setOnMouseClicked(
        event -> {
          if (event.getClickCount() == 2) {
            TreeItem<LibraryItem> item = tree.getSelectionModel().getSelectedItem();
            if (item != null && !item.getValue().isDirectory) {
              if (onPresetRequest != null) {
                onPresetRequest.accept(item.getValue());
              }

              if (item.getValue().resourcePath != null && 
                  (item.getValue().resourcePath.contains("/KITS/") || 
                   item.getValue().resourcePath.contains("/SYNTHS/"))) {
                 editorPane.loadPreset(item.getValue().file, item.getValue().name);
                 tabs.getSelectionModel().select(1); // Select Editor Tab
              }
            }
          }
        });

    box.getChildren().add(tree);
    VBox.setVgrow(tree, javafx.scene.layout.Priority.ALWAYS);
    return box;
  }

  private void addResourcesToTree(TreeItem<LibraryItem> root, String label, String internalDir) {
    TreeItem<LibraryItem> folder = new TreeItem<>(new LibraryItem(label, null, null, true));
    root.getChildren().add(folder);

    try {
      URL url = getClass().getResource(internalDir);
      // Special handling for Fat Jar resources
      if (url == null) {
        // Try finding the jar file itself if the folder entry is missing
        String classPath = getClass().getName().replace(".", "/") + ".class";
        url = getClass().getClassLoader().getResource(classPath);
      }

      if (url != null) {
        URI uri = url.toURI();
        Path path;
        FileSystem fs = null;

        if (uri.getScheme().equals("jar")) {
          try {
            fs = FileSystems.getFileSystem(uri);
          } catch (Exception e) {
            fs = FileSystems.newFileSystem(uri, Collections.emptyMap());
          }
          path = fs.getPath(internalDir);
        } else if (uri.getScheme().equals("file")) {
          path = Paths.get(uri);
          if (!uri.toString().endsWith(internalDir)) {
            path =
                path.getParent()
                    .resolve(internalDir.startsWith("/") ? internalDir.substring(1) : internalDir);
          }
        } else {
          return;
        }

        if (Files.exists(path)) {
          try (Stream<Path> walk = Files.walk(path, 1)) {
            walk.filter(p -> p.getFileName().toString().toUpperCase().endsWith(".XML"))
                .sorted(
                    java.util.Comparator.comparing(p -> p.getFileName().toString().toUpperCase()))
                .forEach(
                    p -> {
                      String name = p.getFileName().toString();
                      String displayName = name.substring(0, name.length() - 4);
                      folder
                          .getChildren()
                          .add(
                              new TreeItem<>(
                                  new LibraryItem(
                                      displayName, internalDir + "/" + name, null, false)));
                    });
          }
        }
      }
    } catch (Exception e) {
      // No hardcoded fallback as requested
      System.err.println("Failed to scan resources for " + label + ": " + e.getMessage());
    }
  }

  private void addFilesToTree(TreeItem<LibraryItem> root, File dir, String label) {
    if (!dir.exists()) return;
    TreeItem<LibraryItem> folder = new TreeItem<>(new LibraryItem(label, null, dir, true));
    root.getChildren().add(folder);

    File[] files = dir.listFiles();
    if (files != null) {
      java.util.Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      for (File f : files) {
        if (f.getName().startsWith(".")) continue;
        if (f.isDirectory()) {
          addFilesToTree(folder, f, f.getName());
        } else if (f.getName().toUpperCase().endsWith(".XML")) {
          String displayName = f.getName().substring(0, f.getName().length() - 4);
          folder.getChildren().add(new TreeItem<>(new LibraryItem(displayName, null, f, false)));
        }
      }
    }
  }

  public void setOnPresetRequest(java.util.function.Consumer<LibraryItem> callback) {
    this.onPresetRequest = callback;
  }

  public void setOnClipSelected(java.util.function.Consumer<Integer> callback) {
    this.onClipSelected = callback;
  }

  public void refreshLibrary() {
    javafx.application.Platform.runLater(
        () -> {
          TabPane tabs = (TabPane) getChildren().get(1);
          tabs.getTabs().get(0).setContent(createLibraryBrowser(tabs, editorPane));
        });
  }

  public static java.util.List<String> getPresets(String internalDir) {
    java.util.List<String> presets = new java.util.ArrayList<>();
    try {
      URL url = ProjectSidebarPanel.class.getResource(internalDir);
      if (url != null) {
        URI uri = url.toURI();
        Path path;
        FileSystem fs = null;
        if (uri.getScheme().equals("jar")) {
          try {
            fs = FileSystems.getFileSystem(uri);
          } catch (Exception e) {
            fs = FileSystems.newFileSystem(uri, java.util.Collections.emptyMap());
          }
          path = fs.getPath(internalDir);
        } else if (uri.getScheme().equals("file")) {
          path = Paths.get(uri);
        } else {
          return presets;
        }

        if (Files.exists(path)) {
          try (java.util.stream.Stream<Path> walk = Files.walk(path, 1)) {
            walk.filter(p -> p.getFileName().toString().toUpperCase().endsWith(".XML"))
                .sorted(
                    java.util.Comparator.comparing(p -> p.getFileName().toString().toUpperCase()))
                .forEach(p -> presets.add(p.getFileName().toString()));
          }
        }
      }
    } catch (Exception e) {
      System.err.println("Failed to scan resources for " + internalDir + ": " + e.getMessage());
    }
    return presets;
  }
}
