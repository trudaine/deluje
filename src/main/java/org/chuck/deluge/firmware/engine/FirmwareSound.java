package org.chuck.deluge.firmware.engine;

import java.util.ArrayList;
import java.util.List;
import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.fx.ModFXProcessor;
import org.chuck.deluge.firmware.dsp.fx.ModFXType;
import org.chuck.deluge.firmware.dsp.granular.GranularProcessor;
import org.chuck.deluge.firmware.model.PolyphonyMode;
import org.chuck.deluge.firmware.modulation.LFO;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.params.ParamManager;
import org.chuck.deluge.firmware.modulation.patch.Destination;
import org.chuck.deluge.firmware.modulation.patch.PatchCable;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.modulation.patch.Patcher;
import org.chuck.deluge.firmware.modulation.sidechain.SideChain;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.model.tuning.ScalaScale;

/**
 * Port of the Deluge's Sound class. This is the central high-fidelity synthesis engine for a single
 * instrument or kit.
 */
public class FirmwareSound extends GlobalEffectable {
  public enum SynthMode {
    SUBTRACTIVE,
    FM,
    RINGMOD
  }

  public final List<FirmwareVoice> voices = new ArrayList<>();

  /** Set true to use the faithful firmware2/ DSP engine (opt-in). */
  public boolean useFirmware2 = true;

  public final java.util.List<org.chuck.deluge.firmware2.Voice> fw2Voices =
      new java.util.ArrayList<>();
  public final org.chuck.deluge.firmware2.Sound fw2Sound = new org.chuck.deluge.firmware2.Sound();
  private int[] fw2ScratchBuffer = null;
  public final LFO[] globalLfos = new LFO[2];
  public final LFO.LFOType[] lfoWaveforms = {
    LFO.LFOType.SINE, LFO.LFOType.TRIANGLE,
    LFO.LFOType.SINE, LFO.LFOType.TRIANGLE
  };
  public final org.chuck.deluge.firmware.model.sample.Sample[] samples =
      new org.chuck.deluge.firmware.model.sample.Sample[2];
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

  // Per-sound arpeggiator: held notes feed the arp, which triggers individual voices on its clock.
  public final org.chuck.deluge.firmware.modulation.Arpeggiator arpeggiator =
      new org.chuck.deluge.firmware.modulation.Arpeggiator(
          new org.chuck.deluge.firmware.modulation.Arpeggiator.Settings());
  private final org.chuck.deluge.firmware.modulation.Arpeggiator.ReturnInstruction arpInstr =
      new org.chuck.deluge.firmware.modulation.Arpeggiator.ReturnInstruction();
  public int arpPhaseIncrement = 0; // arp clock (Q-units; one step == 1<<24 of gatePos)
  public int arpDivision =
      16; // step note division (16 = 16th note); used to derive arpPhaseIncrement
  private int lastArpNote = -1;

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
    return arpeggiator.settings.mode
        != org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode.OFF;
  }

  public final GranularProcessor granular = new GranularProcessor();
  public final SideChain sidechain = new SideChain();
  public int sidechainSend = 0;
  public final Stutterer stutterer = new Stutterer();
  private int silentBlockCount = 200; // Starts gated on boot

  public FirmwareSound() {
    for (int i = 0; i < globalLfos.length; i++) globalLfos[i] = new LFO();
    for (int i = 0; i < Param.kNumParams; i++) {
      paramNeutralValues[i] =
          org.chuck.deluge.firmware.modulation.params.ParamCurves.getParamNeutralValue(i);
      paramKnobs[i] = 0;
    }
    // Default neutral values as requested for LKG state
    paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 1073741824;
    paramNeutralValues[Param.LOCAL_VOLUME] = 1073741824;
    // Default filter neutral settings
    paramNeutralValues[Param.LOCAL_LPF_FREQ] = Q31.ONE;
    paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = 0;
    paramNeutralValues[Param.LOCAL_LPF_MORPH] = 0;
    paramNeutralValues[Param.LOCAL_HPF_FREQ] = 0;
    paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = 0;
    paramNeutralValues[Param.LOCAL_HPF_MORPH] = 0;

    // Pitch Adjust defaults to 0 (neutral) in C++
    paramNeutralValues[Param.LOCAL_PITCH_ADJUST] = 0;
    paramNeutralValues[Param.LOCAL_OSC_A_PITCH_ADJUST] = 0;
    paramNeutralValues[Param.LOCAL_OSC_B_PITCH_ADJUST] = 0;
    paramNeutralValues[Param.LOCAL_MODULATOR_0_PITCH_ADJUST] = 0;
    paramNeutralValues[Param.LOCAL_MODULATOR_1_PITCH_ADJUST] = 0;

    // Exponential rates defaulting to 0 in C++
    paramNeutralValues[Param.GLOBAL_DELAY_RATE] = 0;
    paramNeutralValues[Param.GLOBAL_ARP_RATE] = 0;
    paramNeutralValues[Param.GLOBAL_MOD_FX_RATE] = 0;

    // Default LFO rates: the param value is now the LFO phase increment directly (firmware scale),
    // so default to the firmware neutral 121739 (~1.25 Hz). The factory overwrites this with
    // getExp(121739, ...) of the patch's rate when an LFO is configured.
    paramNeutralValues[Param.GLOBAL_LFO_FREQ_1] = 121739;
    paramNeutralValues[Param.GLOBAL_LFO_FREQ_2] = 121739;
    paramNeutralValues[Param.LOCAL_LFO_LOCAL_FREQ_1] = 121739;
    paramNeutralValues[Param.LOCAL_LFO_LOCAL_FREQ_2] = 121739;

    // Default ADSR Envelopes configuration
    for (int i = 0; i < 4; i++) {
      paramNeutralValues[Param.LOCAL_ENV_0_ATTACK + i] = 20000;
      paramNeutralValues[Param.LOCAL_ENV_0_DECAY + i] = 400;
      paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN + i] = (i == 0) ? Q31.ONE : 0;
      paramNeutralValues[Param.LOCAL_ENV_0_RELEASE + i] = 400;
    }
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
  protected void renderInternal(StereoSample[] buffer, int numSamples, ParamManager unused) {
    int scHit = GlobalSidechainBus.getActiveFrameHit();
    if (scHit != 0) {
      sidechain.registerHit(scHit);
    }
    // 0. Arpeggiator clock: advance the arp and action any note on/off it emits this block. Runs
    // before the silent-bypass so it keeps stepping between sounding notes.
    if (arpEnabled() && arpPhaseIncrement > 0) {
      arpInstr.noteOn = false;
      arpInstr.noteOff = false;
      arpeggiator.render(arpInstr, numSamples, arpPhaseIncrement);
      if (arpInstr.noteOff && lastArpNote >= 0) {
        releaseVoice(lastArpNote, -1);
        lastArpNote = -1;
      }
      if (arpInstr.noteOn) {
        triggerVoice(arpInstr.noteCode, arpInstr.velocity, -1);
        lastArpNote = arpInstr.noteCode;
      }
    }

    boolean hasActiveVoices;
    synchronized (voices) {
      hasActiveVoices = !voices.isEmpty();
    }
    if (!hasActiveVoices && useFirmware2) {
      synchronized (fw2Voices) {
        hasActiveVoices = !fw2Voices.isEmpty();
      }
    }
    boolean arpHolding = arpEnabled() && arpeggiator.hasInputNotes();
    if (!hasActiveVoices && !arpHolding && silentBlockCount > 100) {
      // Fast bypass: write silence and return
      for (int i = 0; i < numSamples; i++) {
        buffer[i].l = 0;
        buffer[i].r = 0;
      }
      return;
    }

    // 1. Update Global LFOs. Faithful: the unsynced LFO phase increment is the exp-curved rate
    // param directly (Sound::getGlobalLFOPhaseIncrement), not an ad-hoc formula.
    int phaseInc1 = paramNeutralValues[Param.GLOBAL_LFO_FREQ_1];
    globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal()] =
        globalLfos[0].render(numSamples, lfoWaveforms[0], phaseInc1);

    int phaseInc2 = paramNeutralValues[Param.GLOBAL_LFO_FREQ_2];
    globalSourceValues[PatchSource.LFO_GLOBAL_2.ordinal()] =
        globalLfos[1].render(numSamples, lfoWaveforms[2], phaseInc2);

    // 2. Sum Voices
    if (useFirmware2 && !isDrum) {
      renderVoicesFw2(buffer, numSamples);
    } else
      synchronized (voices) {
        java.util.Iterator<FirmwareVoice> it = voices.iterator();
        while (it.hasNext()) {
          FirmwareVoice voice = it.next();
          if (!voice.active) {
            it.remove();
            continue;
          }

          int pIncA = noteToPhaseInc(voice.note);
          int pIncB = noteToPhaseInc(voice.note + 12);
          voice.render(buffer, numSamples, pIncA, pIncB);
        }
      }

    // 3. Apply High-Fidelity FX Chain (firmware order: SRR/bitcrush → mod FX → stutter → ...)
    // Sample-rate reduction + bitcrushing
    srrBitcrush.process(buffer, numSamples, bitcrushParam, srrParam, postFXVolumeHolder);

    // Stutter
    stutterer.processStutter(buffer, paramManager);

    // Modulation FX. GRAIN is a granular processor (different from the LFO-based mod FX); the
    // firmware routes it separately. Mapping: rate, depth (mix), offset (density), feedback (pitch
    // randomness), tempo — mirroring ModControllableAudio::processGrainFX's argument order.
    if (modFXType == ModFXType.GRAIN) {
      granular.processGrainFX(
          buffer,
          modFXRateIncrement,
          modFXDepth << 1,
          modFXOffset,
          modFXFeedback,
          postFXVolumeHolder,
          currentBpm);
    } else {
      modFX.processModFX(
          buffer,
          modFXType,
          modFXRateIncrement,
          modFXDepth,
          postFXVolumeHolder,
          modFXOffset,
          modFXFeedback);
    }

    // Bass/treble EQ
    eq.process(buffer, numSamples, eqBassParam, eqTrebleParam);

    // Sidechain
    int shape = paramNeutralValues[Param.UNPATCHED_SIDECHAIN_SHAPE];
    int scAmount = sidechain.render(numSamples, shape);
    globalSourceValues[PatchSource.SIDECHAIN.ordinal()] = scAmount;
    if (hasSidechainVolumePatch()) {
      globalPatcher.performPatching(
          0, this, paramManager, globalSourceValues, globalParamFinalValues);
      postReverbVolumeHolder[0] = globalParamFinalValues[Param.GLOBAL_VOLUME_POST_REVERB_SEND];
    }

    // Update gate status
    if (hasActiveVoices) {
      silentBlockCount = 0;
    } else {
      boolean isSilent = true;
      for (int i = 0; i < numSamples; i++) {
        if (buffer[i].l != 0 || buffer[i].r != 0) {
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
      arpeggiator.noteOn(note, vel);
      return;
    }
    triggerVoice(note, vel, midiChannel);
  }

  private void triggerVoice(int note, int vel, int midiChannel) {
    if (sidechainSend != 0) {
      GlobalSidechainBus.registerHit(sidechainSend);
    }
    if (useFirmware2 && !isDrum) {
      triggerVoiceFw2(note, vel);
      return;
    }
    synchronized (voices) {
      FirmwareVoice voiceToUse = null;

      if (polyphonic != PolyphonyMode.POLY) {
        for (FirmwareVoice v : voices) {
          if (v.active) {
            voiceToUse = v;
            break;
          }
        }
      }

      if (voiceToUse != null) {
        voiceToUse.midiChannel = midiChannel;
        voiceToUse.mpePitchBend = 8192;
        voiceToUse.mpePressure = 0;
        voiceToUse.mpeTimbre = 64;
        voiceToUse.noteOn(note, vel);
        return;
      }

      for (FirmwareVoice v : voices) {
        if (!v.active) {
          v.midiChannel = midiChannel;
          v.mpePitchBend = 8192;
          v.mpePressure = 0;
          v.mpeTimbre = 64;
          v.noteOn(note, vel);
          return;
        }
      }
      if (voices.size() < maxPolyphony) {
        FirmwareVoice v = new FirmwareVoice(this);
        v.midiChannel = midiChannel;
        v.mpePitchBend = 8192;
        v.mpePressure = 0;
        v.mpeTimbre = 64;
        v.noteOn(note, vel);
        voices.add(v);
      }
    }
  }

  /** Active voice count for the engine currently in use (firmware2 vs legacy). */
  public int getActiveVoiceCount() {
    if (useFirmware2) {
      synchronized (fw2Voices) {
        int n = 0;
        for (var v : fw2Voices) if (v.active) n++;
        return n;
      }
    }
    int n = 0;
    for (FirmwareVoice v : voices) if (v.active) n++;
    return n;
  }

  private void triggerVoiceFw2(int note, int vel) {
    synchronized (fw2Voices) {
      if (polyphonic != PolyphonyMode.POLY)
        for (var v : fw2Voices)
          if (v.active) {
            v.noteOn(note, vel);
            // Mirror to legacy voices list so tests inspecting .voices work
            syncLegacyVoicesFromFw2();
            return;
          }
      for (var v : fw2Voices)
        if (!v.active) {
          v.noteOn(note, vel);
          syncLegacyVoicesFromFw2();
          return;
        }
      if (fw2Voices.size() < maxPolyphony) {
        var v = new org.chuck.deluge.firmware2.Voice(fw2Sound);
        v.noteOn(note, vel);
        fw2Voices.add(v);
        syncLegacyVoicesFromFw2();
      }
    }
  }

  /** Keep the legacy {@code voices} list in sync with firmware2 voices so existing tests work. */
  private void syncLegacyVoicesFromFw2() {
    voices.clear();
    for (var fv : fw2Voices) {
      FirmwareVoice lv = new FirmwareVoice(this);
      lv.active = fv.active;
      lv.note = fv.note;
      voices.add(lv);
    }
  }

  private void renderVoicesFw2(StereoSample[] buffer, int numSamples) {
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
    fw2Sound.volumeNeutralValueForUnison = 134217728; // neutral
    fw2Sound.modulator1ToModulator0 = fmModulator1ToModulator0;
    fw2Sound.fmRatio1 = fmRatio1;
    fw2Sound.fmRatio2 = fmRatio2;
    fw2Sound.modulatorTranspose[0] = fmModulator1Transpose;
    fw2Sound.modulatorTranspose[1] = fmModulator2Transpose;
    fw2Sound.setModulatorCents(0, fmModulator1Cents);
    fw2Sound.setModulatorCents(1, fmModulator2Cents);

    // Propagate globalSourceValues (first 3 values)
    System.arraycopy(globalSourceValues, 0, fw2Sound.globalSourceValues, 0, 3);

    // Propagate LFO config waveforms
    fw2Sound.lfoConfig[0].waveType = fw2LfoType(lfoWaveforms[0]);
    fw2Sound.lfoConfig[1].waveType = fw2LfoType(lfoWaveforms[1]);
    fw2Sound.lfoConfig[2].waveType = fw2LfoType(lfoWaveforms[2]);
    fw2Sound.lfoConfig[3].waveType = fw2LfoType(lfoWaveforms[3]);

    // Bridge the patch's param knobs + cables into the firmware2 Sound (its patched-param set
    // mirrors the C ParamManager). The factory builds paramKnobs (paramNeutralValues base + the
    // envelope-RATE knobs, which paramNeutralValues stores as legacy curve-outputs in a different
    // domain), so use it directly for factory-built sounds — byte-identical to before. A
    // directly-constructed FirmwareSound (e.g. tests configuring only paramNeutralValues) leaves
    // paramKnobs zero; fall back to paramNeutralValues there so its config is honoured.
    int[] knobSource = paramKnobsPopulated ? paramKnobs : paramNeutralValues;
    System.arraycopy(
        knobSource, 0, fw2Sound.patchedParamValues, 0,
        Math.min(knobSource.length, fw2Sound.patchedParamValues.length));
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

    // 2. Render voices reusing the scratch buffer
    int requiredLength = numSamples * 2;
    if (fw2ScratchBuffer == null || fw2ScratchBuffer.length < requiredLength) {
      fw2ScratchBuffer = new int[requiredLength];
    }

    synchronized (fw2Voices) {
      var it = fw2Voices.iterator();
      while (it.hasNext()) {
        var v = it.next();
        if (!v.active) {
          it.remove();
          continue;
        }
        // Base: curve-apply all patched-param knobs (no cables). Voice.render then applies
        // cable modulation on top via performPatching.
        org.chuck.deluge.firmware2.Patcher.performInitialPatching(
            fw2Sound.patchedParamValues, v.sourceValues, v.paramFinalValues);

        java.util.Arrays.fill(fw2ScratchBuffer, 0, requiredLength, 0);

        boolean doLPF =
            (lpfMode != org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF);
        boolean doHPF =
            (hpfMode != org.chuck.deluge.firmware.dsp.filter.FirmwareFilter.FilterMode.OFF);

        v.render(fw2ScratchBuffer, numSamples, doLPF, doHPF);

        for (int i = 0; i < numSamples; i++) {
          buffer[i].l = Q31.addSaturate(buffer[i].l, fw2ScratchBuffer[i * 2]);
          buffer[i].r = Q31.addSaturate(buffer[i].r, fw2ScratchBuffer[i * 2 + 1]);
        }
      }
    }
    syncLegacyVoicesFromFw2();
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
    synchronized (voices) {
      for (FirmwareVoice v : voices) if (v.active) v.noteOff(0);
    }
    synchronized (fw2Voices) {
      for (var v : fw2Voices) v.noteOff();
    }
  }

  public void releaseNote(int note) {
    releaseNote(note, -1);
  }

  public void releaseNote(int note, int midiChannel) {
    if (arpEnabled()) {
      arpeggiator.noteOff(note);
      return;
    }
    releaseVoice(note, midiChannel);
  }

  private void releaseVoice(int note, int midiChannel) {
    if (useFirmware2) {
      synchronized (fw2Voices) {
        for (var v : fw2Voices) {
          if (v.active && v.note == note) {
            v.noteOff(); // triggers the envelope release (envelope.cpp noteOff/unconditionalRelease)
          }
        }
      }
      return;
    }
    synchronized (voices) {
      for (FirmwareVoice v : voices) {
        if (v.active && v.note == note && (midiChannel == -1 || v.midiChannel == midiChannel)) {
          v.noteOff(0);
        }
      }
    }
  }

  public void releaseAllNotes() {
    synchronized (voices) {
      for (FirmwareVoice v : voices) {
        if (v.active) {
          v.noteOff(0);
        }
      }
    }
  }

  public void mpePitchBend(int midiChannel, int value) {
    synchronized (voices) {
      for (FirmwareVoice v : voices) {
        if (v.active && v.midiChannel == midiChannel) {
          v.mpePitchBend = value;
        }
      }
    }
  }

  public void mpePressure(int midiChannel, int value) {
    synchronized (voices) {
      for (FirmwareVoice v : voices) {
        if (v.active && v.midiChannel == midiChannel) {
          v.mpePressure = value;
        }
      }
    }
  }

  public void mpeTimbre(int midiChannel, int value) {
    synchronized (voices) {
      for (FirmwareVoice v : voices) {
        if (v.active && v.midiChannel == midiChannel) {
          v.mpeTimbre = value;
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

  @Override
  public void processFilters(StereoSample[] buffer, int numSamples) {
    if (voices.isEmpty()) {
      filterSet.reset();
      return;
    }

    int lpfFrequency, lpfResonance, lpfMorph;
    int hpfFrequency, hpfResonance, hpfMorph;

    FirmwareVoice primaryVoice = null;
    synchronized (voices) {
      if (!voices.isEmpty()) {
        primaryVoice = voices.iterator().next();
      }
    }

    if (primaryVoice != null) {
      lpfFrequency = primaryVoice.paramFinalValues[Param.LOCAL_LPF_FREQ];
      lpfResonance = primaryVoice.paramFinalValues[Param.LOCAL_LPF_RESONANCE];
      lpfMorph = primaryVoice.paramFinalValues[Param.LOCAL_LPF_MORPH];

      hpfFrequency = primaryVoice.paramFinalValues[Param.LOCAL_HPF_FREQ];
      hpfResonance = primaryVoice.paramFinalValues[Param.LOCAL_HPF_RESONANCE];
      hpfMorph = primaryVoice.paramFinalValues[Param.LOCAL_HPF_MORPH];
    } else {
      lpfFrequency = paramNeutralValues[Param.LOCAL_LPF_FREQ];
      lpfResonance = paramNeutralValues[Param.LOCAL_LPF_RESONANCE];
      lpfMorph = paramNeutralValues[Param.LOCAL_LPF_MORPH];

      hpfFrequency = paramNeutralValues[Param.LOCAL_HPF_FREQ];
      hpfResonance = paramNeutralValues[Param.LOCAL_HPF_RESONANCE];
      hpfMorph = paramNeutralValues[Param.LOCAL_HPF_MORPH];
    }

    // Bypassed: Migrated to polyphonic per-voice FilterSet rendering stage in FirmwareVoice.java!
    // Keep global filter inactive to avoid double-filtering.
    // filterSet.setConfig(
    //     lpfFrequency,
    //     lpfResonance,
    //     lpfMode,
    //     lpfMorph,
    //     hpfFrequency,
    //     hpfResonance,
    //     hpfMode,
    //     hpfMorph,
    //     Q31.ONE,
    //     filterRoute);
    // filterSet.renderStereoInterleaved(buffer, numSamples);
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
  // <modulator2>); 0x80000000 == off. The live amplitude is computed per block by FirmwareVoice
  // through the Deluge patched-param volume curve (so patch cables such as envelope2 ->
  // modulator1Volume dynamically drive the FM depth, exactly as on hardware).
  public final int[] fmModulatorAmountBase = {Integer.MIN_VALUE, Integer.MIN_VALUE};
  // Final Q31 feedback amounts for modulators and carriers (0 = no self-feedback).
  public final int[] fmModulatorFeedback = new int[2];
  public final int[] fmCarrierFeedback = new int[2];
  // When true, modulator 1 FMs modulator 0 (chained) instead of the carriers directly.
  public boolean fmModulator1ToModulator0 = false;
}
