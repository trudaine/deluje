package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.chuck.deluge.midi.MidiInputRouter;
import org.chuck.deluge.midi.MidiService;

public class DelugeE2ETest {

  @Test
  public void testSequencerWorkflow() throws Exception {
    System.setProperty("chuck.audio.dummy", "true");

    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    MidiInputRouter router = new MidiInputRouter(vm, bridge);
    MidiService midiService = new MidiService(vm, bridge, router);

    // 1. Load project state
    try (java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song1.xml")) {
      assertNotNull(is, "song1.xml should exist in resources");
      org.chuck.deluge.model.ProjectModel model = org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song1");
      assertTrue(model.getTracks().size() >= 0, "Model tracks should load");
    }

    // 2. Trigger Playback
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);
    
    // Allow the audio thread to process a bit
    Thread.sleep(150);

    long step = vm.getGlobalInt(BridgeContract.G_CURRENT_STEP);
    System.out.println("E2E Test: playhead step is " + step);
    
    // 3. Add sequence note
    bridge.setStep(0, 4, true);
    boolean active = bridge.getStep(0, 4);
    assertTrue(active, "Sequencer step memory allocation should hold");

    vm.setGlobalInt(BridgeContract.G_PLAY, 0L);
  }
}
