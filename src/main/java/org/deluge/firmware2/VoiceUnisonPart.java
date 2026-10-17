package org.deluge.firmware2;

/**
 * Port of C++ struct VoiceUnisonPart (voice_unison_part.h). Represents a single detuned/panned
 * instance within a Voice.
 */
public class VoiceUnisonPart {
  public final int[] modulatorPhase = new int[2];
  public final int[] modulatorPhaseIncrement = new int[2];
  public final int[] modulatorFeedback = new int[2];
  public final VoiceSource[] sources = new VoiceSource[2];

  public VoiceUnisonPart() {
    for (int i = 0; i < sources.length; i++) {
      sources[i] = new VoiceSource();
    }
  }
}
