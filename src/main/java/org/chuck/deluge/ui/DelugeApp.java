package org.chuck.deluge.ui;

import java.io.InputStream;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.chuck.audio.ChuckAudio;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.midi.MidiInputRouter;
import org.chuck.midi.MidiMsg;
import org.rtmidijava.RtMidiFactory;
import org.rtmidijava.RtMidiIn;

/** Main entry point for the Deluge Emulator Phase 3 UI. Replaces the legacy SequencerApp. */
public class DelugeApp extends Application {
  private static final String DEFAULT_ENGINE = "/org/chuck/deluge/engine.ck";

  private ChuckVM vm;
  private ChuckAudio audio;
  private BridgeContract bridge;
  private MidiInputRouter midiRouter;
  private DelugeMainPanel mainPanel;
  private boolean engineLoaded = false;

  @Override
  public void start(Stage primaryStage) {
    initVM();
    initMIDI();

    primaryStage.setTitle("ChucK-Java Deluge Emulator");

    mainPanel = new DelugeMainPanel(vm, bridge, midiRouter);

    Scene scene = new Scene(mainPanel, 1200, 800);
    // Apply OLED-style styling
    scene.setFill(Color.BLACK);
    String css = 
        ".root { -fx-base: #1a1a1a; -fx-background: #000000; }" +
        ".label { -fx-font-family: 'Courier New'; -fx-font-weight: bold; -fx-text-fill: #00ff41; }" + // Matrix Green
        ".button { -fx-font-family: 'Courier New'; -fx-background-radius: 3; }" +
        ".combo-box { -fx-font-family: 'Courier New'; -fx-font-size: 10px; }";
    mainPanel.setStyle("-fx-font-family: 'Courier New';");

    scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
        if (event.isControlDown() && event.getCode() == javafx.scene.input.KeyCode.G) {
            boolean current = bridge.isUseJavaEngine();
            bridge.setUseJavaEngine(!current);
            System.out.println("🔄 Toggling Engine Mode: " + (bridge.isUseJavaEngine() ? "JAVA DSL" : "CLASSIC CHUCK"));
            loadEngine(true);
            event.consume();
        }
    });

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

  private void initMIDI() {
    midiRouter = new MidiInputRouter(vm, bridge);
    try {
      RtMidiIn midiIn = RtMidiFactory.createDefaultIn();
      int ports = midiIn.getPortCount();
      for (int i = 0; i < ports; i++) {
        midiIn.openPort(i, "Deluge-App-Input-" + i);
        System.out.println("[DelugeApp] Opened MIDI port: " + midiIn.getPortName(i));
      }
      midiIn.setFastCallback(
          (timestamp, message) -> {
            byte[] raw = message.toArray(ValueLayout.JAVA_BYTE);
            MidiMsg msg = new MidiMsg();
            msg.when = timestamp;
            msg.setData(raw);
            midiRouter.handleMidiMessage(msg);
          });
      System.out.println("[DelugeApp] MIDI initialized with " + ports + " ports.");
    } catch (Exception e) {
      System.err.println("[DelugeApp] MIDI failed: " + e.getMessage());
    }
  }

  private synchronized void loadEngine(boolean force) {
    if (!force && engineLoaded) return;

    vm.clear();
    engineLoaded = true;

    bridge.register(vm);
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L); // Start stopped

    if (bridge.isUseJavaEngine()) {
      System.out.println("🚀 Starting Native Java DSL Engine...");
      vm.spork((Runnable) new org.chuck.deluge.engine.DelugeEngineDSL());
      return;
    }

    System.out.println("🎸 Starting Classic ChucK Engine...");
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
