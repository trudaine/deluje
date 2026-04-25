package org.chuck.deluge.ui.swing2;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;

public class Swing2Launcher {
  public static void main(String[] args) {
    System.out.println("Launching Swing2 MVC Workstation...");
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
    vm.setAudio(audio);
    audio.start();

    org.chuck.deluge.engine.DelugeEngine engine =
        new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
    vm.spork(engine::shred);

    java.awt.EventQueue.invokeLater(
        () -> {
          Swing2DelugeApp app = new Swing2DelugeApp(vm, bridge, null);
          app.setVisible(true);
        });
  }
}
