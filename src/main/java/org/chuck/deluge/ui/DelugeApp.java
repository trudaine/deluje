package org.chuck.deluge.ui;

import java.net.URL;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.chuck.audio.ChuckAudio;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Main Entry Point for the ChucK-Java Deluge Emulator. */
public class DelugeApp extends Application {
  private static final int SAMPLE_RATE = 44100;

  private ChuckVM vm;
  private ChuckAudio audio;
  private BridgeContract bridge;
  private DelugeMainPanel mainPanel;
  private boolean engineLoaded = false;

  @Override
  public void start(Stage primaryStage) {
    // 1. Configure VM and Bridge
    vm = new ChuckVM(SAMPLE_RATE, 2);
    int lv = Integer.getInteger("chuck.loglevel", 1);
    vm.setLogLevel(lv);

    // Configure Search Paths for SndBuf
    org.chuck.core.ChuckConfig.addSearchPath("SAMPLES");
    org.chuck.core.ChuckConfig.addSearchPath("src/main/resources");
    org.chuck.core.ChuckConfig.addSearchPath("deluge/src/main/resources");

    bridge = new BridgeContract();
    bridge.register(vm);
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);

    // 2. Initialize Audio Engine (CRITICAL: This drives the VM clock)
    boolean isDummy = Boolean.getBoolean("chuck.audio.dummy");
    if (!isDummy) {
      audio = new ChuckAudio(vm, 1024, 2, SAMPLE_RATE);
      vm.setAudio(audio);
      audio.start();
      System.out.println("Deluge Audio Engine started (SourceDataLine).");
    } else {
      System.out.println("Deluge Audio Engine in DUMMY mode.");
    }

    // 3. Create UI
    mainPanel = new DelugeMainPanel(vm, bridge, audio);

    Scene scene = new Scene(mainPanel, 1400, 800);

    // Robust stylesheet loading
    URL cssUrl = getClass().getResource("/org/chuck/deluge/style.css");
    if (cssUrl != null) {
      scene.getStylesheets().add(cssUrl.toExternalForm());
    }

    scene.setFill(Color.web("#1a1a1a"));

    // Global Key Handlers for Shortcuts
    scene.setOnKeyPressed(
        event -> {
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

    // 4. Start Engine Shreds
    loadEngine();

    // Frame update loop
    startAnimationTimer();
  }

  private synchronized void loadEngine() {
    // Spork Java DSL Engine Orchestrator
    org.chuck.deluge.engine.DelugeEngine engine =
        new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
    vm.spork(engine::shred);
    System.out.println("Deluge Distributed Shreds sporked.");
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
    if (audio != null) {
      audio.stop();
    }
    if (vm != null) {
      vm.shutdown();
    }
    // Force exit to kill any background threads immediately
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
