package org.chuck.deluge.firmware.model.sample;

public class Sample {
    public String fileName;
    public float sampleRate;
    public float midiNoteFromFile = -1.0f;
    public int fileLoopStartSamples = -1;
    public int fileLoopEndSamples = -1;
    public int numChannels;
    public int byteDepth;

    public float[] data; // mono or interleaved stereo

    public Sample() {}
}
