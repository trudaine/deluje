package org.chuck.deluge.firmware.engine;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.model.Clip;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.model.note.Note;
import org.chuck.deluge.firmware.model.note.NoteRow;
import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.util.FirmwareUtils;
import org.chuck.deluge.firmware.modulation.patch.PatchCable;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.storage.audio.AudioFileReader;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.Drum;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.LfoModel;
import org.chuck.deluge.model.MidiTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.project.PreferencesManager;

/** Glue code to convert the existing XML-loaded models into the high-fidelity firmware engine. */
public class FirmwareFactory {
  private static final Map<String, PatchSource> SOURCE_MAP = new HashMap<>();

  /**
   * Maps model patch destination names (e.g. "lpfFrequency", "volume", "pan") to firmware Param
   * constants.
   */
  private static final Map<String, Integer> DEST_MAP = new HashMap<>();

  static {
    // Sources (model → firmware PatchSource)
    SOURCE_MAP.put("LFO1", PatchSource.LFO_LOCAL_1);
    SOURCE_MAP.put("LFO2", PatchSource.LFO_LOCAL_2);
    SOURCE_MAP.put("LFO_GLOBAL_1", PatchSource.LFO_GLOBAL_1);
    SOURCE_MAP.put("LFO_GLOBAL_2", PatchSource.LFO_GLOBAL_2);
    SOURCE_MAP.put("ENVELOPE_0", PatchSource.ENVELOPE_0);
    SOURCE_MAP.put("ENVELOPE_1", PatchSource.ENVELOPE_1);
    SOURCE_MAP.put("ENVELOPE_2", PatchSource.ENVELOPE_2);
    SOURCE_MAP.put("ENVELOPE_3", PatchSource.ENVELOPE_3);
    SOURCE_MAP.put("SIDECHAIN", PatchSource.SIDECHAIN);
    SOURCE_MAP.put("X", PatchSource.X);
    SOURCE_MAP.put("Y", PatchSource.Y);
    SOURCE_MAP.put("AFTERTOUCH", PatchSource.AFTERTOUCH);
    SOURCE_MAP.put("VELOCITY", PatchSource.VELOCITY);
    SOURCE_MAP.put("NOTE", PatchSource.NOTE);
    SOURCE_MAP.put("RANDOM", PatchSource.RANDOM);

    // Destinations (model → firmware Param constant)
    DEST_MAP.put("lpfFrequency", Param.LOCAL_LPF_FREQ);
    DEST_MAP.put("lpfResonance", Param.LOCAL_LPF_RESONANCE);
    DEST_MAP.put("hpfFrequency", Param.LOCAL_HPF_FREQ);
    DEST_MAP.put("volume", Param.LOCAL_VOLUME);
    DEST_MAP.put("pan", Param.LOCAL_PAN);
    DEST_MAP.put("pitch", Param.LOCAL_PITCH_ADJUST);
    DEST_MAP.put("osc1Pitch", Param.LOCAL_OSC_A_PITCH_ADJUST);
    DEST_MAP.put("osc2Pitch", Param.LOCAL_OSC_B_PITCH_ADJUST);
  }

  public static Song createSong(ProjectModel model) {
    Song song = new Song();
    song.tempoBPM = model.getBpm();

    System.out.println(
        "[FirmwareFactory] Creating FW Song. Tracks in model: " + model.getTracks().size());
    for (TrackModel track : model.getTracks()) {
      if (track instanceof SynthTrackModel synthTrack) {
        InstrumentClip clip = createInstrumentClip(synthTrack);
        song.addClip(clip);
      } else if (track instanceof KitTrackModel kitTrack) {
        InstrumentClip clip = createKitClip(kitTrack);
        song.addClip(clip);
      } else if (track instanceof MidiTrackModel midiTrack) {
        InstrumentClip clip = createMidiClip(midiTrack);
        song.addClip(clip);
      }
    }
    return song;
  }

  private static InstrumentClip createInstrumentClip(SynthTrackModel model) {
    InstrumentClip clip = new InstrumentClip();
    clip.loopLength = 16 * 24;

    FirmwareSound sound = new FirmwareSound();
    clip.sound = sound;

    // ── Copy all parameters and patch cables ──
    mapModelToSound(model, sound);

    // ── Note rendering logic remains ──
    if (!model.getClips().isEmpty()) {
      ClipModel clipModel = model.getClips().get(0);
      int stepTicks = clipModel.isTripletMode() ? 32 : 24;
      clip.tripletMode = clipModel.isTripletMode();
      clip.loopLength = clipModel.getStepCount() * stepTicks;
      mapPlayDirection(clipModel, clip);
      for (int r = 0; r < clipModel.getRowCount(); r++) {
        int pitch = (clipModel.getRowCount() - 1) - r;
        NoteRow row = new NoteRow(pitch);
        java.util.List<org.chuck.deluge.model.HighResNote> rawNotes = clipModel.getRawNoteEvents(r);
        if (rawNotes != null && !rawNotes.isEmpty()) {
          for (org.chuck.deluge.model.HighResNote note : rawNotes) {
            row.attemptNoteAdd(
                note.getTickPos(),
                note.getTickLen(),
                (int) (note.getVelocity() * 127),
                (int) (note.getProbability() * 100),
                null,
                0);
          }
        } else {
          // If the step cell has a custom explicit pitch parameter (e.g. from tests/custom XMLs!),
          // use it to override the row's pitch!
          for (int s = 0; s < clipModel.getStepCount(); s++) {
            StepData step = clipModel.getStep(r, s);
            if (step.active() && step.pitch() > 0) {
              pitch = step.pitch();
              row = new NoteRow(pitch);
              break;
            }
          }
          for (int s = 0; s < clipModel.getStepCount(); s++) {
            StepData step = clipModel.getStep(r, s);
            if (step.active()) {
              row.attemptNoteAdd(
                  s * stepTicks,
                  (int) (step.gate() * stepTicks),
                  (int) (step.velocity() * 127.0f),
                  100,
                  null,
                  0);
            }
          }
        }
        clip.noteRows.add(row);
      }
    }
    return clip;
  }

  private static InstrumentClip createMidiClip(MidiTrackModel model) {
    InstrumentClip clip = new InstrumentClip();
    clip.loopLength = 16 * 24;

    FirmwareMidiInstrument midiInstrument =
        new FirmwareMidiInstrument(model.getMidiChannel(), model.isMpe());
    midiInstrument.setMpeZone(model.getMpeZone());
    clip.sound = midiInstrument;

    if (!model.getClips().isEmpty()) {
      ClipModel clipModel = model.getClips().get(0);
      int stepTicks = clipModel.isTripletMode() ? 32 : 24;
      clip.tripletMode = clipModel.isTripletMode();
      clip.loopLength = clipModel.getStepCount() * stepTicks;
      mapPlayDirection(clipModel, clip);
      for (int r = 0; r < clipModel.getRowCount(); r++) {
        int pitch = (clipModel.getRowCount() - 1) - r;
        NoteRow row = new NoteRow(pitch);
        java.util.List<org.chuck.deluge.model.HighResNote> rawNotes = clipModel.getRawNoteEvents(r);
        if (rawNotes != null && !rawNotes.isEmpty()) {
          for (org.chuck.deluge.model.HighResNote note : rawNotes) {
            row.attemptNoteAdd(
                note.getTickPos(),
                note.getTickLen(),
                (int) (note.getVelocity() * 127),
                (int) (note.getProbability() * 100),
                null,
                0);
          }
        } else {
          // If the step cell has a custom explicit pitch parameter, use it to override the row's
          // pitch!
          for (int s = 0; s < clipModel.getStepCount(); s++) {
            StepData step = clipModel.getStep(r, s);
            if (step.active() && step.pitch() > 0) {
              pitch = step.pitch();
              row = new NoteRow(pitch);
              break;
            }
          }
          for (int s = 0; s < clipModel.getStepCount(); s++) {
            StepData step = clipModel.getStep(r, s);
            if (step.active()) {
              row.attemptNoteAdd(
                  s * stepTicks,
                  (int) (step.gate() * stepTicks),
                  (int) (step.velocity() * 127.0f),
                  100,
                  null,
                  0);
            }
          }
        }
        clip.noteRows.add(row);
      }
    }
    return clip;
  }

  private static void mapModelToSound(SynthTrackModel model, FirmwareSound sound) {
    sound.oscTypes[0] = stringToOscType(model.getOsc1Type());
    sound.oscTypes[1] = stringToOscType(model.getOsc2Type());

    // Map Synth Mode (0=Subtractive, 1=FM, 2=RingMod)
    int modeVal = model.getSynthMode();
    if (modeVal == 1) {
      sound.synthMode = FirmwareSound.SynthMode.FM;
    } else if (modeVal == 2) {
      sound.synthMode = FirmwareSound.SynthMode.RINGMOD;
    } else {
      sound.synthMode = FirmwareSound.SynthMode.SUBTRACTIVE;
    }

    // Map FM modulator-to-carrier frequency ratios (from each modulator's transpose + cents).
    sound.fmRatio1 = model.getFmRatio();
    sound.fmRatio2 = model.getFmRatio2();

    // Native 2-op FM engine: precompute modulator/carrier amplitudes and feedback through the
    // Deluge patched-param curves (port of voice.cpp). The modulator amount sets FM depth (timbre);
    // it is independent of the carrier oscillator volumes. Modulator volume: neutral 2^25, range
    // 2^30, parabola volume curve. Feedback: neutral 5931642, linear curve. 0x80000000 -> 0 (off).
    if (sound.synthMode == FirmwareSound.SynthMode.FM) {
      // Store the raw knob values; the live amplitude (base + patch cables, e.g. envelope2 ->
      // modulator volume) is computed each block in FirmwareVoice via the volume curve.
      sound.fmModulatorAmountBase[0] = model.getModulator1AmountQ31();
      sound.fmModulatorAmountBase[1] = model.getModulator2AmountQ31();
      sound.fmModulatorFeedback[0] =
          FirmwareUtils.finalLinearParam(model.getModulator1FeedbackQ31(), 5931642, 1073741824);
      sound.fmModulatorFeedback[1] =
          FirmwareUtils.finalLinearParam(model.getModulator2FeedbackQ31(), 5931642, 1073741824);
      sound.fmCarrierFeedback[0] =
          FirmwareUtils.finalLinearParam(model.getCarrier1FeedbackQ31(), 5931642, 1073741824);
      sound.fmCarrierFeedback[1] =
          FirmwareUtils.finalLinearParam(model.getCarrier2FeedbackQ31(), 5931642, 1073741824);
      sound.fmModulator1ToModulator0 = model.isModulator1ToModulator0();
    }

    // Volume/Pan
    sound.paramNeutralValues[Param.LOCAL_VOLUME] = (int) (model.getVolume() * 2147483647.0);
    // LOCAL_PAN is BIPOLAR (0 = centre, ±2^30 = hard L/R), matching the firmware shouldDoPanning input.
    sound.paramNeutralValues[Param.LOCAL_PAN] =
        (int) (Math.max(-1.0, Math.min(1.0, model.getPan())) * 1073741824.0);

    // Oscillator & Noise Volumes. In FM mode these are the carrier amplitudes (the modulator depth
    // is carried separately in sound.fmModulatorAmount, no longer smuggled through OSC_B_VOLUME).
    sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] =
        (int) (Math.max(0.0, Math.min(1.0, model.getOscAVolume())) * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] =
        (int) (Math.max(0.0, Math.min(1.0, model.getOscBVolume())) * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = (int) (model.getNoiseVol() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_OSC_B_PITCH_ADJUST] =
        (model.getOsc2Transpose() * 100 + model.getOsc2Cents()) * 178956;

    // Filter cutoff: the faithful filter (FirmwareFilter.curveFrequency: instantTan of the q31
    // LPF_FREQ param) expects the firmware's exp-curved param value, NOT a Hz-derived number. The
    // model carries Hz (the parser ran the cutoff knob through hexToHz); recover the original knob
    // value by inverting hexToHz (bijective), then run the firmware param path:
    // getFinalParameterValueExp(neutral, combineCablesExp(knob, paramRange)). LPF: neutral 2000000,
    // range 536870912*1.4; HPF: neutral 2672947, range 1073741824.
    sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] =
        FirmwareUtils.getExp(2000000, cutoffComboFromHz(model.getLpfFreq(), 751619276));
    sound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = (int) (model.getLpfRes() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_LPF_MORPH] = (int) (model.getLpfMorph() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_HPF_FREQ] =
        FirmwareUtils.getExp(2672947, cutoffComboFromHz(model.getHpfFreq(), 1073741824));
    sound.paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = (int) (model.getHpfRes() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_HPF_MORPH] = (int) (model.getHpfMorph() * 2147483647.0);

    sound.setLpfMode(model.getFilterMode());
    sound.setHpfMode(model.getHpfMode());
    sound.setFilterRoute(model.getFilterRoute());

    // Modulation FX (chorus/flanger/phaser/...). The model carries rate in Hz and
    // depth/offset/feedback as 0..1; convert rate to a Q32 LFO phase increment and the rest to Q31,
    // matching what ModFXProcessor expects. (Previously these were never set on the pure engine, so
    // mod FX was inert regardless of the patch.)
    sound.modFXType = stringToModFXType(model.getModFxType());
    sound.modFXRateIncrement = (int) ((double) model.getModFxRate() * 4294967296.0 / 44100.0);
    sound.modFXDepth = (int) (clamp01(model.getModFxDepth()) * 2147483647.0);
    sound.modFXOffset = (int) (clamp01(model.getModFxOffset()) * 2147483647.0);
    sound.modFXFeedback = (int) (clamp01(model.getModFxFeedback()) * 2147483647.0);

    // Per-track reverb send (previously hardcoded to 0, so the master reverb bus was always
    // silent).
    sound.reverbSendAmount = (int) (clamp01(model.getReverbSend()) * 2147483647.0);

    // Bitcrush + sample-rate reduction (0..1 -> bipolar Q31; MIN_VALUE = off).
    sound.bitcrushParam = normToBipolarParam(model.getBitCrush());
    sound.srrParam = normToBipolarParam(model.getSampleRateReduction());

    // Bass/treble EQ (model stores dB in [-12, 12]; 0 dB = flat).
    sound.eqBassParam = dbToBipolarParam(model.getEqBass());
    sound.eqTrebleParam = dbToBipolarParam(model.getEqTreble());

    // Arpeggiator
    configureArp(sound, model.getArp());

    // DX7 (Dexed) patch — render via the real DX7 engine instead of the native FM mapping.
    sound.dx7Patch = hexToBytes(model.getDx7Patch());
    sound.dx7EngineType = model.getEngineType();
    sound.dx7RandomDetune = model.getDx7RandomDetune();

    // Retrigger Phases
    sound.osc1RetriggerPhase = model.getOsc1RetrigPhase();
    sound.osc2RetriggerPhase = model.getOsc2RetrigPhase();
    sound.mod1RetrigPhase = model.getMod1RetrigPhase();
    sound.mod2RetrigPhase = model.getMod2RetrigPhase();

    // Envelopes
    for (int i = 0; i < 4; i++) {
      EnvelopeModel em = model.getEnv(i);
      int attackInc;
      int decayInc;
      int releaseInc;
      if (model.isEnvKnobSet(i)) {
        // Faithful: the envelope rate increments follow the firmware's per-stage curves
        // (getFinalParameterValueExpWithDumbEnvelopeHack): attack via getExp on the negated patched
        // knob; decay/release via the release-rate table. Neutrals 4096 / 70<<9 / 140<<9; attack
        // range 536870912*1.5, decay/release range 2^30.
        attackInc =
            FirmwareUtils.finalEnvRateParam(
                4096, FirmwareUtils.patchCombineExpStep(0, model.getEnvAttackKnobQ31(i), 805306368), 0);
        decayInc =
            FirmwareUtils.finalEnvRateParam(
                70 << 9,
                FirmwareUtils.patchCombineExpStep(0, model.getEnvDecayKnobQ31(i), 1073741824),
                1);
        releaseInc =
            FirmwareUtils.finalEnvRateParam(
                140 << 9,
                FirmwareUtils.patchCombineExpStep(0, model.getEnvReleaseKnobQ31(i), 1073741824),
                2);
      } else {
        // Programmatic time-in-seconds: increment = 190.2 / time is the faithful time<->increment
        // relationship (the render's pos overflows at 2^23).
        float aTime = Math.max(0.0001f, em.attack());
        float dTime = Math.max(0.0001f, em.decay());
        float rTime = Math.max(0.0001f, em.release());
        attackInc = (int) (190.2f / aTime);
        decayInc = (int) (190.2f / dTime);
        releaseInc = (int) (190.2f / rTime);
      }

      sound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK + i] = Math.max(1, Math.min(8388608, attackInc));
      sound.paramNeutralValues[Param.LOCAL_ENV_0_DECAY + i] = Math.max(1, Math.min(8388608, decayInc));
      sound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN + i] = (int) (em.sustain() * 2147483647.0);
      sound.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE + i] =
          Math.max(1, Math.min(8388608, releaseInc));
    }

    sound.numUnison = model.getUnisonNum();
    sound.unisonDetune = (int) model.getUnisonDetune();
    sound.unisonStereoSpread = (int) (model.getUnisonStereoSpread() * 2147483647.0);

    // Sidechain settings
    int sidechainAttackVal =
        (int) (200 + Math.pow(2.0, (1.0 - model.getSidechainAttack()) * 12.0) * 2.0);
    int sidechainReleaseVal =
        (int) (50 + Math.pow(2.0, (1.0 - model.getSidechainRelease()) * 12.0) * 1.5);
    sound.sidechain.attack = sidechainAttackVal;
    sound.sidechain.release = sidechainReleaseVal;
    sound.sidechain.syncLevel =
        org.chuck.deluge.firmware.model.SyncLevel.values()[
            model.getSidechainSyncLevel()
                % org.chuck.deluge.firmware.model.SyncLevel.values().length];
    sound.sidechain.syncType =
        org.chuck.deluge.firmware.model.SyncType.values()[
            model.getSidechainSyncType()
                % org.chuck.deluge.firmware.model.SyncType.values().length];
    sound
            .paramNeutralValues[
            org.chuck.deluge.firmware.modulation.params.Param.UNPATCHED_SIDECHAIN_SHAPE] =
        0; // default shape
    try {
      sound.polyphonic =
          org.chuck.deluge.firmware.model.PolyphonyMode.valueOf(model.getPolyphony().name());
    } catch (Exception e) {
      sound.polyphonic = org.chuck.deluge.firmware.model.PolyphonyMode.POLY;
    }

    // Map LFO waveforms and dynamic rates
    for (int i = 0; i < 4; i++) {
      LfoModel lm = model.getLfo(i);
      if (lm != null) {
        sound.lfoWaveforms[i] = mapLfoType(lm.waveform());

        int paramId =
            switch (i) {
              case 0 -> Param.GLOBAL_LFO_FREQ_1;
              case 1 -> Param.LOCAL_LFO_LOCAL_FREQ_1;
              case 2 -> Param.GLOBAL_LFO_FREQ_2;
              case 3 -> Param.LOCAL_LFO_LOCAL_FREQ_2;
              default -> -1;
            };
        if (paramId != -1) {
          // Faithful: the unsynced LFO phase increment IS the exp-curved rate param
          // (firmware getLocal/GlobalLFOPhaseIncrement returns paramFinalValues[LFO_FREQ] directly).
          // Feed the RAW stored knob straight to getExp(neutral 121739, combineExp(knob, range 2^30))
          // — the firmware curve — instead of the lossy Hz round-trip. Replaces the old
          // `200 + pow(2,...)*500` formula in the voice/sound render.
          int rateKnob = model.getLfoRateKnobQ31(i);
          sound.paramNeutralValues[paramId] =
              FirmwareUtils.getExp(
                  121739, FirmwareUtils.patchCombineExpStep(0, rateKnob, 1073741824));
        }
      }
    }

    // Patch Cables
    for (org.chuck.deluge.model.PatchCable pcm : model.getPatchCables()) {
      try {
        String destStr = pcm.destination().toUpperCase();
        String srcStr = pcm.source().toUpperCase();

        // Manual mapping from string to Param ID. Order matters: the more specific modulator/osc
        // volume names must be matched before the generic "VOLUME" catch-all, otherwise FM
        // modulator-depth cables (e.g. envelope2 -> modulator1Volume) get misrouted to the master
        // LOCAL_VOLUME and the FM depth never tracks the envelope.
        int paramId = -1;
        if (destStr.contains("LPFFREQUENCY") || destStr.contains("LPF_FREQ"))
          paramId = Param.LOCAL_LPF_FREQ;
        else if (destStr.contains("LPFRESONANCE") || destStr.contains("LPF_RES"))
          paramId = Param.LOCAL_LPF_RESONANCE;
        else if (destStr.contains("HPFFREQUENCY") || destStr.contains("HPF_FREQ"))
          paramId = Param.LOCAL_HPF_FREQ;
        else if (destStr.contains("HPFRESONANCE") || destStr.contains("HPF_RES"))
          paramId = Param.LOCAL_HPF_RESONANCE;
        else if (destStr.contains("MODULATOR1VOLUME")) paramId = Param.LOCAL_MODULATOR_0_VOLUME;
        else if (destStr.contains("MODULATOR2VOLUME")) paramId = Param.LOCAL_MODULATOR_1_VOLUME;
        else if (destStr.contains("CARRIER1FEEDBACK")) paramId = Param.LOCAL_CARRIER_0_FEEDBACK;
        else if (destStr.contains("CARRIER2FEEDBACK")) paramId = Param.LOCAL_CARRIER_1_FEEDBACK;
        else if (destStr.contains("MODULATOR1FEEDBACK")) paramId = Param.LOCAL_MODULATOR_0_FEEDBACK;
        else if (destStr.contains("MODULATOR2FEEDBACK")) paramId = Param.LOCAL_MODULATOR_1_FEEDBACK;
        else if (destStr.contains("OSCAVOLUME") || destStr.contains("OSC1VOLUME"))
          paramId = Param.LOCAL_OSC_A_VOLUME;
        else if (destStr.contains("OSCBVOLUME") || destStr.contains("OSC2VOLUME"))
          paramId = Param.LOCAL_OSC_B_VOLUME;
        else if (destStr.contains("PITCH")) paramId = Param.LOCAL_PITCH_ADJUST;
        else if (destStr.contains("VOLUME")) paramId = Param.LOCAL_VOLUME;

        if (paramId != -1) {
          PatchSource source = stringToPatchSource(pcm.source());
          int amount = (int) (pcm.amount() * 2147483647.0);
          PatchCable engineCable = new PatchCable();
          engineCable.from = source;
          engineCable.amount = amount;
          sound.paramManager.getPatchCableSet().addCable(paramId, engineCable);
        }
      } catch (Exception e) {
        // Skip invalid cables
      }
    }
  }

  /**
   * Recover the original cutoff knob value from the model's Hz (the parser ran the patch's hex knob
   * through {@code DelugeHexMapper.hexToHz}: {@code Hz = 20·1000^((norm+1)/2)}; inverting it returns
   * the exact knob), then form the firmware no-cable exp combine: {@code multiply_32x32_rshift32(knob,
   * paramRange)}. Feed the result to {@code getExp(neutral, combo)} to get the q31 filter-frequency
   * param the faithful filter expects.
   */
  private static int cutoffComboFromHz(double hz, int paramRange) {
    double norm = 2.0 * Math.log(Math.max(20.0, hz) / 20.0) / Math.log(1000.0) - 1.0;
    norm = Math.max(-1.0, Math.min(1.0, norm));
    int knob = (int) Math.rint(norm * 2147483647.0);
    return org.chuck.deluge.firmware.util.Q31.multiply_32x32_rshift32(knob, paramRange);
  }

  public static PatchSource stringToPatchSource(String str) {
    if (str == null) return PatchSource.NONE;
    String clean = str.trim().toUpperCase().replace("_", "").replace(" ", "");
    if (clean.equals("LFO1") || clean.equals("LFOLOCAL1")) return PatchSource.LFO_LOCAL_1;
    if (clean.equals("LFO2") || clean.equals("LFOLOCAL2")) return PatchSource.LFO_LOCAL_2;
    if (clean.equals("LFOGLOBAL1")) return PatchSource.LFO_GLOBAL_1;
    if (clean.equals("LFOGLOBAL2")) return PatchSource.LFO_GLOBAL_2;
    if (clean.equals("ENVELOPE1") || clean.equals("ENV1")) return PatchSource.ENVELOPE_0;
    if (clean.equals("ENVELOPE2") || clean.equals("ENV2")) return PatchSource.ENVELOPE_1;
    if (clean.equals("ENVELOPE3") || clean.equals("ENV3")) return PatchSource.ENVELOPE_2;
    if (clean.equals("ENVELOPE4") || clean.equals("ENV4")) return PatchSource.ENVELOPE_3;
    if (clean.equals("SIDECHAIN")) return PatchSource.SIDECHAIN;
    if (clean.equals("X")) return PatchSource.X;
    if (clean.equals("Y")) return PatchSource.Y;
    if (clean.equals("AFTERTOUCH")) return PatchSource.AFTERTOUCH;
    if (clean.equals("VELOCITY")) return PatchSource.VELOCITY;
    if (clean.equals("NOTE")) return PatchSource.NOTE;
    if (clean.equals("RANDOM")) return PatchSource.RANDOM;
    try {
      return PatchSource.valueOf(str.toUpperCase());
    } catch (Exception e) {
      return PatchSource.NONE;
    }
  }

  private static OscType stringToOscType(String s) {
    if (s == null) return OscType.SINE;
    try {
      return OscType.valueOf(s.toUpperCase());
    } catch (Exception e) {
      return OscType.SINE;
    }
  }

  private static org.chuck.deluge.firmware.dsp.fx.ModFXType stringToModFXType(String s) {
    if (s == null) return org.chuck.deluge.firmware.dsp.fx.ModFXType.NONE;
    try {
      return org.chuck.deluge.firmware.dsp.fx.ModFXType.valueOf(s.trim().toUpperCase());
    } catch (Exception e) {
      return org.chuck.deluge.firmware.dsp.fx.ModFXType.NONE;
    }
  }

  private static float clamp01(float v) {
    return (v < 0f) ? 0f : (v > 1f ? 1f : v);
  }

  /** Map a 0..1 knob to the firmware's bipolar Q31 param range; 0 -> MIN_VALUE ("off"). */
  private static int normToBipolarParam(float norm) {
    if (norm <= 0f) return Integer.MIN_VALUE;
    double v = (double) clamp01(norm) * 4294967295.0 - 2147483648.0;
    return (int) Math.round(v);
  }

  /** Parse a DX7 patch hex string (e.g. 312 chars = 156 bytes) to bytes; null/blank/odd → null. */
  private static byte[] hexToBytes(String hex) {
    if (hex == null) return null;
    hex = hex.trim();
    if (hex.isEmpty() || (hex.length() & 1) != 0) return null;
    byte[] out = new byte[hex.length() / 2];
    try {
      for (int i = 0; i < out.length; i++) {
        out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      }
    } catch (NumberFormatException e) {
      return null;
    }
    return out;
  }

  /** Map an EQ gain in dB ([-12, 12], 0 = flat) to a bipolar Q31 param (0 = flat). */
  private static int dbToBipolarParam(float db) {
    double norm = Math.max(-1.0, Math.min(1.0, db / 12.0));
    return (int) (norm * 2147483647.0);
  }

  /** Map an ArpModel onto a sound's per-sound arpeggiator settings. */
  private static void configureArp(
      org.chuck.deluge.firmware.engine.FirmwareSound sound, org.chuck.deluge.model.ArpModel arp) {
    var s = sound.arpeggiator.settings;
    if (arp == null || !arp.active()) {
      s.mode = org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode.OFF;
      return;
    }
    s.mode = stringToArpMode(arp.mode());
    s.octaveMode = stringToArpOctaveMode(arp.octaveMode());
    s.numOctaves = Math.max(1, arp.octaves());
    s.numStepRepeats = Math.max(1, arp.stepRepeat());
    s.gate = (int) (Math.max(0f, Math.min(1f, arp.gate())) * 2147483647.0);
    // syncLevel here is a note-division denominator (1=whole, 4=quarter, 16=16th); default 16th.
    sound.arpDivision = (arp.syncLevel() > 0) ? arp.syncLevel() : 16;
  }

  private static org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode stringToArpMode(
      String m) {
    if (m == null) return org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode.UP;
    return switch (m.trim().toUpperCase()) {
      case "DOWN" -> org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode.DOWN;
      case "UP_DOWN", "UPDN", "UPDOWN" ->
          org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode.UPDOWN;
      case "RANDOM", "RAND", "WALK", "WLK1", "WLK2", "WLK3" ->
          org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode.RANDOM;
      case "OFF" -> org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode.OFF;
      default -> org.chuck.deluge.firmware.modulation.Arpeggiator.ArpMode.UP;
    };
  }

  private static org.chuck.deluge.firmware.modulation.Arpeggiator.ArpOctaveMode
      stringToArpOctaveMode(String m) {
    if (m == null) return org.chuck.deluge.firmware.modulation.Arpeggiator.ArpOctaveMode.UP;
    return switch (m.trim().toUpperCase()) {
      case "DOWN" -> org.chuck.deluge.firmware.modulation.Arpeggiator.ArpOctaveMode.DOWN;
      case "UPDN", "UPDOWN", "ALT" ->
          org.chuck.deluge.firmware.modulation.Arpeggiator.ArpOctaveMode.UPDOWN;
      default -> org.chuck.deluge.firmware.modulation.Arpeggiator.ArpOctaveMode.UP;
    };
  }

  public static InstrumentClip createKitClip(KitTrackModel model) {
    InstrumentClip clip = new InstrumentClip();
    FirmwareKit kit = new FirmwareKit();
    clip.sound = kit;
    clip.loopLength = 16 * 24;

    File sdRoot = PreferencesManager.getLibraryDir();
    File devSamples = new File("deluge/src/main/resources");
    if (!devSamples.exists()) {
      devSamples = new File("src/main/resources");
    }

    int drumIdx = 0;
    for (Drum d : model.getDrums()) {
      if (drumIdx >= kit.drumSounds.size()) break;
      if (d instanceof SoundDrum sd) {
        FirmwareSound drumSound = kit.drumSounds.get(drumIdx);
        drumSound.isDrum = true;
        drumSound.oscTypes[0] = OscType.SAMPLE;
        drumSound
                .paramNeutralValues[
                org.chuck.deluge.firmware.modulation.params.Param.LOCAL_OSC_A_VOLUME] =
            org.chuck.deluge.firmware.util.Q31.ONE;
        drumSound
                .paramNeutralValues[
                org.chuck.deluge.firmware.modulation.params.Param.LOCAL_OSC_B_VOLUME] =
            0;
        drumSound
                .paramNeutralValues[
                org.chuck.deluge.firmware.modulation.params.Param.LOCAL_NOISE_VOLUME] =
            0;
        drumSound.osc1RetriggerPhase = sd.getOsc1RetrigPhase();
        drumSound.osc2RetriggerPhase = sd.getOsc2RetrigPhase();
        drumSound.mod1RetrigPhase = sd.getMod1RetrigPhase();
        drumSound.mod2RetrigPhase = sd.getMod2RetrigPhase();
        drumSound.sidechainSend = (int) (sd.getSidechainSend() * 2147483647.0);

        // Per-drum modulation FX (same mapping as synth sounds).
        drumSound.modFXType = stringToModFXType(sd.getModFxType());
        drumSound.modFXRateIncrement = (int) ((double) sd.getModFxRate() * 4294967296.0 / 44100.0);
        drumSound.modFXDepth = (int) (clamp01(sd.getModFxDepth()) * 2147483647.0);
        drumSound.modFXOffset = (int) (clamp01(sd.getModFxOffset()) * 2147483647.0);
        drumSound.modFXFeedback = (int) (clamp01(sd.getModFxFeedback()) * 2147483647.0);
        drumSound.bitcrushParam = normToBipolarParam(sd.getBitCrush());
        drumSound.srrParam = normToBipolarParam(sd.getSampleRateReduction());
        drumSound.eqBassParam = dbToBipolarParam(sd.getEqBass());
        drumSound.eqTrebleParam = dbToBipolarParam(sd.getEqTreble());

        // Map per-lane step automation from the ClipModel to the drum sound's ParamManager
        if (!model.getClips().isEmpty()) {
          org.chuck.deluge.model.ClipModel clipModel = model.getClips().get(0);
          int stepTicks = clipModel.isTripletMode() ? 32 : 24;
          java.util.Map<String, float[]> rowAutos = clipModel.getRowAutomationData().get(drumIdx);
          if (rowAutos != null) {
            for (java.util.Map.Entry<String, float[]> entry : rowAutos.entrySet()) {
              int paramId = getParamIdFromName(entry.getKey());
              if (paramId != -1) {
                float[] array = entry.getValue();
                for (int s = 0; s < array.length; s++) {
                  if (array[s] > 0.0f) {
                    int q31Val = (int) (array[s] * 2147483647.0);
                    int pos = s * stepTicks;
                    drumSound.paramManager.recordParamValue(paramId, q31Val, pos);
                  }
                }
              }
            }
          }
        }

        String path = sd.getSamplePath();
        if (path != null && !path.isEmpty()) {
          File f = resolveSample(path, sdRoot, devSamples);
          if (f != null && f.exists()) {
            try {
              Sample s = AudioFileReader.readSample(f.getAbsolutePath());
              if (s != null) {
                drumSound.samples[0] = s;
                System.out.println(
                    "[DIAG] Sample: "
                        + s.fileName
                        + " size="
                        + s.data.length
                        + " first10="
                        + java.util.Arrays.toString(
                            java.util.Arrays.copyOfRange(s.data, 0, Math.min(s.data.length, 10))));
                System.out.println(
                    "[FirmwareFactory] Loaded sample: "
                        + f.getName()
                        + " (size: "
                        + s.getNumSamples()
                        + ")");
              }
            } catch (IOException e) {
              System.err.println("[FirmwareFactory] Failed to load kit sample: " + path);
            }
          } else {
            System.err.println("[FirmwareFactory] Sample NOT FOUND: " + path);
          }
        }
      }
      drumIdx++;
    }

    if (!model.getClips().isEmpty()) {
      ClipModel clipModel = model.getClips().get(0);
      int stepTicks = clipModel.isTripletMode() ? 32 : 24;
      clip.tripletMode = clipModel.isTripletMode();
      clip.loopLength = clipModel.getStepCount() * stepTicks;
      mapPlayDirection(clipModel, clip);
      for (int r = 0; r < clipModel.getRowCount(); r++) {
        NoteRow row = new NoteRow(r);
        java.util.List<org.chuck.deluge.model.HighResNote> rawNotes = clipModel.getRawNoteEvents(r);
        if (rawNotes != null && !rawNotes.isEmpty()) {
          for (org.chuck.deluge.model.HighResNote note : rawNotes) {
            row.attemptNoteAdd(
                note.getTickPos(),
                note.getTickLen(),
                (int) (note.getVelocity() * 127),
                (int) (note.getProbability() * 100),
                null,
                0);
          }
        } else {
          for (int s = 0; s < clipModel.getStepCount(); s++) {
            StepData step = clipModel.getStep(r, s);
            if (step.active()) {
              row.attemptNoteAdd(
                  s * stepTicks,
                  (int) (step.gate() * stepTicks),
                  (int) (step.velocity() * 127.0f),
                  100,
                  null,
                  0);
            }
          }
        }
        clip.noteRows.add(row);
      }
    }
    return clip;
  }

  private static File resolveSample(String path, File sdRoot, File devSamples) {
    File f = new File(path);
    if (f.exists()) return f;
    if (sdRoot != null) {
      f = new File(sdRoot, path);
      if (f.exists()) return f;
    }
    f = new File(devSamples, path);
    return f;
  }

  public static void syncFirmwareToModel(Song fwSong, ProjectModel model) {
    if (fwSong == null || model == null) return;
    System.out.println(
        "[FirmwareFactory] syncFirmwareToModel active, syncing " + fwSong.clips.size() + " clips");

    int totalTracks = Math.min(fwSong.clips.size(), model.getTracks().size());
    for (int t = 0; t < totalTracks; t++) {
      Clip clip = fwSong.clips.get(t);
      TrackModel trackModel = model.getTracks().get(t);

      if (clip instanceof InstrumentClip instrumentClip) {
        if (trackModel.getClips().isEmpty()) continue;
        ClipModel clipModel = trackModel.getClips().get(0);
        int stepTicks = clipModel.isTripletMode() ? 32 : 24;

        // 1. Clear existing steps/notes in Java Model
        for (int r = 0; r < clipModel.getRowCount(); r++) {
          for (int s = 0; s < clipModel.getStepCount(); s++) {
            clipModel.setStep(r, s, StepData.empty());
          }
          clipModel.setRawNoteEvents(r, null);
        }

        // 2. Back-propagate NoteRows from Layer 3 (firmware) to Layer 1 (Java Model)
        for (NoteRow noteRow : instrumentClip.noteRows) {
          int pitch = noteRow.y;
          int r;
          if (trackModel instanceof SynthTrackModel) {
            r = (clipModel.getRowCount() - 1) - pitch;
          } else if (trackModel instanceof KitTrackModel) {
            r = pitch;
          } else if (trackModel instanceof MidiTrackModel) {
            r = (clipModel.getRowCount() - 1) - pitch;
          } else {
            r = pitch;
          }

          if (r < 0 || r >= clipModel.getRowCount()) continue;

          java.util.List<org.chuck.deluge.model.HighResNote> rawNotes = new java.util.ArrayList<>();

          for (Note note : noteRow.notes) {
            int tickPos = note.pos;
            int tickLen = note.length;
            float vel = note.getVelocity() / 127.0f;
            float prob = note.getProbability() / 100.0f;
            rawNotes.add(new org.chuck.deluge.model.HighResNote(tickPos, tickLen, vel, prob, 0));

            int startStep = tickPos / stepTicks;
            if (startStep >= 0 && startStep < clipModel.getStepCount()) {
              float gateSteps = (float) tickLen / stepTicks;
              StepData step =
                  new StepData(true, vel, gateSteps, prob, pitch, 0, note.getFill() / 100.0f);
              clipModel.setStep(r, startStep, step);

              int endStep = (int) (startStep + gateSteps - 0.05f);
              for (int s = startStep + 1; s <= endStep; s++) {
                if (s >= 0 && s < clipModel.getStepCount()) {
                  clipModel.setStep(r, s, new StepData(false, 0.8f, 0.0f, 1.0f, 0, 0, 0.0f));
                }
              }
            }
          }

          if (!rawNotes.isEmpty()) {
            clipModel.setRawNoteEvents(r, rawNotes);
          }
        }
      }
    }
  }

  private static int amountToQ31(String dest, float amount) {
    if (dest.toLowerCase().contains("pitch")) {
      return (int) (amount * 1000000.0);
    }
    return (int) (amount * 2147483647.0);
  }

  private static int getParamIdFromName(String name) {
    return switch (name) {
      case "volume" -> Param.LOCAL_VOLUME;
      case "pan" -> Param.LOCAL_PAN;
      case "lpfFrequency" -> Param.LOCAL_LPF_FREQ;
      case "lpfResonance" -> Param.LOCAL_LPF_RESONANCE;
      case "lpfMorph" -> Param.LOCAL_LPF_MORPH;
      case "hpfFrequency" -> Param.LOCAL_HPF_FREQ;
      case "hpfResonance" -> Param.LOCAL_HPF_RESONANCE;
      case "hpfMorph" -> Param.LOCAL_HPF_MORPH;
      case "delayRate" -> Param.GLOBAL_DELAY_RATE;
      case "delayFeedback" -> Param.GLOBAL_DELAY_FEEDBACK;
      case "reverbAmount" -> Param.GLOBAL_REVERB_AMOUNT;
      default -> -1;
    };
  }

  private static org.chuck.deluge.firmware.modulation.LFO.LFOType mapLfoType(
      org.chuck.deluge.model.LfoType type) {
    if (type == null) return org.chuck.deluge.firmware.modulation.LFO.LFOType.SINE;
    switch (type) {
      case S_AND_H:
        return org.chuck.deluge.firmware.modulation.LFO.LFOType.SAMPLE_AND_HOLD;
      default:
        return org.chuck.deluge.firmware.modulation.LFO.LFOType.valueOf(type.name());
    }
  }

  private static void mapPlayDirection(ClipModel clipModel, Clip clip) {
    if (clipModel != null) {
      clip.sequenceDirectionMode =
          switch (clipModel.getPlayDirection()) {
            case FORWARD -> org.chuck.deluge.firmware.model.SequenceDirection.FORWARD;
            case REVERSE -> org.chuck.deluge.firmware.model.SequenceDirection.REVERSE;
            case PING_PONG -> org.chuck.deluge.firmware.model.SequenceDirection.PINGPONG;
            case RANDOM -> org.chuck.deluge.firmware.model.SequenceDirection.RANDOM;
          };
      if (clip.sequenceDirectionMode == org.chuck.deluge.firmware.model.SequenceDirection.REVERSE) {
        clip.currentlyPlayingReversed = true;
      }
    }
  }
}
