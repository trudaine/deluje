package org.chuck.deluge.ui;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.junit.jupiter.api.Test;

public class SoundTest {
  @Test
  public void testPhantomSoundOnMute() throws Exception {
    ChuckVM vm = new ChuckVM(44100, 2);
    vm.setLogLevel(0);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    // 1. Load song3.xml
    java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song3.xml");
    org.chuck.deluge.model.ProjectModel model =
        org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song3");

    // Populate Bridge (mimicking SwingDelugeApp)
    int trackIdx = 0;
    for (org.chuck.deluge.model.TrackModel track : model.getTracks()) {
      for (org.chuck.deluge.model.ClipModel clip : track.getClips()) {
        boolean isSynth = track instanceof org.chuck.deluge.model.SynthTrackModel;
        for (int r = 0; r < 8; r++) {
          for (int s = 0; s < 16; s++) {
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

    // 3. Start Audio Shreds (mimicking DelugeEngine)
    org.chuck.deluge.engine.DelugeEngine engine =
        new org.chuck.deluge.engine.DelugeEngine(vm, bridge);
    vm.spork(engine::shred);

    // Start playback
    vm.setGlobalInt(BridgeContract.G_PLAY, 1L);

    // Advance time to play a bit
    vm.advanceTime(2000000L); // 2 seconds in microseconds!

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

    Thread.sleep(2000);
  }
}
