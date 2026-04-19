package org.chuck.deluge.ui;

import java.io.InputStream;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.chuck.audio.ChuckAudio;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

/** Main entry point for the Deluge Emulator Phase 3 UI. Replaces the legacy SequencerApp. */
public class DelugeApp extends Application {
  private static final String DEFAULT_ENGINE = "/org/chuck/deluge/engine.ck";

  private ChuckVM vm;
  private ChuckAudio audio;
  private BridgeContract bridge;
  private DelugeMainPanel mainPanel;
  private boolean engineLoaded = false;

  @Override
  public void start(Stage primaryStage) {
    initVM();

    primaryStage.setTitle("ChucK-Java Deluge Emulator");

    mainPanel = new DelugeMainPanel(vm, bridge);

    Scene scene = new Scene(mainPanel, 1200, 800);
    // Apply a dark theme base
    scene.setFill(Color.web("#1e1e1e"));
    // Add external stylesheet if needed: scene.getStylesheets().add("...");

    primaryStage.setScene(scene);

    primaryStage.setOnCloseRequest(e -> shutdown());
    primaryStage.show();

    // Start engine in background
    new Thread(() -> loadEngine(false)).start();

    startAnimationTimer();
  }

  private void initVM() {
    vm = new ChuckVM(44100, 2);
    bridge = new BridgeContract();
    bridge.register(vm);

    int lv = Integer.getInteger("chuck.loglevel", 1);
    vm.setLogLevel(lv);

    org.chuck.core.ChuckConfig.addSearchPath("/examples");

    audio = new ChuckAudio(vm, 1024, 2, 44100);
    vm.setAudio(audio);
    audio.start();
  }

  private synchronized void loadEngine(boolean force) {
    if (!force && engineLoaded) return;

    vm.clear();
    engineLoaded = true;

    bridge.register(vm);
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L); // Start stopped

    try (InputStream is = DelugeApp.class.getResourceAsStream(DEFAULT_ENGINE)) {
      if (is != null) {
        String code = new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        vm.run(code, "engine.ck");
      } else {
        System.err.println("Could not find bundled engine resource: " + DEFAULT_ENGINE);
      }
    } catch (Exception ex) {
      ex.printStackTrace();
    }
  }

  private void startAnimationTimer() {
    new javafx.animation.AnimationTimer() {
      @Override
      public void handle(long now) {
        if (mainPanel != null) {
          mainPanel.updateFromVM();
        }
      }
    }.start();
  }

  private void shutdown() {
    System.out.println("[DelugeApp] Shutdown initiated...");
    Thread shutdownThread =
        new Thread(
            () -> {
              try {
                if (audio != null) audio.stop();
                if (vm != null) vm.shutdown();
              } catch (Exception e) {
                e.printStackTrace();
              } finally {
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
