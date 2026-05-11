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
  private float unisonStereoSpread = 0.0f;
  private float waveIndex = 0.0f;

  // Oscillator 1 sample-playback params (maps to firmware SampleRecorder/playback)
  private int osc1LoopMode = 0;       // 0=off, 1=loop, 2=oneshot
  private boolean osc1Reversed = false;
  private boolean osc1TimeStretch = false;
  private float osc1TimeStretchAmount = 0.0f;
  private int osc1Cents = 0;          // fine detune in cents (-50 to 50)

  // Oscillator 2 transpose/detune
  private int osc2Transpose = 0;      // semitones
  private int osc2Cents = 0;          // cents

  // Filter
  private FilterMode filterMode = FilterMode.LADDER_12;
  private float lpfFreq = 20000.0f;
  private float lpfRes = 0.0f;
  private float lpfMorph = 0.0f;
  private float hpfFreq = 20.0f;
  private float hpfRes = 0.0f;
  private float hpfMorph = 0.0f;
  private FilterMode hpfMode = FilterMode.LADDER_12;
  private float hpfFm = 0.0f;
  private float filterDrive = 1.0f;
  private boolean filterNotch = false;
  private int filterRoute = 0; // 0=SERIES_LPF_HPF, 1=SERIES_HPF_LPF, 2=PARALLEL

  // Modulation arrays (per firmware correction §23)
  private final EnvelopeModel[] env = new EnvelopeModel[4];
  private final LfoModel[] lfo = new LfoModel[4];

  private ArpModel arp = ArpModel.defaultConfig();
  private float portamento = 0.0f;

  private final List<PatchCable> patchCables = new ArrayList<>();
  private final List<ModKnob> modKnobs = new ArrayList<>(16);

  // FX and EQ
  // Master volume, pan
  private float volume = 0.5f;
  private float pan = 0.0f;

  // Stutter, bitcrush, sample rate reduction
  private float stutterRate = 0.0f;
  private float sampleRateReduction = 0.0f;
  private float bitCrush = 0.0f;

  // Compressor per-track
  private float compressorAttack = 0.0f;
  private float compressorRelease = 0.0f;
  private int compressorSyncLevel = 0;
  private float compressorBlend = 0.0f;
  private float compressorSidechainHpf = 0.0f;
  private int compressorSyncType = 0; // sidechain sync type

  // Sidechain (at sound level, separate from compressor)
  private int sidechainSyncLevel = 0;
  private int sidechainSyncType = 0;
  private float sidechainAttack = 0.0f;
  private float sidechainRelease = 0.0f;
  private float compressorThreshold = 0.0f;  // dB, negative = compression threshold
  private float compressorRatio = 0.0f;      // compression ratio (0 = off)

  private String modFxType = "NONE";
  private float modFxRate = 0.0f;
  private float modFxDepth = 0.0f;
  private float modFxFeedback = 0.0f;
  private float delaySend = 0.0f;
  private float reverbSend = 0.0f;
  private float eqBass = 0.0f;
  private float eqTreble = 0.0f;

  /** Synth mode: 0=SUBTRACTIVE, 1=FM, 2=RINGMOD. Maps to XML `<mode>` tag content. */
  /** Synth mode: 0=SUBTRACTIVE, 1=FM, 2=RINGMOD. Maps to XML `<mode>` tag content. */
  private int synthMode = 0;

  /** FM modulator ratio derived from <modulator1><transpose> — 2^(transpose/12). 1.0 = no transposition. */
  private float fmRatio = 1.0f;
  /** FM modulator amount (0-1) derived from <modulator1Amount> hex knob value. */
  private float fmAmount = 0.0f;

  /** Modulator 1 feedback amount (0-1) — self-modulation of the FM modulator. */
  private float modulator1Feedback = 0.0f;
  /** Modulator 2 FM amount (0-1). */
  private float modulator2Amount = 0.0f;
  /** Modulator 2 feedback amount (0-1). */
  private float modulator2Feedback = 0.0f;
  /** Carrier 1 feedback amount (0-1) — self-modulation of the FM carrier. */
  private float carrier1Feedback = 0.0f;
  /** Carrier 2 feedback amount (0-1). */
  private float carrier2Feedback = 0.0f;

  public enum PolyphonyMode { POLY, MONO, LEGATO, AUTO, CHOKE }

  private PolyphonyMode polyphony = PolyphonyMode.POLY;
  private int maxVoiceCount = 8;

  /** Semitone transpose of the entire sound, typically -24 to 24. Maps to XML `<sound>` transpose attr. */
  private int transpose = 0;

  /** DX7 patch hex string (312 hex chars = 156 bytes), null if not a DX7 track. */
  private String dx7patch = null;

  /**
   * DX7 engine type: -1=AUTO (firmware default, MkI for algo 3/5 with feedback),
   * 0=MODERN (sinLookup/exp2Lookup, 32-bit float path), 1=VINTAGE (mkiSin, 14-bit envelopes).
   */
  private int engineType = -1;

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

  public float getUnisonStereoSpread() {
    return unisonStereoSpread;
  }

  public void setUnisonStereoSpread(float unisonStereoSpread) {
    this.unisonStereoSpread = unisonStereoSpread;
  }

  public float getWaveIndex() { return waveIndex; }
  public void setWaveIndex(float v) { this.waveIndex = v; }

  public int getOsc1LoopMode() { return osc1LoopMode; }
  public void setOsc1LoopMode(int v) { this.osc1LoopMode = v; }
  public boolean isOsc1Reversed() { return osc1Reversed; }
  public void setOsc1Reversed(boolean v) { this.osc1Reversed = v; }
  public boolean isOsc1TimeStretch() { return osc1TimeStretch; }
  public void setOsc1TimeStretch(boolean v) { this.osc1TimeStretch = v; }
  public float getOsc1TimeStretchAmount() { return osc1TimeStretchAmount; }
  public void setOsc1TimeStretchAmount(float v) { this.osc1TimeStretchAmount = v; }
  public int getOsc1Cents() { return osc1Cents; }
  public void setOsc1Cents(int v) { this.osc1Cents = Math.max(-50, Math.min(50, v)); }

  public int getOsc2Transpose() { return osc2Transpose; }
  public void setOsc2Transpose(int v) { this.osc2Transpose = Math.max(-24, Math.min(24, v)); }
  public int getOsc2Cents() { return osc2Cents; }
  public void setOsc2Cents(int v) { this.osc2Cents = Math.max(-50, Math.min(50, v)); }

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

  public FilterMode getHpfMode() { return hpfMode; }
  public void setHpfMode(FilterMode hpfMode) { this.hpfMode = hpfMode; }
  public float getHpfFm() { return hpfFm; }
  public void setHpfFm(float hpfFm) { this.hpfFm = hpfFm; }

  public float getFilterDrive() { return filterDrive; }
  public void setFilterDrive(float filterDrive) { this.filterDrive = Math.max(0.0f, Math.min(2.0f, filterDrive)); }
  public boolean isFilterNotch() { return filterNotch; }
  public void setFilterNotch(boolean filterNotch) { this.filterNotch = filterNotch; }
  public int getFilterRoute() { return filterRoute; }
  public void setFilterRoute(int filterRoute) { this.filterRoute = filterRoute; }
  public int getMaxVoiceCount() { return maxVoiceCount; }
  public void setMaxVoiceCount(int maxVoiceCount) { this.maxVoiceCount = Math.max(1, Math.min(16, maxVoiceCount)); }

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

  @Override
  public float getVolume() { return volume; }
  @Override
  public void setVolume(float v) { this.volume = v; }
  @Override
  public float getPan() { return pan; }
  @Override
  public void setPan(float v) { this.pan = v; }
  public float getStutterRate() { return stutterRate; }
  public void setStutterRate(float v) { this.stutterRate = v; }
  public float getSampleRateReduction() { return sampleRateReduction; }
  public void setSampleRateReduction(float v) { this.sampleRateReduction = v; }
  public float getBitCrush() { return bitCrush; }
  public void setBitCrush(float v) { this.bitCrush = v; }

  public float getCompressorAttack() { return compressorAttack; }
  public void setCompressorAttack(float v) { this.compressorAttack = v; }
  public float getCompressorRelease() { return compressorRelease; }
  public void setCompressorRelease(float v) { this.compressorRelease = v; }
  public int getCompressorSyncLevel() { return compressorSyncLevel; }
  public void setCompressorSyncLevel(int v) { this.compressorSyncLevel = v; }
  public float getCompressorBlend() { return compressorBlend; }
  public void setCompressorBlend(float v) { this.compressorBlend = Math.max(0.0f, Math.min(1.0f, v)); }
  public float getCompressorSidechainHpf() { return compressorSidechainHpf; }
  public void setCompressorSidechainHpf(float v) { this.compressorSidechainHpf = Math.max(0.0f, Math.min(1.0f, v)); }
  public float getCompressorThreshold() { return compressorThreshold; }
  public void setCompressorThreshold(float v) { this.compressorThreshold = v; }
  public float getCompressorRatio() { return compressorRatio; }
  public void setCompressorRatio(float v) { this.compressorRatio = v; }
  public int getCompressorSyncType() { return compressorSyncType; }
  public void setCompressorSyncType(int v) { this.compressorSyncType = v; }

  // Sidechain (at sound level)
  public int getSidechainSyncLevel() { return sidechainSyncLevel; }
  public void setSidechainSyncLevel(int v) { this.sidechainSyncLevel = v; }
  public int getSidechainSyncType() { return sidechainSyncType; }
  public void setSidechainSyncType(int v) { this.sidechainSyncType = v; }
  public float getSidechainAttack() { return sidechainAttack; }
  public void setSidechainAttack(float v) { this.sidechainAttack = v; }
  public float getSidechainRelease() { return sidechainRelease; }
  public void setSidechainRelease(float v) { this.sidechainRelease = v; }

  public int getSynthMode() {
    return synthMode;
  }

  public void setSynthMode(int synthMode) {
    this.synthMode = synthMode;
  }

  public int getTranspose() { return transpose; }
  public void setTranspose(int transpose) { this.transpose = Math.max(-24, Math.min(24, transpose)); }

  public String getDx7Patch() { return dx7patch; }
  public void setDx7Patch(String dx7patch) { this.dx7patch = dx7patch; }

  public int getEngineType() { return engineType; }
  public void setEngineType(int engineType) { this.engineType = engineType; }

  public int getSynthAlgorithm() {
    return synthAlgorithm;
  }

  public void setSynthAlgorithm(int synthAlgorithm) {
    this.synthAlgorithm = synthAlgorithm;
  }

  public float getFmRatio() {
    return fmRatio;
  }

  public void setFmRatio(float fmRatio) {
    this.fmRatio = fmRatio;
  }

  public float getFmAmount() {
    return fmAmount;
  }

  public void setFmAmount(float fmAmount) {
    this.fmAmount = fmAmount;
  }

  public float getModulator1Feedback() { return modulator1Feedback; }
  public void setModulator1Feedback(float v) { this.modulator1Feedback = v; }

  public float getModulator2Amount() { return modulator2Amount; }
  public void setModulator2Amount(float v) { this.modulator2Amount = v; }

  public float getModulator2Feedback() { return modulator2Feedback; }
  public void setModulator2Feedback(float v) { this.modulator2Feedback = v; }

  public float getCarrier1Feedback() { return carrier1Feedback; }
  public void setCarrier1Feedback(float v) { this.carrier1Feedback = v; }

  public float getCarrier2Feedback() { return carrier2Feedback; }
  public void setCarrier2Feedback(float v) { this.carrier2Feedback = v; }

  /** Oscillator retrigger phase. -1=FREE (free running), 0=RESET, positive=phase offset in degrees. */
  private int retrigPhase = 0;

  public int getRetrigPhase() { return retrigPhase; }
  public void setRetrigPhase(int v) { this.retrigPhase = v; }

  public PolyphonyMode getPolyphony() {
    return polyphony;
  }

  public void setPolyphony(PolyphonyMode polyphony) {
    this.polyphony = polyphony;
  }
}
