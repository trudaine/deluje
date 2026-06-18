package org.deluge.firmware.model.sample;

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

  private int[] monoIntData; // lazily built for the time-stretcher (Q31 mono)

  /**
   * Mono, Q31-scaled int view of the sample data, built lazily — the TimeStretcher reads a single
   * int[] channel. Stereo is downmixed (L+R)/2.
   */
  public int[] getMonoIntData() {
    if (monoIntData == null && data != null) {
      int n = getNumSamples();
      int[] out = new int[n];
      if (numChannels == 1) {
        for (int i = 0; i < n; i++) out[i] = (int) (data[i] * 2147483647.0f);
      } else {
        for (int i = 0; i < n; i++) {
          float m = (data[i * numChannels] + data[i * numChannels + 1]) * 0.5f;
          out[i] = (int) (m * 2147483647.0f);
        }
      }
      monoIntData = out;
    }
    return monoIntData;
  }
}
