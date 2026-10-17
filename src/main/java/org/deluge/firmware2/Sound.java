package org.deluge.firmware2;

import java.util.ArrayList;
import org.deluge.firmware2.FilterSet.FilterMode;
import org.deluge.firmware2.Lfo.LfoConfig;
import org.deluge.firmware2.Oscillator.OscType;

/**
 * Replica of the Deluge C++ Sound class configuration. Self-contained in firmware2 package with
 * zero dependencies on legacy firmware.
 */
public class Sound extends GlobalEffectable {
  /**
   * Optional engine-supplied microtonal tuning. Null = faithful C behavior (static LookupTables).
   * Kept as a neutral firmware2 interface so the port has no upward dependency on firmware.model.
   */
  public TuningProvider tuning;

  public static final int kMaxNumVoicesUnison = 8;

  public int synthMode = 0; // 0=subtractive, 1=FM, 2=ringmod
  public int voicePriority = 1; // 0=low, 1=medium, 2=high
  public final OscType[] oscTypes = {OscType.SINE, OscType.SINE};
  public final WaveTable[] waveTables = new WaveTable[2];
  public FilterMode lpfMode = FilterMode.OFF;
  public FilterMode hpfMode = FilterMode.OFF;
  public int filterRoute = 0;
  public int numUnison = 1;
  public int unisonDetune = 8;
  public int unisonStereoSpread = 0;
  public final PhaseIncrementFineTuner[] unisonDetuners =
      new PhaseIncrementFineTuner[kMaxNumVoicesUnison];
  public final int[] unisonPan = new int[kMaxNumVoicesUnison];
  public int volumeNeutralValueForUnison = 134217728;
  public boolean modulator1ToModulator0 = false;
  public float fmRatio1 = 1.0f;
  public float fmRatio2 = 1.0f;

  /** C: sound.h:143 — osc B hard-syncs to osc A when true. */
  public boolean oscillatorSync = false;

  /** C: mod_controllable_audio.h:107 — per-sound saturation/clipping amount; 0 = off. */
  public final int[] customLfoWave = new int[256];

  /** C: UNPATCHED_PORTAMENTO knob (raw Q31); INT_MIN = off. */
  public int portamentoKnob = Integer.MIN_VALUE;

  /** C: sound.h:141 — previous note code, stored for portamento; INT_MIN = none yet. */
  public int lastNoteCode = Integer.MIN_VALUE;

  /** C: sound.h:286 — saturation output left-shift derived from clippingAmount. */
  @Override
  public int getShiftAmountForSaturation() {
    return (clippingAmount >= 2) ? (clippingAmount - 2) : 0;
  }

  /** A Sound saturates per-voice (voice.cpp:1553-1565), never at track level. */
  @Override
  protected boolean hasTrackLevelSaturation() {
    return false;
  }

  private int[][] compScratch;

  /**
   * C sound.cpp:2591-2600 — the per-sound compressor runs AFTER processReverbSendAndVolume, on the
   * volume-applied signal, with the postFX volume as its neutral reference.
   */
  @Override
  public void processPostVolumeDynamics(int[] interleaved, int numSamples, int postFXVolume) {
    int compThreshold = patchedParamValues[Param.UNPATCHED_COMPRESSOR_THRESHOLD];
    compressor.setThreshold(compThreshold);
    if (compThreshold <= 0) {
      compressor.reset();
      return;
    }
    if (compScratch == null || compScratch.length < numSamples) {
      compScratch = new int[numSamples][2];
    }
    for (int i = 0; i < numSamples; i++) {
      compScratch[i][0] = interleaved[i * 2];
      compScratch[i][1] = interleaved[i * 2 + 1];
    }
    compressor.renderVolNeutral(compScratch, numSamples, postFXVolume);
    for (int i = 0; i < numSamples; i++) {
      interleaved[i * 2] = compScratch[i][0];
      interleaved[i * 2 + 1] = compScratch[i][1];
    }
  }

  /** C: sound.h:156 — per-source retrigger phase; 0xFFFFFFFF means "off" (sound.cpp:88). */
  public final int[] oscRetriggerPhase = {0xFFFFFFFF, 0xFFFFFFFF};

  /** C: sound.h:157 — per-modulator retrigger phase; 0xFFFFFFFF means "off". */
  public final int[] modulatorRetriggerPhase = {0xFFFFFFFF, 0xFFFFFFFF};

  /**
   * C: sound.cpp:2112-2121 — osc sync renders only when enabled, not FM, and osc B is audible (its
   * volume KNOB isn't off) or we're in ringmod.
   */
  public boolean renderingOscillatorSyncCurrently() {
    if (!oscillatorSync) {
      return false;
    }
    if (synthMode == 1) { // FM
      return false;
    }
    return patchedParamValues[Param.LOCAL_OSC_B_VOLUME] != Integer.MIN_VALUE || synthMode == 2;
  }

  /** C: sound.cpp:2135-2144 — true if oscillator sync can ever render on this sound. */
  public boolean renderingOscillatorSyncEver() {
    if (!oscillatorSync) {
      return false;
    }
    if (synthMode == 1) { // FM
      return false;
    }
    return patchedParamValues[Param.LOCAL_OSC_B_VOLUME] != Integer.MIN_VALUE
        || doesParamHaveSomethingPatchedToIt(Param.LOCAL_OSC_B_VOLUME)
        || synthMode == 2;
  }

  /** C PatchCableSet::doesParamHaveSomethingPatchedToIt — any real cable lands on this param. */
  public boolean doesParamHaveSomethingPatchedToIt(int p) {
    java.util.List<Patcher.Destination> dests = patchCableSet.destinations;
    for (int i = 0; i < dests.size(); i++) {
      Patcher.Destination d = dests.get(i);
      if (d.paramId == p && d.targetSource < 0 && !d.cables.isEmpty()) {
        return true;
      }
    }
    return false;
  }

  /**
   * envelopeHasSustainCurrently (sound.cpp:4320-4328). Params fetched "pre-LPF" — the raw patched
   * (knob) values, not paramFinalValues.
   */
  public boolean envelopeHasSustainCurrently(int e) {
    return patchedParamValues[Param.LOCAL_ENV_0_SUSTAIN + e] != -2147483648
        || patchedParamValues[Param.LOCAL_ENV_0_DECAY + e]
            > patchedParamValues[Param.LOCAL_ENV_0_RELEASE + e];
  }

  /** C: sound.cpp:4367-4376 — true if envelope {@code e} can ever sustain. */
  public boolean envelopeHasSustainEver(int e) {
    return patchedParamValues[Param.LOCAL_ENV_0_SUSTAIN + e] != Integer.MIN_VALUE
        || doesParamHaveSomethingPatchedToIt(Param.LOCAL_ENV_0_SUSTAIN + e)
        || patchedParamValues[Param.LOCAL_ENV_0_DECAY + e]
            > patchedParamValues[Param.LOCAL_ENV_0_RELEASE + e];
  }

  /**
   * C: sound.cpp:1927-1964 — returns true unless all active sources are play-once samples (without
   * a sustain loop-end point when {@code !disregardSampleLoop}) or envelope 0 has no sustain.
   */
  public boolean allowNoteTails(boolean disregardSampleLoop) {
    if (arpEnabled()) {
      return true;
    }
    if (!envelopeHasSustainEver(0)) {
      return false;
    }
    if (synthMode != 0
        || patchedParamValues[Param.LOCAL_NOISE_VOLUME] != Integer.MIN_VALUE
        || doesParamHaveSomethingPatchedToIt(Param.LOCAL_NOISE_VOLUME)) {
      return true;
    }
    boolean anyActiveSources = false;
    for (int s = 0; s < 2; s++) {
      boolean sourceEverActive = isSourceActiveEver(s, hasAnySampleLoaded(s));
      anyActiveSources = sourceEverActive || anyActiveSources;
      boolean hasLoopEnd = sampleLoopEnd[s] > 0;
      if (!hasLoopEnd) {
        for (CompiledKeyZone kz : sourceZones[s]) {
          if (kz.endLoopPos > 0) {
            hasLoopEnd = true;
            break;
          }
        }
      }
      if (sourceEverActive
          && (oscTypes[s] != OscType.SAMPLE
              || sampleLoopMode[s] != 2
              || (!disregardSampleLoop && hasLoopEnd))) {
        return true;
      }
    }
    return !anyActiveSources;
  }

  /** C: Source::hasAtLeastOneAudioFileLoaded (source.cpp:324-334). */
  public boolean hasAnySampleLoaded(int s) {
    if (samples[s] != null) {
      return true;
    }
    for (CompiledKeyZone kz : sourceZones[s]) {
      if (kz.sample != null) {
        return true;
      }
    }
    return false;
  }

  /** FM modulator transpose in semitones (voice.cpp modulatorTranspose[m]). */
  public final int[] modulatorTranspose = new int[2];

  /** FM modulator cents fine-tuners (voice.cpp modulatorTransposers[m]). */
  public final PhaseIncrementFineTuner[] modulatorTransposers = {
    new PhaseIncrementFineTuner(), new PhaseIncrementFineTuner()
  };

  /** C: modulatorTransposers[m].setup((int32_t)modulatorCents[m] * 42949672) (sound.cpp:3077). */
  public void setModulatorCents(int m, int cents) {
    modulatorTransposers[m].setup(cents * 42949672);
  }

  /**
   * Sound-level master transpose in semitones (C {@code sound.transpose}, voice.cpp:419). Added to
   * the note code for ALL sources + modulators before per-source transpose (noteCodeWithMaster).
   */
  public int masterTranspose = 0;

  /**
   * Sound-level master cents fine-tune (C {@code sound.cents} / {@code sound.fineTuner},
   * sound.cpp:2979-2984, voice.cpp:509, 554).
   */
  public int masterCents = 0;

  public final PhaseIncrementFineTuner masterFineTuner = new PhaseIncrementFineTuner();

  public void setMasterCents(int cents) {
    this.masterCents = cents;
    masterFineTuner.setup(cents * 42949672);
  }

  /**
   * Per-oscillator semitone transpose (C {@code sources[s].transpose}, voice.cpp:439-442). Added to
   * the note code per source before computing the phase increment.
   */
  public final int[] sourceTranspose = new int[2];

  /** Per-oscillator cents fine-tuners (C {@code sources[s].fineTuner}, voice.cpp:505). */
  public final PhaseIncrementFineTuner[] sourceFineTuners = {
    new PhaseIncrementFineTuner(), new PhaseIncrementFineTuner()
  };

  /** C: sources[s].cents (±50) scaled into the fine-tuner exactly like the modulator cents. */
  public void setSourceCents(int s, int cents) {
    sourceFineTuners[s].setup(cents * 42949672);
  }

  /**
   * Per-source DX7 patch (156-byte). Mirrors C sources[s].ensureDxPatch(); null = source not DX7.
   */
  public final byte[][] sourceDx7Patch = new byte[2][];

  public final int[] sourceDx7EngineType = new int[2];
  public final int[] sourceDx7RandomDetune = new int[2];

  /** C SampleControls.interpolationMode per source: true = 2-tap LINEAR ("lo-fi"). */
  public final boolean[] sourceLinearInterpolation = new boolean[2];

  public volatile int[] globalSourceValues = new int[3];

  /**
   * The patch's per-param "knob"/preset values (bipolar Q31), mirroring the C {@code
   * ParamManager}'s patched-param set. The patcher reads these via {@link
   * #getSmoothedPatchedParamValue} and runs them through the firmware curves.
   */
  public volatile int[] patchedParamValues = new int[200];

  /** The patch's modulation cables (mirrors the C {@code ParamManager}'s PatchCableSet). */
  public final Patcher.PatchCableSet patchCableSet = new Patcher.PatchCableSet();

  /**
   * Holds the state of the active parameter low-pass filter (smoothing). In the Deluge firmware,
   * when a parameter (like volume or cutoff) is adjusted, its value is ramped smoothly over time
   * rather than jumping instantly, which prevents audible clicks and pops.
   */
  public static class ParamLPF {
    /** The parameter ID currently being smoothed, or -1 if no smoothing is active. */
    public int p = -1; // -1 represents PARAM_LPF_OFF

    /** The current intermediate smoothed value of the parameter (in Q31). */
    public int currentValue;
  }

  /** Active parameter smoothing filter state. */
  public final ParamLPF paramLPF = new ParamLPF();

  // Per-block reverb send cache (sound.cpp:2450-2453, 2610, 2613, 2646): during a renderOutput
  // block, both the grain reverb send (inside renderInternal) and the ordinary dry reverb send
  // (inside GlobalEffectable.renderOutput) must use the same resolved GLOBAL_REVERB_AMOUNT value,
  // and doParamLPF runs at the end of the full block (endRenderOutput) like sound.cpp:2646.
  private boolean inRenderOutputBlock = false;
  private boolean hasBlockResolvedReverbValue = false;
  private int blockResolvedReverbValue = 0;
  private int pendingParamLPFSamples = 0;

  @Override
  public void beginRenderOutput() {
    inRenderOutputBlock = true;
    hasBlockResolvedReverbValue = false;
    pendingParamLPFSamples = 0;
  }

  @Override
  public void endRenderOutput() {
    int lpfSamples = pendingParamLPFSamples;
    pendingParamLPFSamples = 0;
    inRenderOutputBlock = false;
    hasBlockResolvedReverbValue = false;
    if (lpfSamples > 0) {
      doParamLPF(lpfSamples);
    }
  }

  /**
   * Retrieves the smoothed value of a parameter if it is currently undergoing LPF smoothing,
   * otherwise returns the target patched value directly. Mirrors the C++ {@code
   * Sound::getSmoothedPatchedParamValue}.
   *
   * @param p the parameter ID
   * @return the smoothed or target parameter value (in Q31)
   */
  public int getSmoothedPatchedParamValue(int p) {
    if (paramLPF.p == p) {
      return paramLPF.currentValue;
    }
    return patchedParamValues[p];
  }

  /**
   * Notifies the sound that a parameter value has changed, initiating LPF smoothing if the
   * parameter requires it.
   *
   * @param p the parameter ID
   * @param shouldDoParamLPF true if LPF smoothing should be applied to this change
   * @param oldValue the previous parameter value
   * @param newValue the new target parameter value
   * @param fromAutomation true if the change originated from sequencer automation
   */
  public void notifyValueChangeViaLPF(
      int p, boolean shouldDoParamLPF, int oldValue, int newValue, boolean fromAutomation) {
    if (p == Param.GLOBAL_REVERB_AMOUNT) {
      hasBlockResolvedReverbValue = false;
    }
    if (!shouldDoParamLPF) {
      if (paramLPF.p == p) {
        paramLPF.p = -1;
      }
      return;
    }

    if (Param.paramNeedsLPF(p, fromAutomation)) {
      if (paramLPF.p != -1) {
        if (paramLPF.p != p) {
          stopParamLPF();
        } else {
          paramLPF.p = p;
          return;
        }
      }
      paramLPF.currentValue = oldValue;
      paramLPF.p = p;
    }
  }

  /**
   * Performs the low-pass filter (smoothing) step for the active parameter. This is called once per
   * audio block (e.g. 128 samples).
   *
   * <p><b>Mathematical Implementation:</b> The difference between the target and current value is
   * shifted right by 8 bits (dividing by 256) to establish a dead-band and prevent tiny precision
   * oscillations. Multiplying this difference by {@code numSamples} (typically 128) results in:
   *
   * <pre>
   *   amountToAdd = (target - current) * 128 / 256 = (target - current) / 2
   * </pre>
   *
   * This effectively moves the parameter exactly <b>halfway</b> towards its target every block,
   * implementing a highly efficient, division-free first-order exponential decay LPF.
   *
   * @param numSamples the number of samples in the rendered block (typically 128)
   */
  public void doParamLPF(int numSamples) {
    if (paramLPF.p == -1) {
      return;
    }

    int oldValue = paramLPF.currentValue;
    int targetValue = patchedParamValues[paramLPF.p];

    int diff = (targetValue >> 8) - (oldValue >> 8);

    if (diff == 0) {
      stopParamLPF();
    } else {
      int amountToAdd = diff * numSamples;
      paramLPF.currentValue += amountToAdd;
    }
  }

  /** Stops the active parameter LPF smoothing, snapping the parameter immediately to its target. */
  public void stopParamLPF() {
    if (!inRenderOutputBlock) {
      hasBlockResolvedReverbValue = false;
    }
    if (paramLPF.p != -1) {
      paramLPF.p = -1;
    }
  }

  public final LfoConfig[] lfoConfig = new LfoConfig[4];
  public int timePerInternalTickInverse = 1 << 20;

  /** C: sound.h:160 — reset by resyncGlobalLFOs / startSkippingRendering. */
  public int timeStartedSkippingRenderingLFO = 0;

  /** C: sound.h:358 — {@code deluge::fast_vector<ActiveVoice> voices_;} */
  public final ArrayList<Voice> voices = new ArrayList<>();

  public enum PolyphonyMode {
    POLY,
    MONO,
    LEGATO
  }

  public PolyphonyMode polyphonic = PolyphonyMode.POLY;
  public int maxPolyphony =
      8; // C: sound.h:116 maxVoiceCount = 8 (per-sound voice cap; steals above)

  public static class CompiledKeyZone {
    public Sample sample;
    public int minPitch;
    public int maxPitch;
    public int minVelocity;
    public int maxVelocity;
    public int startSamplePos;
    public int endSamplePos;
    public int startLoopPos;
    public int endLoopPos;
    public boolean looping;
    public int transpose; // semitones tuning the recorded sample to the played note
    public int cents; // C SampleHolderForVoice cents (applied via fineTuner, voice.cpp:505)
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  public final java.util.List<CompiledKeyZone>[] sourceZones =
      new java.util.List[] {
        new java.util.ArrayList<CompiledKeyZone>(), new java.util.ArrayList<CompiledKeyZone>()
      };

  public final Sample[] samples = new Sample[2];
  public final int[] sampleStartPoint = {0, 0};
  public final int[] sampleEndPoint = {65535, 65535};
  public final int[] sampleLoopMode = {0, 0}; // 0 = off, 1 = loop, 2 = once
  public final int[] sampleLoopStart = {0, 0};
  public final int[] sampleLoopEnd = {0, 0}; // 0 = no loop-end point (C loopEndPos)
  public final boolean[] sampleReverse = {false, false};
  public final boolean[] sampleTimestretch = {false, false};

  public final Arpeggiator.Synth arpeggiator = new Arpeggiator.Synth();
  public final Arpeggiator.Settings arpSettings = new Arpeggiator.Settings();
  public final Arpeggiator.ArpReturnInstruction arpInstr = new Arpeggiator.ArpReturnInstruction();
  public int arpPhaseIncrement = 0;

  public final Lfo[] globalLfos = {new Lfo(), new Lfo()};
  // LFO waveforms live on lfoConfig[].waveType (as in the C); there is no separate array. The
  // global
  // LFO render reads lfoConfig[0]/lfoConfig[2] directly (sound.cpp:2382/2395).

  public final Sidechain sidechain = new Sidechain();
  public int sidechainSend = 0;

  /**
   * C: sound.h:154 — {@code std::array<int32_t, kNumExpressionDimensions>
   * monophonicExpressionValues{}};
   */
  public final int[] monophonicExpressionValues = new int[3];

  /**
   * C: sound.h:149 — {@code std::bitset<kNumExpressionDimensions>
   * expressionSourcesChangedAtSynthLevel{0}};
   */
  public int expressionSourcesChangedAtSynthLevel;

  public final SrrBitcrush srrBitcrush = new SrrBitcrush();
  public final ModFx modFX = new ModFx();
  public final GranularProcessor granular = new GranularProcessor();
  public final Eq eq = new Eq();
  public final Compressor compressor = new Compressor();

  // Per-sound delay (C: ModControllableAudio::delay, applied in processFX after modFX). The
  // firmware's delay is PER-SOUND; the FirmwareAudioEngine master delay covers the song-level
  // GlobalEffectable delay. Tempo sync happens INSIDE Delay.setupWorkingState (delay.cpp:102-117):
  // the caller provides the preset's syncLevel/syncType, the exp-final userDelayRate
  // (paramFinalValues[GLOBAL_DELAY_RATE], neutral 2^24) and timePerInternalTickInverse.
  public final Delay delay = new Delay();
  public final Delay.State delayState = new Delay.State();
  public int delayUserRate = 0;
  public int delayFeedbackAmount = 0;
  public int delaySyncLevel = 0; // 0 = unsynced; 1 = whole ... 9 = 256th (SyncLevel numbering)
  public int delaySyncType = 0; // 0 = EVEN, 1 = TRIPLET, 2 = DOTTED
  public boolean delayPingPong = false;
  public boolean delayAnalog = false;
  public boolean shouldLimitDelayFeedback = false;

  public ModFx.ModFXType modFXType = ModFx.ModFXType.NONE;
  public int modFXRateIncrement = 0;
  public int modFXDepth = 0;
  public int modFXOffset = 0;
  public int modFXFeedback = 0;
  public int bitcrushParam = 0;
  public int srrParam = 0;
  public int eqBassParam = 0;
  public int eqTrebleParam = 0;
  public int currentBpm = 120;
  private final int[] trackParamFinalValues = new int[Param.kNumParams];

  private StereoSample[] fxStereoBuffer = new StereoSample[256];
  private int[][] fxIntBuffer = new int[256][2];

  public Sound() {
    for (int i = 0; i < 4; i++) {
      lfoConfig[i] = new LfoConfig();
    }
    for (int i = 0; i < kMaxNumVoicesUnison; i++) {
      unisonDetuners[i] = new PhaseIncrementFineTuner();
    }
    for (int i = 0; i < 256; i++) {
      fxStereoBuffer[i] = new StereoSample();
    }
    for (int i = 0; i < 256; i++) {
      fxIntBuffer[i] = new int[2];
    }
  }

  public void setupUnisonDetuners() {
    if (numUnison != 1) {
      int detuneScaled = unisonDetune * 42949672;
      int lowestVoice = -(detuneScaled >> 1);
      int voiceSpacing = detuneScaled / (numUnison - 1);

      for (int u = 0; u < numUnison; u++) {
        // Middle unison part gets no detune
        if ((numUnison & 1) != 0 && u == ((numUnison - 1) >> 1)) {
          unisonDetuners[u].setNoDetune();
        } else {
          unisonDetuners[u].setup(lowestVoice + voiceSpacing * u);
        }
      }
    }
  }

  public void setupUnisonStereoSpread() {
    if (numUnison != 1) {
      int spreadScaled = unisonStereoSpread * 42949672;
      int lowestVoice = -(spreadScaled >> 1);
      int voiceSpacing = spreadScaled / (numUnison - 1);

      for (int u = 0; u < numUnison; u++) {
        // alternate the voices like -2 +1 0 -1 +2 for more balanced
        // interaction with detune
        boolean isOdd = (Math.min(u, numUnison - 1 - u) & 1) != 0;
        int sign = isOdd ? -1 : 1;

        unisonPan[u] = sign * (lowestVoice + voiceSpacing * u);
      }
    }
  }

  public void calculateEffectiveVolume() {
    volumeNeutralValueForUnison = (int) (134217728.0 / Math.sqrt(numUnison));
  }

  // ═══════════════════════════════════════════════════════════════════════════════
  // Faithful param-default methods (sound.cpp:131-325). Each takes an int[] that the
  // bridge (FirmwareSound) holds as paramNeutralValues[], matching the C PatchedParamSet.
  // ═══════════════════════════════════════════════════════════════════════════════

  /** C: sound.cpp:131-187 + ModControllableAudio::initParams. */
  public static void initParams(int[] params) {
    for (int i = 0; i < params.length; i++) {
      params[i] = Integer.MIN_VALUE; // C setCurrentValueBasicForSetup(INT_MIN) for most
    }

    params[Param.LOCAL_VOLUME] = 0; // C:141
    params[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE; // C:142 — 2147483647
    params[Param.LOCAL_OSC_B_VOLUME] = Integer.MAX_VALUE; // C:143
    params[Param.GLOBAL_VOLUME_POST_FX] = // C:144-145
        Functions.getParamFromUserValue(Param.GLOBAL_VOLUME_POST_FX, 40);
    params[Param.GLOBAL_VOLUME_POST_REVERB_SEND] = 0; // C:146
    params[Param.LOCAL_FOLD] = Integer.MIN_VALUE; // C:147
    params[Param.LOCAL_HPF_RESONANCE] = Integer.MIN_VALUE; // C:148
    params[Param.LOCAL_HPF_FREQ] = Integer.MIN_VALUE; // C:149
    params[Param.LOCAL_HPF_MORPH] = Integer.MIN_VALUE; // C:150
    params[Param.LOCAL_LPF_MORPH] = Integer.MIN_VALUE; // C:151
    params[Param.LOCAL_PITCH_ADJUST] = 0; // C:152
    params[Param.GLOBAL_REVERB_AMOUNT] = Integer.MIN_VALUE; // C:153
    params[Param.GLOBAL_DELAY_RATE] = 0; // C:154
    params[Param.GLOBAL_ARP_RATE] = 0; // C:155
    params[Param.GLOBAL_DELAY_FEEDBACK] = Integer.MIN_VALUE; // C:156
    params[Param.LOCAL_CARRIER_0_FEEDBACK] = Integer.MIN_VALUE; // C:157
    params[Param.LOCAL_CARRIER_1_FEEDBACK] = Integer.MIN_VALUE; // C:158
    params[Param.LOCAL_MODULATOR_0_FEEDBACK] = Integer.MIN_VALUE; // C:159
    params[Param.LOCAL_MODULATOR_1_FEEDBACK] = Integer.MIN_VALUE; // C:160
    params[Param.LOCAL_MODULATOR_0_VOLUME] = Integer.MIN_VALUE; // C:161
    params[Param.LOCAL_MODULATOR_1_VOLUME] = Integer.MIN_VALUE; // C:162
    params[Param.LOCAL_OSC_A_PHASE_WIDTH] = 0; // C:163
    params[Param.LOCAL_OSC_B_PHASE_WIDTH] = 0; // C:164
    params[Param.LOCAL_ENV_1_ATTACK] = // C:165-166
        Functions.getParamFromUserValue(Param.LOCAL_ENV_1_ATTACK, 20);
    params[Param.LOCAL_ENV_1_DECAY] = // C:167-168
        Functions.getParamFromUserValue(Param.LOCAL_ENV_1_DECAY, 20);
    params[Param.LOCAL_ENV_1_SUSTAIN] = // C:169-170
        Functions.getParamFromUserValue(Param.LOCAL_ENV_1_SUSTAIN, 25);
    params[Param.LOCAL_ENV_1_RELEASE] = // C:171-172
        Functions.getParamFromUserValue(Param.LOCAL_ENV_1_RELEASE, 20);
    params[Param.LOCAL_LFO_LOCAL_FREQ_1] = 0; // C:173
    params[Param.GLOBAL_LFO_FREQ_1] = // C:174-175
        Functions.getParamFromUserValue(Param.GLOBAL_LFO_FREQ_1, 30);
    params[Param.LOCAL_LFO_LOCAL_FREQ_2] = 0; // C:176
    params[Param.GLOBAL_LFO_FREQ_2] = // C:177-178
        Functions.getParamFromUserValue(Param.GLOBAL_LFO_FREQ_2, 30);
    params[Param.LOCAL_PAN] = 0; // C:179
    params[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE; // C:180
    params[Param.GLOBAL_MOD_FX_DEPTH] = 0; // C:181
    params[Param.GLOBAL_MOD_FX_RATE] = 0; // C:182
    params[Param.LOCAL_OSC_A_PITCH_ADJUST] = 0; // C:183
    params[Param.LOCAL_OSC_B_PITCH_ADJUST] = 0; // C:184
    params[Param.LOCAL_MODULATOR_0_PITCH_ADJUST] = 0; // C:185
    params[Param.LOCAL_MODULATOR_1_PITCH_ADJUST] = 0; // C:186
    // C:138 — PORTAMENTO = INT_MIN (internal param, not exposed)
    params[Param.UNPATCHED_BASS] = 0;
    params[Param.UNPATCHED_TREBLE] = 0;
    params[Param.UNPATCHED_BASS_FREQ] = 0;
    params[Param.UNPATCHED_TREBLE_FREQ] = 0;
    params[Param.UNPATCHED_STUTTER_RATE] = 0;
    params[Param.UNPATCHED_MOD_FX_OFFSET] = 0; // C: mod_controllable_audio.cpp:131
    params[Param.UNPATCHED_MOD_FX_FEEDBACK] = 0; // C: mod_controllable_audio.cpp:132
    params[Param.UNPATCHED_SIDECHAIN_SHAPE] = -601295438; // C: mod_controllable_audio.cpp:138
    params[Param.UNPATCHED_COMPRESSOR_THRESHOLD] = 0; // C: mod_controllable_audio.cpp:139
  }

  /** C: sound.cpp:223-259 — preset that ships with new synths. */
  public static void setupAsDefaultSynth(int[] params, Sound cfg) {
    params[Param.LOCAL_OSC_B_VOLUME] = 0x47AE1457; // C:226 — ~half
    params[Param.LOCAL_LPF_RESONANCE] = 0xA2000000; // C:227
    params[Param.LOCAL_LPF_FREQ] = 0x10000000; // C:228
    params[Param.LOCAL_ENV_0_ATTACK] = 0x80000000; // C:229
    params[Param.LOCAL_ENV_0_DECAY] = 0xE6666654; // C:230
    params[Param.LOCAL_ENV_0_SUSTAIN] = 0x7FFFFFFF; // C:231
    params[Param.LOCAL_ENV_0_RELEASE] = 0x851EB851; // C:232
    params[Param.LOCAL_ENV_1_ATTACK] = 0xA3D70A37; // C:233
    params[Param.LOCAL_ENV_1_DECAY] = 0xA3D70A37; // C:234
    params[Param.LOCAL_ENV_1_SUSTAIN] = 0xFFFFFFE9; // C:235
    params[Param.LOCAL_ENV_1_RELEASE] = 0xE6666654; // C:236
    params[Param.GLOBAL_VOLUME_POST_FX] = 0x50000000; // C:237

    // C:239-243 — default patch cables (4 cables)
    addCable(cfg, PatchSource.NOTE, Param.LOCAL_LPF_FREQ, 0x08F5C28C); // C:239
    addCable(cfg, PatchSource.ENVELOPE_1, Param.LOCAL_LPF_FREQ, 0x1C28F5B8); // C:240
    addCable(cfg, PatchSource.VELOCITY, Param.LOCAL_LPF_FREQ, 0x0F5C28F0); // C:241
    addCable(cfg, PatchSource.VELOCITY, Param.LOCAL_VOLUME, 0x3FFFFFE8); // C:242

    cfg.lpfMode = FilterMode.TRANSISTOR_24DB; // C:248
    cfg.oscTypes[0] = OscType.SAW; // C:250
  }

  /** C: sound.cpp:297-325 — blank init (no patch, no shaping). */
  public static void setupAsBlankSynth(int[] params, Sound cfg, boolean isDx) {
    params[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE; // C:300
    params[Param.LOCAL_LPF_FREQ] = Integer.MAX_VALUE; // C:301
    params[Param.LOCAL_LPF_RESONANCE] = Integer.MIN_VALUE; // C:302
    params[Param.LOCAL_ENV_0_ATTACK] = Integer.MIN_VALUE; // C:303
    params[Param.LOCAL_ENV_0_DECAY] = // C:304-305
        Functions.getParamFromUserValue(Param.LOCAL_ENV_0_DECAY, 20);
    params[Param.LOCAL_ENV_0_SUSTAIN] = Integer.MAX_VALUE; // C:306
    if (isDx) {
      cfg.oscTypes[0] = OscType.DX7; // C:308
      cfg.patchCableSet.destinations.clear(); // C:311
      params[Param.LOCAL_ENV_0_RELEASE] = Integer.MAX_VALUE; // C:312
    } else {
      params[Param.LOCAL_ENV_0_RELEASE] = Integer.MIN_VALUE; // C:315
      cfg.patchCableSet.destinations.clear(); // C:317 (numPatchCables=1)
      addCable(
          cfg,
          PatchSource.VELOCITY,
          Param.LOCAL_VOLUME, // C:318-319
          Functions.getParamFromUserValue(Param.PATCH_CABLE, 50));
    }
  }

  // ── isSourceActiveCurrently (sound.cpp:2088-2092) ──

  /**
   * C: sound.cpp:2088-2092. A source is ACTIVE right now if: 1. Ringmod mode, or the smoothed
   * patched-param value for OSC_A_VOLUME+s != MIN_VALUE (off) 2. AND (FM mode, or source type !=
   * SAMPLE, or a sample IS loaded for this source)
   *
   * @param hasSample true if a sample audio file is loaded for this source (C:
   *     sources[s].hasAtLeastOneAudioFileLoaded())
   */
  public boolean isSourceActiveCurrently(int s, boolean hasSample) {
    boolean ampActive =
        (synthMode == 2) // RINGMOD
            || patchedParamValues[Param.LOCAL_OSC_A_VOLUME + s] != Integer.MIN_VALUE;
    boolean typeActive =
        (synthMode == 1) // FM
            || oscTypes[s] != OscType.SAMPLE
            || hasSample;
    return ampActive && typeActive;
  }

  /** C: sound.cpp:2107-2117 — true if source {@code s} can ever sound. */
  public boolean isSourceActiveEver(int s, boolean hasSample) {
    boolean ampActive =
        (synthMode == 2) // RINGMOD
            || patchedParamValues[Param.LOCAL_OSC_A_VOLUME + s] != Integer.MIN_VALUE
            || doesParamHaveSomethingPatchedToIt(Param.LOCAL_OSC_A_VOLUME + s)
            || renderingOscillatorSyncEver();
    boolean typeActive =
        (synthMode == 1) // FM
            || oscTypes[s] != OscType.SAMPLE
            || hasSample;
    return ampActive && typeActive;
  }

  private static void addCable(Sound cfg, PatchSource source, int paramId, int amount) {
    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = source.ordinal();
    cable.amount = amount;
    cfg.patchCableSet.addCable(paramId, cable);
  }

  /**
   * getGlobalLFOPhaseIncrement (sound.cpp:2700-2707) — unsynced LFOs run at the rate param's final
   * value; synced LFOs derive the increment from the tempo.
   */
  public int getGlobalLFOPhaseIncrement(LfoConfig config, int param) {
    if (config.syncLevel == Lfo.SyncLevel.NONE) {
      return Patcher.computeFinalValueForParam(param, patchedParamValues[param]);
    }
    return getSyncedLFOPhaseIncrement(config);
  }

  public int getSyncedLFOPhaseIncrement(LfoConfig config) {
    int shift = Lfo.SyncLevel.L_256TH.ordinal() - config.syncLevel.ordinal();
    int phaseIncrement = timePerInternalTickInverse >> shift;
    switch (config.syncType) {
      case EVEN:
        break;
      case TRIPLET:
        phaseIncrement = phaseIncrement * 3 / 2;
        break;
      case DOTTED:
        phaseIncrement = phaseIncrement * 2 / 3;
        break;
    }
    return phaseIncrement;
  }

  // ── resyncGlobalLFOs (sound.cpp:2723-2802) ──

  /**
   * Re-aligns the phase of the clock-synced global LFOs (LFO1, LFO3) to the song clock. Port of
   * {@code Sound::resyncGlobalLFOs} (sound.cpp:2723).
   *
   * <p>The C reads the global {@code playbackHandler} and {@code AudioEngine::audioSampleTimer};
   * firmware2 has no transport/clock port (see docs/FIRMWARE2_DSP_PORT_STATUS.md — transport is
   * non-port), so the Java transport layer passes those exact values in. When the clock is stopped
   * ({@code !clockActive}) this early-returns — identical to the C {@code isEitherClockActive()}
   * guard — so the no-arg overload (the only state reachable today) is a faithful no-op.
   *
   * @param clockActive {@code playbackHandler.isEitherClockActive()}
   * @param lastInternalTickDone {@code
   *     playbackHandler.getCurrentInternalTickCount(&timeSinceLastTick)}
   * @param timeSinceLastTick the out-param of {@code getCurrentInternalTickCount} (uint32)
   * @param timePerInternalTick {@code playbackHandler.getTimePerInternalTick()} (uint32)
   * @param audioSampleTimer {@code AudioEngine::audioSampleTimer} (uint32)
   */
  public void resyncGlobalLFOs(
      boolean clockActive,
      long lastInternalTickDone,
      int timeSinceLastTick,
      int timePerInternalTick,
      int audioSampleTimer) {
    if (!clockActive) {
      return; // sound.cpp:2724 — no clock, no sync
    }
    // LFO1_ID → globalLfos[0]/lfoConfig[0]; LFO3_ID → globalLfos[1]/lfoConfig[2]
    // (sound.cpp:2727,2766)
    if (lfoConfig[0].syncLevel != Lfo.SyncLevel.NONE) {
      resyncOneGlobalLFO(
          globalLfos[0],
          lfoConfig[0],
          lastInternalTickDone,
          timeSinceLastTick,
          timePerInternalTick,
          audioSampleTimer);
    }
    if (lfoConfig[2].syncLevel != Lfo.SyncLevel.NONE) {
      resyncOneGlobalLFO(
          globalLfos[1],
          lfoConfig[2],
          lastInternalTickDone,
          timeSinceLastTick,
          timePerInternalTick,
          audioSampleTimer);
    }
  }

  /** Stopped-clock / no-transport overload — faithful no-op, matching the C early-return. */
  public void resyncGlobalLFOs() {
    resyncGlobalLFOs(false, 0, 0, 0, 0);
  }

  /**
   * The per-LFO body the C duplicates verbatim for LFO1 (sound.cpp:2727-2761) and LFO3
   * (sound.cpp:2766-2801). Factored to one helper to keep the two paths identical.
   */
  private void resyncOneGlobalLFO(
      Lfo lfo,
      LfoConfig config,
      long lastInternalTickDone,
      int timeSinceLastTick,
      int timePerInternalTick,
      int audioSampleTimer) {
    // Resets the thing where skipped samples are later converted into LFO phase increment.
    timeStartedSkippingRenderingLFO = audioSampleTimer; // sound.cpp:2730

    lfo.setGlobalInitialPhase(config); // sound.cpp:2734

    // If we're right at the first tick, no need to do anything else! (sound.cpp:2741)
    if (lastInternalTickDone != 0 || timeSinceLastTick != 0) {
      int shift = Lfo.SyncLevel.L_256TH.ordinal() - config.syncLevel.ordinal();
      int numInternalTicksPerPeriod = 3 << shift; // sound.cpp:2742
      switch (config.syncType) {
        case EVEN:
          break; // Nothing to do
        case TRIPLET:
          numInternalTicksPerPeriod = numInternalTicksPerPeriod * 2 / 3; // sound.cpp:2750
          break;
        case DOTTED:
          numInternalTicksPerPeriod = numInternalTicksPerPeriod * 3 / 2; // sound.cpp:2753
          break;
      }
      // (uint64_t)lastInternalTickDone % (uint16_t)numInternalTicksPerPeriod (sound.cpp:2756)
      long modulus = numInternalTicksPerPeriod & 0xFFFFL; // (uint16_t) truncation
      long offsetTicks = Long.remainderUnsigned(lastInternalTickDone, modulus);

      // If we're right at a bar (or something), no need to do anything else (sound.cpp:2759)
      if (timeSinceLastTick != 0 || offsetTicks != 0) {
        // All of the following are uint32 in C — replicate the 32-bit wrap, then read as unsigned.
        int timePerPeriod = numInternalTicksPerPeriod * timePerInternalTick; // sound.cpp:2761
        int offsetTime =
            (int) (offsetTicks * (timePerInternalTick & 0xFFFFFFFFL)) + timeSinceLastTick; // :2762
        // globalLFO.phase += (uint32_t)((float)offsetTime / timePerPeriod * 4294967296) (:2763)
        float ratio = (float) (offsetTime & 0xFFFFFFFFL) / (float) (timePerPeriod & 0xFFFFFFFFL);
        lfo.phase += (int) (long) (ratio * 4294967296.0f);
      }
    }
  }

  public void polyphonicExpressionEventOnChannelOrNote(
      int newValue, int expressionDimension, int channelOrNoteNumber, int whichCharacteristic) {
    synchronized (voices) {
      int s = expressionDimension + PatchSource.X.ordinal();
      for (Voice voice : voices) {
        if (voice.active
            && voice.inputCharacteristics[whichCharacteristic] == channelOrNoteNumber) {
          voice.expressionEventImmediate(this, newValue, s);
        }
      }
    }
  }

  public void monophonicExpressionEvent(int newValue, int expressionDimension) {
    monophonicExpressionValues[expressionDimension] = newValue;
    synchronized (voices) {
      int s = expressionDimension + PatchSource.X.ordinal();
      for (Voice voice : voices) {
        if (voice.active) {
          voice.sourceValues[s] = voice.combineExpressionValues(this, expressionDimension);
        }
      }
    }
  }

  @Override
  public void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    // 1. Sidechain hit registration & render
    int scHit = GlobalSidechainBus.getActiveFrameHit();
    if (scHit > 0) {
      sidechain.registerHit(scHit);
    }
    int shape = patchedParamValues[Param.UNPATCHED_SIDECHAIN_SHAPE];
    int scAmount = sidechain.render(numSamples, shape);
    globalSourceValues[PatchSource.SIDECHAIN.ordinal()] = scAmount;

    // 2. Global LFO rendering. C (sound.cpp:2382/2395) renders globalLFO1/3 from lfoConfig[LFO1_ID]
    // / lfoConfig[LFO3_ID] — the config carries the waveType, so the waveform must come from there,
    // NOT a separate array. (LFO1_ID → lfoConfig[0], LFO3_ID → lfoConfig[2].)
    int phaseInc1 = getGlobalLFOPhaseIncrement(lfoConfig[0], Param.GLOBAL_LFO_FREQ_1);
    int phaseInc2 = getGlobalLFOPhaseIncrement(lfoConfig[2], Param.GLOBAL_LFO_FREQ_2);

    globalLfos[0].customWave = customLfoWave;
    globalLfos[1].customWave = customLfoWave;
    globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal()] =
        globalLfos[0].render(numSamples, lfoConfig[0], phaseInc1);
    globalSourceValues[PatchSource.LFO_GLOBAL_2.ordinal()] =
        globalLfos[1].render(numSamples, lfoConfig[2], phaseInc2);

    // C sound.cpp:2446-2453 — evaluate global range/depth cables and resolve GLOBAL_REVERB_AMOUNT
    // once at the start of the block (even when voices_.empty() during FX tails, and before
    // doParamLPF advances paramLPF at the end of the block).
    Patcher.evaluateGlobalRangeDestinations(patchCableSet, globalSourceValues);
    blockResolvedReverbValue = resolveGlobalReverbParamFinalValue();
    hasBlockResolvedReverbValue = inRenderOutputBlock;

    // 3. Arpeggiator clock & processing
    if (arpEnabled()) {
      int phaseInc =
          (arpPhaseIncrement > 0)
              ? arpPhaseIncrement
              : arpSettings.getPhaseIncrement(
                  Patcher.computeFinalValueForParam(
                      Param.GLOBAL_ARP_RATE, patchedParamValues[Param.GLOBAL_ARP_RATE]),
                  this.timePerInternalTickInverse);
      if (phaseInc > 0) {
        // sound.cpp:2456 — (uint32_t)gate + 2147483648, passed FULL-SCALE (the arp itself does the
        // single >> 8, arpeggiator.cpp:1457). Int add wraps exactly like the C uint32 add.
        int gateThreshold = arpSettings.gate + Integer.MIN_VALUE;
        arpInstr.reset();
        arpeggiator.render(arpSettings, arpInstr, numSamples, gateThreshold, phaseInc);

        // C sound.cpp:2478-2491 — release glide notes first, then post-arp note-offs.
        for (int n = 0; n < 4; n++) {
          int glideOff = arpInstr.glideNoteCodeOffPostArp[n];
          if (glideOff == Arpeggiator.ARP_NOTE_NONE) {
            break;
          }
          releaseVoice(glideOff, -1);
        }
        for (int n = 0; n < 4; n++) {
          int noteOff = arpInstr.noteCodeOffPostArp[n];
          if (noteOff == Arpeggiator.ARP_NOTE_NONE) {
            break;
          }
          releaseVoice(noteOff, -1);
        }

        if (arpInstr.arpNoteOn != null) {
          // C process_postarp_notes (sound.cpp:2354-2375) — EVERY pending entry until ARP_NOTE_NONE
          // plays (chord/octave arp modes emit several), each at the arp note's real velocity.
          for (int n = 0; n < arpInstr.arpNoteOn.noteCodeOnPostArp.length; n++) {
            int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[n];
            if (noteOn == Arpeggiator.ARP_NOTE_NONE) {
              break;
            }
            if (arpInstr.arpNoteOn.noteStatus[n] != Arpeggiator.ArpNoteStatus.PENDING) {
              continue;
            }
            triggerVoice(noteOn, arpInstr.arpNoteOn.velocity, -1);
            arpInstr.arpNoteOn.noteStatus[n] = Arpeggiator.ArpNoteStatus.PLAYING;
          }
          arpInstr.arpNoteOn = null;
        }
      }
    }

    // C sound.cpp:2506-2519 — a filter section only runs when it can have an audible effect:
    // drive mode always; otherwise when the cutoff is patched, below max (LPF), set at all
    // (HPF), or the morph is engaged. A filter whose knobs sit at neutral is a hard bypass on
    // hardware (no ladder phase/level residue).
    boolean thisHasFilters = (lpfMode != FilterMode.OFF) || (hpfMode != FilterMode.OFF);
    boolean doLPF =
        thisHasFilters
            && (lpfMode == FilterMode.TRANSISTOR_24DB_DRIVE
                || doesParamHaveSomethingPatchedToIt(Param.LOCAL_LPF_FREQ)
                || getSmoothedPatchedParamValue(Param.LOCAL_LPF_FREQ) < 0x7FFFFFD2
                || getSmoothedPatchedParamValue(Param.LOCAL_LPF_MORPH) > Integer.MIN_VALUE);
    boolean doHPF =
        thisHasFilters
            && (doesParamHaveSomethingPatchedToIt(Param.LOCAL_HPF_FREQ)
                || getSmoothedPatchedParamValue(Param.LOCAL_HPF_FREQ) != Integer.MIN_VALUE
                || getSmoothedPatchedParamValue(Param.LOCAL_HPF_MORPH) > Integer.MIN_VALUE);

    // 4. Sum active voices
    boolean hasActiveVoices = false;
    synchronized (voices) {
      for (int i = voices.size() - 1; i >= 0; i--) {
        Voice v = voices.get(i);
        if (!v.active) {
          voices.remove(i);
          continue;
        }
        hasActiveVoices = true;
        Patcher.performInitialPatching(this, v.sourceValues, v.paramFinalValues);
        v.render(buffer, numSamples, doLPF, doHPF);
      }
    }

    boolean arpHolding = arpEnabled() && arpeggiator.hasAnyInputNotesActive();
    // C sound.cpp:2165 — keep rendering the FX tail while the per-sound delay still has repeats
    // pending, so the delay tail isn't cut when the note's voices end.
    boolean delayTailActive = delay.repeatsUntilAbandon != 0;
    // C sound.cpp:2207-2209, GranularProcessor.cpp:50, 84-86 — keep rendering the grain FX tail
    // once any audio has been written into the grain buffer until wrapsToShutdown < 0, even if
    // the note ended before the first full 65,536-sample ring wrap set grainInitialized.
    boolean grainTailActive =
        modFXType == ModFx.ModFXType.GRAIN
            && (granular.bufferWriteIndex > 0 || granular.bufferFull || granular.grainInitialized)
            && granular.wrapsToShutdown >= 0;
    if (!hasActiveVoices && !arpHolding && !delayTailActive && !grainTailActive) {
      return;
    }

    // 5. Apply Track FX (SRR/bitcrush, ModFX/granular, EQ)
    if (numSamples > fxIntBuffer.length) {
      int oldLen = fxIntBuffer.length;
      fxIntBuffer = java.util.Arrays.copyOf(fxIntBuffer, numSamples);
      for (int i = oldLen; i < numSamples; i++) {
        fxIntBuffer[i] = new int[2];
      }
    }
    if (numSamples > fxStereoBuffer.length) {
      int oldLen = fxStereoBuffer.length;
      fxStereoBuffer = java.util.Arrays.copyOf(fxStereoBuffer, numSamples);
      for (int i = oldLen; i < numSamples; i++) {
        fxStereoBuffer[i] = new StereoSample();
      }
    }

    for (int i = 0; i < numSamples; i++) {
      fxIntBuffer[i][0] = buffer[i * 2];
      fxIntBuffer[i][1] = buffer[i * 2 + 1];
    }

    // C patcher.cpp:219 — use getSmoothedPatchedParamValue so manual knob changes ramp smoothly.
    int postFXKnob = getSmoothedPatchedParamValue(Param.GLOBAL_VOLUME_POST_FX);
    Patcher.Destination dFx = null;
    for (var dest : patchCableSet.destinations) {
      if (dest.paramId == Param.GLOBAL_VOLUME_POST_FX && dest.targetSource < 0) {
        dFx = dest;
        break;
      }
    }
    // C: patcher.cpp:210-233 — even with no patch cables (destination == nullptr),
    // combineCablesLinear folds the stored knob through cableToLinearParamWithoutRangeAdjustment
    // (range = 2^30, shifting knob >> 2 into [-2^29, +2^29]) before getFinalParameterValueVolume.
    // Passing postFXKnob raw skipped that >> 2 and clamped every knob >= 0x20000000 to max.
    int postFXVolume =
        (dFx != null)
            ? Functions.getFinalParameterValueVolume(
                Functions.getParamNeutralValue(Param.GLOBAL_VOLUME_POST_FX),
                Patcher.combineCablesLinear(dFx, postFXKnob, globalSourceValues))
            : Patcher.computeFinalValueForParam(Param.GLOBAL_VOLUME_POST_FX, postFXKnob);
    int[] postFXVolumeHolder = {postFXVolume};
    int[] postReverbVolumeHolder = {2147483647};

    srrBitcrush.process(fxIntBuffer, numSamples, bitcrushParam, srrParam, postFXVolumeHolder);

    if (modFXType == ModFx.ModFXType.GRAIN) {
      // C sound.cpp:2450-2453, 2610, mod_controllable_audio.cpp:206-210 — Sound::render passes
      // reverbSendAmount >> 1 into processFX, and processGrainFX scales that halved send by
      // postFXVolume << 5.
      int reverbAmountAdjust = 67108864;
      int reverbSendAmount = computeReverbSendAmount(reverbAmountAdjust);
      int grainReverbAmount =
          Functions.multiply_32x32_rshift32(postFXVolumeHolder[0], reverbSendAmount >> 1) << 5;
      granular.processGrainFX(
          fxIntBuffer,
          numSamples,
          modFXRateIncrement,
          modFXDepth << 1,
          modFXOffset,
          modFXFeedback,
          postFXVolumeHolder,
          hasActiveVoices,
          (float) currentBpm,
          grainReverbAmount,
          reverbBuffer);
    } else {
      modFX.processModFX(
          fxIntBuffer,
          numSamples,
          modFXType,
          modFXRateIncrement,
          modFXDepth,
          postFXVolumeHolder,
          modFXOffset,
          modFXFeedback,
          true,
          // C sound.cpp:2588 — anySoundComingIn = !voices_.empty(): during delay/reverb-tail-only
          // blocks the C skips modFX entirely (incl. its postFXVolume cuts).
          hasActiveVoices);
    }

    eq.process(
        fxIntBuffer,
        numSamples,
        eqBassParam,
        eqTrebleParam,
        patchedParamValues[Param.UNPATCHED_BASS_FREQ],
        patchedParamValues[Param.UNPATCHED_TREBLE_FREQ]);

    // Per-sound delay (C: sound.cpp:2477-2484 sets the working state; processFX runs
    // delay.process after EQ, mod_controllable_audio.cpp:195). setupWorkingState is called
    // UNCONDITIONALLY like the C — it applies the tempo sync (delay.cpp:102-117) and, when the
    // feedback drops below 256, discards the buffers via informWhetherActive(false).
    delay.syncLevel = delaySyncLevel;
    delay.syncType = delaySyncType;
    delay.pingPong = delayPingPong;
    delay.analog = delayAnalog;
    delayState.userDelayRate = delayUserRate;
    delayState.delayFeedbackAmount =
        shouldLimitDelayFeedback
            ? Math.min(delayFeedbackAmount, (1 << 30) - (1 << 26))
            : delayFeedbackAmount;
    delay.setupWorkingState(delayState, timePerInternalTickInverse, hasActiveVoices);
    delay.process(fxIntBuffer, numSamples, delayState);

    // Stutter runs AFTER processFX (modFX → EQ → delay), so it chops the delayed/chorused
    // signal like the C (sound.cpp:2589). C: only one stutter effect is active at a time via a
    // single global stutterer (stutterer.h), gated by isStuttering(this) matching
    // ModControllableAudio::processStutter (mod_controllable_audio.cpp:1315) -- not a per-instance
    // flag. Bypass entirely if this Sound isn't the one currently stuttering, to save CPU.
    if (Stutterer.GLOBAL.isStuttering(this)) {
      for (int i = 0; i < numSamples; i++) {
        fxStereoBuffer[i].l = fxIntBuffer[i][0];
        fxStereoBuffer[i].r = fxIntBuffer[i][1];
      }
      Stutterer.GLOBAL.processStutter(fxStereoBuffer, numSamples, timePerInternalTickInverse);
      for (int i = 0; i < numSamples; i++) {
        fxIntBuffer[i][0] = fxStereoBuffer[i].l;
        fxIntBuffer[i][1] = fxStereoBuffer[i].r;
      }
    }

    // Per-sound compressor moved to processPostVolumeDynamics — C runs it AFTER
    // processReverbSendAndVolume (sound.cpp:2591-2600), so it must see the volume-applied signal.

    int postReverbKnob = getSmoothedPatchedParamValue(Param.GLOBAL_VOLUME_POST_REVERB_SEND);
    Patcher.Destination dReverb = null;
    for (var dest : patchCableSet.destinations) {
      if (dest.paramId == Param.GLOBAL_VOLUME_POST_REVERB_SEND && dest.targetSource < 0) {
        dReverb = dest;
        break;
      }
    }
    postReverbVolumeHolder[0] =
        (dReverb != null)
            ? Functions.getFinalParameterValueVolume(
                Functions.getParamNeutralValue(Param.GLOBAL_VOLUME_POST_REVERB_SEND),
                Patcher.combineCablesLinear(dReverb, postReverbKnob, globalSourceValues))
            : Patcher.computeFinalValueForParam(
                Param.GLOBAL_VOLUME_POST_REVERB_SEND, postReverbKnob);

    super.postFXVolume = postFXVolumeHolder[0];
    super.postReverbVolume = postReverbVolumeHolder[0];

    for (int i = 0; i < numSamples; i++) {
      buffer[i * 2] = fxIntBuffer[i][0];
      buffer[i * 2 + 1] = fxIntBuffer[i][1];
    }
    // C sound.cpp:2646 — doParamLPF runs at the very end of Sound::render, AFTER
    // processReverbSendAndVolume (sound.cpp:2613) and compressor (sound.cpp:2618).
    if (inRenderOutputBlock) {
      pendingParamLPFSamples = numSamples;
    } else {
      doParamLPF(numSamples);
    }
  }

  public boolean arpEnabled() {
    return arpSettings.mode != Arpeggiator.ArpMode.OFF;
  }

  public void triggerNote(int note, int vel) {
    triggerNote(note, vel, -1);
  }

  public void triggerNote(int note, int vel, int midiChannel) {
    if (arpEnabled()) {
      arpInstr.reset();
      arpeggiator.noteOn(arpSettings, note, vel, arpInstr, midiChannel, null);
      for (int n = 0; n < 4; n++) {
        int glideOff = arpInstr.glideNoteCodeOffPostArp[n];
        if (glideOff == Arpeggiator.ARP_NOTE_NONE) {
          break;
        }
        releaseVoice(glideOff, -1);
      }
      for (int n = 0; n < 4; n++) {
        int noteOff = arpInstr.noteCodeOffPostArp[n];
        if (noteOff == Arpeggiator.ARP_NOTE_NONE) {
          break;
        }
        releaseVoice(noteOff, -1);
      }
      if (arpInstr.arpNoteOn != null) {
        int v = arpInstr.arpNoteOn.velocity;
        if (v <= 0) v = 64;
        for (int n = 0; n < arpInstr.arpNoteOn.noteCodeOnPostArp.length; n++) {
          int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[n];
          if (noteOn == Arpeggiator.ARP_NOTE_NONE) {
            break;
          }
          if (arpInstr.arpNoteOn.noteStatus[n] != Arpeggiator.ArpNoteStatus.PENDING) {
            continue;
          }
          triggerVoice(noteOn, v, midiChannel);
          arpInstr.arpNoteOn.noteStatus[n] = Arpeggiator.ArpNoteStatus.PLAYING;
        }
        arpInstr.arpNoteOn = null;
      }
      return;
    }
    triggerVoice(note, vel, midiChannel);
  }

  public void triggerNoteLate(int note, int vel, int samplesLate) {
    if (sidechainSend != 0) {
      GlobalSidechainBus.registerHit(sidechainSend);
    }
    triggerVoiceInternal(note, vel, -1, null, samplesLate);
  }

  public void triggerVoice(int note, int vel, int midiChannel) {
    if (sidechainSend != 0) {
      GlobalSidechainBus.registerHit(sidechainSend);
    }
    triggerVoiceInternal(note, vel, midiChannel, null, 0);
  }

  private void triggerVoiceInternal(
      int note, int vel, int midiChannel, int[] mpeValues, int samplesLate) {
    if (muted) {
      return;
    }
    if (org.deluge.engine.FirmwareAudioEngine.debugTelemetry) {}
    synchronized (voices) {
      setupUnisonDetuners();
      setupUnisonStereoSpread();
      calculateEffectiveVolume();

      // C sound.cpp:1618-1622, 1649-1651 — in LEGATO mode, if an active voice is still before
      // RELEASE and note tails are allowed, glide/re-pitch via changeNoteCode without resetting
      // envelopes, oscillator phases, filters, or active sample readers.
      if (polyphonic == PolyphonyMode.LEGATO && allowNoteTails(true)) {
        for (var v : voices) {
          if (v.active
              && !v.previouslyIgnoredNoteOff
              && v.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0) {
            for (int s = 0; s < 2; s++) {
              if (synthMode != 1 && oscTypes[s] == OscType.SAMPLE) {
                if (v.sources[s].sampleRef == null && hasAnySampleLoaded(s)) {
                  setupVoiceSampleSource(v, s, note, vel, samplesLate);
                  boolean activeNow = v.sources[s].sampleRef != null && isSourceActiveEver(s, true);
                  for (int u = 0; u < numUnison; u++) {
                    v.unisonParts[u].sources[s].active = activeNow;
                    v.unisonParts[u].sources[s].disabledByPitch = false;
                  }
                }
              } else if (v.sources[s].sampleRef != null) {
                // C voice.cpp:422 / sound.cpp:1232 — source transitioned away from SAMPLE while
                // held; clear retained sample state and restore waveform activity.
                v.sources[s].clearSample();
                boolean activeNow = isSourceActiveEver(s, false);
                for (int u = 0; u < numUnison; u++) {
                  v.unisonParts[u].sources[s].active = activeNow;
                  v.unisonParts[u].sources[s].disabledByPitch = false;
                }
              }
            }
            v.ensureUnisonPartsInitialized(note, vel);
            v.changeNoteCode(note, midiChannel, mpeValues);
            return;
          }
        }
      }

      Voice targetVoice = null;
      if (polyphonic != PolyphonyMode.POLY) {
        for (var v : voices) {
          if (v.active) {
            targetVoice = v;
            break;
          }
        }
      }
      if (targetVoice == null) {
        for (var v : voices) {
          if (!v.active) {
            targetVoice = v;
            break;
          }
        }
      }
      if (targetVoice == null && voices.size() < maxPolyphony) {
        targetVoice = new Voice(this);
        voices.add(targetVoice);
      }
      if (targetVoice == null) {
        int highestRating = Integer.MIN_VALUE;
        for (var v : voices) {
          int rating = v.getPriorityRating();
          if (rating > highestRating) {
            highestRating = rating;
            targetVoice = v;
          }
        }
      }
      if (targetVoice != null) {
        // Setup/Update samples on targetVoice's sources before triggering
        for (int s = 0; s < 2; s++) {
          setupVoiceSampleSource(targetVoice, s, note, vel, samplesLate);
        }
        targetVoice.noteOn(note, vel, midiChannel, mpeValues);
      }
    }
  }

  /**
   * Initializes or clears sample playback state on {@code targetVoice.sources[s]} for {@code note}.
   * Mirrors C {@code Voice::noteOn} (voice.cpp:226-256), which only attaches an audioFileHolder and
   * sample reader when {@code oscType == OscType::SAMPLE && synthMode != SynthMode::FM}.
   */
  private void setupVoiceSampleSource(
      Voice targetVoice, int s, int note, int vel, int samplesLate) {
    if (synthMode == 1 || oscTypes[s] != OscType.SAMPLE) {
      targetVoice.sources[s].clearSample();
      return;
    }

    Sample targetSample = null;
    int startFrame = sampleStartPoint[s];
    int len = 0;
    int endFrame = sampleEndPoint[s];
    boolean looping = sampleLoopMode[s] == 1;
    int loopStartFrame = sampleLoopStart[s];
    int loopEndPos = 0;
    int zoneTranspose = Integer.MIN_VALUE; // INT_MIN = no zone (single sample)
    int zoneCents = 0;

    // C source.cpp:127 / multisample_range.h — multisample key/velocity zone matching
    if (!sourceZones[s].isEmpty()) {
      CompiledKeyZone matchedZone = null;
      for (CompiledKeyZone kz : sourceZones[s]) {
        if (kz.sample != null
            && note >= kz.minPitch
            && note <= kz.maxPitch
            && vel >= kz.minVelocity
            && vel <= kz.maxVelocity) {
          matchedZone = kz;
          break;
        }
      }
      if (matchedZone != null) {
        targetSample = matchedZone.sample;
        len = (int) targetSample.lengthInSamples;
        startFrame = matchedZone.startSamplePos;
        endFrame = matchedZone.endSamplePos == -1 ? len : Math.min(matchedZone.endSamplePos, len);
        looping = matchedZone.looping;
        loopStartFrame = matchedZone.startLoopPos;
        loopEndPos = matchedZone.endLoopPos > 0 ? Math.min(matchedZone.endLoopPos, len) : 0;
        zoneTranspose = matchedZone.transpose;
        zoneCents = matchedZone.cents;
      }
    }

    if (targetSample == null) {
      targetSample = samples[s];
      if (targetSample != null) {
        len = (int) targetSample.lengthInSamples;
        endFrame = sampleEndPoint[s] == 65535 ? len : Math.min(sampleEndPoint[s], len);
        loopEndPos = sampleLoopEnd[s] > 0 ? Math.min(sampleLoopEnd[s], len) : 0;
      }
    }

    if (targetSample != null) {
      // Carry the matched multisample zone's transpose (authoritative tuning) onto the source;
      // INT_MIN = single sample / no zone -> pitch falls back to the WAV-root-derived transpose.
      targetVoice.sources[s].zoneTranspose = zoneTranspose;
      targetVoice.sources[s].zoneCents = zoneCents;
      // C SampleControls.interpolationMode — per-source lo-fi linear interpolation across every
      // unison part.
      targetVoice.sources[s].setLinearInterpolation(sourceLinearInterpolation[s]);

      // C sample_playback_guide.cpp:71-81, voice_sample_playback_guide.cpp:36-68 — convert
      // file-coordinate start/end and loop points into directional playback bounds.
      boolean reversed = sampleReverse[s];
      int playDir = reversed ? -1 : 1;
      int dirStart = !reversed ? startFrame : (endFrame - 1);
      int dirEnd = !reversed ? endFrame : (startFrame - 1);
      int rawLoopStart = loopStartFrame > 0 ? loopStartFrame : 0;
      int rawLoopEnd = loopEndPos > 0 ? loopEndPos : 0;
      // C voice_sample_playback_guide.cpp:38-68 — decrement reversed markers first (keeping 0 as 0)
      // and check marker presence (!= 0) AFTER conversion so raw position 1 -> 0 means no marker.
      int loopStartPlaybackAtSample = !reversed ? rawLoopStart : rawLoopEnd;
      int loopEndPlaybackAtSample = !reversed ? rawLoopEnd : rawLoopStart;
      if (reversed) {
        if (loopStartPlaybackAtSample != 0) loopStartPlaybackAtSample--;
        if (loopEndPlaybackAtSample != 0) loopEndPlaybackAtSample--;
      }
      boolean hasLoopStart = loopStartPlaybackAtSample != 0;
      boolean hasLoopEnd = loopEndPlaybackAtSample != 0;
      int dirLoopStart = hasLoopStart ? loopStartPlaybackAtSample : dirStart;
      int dirLoopEnd = hasLoopEnd ? loopEndPlaybackAtSample : dirEnd;

      boolean ts = sampleTimestretch[s];
      if (ts) {
        targetVoice.sources[s].setupSampleTimeStretch(
            targetSample,
            dirStart,
            dirEnd,
            playDir,
            looping,
            dirLoopStart,
            hasLoopEnd,
            dirLoopEnd,
            16777216);
      } else {
        targetVoice.sources[s].setupSample(
            targetSample,
            dirStart,
            dirEnd,
            playDir,
            looping,
            dirLoopStart,
            hasLoopEnd,
            dirLoopEnd,
            samplesLate);
      }
    } else {
      targetVoice.sources[s].clearSample();
    }
  }

  public void releaseNote(int note) {
    releaseNote(note, -1);
  }

  public void releaseNote(int note, int midiChannel) {
    if (arpEnabled()) {
      arpInstr.reset();
      arpeggiator.noteOff(arpSettings, note, arpInstr);
      // C sound.cpp:1588-1599 — release glide notes first, then post-arp note-offs.
      for (int n = 0; n < 4; n++) {
        int glideOff = arpInstr.glideNoteCodeOffPostArp[n];
        if (glideOff == Arpeggiator.ARP_NOTE_NONE) {
          break;
        }
        releaseVoice(glideOff, -1);
      }
      for (int n = 0; n < 4; n++) {
        int noteOff = arpInstr.noteCodeOffPostArp[n];
        if (noteOff == Arpeggiator.ARP_NOTE_NONE) {
          break;
        }
        releaseVoice(noteOff, -1);
      }
      if (arpInstr.arpNoteOn != null) {
        int v = arpInstr.arpNoteOn.velocity;
        if (v > 0) {
          for (int n = 0; n < arpInstr.arpNoteOn.noteCodeOnPostArp.length; n++) {
            int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[n];
            if (noteOn == Arpeggiator.ARP_NOTE_NONE) {
              break;
            }
            if (arpInstr.arpNoteOn.noteStatus[n] != Arpeggiator.ArpNoteStatus.PENDING) {
              continue;
            }
            triggerVoice(noteOn, v, -1);
            arpInstr.arpNoteOn.noteStatus[n] = Arpeggiator.ArpNoteStatus.PLAYING;
          }
        }
        arpInstr.arpNoteOn = null;
      }
      return;
    }
    releaseVoice(note, midiChannel);
  }

  public void releaseVoice(int note, int midiChannel) {
    synchronized (voices) {
      for (var v : voices) {
        // C sound.cpp:1874-1876 — Sound::noteOffPostArpeggiator matches voices by
        // noteCodeAfterArpeggiation == noteCode and envelopes[0].state < EnvelopeStage::RELEASE,
        // with no MIDI channel test.
        if (v.active
            && v.note == note
            && v.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0) {
          v.noteOff();
        }
      }
    }
  }

  public void releaseAllNotes() {
    synchronized (voices) {
      for (var v : voices) {
        if (v.active) {
          v.noteOff();
        }
      }
    }
  }

  private int resolveGlobalReverbParamFinalValue() {
    Patcher.evaluateGlobalRangeDestinations(patchCableSet, globalSourceValues);
    int reverbKnob = getSmoothedPatchedParamValue(Param.GLOBAL_REVERB_AMOUNT);
    Patcher.Destination dRev = null;
    for (var dest : patchCableSet.destinations) {
      if (dest.paramId == Param.GLOBAL_REVERB_AMOUNT && dest.targetSource < 0) {
        dRev = dest;
        break;
      }
    }
    return (dRev != null)
        ? Functions.getFinalParameterValueVolume(
            Functions.getParamNeutralValue(Param.GLOBAL_REVERB_AMOUNT),
            Patcher.combineCablesLinear(dRev, reverbKnob, globalSourceValues))
        : Patcher.computeFinalValueForParam(Param.GLOBAL_REVERB_AMOUNT, reverbKnob);
  }

  @Override
  public int computeReverbSendAmount(int reverbAmountAdjust) {
    // C sound.cpp:2450-2453, patcher.cpp:133-137, 219 — resolve smoothed GLOBAL_REVERB_AMOUNT with
    // active patch cables (globalSourceValues) once per block before scaling by reverbAmountAdjust
    // << 5.
    int resolvedReverb =
        hasBlockResolvedReverbValue
            ? blockResolvedReverbValue
            : resolveGlobalReverbParamFinalValue();
    return Functions.lshiftAndSaturate(
        Functions.multiply_32x32_rshift32_rounded(reverbAmountAdjust, resolvedReverb), 5);
  }

  public boolean noteIsOn(int note, boolean resetTimeEntered) {
    synchronized (voices) {
      for (var voice : voices) {
        if (voice.active
            && voice.note == note
            && voice.envelopes[0].state.compareTo(Envelope.Stage.RELEASE) < 0) {
          if (resetTimeEntered) {
            voice.envelopes[0].resetTimeEntered();
          }
          return true;
        }
      }
    }
    return false;
  }
}
