package org.chuck.deluge.firmware.engine;

public class VoiceUnisonPart {
    public final int[] modulatorPhase = new int[2]; // kNumModulators
    public final int[] modulatorPhaseIncrement = new int[2];
    public final int[] modulatorFeedback = new int[2];
    public final VoiceUnisonPartSource[] sources = new VoiceUnisonPartSource[2]; // kNumSources

    public VoiceUnisonPart() {
        for (int i = 0; i < sources.length; i++) {
            sources[i] = new VoiceUnisonPartSource();
        }
    }

    public void reset() {
        for (int i = 0; i < 2; i++) {
            modulatorPhase[i] = 0;
            modulatorPhaseIncrement[i] = 0;
            modulatorFeedback[i] = 0;
            sources[i].reset();
        }
    }
}
