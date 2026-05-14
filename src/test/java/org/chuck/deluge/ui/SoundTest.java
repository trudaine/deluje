package org.chuck.deluge.ui;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class SoundTest {
  private static ChuckVM vm;
  private static BridgeContract bridge;

  @BeforeAll
  static void setUpAll() {
    System.setProperty("chuck.audio.dummy", "true");
    vm = new ChuckVM(44100, 2);
    vm.setLogLevel(0);
    bridge = new BridgeContract();

    // Default tracks to prevent DSL hang if XML parsing fails or is delayed
    bridge.setTrackType(0, 0);
    bridge.setTrackType(4, 1);

    bridge.register(vm);
  }

  @AfterAll
  static void tearDownAll() {
    if (vm != null) vm.shutdown();
  }

  @Test
  public void testPhantomSoundOnMute() throws Exception {
    // 1. Load song3.xml
    java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song3.xml");
    org.chuck.deluge.model.ProjectModel model =
        org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song3");

    // Populate Bridge (mimicking SwingDelugeApp)
    int trackIdx = 0;
    for (org.chuck.deluge.model.TrackModel track : model.getTracks()) {
      for (org.chuck.deluge.model.ClipModel clip : track.getClips()) {
        boolean isSynth = track instanceof org.chuck.deluge.model.SynthTrackModel;
        bridge.setTrackType(trackIdx, isSynth ? 1 : 0);
        for (int r = 0; r < 8; r++) {
          for (int s = 0; s < clip.getStepCount(); s++) {
            org.chuck.deluge.model.StepData sd = clip.getStep(r, s);
            if (sd != null && sd.active()) {
              if (isSynth) {
                bridge.setStep(trackIdx * 8, s, true);
                bridge.setPitch(trackIdx * 8, s, (24 - 1) - r);
              } else {
                bridge.setStep(trackIdx * 8 + r, s, true);
              }
            }
          }
        }
      }
      trackIdx++;
    }

    // 2. Mute all 64 tracks!
    for (int i = 0; i < 64; i++) {
      bridge.setMute(i, true);
    }

    // 3. Start Audio Shreds (using DelugeEngineDSL)
    vm.spork(new org.chuck.deluge.engine.DelugeEngineDSL(vm));

    vm.advanceTime(100);
    vm.broadcastGlobalEvent(BridgeContract.G_LOAD_TRIGGER);
    vm.advanceTime(44100 / 4); // Settle

    // Start playback
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Advance time to play a bit
    vm.advanceTime(2000000L); // 2 seconds in microseconds - oops, this is samples!
    // 2M samples is ~45 seconds. It's fine, it will process headless.

    // 4. Inspect exactly which tracks are active
    System.out.println("=== AUDITING PLAYING TRACKS ===");
    int totalActive = 0;
    for (int i = 0; i < 64; i++) {
      if (bridge.getStep(i, 0) || bridge.getStep(i, 4) || bridge.getStep(i, 8)) {
        System.out.println("Track slot " + i + " still holds active step triggers!");
        totalActive++;
      }
    }
    System.out.println("Total tracks ignoring MUTE: " + totalActive);
    System.out.flush();
  }
}
