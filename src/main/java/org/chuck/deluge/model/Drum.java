package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Abstract base for all drum types on a Kit track. Holds synth-parity parameters common to
 * sample-based, MIDI, and gate-trigger drums.
 */
public abstract class Drum {

  protected String name;
  protected float pitchSemitones = 0.0f;
  protected int muteGroup = 0;

  // Per-sound shaping
  protected EnvelopeModel adsr = EnvelopeModel.defaultConfig();
  protected float lpfFreq = 20000.0f;
  protected float lpfRes = 0.0f;

  protected float eqBass = 0.0f;
  protected float eqTreble = 0.0f;
  protected float sidechainSend = 0.0f;

  // Envelopes 2-4
  protected EnvelopeModel env2 = EnvelopeModel.defaultConfig();
  protected EnvelopeModel env3 = EnvelopeModel.defaultConfig();
  protected EnvelopeModel env4 = EnvelopeModel.defaultConfig();

  // LFOs
  protected LfoModel lfo1 = LfoModel.defaultConfig(true);
  protected LfoModel lfo2 = LfoModel.defaultConfig(false);

  // Delay
  protected float delayRate = 0.0f;
  protected float delayFeedback = 0.0f;
  protected int delayPingPong = 0; // 0=off, 1=on
  protected int delayAnalog = 0; // 0=digital, 1=analog

  // Mod knobs & patch cables
  protected final List<ModKnob> modKnobs = new ArrayList<>(16);
  protected final List<PatchCable> patchCables = new ArrayList<>();

  // Polyphonic, voice priority, clipping
  protected SynthTrackModel.PolyphonyMode polyphony = SynthTrackModel.PolyphonyMode.POLY;
  protected int voicePriority = 1;
  protected float clippingAmount = 0.0f;

  // Unison
  protected int unisonNum = 1;
  protected float unisonDetune = 0.0f;
  protected float unisonStereoSpread = 0.0f;

  // Compressor
  protected float compressorAttack = 0.0f;
  protected float compressorRelease = 0.0f;
  protected int compressorSyncLevel = 0;
  protected float compressorBlend = 0.0f;
  protected float compressorSidechainHpf = 0.0f;
  protected float compressorThreshold = 0.0f;
  protected float compressorRatio = 0.0f;

  // LPF Mode/Morph
  protected FilterMode lpfMode = FilterMode.LADDER_12;
  protected float lpfMorph = 0.0f;
  protected float lpfDrive = 1.0f;
  protected boolean lpfNotch = false;
  protected int maxVoiceCount = 8;

  // Sidechain (at sound level)
  protected int sidechainSyncLevel = 0;
  protected int sidechainSyncType = 0;
  protected float sidechainAttack = 0.0f;
  protected float sidechainRelease = 0.0f;

  // Mod FX
  protected String modFxType = "NONE";
  protected float modFxRate = 0.3f;
  protected float modFxDepth = 0.3f;
  protected float modFxOffset = 0.0f;
  protected float modFxFeedback = 0.0f;

  // HPF
  protected float hpfFreq = 20.0f;
  protected float hpfRes = 0.0f;
  protected float hpfMorph = 0.0f;
  protected FilterMode hpfMode = FilterMode.LADDER_12;
  protected float hpfFm = 0.0f;

  // Oscillator retrigger phases
  protected int osc1RetrigPhase = 0;
  protected int osc2RetrigPhase = 0;
  protected int mod1RetrigPhase = -1;
  protected int mod2RetrigPhase = -1;

  // Wavetable position
  protected float waveIndex = 0.0f;

  // Default param values
  protected float volume = 0.5f;
  protected float pan = 0.0f;
  protected float oscAVolume = 1.0f;
  protected float oscBVolume = 0.0f;
  protected float noiseVolume = 0.0f;
  protected float arpeggiatorGate = 0.0f;
  protected float portamento = 0.0f;
  protected float stutterRate = 0.0f;

  // Stutter config (ModControllableAudio::stutterConfig)
  protected boolean stutterQuantized = true;
  protected boolean stutterReversed = false;
  protected boolean stutterPingPong = false;
  protected float sampleRateReduction = 0.0f;
  protected float bitCrush = 0.0f;
  protected float fmAmount = 0.0f;
  protected float reverbAmount = 0.0f;

  protected Drum(String name) {
    this.name = name;
    for (int i = 0; i < 16; i++) {
      modKnobs.add(ModKnob.empty());
    }
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public float getPitchSemitones() {
    return pitchSemitones;
  }

  public void setPitchSemitones(float v) {
    this.pitchSemitones = v;
  }

  public int getMuteGroup() {
    return muteGroup;
  }

  public void setMuteGroup(int v) {
    this.muteGroup = v;
  }

  public EnvelopeModel getAdsr() {
    return adsr;
  }

  public void setAdsr(EnvelopeModel v) {
    this.adsr = v;
  }

  public float getLpfFreq() {
    return lpfFreq;
  }

  public void setLpfFreq(float v) {
    this.lpfFreq = v;
  }

  public float getLpfRes() {
    return lpfRes;
  }

  public void setLpfRes(float v) {
    this.lpfRes = v;
  }

  public float getEqBass() {
    return eqBass;
  }

  public void setEqBass(float v) {
    this.eqBass = v;
  }

  public float getEqTreble() {
    return eqTreble;
  }

  public void setEqTreble(float v) {
    this.eqTreble = v;
  }

  public float getSidechainSend() {
    return sidechainSend;
  }

  public void setSidechainSend(float v) {
    this.sidechainSend = v;
  }

  public int getSidechainSyncLevel() {
    return sidechainSyncLevel;
  }

  public void setSidechainSyncLevel(int v) {
    this.sidechainSyncLevel = v;
  }

  public int getSidechainSyncType() {
    return sidechainSyncType;
  }

  public void setSidechainSyncType(int v) {
    this.sidechainSyncType = v;
  }

  public float getSidechainAttack() {
    return sidechainAttack;
  }

  public void setSidechainAttack(float v) {
    this.sidechainAttack = v;
  }

  public float getSidechainRelease() {
    return sidechainRelease;
  }

  public void setSidechainRelease(float v) {
    this.sidechainRelease = v;
  }

  public EnvelopeModel getEnv2() {
    return env2;
  }

  public void setEnv2(EnvelopeModel v) {
    this.env2 = v;
  }

  public EnvelopeModel getEnv3() {
    return env3;
  }

  public void setEnv3(EnvelopeModel v) {
    this.env3 = v;
  }

  public EnvelopeModel getEnv4() {
    return env4;
  }

  public void setEnv4(EnvelopeModel v) {
    this.env4 = v;
  }

  public LfoModel getLfo1() {
    return lfo1;
  }

  public void setLfo1(LfoModel v) {
    this.lfo1 = v;
  }

  public LfoModel getLfo2() {
    return lfo2;
  }

  public void setLfo2(LfoModel v) {
    this.lfo2 = v;
  }

  public float getDelayRate() {
    return delayRate;
  }

  public void setDelayRate(float v) {
    this.delayRate = v;
  }

  public float getDelayFeedback() {
    return delayFeedback;
  }

  public void setDelayFeedback(float v) {
    this.delayFeedback = v;
  }

  public int getDelayPingPong() {
    return delayPingPong;
  }

  public void setDelayPingPong(int v) {
    this.delayPingPong = v;
  }

  public int getDelayAnalog() {
    return delayAnalog;
  }

  public void setDelayAnalog(int v) {
    this.delayAnalog = v;
  }

  public List<ModKnob> getModKnobs() {
    return modKnobs;
  }

  public void setModKnob(int index, ModKnob knob) {
    this.modKnobs.set(index, knob);
  }

  public List<PatchCable> getPatchCables() {
    return patchCables;
  }

  public void addPatchCable(PatchCable cable) {
    this.patchCables.add(cable);
  }

  public SynthTrackModel.PolyphonyMode getPolyphony() {
    return polyphony;
  }

  public void setPolyphony(SynthTrackModel.PolyphonyMode v) {
    this.polyphony = v;
  }

  public boolean isPolyphonic() {
    return polyphony != SynthTrackModel.PolyphonyMode.MONO;
  }

  public void setPolyphonic(boolean v) {
    this.polyphony = v ? SynthTrackModel.PolyphonyMode.POLY : SynthTrackModel.PolyphonyMode.MONO;
  }

  public int getVoicePriority() {
    return voicePriority;
  }

  public void setVoicePriority(int v) {
    this.voicePriority = v;
  }

  public float getClippingAmount() {
    return clippingAmount;
  }

  public void setClippingAmount(float v) {
    this.clippingAmount = v;
  }

  public int getUnisonNum() {
    return unisonNum;
  }

  public void setUnisonNum(int v) {
    this.unisonNum = v;
  }

  public float getUnisonDetune() {
    return unisonDetune;
  }

  public void setUnisonDetune(float v) {
    this.unisonDetune = v;
  }

  public float getUnisonStereoSpread() {
    return unisonStereoSpread;
  }

  public void setUnisonStereoSpread(float v) {
    this.unisonStereoSpread = v;
  }

  public float getCompressorAttack() {
    return compressorAttack;
  }

  public void setCompressorAttack(float v) {
    this.compressorAttack = v;
  }

  public float getCompressorRelease() {
    return compressorRelease;
  }

  public void setCompressorRelease(float v) {
    this.compressorRelease = v;
  }

  public int getCompressorSyncLevel() {
    return compressorSyncLevel;
  }

  public void setCompressorSyncLevel(int v) {
    this.compressorSyncLevel = v;
  }

  public float getCompressorBlend() {
    return compressorBlend;
  }

  public void setCompressorBlend(float v) {
    this.compressorBlend = Math.max(0.0f, Math.min(1.0f, v));
  }

  public float getCompressorSidechainHpf() {
    return compressorSidechainHpf;
  }

  public void setCompressorSidechainHpf(float v) {
    this.compressorSidechainHpf = Math.max(0.0f, Math.min(1.0f, v));
  }

  public float getCompressorThreshold() {
    return compressorThreshold;
  }

  public void setCompressorThreshold(float v) {
    this.compressorThreshold = v;
  }

  public float getCompressorRatio() {
    return compressorRatio;
  }

  public void setCompressorRatio(float v) {
    this.compressorRatio = v;
  }

  public FilterMode getLpfMode() {
    return lpfMode;
  }

  public void setLpfMode(FilterMode v) {
    this.lpfMode = v;
  }

  public float getLpfMorph() {
    return lpfMorph;
  }

  public void setLpfMorph(float v) {
    this.lpfMorph = v;
  }

  public float getLpfDrive() {
    return lpfDrive;
  }

  public void setLpfDrive(float v) {
    this.lpfDrive = Math.max(0.0f, Math.min(2.0f, v));
  }

  public boolean isLpfNotch() {
    return lpfNotch;
  }

  public void setLpfNotch(boolean v) {
    this.lpfNotch = v;
  }

  public int getMaxVoiceCount() {
    return maxVoiceCount;
  }

  public void setMaxVoiceCount(int v) {
    this.maxVoiceCount = Math.max(1, Math.min(16, v));
  }

  public String getModFxType() {
    return modFxType;
  }

  public void setModFxType(String v) {
    this.modFxType = v;
  }

  public float getModFxRate() {
    return modFxRate;
  }

  public void setModFxRate(float v) {
    this.modFxRate = v;
  }

  public float getModFxDepth() {
    return modFxDepth;
  }

  public void setModFxDepth(float v) {
    this.modFxDepth = v;
  }

  public float getModFxOffset() {
    return modFxOffset;
  }

  public void setModFxOffset(float v) {
    this.modFxOffset = v;
  }

  public float getModFxFeedback() {
    return modFxFeedback;
  }

  public void setModFxFeedback(float v) {
    this.modFxFeedback = v;
  }

  public float getHpfFreq() {
    return hpfFreq;
  }

  public void setHpfFreq(float v) {
    this.hpfFreq = v;
  }

  public float getHpfRes() {
    return hpfRes;
  }

  public void setHpfRes(float v) {
    this.hpfRes = v;
  }

  public float getHpfMorph() {
    return hpfMorph;
  }

  public void setHpfMorph(float v) {
    this.hpfMorph = v;
  }

  public FilterMode getHpfMode() {
    return hpfMode;
  }

  public void setHpfMode(FilterMode v) {
    this.hpfMode = v;
  }

  public float getHpfFm() {
    return hpfFm;
  }

  public void setHpfFm(float v) {
    this.hpfFm = v;
  }

  public int getOsc1RetrigPhase() {
    return osc1RetrigPhase;
  }

  public void setOsc1RetrigPhase(int v) {
    this.osc1RetrigPhase = v;
  }

  public int getOsc2RetrigPhase() {
    return osc2RetrigPhase;
  }

  public void setOsc2RetrigPhase(int v) {
    this.osc2RetrigPhase = v;
  }

  public int getMod1RetrigPhase() {
    return mod1RetrigPhase;
  }

  public void setMod1RetrigPhase(int v) {
    this.mod1RetrigPhase = v;
  }

  public int getMod2RetrigPhase() {
    return mod2RetrigPhase;
  }

  public void setMod2RetrigPhase(int v) {
    this.mod2RetrigPhase = v;
  }

  public int getRetrigPhase() {
    return osc1RetrigPhase;
  }

  public void setRetrigPhase(int v) {
    this.osc1RetrigPhase = v;
    this.osc2RetrigPhase = v;
  }

  public float getWaveIndex() {
    return waveIndex;
  }

  public void setWaveIndex(float v) {
    this.waveIndex = v;
  }

  public float getVolume() {
    return volume;
  }

  public void setVolume(float v) {
    this.volume = v;
  }

  public float getPan() {
    return pan;
  }

  public void setPan(float v) {
    this.pan = v;
  }

  public float getOscAVolume() {
    return oscAVolume;
  }

  public void setOscAVolume(float v) {
    this.oscAVolume = v;
  }

  public float getOscBVolume() {
    return oscBVolume;
  }

  public void setOscBVolume(float v) {
    this.oscBVolume = v;
  }

  public float getNoiseVolume() {
    return noiseVolume;
  }

  public void setNoiseVolume(float v) {
    this.noiseVolume = v;
  }

  public float getArpeggiatorGate() {
    return arpeggiatorGate;
  }

  public void setArpeggiatorGate(float v) {
    this.arpeggiatorGate = v;
  }

  public float getPortamento() {
    return portamento;
  }

  public void setPortamento(float v) {
    this.portamento = v;
  }

  public float getStutterRate() {
    return stutterRate;
  }

  public void setStutterRate(float v) {
    this.stutterRate = v;
  }

  public boolean isStutterQuantized() {
    return stutterQuantized;
  }

  public void setStutterQuantized(boolean v) {
    this.stutterQuantized = v;
  }

  public boolean isStutterReversed() {
    return stutterReversed;
  }

  public void setStutterReversed(boolean v) {
    this.stutterReversed = v;
  }

  public boolean isStutterPingPong() {
    return stutterPingPong;
  }

  public void setStutterPingPong(boolean v) {
    this.stutterPingPong = v;
  }

  public float getSampleRateReduction() {
    return sampleRateReduction;
  }

  public void setSampleRateReduction(float v) {
    this.sampleRateReduction = v;
  }

  public float getBitCrush() {
    return bitCrush;
  }

  public void setBitCrush(float v) {
    this.bitCrush = v;
  }

  public float getFmAmount() {
    return fmAmount;
  }

  public void setFmAmount(float v) {
    this.fmAmount = v;
  }

  public float getReverbAmount() {
    return reverbAmount;
  }

  public void setReverbAmount(float v) {
    this.reverbAmount = v;
  }
}
