package org.deluge.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Encapsulates the raw hardware-faithful Q31 parameter knob states and overrides parsed from song
 * clips or presets to preserve the exact firmware-matching ranges.
 */
public class RawKnobConfig {
  private final int[] lfoRateKnobQ31 = {0, 0, 0, 0};
  private final int[] envAttackKnobQ31 = {0, 0, 0, 0};
  private final int[] envDecayKnobQ31 = {0, 0, 0, 0};
  private final int[] envSustainKnobQ31 = {0, 0, 0, 0};
  private final int[] envReleaseKnobQ31 = {0, 0, 0, 0};
  private final boolean[] envKnobSet = {false, false, false, false};
  private final Map<Integer, Integer> rawParamKnobs = new HashMap<>();

  public int getLfoRateKnobQ31(int index) {
    return lfoRateKnobQ31[index];
  }

  public void setLfoRateKnobQ31(int index, int v) {
    lfoRateKnobQ31[index] = v;
  }

  public boolean isEnvKnobSet(int index) {
    return envKnobSet[index];
  }

  public int getEnvAttackKnobQ31(int index) {
    return envAttackKnobQ31[index];
  }

  public int getEnvDecayKnobQ31(int index) {
    return envDecayKnobQ31[index];
  }

  public int getEnvSustainKnobQ31(int index) {
    return envSustainKnobQ31[index];
  }

  public int getEnvReleaseKnobQ31(int index) {
    return envReleaseKnobQ31[index];
  }

  public void setEnvRateKnobsQ31(int index, int attack, int decay, int release) {
    envAttackKnobQ31[index] = attack;
    envDecayKnobQ31[index] = decay;
    envSustainKnobQ31[index] = 0;
    envReleaseKnobQ31[index] = release;
    envKnobSet[index] = true;
  }

  public void setEnvKnobsQ31(int index, int attack, int decay, int sustain, int release) {
    envAttackKnobQ31[index] = attack;
    envDecayKnobQ31[index] = decay;
    envSustainKnobQ31[index] = sustain;
    envReleaseKnobQ31[index] = release;
    envKnobSet[index] = true;
  }

  public void setRawParamKnob(int paramId, int q31) {
    rawParamKnobs.put(paramId, q31);
  }

  public Map<Integer, Integer> getRawParamKnobs() {
    return rawParamKnobs;
  }

  public void copyFrom(RawKnobConfig other) {
    System.arraycopy(other.lfoRateKnobQ31, 0, this.lfoRateKnobQ31, 0, 4);
    System.arraycopy(other.envAttackKnobQ31, 0, this.envAttackKnobQ31, 0, 4);
    System.arraycopy(other.envDecayKnobQ31, 0, this.envDecayKnobQ31, 0, 4);
    System.arraycopy(other.envSustainKnobQ31, 0, this.envSustainKnobQ31, 0, 4);
    System.arraycopy(other.envReleaseKnobQ31, 0, this.envReleaseKnobQ31, 0, 4);
    System.arraycopy(other.envKnobSet, 0, this.envKnobSet, 0, 4);
    this.rawParamKnobs.clear();
    this.rawParamKnobs.putAll(other.rawParamKnobs);
  }
}
