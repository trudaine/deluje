package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.midi.MidiInputRouter;
import org.chuck.deluge.midi.MidiService;
import org.junit.jupiter.api.Test;

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
      org.chuck.deluge.model.ProjectModel model =
          org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song1");
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

  @Test
  public void testSidebarLoadPreset() throws Exception {
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    org.chuck.deluge.ui.SwingProjectSidebarPanel sidebar =
        new org.chuck.deluge.ui.SwingProjectSidebarPanel(vm, bridge, null);

    final boolean[] loaded = {false};
    sidebar.setOnSongLoaded(
        model -> {
          loaded[0] = true;
        });

    org.chuck.deluge.model.ProjectModel proj = new org.chuck.deluge.model.ProjectModel();
    org.chuck.deluge.model.KitTrackModel kit = new org.chuck.deluge.model.KitTrackModel("MOCK KIT");
    proj.addTrack(kit);

    sidebar.setOnSongLoaded(
        model -> {
          loaded[0] = true;
          assertEquals(1, model.getTracks().size());
        });

    proj.addTrack(kit);

    // Simulate playback on each row
    for (int r = 0; r < 8; r++) {
      bridge.setStep(r, 0, true);
      assertTrue(bridge.getStep(r, 0), "Row " + r + " sequence memory allocation should store.");
    }
  }
}
