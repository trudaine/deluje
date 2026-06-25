package org.deluge.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
  private final int[] customLfoWave = new int[256];

  {
    // Default triangle wave
    for (int i = 0; i < 256; i++) {
      customLfoWave[i] =
          (i < 128) ? (-1073741824 + i * 16777216) : (1073741824 - (i - 128) * 16777216);
    }
  }

  // Oscillator 1 sample-playback params (maps to firmware SampleRecorder/playback)
  private int osc1LoopMode = 0; // 0=off, 1=loop, 2=oneshot
  private boolean osc1Reversed = false;
  private boolean osc1TimeStretch = false;
  private float osc1TimeStretchAmount = 0.0f;
  private int osc1Cents = 0; // fine detune in cents (-50 to 50)
  private boolean osc1LinearInterpolation = false;
  private String osc1SamplePath = null;

  // Oscillator 2 sample-playback params (maps to firmware SampleRecorder/playback)
  private int osc2LoopMode = 0; // 0=off, 1=loop, 2=oneshot
  private boolean osc2Reversed = false;
  private boolean osc2TimeStretch = false;
  private float osc2TimeStretchAmount = 0.0f;
  private boolean osc2LinearInterpolation = false;
  private String osc2SamplePath = null;

  // Oscillator 2 transpose/detune
  private int osc2Transpose = 0; // semitones
  private int osc2Cents = 0; // cents

  // Filter
  private FilterMode filterMode = FilterMode.LADDER_24;
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

  /**
   * Raw stored LFO-rate knob (Q31) for each of the 4 LFOs, preserved for the firmware-faithful rate
   * mapping (knob -&gt; getExp). Avoids the lossy Hz round-trip through {@link
   * org.deluge.xml.DelugeHexMapper#hexToLfoHz}. 0 = the firmware neutral rate (~1.25 Hz).
   */
  private final int[] lfoRateKnobQ31 = {0, 0, 0, 0};

  /**
   * Raw Q31 param-knob overrides parsed from a song clip's {@code <soundParams>} (firmware2 Param
   * id -&gt; raw value). The Deluge song format stores every sound param as a raw Q31 knob; for
   * params where the float round-trip (hex-&gt;float-&gt;normToKnob) loses the firmware range —
   * notably the filter resonance/morph/cutoff, whose minimum must be INT_MIN not the float-path's
   * -2^29 — the factory applies these verbatim, mirroring the firmware's readParamsFromFile. Empty
   * for preset-built tracks (those keep the float path).
   */
  private final Map<Integer, Integer> rawParamKnobs = new HashMap<>();

  public void setRawParamKnob(int paramId, int q31) {
    rawParamKnobs.put(paramId, q31);
  }

  public Map<Integer, Integer> getRawParamKnobs() {
    return rawParamKnobs;
  }

  /**
   * Raw stored envelope rate knobs (Q31) for the 4 envelopes, preserved for the firmware-faithful
   * rate curves (attack: getExp; decay/release: releaseRateTable). {@code envKnobSet[i]} marks that
   * a patch supplied raw knobs for envelope i; programmatic callers that only set times in seconds
   * leave it false, so the factory falls back to the (also-faithful) increment = 190.2/time path.
   */
  private final int[] envAttackKnobQ31 = {0, 0, 0, 0};

  private final int[] envDecayKnobQ31 = {0, 0, 0, 0};
  private final int[] envSustainKnobQ31 = {0, 0, 0, 0};
  private final int[] envReleaseKnobQ31 = {0, 0, 0, 0};
  private final boolean[] envKnobSet = {false, false, false, false};

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
  private float compressorThreshold = 0.0f; // dB, negative = compression threshold
  private float compressorRatio = 0.0f; // compression ratio (0 = off)

  private String modFxType = "NONE";
  private float modFxRate = 0.0f;
  private float modFxDepth = 0.0f;
  private float modFxFeedback = 0.0f;
  private float modFxOffset = 0.0f;
  private float delaySend = 0.0f;
  private float reverbSend = 0.0f;
  private float eqBass = 0.0f;
  private float eqTreble = 0.0f;

  /** Synth mode: 0=SUBTRACTIVE, 1=FM, 2=RINGMOD. Maps to XML `<mode>` tag content. */
  /** Synth mode: 0=SUBTRACTIVE, 1=FM, 2=RINGMOD. Maps to XML `<mode>` tag content. */
  private int synthMode = 0;

  /**
   * FM modulator ratio derived from <modulator1><transpose> — 2^(transpose/12). 1.0 = no
   * transposition.
   */
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

  /**
   * Frequency ratio of FM modulator 2 to the carrier, derived from {@code <modulator2>} transpose +
   * cents. {@link #fmRatio} is modulator 1's ratio. Default 1.0 (same pitch as carrier).
   */
  private float fmRatio2 = 1.0f;

  /**
   * Raw signed Q31 values exactly as stored in the patch XML, preserved for the firmware-faithful
   * FM engine (which runs them through the Deluge patched-param volume/linear curves). {@code
   * Integer.MIN_VALUE} (0x80000000) means "off". These supersede the lossy unipolar floats above
   * for the native 2-op FM path.
   */
  private int modulator1AmountQ31 = Integer.MIN_VALUE;

  private int modulator2AmountQ31 = Integer.MIN_VALUE;
  private int modulator1FeedbackQ31 = Integer.MIN_VALUE;
  private int modulator2FeedbackQ31 = Integer.MIN_VALUE;
  private int carrier1FeedbackQ31 = Integer.MIN_VALUE;
  private int carrier2FeedbackQ31 = Integer.MIN_VALUE;

  /** Wavefolder knob (raw Q31, C LOCAL_FOLD / XML "waveFold"); INT_MIN = off (sound.cpp:147). */
  private int waveFoldQ31 = Integer.MIN_VALUE;

  public int getWaveFoldQ31() {
    return waveFoldQ31;
  }

  public void setWaveFoldQ31(int v) {
    this.waveFoldQ31 = v;
  }

  /** Saturation/clipping amount (C clippingAmount, mod_controllable_audio.h:107); 0 = off. */
  private int clippingAmount = 0;

  public int getClippingAmount() {
    return clippingAmount;
  }

  public void setClippingAmount(int v) {
    this.clippingAmount = v;
  }

  /** Portamento knob (raw Q31, C UNPATCHED_PORTAMENTO / XML "portamento"); INT_MIN = off. */
  private int portamentoQ31 = Integer.MIN_VALUE;

  public int getPortamentoQ31() {
    return portamentoQ31;
  }

  public void setPortamentoQ31(int v) {
    this.portamentoQ31 = v;
  }

  /**
   * When true, FM modulator 1 modulates modulator 0 (chained) rather than the carriers directly.
   */
  private boolean modulator1ToModulator0 = false;

  public enum PolyphonyMode {
    POLY,
    MONO,
    LEGATO,
    AUTO,
    CHOKE
  }

  private PolyphonyMode polyphony = PolyphonyMode.POLY;
  private int maxVoiceCount = 8;
  // C sound.h: voice-stealing priority (0=low, 1=medium, 2=high). XML "voicePriority".
  private int voicePriority = 1;
  private float oscAVolume = 1.0f;
  private float oscBVolume = 1.0f;

  /**
   * Semitone transpose of the entire sound, typically -24 to 24. Maps to XML `<sound>` transpose
   * attr.
   */
  private int transpose = 0;

  /** DX7 patch hex string (312 hex chars = 156 bytes), null if not a DX7 track. */
  private String dx7patch = null;

  /** DX7 random detune amount (written as dx7randomdetune on osc1 element). */
  private int dx7RandomDetune = 0;

  /**
   * DX7 engine type: -1=AUTO (firmware default, MkI for algo 3/5 with feedback), 0=MODERN
   * (sinLookup/exp2Lookup, 32-bit float path), 1=VINTAGE (mkiSin, 14-bit envelopes).
   */
  private int engineType = -1;

  // Synthesis algorithm: 0=FM, 10=Mandolin, 11=Rhodey, 12=ModalBar, 13=Moog
  private int synthAlgorithm = 0;

  // Oscillator sync (hard sync, applies to osc2 when true)
  private boolean oscillatorSync = false;

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
    this.oscAVolume = oscMix;
    this.oscBVolume = 1.0f - oscMix;
  }

  public float getOscAVolume() {
    return oscAVolume;
  }

  public void setOscAVolume(float oscAVolume) {
    this.oscAVolume = oscAVolume;
    this.oscMix = oscAVolume;
  }

  public float getOscBVolume() {
    return oscBVolume;
  }

  public void setOscBVolume(float oscBVolume) {
    this.oscBVolume = oscBVolume;
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

  public float getWaveIndex() {
    return waveIndex;
  }

  public void setWaveIndex(float v) {
    this.waveIndex = v;
  }

  public String getOsc1SamplePath() {
    return osc1SamplePath;
  }

  public void setOsc1SamplePath(String p) {
    this.osc1SamplePath = p;
  }

  // Verbatim <osc1>/<osc2> XML for multisample (<sampleRanges>) oscillators, captured on parse and
  // re-emitted unchanged so keyzone presets survive a song round-trip (our model doesn't re-model
  // keyzones). Null for normal oscillators.
  private String osc1RawXml = null;
  private String osc2RawXml = null;

  public String getOsc1RawXml() {
    return osc1RawXml;
  }

  public void setOsc1RawXml(String x) {
    this.osc1RawXml = x;
  }

  public String getOsc2RawXml() {
    return osc2RawXml;
  }

  public void setOsc2RawXml(String x) {
    this.osc2RawXml = x;
  }

  public String getOsc2SamplePath() {
    return osc2SamplePath;
  }

  public void setOsc2SamplePath(String p) {
    this.osc2SamplePath = p;
  }

  public int getOsc1LoopMode() {
    return osc1LoopMode;
  }

  public void setOsc1LoopMode(int v) {
    this.osc1LoopMode = v;
  }

  public boolean isOsc1Reversed() {
    return osc1Reversed;
  }

  public void setOsc1Reversed(boolean v) {
    this.osc1Reversed = v;
  }

  public boolean isOsc1TimeStretch() {
    return osc1TimeStretch;
  }

  public void setOsc1TimeStretch(boolean v) {
    this.osc1TimeStretch = v;
  }

  public float getOsc1TimeStretchAmount() {
    return osc1TimeStretchAmount;
  }

  public void setOsc1TimeStretchAmount(float v) {
    this.osc1TimeStretchAmount = v;
  }

  public int getOsc1Cents() {
    return osc1Cents;
  }

  public void setOsc1Cents(int v) {
    this.osc1Cents = Math.max(-50, Math.min(50, v));
  }

  public boolean isOsc1LinearInterpolation() {
    return osc1LinearInterpolation;
  }

  public void setOsc1LinearInterpolation(boolean v) {
    this.osc1LinearInterpolation = v;
  }

  public int getOsc2Transpose() {
    return osc2Transpose;
  }

  public void setOsc2Transpose(int v) {
    this.osc2Transpose = Math.max(-24, Math.min(24, v));
  }

  public int getOsc2Cents() {
    return osc2Cents;
  }

  public void setOsc2Cents(int v) {
    this.osc2Cents = Math.max(-50, Math.min(50, v));
  }

  public int getOsc2LoopMode() {
    return osc2LoopMode;
  }

  public void setOsc2LoopMode(int v) {
    this.osc2LoopMode = v;
  }

  public boolean isOsc2Reversed() {
    return osc2Reversed;
  }

  public void setOsc2Reversed(boolean v) {
    this.osc2Reversed = v;
  }

  public boolean isOsc2TimeStretch() {
    return osc2TimeStretch;
  }

  public void setOsc2TimeStretch(boolean v) {
    this.osc2TimeStretch = v;
  }

  public float getOsc2TimeStretchAmount() {
    return osc2TimeStretchAmount;
  }

  public void setOsc2TimeStretchAmount(float v) {
    this.osc2TimeStretchAmount = v;
  }

  public boolean isOsc2LinearInterpolation() {
    return osc2LinearInterpolation;
  }

  public void setOsc2LinearInterpolation(boolean v) {
    this.osc2LinearInterpolation = v;
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

  public FilterMode getHpfMode() {
    return hpfMode;
  }

  public void setHpfMode(FilterMode hpfMode) {
    this.hpfMode = hpfMode;
  }

  public float getHpfFm() {
    return hpfFm;
  }

  public void setHpfFm(float hpfFm) {
    this.hpfFm = hpfFm;
  }

  public float getFilterDrive() {
    return filterDrive;
  }

  public void setFilterDrive(float filterDrive) {
    this.filterDrive = Math.max(0.0f, Math.min(2.0f, filterDrive));
  }

  public boolean isFilterNotch() {
    return filterNotch;
  }

  public void setFilterNotch(boolean filterNotch) {
    this.filterNotch = filterNotch;
  }

  public int getFilterRoute() {
    return filterRoute;
  }

  public void setFilterRoute(int filterRoute) {
    this.filterRoute = filterRoute;
  }

  public int getMaxVoiceCount() {
    return maxVoiceCount;
  }

  public void setMaxVoiceCount(int maxVoiceCount) {
    this.maxVoiceCount = Math.max(1, Math.min(16, maxVoiceCount));
  }

  public int getVoicePriority() {
    return voicePriority;
  }

  public void setVoicePriority(int voicePriority) {
    this.voicePriority = Math.max(0, Math.min(2, voicePriority));
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

  /** Set the raw envelope rate knobs (Q31) for envelope {@code index}, marking it knob-driven. */
  public void setEnvRateKnobsQ31(int index, int attack, int decay, int release) {
    envAttackKnobQ31[index] = attack;
    envDecayKnobQ31[index] = decay;
    envSustainKnobQ31[index] = 0; // default/neutral sustain if not provided in this legacy path
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
    this.modFxType = modFxType != null ? modFxType.toUpperCase() : null;
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

  public float getModFxOffset() {
    return modFxOffset;
  }

  public void setModFxOffset(float modFxOffset) {
    this.modFxOffset = modFxOffset;
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

  // Per-sound delay (the instrument's own <delay> element + soundParams delayFeedback). The
  // firmware delay is per-sound; these drive FirmwareSound's per-sound delay (BPM-synced).
  private int delaySyncLevel = 0;
  private int delaySyncType = 0;
  private int delayFeedbackQ31 = 0;
  private boolean delayPingPong = false;
  private boolean delayAnalog = false;

  public int getDelaySyncLevel() {
    return delaySyncLevel;
  }

  public void setDelaySyncLevel(int v) {
    this.delaySyncLevel = v;
  }

  public int getDelaySyncType() {
    return delaySyncType;
  }

  public void setDelaySyncType(int v) {
    this.delaySyncType = v;
  }

  public int getDelayFeedbackQ31() {
    return delayFeedbackQ31;
  }

  public void setDelayFeedbackQ31(int v) {
    this.delayFeedbackQ31 = v;
  }

  public boolean isDelayPingPong() {
    return delayPingPong;
  }

  public void setDelayPingPong(boolean v) {
    this.delayPingPong = v;
  }

  public boolean isDelayAnalog() {
    return delayAnalog;
  }

  public void setDelayAnalog(boolean v) {
    this.delayAnalog = v;
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
  public float getVolume() {
    return volume;
  }

  @Override
  public void setVolume(float v) {
    this.volume = v;
  }

  @Override
  public float getPan() {
    return pan;
  }

  @Override
  public void setPan(float v) {
    this.pan = v;
  }

  public float getStutterRate() {
    return stutterRate;
  }

  public void setStutterRate(float v) {
    this.stutterRate = v;
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

  public int getCompressorSyncType() {
    return compressorSyncType;
  }

  public void setCompressorSyncType(int v) {
    this.compressorSyncType = v;
  }

  // Sidechain (at sound level)
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

  public int getSynthMode() {
    return synthMode;
  }

  public void setSynthMode(int synthMode) {
    this.synthMode = synthMode;
  }

  public int getTranspose() {
    return transpose;
  }

  public void setTranspose(int transpose) {
    this.transpose = Math.max(-24, Math.min(24, transpose));
  }

  public String getDx7Patch() {
    return dx7patch;
  }

  public void setDx7Patch(String dx7patch) {
    this.dx7patch = dx7patch;
  }

  public int getDx7RandomDetune() {
    return dx7RandomDetune;
  }

  public void setDx7RandomDetune(int v) {
    this.dx7RandomDetune = v;
  }

  public int getEngineType() {
    return engineType;
  }

  public void setEngineType(int engineType) {
    this.engineType = engineType;
  }

  public int getSynthAlgorithm() {
    return synthAlgorithm;
  }

  public void setSynthAlgorithm(int synthAlgorithm) {
    this.synthAlgorithm = synthAlgorithm;
  }

  public boolean isOscillatorSync() {
    return oscillatorSync;
  }

  public void setOscillatorSync(boolean v) {
    this.oscillatorSync = v;
  }

  public float getFmRatio() {
    return fmRatio;
  }

  public void setFmRatio(float fmRatio) {
    this.fmRatio = fmRatio;
  }

  // Raw modulator transpose (semitones) + cents, preserved for the firmware-faithful FM engine,
  // which computes the modulator phase increment from the note table + cents detune (voice.cpp),
  // instead of the lossy 2^(transpose/12) float ratio.
  private int modulator1Transpose = 0;
  private int modulator1Cents = 0;
  private int modulator2Transpose = 0;
  private int modulator2Cents = 0;

  public int getModulator1Transpose() {
    return modulator1Transpose;
  }

  public void setModulator1Transpose(int v) {
    this.modulator1Transpose = v;
  }

  public int getModulator1Cents() {
    return modulator1Cents;
  }

  public void setModulator1Cents(int v) {
    this.modulator1Cents = v;
  }

  public int getModulator2Transpose() {
    return modulator2Transpose;
  }

  public void setModulator2Transpose(int v) {
    this.modulator2Transpose = v;
  }

  public int getModulator2Cents() {
    return modulator2Cents;
  }

  public void setModulator2Cents(int v) {
    this.modulator2Cents = v;
  }

  public float getFmAmount() {
    return fmAmount;
  }

  public void setFmAmount(float fmAmount) {
    this.fmAmount = fmAmount;
  }

  public float getModulator1Feedback() {
    return modulator1Feedback;
  }

  public void setModulator1Feedback(float v) {
    this.modulator1Feedback = v;
  }

  public float getModulator2Amount() {
    return modulator2Amount;
  }

  public void setModulator2Amount(float v) {
    this.modulator2Amount = v;
  }

  public float getModulator2Feedback() {
    return modulator2Feedback;
  }

  public void setModulator2Feedback(float v) {
    this.modulator2Feedback = v;
  }

  public float getCarrier1Feedback() {
    return carrier1Feedback;
  }

  public void setCarrier1Feedback(float v) {
    this.carrier1Feedback = v;
  }

  public float getCarrier2Feedback() {
    return carrier2Feedback;
  }

  public void setCarrier2Feedback(float v) {
    this.carrier2Feedback = v;
  }

  public float getFmRatio2() {
    return fmRatio2;
  }

  public void setFmRatio2(float v) {
    this.fmRatio2 = v;
  }

  public int getModulator1AmountQ31() {
    return modulator1AmountQ31;
  }

  public void setModulator1AmountQ31(int v) {
    this.modulator1AmountQ31 = v;
  }

  public int getModulator2AmountQ31() {
    return modulator2AmountQ31;
  }

  public void setModulator2AmountQ31(int v) {
    this.modulator2AmountQ31 = v;
  }

  public int getModulator1FeedbackQ31() {
    return modulator1FeedbackQ31;
  }

  public void setModulator1FeedbackQ31(int v) {
    this.modulator1FeedbackQ31 = v;
  }

  public int getModulator2FeedbackQ31() {
    return modulator2FeedbackQ31;
  }

  public void setModulator2FeedbackQ31(int v) {
    this.modulator2FeedbackQ31 = v;
  }

  public int getCarrier1FeedbackQ31() {
    return carrier1FeedbackQ31;
  }

  public void setCarrier1FeedbackQ31(int v) {
    this.carrier1FeedbackQ31 = v;
  }

  public int getCarrier2FeedbackQ31() {
    return carrier2FeedbackQ31;
  }

  public void setCarrier2FeedbackQ31(int v) {
    this.carrier2FeedbackQ31 = v;
  }

  public boolean isModulator1ToModulator0() {
    return modulator1ToModulator0;
  }

  public void setModulator1ToModulator0(boolean v) {
    this.modulator1ToModulator0 = v;
  }

  // Stutter config (ModControllableAudio::stutterConfig)
  private boolean stutterQuantized = true;
  private boolean stutterReversed = false;
  private boolean stutterPingPong = false;

  /**
   * Oscillator retrigger phases — the RAW uint32 the Deluge stores/serializes (degrees * 11930464,
   * C retrigger_phase.h:49-58); -1 (0xFFFFFFFF) = off/free-running, the C default (sound.cpp:88).
   */
  private int osc1RetrigPhase = -1;

  private int osc2RetrigPhase = -1;
  private int mod1RetrigPhase = -1;
  private int mod2RetrigPhase = -1;

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

  public PolyphonyMode getPolyphony() {
    return polyphony;
  }

  public void setPolyphony(PolyphonyMode polyphony) {
    this.polyphony = polyphony;
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

  /**
   * Copies all synthesis, oscillator, filter, LFO, envelope, and FX parameters from another model.
   */
  public void copyParametersFrom(SynthTrackModel other) {
    this.osc1Type = other.getOsc1Type();
    this.osc2Type = other.getOsc2Type();
    this.oscMix = other.getOscMix();
    this.noiseVol = other.getNoiseVol();
    this.unisonNum = other.getUnisonNum();
    this.unisonDetune = other.getUnisonDetune();
    this.unisonStereoSpread = other.getUnisonStereoSpread();
    this.waveIndex = other.getWaveIndex();
    this.osc1LoopMode = other.getOsc1LoopMode();
    this.osc1Reversed = other.isOsc1Reversed();
    this.osc1TimeStretch = other.isOsc1TimeStretch();
    this.osc1TimeStretchAmount = other.getOsc1TimeStretchAmount();
    this.osc1Cents = other.getOsc1Cents();
    this.osc1LinearInterpolation = other.isOsc1LinearInterpolation();
    this.osc1SamplePath = other.getOsc1SamplePath();
    this.osc2LoopMode = other.getOsc2LoopMode();
    this.osc2Reversed = other.isOsc2Reversed();
    this.osc2TimeStretch = other.isOsc2TimeStretch();
    this.osc2TimeStretchAmount = other.getOsc2TimeStretchAmount();
    this.osc2LinearInterpolation = other.isOsc2LinearInterpolation();
    this.osc2SamplePath = other.getOsc2SamplePath();
    this.osc2Transpose = other.getOsc2Transpose();
    this.osc2Cents = other.getOsc2Cents();
    this.filterMode = other.getFilterMode();
    this.lpfFreq = other.getLpfFreq();
    this.lpfRes = other.getLpfRes();
    this.lpfMorph = other.getLpfMorph();
    this.hpfFreq = other.getHpfFreq();
    this.hpfRes = other.getHpfRes();
    this.hpfMorph = other.getHpfMorph();
    this.hpfMode = other.getHpfMode();
    this.hpfFm = other.getHpfFm();
    this.filterDrive = other.getFilterDrive();
    this.filterNotch = other.isFilterNotch();
    this.filterRoute = other.getFilterRoute();

    // Arrays
    for (int i = 0; i < 4; i++) {
      this.env[i] = other.getEnv(i);
      this.lfo[i] = other.getLfo(i);
      this.lfoRateKnobQ31[i] = other.getLfoRateKnobQ31(i);
    }

    // FM
    this.synthMode = other.getSynthMode();
    this.fmRatio = other.getFmRatio();
    this.fmAmount = other.getFmAmount();
    this.modulator1Feedback = other.getModulator1Feedback();
    this.modulator2Amount = other.getModulator2Amount();
    this.modulator2Feedback = other.getModulator2Feedback();
    this.carrier1Feedback = other.getCarrier1Feedback();
    this.carrier2Feedback = other.getCarrier2Feedback();
    this.fmRatio2 = other.getFmRatio2();
    this.modulator1AmountQ31 = other.modulator1AmountQ31;
    this.modulator2AmountQ31 = other.modulator2AmountQ31;
    this.modulator1FeedbackQ31 = other.modulator1FeedbackQ31;
    this.modulator2FeedbackQ31 = other.modulator2FeedbackQ31;
    this.carrier1FeedbackQ31 = other.carrier1FeedbackQ31;
    this.carrier2FeedbackQ31 = other.carrier2FeedbackQ31;
    this.waveFoldQ31 = other.getWaveFoldQ31();
    this.clippingAmount = other.getClippingAmount();
    this.portamentoQ31 = other.getPortamentoQ31();
    this.modulator1ToModulator0 = other.modulator1ToModulator0;
    this.polyphony = other.getPolyphony();
    this.maxVoiceCount = other.getMaxVoiceCount();
    this.voicePriority = other.getVoicePriority();
    this.oscAVolume = other.getOscAVolume();
    this.oscBVolume = other.getOscBVolume();
    this.transpose = other.getTranspose();
    this.dx7patch = other.dx7patch;
    this.dx7RandomDetune = other.dx7RandomDetune;
    this.engineType = other.engineType;
    this.synthAlgorithm = other.getSynthAlgorithm();
    this.oscillatorSync = other.oscillatorSync;

    // Patch cables & Mod knobs
    this.patchCables.clear();
    this.patchCables.addAll(other.getPatchCables());
    for (int i = 0; i < 16; i++) {
      this.modKnobs.set(i, other.getModKnobs().get(i));
    }

    // FX
    this.volume = other.getVolume();
    this.pan = other.getPan();
    this.stutterRate = other.getStutterRate();
    this.sampleRateReduction = other.getSampleRateReduction();
    this.bitCrush = other.getBitCrush();
    this.compressorAttack = other.getCompressorAttack();
    this.compressorRelease = other.getCompressorRelease();
    this.compressorSyncLevel = other.getCompressorSyncLevel();
    this.compressorBlend = other.getCompressorBlend();
    this.compressorSidechainHpf = other.getCompressorSidechainHpf();
    this.compressorSyncType = other.getCompressorSyncType();
    this.sidechainSyncLevel = other.getSidechainSyncLevel();
    this.sidechainSyncType = other.getSidechainSyncType();
    this.sidechainAttack = other.getSidechainAttack();
    this.sidechainRelease = other.getSidechainRelease();
    this.compressorThreshold = other.getCompressorThreshold();
    this.compressorRatio = other.getCompressorRatio();
    this.modFxType = other.getModFxType();
    this.modFxRate = other.getModFxRate();
    this.modFxDepth = other.getModFxDepth();
    this.modFxFeedback = other.getModFxFeedback();
    this.modFxOffset = other.getModFxOffset();
    this.delaySend = other.getDelaySend();
    this.reverbSend = other.getReverbSend();
    this.eqBass = other.getEqBass();
    this.eqTreble = other.getEqTreble();
    this.delaySyncLevel = other.getDelaySyncLevel();
    this.delaySyncType = other.getDelaySyncType();
    this.delayFeedbackQ31 = other.getDelayFeedbackQ31();
    this.delayPingPong = other.isDelayPingPong();
    this.delayAnalog = other.isDelayAnalog();
    System.arraycopy(other.getCustomLfoWave(), 0, this.customLfoWave, 0, 256);
  }

  public int[] getCustomLfoWave() {
    return customLfoWave;
  }
}
