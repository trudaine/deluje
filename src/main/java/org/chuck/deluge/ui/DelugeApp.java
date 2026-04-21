package org.chuck.deluge.ui;

import java.io.InputStream;
import java.net.URL;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/**
 * Main Entry Point for the ChucK-Java Deluge Emulator.
 */
public class DelugeApp extends Application {
  private static final int SAMPLE_RATE = 44100;

  private ChuckVM vm;
  private BridgeContract bridge;
  private DelugeMainPanel mainPanel;
  private boolean engineLoaded = false;

  @Override
    public void start(Stage primaryStage) {
    System.setProperty("chuck.audio.dummy", "false"); // Use real audio

    // Configure Search Paths for SndBuf
    org.chuck.core.ChuckConfig.addSearchPath("SAMPLES");
    org.chuck.core.ChuckConfig.addSearchPath("src/main/resources");
    org.chuck.core.ChuckConfig.addSearchPath("deluge/src/main/resources");

    // 1. Initialize VM and Bridge FIRST
    vm = new ChuckVM(SAMPLE_RATE, 2);
    bridge = new BridgeContract();
    
    // 2. Register Bridge BEFORE creating UI
    bridge.register(vm);
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);

    // 3. Create UI
    mainPanel = new DelugeMainPanel(vm, bridge);

    Scene scene = new Scene(mainPanel, 1200, 800);
    
    // Robust stylesheet loading
    URL cssUrl = getClass().getResource("/org/chuck/deluge/style.css");
    if (cssUrl != null) {
        scene.getStylesheets().add(cssUrl.toExternalForm());
    } else {
        System.err.println("Warning: style.css not found.");
    }
    
    scene.setFill(Color.web("#1a1a1a"));

    // Global Key Handlers for Shortcuts
    scene.setOnKeyPressed(event -> {
        if (event.isControlDown()) {
            switch (event.getCode()) {
                case S -> mainPanel.saveProject();
                case N -> mainPanel.resetProject();
            }
        }
    });

    primaryStage.setTitle("ChucK-Java Deluge Emulator");
    primaryStage.setScene(scene);
    primaryStage.show();

    // 4. Start Engine
    loadEngine();

    // Frame update loop
    startAnimationTimer();
  }

  private synchronized void loadEngine() {
    // Spork Java DSL Engine
    org.chuck.deluge.engine.DelugeEngine engine = new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
    vm.spork(engine::shred);
    System.out.println("Deluge Engine (Java DSL) loaded and sporked.");
  }

  private void startAnimationTimer() {
    new javafx.animation.AnimationTimer() {
      @Override
      public void handle(long now) {
        if (vm != null) {
          mainPanel.updateFromVM();
        }
      }
    }.start();
  }

  @Override
  public void stop() {
    if (vm != null) {
      vm.shutdown();
    }
    // Force exit to kill any background audio threads immediately
    Thread shutdownThread =
        new Thread(
            () -> {
              try {
                Thread.sleep(500);
                Runtime.getRuntime().halt(0);
              } catch (Exception ignored) {
                Runtime.getRuntime().halt(0);
              }
            },
            "Deluge-Shutdown-Thread");
    shutdownThread.setDaemon(true);
    shutdownThread.start();
  }

  public static void main(String[] args) {
    launch(args);
  }
}
