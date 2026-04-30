package org.chuck.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.chuck.core.ChuckVM;
import org.chuck.deluge.BridgeContract;
import org.junit.jupiter.api.Test;

public class MuteTest {
  @Test
  public void testSong3LoadedSteps() throws Exception {
    ChuckVM vm = new ChuckVM(44100, 2);
    BridgeContract bridge = new BridgeContract();
    bridge.register(vm);

    java.io.InputStream is = getClass().getResourceAsStream("/SONGS/song3.xml");
    org.chuck.deluge.model.ProjectModel model =
        org.chuck.deluge.xml.DelugeXmlParser.parseSong(is, "song3");

    assertNotNull(model);
    System.out.println("Test tracks parsed: " + model.getTracks().size());

    int trackIdx = 0;
    for (org.chuck.deluge.model.TrackModel track : model.getTracks()) {
      for (org.chuck.deluge.model.ClipModel clip : track.getClips()) {
        for (int r = 0; r < 8; r++) {
          for (int s = 0; s < clip.getStepCount(); s++) {
            org.chuck.deluge.model.StepData sd = clip.getStep(r, s);
            if (sd != null && sd.active()) {
              bridge.setStep(trackIdx * 8 + r, s, true);
              System.out.println(
                  "Active step placed in Audio Track slot: " + (trackIdx * 8 + r) + " Step: " + s);
            }
          }
        }
      }
      trackIdx++;
    }

    int activeTracksFound = 0;
    for (int i = 0; i < 64; i++) {
      boolean trackHasSteps = false;
      for (int s = 0; s < BridgeContract.STEPS; s++) {
        if (bridge.getStep(i, s)) {
          trackHasSteps = true;
          break;
        }
      }
      if (trackHasSteps) {
        activeTracksFound++;
      }
    }
    System.out.println("Total audio tracks populated with steps: " + activeTracksFound);
  }
}
