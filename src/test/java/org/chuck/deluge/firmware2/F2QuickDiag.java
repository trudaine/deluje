package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;
import org.chuck.deluge.firmware.engine.*;
import org.chuck.deluge.firmware.model.*;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.model.*;
import org.junit.jupiter.api.Test;

public class F2QuickDiag {
  @Test
  public void diag() {
    SynthTrackModel m = new SynthTrackModel("t");
    m.setOsc1Type("SINE"); m.setOsc2Type("NONE");
    m.setLpfFreq(20000f); m.setVolume(1.0f);
    m.addClip(new ClipModel("c",8,16));
    ProjectModel p = new ProjectModel(); p.addTrack(m);
    FirmwareSound s = (FirmwareSound)((InstrumentClip)FirmwareFactory.createSong(p).clips.get(0)).sound;
    s.useFirmware2 = true;
    s.triggerNote(69, 100);
    
    // Check what came back
    System.out.println("fw2Voices=" + s.fw2Voices.size());
    if (!s.fw2Voices.isEmpty()) {
      var v = s.fw2Voices.get(0);
      System.out.println("active=" + v.active + " note=" + v.note);
      // Manually set params like the working test
      v.paramFinalValues[0] = Functions.ONE_Q31 >> 1; // OSC_A
      v.paramFinalValues[2] = Functions.ONE_Q31 >> 1; // VOL
      v.paramFinalValues[24] = Functions.ONE_Q31; // LPF open
      v.paramFinalValues[33] = 8388608; // fast attack
      v.paramFinalValues[37] = 0; // no decay
      v.paramFinalValues[13] = Functions.ONE_Q31; // sustain
      v.paramFinalValues[41] = 0; // no release
      v.paramFinalValues[23] = 0; // pan center
      v.paramFinalValues[25] = Functions.K_MAX_SAMPLE_VALUE; // pitch neutral
      v.paramFinalValues[27] = Functions.K_MAX_SAMPLE_VALUE; // oscB pitch neutral
    }
    
    StereoSample[] buf = new StereoSample[128];
    for (int i=0; i<128; i++) buf[i] = new StereoSample();
    s.renderOutput(buf, 128, null);
    
    double sum=0; long peak=0;
    for (int i=0; i<128; i++) {
      sum += (double)buf[i].l*buf[i].l;
      long a = Math.abs((long)buf[i].l);
      if (a>peak) peak=a;
    }
    System.out.printf("peak=%d RMS=%.4f%n", peak, Math.sqrt(sum/128)/2147483648.0);
  }
}
