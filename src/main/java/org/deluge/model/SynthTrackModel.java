package org.deluge.model;

/** Represents a synthesizer track containing full synthesis and modulation parameters. */
public class SynthTrackModel extends TrackModel {

  // Oscillators
  private final OscillatorConfig osc1 = new OscillatorConfig("SINE");
  private final OscillatorConfig osc2 = new OscillatorConfig("NONE");
  private String osc1RawXml = null;
  private String osc2RawXml = null;

  public OscillatorConfig getOsc1() {
    return osc1;
  }

  public OscillatorConfig getOsc2() {
    return osc2;
  }

  private float oscMix = 0.5f;
  private float noiseVol = 0.0f;
  private final UnisonConfig unison = new UnisonConfig();
  private float waveIndex = 0.0f;
  private final int[] customLfoWave = new int[256];

  {
    // Default triangle wave
    for (int i = 0; i < 256; i++) {
      customLfoWave[i] =
          (i < 128) ? (-1073741824 + i * 16777216) : (1073741824 - (i - 128) * 16777216);
    }
  }

  // Filter
  private final FilterConfig filter = new FilterConfig();

  public FilterConfig getFilter() {
    return filter;
  }

  // Modulation arrays (per firmware correction §23)
  private final EnvelopeModel[] env = new EnvelopeModel[4];
  private final LfoModel[] lfo = new LfoModel[4];

  /**
   * Raw stored LFO-rate knob (Q31) for each of the 4 LFOs, preserved for the firmware-faithful rate
   * mapping (knob -&gt; getExp). Avoids the lossy Hz round-trip through {@link
   * org.deluge.xml.DelugeHexMapper#hexToLfoHz}. 0 = the firmware neutral rate (~1.25 Hz).
   */
  private final RawKnobConfig rawKnobs = new RawKnobConfig();

  private ArpModel arp = ArpModel.defaultConfig();
  private float portamento = 0.0f;

  private final ModulationConfig modulation = new ModulationConfig();

  // FX and EQ
  // Master volume, pan
  private float volume = 0.5f;
  private float pan = 0.0f;

  // Stutter, bitcrush, sample rate reduction
  private final StutterConfig stutter = new StutterConfig();
  private float sampleRateReduction = 0.0f;
  private float bitCrush = 0.0f;

  // Compressor per-track
  private final CompressorConfig compressor = new CompressorConfig();

  public CompressorConfig getCompressor() {
    return compressor;
  }

  private final FxConfig fx = new FxConfig();

  public FxConfig getFx() {
    return fx;
  }

  /** Synth mode: 0=SUBTRACTIVE, 1=FM, 2=RINGMOD. Maps to XML `<mode>` tag content. */
  /** Synth mode: 0=SUBTRACTIVE, 1=FM, 2=RINGMOD. Maps to XML `<mode>` tag content. */
  private int synthMode = 0;

  private final FmConfig fm = new FmConfig();

  public FmConfig getFm() {
    return fm;
  }

  // Overall voice pitch adjust (raw Q31; C LOCAL_PITCH_ADJUST, XML pitchAdjust). INT_MIN = unset.
  private int pitchAdjustQ31 = Integer.MIN_VALUE;

  public int getOsc1PitchAdjustQ31() {
    return osc1.getPitchAdjustQ31();
  }

  public void setOsc1PitchAdjustQ31(int v) {
    osc1.setPitchAdjustQ31(v);
  }

  public int getOsc2PitchAdjustQ31() {
    return osc2.getPitchAdjustQ31();
  }

  public void setOsc2PitchAdjustQ31(int v) {
    osc2.setPitchAdjustQ31(v);
  }

  /** Wavefolder knob (raw Q31, C LOCAL_FOLD / XML "waveFold"); INT_MIN = off (sound.cpp:147). */
  private int waveFoldQ31 = Integer.MIN_VALUE;

  public int getWaveFoldQ31() {
    return waveFoldQ31;
  }

  public void setWaveFoldQ31(int v) {
    this.waveFoldQ31 = v;
  }

  /** Portamento knob (raw Q31, C UNPATCHED_PORTAMENTO / XML "portamento"); INT_MIN = off. */
  private int portamentoQ31 = Integer.MIN_VALUE;

  public int getPortamentoQ31() {
    return portamentoQ31;
  }

  public void setPortamentoQ31(int v) {
    this.portamentoQ31 = v;
  }

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

  // C: Output::modKnobMode (model/output.h:105) -- which of the 8 gold-knob parameter pairs the
  // physical MOD_ENCODER_0/1 knobs currently control, selected by pressing one of the 8 MOD
  // buttons (C: model/global_effectable/global_effectable.cpp:100-131, sound.cpp:97-122 for the
  // default per-mode parameter table). Not yet round-tripped through XML serialization.
  private int modKnobMode = 1;

  /**
   * Semitone transpose of the entire sound, typically -24 to 24. Maps to XML `<sound>` transpose
   * attr.
   */
  private int transpose = 0;

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
  }

  public UnisonConfig getUnison() {
    return unison;
  }

  public StutterConfig getStutter() {
    return stutter;
  }

  public ModulationConfig getModulation() {
    return modulation;
  }

  public RawKnobConfig getRawKnobs() {
    return rawKnobs;
  }

  public KeyZoneConfig getKeyZones() {
    return keyZones;
  }

  // Getters and Setters for all fields...
  public String getOsc1Type() {
    return osc1.getType();
  }

  public void setOsc1Type(String osc1Type) {
    osc1.setType(osc1Type);
  }

  public String getOsc2Type() {
    return osc2.getType();
  }

  public void setOsc2Type(String osc2Type) {
    osc2.setType(osc2Type);
  }

  public float getOscMix() {
    return oscMix;
  }

  public void setOscMix(float oscMix) {
    this.oscMix = oscMix;
    osc1.setVolume(oscMix);
    osc2.setVolume(1.0f - oscMix);
  }

  public float getOscAVolume() {
    return osc1.getVolume();
  }

  public void setOscAVolume(float oscAVolume) {
    osc1.setVolume(oscAVolume);
    this.oscMix = oscAVolume;
  }

  public float getOscBVolume() {
    return osc2.getVolume();
  }

  public void setOscBVolume(float oscBVolume) {
    osc2.setVolume(oscBVolume);
  }

  public float getNoiseVol() {
    return noiseVol;
  }

  public void setNoiseVol(float noiseVol) {
    this.noiseVol = noiseVol;
  }

  public float getWaveIndex() {
    return waveIndex;
  }

  public void setWaveIndex(float v) {
    this.waveIndex = v;
  }

  public String getOsc1SamplePath() {
    return osc1.getSamplePath();
  }

  public void setOsc1SamplePath(String p) {
    osc1.setSamplePath(p);
  }

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
    return osc2.getSamplePath();
  }

  public void setOsc2SamplePath(String p) {
    osc2.setSamplePath(p);
  }

  public int getOsc1LoopMode() {
    return osc1.getLoopMode();
  }

  public void setOsc1LoopMode(int v) {
    osc1.setLoopMode(v);
  }

  public boolean isOsc1Reversed() {
    return osc1.isReversed();
  }

  public void setOsc1Reversed(boolean v) {
    osc1.setReversed(v);
  }

  public boolean isOsc1TimeStretch() {
    return osc1.isTimeStretch();
  }

  public void setOsc1TimeStretch(boolean v) {
    osc1.setTimeStretch(v);
  }

  public float getOsc1TimeStretchAmount() {
    return osc1.getTimeStretchAmount();
  }

  public void setOsc1TimeStretchAmount(float v) {
    osc1.setTimeStretchAmount(v);
  }

  public int getOsc1Cents() {
    return osc1.getCents();
  }

  public void setOsc1Cents(int v) {
    osc1.setCents(Math.max(-50, Math.min(50, v)));
  }

  public int getOsc1Transpose() {
    return osc1.getTranspose();
  }

  public void setOsc1Transpose(int v) {
    osc1.setTranspose(Math.max(-24, Math.min(24, v)));
  }

  public boolean isOsc1LinearInterpolation() {
    return osc1.isLinearInterpolation();
  }

  public void setOsc1LinearInterpolation(boolean v) {
    osc1.setLinearInterpolation(v);
  }

  public int getOsc2Transpose() {
    return osc2.getTranspose();
  }

  public void setOsc2Transpose(int v) {
    osc2.setTranspose(Math.max(-24, Math.min(24, v)));
  }

  public int getOsc2Cents() {
    return osc2.getCents();
  }

  public void setOsc2Cents(int v) {
    osc2.setCents(Math.max(-50, Math.min(50, v)));
  }

  public int getOsc2LoopMode() {
    return osc2.getLoopMode();
  }

  public void setOsc2LoopMode(int v) {
    osc2.setLoopMode(v);
  }

  public boolean isOsc2Reversed() {
    return osc2.isReversed();
  }

  public void setOsc2Reversed(boolean v) {
    osc2.setReversed(v);
  }

  public boolean isOsc2TimeStretch() {
    return osc2.isTimeStretch();
  }

  public void setOsc2TimeStretch(boolean v) {
    osc2.setTimeStretch(v);
  }

  public float getOsc2TimeStretchAmount() {
    return osc2.getTimeStretchAmount();
  }

  public void setOsc2TimeStretchAmount(float v) {
    osc2.setTimeStretchAmount(v);
  }

  public boolean isOsc2LinearInterpolation() {
    return osc2.isLinearInterpolation();
  }

  public void setOsc2LinearInterpolation(boolean v) {
    osc2.setLinearInterpolation(v);
  }

  public FilterMode getFilterMode() {
    return filter.getFilterMode();
  }

  public void setFilterMode(FilterMode filterMode) {
    filter.setFilterMode(filterMode);
  }

  public float getLpfFreq() {
    return filter.getLpfFreq();
  }

  public void setLpfFreq(float lpfFreq) {
    filter.setLpfFreq(lpfFreq);
  }

  public float getLpfRes() {
    return filter.getLpfRes();
  }

  public void setLpfRes(float lpfRes) {
    filter.setLpfRes(lpfRes);
  }

  public float getLpfMorph() {
    return filter.getLpfMorph();
  }

  public void setLpfMorph(float lpfMorph) {
    filter.setLpfMorph(lpfMorph);
  }

  public float getHpfFreq() {
    return filter.getHpfFreq();
  }

  public void setHpfFreq(float hpfFreq) {
    filter.setHpfFreq(hpfFreq);
  }

  public float getHpfRes() {
    return filter.getHpfRes();
  }

  public void setHpfRes(float hpfRes) {
    filter.setHpfRes(hpfRes);
  }

  public float getHpfMorph() {
    return filter.getHpfMorph();
  }

  public void setHpfMorph(float hpfMorph) {
    filter.setHpfMorph(hpfMorph);
  }

  public FilterMode getHpfMode() {
    return filter.getHpfMode();
  }

  public void setHpfMode(FilterMode hpfMode) {
    filter.setHpfMode(hpfMode);
  }

  public float getHpfFm() {
    return filter.getHpfFm();
  }

  public void setHpfFm(float hpfFm) {
    filter.setHpfFm(hpfFm);
  }

  public float getFilterDrive() {
    return filter.getFilterDrive();
  }

  public void setFilterDrive(float filterDrive) {
    filter.setFilterDrive(Math.max(0.0f, Math.min(2.0f, filterDrive)));
  }

  public boolean isFilterNotch() {
    return filter.isFilterNotch();
  }

  public void setFilterNotch(boolean filterNotch) {
    filter.setFilterNotch(filterNotch);
  }

  public int getFilterRoute() {
    return filter.getFilterRoute();
  }

  public void setFilterRoute(int filterRoute) {
    filter.setFilterRoute(filterRoute);
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

  public String getModFxType() {
    return fx.getModFxType();
  }

  public void setModFxType(String modFxType) {
    fx.setModFxType(modFxType != null ? modFxType.toUpperCase() : null);
  }

  public float getModFxRate() {
    return fx.getModFxRate();
  }

  public void setModFxRate(float modFxRate) {
    fx.setModFxRate(modFxRate);
  }

  public float getModFxDepth() {
    return fx.getModFxDepth();
  }

  public void setModFxDepth(float modFxDepth) {
    fx.setModFxDepth(modFxDepth);
  }

  public float getModFxFeedback() {
    return fx.getModFxFeedback();
  }

  public void setModFxFeedback(float modFxFeedback) {
    fx.setModFxFeedback(modFxFeedback);
  }

  public float getModFxOffset() {
    return fx.getModFxOffset();
  }

  public void setModFxOffset(float modFxOffset) {
    fx.setModFxOffset(modFxOffset);
  }

  public float getDelaySend() {
    return fx.getDelaySend();
  }

  public void setDelaySend(float delaySend) {
    fx.setDelaySend(delaySend);
  }

  public float getReverbSend() {
    return fx.getReverbSend();
  }

  public void setReverbSend(float reverbSend) {
    fx.setReverbSend(reverbSend);
  }

  public int getDelaySyncLevel() {
    return fx.getDelaySyncLevel();
  }

  public void setDelaySyncLevel(int v) {
    fx.setDelaySyncLevel(v);
  }

  public int getDelaySyncType() {
    return fx.getDelaySyncType();
  }

  public void setDelaySyncType(int v) {
    fx.setDelaySyncType(v);
  }

  public int getDelayFeedbackQ31() {
    return fx.getDelayFeedbackQ31();
  }

  public void setDelayFeedbackQ31(int v) {
    fx.setDelayFeedbackQ31(v);
  }

  public boolean isDelayPingPong() {
    return fx.isDelayPingPong();
  }

  public void setDelayPingPong(boolean v) {
    fx.setDelayPingPong(v);
  }

  public boolean isDelayAnalog() {
    return fx.isDelayAnalog();
  }

  public void setDelayAnalog(boolean v) {
    fx.setDelayAnalog(v);
  }

  public float getEqBass() {
    return fx.getEqBass();
  }

  public void setEqBass(float eqBass) {
    fx.setEqBass(eqBass);
  }

  public float getEqTreble() {
    return fx.getEqTreble();
  }

  public void setEqTreble(float eqTreble) {
    fx.setEqTreble(eqTreble);
  }

  @Override
  public float getVolume() {
    return volume;
  }

  @Override
  public void setVolume(float v) {
    this.volume = v;
  }

  public int getModKnobMode() {
    return modKnobMode;
  }

  public void setModKnobMode(int mode) {
    this.modKnobMode = Math.max(0, Math.min(7, mode));
  }

  @Override
  public float getPan() {
    return pan;
  }

  @Override
  public void setPan(float v) {
    this.pan = v;
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
    return compressor.getCompressorAttack();
  }

  public void setCompressorAttack(float v) {
    compressor.setCompressorAttack(v);
  }

  public float getCompressorRelease() {
    return compressor.getCompressorRelease();
  }

  public void setCompressorRelease(float v) {
    compressor.setCompressorRelease(v);
  }

  public int getCompressorSyncLevel() {
    return compressor.getCompressorSyncLevel();
  }

  public void setCompressorSyncLevel(int v) {
    compressor.setCompressorSyncLevel(v);
  }

  public float getCompressorBlend() {
    return compressor.getCompressorBlend();
  }

  public void setCompressorBlend(float v) {
    compressor.setCompressorBlend(Math.max(0.0f, Math.min(1.0f, v)));
  }

  public float getCompressorSidechainHpf() {
    return compressor.getCompressorSidechainHpf();
  }

  public void setCompressorSidechainHpf(float v) {
    compressor.setCompressorSidechainHpf(Math.max(0.0f, Math.min(1.0f, v)));
  }

  public float getCompressorThreshold() {
    return compressor.getCompressorThreshold();
  }

  public void setCompressorThreshold(float v) {
    compressor.setCompressorThreshold(v);
  }

  public float getCompressorRatio() {
    return compressor.getCompressorRatio();
  }

  public void setCompressorRatio(float v) {
    compressor.setCompressorRatio(v);
  }

  public float getCompressorShape() {
    return compressor.getCompressorShape();
  }

  public void setCompressorShape(float v) {
    compressor.setCompressorShape(v);
  }

  public int getCompressorSyncType() {
    return compressor.getCompressorSyncType();
  }

  public void setCompressorSyncType(int v) {
    compressor.setCompressorSyncType(v);
  }

  // Sidechain (at sound level)
  public int getSidechainSyncLevel() {
    return compressor.getSidechainSyncLevel();
  }

  public void setSidechainSyncLevel(int v) {
    compressor.setSidechainSyncLevel(v);
  }

  public int getSidechainSyncType() {
    return compressor.getSidechainSyncType();
  }

  public void setSidechainSyncType(int v) {
    compressor.setSidechainSyncType(v);
  }

  public float getSidechainAttack() {
    return compressor.getSidechainAttack();
  }

  public void setSidechainAttack(float v) {
    compressor.setSidechainAttack(v);
  }

  public float getSidechainRelease() {
    return compressor.getSidechainRelease();
  }

  public void setSidechainRelease(float v) {
    compressor.setSidechainRelease(v);
  }

  public int getSidechainAttackRaw() {
    return compressor.getSidechainAttackRaw();
  }

  public void setSidechainAttackRaw(int v) {
    compressor.setSidechainAttackRaw(v);
  }

  public int getSidechainReleaseRaw() {
    return compressor.getSidechainReleaseRaw();
  }

  public void setSidechainReleaseRaw(int v) {
    compressor.setSidechainReleaseRaw(v);
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
    return fm.getDx7patch();
  }

  public void setDx7Patch(String dx7patch) {
    fm.setDx7patch(dx7patch);
  }

  public int getDx7RandomDetune() {
    return fm.getDx7RandomDetune();
  }

  public void setDx7RandomDetune(int v) {
    fm.setDx7RandomDetune(v);
  }

  public int getEngineType() {
    return fm.getEngineType();
  }

  public void setEngineType(int engineType) {
    fm.setEngineType(engineType);
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
    return fm.getFmRatio();
  }

  public void setFmRatio(float fmRatio) {
    fm.setFmRatio(fmRatio);
  }

  public int getModulator1Transpose() {
    return fm.getModulator1Transpose();
  }

  public void setModulator1Transpose(int v) {
    fm.setModulator1Transpose(v);
  }

  public int getModulator1Cents() {
    return fm.getModulator1Cents();
  }

  public void setModulator1Cents(int v) {
    fm.setModulator1Cents(v);
  }

  public int getModulator2Transpose() {
    return fm.getModulator2Transpose();
  }

  public void setModulator2Transpose(int v) {
    fm.setModulator2Transpose(v);
  }

  public int getModulator2Cents() {
    return fm.getModulator2Cents();
  }

  public void setModulator2Cents(int v) {
    fm.setModulator2Cents(v);
  }

  public float getFmAmount() {
    return fm.getFmAmount();
  }

  public void setFmAmount(float fmAmount) {
    fm.setFmAmount(fmAmount);
  }

  public float getModulator1Feedback() {
    return fm.getModulator1Feedback();
  }

  public void setModulator1Feedback(float v) {
    fm.setModulator1Feedback(v);
  }

  public float getModulator2Amount() {
    return fm.getModulator2Amount();
  }

  public void setModulator2Amount(float v) {
    fm.setModulator2Amount(v);
  }

  public float getModulator2Feedback() {
    return fm.getModulator2Feedback();
  }

  public void setModulator2Feedback(float v) {
    fm.setModulator2Feedback(v);
  }

  public float getCarrier1Feedback() {
    return fm.getCarrier1Feedback();
  }

  public void setCarrier1Feedback(float v) {
    fm.setCarrier1Feedback(v);
  }

  public float getCarrier2Feedback() {
    return fm.getCarrier2Feedback();
  }

  public void setCarrier2Feedback(float v) {
    fm.setCarrier2Feedback(v);
  }

  public float getFmRatio2() {
    return fm.getFmRatio2();
  }

  public void setFmRatio2(float v) {
    fm.setFmRatio2(v);
  }

  public int getModulator1AmountQ31() {
    return fm.getModulator1AmountQ31();
  }

  public void setModulator1AmountQ31(int v) {
    fm.setModulator1AmountQ31(v);
  }

  public int getModulator2AmountQ31() {
    return fm.getModulator2AmountQ31();
  }

  public void setModulator2AmountQ31(int v) {
    fm.setModulator2AmountQ31(v);
  }

  public int getPitchAdjustQ31() {
    return pitchAdjustQ31;
  }

  public void setPitchAdjustQ31(int v) {
    this.pitchAdjustQ31 = v;
  }

  public int getOsc1PhaseWidthQ31() {
    return osc1.getPhaseWidthQ31();
  }

  public void setOsc1PhaseWidthQ31(int v) {
    osc1.setPhaseWidthQ31(v);
  }

  public int getOsc2PhaseWidthQ31() {
    return osc2.getPhaseWidthQ31();
  }

  public void setOsc2PhaseWidthQ31(int v) {
    osc2.setPhaseWidthQ31(v);
  }

  public int getModulator1FeedbackQ31() {
    return fm.getModulator1FeedbackQ31();
  }

  public void setModulator1FeedbackQ31(int v) {
    fm.setModulator1FeedbackQ31(v);
  }

  public int getModulator2FeedbackQ31() {
    return fm.getModulator2FeedbackQ31();
  }

  public void setModulator2FeedbackQ31(int v) {
    fm.setModulator2FeedbackQ31(v);
  }

  public int getCarrier1FeedbackQ31() {
    return fm.getCarrier1FeedbackQ31();
  }

  public void setCarrier1FeedbackQ31(int v) {
    fm.setCarrier1FeedbackQ31(v);
  }

  public int getCarrier2FeedbackQ31() {
    return fm.getCarrier2FeedbackQ31();
  }

  public void setCarrier2FeedbackQ31(int v) {
    fm.setCarrier2FeedbackQ31(v);
  }

  public boolean isModulator1ToModulator0() {
    return fm.isModulator1ToModulator0();
  }

  public void setModulator1ToModulator0(boolean v) {
    fm.setModulator1ToModulator0(v);
  }

  // Stutter config (ModControllableAudio::stutterConfig)
  private boolean stutterQuantized = true;
  private boolean stutterReversed = false;
  private boolean stutterPingPong = false;

  public int getOsc1RetrigPhase() {
    return osc1.getRetrigPhase();
  }

  public void setOsc1RetrigPhase(int v) {
    osc1.setRetrigPhase(v);
  }

  public int getOsc2RetrigPhase() {
    return osc2.getRetrigPhase();
  }

  public void setOsc2RetrigPhase(int v) {
    osc2.setRetrigPhase(v);
  }

  public int getMod1RetrigPhase() {
    return fm.getMod1RetrigPhase();
  }

  public void setMod1RetrigPhase(int v) {
    fm.setMod1RetrigPhase(v);
  }

  public int getMod2RetrigPhase() {
    return fm.getMod2RetrigPhase();
  }

  public void setMod2RetrigPhase(int v) {
    fm.setMod2RetrigPhase(v);
  }

  public int getRetrigPhase() {
    return osc1.getRetrigPhase();
  }

  public void setRetrigPhase(int v) {
    osc1.setRetrigPhase(v);
    osc2.setRetrigPhase(v);
  }

  public PolyphonyMode getPolyphony() {
    return polyphony;
  }

  public void setPolyphony(PolyphonyMode polyphony) {
    this.polyphony = polyphony;
  }

  /**
   * Copies all synthesis, oscillator, filter, LFO, envelope, and FX parameters from another model.
   */
  public void copyParametersFrom(SynthTrackModel other) {
    this.osc1.copyFrom(other.getOsc1());
    this.osc2.copyFrom(other.getOsc2());
    this.osc1RawXml = other.osc1RawXml;
    this.osc2RawXml = other.osc2RawXml;
    this.oscMix = other.getOscMix();
    this.noiseVol = other.getNoiseVol();
    this.unison.copyFrom(other.getUnison());
    this.waveIndex = other.getWaveIndex();
    this.filter.copyFrom(other.getFilter());

    // Arrays
    for (int i = 0; i < 4; i++) {
      this.env[i] = other.getEnv(i);
      this.lfo[i] = other.getLfo(i);
    }

    // FM
    this.synthMode = other.getSynthMode();
    this.fm.copyFrom(other.getFm());
    this.pitchAdjustQ31 = other.pitchAdjustQ31;
    this.waveFoldQ31 = other.getWaveFoldQ31();
    this.setClippingAmount(other.getClippingAmount());
    this.portamentoQ31 = other.getPortamentoQ31();
    this.polyphony = other.getPolyphony();
    this.maxVoiceCount = other.getMaxVoiceCount();
    this.voicePriority = other.getVoicePriority();
    this.modKnobMode = other.getModKnobMode();
    this.transpose = other.getTranspose();
    this.synthAlgorithm = other.getSynthAlgorithm();
    this.oscillatorSync = other.oscillatorSync;

    // New configs
    this.stutter.copyFrom(other.getStutter());
    this.modulation.copyFrom(other.getModulation());
    this.rawKnobs.copyFrom(other.getRawKnobs());
    this.keyZones.copyFrom(other.getKeyZones());

    // FX
    this.volume = other.getVolume();
    this.pan = other.getPan();
    this.sampleRateReduction = other.getSampleRateReduction();
    this.bitCrush = other.getBitCrush();
    this.compressor.copyFrom(other.getCompressor());
    this.fx.copyFrom(other.getFx());
    System.arraycopy(other.getCustomLfoWave(), 0, this.customLfoWave, 0, 256);
  }

  public int[] getCustomLfoWave() {
    return customLfoWave;
  }

  public float getArpRate() {
    return this.arp != null ? this.arp.rate() : 0.0f;
  }

  public void setArpRate(float rate) {
    if (this.arp != null) {
      this.arp = this.arp.toBuilder().rate(rate).build();
    }
  }

  private final KeyZoneConfig keyZones = new KeyZoneConfig();
}
