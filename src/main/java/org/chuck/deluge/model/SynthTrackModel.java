package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Represents a synthesizer track containing full synthesis and modulation parameters. */
public class SynthTrackModel extends TrackModel {

  // Oscillators
  private String osc1Type = "SINE";
  private String osc2Type = "NONE";
  private float oscMix = 0.5f;
  private float noiseVol = 0.0f;
  private int unisonNum = 1;
  private float unisonDetune = 0.0f;

  // Filter
  private FilterMode filterMode = FilterMode.LADDER_12;
  private float lpfFreq = 20000.0f;
  private float lpfRes = 0.0f;
  private float lpfMorph = 0.0f;
  private float hpfFreq = 20.0f;
  private float hpfRes = 0.0f;
  private float hpfMorph = 0.0f;

  // Modulation arrays (per firmware correction §23)
  private final EnvelopeModel[] env = new EnvelopeModel[4];
  private final LfoModel[] lfo = new LfoModel[4];

  private ArpModel arp = ArpModel.defaultConfig();
  private float portamento = 0.0f;

  private final List<PatchCable> patchCables = new ArrayList<>();
  private final List<ModKnob> modKnobs = new ArrayList<>(16);

  // FX and EQ
  private String modFxType = "NONE";
  private float modFxRate = 0.0f;
  private float modFxDepth = 0.0f;
  private float modFxFeedback = 0.0f;
  private float delaySend = 0.0f;
  private float reverbSend = 0.0f;
  private float eqBass = 0.0f;
  private float eqTreble = 0.0f;

  // Synthesis algorithm: 0=FM, 10=Mandolin, 11=Rhodey, 12=ModalBar, 13=Moog
  private int synthAlgorithm = 0;

  public SynthTrackModel(String name) {
    super(name, TrackType.SYNTH);
    for (int i = 0; i < 4; i++) {
      env[i] = EnvelopeModel.defaultConfig();
      // LFO 0 and 1 are local (per voice), 2 and 3 are global
      lfo[i] = LfoModel.defaultConfig(i < 2);
    }
    for (int i = 0; i < 16; i++) {
      modKnobs.add(ModKnob.empty());
    }
  }

  // Getters and Setters for all fields...
  public String getOsc1Type() {
    return osc1Type;
  }

  public void setOsc1Type(String osc1Type) {
    this.osc1Type = osc1Type;
  }

  public String getOsc2Type() {
    return osc2Type;
  }

  public void setOsc2Type(String osc2Type) {
    this.osc2Type = osc2Type;
  }

  public float getOscMix() {
    return oscMix;
  }

  public void setOscMix(float oscMix) {
    this.oscMix = oscMix;
  }

  public float getNoiseVol() {
    return noiseVol;
  }

  public void setNoiseVol(float noiseVol) {
    this.noiseVol = noiseVol;
  }

  public int getUnisonNum() {
    return unisonNum;
  }

  public void setUnisonNum(int unisonNum) {
    this.unisonNum = unisonNum;
  }

  public float getUnisonDetune() {
    return unisonDetune;
  }

  public void setUnisonDetune(float unisonDetune) {
    this.unisonDetune = unisonDetune;
  }

  public FilterMode getFilterMode() {
    return filterMode;
  }

  public void setFilterMode(FilterMode filterMode) {
    this.filterMode = filterMode;
  }

  public float getLpfFreq() {
    return lpfFreq;
  }

  public void setLpfFreq(float lpfFreq) {
    this.lpfFreq = lpfFreq;
  }

  public float getLpfRes() {
    return lpfRes;
  }

  public void setLpfRes(float lpfRes) {
    this.lpfRes = lpfRes;
  }

  public float getLpfMorph() {
    return lpfMorph;
  }

  public void setLpfMorph(float lpfMorph) {
    this.lpfMorph = lpfMorph;
  }

  public float getHpfFreq() {
    return hpfFreq;
  }

  public void setHpfFreq(float hpfFreq) {
    this.hpfFreq = hpfFreq;
  }

  public float getHpfRes() {
    return hpfRes;
  }

  public void setHpfRes(float hpfRes) {
    this.hpfRes = hpfRes;
  }

  public float getHpfMorph() {
    return hpfMorph;
  }

  public void setHpfMorph(float hpfMorph) {
    this.hpfMorph = hpfMorph;
  }

  public EnvelopeModel getEnv(int index) {
    return env[index];
  }

  public void setEnv(int index, EnvelopeModel model) {
    env[index] = model;
  }

  public LfoModel getLfo(int index) {
    return lfo[index];
  }

  public void setLfo(int index, LfoModel model) {
    lfo[index] = model;
  }

  public ArpModel getArp() {
    return arp;
  }

  public void setArp(ArpModel arp) {
    this.arp = arp;
  }

  public float getPortamento() {
    return portamento;
  }

  public void setPortamento(float portamento) {
    this.portamento = portamento;
  }

  public List<PatchCable> getPatchCables() {
    return patchCables;
  }

  public void addPatchCable(PatchCable cable) {
    this.patchCables.add(cable);
  }

  public List<ModKnob> getModKnobs() {
    return modKnobs;
  }

  public void setModKnob(int index, ModKnob knob) {
    this.modKnobs.set(index, knob);
  }

  public String getModFxType() {
    return modFxType;
  }

  public void setModFxType(String modFxType) {
    this.modFxType = modFxType;
  }

  public float getModFxRate() {
    return modFxRate;
  }

  public void setModFxRate(float modFxRate) {
    this.modFxRate = modFxRate;
  }

  public float getModFxDepth() {
    return modFxDepth;
  }

  public void setModFxDepth(float modFxDepth) {
    this.modFxDepth = modFxDepth;
  }

  public float getModFxFeedback() {
    return modFxFeedback;
  }

  public void setModFxFeedback(float modFxFeedback) {
    this.modFxFeedback = modFxFeedback;
  }

  public float getDelaySend() {
    return delaySend;
  }

  public void setDelaySend(float delaySend) {
    this.delaySend = delaySend;
  }

  public float getReverbSend() {
    return reverbSend;
  }

  public void setReverbSend(float reverbSend) {
    this.reverbSend = reverbSend;
  }

  public float getEqBass() {
    return eqBass;
  }

  public void setEqBass(float eqBass) {
    this.eqBass = eqBass;
  }

  public float getEqTreble() {
    return eqTreble;
  }

  public void setEqTreble(float eqTreble) {
    this.eqTreble = eqTreble;
  }

  public int getSynthAlgorithm() {
    return synthAlgorithm;
  }

  public void setSynthAlgorithm(int synthAlgorithm) {
    this.synthAlgorithm = synthAlgorithm;
  }
}
