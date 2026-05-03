package org.chuck.deluge;

import org.chuck.deluge.engine.DelugeEngineDSL;
import org.chuck.deluge.engine.NativeJavaSequencer;
import org.chuck.deluge.project.PreferencesManager;

public class SequencerLauncher {
  public static void main(String[] args) {
    System.out.println("Launching Deluge Workstation...");

    org.chuck.core.ChuckVM vm = new org.chuck.core.ChuckVM(44100, 2);
    org.chuck.deluge.BridgeContract bridge = new org.chuck.deluge.BridgeContract();
    bridge.register(vm);

    org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
    vm.setAudio(audio);
    audio.start();

    // Select and start the engine based on preferences
    PreferencesManager.SequencerEngine engineType = PreferencesManager.getSequencerEngine();
    System.out.println("Selected Engine: " + engineType);

    if (engineType == PreferencesManager.SequencerEngine.CHUCK) {
        // Spork the original ChucK DSL engine
        DelugeEngineDSL dslEngine = new DelugeEngineDSL();
        vm.spork(dslEngine);
    } else {
        // Start the experimental Pure Java engine
        NativeJavaSequencer.launch(bridge);
    }

    org.chuck.deluge.midi.MidiInputRouter router =
        new org.chuck.deluge.midi.MidiInputRouter(vm, bridge);
    org.chuck.deluge.midi.MidiService midiService =
        new org.chuck.deluge.midi.MidiService(vm, bridge, router);
    midiService.start();

    java.awt.EventQueue.invokeLater(
        () -> {
          org.chuck.deluge.ui.SwingDelugeApp app =
              new org.chuck.deluge.ui.SwingDelugeApp(vm, bridge, midiService);
          app.setVisible(true);
        });
  }
}
