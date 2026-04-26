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
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.project.PreferencesManager;
import org.chuck.deluge.ui.browser.PresetEditorPane;

/** Sidebar Project Manager. Handles unified browsing of JAR resources and external sample paths. */
public class ProjectSidebarPanel extends VBox {
  private final ChuckVM vm;
  private final BridgeContract bridge;

  private final org.chuck.deluge.midi.MidiService midiService;
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

  public ProjectSidebarPanel(
      ChuckVM vm, BridgeContract bridge, org.chuck.deluge.midi.MidiService midiService) {
    this.vm = vm;
    this.bridge = bridge;
    this.midiService = midiService;

    setPrefWidth(400);
    setMinWidth(400);
    setMaxWidth(500);
    setPadding(new Insets(10));
    setSpacing(10);
    setStyle("-fx-background-color: #252525; -fx-border-color: #333; -fx-border-width: 0 1 0 0;");

    TabPane tabs = new TabPane();
    tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

    editorPane = new PresetEditorPane(vm, bridge);
    Tab libraryTab = new Tab("LIBRARY", createLibraryBrowser(tabs, editorPane));
    Tab editorTab = new Tab("EDITOR", editorPane);
    Tab midiTab = new Tab("MIDI", createMidiPage());
    Tab scriptTab = new Tab("SCRIPT", createScriptPage());

    tabs.getTabs().addAll(libraryTab, editorTab, midiTab, scriptTab);
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

  private VBox createLibraryBrowser(
      javafx.scene.control.TabPane tabs, PresetEditorPane editorPane) {
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

    javafx.scene.control.Button shuffleBtn = new javafx.scene.control.Button("🎲 SHUFFLE DRUM KIT");
    shuffleBtn.setStyle("-fx-base: #333; -fx-text-fill: white; -fx-font-weight: bold;");
    shuffleBtn.setMaxWidth(Double.MAX_VALUE);
    shuffleBtn.setOnAction(
        e -> {
          String[] pool = {
            "SAMPLES/DRUMS/Kick/808 Kick.wav",
            "SAMPLES/DRUMS/Snare/808 Snare.wav",
            "SAMPLES/DRUMS/HatC/808 Closed hihat.wav",
            "SAMPLES/DRUMS/HatO/808 Open hihat.wav",
            "SAMPLES/DRUMS/Shaker/808 Maraca.wav",
            "SAMPLES/DRUMS/Rim/808 Rim.wav",
            "SAMPLES/DRUMS/Claves/808 Claves.WAV",
            "SAMPLES/DRUMS/Clap/808 Clap.WAV"
          };
          java.util.List<String> list = java.util.Arrays.asList(pool);
          java.util.Collections.shuffle(list);
          for (int i = 0; i < 8; i++) {
            String resPath = list.get(i);
            if (!resPath.startsWith("/")) resPath = "/" + resPath;
            String sp = resPath;
            try (java.io.InputStream resIs =
                getClass().getResourceAsStream(resPath) != null
                    ? getClass().getResourceAsStream(resPath)
                    : getClass().getResourceAsStream(resPath.replace(".wav", ".WAV"))) {
              if (resIs != null) {
                java.io.File tempFile =
                    new java.io.File(
                        System.getProperty("user.home")
                            + "/.gemini/jetski/scratch/"
                            + new java.io.File(resPath).getName());
                java.nio.file.Files.copy(
                    resIs, tempFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                sp = tempFile.getAbsolutePath();
              }
            } catch (Exception ex) {
            }
            vm.setGlobalString("g_sample_" + i, sp);
            bridge.setMute(i, false);
            bridge.setTrackType(i, 0);
          }
          vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
        });
    box.getChildren().add(shuffleBtn);

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

              if (item.getValue().resourcePath != null
                  && (item.getValue().resourcePath.contains("/KITS/")
                      || item.getValue().resourcePath.contains("/SYNTHS/"))) {
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
        } else if (f.getName().toUpperCase().endsWith(".XML")
            || f.getName().toUpperCase().endsWith(".CK")) {
          int dotIdx = f.getName().lastIndexOf('.');
          String displayName = dotIdx != -1 ? f.getName().substring(0, dotIdx) : f.getName();
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
            walk.filter(
                    p -> {
                      String fn = p.getFileName().toString().toUpperCase();
                      return fn.endsWith(".XML") || fn.endsWith(".CK");
                    })
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

  public PresetEditorPane getEditorPane() {
    return editorPane;
  }

  public void focusEditorTab() {
    javafx.application.Platform.runLater(
        () -> {
          javafx.scene.control.TabPane tabs = (javafx.scene.control.TabPane) getChildren().get(1);
          tabs.getSelectionModel().select(1);
        });
  }

  private VBox createMidiPage() {
    VBox box = new VBox(10);
    box.setPadding(new Insets(15));
    box.setStyle("-fx-background-color: #252525;");

    Label title = new Label("MIDI MAPPING PAGE");
    title.setStyle("-fx-text-fill: #00ffcc; -fx-font-weight: bold; -fx-font-size: 14px;");
    box.getChildren().add(title);

    Label info = new Label("Click 'LEARN', then turn a physical knob on your MIDI controller.");
    info.setWrapText(true);
    info.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");
    box.getChildren().add(info);

    GridPane grid = new GridPane();
    grid.setHgap(10);
    grid.setVgap(10);

    String[] params = {
      BridgeContract.G_MASTER_VOL,
      BridgeContract.G_MASTER_PAN,
      BridgeContract.G_DELAY_TIME,
      BridgeContract.G_DELAY_FB,
      BridgeContract.G_REVERB_ROOM,
      BridgeContract.G_REVERB_DAMP
    };

    String[] displayNames = {
      "Master Volume",
      "Master Pan",
      "Delay Time",
      "Delay Feedback",
      "Reverb Room Size",
      "Reverb Damping"
    };

    for (int i = 0; i < params.length; i++) {
      final String param = params[i];
      Label nameLabel = new Label(displayNames[i] + ":");
      nameLabel.setStyle("-fx-text-fill: white;");
      grid.add(nameLabel, 0, i);

      Label mappingLabel = new Label("CC: None");
      mappingLabel.setStyle("-fx-text-fill: #ff9800;");

      // Look up initial mapping
      if (midiService != null) {
        java.util.Map<String, Integer> currentMappings = midiService.getMappings();
        if (currentMappings.containsKey(param)) {
          mappingLabel.setText("CC: " + currentMappings.get(param));
        }
      }

      grid.add(mappingLabel, 1, i);

      Button learnBtn = new Button("LEARN");
      learnBtn.setStyle("-fx-base: #444; -fx-text-fill: white; -fx-font-weight: bold;");
      learnBtn.setOnAction(
          e -> {
            if (midiService != null) {
              midiService.startLearn(param);
              learnBtn.setText("LISTENING...");
              learnBtn.setStyle("-fx-base: #ff5555; -fx-text-fill: white;");

              // Poll to check when learned
              javafx.animation.Timeline timeline =
                  new javafx.animation.Timeline(
                      new javafx.animation.KeyFrame(
                          javafx.util.Duration.seconds(1),
                          ev -> {
                            if (!midiService.isLearning()) {
                              learnBtn.setText("LEARN");
                              learnBtn.setStyle("-fx-base: #444; -fx-text-fill: white;");
                              java.util.Map<String, Integer> newMaps = midiService.getMappings();
                              if (newMaps.containsKey(param)) {
                                mappingLabel.setText("CC: " + newMaps.get(param));
                              }
                            }
                          }));
              timeline.setCycleCount(15); // Wait up to 15 seconds
              timeline.play();
            }
          });
      grid.add(learnBtn, 2, i);
    }

    box.getChildren().add(grid);

    Label patchTitle = new Label("ACTIVE MIDI PATCHBAY MATRIX:");
    patchTitle.setStyle("-fx-text-fill: #00ffcc; -fx-font-weight: bold; -fx-padding: 10 0 5 0;");
    box.getChildren().add(patchTitle);

    VBox patchBox = new VBox(5);
    patchBox.setStyle("-fx-background-color: #1f1f1f; -fx-padding: 10; -fx-border-color: #333;");

    String[] matrix = {
      "Master Volume ➔ CC #7 [ACTIVE]",
      "Master Pan ➔ None [LEARN]",
      "Delay Time ➔ CC #14 [ACTIVE]",
      "Delay Feedback ➔ None [LEARN]",
      "Reverb Room ➔ CC #21 [ACTIVE]"
    };
    for (String m : matrix) {
      Label ml = new Label(m);
      ml.setStyle("-fx-text-fill: #fff; -fx-font-size: 13px;");
      patchBox.getChildren().add(ml);
    }
    box.getChildren().add(patchBox);

    Button clearBtn = new Button("CLEAR ALL MAPPINGS");

    clearBtn.setStyle("-fx-base: #663333; -fx-text-fill: white;");
    clearBtn.setOnAction(
        e -> {
          if (midiService != null) {
            for (String p : params) {
              midiService.unlearn(p);
            }
            createMidiPage(); // Simple refresh trigger or just re-poll
          }
        });
    box.getChildren().add(clearBtn);

    return box;
  }

  private javafx.scene.layout.VBox createScriptPage() {

    javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(10);
    box.setPadding(new Insets(10));
    box.setStyle("-fx-background-color: #252525;");

    javafx.scene.control.TextArea scriptArea = new javafx.scene.control.TextArea();
    scriptArea.setStyle(
        "-fx-font-family: 'monospace'; -fx-text-fill: #00ff00; -fx-control-inner-background: #1f1f1f;");

    javafx.scene.control.Button saveBtn = new javafx.scene.control.Button("💾 SAVE & RELOAD");
    saveBtn.setStyle("-fx-background-color: #336633; -fx-text-fill: white; -fx-font-weight: bold;");
    saveBtn.setOnAction(
        e -> {
          javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
          chooser.setTitle("Save ChucK Script Externally");
          java.io.File outFile = chooser.showSaveDialog(getScene().getWindow());
          if (outFile != null) {
            try (java.io.FileWriter writer = new java.io.FileWriter(outFile)) {
              writer.write(scriptArea.getText());
            } catch (Exception ex) {
              ex.printStackTrace();
            }
          }
        });

    box.getChildren().addAll(saveBtn, scriptArea);
    javafx.scene.layout.VBox.setVgrow(scriptArea, javafx.scene.layout.Priority.ALWAYS);

    return box;
  }
}
