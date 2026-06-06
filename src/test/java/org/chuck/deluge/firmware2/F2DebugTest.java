package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;
import org.chuck.deluge.firmware.engine.*;
import org.chuck.deluge.firmware.model.*;
import org.chuck.deluge.model.*;
import org.junit.jupiter.api.Test;

public class F2DebugTest {
  @Test
  public void debugTrigger() {
    SynthTrackModel m = new SynthTrackModel("t");
    m.setOsc1Type("SINE"); m.setOsc2Type("NONE");
    m.setLpfFreq(20000f); m.setVolume(1.0f);
    m.addClip(new ClipModel("c",8,16));
    ProjectModel p = new ProjectModel(); p.addTrack(m);
    FirmwareSound s = (FirmwareSound)((InstrumentClip)FirmwareFactory.createSong(p).clips.get(0)).sound;
    s.useFirmware2 = true;
    s.triggerNote(69, 100);
    System.out.println("fw2Voices=" + s.fw2Voices.size() + " voices=" + s.voices.size());
    assertTrue(s.fw2Voices.size() > 0, "fw2 voice should be created");
  }
}
