package org.chuck.deluge.engine.dsp;

/** Pure Java Schroeder Reverb implementation. */
public class NativeReverb {
  private final float sampleRate;
  private final CombFilter[] combsL = new CombFilter[8];
  private final CombFilter[] combsR = new CombFilter[8];
  private final AllPassFilter[] allpassesL = new AllPassFilter[4];
  private final AllPassFilter[] allpassesR = new AllPassFilter[4];

  public NativeReverb(float sampleRate) {
    this.sampleRate = sampleRate;
    int[] combTaps = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    int[] apTaps = {556, 441, 341, 225};

    for (int i = 0; i < 8; i++) {
      combsL[i] = new CombFilter(combTaps[i]);
      combsR[i] = new CombFilter(combTaps[i] + 23); // offset for stereo width
    }
    for (int i = 0; i < 4; i++) {
      allpassesL[i] = new AllPassFilter(apTaps[i]);
      allpassesR[i] = new AllPassFilter(apTaps[i] + 23);
    }
  }

  public float[] tick(float inL, float inR) {
    float mono = (inL + inR) * 0.5f;
    float outL = 0, outR = 0;

    for (int i = 0; i < 8; i++) {
      outL += combsL[i].tick(mono);
      outR += combsR[i].tick(mono);
    }
    for (int i = 0; i < 4; i++) {
      outL = allpassesL[i].tick(outL);
      outR = allpassesR[i].tick(outR);
    }

    return new float[] {outL * 0.1f, outR * 0.1f};
  }

  private static class CombFilter {
    private final float[] buffer;
    private int idx = 0;
    private double feedback = 0.84;
    private float lastOut = 0;

    CombFilter(int size) {
      buffer = new float[size];
    }

    float tick(float in) {
      float out = buffer[idx];
      lastOut = out * 0.8f + lastOut * 0.2f; // damp
      buffer[idx] = in + lastOut * (float) feedback;
      idx = (idx + 1) % buffer.length;
      return out;
    }
  }

  private static class AllPassFilter {
    private final float[] buffer;
    private int idx = 0;

    AllPassFilter(int size) {
      buffer = new float[size];
    }

    float tick(float in) {
      float bufIn = buffer[idx];
      float out = -in + bufIn;
      buffer[idx] = in + bufIn * 0.5f;
      idx = (idx + 1) % buffer.length;
      return out;
    }
  }
}
