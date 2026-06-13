package org.chuck.deluge.firmware2;

import java.util.ArrayList;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.engine.GlobalSidechainBus;
import org.chuck.deluge.firmware.engine.Stutterer;
import org.chuck.deluge.firmware2.FilterSet.FilterMode;
import org.chuck.deluge.firmware2.Lfo.LfoConfig;
import org.chuck.deluge.firmware2.Oscillator.OscType;

/**
 * Replica of the Deluge C++ Sound class configuration. Self-contained in firmware2 package with
 * zero dependencies on legacy firmware.
 */
public class Sound extends GlobalEffectable {
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
  public int clippingAmount = 0;

  /** C: UNPATCHED_PORTAMENTO knob (raw Q31); INT_MIN = off. */
  public int portamentoKnob = Integer.MIN_VALUE;

  /** C: sound.h:141 — previous note code, stored for portamento; INT_MIN = none yet. */
  public int lastNoteCode = Integer.MIN_VALUE;

  /** C: sound.h:286 — saturation output left-shift derived from clippingAmount. */
  public int getShiftAmountForSaturation() {
    return (clippingAmount >= 2) ? (clippingAmount - 2) : 0;
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
   * Per-source DX7 patch (156-byte). Mirrors C sources[s].ensureDxPatch(); null = source not DX7.
   */
  public final byte[][] sourceDx7Patch = new byte[2][];

  public final int[] globalSourceValues = new int[3];

  /**
   * The patch's per-param "knob"/preset values (bipolar Q31), mirroring the C {@code
   * ParamManager}'s patched-param set. The patcher reads these via {@link
   * #getSmoothedPatchedParamValue} and runs them through the firmware curves.
   */
  public final int[] patchedParamValues = new int[200];

  /** The patch's modulation cables (mirrors the C {@code ParamManager}'s PatchCableSet). */
  public final Patcher.PatchCableSet patchCableSet = new Patcher.PatchCableSet();

  /** C: {@code Sound::getSmoothedPatchedParamValue}. No automation smoothing yet (static value). */
  public int getSmoothedPatchedParamValue(int p) {
    return patchedParamValues[p];
  }

  public final LfoConfig[] lfoConfig = new LfoConfig[4];
  public int timePerInternalTickInverse = 1 << 20;

  /** C: sound.h:358 — {@code deluge::fast_vector<ActiveVoice> voices_;} */
  public final ArrayList<Voice> voices = new ArrayList<>();

  public enum PolyphonyMode {
    POLY,
    MONO,
    LEGATO
  }

  public PolyphonyMode polyphonic = PolyphonyMode.POLY;
  public int maxPolyphony = 64;

  public final Sample[] samples = new Sample[2];
  public final int[] sampleStartPoint = {0, 0};
  public final int[] sampleEndPoint = {65535, 65535};
  public final int[] sampleLoopMode = {0, 0}; // 0 = off, 1 = loop
  public final int[] sampleLoopStart = {0, 0};
  public final boolean[] sampleReverse = {false, false};
  public final boolean[] sampleTimestretch = {false, false};

  public final Arpeggiator.Synth arpeggiator = new Arpeggiator.Synth();
  public final Arpeggiator.Settings arpSettings = new Arpeggiator.Settings();
  public final Arpeggiator.ArpReturnInstruction arpInstr = new Arpeggiator.ArpReturnInstruction();
  public int arpPhaseIncrement = 0;

  public final Lfo[] globalLfos = {new Lfo(), new Lfo()};
  public final Lfo.LfoType[] lfoWaveforms = {
    Lfo.LfoType.SINE, Lfo.LfoType.TRIANGLE,
    Lfo.LfoType.SINE, Lfo.LfoType.TRIANGLE
  };

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
  public final Stutterer stutterer = new Stutterer();
  public final Compressor compressor = new Compressor();

  // Per-sound delay (C: ModControllableAudio::delay, applied in processFX after modFX). The
  // firmware's delay is PER-SOUND; the FirmwareAudioEngine master delay covers the song-level
  // GlobalEffectable delay. Sync is driven externally (syncLevel 0 + userDelayRate), the same
  // scheme the master delay uses. delayUserRate <= 0 or delayFeedbackAmount < 256 → inert.
  public final Delay delay = new Delay();
  public final Delay.State delayState = new Delay.State();
  public int delayUserRate = 0; // 0 = no per-sound delay
  public int delayFeedbackAmount = 0;
  public boolean delayPingPong = false;
  public boolean delayAnalog = false;

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

  private static void addCable(Sound cfg, PatchSource source, int paramId, int amount) {
    Patcher.PatchCable cable = new Patcher.PatchCable();
    cable.source = source.ordinal();
    cable.amount = amount;
    cfg.patchCableSet.addCable(paramId, cable);
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

    // 2. Global LFO rendering
    int phaseInc1 =
        Patcher.computeFinalValueForParam(
            Param.GLOBAL_LFO_FREQ_1, patchedParamValues[Param.GLOBAL_LFO_FREQ_1]);
    globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal()] =
        globalLfos[0].render(numSamples, lfoWaveforms[0], phaseInc1);

    int phaseInc2 =
        Patcher.computeFinalValueForParam(
            Param.GLOBAL_LFO_FREQ_2, patchedParamValues[Param.GLOBAL_LFO_FREQ_2]);
    globalSourceValues[PatchSource.LFO_GLOBAL_2.ordinal()] =
        globalLfos[1].render(numSamples, lfoWaveforms[2], phaseInc2);

    // 3. Arpeggiator clock & processing
    if (arpEnabled() && arpPhaseIncrement > 0) {
      long gateU = (arpSettings.gate & 0xFFFFFFFFL);
      long gateBiased = gateU + (1L << 31);
      int gateThreshold = (int) (gateBiased >> 8);
      arpeggiator.render(arpSettings, arpInstr, numSamples, gateThreshold, arpPhaseIncrement);

      for (int n = 0; n < 4; n++) {
        int noteOff = arpInstr.noteCodeOffPostArp[n];
        if (noteOff != Arpeggiator.ARP_NOTE_NONE) {
          releaseVoice(noteOff, -1);
        }
      }

      if (arpInstr.arpNoteOn != null) {
        int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[0];
        int vel = arpInstr.arpNoteOn.velocity;
        if (vel <= 0) vel = 64;
        triggerVoice(noteOn, vel, -1);
        arpInstr.arpNoteOn.noteStatus[0] = Arpeggiator.ArpNoteStatus.PLAYING;
        arpInstr.arpNoteOn = null;
      }
    }

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
        Patcher.performInitialPatching(patchedParamValues, v.sourceValues, v.paramFinalValues);
        v.render(buffer, numSamples, lpfMode != FilterMode.OFF, hpfMode != FilterMode.OFF);
      }
    }

    boolean arpHolding = arpEnabled() && arpeggiator.hasAnyInputNotesActive();
    // C sound.cpp:2165 — keep rendering the FX tail while the per-sound delay still has repeats
    // pending, so the delay tail isn't cut when the note's voices end.
    boolean delayTailActive = delay.repeatsUntilAbandon != 0;
    if (!hasActiveVoices && !arpHolding && !delayTailActive) {
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

    int postFXKnob = patchedParamValues[Param.GLOBAL_VOLUME_POST_FX];
    Patcher.Destination dFx = null;
    for (var dest : patchCableSet.destinations) {
      if (dest.paramId == Param.GLOBAL_VOLUME_POST_FX) {
        dFx = dest;
        break;
      }
    }
    int postFXVal =
        (dFx != null)
            ? Patcher.combineCablesLinear(dFx, postFXKnob, globalSourceValues)
            : postFXKnob;
    int neutralFX = Functions.getParamNeutralValue(Param.GLOBAL_VOLUME_POST_FX);
    int[] postFXVolumeHolder = {Functions.getFinalParameterValueVolume(neutralFX, postFXVal)};
    int[] postReverbVolumeHolder = {2147483647};

    srrBitcrush.process(fxIntBuffer, numSamples, bitcrushParam, srrParam, postFXVolumeHolder);

    // Stutterer still uses StereoSample[]
    for (int i = 0; i < numSamples; i++) {
      fxStereoBuffer[i].l = fxIntBuffer[i][0];
      fxStereoBuffer[i].r = fxIntBuffer[i][1];
    }
    stutterer.processStutter(fxStereoBuffer, null);
    for (int i = 0; i < numSamples; i++) {
      fxIntBuffer[i][0] = fxStereoBuffer[i].l;
      fxIntBuffer[i][1] = fxStereoBuffer[i].r;
    }

    if (modFXType == ModFx.ModFXType.GRAIN) {
      granular.processGrainFX(
          fxIntBuffer,
          numSamples,
          modFXRateIncrement,
          modFXDepth << 1,
          modFXOffset,
          modFXFeedback,
          postFXVolumeHolder,
          true,
          (float) currentBpm);
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
          true);
    }

    eq.process(
        fxIntBuffer,
        numSamples,
        eqBassParam,
        eqTrebleParam,
        patchedParamValues[Param.UNPATCHED_BASS_FREQ],
        patchedParamValues[Param.UNPATCHED_TREBLE_FREQ]);

    // Per-sound delay (C: processFX delay.process, after EQ). Rate is driven externally with the
    // delay's own sync disabled (syncLevel 0), matching the master-delay scheme; FirmwareSound
    // computes delayUserRate from the per-sound delay sync + BPM.
    if (delayUserRate > 0 && delayFeedbackAmount >= 256) {
      delay.syncLevel = 0;
      delay.pingPong = delayPingPong;
      delay.analog = delayAnalog;
      delayState.userDelayRate = delayUserRate;
      delayState.delayFeedbackAmount = delayFeedbackAmount;
      delay.setupWorkingState(delayState, 1 << 20, hasActiveVoices);
      delay.process(fxIntBuffer, numSamples, delayState);
    }

    int compThreshold = patchedParamValues[Param.UNPATCHED_COMPRESSOR_THRESHOLD];
    compressor.setThreshold(compThreshold);
    if (compThreshold > 0) {
      compressor.renderVolNeutral(fxIntBuffer, postFXVolumeHolder[0]);
    } else {
      compressor.reset();
    }

    int postReverbKnob = patchedParamValues[Param.GLOBAL_VOLUME_POST_REVERB_SEND];
    Patcher.Destination dReverb = null;
    for (var dest : patchCableSet.destinations) {
      if (dest.paramId == Param.GLOBAL_VOLUME_POST_REVERB_SEND) {
        dReverb = dest;
        break;
      }
    }
    int postReverbVal =
        (dReverb != null)
            ? Patcher.combineCablesLinear(dReverb, postReverbKnob, globalSourceValues)
            : postReverbKnob;
    int neutralReverb = Functions.getParamNeutralValue(Param.GLOBAL_VOLUME_POST_REVERB_SEND);
    postReverbVolumeHolder[0] =
        Functions.getFinalParameterValueVolume(neutralReverb, postReverbVal);

    super.postFXVolume = postFXVolumeHolder[0];
    super.postReverbVolume = postReverbVolumeHolder[0];

    for (int i = 0; i < numSamples; i++) {
      buffer[i * 2] = fxIntBuffer[i][0];
      buffer[i * 2 + 1] = fxIntBuffer[i][1];
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
      arpeggiator.noteOn(arpSettings, note, vel, arpInstr, midiChannel, null);
      if (arpInstr.arpNoteOn != null) {
        int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[0];
        int v = arpInstr.arpNoteOn.velocity;
        if (v <= 0) v = 64;
        triggerVoice(noteOn, v, midiChannel);
        arpInstr.arpNoteOn.noteStatus[0] = Arpeggiator.ArpNoteStatus.PLAYING;
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
    synchronized (voices) {
      setupUnisonDetuners();
      setupUnisonStereoSpread();
      calculateEffectiveVolume();

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
          if (samples[s] != null) {
            boolean ts = sampleTimestretch[s] && !sampleReverse[s];
            int playDir = sampleReverse[s] ? -1 : 1;
            int len = (int) samples[s].lengthInSamples;
            int startFrame = sampleStartPoint[s];
            int endFrame = sampleEndPoint[s] == 65535 ? len : Math.min(sampleEndPoint[s], len);
            boolean looping = sampleLoopMode[s] == 1;
            int loopStartFrame = sampleLoopStart[s];
            if (ts) {
              int tsRatio = (int) Math.max(1, 16777216.0 * (samples[s].sampleRate / 44100.0));
              targetVoice.sources[s].setupSampleTimeStretch(
                  samples[s], startFrame, playDir, tsRatio);
            } else {
              targetVoice.sources[s].setupSample(
                  samples[s], startFrame, endFrame, playDir, looping, loopStartFrame, samplesLate);
            }
          } else {
            targetVoice.sources[s].sampleRef = null;
            targetVoice.sources[s].voiceSample.active = false;
          }
        }
        targetVoice.noteOn(note, vel, midiChannel, mpeValues);
      }
    }
  }

  public void releaseNote(int note) {
    releaseNote(note, -1);
  }

  public void releaseNote(int note, int midiChannel) {
    if (arpEnabled()) {
      arpeggiator.noteOff(arpSettings, note, arpInstr);
      for (int n = 0; n < 4; n++) {
        int noteOff = arpInstr.noteCodeOffPostArp[n];
        if (noteOff != Arpeggiator.ARP_NOTE_NONE) {
          releaseVoice(noteOff, -1);
        }
      }
      if (arpInstr.arpNoteOn != null) {
        int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[0];
        int v = arpInstr.arpNoteOn.velocity;
        if (v > 0) triggerVoice(noteOn, v, -1);
      }
      return;
    }
    releaseVoice(note, midiChannel);
  }

  public void releaseVoice(int note, int midiChannel) {
    synchronized (voices) {
      for (var v : voices) {
        if (v.active && v.note == note) {
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
}
