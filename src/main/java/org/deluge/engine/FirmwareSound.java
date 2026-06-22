package org.deluge.engine;

import org.deluge.firmware2.Param;
import org.deluge.firmware2.Sidechain;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.tuning.ScalaScale;
import org.deluge.modulation.patch.Destination;
import org.deluge.modulation.patch.PatchCable;
import org.deluge.modulation.patch.PatchSource;

/**
 * Port of the Deluge's Sound class. This is the central high-fidelity synthesis engine for a single
 * instrument or kit.
 */
public class FirmwareSound extends org.deluge.firmware2.GlobalEffectable {
  public enum SynthMode {
    SUBTRACTIVE,
    FM,
    RINGMOD
  }

  // Legacy FirmwareVoice path retired — always routes through firmware2.

  public final org.deluge.firmware2.Sound fw2Sound = new org.deluge.firmware2.Sound();
  private int[] fw2ScratchBuffer = null;
  // Transport clock seams: updated each render block by the PlaybackHandler so the
  // SamplePlaybackGuide sync methods (getSyncedNumSamplesIn, adjustPitchToCorrectDriftFromSync)
  // can correct clip-synced drift. Set before each render block.
  public int transportTickCount;
  public int transportTimePerTick = 44100 / 96; // default: 96 PPQN @ 44.1kHz
  public boolean transportExternalClockActive;
  public int transportTimeSinceLastTick;

  public final org.deluge.firmware2.Lfo[] globalLfos = new org.deluge.firmware2.Lfo[2];
  public final org.deluge.firmware2.Lfo.LfoType[] lfoWaveforms = {
    org.deluge.firmware2.Lfo.LfoType.SINE, org.deluge.firmware2.Lfo.LfoType.TRIANGLE,
    org.deluge.firmware2.Lfo.LfoType.SINE, org.deluge.firmware2.Lfo.LfoType.TRIANGLE
  };
  public final int[] customLfoWave = new int[256];
  public final org.deluge.playback.Sample[] samples = new org.deluge.playback.Sample[2];
  public final org.deluge.firmware2.Sample[] fw2SampleCache = new org.deluge.firmware2.Sample[2];

  /**
   * "type:path" key of the sample/wavetable currently loaded per oscillator source. Lets the
   * live-apply path (FirmwareFactory.loadOscResources) skip re-reading an unchanged file.
   */
  public final String[] loadedOscPath = new String[2];

  public final org.deluge.playback.SampleVoiceSettings[] sampleSettings = {
    new org.deluge.playback.SampleVoiceSettings(), new org.deluge.playback.SampleVoiceSettings()
  };
  public final org.deluge.firmware2.Oscillator.OscType[] oscTypes = {
    org.deluge.firmware2.Oscillator.OscType.SINE, org.deluge.firmware2.Oscillator.OscType.SINE
  };
  // maxPolyphony / voicePriority now live solely on fw2Sound (single source of truth — facade
  // refactor Phase 1). The factory writes fw2Sound directly; no shadow field, no sync copy.
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

  // ── Ported High-Fidelity Logic ──
  public final int[] monophonicExpressionValues = new int[3]; // X, Y, Z

  // C: firmware2 Arpeggiator — faithful port of modulation/arpeggiator.cpp.
  public final org.deluge.firmware2.Arpeggiator.Synth arpeggiator = fw2Sound.arpeggiator;
  public final org.deluge.firmware2.Arpeggiator.Settings arpSettings = fw2Sound.arpSettings;
  private final org.deluge.firmware2.Arpeggiator.ArpReturnInstruction arpInstr = fw2Sound.arpInstr;
  public int arpDivision =
      16; // step note division (16 = 16th note); used to derive arpPhaseIncrement
  public float arpRateMultiplier = 1.0f; // free-rate multiplier on the BPM-derived arp step

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
    return arpSettings.mode != org.deluge.firmware2.Arpeggiator.ArpMode.OFF;
  }

  public final Sidechain sidechain = fw2Sound.sidechain;
  public final org.deluge.modulation.params.ParamManager paramManager =
      new org.deluge.modulation.params.ParamManager();
  private int silentBlockCount = 200; // Starts gated on boot

  // Per-sound delay config from the song's instrument <delay> + soundParams delayFeedback. The
  // delay time is BPM-synced: delaySyncLevel is a note-division exponent (firmware syncLevel), and
  // syncParamsToFw2 converts it to fw2Sound.delayUserRate using currentBpm. delayFeedbackAmount <
  // 256
  // (or syncLevel 0) leaves the per-sound delay inert.
  public int delaySyncLevel = 0;
  public int delaySyncType = 0; // 0=even, 1=triplet, 2=dotted
  public int delayFeedbackAmount = 0;
  public boolean delayPingPong = false;
  public boolean delayAnalog = false;
  private StereoSample[] fxStereoBuffer = new StereoSample[256];

  public FirmwareSound() {
    for (int i = 0; i < 256; i++) fxStereoBuffer[i] = new StereoSample();
    for (int i = 0; i < globalLfos.length; i++) globalLfos[i] = new org.deluge.firmware2.Lfo();
    // C Sound::initParams (sound.cpp:131-187, faithfully ported to fw2 Sound.initParams).
    org.deluge.firmware2.Sound.initParams(paramNeutralValues);

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

  /** synthMode lives solely on fw2Sound (int); these accessors keep the enum API. */
  public SynthMode getSynthMode() {
    return fw2Sound.synthMode == 1
        ? SynthMode.FM
        : fw2Sound.synthMode == 2 ? SynthMode.RINGMOD : SynthMode.SUBTRACTIVE;
  }

  public void setSynthMode(SynthMode m) {
    fw2Sound.synthMode = (m == SynthMode.FM) ? 1 : (m == SynthMode.RINGMOD) ? 2 : 0;
  }

  /**
   * Note-to-phaseIncrement for the subtractive oscillator path. Computed from equal temperament
   * (2^(n/12)*440) rather than the firmware's {@code noteIntervalTable[12]} + octave-shift because
   * the firmware dispatches through pitch-adjust paths that combine table lookup +
   * multiply_32x32_rshift32(table[i], pitchAdjustNeutralValue) + {13-octave} shift; the effective
   * scalar differs from the direct table → phaseIncrement mapping used here. The Math.pow formula
   * produces the identical result to within ~0.007 cent (FirmwareTuningTest verified across 5
   * octaves × 4 waveforms). The firmware tables are available in {@link
   * org.deluge.firmware.util.LookupTables#noteIntervalTable} for future alignment.
   */
  public static int noteToPhaseInc(int note) {
    ScalaScale scale = ScalaScale.getActiveScale();
    double freq = (scale != null) ? scale.mtof(note) : (440.0 * Math.pow(2.0, (note - 69) / 12.0));
    return (int) (freq * (16777216.0 / 44100.0));
  }

  @Override
  protected void renderInternal(int[] buffer, int numSamples, int[] reverbBuffer) {
    syncParamsToFw2();
    fw2Sound.renderInternal(buffer, numSamples, reverbBuffer);

    super.postFXVolume = fw2Sound.postFXVolume;
    super.postReverbVolume = fw2Sound.postReverbVolume;

    // Update gate status
    boolean hasActiveVoices;
    synchronized (fw2Sound.voices) {
      hasActiveVoices = !fw2Sound.voices.isEmpty();
    }
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

  public void triggerNote(int note, int vel) {
    if (FirmwareAudioEngine.debugTelemetry) {
      System.out.println(
          "[DIAG FirmwareSound] triggerNote: note="
              + note
              + " vel="
              + vel
              + " sound="
              + this.hashCode()
              + " sample="
              + (samples[0] != null ? samples[0].fileName : "null"));
    }
    syncParamsToFw2();
    fw2Sound.triggerNote(note, vel);
  }

  public void triggerNote(int note, int vel, int midiChannel) {
    if (FirmwareAudioEngine.debugTelemetry) {
      System.out.println(
          "[DIAG FirmwareSound] triggerNote: note="
              + note
              + " vel="
              + vel
              + " chan="
              + midiChannel
              + " sound="
              + this.hashCode());
    }
    syncParamsToFw2();
    fw2Sound.triggerNote(note, vel, midiChannel);
  }

  public void triggerNoteLate(int note, int vel, int samplesLate) {
    if (FirmwareAudioEngine.debugTelemetry) {
      System.out.println(
          "[DIAG FirmwareSound] triggerNoteLate: note="
              + note
              + " vel="
              + vel
              + " late="
              + samplesLate
              + " sound="
              + this.hashCode()
              + " sample="
              + (samples[0] != null ? samples[0].fileName : "null"));
    }
    syncParamsToFw2();
    fw2Sound.triggerNoteLate(note, vel, samplesLate);
  }

  /** Active voice count. */
  public int getActiveVoiceCount() {
    synchronized (fw2Sound.voices) {
      int n = 0;
      for (var v : fw2Sound.voices) if (v.active) n++;
      return n;
    }
  }

  // Synchronized against FirmwareFactory.applyModelToLiveSound (the UI live-apply path), which
  // rebuilds the patch-cable set and rewrites params under the same lock — without it the audio
  // thread could iterate a half-rebuilt cable list here.
  public synchronized void syncParamsToFw2() {
    fw2Sound.oscTypes[0] = oscTypes[0];
    fw2Sound.oscTypes[1] = oscTypes[1];
    if (dx7Patch != null) {
      fw2Sound.oscTypes[0] = org.deluge.firmware2.Oscillator.OscType.DX7;
      fw2Sound.sourceDx7Patch[0] = dx7Patch;
    } else {
      fw2Sound.sourceDx7Patch[0] = null;
    }
    // numUnison/unisonDetune/unisonStereoSpread/oscillatorSync/clippingAmount/portamentoKnob now
    // written directly to fw2Sound by the factory (single source of truth); the setup* derivations
    // below recompute from fw2Sound's own (factory-set) values.
    fw2Sound.setupUnisonDetuners();
    fw2Sound.setupUnisonStereoSpread();
    fw2Sound.calculateEffectiveVolume();
    fw2Sound.oscRetriggerPhase[0] = osc1RetriggerPhase;
    fw2Sound.oscRetriggerPhase[1] = osc2RetriggerPhase;
    fw2Sound.modulatorRetriggerPhase[0] = mod1RetrigPhase;
    fw2Sound.modulatorRetriggerPhase[1] = mod2RetrigPhase;
    fw2Sound.modulator1ToModulator0 = fmModulator1ToModulator0;
    fw2Sound.fmRatio1 = fmRatio1;
    fw2Sound.fmRatio2 = fmRatio2;
    int m1Transpose = fmModulator1Transpose;
    int m1Cents = fmModulator1Cents;
    if (m1Transpose == 0 && m1Cents == 0 && fmRatio1 > 0.0f) {
      int totalCents = (int) Math.round(1200f * (float) (Math.log(fmRatio1) / Math.log(2)));
      m1Transpose = (int) Math.round(totalCents / 100f);
      m1Cents = totalCents - m1Transpose * 100;
    }
    fw2Sound.modulatorTranspose[0] = m1Transpose;
    fw2Sound.setModulatorCents(0, m1Cents);

    int m2Transpose = fmModulator2Transpose;
    int m2Cents = fmModulator2Cents;
    if (m2Transpose == 0 && m2Cents == 0 && fmRatio2 > 0.0f) {
      int totalCents = (int) Math.round(1200f * (float) (Math.log(fmRatio2) / Math.log(2)));
      m2Transpose = (int) Math.round(totalCents / 100f);
      m2Cents = totalCents - m2Transpose * 100;
    }
    fw2Sound.modulatorTranspose[1] = m2Transpose;
    fw2Sound.setModulatorCents(1, m2Cents);

    for (int s = 0; s < 2; s++) {
      if (fw2Sound.oscTypes[s] == org.deluge.firmware2.Oscillator.OscType.SAMPLE
          && samples[s] != null
          && samples[s].data != null) {
        if (fw2SampleCache[s] == null) {
          fw2SampleCache[s] = org.deluge.firmware2.Sample.fromFirmwareSample(samples[s]);
        }
        fw2Sound.samples[s] = fw2SampleCache[s];
        fw2Sound.sampleReverse[s] = sampleSettings[s].reverse;
        fw2Sound.sampleStartPoint[s] = sampleSettings[s].startPoint;
        fw2Sound.sampleEndPoint[s] = sampleSettings[s].endPoint;
        fw2Sound.sampleLoopMode[s] = sampleSettings[s].loopMode;
        fw2Sound.sampleLoopStart[s] = sampleSettings[s].loopStart;
        fw2Sound.sampleTimestretch[s] = sampleSettings[s].timestretch;
      } else {
        fw2Sound.samples[s] = null;
      }
    }

    int[] nextGlobalSourceValues = new int[3];
    System.arraycopy(globalSourceValues, 0, nextGlobalSourceValues, 0, 3);
    fw2Sound.globalSourceValues = nextGlobalSourceValues;

    fw2Sound.lfoConfig[0].waveType = lfoWaveforms[0];
    fw2Sound.lfoConfig[1].waveType = lfoWaveforms[1];
    fw2Sound.lfoConfig[2].waveType = lfoWaveforms[2];
    fw2Sound.lfoConfig[3].waveType = lfoWaveforms[3];

    int[] nextPatchedParamValues = new int[200];
    System.arraycopy(
        paramNeutralValues,
        0,
        nextPatchedParamValues,
        0,
        Math.min(paramNeutralValues.length, nextPatchedParamValues.length));
    if (paramKnobsPopulated) {
      for (int i = 0; i < Param.kNumParams; i++) {
        nextPatchedParamValues[i] = paramKnobs[i];
      }
    }

    if (FirmwareAudioEngine.debugTelemetry
        && nextPatchedParamValues[org.deluge.firmware2.Param.LOCAL_VOLUME] < 10000000) {
      System.out.printf(
          "[TELEMETRY DEBUG syncParamsToFw2] paramKnobsPopulated=%b, neutral=%d, knob=%d, nextPatched=%d\n",
          paramKnobsPopulated,
          paramNeutralValues[org.deluge.firmware2.Param.LOCAL_VOLUME],
          paramKnobs[org.deluge.firmware2.Param.LOCAL_VOLUME],
          nextPatchedParamValues[org.deluge.firmware2.Param.LOCAL_VOLUME]);
    }

    fw2Sound.patchedParamValues = nextPatchedParamValues;
    if (fmModulatorAmountBase[0] != Integer.MIN_VALUE) {
      nextPatchedParamValues[org.deluge.firmware2.Param.LOCAL_MODULATOR_0_VOLUME] =
          fmModulatorAmountBase[0];
    }
    if (fmModulatorAmountBase[1] != Integer.MIN_VALUE) {
      nextPatchedParamValues[org.deluge.firmware2.Param.LOCAL_MODULATOR_1_VOLUME] =
          fmModulatorAmountBase[1];
    }
    fw2Sound.patchedParamValues = nextPatchedParamValues;
    java.util.List<org.deluge.firmware2.Patcher.Destination> nextDestinations =
        new java.util.ArrayList<>();
    for (Destination d : paramManager.getPatchCableSet().destinations) {
      org.deluge.firmware2.Patcher.Destination dest =
          new org.deluge.firmware2.Patcher.Destination(d.paramId);
      for (PatchCable c : d.cables) {
        var fc = new org.deluge.firmware2.Patcher.PatchCable();
        fc.source = c.from.ordinal();
        fc.amount = c.getAmount();
        fc.polarity =
            (c.polarity == PatchCable.Polarity.UNIPOLAR)
                ? org.deluge.firmware2.Patcher.PatchCable.UNIPOLAR
                : org.deluge.firmware2.Patcher.PatchCable.BIPOLAR;
        dest.cables.add(fc);
        dest.sourcesMask |= (1 << fc.source);
      }
      nextDestinations.add(dest);
    }
    fw2Sound.patchCableSet.destinations = nextDestinations;

    // Modulated FX params: compute directly into fw2Sound from its patched-param knobs (no shadow).
    fw2Sound.modFXRateIncrement = fw2Sound.patchedParamValues[Param.GLOBAL_MOD_FX_RATE];
    fw2Sound.modFXDepth = fw2Sound.patchedParamValues[Param.GLOBAL_MOD_FX_DEPTH];
    fw2Sound.modFXOffset = fw2Sound.patchedParamValues[Param.UNPATCHED_MOD_FX_OFFSET];
    fw2Sound.modFXFeedback = fw2Sound.patchedParamValues[Param.UNPATCHED_MOD_FX_FEEDBACK];
    fw2Sound.bitcrushParam = fw2Sound.patchedParamValues[Param.UNPATCHED_BITCRUSHING];
    fw2Sound.srrParam = fw2Sound.patchedParamValues[Param.UNPATCHED_SAMPLE_RATE_REDUCTION];
    fw2Sound.eqBassParam = fw2Sound.patchedParamValues[Param.UNPATCHED_BASS];
    fw2Sound.eqTrebleParam = fw2Sound.patchedParamValues[Param.UNPATCHED_TREBLE];

    fw2Sound.currentBpm = (int) currentBpm;

    // Per-sound delay: convert the note-division syncLevel to a buffer rate using the live BPM
    // (same scheme as PureFirmwareEngine's master-delay sync). syncLevel exponent → multiples of a
    // 16th-note step; rate = 16384 * 2^24 / (delaySec * 44100), the inverse of
    // DelayBuffer.getIdealBufferSizeFromRate.
    int dfb =
        org.deluge.firmware2.Patcher.computeFinalValueForParam(
            org.deluge.firmware2.Param.GLOBAL_DELAY_FEEDBACK,
            fw2Sound.patchedParamValues[org.deluge.firmware2.Param.GLOBAL_DELAY_FEEDBACK]);
    if (delaySyncLevel > 0 && dfb >= 256) {
      double bpm = currentBpm > 0 ? currentBpm : 120.0;
      double stepSec = (60.0 / bpm) / 4.0; // 16th-note
      double syncFactor = Math.pow(2.0, delaySyncLevel - 1);
      if (delaySyncType == 1) syncFactor *= 1.5; // triplet
      else if (delaySyncType == 2) syncFactor *= 2.0 / 3.0; // dotted
      double delaySec = Math.max(0.001, Math.min(2.0, syncFactor * stepSec));
      long rate = (long) (16384L * 16777216L / (delaySec * 44100.0));
      fw2Sound.delayUserRate = (int) Math.min(rate, Integer.MAX_VALUE);
      fw2Sound.delayFeedbackAmount = dfb;
      fw2Sound.delayPingPong = delayPingPong;
      fw2Sound.delayAnalog = delayAnalog;
    } else if (delaySyncLevel == 0 && dfb >= 256) {
      fw2Sound.delayUserRate =
          fw2Sound.patchedParamValues[org.deluge.firmware2.Param.GLOBAL_DELAY_RATE];
      fw2Sound.delayFeedbackAmount = dfb;
      fw2Sound.delayPingPong = delayPingPong;
      fw2Sound.delayAnalog = delayAnalog;
    } else {
      fw2Sound.delayUserRate = 0;
      fw2Sound.delayFeedbackAmount = 0;
    }

    // Copy Custom LFO Waveform
    System.arraycopy(customLfoWave, 0, fw2Sound.customLfoWave, 0, 256);

    // arpSettings are already shared between FirmwareSound and fw2Sound.
  }

  public void noteOffAll() {
    synchronized (fw2Sound.voices) {
      for (var v : fw2Sound.voices) v.noteOff();
    }
  }

  public void releaseNote(int note) {
    syncParamsToFw2();
    fw2Sound.releaseNote(note);
  }

  public void releaseNote(int note, int midiChannel) {
    syncParamsToFw2();
    fw2Sound.releaseNote(note, midiChannel);
  }

  public void releaseAllNotes() {
    syncParamsToFw2();
    fw2Sound.releaseAllNotes();
  }

  /** C: polyphonicExpressionEventOnChannelOrNote — pitch bend (X), immediate. */
  public void mpePitchBend(int midiChannel, int newValue) {
    int s = PatchSource.X.ordinal();
    int voiceLevelValue = (newValue - 8192) << 18;
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
    int voiceLevelValue = newValue << 24;
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
    int voiceLevelValue = (newValue - 64) << 25;
    synchronized (fw2Sound.voices) {
      for (var v : fw2Sound.voices) {
        if (v.active && v.inputCharacteristics[1] == midiChannel) {
          v.expressionEventImmediate(fw2Sound, voiceLevelValue, s);
        }
      }
    }
  }

  // ── High-Fidelity Filter States & Modulations ──

  public void setLpfMode(org.deluge.model.FilterMode modelMode) {
    if (modelMode == null) {
      fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;
      return;
    }
    switch (modelMode) {
      case LADDER_12:
        fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_12DB;
        break;
      case LADDER_24:
        fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB;
        break;
      case DRIVE:
        fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.TRANSISTOR_24DB_DRIVE;
        break;
      case SVF:
      case SVF_NOTCH:
        fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.SVF_NOTCH;
        break;
      case SVF_BAND:
        fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.SVF_BAND;
        break;
    }
  }

  public void setHpfMode(org.deluge.model.FilterMode modelMode) {
    if (modelMode == null) {
      fw2Sound.hpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;
      return;
    }
    switch (modelMode) {
      // The firmware HPF ladder is a single mode (HPLADDER) — every ladder-type setting maps to
      // it. NOTE: the XML parser turns the default "HPLadder" into LADDER_12, so LADDER_12 must
      // map to HPLADDER here, not OFF (otherwise the high-pass is silently disabled).
      case LADDER_12:
      case LADDER_24:
      case DRIVE:
        fw2Sound.hpfMode = org.deluge.firmware2.FilterSet.FilterMode.HPLADDER;
        break;
      case SVF:
      case SVF_BAND:
        fw2Sound.hpfMode = org.deluge.firmware2.FilterSet.FilterMode.SVF_BAND;
        break;
      case SVF_NOTCH:
        fw2Sound.hpfMode = org.deluge.firmware2.FilterSet.FilterMode.SVF_NOTCH;
        break;
      default:
        fw2Sound.hpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;
        break;
    }
  }

  /** filterRoute lives on fw2Sound (int ordinal); this getter restores the enum for the UI. */
  public org.deluge.firmware2.FilterRoute getFilterRoute() {
    return org.deluge.firmware2.FilterRoute.values()[fw2Sound.filterRoute];
  }

  public void setFilterRoute(int routeCode) {
    if (routeCode == 1) {
      fw2Sound.filterRoute = org.deluge.firmware2.FilterRoute.LOW_TO_HIGH.ordinal();
    } else if (routeCode == 2) {
      fw2Sound.filterRoute = org.deluge.firmware2.FilterRoute.PARALLEL.ordinal();
    } else {
      fw2Sound.filterRoute = org.deluge.firmware2.FilterRoute.HIGH_TO_LOW.ordinal();
    }
  }

  public boolean hasFilters() {
    return fw2Sound.lpfMode != org.deluge.firmware2.FilterSet.FilterMode.OFF
        || fw2Sound.hpfMode != org.deluge.firmware2.FilterSet.FilterMode.OFF;
  }

  // ── Subtractive Oscillator Retrigger Starting Phases ──
  // Raw uint32 phase as the C stores/serializes it (degrees * 11930464, retrigger_phase.h:49-58);
  // -1 (0xFFFFFFFF) = off/free-running (C sound.cpp:88 default).
  public int osc1RetriggerPhase = -1;
  public int osc2RetriggerPhase = -1;
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
