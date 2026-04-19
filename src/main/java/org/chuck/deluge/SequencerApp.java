package org.chuck.deluge;

import java.io.File;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.chuck.audio.ChuckAudio;
import org.chuck.core.ChuckVM;

public class SequencerApp extends Application {
  private static final String DEFAULT_ENGINE = "/org/chuck/deluge/engine.ck";
  private ChuckVM vm;
  private ChuckAudio audio;
  private BridgeContract bridge;
  private SequencerPanel sequencerPanel;
  private File selectedCkFile;
  private Label fileLabel;
  private boolean engineLoaded = false;

  private synchronized void loadEngine(boolean force) {
    if (!force && engineLoaded) return;

    vm.clear();
    engineLoaded = true;

    // Re-register all bridge globals (same Java array objects, new VM scope after clear)
    bridge.register(vm);
    // Signal engine to start playing
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    if (selectedCkFile != null) {
      vm.add(selectedCkFile.getAbsolutePath());
    } else {
      // Load from classpath resource
      try (java.io.InputStream is = SequencerApp.class.getResourceAsStream(DEFAULT_ENGINE)) {
        if (is != null) {
          String code = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
          vm.run(code, "engine.ck");
        } else {
          System.err.println("Could not find bundled engine resource: " + DEFAULT_ENGINE);
        }
      } catch (java.io.IOException ex) {
        ex.printStackTrace();
      }
    }
  }

  @Override
  public void start(Stage primaryStage) {
    initVM();

    primaryStage.setTitle("ChucK-Java Sequencer Standalone");

    BorderPane root = new BorderPane();
    root.setPadding(new Insets(10));

    // Center: The Sequencer Panel
    sequencerPanel = new SequencerPanel(vm, bridge);
    root.setCenter(sequencerPanel);

    // Initial engine load - now safe because sequencerPanel exists
    new Thread(() -> loadEngine(false)).start();

    // Bottom Area: Controls + File Selection
    VBox bottomArea = new VBox(10);
    bottomArea.setPadding(new Insets(15, 5, 5, 5));
    bottomArea.setAlignment(Pos.CENTER);

    // Main Controls Row
    HBox mainControls = new HBox(10);
    mainControls.setAlignment(Pos.CENTER);

    Button playBtn = new Button("▶ Play");
    playBtn.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white; -fx-font-weight: bold;");
    Button stopBtn = new Button("■ Stop");
    stopBtn.setStyle("-fx-background-color: #c62828; -fx-text-fill: white; -fx-font-weight: bold;");

    playBtn.setOnAction(
        e -> {
          playBtn.setDisable(true);
          new Thread(
                  () -> {
                    loadEngine(true);
                    Platform.runLater(() -> playBtn.setDisable(false));
                  })
              .start();
        });

    stopBtn.setOnAction(
        e -> {
          vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
          vm.setGlobalInt(BridgeContract.G_CURRENT_STEP, -1L);
        });

    Button randomBtn = new Button("🎲 Randomize");
    randomBtn.setOnAction(e -> sequencerPanel.randomizeGrid());

    Button clearBtn = new Button("🗑 Clear");
    clearBtn.setOnAction(e -> sequencerPanel.clearGrid());

    Button saveBtn = new Button("💾 Save...");
    saveBtn.setOnAction(e -> sequencerPanel.savePattern());

    Button loadBtn = new Button("📂 Load...");
    loadBtn.setOnAction(e -> sequencerPanel.loadPattern());

    Button quitBtn = new Button("❌ Quit");
    quitBtn.setOnAction(e -> shutdown());

    mainControls
        .getChildren()
        .addAll(
            playBtn,
            stopBtn,
            new Separator(Orientation.VERTICAL),
            randomBtn,
            clearBtn,
            new Separator(Orientation.VERTICAL),
            saveBtn,
            loadBtn,
            new Separator(Orientation.VERTICAL),
            quitBtn);

    // File Selection Row
    HBox fileControls = new HBox(10);
    fileControls.setAlignment(Pos.CENTER);

    Button selectFileBtn = new Button("Select custom .ck Engine...");
    fileLabel = new Label("Using: " + DEFAULT_ENGINE);
    fileLabel.setStyle("-fx-text-fill: #888; -fx-font-style: italic; -fx-font-size: 11;");

    selectFileBtn.setOnAction(
        e -> {
          FileChooser fileChooser = new FileChooser();
          fileChooser
              .getExtensionFilters()
              .add(new FileChooser.ExtensionFilter("ChucK Files", "*.ck"));
          File file = fileChooser.showOpenDialog(primaryStage);
          if (file != null) {
            selectedCkFile = file;
            fileLabel.setText("Custom Engine: " + file.getName());
          }
        });

    fileControls.getChildren().addAll(selectFileBtn, fileLabel);

    bottomArea.getChildren().addAll(new Separator(), mainControls, fileControls);
    root.setBottom(bottomArea);

    Scene scene = new Scene(root, 1000, 520);
    primaryStage.setScene(scene);

    // Explicit close handler
    primaryStage.setOnCloseRequest(
        e -> {
          shutdown();
        });

    primaryStage.show();

    startAnimationTimer();
  }

  private void initVM() {
    vm = new ChuckVM(44100, 2);
    bridge = new BridgeContract();
    bridge.register(vm);
    int lv = Integer.getInteger("chuck.loglevel", 1);
    vm.setLogLevel(lv);
    // Add /examples to search paths for internal resource discovery
    org.chuck.core.ChuckConfig.addSearchPath("/examples");
    audio = new ChuckAudio(vm, 1024, 2, 44100); // Larger buffer for Mac
    vm.setAudio(audio);
    audio.start();
  }

  private void startAnimationTimer() {
    new javafx.animation.AnimationTimer() {
      @Override
      public void handle(long now) {
        if (sequencerPanel != null) {
          int step = (int) vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
          sequencerPanel.setStep(step);
          sequencerPanel.syncUIFromVM();
        }
      }
    }.start();
  }

  private void shutdown() {
    System.out.println("[SequencerApp] Shutdown initiated...");

    // Run cleanup in a background thread
    Thread shutdownThread =
        new Thread(
            () -> {
              try {
                System.out.println("[SequencerApp] Stopping audio...");
                if (audio != null) audio.stop();
                System.out.println("[SequencerApp] Shutting down VM...");
                if (vm != null) vm.shutdown();
                System.out.println("[SequencerApp] Cleanup complete. Exiting.");
              } catch (Exception e) {
                e.printStackTrace();
              } finally {
                Runtime.getRuntime().halt(0);
              }
            },
            "Sequencer-Shutdown-Thread");

    shutdownThread.setDaemon(true);
    shutdownThread.start();
  }

  @Override
  public void stop() {
    shutdown();
  }

  public static void main(String[] args) {
    launch(args);
  }
}
