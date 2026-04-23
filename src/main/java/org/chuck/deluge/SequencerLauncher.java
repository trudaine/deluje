package org.chuck.deluge;

public class SequencerLauncher {
  public static void main(String[] args) {
    boolean useSwing = false;
    for (String arg : args) {
      if ("--swing".equals(arg)) {
        useSwing = true;
        break;
      }
    }

    if (useSwing) {
      System.out.println("Launching Deluge Workstation in Pure Swing edition...");
      // Set up minimal VM infrastructure
      org.chuck.core.ChuckVM vm = new org.chuck.core.ChuckVM(44100, 2);
      org.chuck.deluge.BridgeContract bridge = new org.chuck.deluge.BridgeContract();
      bridge.register(vm);

      org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
      vm.setAudio(audio);
      audio.start();

      // Spork Java DSL Engine Orchestrator
      org.chuck.deluge.engine.DelugeEngine engine = new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
      vm.spork(engine::shred);

      java.awt.EventQueue.invokeLater(() -> {
        org.chuck.deluge.ui.SwingDelugeApp app = new org.chuck.deluge.ui.SwingDelugeApp(vm, bridge);
        app.setVisible(true);
      });
    } else {
      org.chuck.deluge.ui.DelugeApp.main(args);
    }
  }
}

