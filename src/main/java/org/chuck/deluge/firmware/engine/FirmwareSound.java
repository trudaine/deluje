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
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
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
  public int[] globalSourceValues = new int[PatchSource.kNumPatchSources];

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
    // Default neutral values as requested for LKG state
    paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Q31.ONE;
    paramNeutralValues[Param.LOCAL_VOLUME] = Q31.ONE;
    // Default filter neutral settings
    paramNeutralValues[Param.LOCAL_LPF_FREQ] = Q31.ONE;
    paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = 0;
    paramNeutralValues[Param.LOCAL_LPF_MORPH] = 0;
    paramNeutralValues[Param.LOCAL_HPF_FREQ] = 0;
    paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = 0;
    paramNeutralValues[Param.LOCAL_HPF_MORPH] = 0;

    // Default LFO rate mappings
    paramNeutralValues[Param.GLOBAL_LFO_FREQ_1] = (int) (0.45 * 2147483647.0);
    paramNeutralValues[Param.GLOBAL_LFO_FREQ_2] = (int) (0.40 * 2147483647.0);
    paramNeutralValues[Param.LOCAL_LFO_LOCAL_FREQ_1] = (int) (0.45 * 2147483647.0);
    paramNeutralValues[Param.LOCAL_LFO_LOCAL_FREQ_2] = (int) (0.40 * 2147483647.0);

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
    boolean arpHolding = arpEnabled() && arpeggiator.hasInputNotes();
    if (!hasActiveVoices && !arpHolding && silentBlockCount > 100) {
      // Fast bypass: write silence and return
      for (int i = 0; i < numSamples; i++) {
        buffer[i].l = 0;
        buffer[i].r = 0;
      }
      return;
    }

    // 1. Update Global LFOs with dynamic logarithmic rates
    int lfoRate1 = paramNeutralValues[Param.GLOBAL_LFO_FREQ_1];
    int phaseInc1 = (int) (200 + Math.pow(2.0, (double) lfoRate1 / 2147483647.0 * 10.0) * 500.0);
    globalSourceValues[PatchSource.LFO_GLOBAL_1.ordinal()] =
        globalLfos[0].render(numSamples, lfoWaveforms[0], phaseInc1);

    int lfoRate2 = paramNeutralValues[Param.GLOBAL_LFO_FREQ_2];
    int phaseInc2 = (int) (200 + Math.pow(2.0, (double) lfoRate2 / 2147483647.0 * 10.0) * 500.0);
    globalSourceValues[PatchSource.LFO_GLOBAL_2.ordinal()] =
        globalLfos[1].render(numSamples, lfoWaveforms[2], phaseInc2);

    // 2. Sum Voices
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
    int[] postFXVolume = {2147483647};

    // Sample-rate reduction + bitcrushing
    srrBitcrush.process(buffer, numSamples, bitcrushParam, srrParam, postFXVolume);

    // Stutter
    stutterer.processStutter(buffer, paramManager);

    // Modulation FX. GRAIN is a granular processor (different from the LFO-based mod FX); the
    // firmware routes it separately. Mapping: rate, depth (mix), offset (density), feedback (pitch
    // randomness), tempo — mirroring ModControllableAudio::processGrainFX's argument order.
    if (modFXType == ModFXType.GRAIN) {
      granular.processGrainFX(
          buffer, modFXRateIncrement, modFXDepth << 1, modFXOffset, modFXFeedback, currentBpm);
    } else {
      modFX.processModFX(
          buffer,
          modFXType,
          modFXRateIncrement,
          modFXDepth,
          postFXVolume,
          modFXOffset,
          modFXFeedback);
    }

    // Bass/treble EQ
    eq.process(buffer, numSamples, eqBassParam, eqTrebleParam);

    // Sidechain
    int shape = paramNeutralValues[Param.UNPATCHED_SIDECHAIN_SHAPE];
    int scAmount = sidechain.render(numSamples, shape);
    globalSourceValues[PatchSource.SIDECHAIN.ordinal()] = scAmount;
    for (int i = 0; i < numSamples; i++) {
      buffer[i].l = (int) (((long) buffer[i].l * scAmount) >> 31);
      buffer[i].r = (int) (((long) buffer[i].r * scAmount) >> 31);
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

  public void noteOffAll() {
    synchronized (voices) {
      for (FirmwareVoice v : voices) {
        if (v.active) v.noteOff(0);
      }
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
    synchronized (voices) {
      System.out.println(
          "[DIAG release] releaseNote requested for pitch="
              + note
              + " voicesPoolSize="
              + voices.size());
      for (FirmwareVoice v : voices) {
        System.out.println(
            "[DIAG release] Active voice candidate check: pitch="
                + v.note
                + " active="
                + v.active
                + " pitchMatch="
                + (v.note == note));
        if (v.active && v.note == note && (midiChannel == -1 || v.midiChannel == midiChannel)) {
          System.out.println(
              "[DIAG release] MATCH SUCCESS: Calling noteOff for voice note=" + v.note);
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
