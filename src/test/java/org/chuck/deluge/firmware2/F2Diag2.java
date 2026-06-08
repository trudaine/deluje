package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.engine.*;
import org.chuck.deluge.firmware.model.*;
import org.chuck.deluge.model.*;
import org.junit.jupiter.api.Test;

public class F2Diag2 {
  @Test
  public void diag2() {
    SynthTrackModel m = new SynthTrackModel("t");
    m.setOsc1Type("SINE");
    m.setOsc2Type("NONE");
    m.setLpfFreq(20000f);
    m.setVolume(1.0f);
    m.addClip(new ClipModel("c", 8, 16));
    ProjectModel p = new ProjectModel();
    p.addTrack(m);
    FirmwareSound s =
        (FirmwareSound) ((InstrumentClip) FirmwareFactory.createSong(p).clips.get(0)).sound;
    s.useFirmware2 = true;
    s.triggerNote(69, 100);
    var v = s.fw2Sound.voices.get(0);
    v.paramFinalValues[0] = Functions.ONE_Q31 >> 1;
    v.paramFinalValues[2] = Functions.ONE_Q31 >> 1;
    v.paramFinalValues[24] = Functions.ONE_Q31;
    v.paramFinalValues[33] = 8388608;
    v.paramFinalValues[37] = 0;
    v.paramFinalValues[13] = Functions.ONE_Q31;
    v.paramFinalValues[41] = 0;
    v.paramFinalValues[23] = 0;
    v.paramFinalValues[25] = Functions.K_MAX_SAMPLE_VALUE;
    v.paramFinalValues[27] = Functions.K_MAX_SAMPLE_VALUE;
    v.paramFinalValues[1] = 0; // oscB off

    int[] ib = new int[256];
    boolean ok =
        v.render(
            ib,
            128,
            0,
            new Oscillator.OscType[] {Oscillator.OscType.SINE, Oscillator.OscType.SINE},
            FilterSet.FilterMode.OFF,
            FilterSet.FilterMode.OFF,
            0,
            134217728);
    System.out.printf(
        "render returned=%b peak=%d%n",
        ok, java.util.Arrays.stream(ib).map(Math::abs).max().orElse(0));
    System.out.printf(
        "env0 state=%s lastValue=%d%n", v.envelopes[0].state, v.envelopes[0].lastValue);
  }
}
