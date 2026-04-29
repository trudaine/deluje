package org.chuck.deluge;

public class SequencerLauncher {
  public static void main(String[] args) {
    System.out.println("Launching Deluge Workstation...");

    org.chuck.core.ChuckVM vm = new org.chuck.core.ChuckVM(44100, 2);
    org.chuck.deluge.BridgeContract bridge = new org.chuck.deluge.BridgeContract();
    bridge.register(vm);

    org.chuck.audio.ChuckAudio audio = new org.chuck.audio.ChuckAudio(vm, 1024, 2, 44100);
    vm.setAudio(audio);
    audio.start();

    // Spork the Java DSL engine
    org.chuck.deluge.engine.DelugeEngineDSL dslEngine =
        new org.chuck.deluge.engine.DelugeEngineDSL();
    vm.spork(dslEngine);

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
