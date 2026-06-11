package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.fx.ModFXProcessor;
import org.chuck.deluge.firmware.dsp.fx.ModFXType;
import org.chuck.deluge.firmware.dsp.granular.GranularProcessor;
import org.chuck.deluge.firmware.model.PolyphonyMode;
import org.chuck.deluge.firmware.modulation.LFO;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.Destination;
import org.chuck.deluge.firmware.modulation.patch.PatchCable;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.modulation.patch.Patcher;
import org.chuck.deluge.firmware.modulation.sidechain.SideChain;
import org.chuck.deluge.model.tuning.ScalaScale;

/**
 * Port of the Deluge's Sound class. This is the central high-fidelity synthesis engine for a single
 * instrument or kit.
 */
public class FirmwareSound extends org.chuck.deluge.firmware2.GlobalEffectable {
  public enum SynthMode {
    SUBTRACTIVE,
    FM,
    RINGMOD
  }

  // Legacy FirmwareVoice path retired — always routes through firmware2.

  public final org.chuck.deluge.firmware2.Sound fw2Sound = new org.chuck.deluge.firmware2.Sound();
  private int[] fw2ScratchBuffer = null;
  // Transport clock seams: updated each render block by the PlaybackHandler so the
  // SamplePlaybackGuide sync methods (getSyncedNumSamplesIn, adjustPitchToCorrectDriftFromSync)
  // can correct clip-synced drift. Set before each render block.
  public int transportTickCount;
  public int transportTimePerTick = 44100 / 96; // default: 96 PPQN @ 44.1kHz
  public boolean transportExternalClockActive;
  public int transportTimeSinceLastTick;

  public final LFO[] globalLfos = new LFO[2];
  public final LFO.LFOType[] lfoWaveforms = {
    LFO.LFOType.SINE, LFO.LFOType.TRIANGLE,
    LFO.LFOType.SINE, LFO.LFOType.TRIANGLE
  };
  public final org.chuck.deluge.firmware.model.sample.Sample[] samples =
      new org.chuck.deluge.firmware.model.sample.Sample[2];
  public final org.chuck.deluge.firmware2.Sample[] fw2SampleCache =
      new org.chuck.deluge.firmware2.Sample[2];
  public final org.chuck.deluge.firmware.model.sample.SampleVoiceSettings[] sampleSettings = {
    new org.chuck.deluge.firmware.model.sample.SampleVoiceSettings(),
    new org.chuck.deluge.firmware.model.sample.SampleVoiceSettings()
  };
  public final org.chuck.deluge.firmware.dsp.oscillators.OscType[] oscTypes = {
    org.chuck.deluge.firmware.dsp.oscillators.OscType.SINE,
    org.chuck.deluge.firmware.dsp.oscillators.OscType.SINE
  };
  public int maxPolyphony = 64;
  public PolyphonyMode polyphonic = PolyphonyMode.POLY;
  public boolean isDrum = false;
  public int[] paramNeutralValues = new int[200];

  /** Raw bipolar Q31 knob values for firmware2 Patcher. */
  public final int[] paramKnobs = new int[200];

  /**
   * True once the factory has populated {@link #paramKnobs} (paramNeutralValues base + env-rate
   * knob overrides). Directly-constructed sounds (tests configuring only paramNeutralValues) leave
   * this false, so the firmware2 bridge falls back to paramNeutralValues for them.
   */
  public boolean paramKnobsPopulated = false;

  public int[] globalSourceValues = new int[PatchSource.kNumPatchSources];
  private final int[] globalParamFinalValues = new int[Param.kNumParams];
  private final Patcher globalPatcher = new Patcher();

  // ── Ported High-Fidelity Logic ──
  public SynthMode synthMode = SynthMode.SUBTRACTIVE;
  public int numUnison = 1;
  public int unisonDetune = 8;
  public int unisonStereoSpread = 0;
  public final int[] monophonicExpressionValues = new int[3]; // X, Y, Z

  public final ModFXProcessor modFX = new ModFXProcessor();
  public ModFXType modFXType = ModFXType.NONE;
  public int modFXRateIncrement = 0; // Q32 LFO phase increment per sample
  public int modFXDepth = 0; // Q31
  public int modFXOffset = 0; // Q31
  public int modFXFeedback = 0; // Q31

  public final org.chuck.deluge.firmware.dsp.fx.SrrBitcrushProcessor srrBitcrush =
      new org.chuck.deluge.firmware.dsp.fx.SrrBitcrushProcessor();
  public int bitcrushParam = Integer.MIN_VALUE; // bipolar Q31; MIN_VALUE = off
  public int srrParam = Integer.MIN_VALUE; // bipolar Q31; MIN_VALUE = off

  public final org.chuck.deluge.firmware.dsp.fx.EqProcessor eq =
      new org.chuck.deluge.firmware.dsp.fx.EqProcessor();
  public int eqBassParam = 0; // bipolar Q31; 0 = flat
  public int eqTrebleParam = 0; // bipolar Q31; 0 = flat

  // C: firmware2 Arpeggiator — faithful port of modulation/arpeggiator.cpp.
  public final org.chuck.deluge.firmware2.Arpeggiator.Synth arpeggiator =
      new org.chuck.deluge.firmware2.Arpeggiator.Synth();
  public final org.chuck.deluge.firmware2.Arpeggiator.Settings arpSettings =
      new org.chuck.deluge.firmware2.Arpeggiator.Settings();
  private final org.chuck.deluge.firmware2.Arpeggiator.ArpReturnInstruction arpInstr =
      new org.chuck.deluge.firmware2.Arpeggiator.ArpReturnInstruction();
  public int arpPhaseIncrement = 0; // arp clock (Q-units; one step == 1<<24 of gatePos)
  public int arpDivision =
      16; // step note division (16 = 16th note); used to derive arpPhaseIncrement

  // Granular mod-FX (ModFXType.GRAIN routes here instead of the LFO-based ModFXProcessor).
  public float currentBpm = 120.0f;

  // DX7 (Dexed) patch — when set, the voice renders via the real DX7 engine instead of the
  // Deluge's native FM. The 156-byte unpacked single-voice patch defines the algorithm + 6
  // operators + per-op EGs; engineType: -1=auto (MkI for algos 3/5 w/ feedback), 0=modern, 1=MkI.
  public byte[] dx7Patch = null;
  public int dx7EngineType = -1;
  public int dx7RandomDetune = 0;

  public boolean isDx7() {
    return dx7Patch != null;
  }

  public boolean arpEnabled() {
    return arpSettings.mode != org.chuck.deluge.firmware2.Arpeggiator.ArpMode.OFF;
  }

  public final GranularProcessor granular = new GranularProcessor();
  public final SideChain sidechain = new SideChain();
  public int sidechainSend = 0;
  public final Stutterer stutterer = new Stutterer();
  public final org.chuck.deluge.firmware.modulation.params.ParamManager paramManager =
      new org.chuck.deluge.firmware.modulation.params.ParamManager();
  private int silentBlockCount = 200; // Starts gated on boot
  private StereoSample[] fxStereoBuffer = new StereoSample[256];

  public FirmwareSound() {
    for (int i = 0; i < 256; i++) fxStereoBuffer[i] = new StereoSample();
    for (int i = 0; i < globalLfos.length; i++) globalLfos[i] = new LFO();
    // C Sound::initParams (sound.cpp:131-187, faithfully ported to fw2 Sound.initParams).
    org.chuck.deluge.firmware2.Sound.initParams(paramNeutralValues);

    // C Sound::setupAsDefaultSynth (sound.cpp:223-259): filter overrides on top of initParams.
    paramNeutralValues[Param.LOCAL_LPF_FREQ] = 0x10000000; // C:228
    paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = 0xA2000000; // C:227
    paramNeutralValues[Param.LOCAL_LPF_MORPH] = 0;
    paramNeutralValues[Param.LOCAL_HPF_FREQ] = 0;
    paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = 0;
    paramNeutralValues[Param.LOCAL_HPF_MORPH] = 0;

    // Pitch-adjust KNOBS must be 0 (centre = no offset), as initParams and the C set them
    // (sound.cpp:152,183-186 setCurrentValueBasicForSetup(0)). The previous code set them to
    // 16777216 (= kMaxSampleValue), confusing the param's OUTPUT neutral with its knob value: a
    // non-zero knob is fed through combineCablesExp + getExp, which shifted every note ~+37 cents
    // per
    // param (LOCAL_PITCH_ADJUST + LOCAL_OSC_A_PITCH_ADJUST compounded to ~+74 cents sharp — the
    // cause
    // of the synth fidelity mismatch). getParamNeutralValue(p)=16777216 is the curve neutral
    // applied
    // by the Patcher; the knob stays 0.
    paramNeutralValues[Param.LOCAL_PITCH_ADJUST] = 0;
    paramNeutralValues[Param.LOCAL_OSC_A_PITCH_ADJUST] = 0;
    paramNeutralValues[Param.LOCAL_OSC_B_PITCH_ADJUST] = 0;
    paramNeutralValues[Param.LOCAL_MODULATOR_0_PITCH_ADJUST] = 0;
    paramNeutralValues[Param.LOCAL_MODULATOR_1_PITCH_ADJUST] = 0;

    // Exponential rates defaulting to 0 (already set by initParams, re-stated for clarity):
    paramNeutralValues[Param.GLOBAL_DELAY_RATE] = 0;
    paramNeutralValues[Param.GLOBAL_ARP_RATE] = 0;
    paramNeutralValues[Param.GLOBAL_MOD_FX_RATE] = 0;

    // C setupAsDefaultSynth envelope defaults (sound.cpp:229-236).
    paramNeutralValues[Param.LOCAL_ENV_0_ATTACK] = 0x80000000; // C:229 — instant attack
    paramNeutralValues[Param.LOCAL_ENV_0_DECAY] = 0xE6666654; // C:230
    paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] = 0x7FFFFFFF; // C:231 — max
    paramNeutralValues[Param.LOCAL_ENV_0_RELEASE] = 0x851EB851; // C:232
    paramNeutralValues[Param.LOCAL_ENV_1_ATTACK] = 0xA3D70A37; // C:233
    paramNeutralValues[Param.LOCAL_ENV_1_DECAY] = 0xA3D70A37; // C:234
    paramNeutralValues[Param.LOCAL_ENV_1_SUSTAIN] = 0xFFFFFFE9; // C:235
    paramNeutralValues[Param.LOCAL_ENV_1_RELEASE] = 0xE6666654; // C:236
  }

  public SynthMode getSynthMode() {
    return synthMode;
  }

  /**
   * Note-to-phaseIncrement for the subtractive oscillator path. Computed from equal temperament
   * (2^(n/12)*440) rather than the firmware's {@code noteIntervalTable[12]} + octave-shift because
   * the firmware dispatches through pitch-adjust paths that combine table lookup +
   * multiply_32x32_rshift32(table[i], pitchAdjustNeutralValue) + {13-octave} shift; the effective
   * scalar differs from the direct table → phaseIncrement mapping used here. The Math.pow formula
   * produces the identical result to within ~0.007 cent (FirmwareTuningTest verified across 5
   * octaves × 4 waveforms). The firmware tables are available in {@link
   * org.chuck.deluge.firmware.util.LookupTables#noteIntervalTable} for future alignment.
   */
  public static int noteToPhaseInc(int note) {
    ScalaScale scale = ScalaScale.getActiveScale();
    double freq = (scale != null) ? scale.mtof(note) : (440.0 * Math.pow(2.0, (note - 69) / 12.0));
    return (int) (freq * (16777216.0 / 44100.0));
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    if (numSamples > fxStereoBuffer.length) {
      int oldLen = fxStereoBuffer.length;
      fxStereoBuffer = java.util.Arrays.copyOf(fxStereoBuffer, numSamples);
      for (int i = oldLen; i < numSamples; i++) {
        fxStereoBuffer[i] = new StereoSample();
      }
    }
    int scHit = GlobalSidechainBus.getActiveFrameHit();
    if (scHit != 0) {
      sidechain.registerHit(scHit);
    }
    // 0. Arpeggiator clock (C: arpeggiator.cpp render): advance the arp and action
    // any note-on/off it emits this block.
    if (arpEnabled() && arpPhaseIncrement > 0) {
      // C: gateThreshold = (uint32_t)((int64_t)gate + 2147483648) >> 8
      // Converts bipolar Q31 gate → unsigned 0..(1<<24) range.
      long gateU = (arpSettings.gate & 0xFFFFFFFFL); // treat gate as unsigned 32-bit
      long gateBiased = gateU + (1L << 31); // +2^31 bias
      int gateThreshold = (int) (gateBiased >> 8); // scale to 24-bit
      arpeggiator.render(arpSettings, arpInstr, numSamples, gateThreshold, arpPhaseIncrement);

      // C: handle note-offs (arpInstr.noteCodeOffPostArp[])
      for (int n = 0; n < 4; n++) {
        int noteOff = arpInstr.noteCodeOffPostArp[n];
        if (noteOff != org.chuck.deluge.firmware2.Arpeggiator.ARP_NOTE_NONE) {
          releaseVoice(noteOff, -1);
        }
      }

      // C: handle note-on (arpInstr.arpNoteOn)
      if (arpInstr.arpNoteOn != null) {
        int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[0];
        int vel = arpInstr.arpNoteOn.velocity;
        if (vel <= 0) vel = 64; // safety default
        triggerVoice(noteOn, vel, -1);
        // C: mark as PLAYING so handlePendingNotes won't re-fire the same note
        arpInstr.arpNoteOn.noteStatus[0] =
            org.chuck.deluge.firmware2.Arpeggiator.ArpNoteStatus.PLAYING;
        arpInstr.arpNoteOn = null;
      }
    }

    boolean hasActiveVoices;
    synchronized (fw2Sound.voices) {
      hasActiveVoices = !fw2Sound.voices.isEmpty();
    }
    boolean arpHolding = arpEnabled() && arpeggiator.hasAnyInputNotesActive();
    if (!hasActiveVoices && !arpHolding && silentBlockCount > 100) {
      // Fast bypass: flat buffer is already silent
      return;
    }

    // 1. Update Global LFOs. C: Sound::getGlobalLFOPhaseIncrement uses paramFinalValues
    // (patcher-curve-applied), not raw knob values.
    int phaseInc1 =
        org.chuck.deluge.firmware2.Patcher.computeFinalValueForParam(
            Param.GLOBAL_LFO_FREQ_1, paramNeutralValues[Param.GLOBAL_LFO_FREQ_1]);
    globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal()] =
        globalLfos[0].render(numSamples, lfoWaveforms[0], phaseInc1);

    int phaseInc2 =
        org.chuck.deluge.firmware2.Patcher.computeFinalValueForParam(
            Param.GLOBAL_LFO_FREQ_2, paramNeutralValues[Param.GLOBAL_LFO_FREQ_2]);
    globalSourceValues[PatchSource.LFO_GLOBAL_2.ordinal()] =
        globalLfos[1].render(numSamples, lfoWaveforms[2], phaseInc2);

    // 2. Sum Voices directly to flat buffer
    renderVoicesFw2(buffer, numSamples);

    // 3. Convert flat buffer to StereoSample[] fxStereoBuffer for legacy FX
    for (int i = 0; i < numSamples; i++) {
      fxStereoBuffer[i].l = buffer[i * 2];
      fxStereoBuffer[i].r = buffer[i * 2 + 1];
    }

    // 4. Apply High-Fidelity FX Chain (firmware order: SRR/bitcrush → mod FX → stutter → ...)
    // Holders start at Q31 unity (the legacy FX processors' convention; the flat-buffer rewrite
    // briefly used 1<<30, which halved each volume → 4× track attenuation at neutral).
    int[] postFXVolumeHolder = {2147483647};
    int[] postReverbVolumeHolder = {2147483647};

    // Sample-rate reduction + bitcrushing
    srrBitcrush.process(fxStereoBuffer, numSamples, bitcrushParam, srrParam, postFXVolumeHolder);

    // Stutter
    stutterer.processStutter(fxStereoBuffer, paramManager);

    // Modulation FX. GRAIN is a granular processor (different from the LFO-based mod FX); the
    // firmware routes it separately. Mapping: rate, depth (mix), offset (density), feedback (pitch
    // randomness), tempo — mirroring ModControllableAudio::processGrainFX's argument order.
    if (modFXType == ModFXType.GRAIN) {
      granular.processGrainFX(
          fxStereoBuffer,
          modFXRateIncrement,
          modFXDepth << 1,
          modFXOffset,
          modFXFeedback,
          postFXVolumeHolder,
          currentBpm);
    } else {
      modFX.processModFX(
          fxStereoBuffer,
          modFXType,
          modFXRateIncrement,
          modFXDepth,
          postFXVolumeHolder,
          modFXOffset,
          modFXFeedback);
    }

    // Bass/treble EQ
    eq.process(fxStereoBuffer, numSamples, eqBassParam, eqTrebleParam);

    // Sidechain
    int shape = paramNeutralValues[Param.UNPATCHED_SIDECHAIN_SHAPE];
    int scAmount = sidechain.render(numSamples, shape);
    globalSourceValues[PatchSource.SIDECHAIN.ordinal()] = scAmount;
    if (hasSidechainVolumePatch()) {
      globalPatcher.performPatching(
          0, this, paramManager, globalSourceValues, globalParamFinalValues);
      postReverbVolumeHolder[0] = globalParamFinalValues[Param.GLOBAL_VOLUME_POST_REVERB_SEND];
    }

    super.postFXVolume = postFXVolumeHolder[0];
    super.postReverbVolume = postReverbVolumeHolder[0];

    // 5. Convert fxStereoBuffer back to flat buffer
    for (int i = 0; i < numSamples; i++) {
      buffer[i * 2] = fxStereoBuffer[i].l;
      buffer[i * 2 + 1] = fxStereoBuffer[i].r;
    }

    // Update gate status
    if (hasActiveVoices) {
      silentBlockCount = 0;
    } else {
      boolean isSilent = true;
      for (int i = 0; i < numSamples * 2; i++) {
        if (buffer[i] != 0) {
          isSilent = false;
          break;
        }
      }
      if (isSilent) {
        if (silentBlockCount <= 100) {
          silentBlockCount++;
        }
      } else {
        silentBlockCount = 0;
      }
    }
  }

  // Legacy test support:
  public void renderInternal(StereoSample[] buffer, int numSamples, Object unused) {
    int[] flat = new int[numSamples * 2];
    renderInternal(flat, numSamples, (int[]) null);
    for (int i = 0; i < numSamples; i++) {
      buffer[i].l = flat[i * 2];
      buffer[i].r = flat[i * 2 + 1];
    }
  }

  private final int[] voiceMonoBuffer = new int[128];

  private boolean hasSidechainVolumePatch() {
    for (Destination destination : paramManager.getPatchCableSet().destinations) {
      if (destination.paramId != Param.GLOBAL_VOLUME_POST_REVERB_SEND) {
        continue;
      }
      for (PatchCable cable : destination.cables) {
        if (cable.from == PatchSource.SIDECHAIN) {
          return true;
        }
      }
    }
    return false;
  }

  public void triggerNote(int note, int vel) {
    triggerNote(note, vel, -1);
  }

  public void triggerNote(int note, int vel, int midiChannel) {
    // When the arpeggiator is on, held notes go to it; it triggers voices on its own clock.
    if (arpEnabled()) {
      arpeggiator.noteOn(arpSettings, note, vel, arpInstr, midiChannel, null);
      // If the arp immediately returns a note-on (first note, no sync), handle it now
      if (arpInstr.arpNoteOn != null) {
        int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[0];
        int v = arpInstr.arpNoteOn.velocity;
        if (v <= 0) v = 64;
        triggerVoice(noteOn, v, midiChannel);
        arpInstr.arpNoteOn.noteStatus[0] =
            org.chuck.deluge.firmware2.Arpeggiator.ArpNoteStatus.PLAYING;
        arpInstr.arpNoteOn = null;
      }
      return;
    }
    triggerVoice(note, vel, midiChannel);
  }

  private void triggerVoice(int note, int vel, int midiChannel) {
    if (sidechainSend != 0) {
      GlobalSidechainBus.registerHit(sidechainSend);
    }
    triggerVoiceFw2(note, vel, midiChannel, null);
  }

  /** Active voice count. */
  public int getActiveVoiceCount() {
    synchronized (fw2Sound.voices) {
      int n = 0;
      for (var v : fw2Sound.voices) if (v.active) n++;
      return n;
    }
  }

  private void triggerVoiceFw2(int note, int vel, int midiChannel, int[] mpeValues) {
    synchronized (fw2Sound.voices) {
      org.chuck.deluge.firmware2.Voice targetVoice = null;
      if (polyphonic != PolyphonyMode.POLY) {
        for (var v : fw2Sound.voices) {
          if (v.active) {
            targetVoice = v;
            break;
          }
        }
      }
      if (targetVoice == null) {
        for (var v : fw2Sound.voices) {
          if (!v.active) {
            targetVoice = v;
            break;
          }
        }
      }
      if (targetVoice == null && fw2Sound.voices.size() < maxPolyphony) {
        targetVoice = new org.chuck.deluge.firmware2.Voice(fw2Sound);
        fw2Sound.voices.add(targetVoice);
      }
      if (targetVoice == null) {
        int highestRating = Integer.MIN_VALUE;
        for (var v : fw2Sound.voices) {
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
          if (fw2SampleCache[s] != null) {
            boolean ts = sampleSettings[s].timestretch && !sampleSettings[s].reverse;
            int playDir = sampleSettings[s].reverse ? -1 : 1;
            int len = (int) fw2SampleCache[s].lengthInSamples;
            int startFrame = sampleSettings[s].startPoint;
            int endFrame =
                sampleSettings[s].endPoint == 65535
                    ? len
                    : Math.min(sampleSettings[s].endPoint, len);
            boolean looping = sampleSettings[s].loopMode == 1;
            int loopStartFrame = sampleSettings[s].loopStart;
            if (ts) {
              int tsRatio = (int) Math.max(1, 16777216.0 * (samples[s].sampleRate / 44100.0));
              targetVoice.sources[s].setupSampleTimeStretch(
                  fw2SampleCache[s], startFrame, playDir, tsRatio);
            } else {
              targetVoice.sources[s].setupSample(
                  fw2SampleCache[s], startFrame, endFrame, playDir, looping, loopStartFrame);
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

  private void renderVoicesFw2(int[] buffer, int numSamples) {
    // 1. Map current settings from FirmwareSound to fw2Sound
    fw2Sound.synthMode = synthMode == SynthMode.FM ? 1 : synthMode == SynthMode.RINGMOD ? 2 : 0;
    fw2Sound.oscTypes[0] = fw2OscType(oscTypes[0]);
    fw2Sound.oscTypes[1] = fw2OscType(oscTypes[1]);
    // DX7: a loaded patch makes source 0 an OscType::DX7 (the Deluge loads DX7 into an osc source).
    if (dx7Patch != null) {
      fw2Sound.oscTypes[0] = org.chuck.deluge.firmware2.Oscillator.OscType.DX7;
      fw2Sound.sourceDx7Patch[0] = dx7Patch;
    } else {
      fw2Sound.sourceDx7Patch[0] = null;
    }
    fw2Sound.lpfMode = fw2LpfMode();
    fw2Sound.hpfMode = fw2HpfMode();
    fw2Sound.filterRoute = filterRoute.ordinal();
    fw2Sound.numUnison = numUnison;
    fw2Sound.unisonDetune = unisonDetune;
    fw2Sound.unisonStereoSpread = unisonStereoSpread;
    fw2Sound.setupUnisonDetuners();
    fw2Sound.setupUnisonStereoSpread();
    fw2Sound.calculateEffectiveVolume();
    fw2Sound.modulator1ToModulator0 = fmModulator1ToModulator0;
    fw2Sound.fmRatio1 = fmRatio1;
    fw2Sound.fmRatio2 = fmRatio2;
    int m1Transpose = fmModulator1Transpose;
    int m1Cents = fmModulator1Cents;
    if (m1Transpose == 0 && m1Cents == 0 && fmRatio1 > 0.0f) {
      int totalCents = Math.round(1200f * (float) (Math.log(fmRatio1) / Math.log(2)));
      m1Transpose = Math.round(totalCents / 100f);
      m1Cents = totalCents - m1Transpose * 100;
    }
    fw2Sound.modulatorTranspose[0] = m1Transpose;
    fw2Sound.setModulatorCents(0, m1Cents);

    int m2Transpose = fmModulator2Transpose;
    int m2Cents = fmModulator2Cents;
    if (m2Transpose == 0 && m2Cents == 0 && fmRatio2 > 0.0f) {
      int totalCents = Math.round(1200f * (float) (Math.log(fmRatio2) / Math.log(2)));
      m2Transpose = Math.round(totalCents / 100f);
      m2Cents = totalCents - m2Transpose * 100;
    }
    fw2Sound.modulatorTranspose[1] = m2Transpose;
    fw2Sound.setModulatorCents(1, m2Cents);

    // Attach samples to voice sources when the sound is sample-based.
    for (int s = 0; s < 2; s++) {
      if (fw2Sound.oscTypes[s] == org.chuck.deluge.firmware2.Oscillator.OscType.SAMPLE
          && samples[s] != null
          && samples[s].data != null) {
        fw2SampleCache[s] = org.chuck.deluge.firmware2.Sample.fromFirmwareSample(samples[s]);
        boolean ts = sampleSettings[s].timestretch && !sampleSettings[s].reverse;
        int playDir = sampleSettings[s].reverse ? -1 : 1;
        int len = (int) fw2SampleCache[s].lengthInSamples;
        int startFrame = sampleSettings[s].startPoint;
        int endFrame =
            sampleSettings[s].endPoint == 65535 ? len : Math.min(sampleSettings[s].endPoint, len);
        boolean looping = sampleSettings[s].loopMode == 1;
        int loopStartFrame = sampleSettings[s].loopStart;
        // Attach to all active voices
        synchronized (fw2Sound.voices) {
          for (var v : fw2Sound.voices) {
            if (v.active && v.sources[s].sampleRef == null) {
              if (ts) {
                int tsRatio = (int) Math.max(1, 16777216.0 * (samples[s].sampleRate / 44100.0));
                v.sources[s].setupSampleTimeStretch(
                    fw2SampleCache[s], startFrame, playDir, tsRatio);
              } else {
                v.sources[s].setupSample(
                    fw2SampleCache[s], startFrame, endFrame, playDir, looping, loopStartFrame);
              }
            }
          }
        }
      }
    }

    // Propagate globalSourceValues (first 3 values)
    System.arraycopy(globalSourceValues, 0, fw2Sound.globalSourceValues, 0, 3);

    // Propagate LFO config waveforms
    fw2Sound.lfoConfig[0].waveType = fw2LfoType(lfoWaveforms[0]);
    fw2Sound.lfoConfig[1].waveType = fw2LfoType(lfoWaveforms[1]);
    fw2Sound.lfoConfig[2].waveType = fw2LfoType(lfoWaveforms[2]);
    fw2Sound.lfoConfig[3].waveType = fw2LfoType(lfoWaveforms[3]);

    // Bridge the patch's param knobs into the firmware2 Sound as C knob values.
    System.arraycopy(
        paramNeutralValues,
        0,
        fw2Sound.patchedParamValues,
        0,
        Math.min(paramNeutralValues.length, fw2Sound.patchedParamValues.length));
    if (paramKnobsPopulated) {
      for (int i = 0; i < Param.kNumParams; i++) {
        if (paramKnobs[i] != 0) {
          fw2Sound.patchedParamValues[i] = paramKnobs[i];
        }
      }
    }
    if (fmModulatorAmountBase[0] != Integer.MIN_VALUE) {
      fw2Sound.patchedParamValues[org.chuck.deluge.firmware2.Param.LOCAL_MODULATOR_0_VOLUME] =
          fmModulatorAmountBase[0];
    }
    if (fmModulatorAmountBase[1] != Integer.MIN_VALUE) {
      fw2Sound.patchedParamValues[org.chuck.deluge.firmware2.Param.LOCAL_MODULATOR_1_VOLUME] =
          fmModulatorAmountBase[1];
    }
    fw2Sound.patchCableSet.destinations.clear();
    for (Destination d : paramManager.getPatchCableSet().destinations) {
      for (PatchCable c : d.cables) {
        var fc = new org.chuck.deluge.firmware2.Patcher.PatchCable();
        fc.source = c.from.ordinal(); // PatchSource ordinals match the C across both engines
        fc.amount = c.getAmount();
        fc.polarity =
            (c.polarity == PatchCable.Polarity.UNIPOLAR)
                ? org.chuck.deluge.firmware2.Patcher.PatchCable.UNIPOLAR
                : org.chuck.deluge.firmware2.Patcher.PatchCable.BIPOLAR;
        fw2Sound.patchCableSet.addCable(d.paramId, fc);
      }
    }

    synchronized (fw2Sound.voices) {
      var it = fw2Sound.voices.iterator();
      while (it.hasNext()) {
        var v = it.next();
        if (!v.active) {
          it.remove();
          continue;
        }
        org.chuck.deluge.firmware2.Patcher.performInitialPatching(
            fw2Sound.patchedParamValues, v.sourceValues, v.paramFinalValues);

        boolean doLPF =
            (lpfMode != org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF);
        boolean doHPF =
            (hpfMode != org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF);

        v.render(buffer, numSamples, doLPF, doHPF);
      }
    }
  }

  private org.chuck.deluge.firmware2.Oscillator.OscType fw2OscType(
      org.chuck.deluge.firmware.dsp.oscillators.OscType t) {
    return switch (t) {
      case SINE -> org.chuck.deluge.firmware2.Oscillator.OscType.SINE;
      case SAW -> org.chuck.deluge.firmware2.Oscillator.OscType.SAW;
      case SQUARE -> org.chuck.deluge.firmware2.Oscillator.OscType.SQUARE;
      case TRIANGLE -> org.chuck.deluge.firmware2.Oscillator.OscType.TRIANGLE;
      case ANALOG_SAW_2 -> org.chuck.deluge.firmware2.Oscillator.OscType.ANALOG_SAW_2;
      case ANALOG_SQUARE -> org.chuck.deluge.firmware2.Oscillator.OscType.ANALOG_SQUARE;
      case WAVETABLE -> org.chuck.deluge.firmware2.Oscillator.OscType.WAVETABLE;
      default -> org.chuck.deluge.firmware2.Oscillator.OscType.SAMPLE;
    };
  }

  private org.chuck.deluge.firmware2.FilterSet.FilterMode fw2LpfMode() {
    return switch (lpfMode) {
      case TRANSISTOR_12DB -> org.chuck.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_12DB;
      case TRANSISTOR_24DB -> org.chuck.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB;
      case TRANSISTOR_24DB_DRIVE ->
          org.chuck.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE;
      case SVF_BAND -> org.chuck.deluge.firmware2.FilterSet.FilterMode.SVF_BAND;
      case SVF_NOTCH -> org.chuck.deluge.firmware2.FilterSet.FilterMode.SVF_NOTCH;
      case HPLADDER -> org.chuck.deluge.firmware2.FilterSet.FilterMode.HPLADDER;
      default -> org.chuck.deluge.firmware2.FilterSet.FilterMode.OFF;
    };
  }

  private org.chuck.deluge.firmware2.FilterSet.FilterMode fw2HpfMode() {
    return switch (hpfMode) {
      case TRANSISTOR_12DB -> org.chuck.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_12DB;
      case TRANSISTOR_24DB -> org.chuck.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB;
      case TRANSISTOR_24DB_DRIVE ->
          org.chuck.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE;
      case SVF_BAND -> org.chuck.deluge.firmware2.FilterSet.FilterMode.SVF_BAND;
      case SVF_NOTCH -> org.chuck.deluge.firmware2.FilterSet.FilterMode.SVF_NOTCH;
      case HPLADDER -> org.chuck.deluge.firmware2.FilterSet.FilterMode.HPLADDER;
      default -> org.chuck.deluge.firmware2.FilterSet.FilterMode.OFF;
    };
  }

  private org.chuck.deluge.firmware2.Lfo.LfoType fw2LfoType(
      org.chuck.deluge.firmware.modulation.LFO.LFOType t) {
    return switch (t) {
      case SINE -> org.chuck.deluge.firmware2.Lfo.LfoType.SINE;
      case TRIANGLE -> org.chuck.deluge.firmware2.Lfo.LfoType.TRIANGLE;
      case SQUARE -> org.chuck.deluge.firmware2.Lfo.LfoType.SQUARE;
      case SAW -> org.chuck.deluge.firmware2.Lfo.LfoType.SAW;
      case SAMPLE_AND_HOLD -> org.chuck.deluge.firmware2.Lfo.LfoType.SAMPLE_AND_HOLD;
      case RANDOM_WALK -> org.chuck.deluge.firmware2.Lfo.LfoType.RANDOM_WALK;
      case WARBLER -> org.chuck.deluge.firmware2.Lfo.LfoType.WARBLER;
    };
  }

  public void noteOffAll() {
    synchronized (fw2Sound.voices) {
      for (var v : fw2Sound.voices) v.noteOff();
    }
  }

  public void releaseNote(int note) {
    releaseNote(note, -1);
  }

  public void releaseNote(int note, int midiChannel) {
    if (arpEnabled()) {
      arpeggiator.noteOff(arpSettings, note, arpInstr);
      // Handle any note-off instructions the arp emitted
      for (int n = 0; n < 4; n++) {
        int noteOff = arpInstr.noteCodeOffPostArp[n];
        if (noteOff != org.chuck.deluge.firmware2.Arpeggiator.ARP_NOTE_NONE) {
          releaseVoice(noteOff, -1);
        }
      }
      // Handle snap-back note-on (mono behavior)
      if (arpInstr.arpNoteOn != null) {
        int noteOn = arpInstr.arpNoteOn.noteCodeOnPostArp[0];
        int v = arpInstr.arpNoteOn.velocity;
        if (v > 0) triggerVoice(noteOn, v, -1);
      }
      return;
    }
    releaseVoice(note, midiChannel);
  }

  private void releaseVoice(int note, int midiChannel) {
    synchronized (fw2Sound.voices) {
      for (var v : fw2Sound.voices) {
        if (v.active && v.note == note) {
          v.noteOff(); // triggers the envelope release (envelope.cpp noteOff/unconditionalRelease)
        }
      }
    }
  }

  public void releaseAllNotes() {
    synchronized (fw2Sound.voices) {
      for (var v : fw2Sound.voices) {
        if (v.active) v.noteOff();
      }
    }
  }

  /** C: polyphonicExpressionEventOnChannelOrNote — pitch bend (X), immediate. */
  public void mpePitchBend(int midiChannel, int newValue) {
    int s = PatchSource.X.ordinal();
    int voiceLevelValue = newValue << 16;
    synchronized (fw2Sound.voices) {
      for (var v : fw2Sound.voices) {
        if (v.active && v.inputCharacteristics[1] == midiChannel) {
          v.expressionEventImmediate(fw2Sound, voiceLevelValue, s);
        }
      }
    }
  }

  /** C: polyphonicExpressionEventOnChannelOrNote — pressure/aftertouch (Z), immediate. */
  public void mpePressure(int midiChannel, int newValue) {
    int s = PatchSource.AFTERTOUCH.ordinal();
    int voiceLevelValue = newValue << 16;
    synchronized (fw2Sound.voices) {
      for (var v : fw2Sound.voices) {
        if (v.active && v.inputCharacteristics[1] == midiChannel) {
          v.expressionEventImmediate(fw2Sound, voiceLevelValue, s);
        }
      }
    }
  }

  /** C: polyphonicExpressionEventOnChannelOrNote — timbre (Y), immediate. */
  public void mpeTimbre(int midiChannel, int newValue) {
    int s = PatchSource.Y.ordinal();
    int voiceLevelValue = newValue << 16;
    synchronized (fw2Sound.voices) {
      for (var v : fw2Sound.voices) {
        if (v.active && v.inputCharacteristics[1] == midiChannel) {
          v.expressionEventImmediate(fw2Sound, voiceLevelValue, s);
        }
      }
    }
  }

  // ── High-Fidelity Filter States & Modulations ──
  public org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode lpfMode =
      org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
  public org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode hpfMode =
      org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
  public org.chuck.deluge.firmware.dsp.filter.FilterRoute filterRoute =
      org.chuck.deluge.firmware.dsp.filter.FilterRoute.HIGH_TO_LOW;

  public void setLpfMode(org.chuck.deluge.model.FilterMode modelMode) {
    if (modelMode == null) return;
    switch (modelMode) {
      case LADDER_12:
        this.lpfMode =
            org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.TRANSISTOR_12DB;
        break;
      case LADDER_24:
        this.lpfMode =
            org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.TRANSISTOR_24DB;
        break;
      case DRIVE:
        this.lpfMode =
            org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.TRANSISTOR_24DB_DRIVE;
        break;
      case SVF:
      case SVF_NOTCH:
        this.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.SVF_NOTCH;
        break;
      case SVF_BAND:
        this.lpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.SVF_BAND;
        break;
    }
  }

  public void setHpfMode(org.chuck.deluge.model.FilterMode modelMode) {
    if (modelMode == null) return;
    switch (modelMode) {
      // The firmware HPF ladder is a single mode (HPLADDER) — every ladder-type setting maps to
      // it. NOTE: the XML parser turns the default "HPLadder" into LADDER_12, so LADDER_12 must
      // map to HPLADDER here, not OFF (otherwise the high-pass is silently disabled).
      case LADDER_12:
      case LADDER_24:
      case DRIVE:
        this.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.HPLADDER;
        break;
      case SVF:
      case SVF_BAND:
        this.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.SVF_BAND;
        break;
      case SVF_NOTCH:
        this.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.SVF_NOTCH;
        break;
      default:
        this.hpfMode = org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
        break;
    }
  }

  public void setFilterRoute(int routeCode) {
    if (routeCode == 1) {
      this.filterRoute = org.chuck.deluge.firmware.dsp.filter.FilterRoute.LOW_TO_HIGH;
    } else if (routeCode == 2) {
      this.filterRoute = org.chuck.deluge.firmware.dsp.filter.FilterRoute.PARALLEL;
    } else {
      this.filterRoute = org.chuck.deluge.firmware.dsp.filter.FilterRoute.HIGH_TO_LOW;
    }
  }

  public boolean hasFilters() {
    return lpfMode != org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF
        || hpfMode != org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF;
  }

  // ── Subtractive Oscillator Retrigger Starting Phases ──
  public int osc1RetriggerPhase = 0;
  public int osc2RetriggerPhase = 0;
  public int mod1RetrigPhase = -1;
  public int mod2RetrigPhase = -1;
  public float fmRatio1 = 1.0f;
  public float fmRatio2 = 1.0f;
  // Raw FM modulator transpose (semitones) + cents for the firmware2 faithful FM increment.
  public int fmModulator1Transpose = 0;
  public int fmModulator1Cents = 0;
  public int fmModulator2Transpose = 0;
  public int fmModulator2Cents = 0;

  // ── Native 2-op FM engine (port of voice.cpp) ──
  // Raw stored knob Q31 values for the two FM modulator amounts (index 0 = <modulator1>, 1 =
  // <modulator2>); 0x80000000 == off. The live amplitude is computed per block by fw2 Voice
  // through the Deluge patched-param volume curve (so patch cables such as envelope2 ->
  // modulator1Volume dynamically drive the FM depth, exactly as on hardware).
  public final int[] fmModulatorAmountBase = {Integer.MIN_VALUE, Integer.MIN_VALUE};
  // Final Q31 feedback amounts for modulators and carriers (0 = no self-feedback).
  public final int[] fmModulatorFeedback = new int[2];
  public final int[] fmCarrierFeedback = new int[2];
  // When true, modulator 1 FMs modulator 0 (chained) instead of the carriers directly.
  public boolean fmModulator1ToModulator0 = false;
}
