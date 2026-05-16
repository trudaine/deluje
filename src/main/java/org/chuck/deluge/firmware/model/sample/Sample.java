package org.chuck.deluge.firmware.model.sample;

public class Sample {
  public String fileName;
  public float sampleRate = 44100.0f;
  public float midiNoteFromFile = 60.0f;
  public int fileLoopStartSamples = -1;
  public int fileLoopEndSamples = -1;
  public int numChannels = 1;
  public int byteDepth = 2;
  public boolean unplayable = false;

  public float[] data; // mono or interleaved stereo

  public Sample() {}

  public int getNumSamples() {
    if (data == null) return 0;
    return data.length / numChannels;
  }
}
