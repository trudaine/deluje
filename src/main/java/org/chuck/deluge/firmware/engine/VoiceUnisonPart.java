package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.model.sample.Sample;

public class VoiceUnisonPart {
  public final int[] modulatorPhase = new int[2];
  public final int[] modulatorPhaseIncrement = new int[2];
  public final int[] modulatorFeedback = new int[2];
  public final VoiceUnisonPartSource[] sources = new VoiceUnisonPartSource[4];

  public VoiceUnisonPart() {
    for (int i = 0; i < sources.length; i++) {
      sources[i] = new VoiceUnisonPartSource();
    }
  }

  public void reset() {
    reset(0, 0, -1, -1);
  }

  public void reset(int osc1InitialPhase, int osc2InitialPhase) {
    reset(osc1InitialPhase, osc2InitialPhase, -1, -1);
  }

  public void reset(
      int osc1InitialPhase, int osc2InitialPhase, int mod1InitialPhase, int mod2InitialPhase) {
    for (int i = 0; i < 2; i++) {
      modulatorPhase[i] = 0;
      modulatorPhaseIncrement[i] = 0;
      modulatorFeedback[i] = 0;
    }
    sources[0].reset(osc1InitialPhase);
    sources[1].reset(osc2InitialPhase);
    sources[2].reset(mod1InitialPhase);
    sources[3].reset(mod2InitialPhase);
  }

  public void noteOn(Sample sample, int samplesLate) {
    for (VoiceUnisonPartSource s : sources) {
      s.noteOn(
          sample, new org.chuck.deluge.firmware.model.sample.SampleVoiceSettings(), samplesLate);
    }
  }
}
