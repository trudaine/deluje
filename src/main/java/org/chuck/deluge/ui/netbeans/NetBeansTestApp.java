package org.chuck.deluge.ui.netbeans;

import javax.swing.UIManager;
import org.chuck.audio.ChuckAudio;
import org.chuck.core.ChuckConfig;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.engine.DelugeEngineDSL;

public class NetBeansTestApp {
  public static void main(String[] args) {
    try {
      UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
    } catch (Exception e) {
    }

    // 1. Configure VM and Search Paths
    ChuckVM vm = new ChuckVM(44100, 2);
    vm.setLogLevel(Integer.getInteger("chuck.loglevel", 1));

    ChuckConfig.addSearchPath("SAMPLES");
    ChuckConfig.addSearchPath("src/main/resources");
    ChuckConfig.addSearchPath("deluge/src/main/resources");

    // 2. Initialize Bridge
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);
    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);

    // 3. Initialize Audio Engine
    boolean isDummy = Boolean.getBoolean("chuck.audio.dummy");
    if (!isDummy) {
      ChuckAudio audio = new ChuckAudio(vm, 1024, 2, 44100);
      vm.setAudio(audio);
      audio.start();
      System.out.println("TEST: Audio Engine started.");
    }

    // 4. Start Monolithic Engine DSL (As requested)
    DelugeEngineDSL engine = new DelugeEngineDSL();
    vm.spork(engine::shred);
    System.out.println("TEST: Deluge Engine DSL sporked.");

    // 5. Launch UI
    javax.swing.SwingUtilities.invokeLater(
        () -> {
          NetBeansDelugeApp app = new NetBeansDelugeApp(vm, bridge);
          app.setVisible(true);
        });
  }
}
