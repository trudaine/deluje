package org.chuck.deluge.model;

import java.util.ArrayList;
import java.util.List;

/** Represents a Kit track containing multiple distinct drum sounds (samples). */
public class KitTrackModel extends TrackModel {

  public static class KitSound {
    private String name = "";
    private String samplePath = "";
    private boolean reverse = false;
    private float startMs = 0.0f;
    private float endMs = 0.0f;
    private int startSamplePos = -1;  // <startSamplePos> from XML zone, -1 = use startMs
    private int endSamplePos = -1;    // <endSamplePos> from XML zone, -1 = use endMs
    private float pitchSemitones = 0.0f;
    private int muteGroup = 0;

    // Per-sound shaping
    private EnvelopeModel adsr = EnvelopeModel.defaultConfig();
    private float lpfFreq = 20000.0f;
    private float lpfRes = 0.0f;

    private float eqBass = 0.0f;
    private float eqTreble = 0.0f;
    private float sidechainSend = 0.0f;

    // ── New synth-parity fields ──

    // Oscillator
    private String osc2Type = "NONE";
    private String osc2SamplePath = "";
    private int osc2StartSamplePos = -1;
    private int osc2EndSamplePos = -1;

    // Envelopes 2-4
    private EnvelopeModel env2 = EnvelopeModel.defaultConfig();
    private EnvelopeModel env3 = EnvelopeModel.defaultConfig();
    private EnvelopeModel env4 = EnvelopeModel.defaultConfig();

    // LFOs (per-sound)
    private LfoModel lfo1 = LfoModel.defaultConfig(true);
    private LfoModel lfo2 = LfoModel.defaultConfig(false);

    // Delay per-sound
    private float delayRate = 0.0f;
    private float delayFeedback = 0.0f;

    // Mod knobs & patch cables
    private final List<ModKnob> modKnobs = new ArrayList<>(16);
    private final List<PatchCable> patchCables = new ArrayList<>();

    // Polyphonic, voice priority, clipping
    private SynthTrackModel.PolyphonyMode polyphony = SynthTrackModel.PolyphonyMode.POLY;
    private int voicePriority = 1;
    private float clippingAmount = 0.0f;

    // Unison
    private int unisonNum = 1;
    private float unisonDetune = 0.0f;
    private float unisonStereoSpread = 0.0f;

    // Compressor per-sound
    private float compressorAttack = 0.0f;
    private float compressorRelease = 0.0f;
    private int compressorSyncLevel = 0;
    private float compressorBlend = 0.0f;
    private float compressorSidechainHpf = 0.0f;

    // LPF Mode
    private FilterMode lpfMode = FilterMode.LADDER_12;
    private float lpfDrive = 1.0f;
    private boolean lpfNotch = false;
    private int maxVoiceCount = 8;

    // Mod FX
    private String modFxType = "NONE";
    private float modFxRate = 0.3f;
    private float modFxDepth = 0.3f;
    private float modFxOffset = 0.0f;
    private float modFxFeedback = 0.0f;

    // HPF
    private float hpfFreq = 20.0f;
    private float hpfRes = 0.0f;
    private float hpfMorph = 0.0f;
    private FilterMode hpfMode = FilterMode.LADDER_12;

    /** Oscillator retrigger phase. -1=FREE, 0=RESET, positive=phase offset in degrees. */
    private int retrigPhase = 0;

    /** Wavetable position index (0.0–1.0), applies only to wavetable-type oscillator. */
    private float waveIndex = 0.0f;

    // Default params values
    private float volume = 0.5f;
    private float pan = 0.0f;
    private float oscAVolume = 1.0f;
    private float oscBVolume = 0.0f;
    private float noiseVolume = 0.0f;
    private float arpeggiatorGate = 0.0f;
    private float portamento = 0.0f;
    private float stutterRate = 0.0f;
    private float sampleRateReduction = 0.0f;
    private float bitCrush = 0.0f;
    private float fmAmount = 0.0f;
    private float reverbAmount = 0.0f;

    public KitSound(String name) {
      this.name = name;
      for (int i = 0; i < 16; i++) {
        modKnobs.add(ModKnob.empty());
      }
    }

    public KitSound(String name, String samplePath) {
      this.name = name;
      this.samplePath = samplePath;
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

    public String getSamplePath() {
      return samplePath;
    }

    public void setSamplePath(String samplePath) {
      this.samplePath = samplePath;
    }

    public boolean isReverse() {
      return reverse;
    }

    public void setReverse(boolean reverse) {
      this.reverse = reverse;
    }

    public float getStartMs() {
      return startMs;
    }

    public void setStartMs(float startMs) {
      this.startMs = startMs;
    }

    public float getEndMs() {
      return endMs;
    }

    public void setEndMs(float endMs) {
      this.endMs = endMs;
    }

    public int getStartSamplePos() {
      return startSamplePos;
    }

    public void setStartSamplePos(int pos) {
      this.startSamplePos = pos;
    }

    public int getEndSamplePos() {
      return endSamplePos;
    }

    public void setEndSamplePos(int pos) {
      this.endSamplePos = pos;
    }

    public float getPitchSemitones() {
      return pitchSemitones;
    }

    public void setPitchSemitones(float pitchSemitones) {
      this.pitchSemitones = pitchSemitones;
    }

    public int getMuteGroup() {
      return muteGroup;
    }

    public void setMuteGroup(int muteGroup) {
      this.muteGroup = muteGroup;
    }

    public EnvelopeModel getAdsr() {
      return adsr;
    }

    public void setAdsr(EnvelopeModel adsr) {
      this.adsr = adsr;
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

    public float getSidechainSend() {
      return sidechainSend;
    }

    public void setSidechainSend(float sidechainSend) {
      this.sidechainSend = sidechainSend;
    }

    // ── New field getters/setters ──

    public String getOsc2Type() { return osc2Type; }
    public void setOsc2Type(String osc2Type) { this.osc2Type = osc2Type; }
    public String getOsc2SamplePath() { return osc2SamplePath; }
    public void setOsc2SamplePath(String v) { this.osc2SamplePath = v; }
    public int getOsc2StartSamplePos() { return osc2StartSamplePos; }
    public void setOsc2StartSamplePos(int v) { this.osc2StartSamplePos = v; }
    public int getOsc2EndSamplePos() { return osc2EndSamplePos; }
    public void setOsc2EndSamplePos(int v) { this.osc2EndSamplePos = v; }

    public EnvelopeModel getEnv2() { return env2; }
    public void setEnv2(EnvelopeModel env2) { this.env2 = env2; }
    public EnvelopeModel getEnv3() { return env3; }
    public void setEnv3(EnvelopeModel env3) { this.env3 = env3; }
    public EnvelopeModel getEnv4() { return env4; }
    public void setEnv4(EnvelopeModel env4) { this.env4 = env4; }

    public LfoModel getLfo1() { return lfo1; }
    public void setLfo1(LfoModel lfo1) { this.lfo1 = lfo1; }
    public LfoModel getLfo2() { return lfo2; }
    public void setLfo2(LfoModel lfo2) { this.lfo2 = lfo2; }

    public float getDelayRate() { return delayRate; }
    public void setDelayRate(float v) { this.delayRate = v; }
    public float getDelayFeedback() { return delayFeedback; }
    public void setDelayFeedback(float v) { this.delayFeedback = v; }

    public List<ModKnob> getModKnobs() { return modKnobs; }
    public void setModKnob(int index, ModKnob knob) { this.modKnobs.set(index, knob); }
    public List<PatchCable> getPatchCables() { return patchCables; }
    public void addPatchCable(PatchCable cable) { this.patchCables.add(cable); }

    public SynthTrackModel.PolyphonyMode getPolyphony() { return polyphony; }
    public void setPolyphony(SynthTrackModel.PolyphonyMode v) { this.polyphony = v; }
    public boolean isPolyphonic() { return polyphony != SynthTrackModel.PolyphonyMode.MONO; }
    public void setPolyphonic(boolean v) { this.polyphony = v ? SynthTrackModel.PolyphonyMode.POLY : SynthTrackModel.PolyphonyMode.MONO; }
    public int getVoicePriority() { return voicePriority; }
    public void setVoicePriority(int v) { this.voicePriority = v; }
    public float getClippingAmount() { return clippingAmount; }
    public void setClippingAmount(float v) { this.clippingAmount = v; }

    public int getUnisonNum() { return unisonNum; }
    public void setUnisonNum(int v) { this.unisonNum = v; }
    public float getUnisonDetune() { return unisonDetune; }
    public void setUnisonDetune(float v) { this.unisonDetune = v; }
    public float getUnisonStereoSpread() { return unisonStereoSpread; }
    public void setUnisonStereoSpread(float v) { this.unisonStereoSpread = v; }

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

    public FilterMode getLpfMode() { return lpfMode; }
    public void setLpfMode(FilterMode v) { this.lpfMode = v; }
    public float getLpfDrive() { return lpfDrive; }
    public void setLpfDrive(float v) { this.lpfDrive = Math.max(0.0f, Math.min(2.0f, v)); }
    public boolean isLpfNotch() { return lpfNotch; }
    public void setLpfNotch(boolean v) { this.lpfNotch = v; }
    public int getMaxVoiceCount() { return maxVoiceCount; }
    public void setMaxVoiceCount(int v) { this.maxVoiceCount = Math.max(1, Math.min(16, v)); }

    public String getModFxType() { return modFxType; }
    public void setModFxType(String v) { this.modFxType = v; }
    public float getModFxRate() { return modFxRate; }
    public void setModFxRate(float v) { this.modFxRate = v; }
    public float getModFxDepth() { return modFxDepth; }
    public void setModFxDepth(float v) { this.modFxDepth = v; }
    public float getModFxOffset() { return modFxOffset; }
    public void setModFxOffset(float v) { this.modFxOffset = v; }
    public float getModFxFeedback() { return modFxFeedback; }
    public void setModFxFeedback(float v) { this.modFxFeedback = v; }

    public float getHpfFreq() { return hpfFreq; }
    public void setHpfFreq(float v) { this.hpfFreq = v; }
    public float getHpfRes() { return hpfRes; }
    public void setHpfRes(float v) { this.hpfRes = v; }
    public float getHpfMorph() { return hpfMorph; }
    public void setHpfMorph(float v) { this.hpfMorph = v; }
    public FilterMode getHpfMode() { return hpfMode; }
    public void setHpfMode(FilterMode v) { this.hpfMode = v; }

    public int getRetrigPhase() { return retrigPhase; }
    public void setRetrigPhase(int v) { this.retrigPhase = v; }

    public float getWaveIndex() { return waveIndex; }
    public void setWaveIndex(float v) { this.waveIndex = v; }

    public float getVolume() { return volume; }
    public void setVolume(float v) { this.volume = v; }
    public float getPan() { return pan; }
    public void setPan(float v) { this.pan = v; }
    public float getOscAVolume() { return oscAVolume; }
    public void setOscAVolume(float v) { this.oscAVolume = v; }
    public float getOscBVolume() { return oscBVolume; }
    public void setOscBVolume(float v) { this.oscBVolume = v; }
    public float getNoiseVolume() { return noiseVolume; }
    public void setNoiseVolume(float v) { this.noiseVolume = v; }
    public float getArpeggiatorGate() { return arpeggiatorGate; }
    public void setArpeggiatorGate(float v) { this.arpeggiatorGate = v; }
    public float getPortamento() { return portamento; }
    public void setPortamento(float v) { this.portamento = v; }
    public float getStutterRate() { return stutterRate; }
    public void setStutterRate(float v) { this.stutterRate = v; }
    public float getSampleRateReduction() { return sampleRateReduction; }
    public void setSampleRateReduction(float v) { this.sampleRateReduction = v; }
    public float getBitCrush() { return bitCrush; }
    public void setBitCrush(float v) { this.bitCrush = v; }
    public float getFmAmount() { return fmAmount; }
    public void setFmAmount(float v) { this.fmAmount = v; }
    public float getReverbAmount() { return reverbAmount; }
    public void setReverbAmount(float v) { this.reverbAmount = v; }
  }

  private final List<KitSound> sounds = new ArrayList<>();

  public KitTrackModel(String name) {
    super(name, TrackType.KIT);
  }

  public List<KitSound> getSounds() {
    return sounds;
  }

  public void addSound(KitSound sound) {
    sounds.add(sound);
  }

  public String getSamplePath() {
    return sounds.isEmpty() ? "" : sounds.get(0).getSamplePath();
  }
}
