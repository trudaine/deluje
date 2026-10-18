package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Param;
import org.junit.jupiter.api.Test;

/**
 * Synth clip automation must advance during playback (C clip.cpp processCurrentPos →
 * paramManager.processCurrentPos, with the clip's paramManager being the one the Sound renders).
 */
class SynthClipAutomationPlaysTest {

  @Test
  void stepAutomationAdvancesTheSoundsParam() {
    ProjectModel p = new ProjectModel();
    SynthTrackModel t = new SynthTrackModel("S");
    ClipModel c = new ClipModel("c", 1, 16);
    t.addClip(c);
    p.addTrack(t);
    for (int s = 0; s < 16; s++) {
      c.setAutomation("lpfFrequency", s, s < 8 ? 0.1f : 0.9f);
    }
    FirmwareFactory.createSong(p);
    FirmwareSound fs = (FirmwareSound) c.getSound();
    var auto = fs.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertNotNull(auto);

    c.setPos(0);
    c.processCurrentPos(0);
    assertEquals((int) (0.1f * 2147483647.0), auto.currentValue, 4096);

    for (int tick = 0; tick < 24 * 9; tick++) { // into step 9
      c.incrementPos(1);
      c.processCurrentPos(1);
    }
    assertEquals((int) (0.9f * 2147483647.0), auto.currentValue, 4096);
  }
}
