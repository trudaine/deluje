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
import org.chuck.deluge.firmware.modulation.patch.PatchCable;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.storage.audio.AudioFileReader;
import org.chuck.deluge.firmware.storage.wave_table.WaveTable;
import org.chuck.deluge.firmware.storage.wave_table.WaveTableReader;
import org.chuck.deluge.firmware.util.FirmwareUtils;
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

    // ── Load samples/wavetables for SAMPLE/WAVETABLE oscillators ──
    loadOscResources(model, sound);

    // ── Note rendering logic remains ──
    if (!model.getClips().isEmpty()) {
      ClipModel clipModel = model.getClips().get(0);
      int stepTicks = clipModel.isTripletMode() ? 32 : 24;
      clip.tripletMode = clipModel.isTripletMode();
      clip.loopLength = clipModel.getStepCount() * stepTicks;
      mapPlayDirection(clipModel, clip);
      for (int r = 0; r < clipModel.getRowCount(); r++) {
        // Real-format Deluge songs have SPARSE noteRows carrying an absolute yNote (MIDI note);
        // the row-index mapping ((rowCount-1)-r, the UI's 128-row grid convention) played wrong
        // pitches for them — found via hardware comparison: the Deluge played the documented C5,
        // our render of the same file didn't.
        int yNote = clipModel.getRowYNote(r);
        int pitch = (yNote >= 0) ? yNote : (clipModel.getRowCount() - 1) - r;
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
        // Real-format Deluge songs have SPARSE noteRows carrying an absolute yNote (MIDI note);
        // the row-index mapping ((rowCount-1)-r, the UI's 128-row grid convention) played wrong
        // pitches for them — found via hardware comparison: the Deluge played the documented C5,
        // our render of the same file didn't.
        int yNote = clipModel.getRowYNote(r);
        int pitch = (yNote >= 0) ? yNote : (clipModel.getRowCount() - 1) - r;
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

  /**
   * Loads sample/wavetable files for SAMPLE/WAVETABLE oscillator sources. Idempotent: skips the
   * file read when the same path is already loaded for that source (tracked in {@code
   * sound.loadedOscPath}), so live re-applies don't re-read files. The loaded data is assigned
   * under the sound's lock so the audio thread never sees a half-updated source.
   */
  public static void loadOscResources(SynthTrackModel model, FirmwareSound sound) {
    File sdRoot = PreferencesManager.getLibraryDir();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      for (int s = 0; s < 2; s++) {
        final int finalS = s;
        executor.submit(
            () -> {
              String path = (finalS == 0) ? model.getOsc1SamplePath() : model.getOsc2SamplePath();
              OscType type = sound.oscTypes[finalS];
              boolean wantsFile =
                  (type == OscType.SAMPLE || type == OscType.WAVETABLE)
                      && path != null
                      && !path.isEmpty();
              if (!wantsFile) {
                synchronized (sound) {
                  sound.loadedOscPath[finalS] = null;
                }
                return;
              }
              String key = type + ":" + path;
              synchronized (sound) {
                if (key.equals(sound.loadedOscPath[finalS])) {
                  return; // already loaded
                }
              }
              File f = resolveSample(path, sdRoot);
              if (f == null || !f.exists()) {
                return;
              }
              if (type == OscType.SAMPLE) {
                try {
                  Sample smp = AudioFileReader.readSample(f.getAbsolutePath());
                  if (smp != null) {
                    var fw2Smp = org.chuck.deluge.firmware2.Sample.fromFirmwareSample(smp);
                    synchronized (sound) {
                      sound.samples[finalS] = smp;
                      sound.fw2SampleCache[finalS] = fw2Smp;
                      sound.loadedOscPath[finalS] = key;
                    }
                    System.out.println(
                        "[FirmwareFactory] Loaded synth sample " + finalS + ": " + f.getName());
                  }
                } catch (IOException e) {
                  System.err.println(
                      "[FirmwareFactory] Failed to load synth sample " + finalS + ": " + path);
                }
              } else {
                try {
                  WaveTable wt = new WaveTable();
                  WaveTableReader.readWavetable(wt, f.getAbsolutePath());
                  synchronized (sound) {
                    sound.fw2Sound.waveTables[finalS] = wt;
                    sound.loadedOscPath[finalS] = key;
                  }
                  System.out.println(
                      "[FirmwareFactory] Loaded synth wavetable " + finalS + ": " + f.getName());
                } catch (IOException e) {
                  System.err.println(
                      "[FirmwareFactory] Failed to load synth wavetable " + finalS + ": " + path);
                }
              }
            });
      }
    }
  }

  /**
   * Live-apply: re-maps the model onto an already-playing bridge sound so dialog edits are heard
   * immediately (the per-block {@code syncParamsToFw2} forwards everything to the fw2 engine).
   * Param/cable writes happen under the sound's lock — {@code syncParamsToFw2} takes the same lock
   * — so the audio thread never iterates a half-rebuilt cable set. File loads happen outside it.
   */
  public static void applyModelToLiveSound(SynthTrackModel model, FirmwareSound sound) {
    synchronized (sound) {
      mapModelToSound(model, sound);
    }
    loadOscResources(model, sound);
  }

  private static void mapModelToSound(SynthTrackModel model, FirmwareSound sound) {
    // Idempotent: clear the cable set so live re-applies don't duplicate cables (they are
    // re-added from the model at the end of this method).
    sound.paramManager.getPatchCableSet().destinations.clear();
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
    sound.fmModulator1Transpose = model.getModulator1Transpose();
    sound.fmModulator1Cents = model.getModulator1Cents();
    sound.fmModulator2Transpose = model.getModulator2Transpose();
    sound.fmModulator2Cents = model.getModulator2Cents();

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

      // The C reads these XML values straight into the patched-param KNOBS (sound.cpp:520-548
      // readParam of modulator1Amount/modulator2Amount/feedbacks; default INT_MIN = off from
      // initParams, sound.cpp:159-162). Without this the fw2 modulator volume stays INT_MIN →
      // paramFinal 0 → no modulation → FM patches play a plain sine.
      sound.paramNeutralValues[Param.LOCAL_MODULATOR_0_VOLUME] = model.getModulator1AmountQ31();
      sound.paramNeutralValues[Param.LOCAL_MODULATOR_1_VOLUME] = model.getModulator2AmountQ31();
      sound.paramNeutralValues[Param.LOCAL_MODULATOR_0_FEEDBACK] = model.getModulator1FeedbackQ31();
      sound.paramNeutralValues[Param.LOCAL_MODULATOR_1_FEEDBACK] = model.getModulator2FeedbackQ31();
      sound.paramNeutralValues[Param.LOCAL_CARRIER_0_FEEDBACK] = model.getCarrier1FeedbackQ31();
      sound.paramNeutralValues[Param.LOCAL_CARRIER_1_FEEDBACK] = model.getCarrier2FeedbackQ31();
    }

    // Volume/Pan
    sound.paramNeutralValues[Param.LOCAL_VOLUME] = normToBipolarParamVolume(model.getVolume());
    // LOCAL_PAN is BIPOLAR (0 = centre, ±2^30 = hard L/R), matching the firmware shouldDoPanning
    // input.
    sound.paramNeutralValues[Param.LOCAL_PAN] =
        (int) (Math.max(-1.0, Math.min(1.0, model.getPan())) * 1073741824.0);

    // Oscillator & Noise Volumes. In FM mode these are the carrier amplitudes (the modulator depth
    // is carried separately in sound.fmModulatorAmount, no longer smuggled through OSC_B_VOLUME).
    sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] =
        normToBipolarParamVolume(model.getOscAVolume());
    sound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] =
        normToBipolarParamVolume(model.getOscBVolume());
    sound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] =
        normToBipolarParamVolume(model.getNoiseVol());
    sound.paramNeutralValues[Param.LOCAL_OSC_B_PITCH_ADJUST] =
        (model.getOsc2Transpose() * 100 + model.getOsc2Cents()) * 178956;

    // Filter cutoff: the faithful filter (FirmwareFilter.curveFrequency: instantTan of the q31
    // LPF_FREQ param) expects the firmware's exp-curved param value, NOT a Hz-derived number. The
    // model carries Hz (the parser ran the cutoff knob through hexToHz); recover the original knob
    // value by inverting hexToHz (bijective), then run the firmware param path:
    // getFinalParameterValueExp(neutral, combineCablesExp(knob, paramRange)). LPF: neutral 2000000,
    // range 536870912*1.4; HPF: neutral 2672947, range 1073741824.
    sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] = cutoffKnobFromHz(model.getLpfFreq());
    sound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = normToLinearParamKnob(model.getLpfRes());
    sound.paramNeutralValues[Param.LOCAL_LPF_MORPH] = normToLinearParamKnob(model.getLpfMorph());
    sound.paramNeutralValues[Param.LOCAL_HPF_FREQ] = cutoffKnobFromHz(model.getHpfFreq());
    sound.paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = normToLinearParamKnob(model.getHpfRes());
    sound.paramNeutralValues[Param.LOCAL_HPF_MORPH] = normToLinearParamKnob(model.getHpfMorph());

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

    // Per-sound delay (the instrument's own <delay> + soundParams delayFeedback). syncParamsToFw2
    // converts delaySyncLevel to the BPM-synced buffer rate. delayFeedback is the raw Q31 knob;
    // INT_MIN/0 (or syncLevel 0) leaves it inert.
    sound.delaySyncLevel = model.getDelaySyncLevel();
    sound.delaySyncType = model.getDelaySyncType();
    sound.delayPingPong = model.isDelayPingPong();
    sound.delayAnalog = model.isDelayAnalog();
    int dfb = model.getDelayFeedbackQ31();
    sound.delayFeedbackAmount = (dfb == Integer.MIN_VALUE) ? 0 : Math.max(0, dfb);

    // Per-track reverb send KNOB (raw Q31 UNPATCHED_REVERB_SEND_AMOUNT). The actual send amount is
    // derived per block via the C volume curve in GlobalEffectable. normToBipolarParamVolume maps
    // the
    // model's 0..1 send to the Deluge's INT_MIN..INT_MAX knob range, so off/unset (norm <= 0) →
    // INT_MIN
    // → no reverb (dry songs stay dry).
    sound.reverbSendKnob = normToBipolarParamVolume(model.getReverbSend());

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

    // Retrigger Phases + oscillator hard sync
    sound.osc1RetriggerPhase = model.getOsc1RetrigPhase();
    sound.osc2RetriggerPhase = model.getOsc2RetrigPhase();
    sound.mod1RetrigPhase = model.getMod1RetrigPhase();
    sound.mod2RetrigPhase = model.getMod2RetrigPhase();
    sound.oscillatorSync = model.isOscillatorSync();

    // Wave Index parameters (mapped to unipolar Q31)
    int waveIndexQ31 = (int) Math.round((double) clamp01(model.getWaveIndex()) * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_OSC_A_WAVE_INDEX] = waveIndexQ31;
    sound.paramNeutralValues[Param.LOCAL_OSC_B_WAVE_INDEX] = waveIndexQ31;

    // Wavefolder knob (raw Q31 like the C readParam of "waveFold" → LOCAL_FOLD; INT_MIN = off).
    sound.paramNeutralValues[Param.LOCAL_FOLD] = model.getWaveFoldQ31();

    // Saturation/clipping amount (C clippingAmount; 0 = off).
    sound.clippingAmount = model.getClippingAmount();

    // Portamento knob (C UNPATCHED_PORTAMENTO, raw Q31; INT_MIN = off).
    sound.portamentoKnob = model.getPortamentoQ31();

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
                4096,
                FirmwareUtils.patchCombineExpStep(0, model.getEnvAttackKnobQ31(i), 805306368),
                0);
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

      int attackKnob;
      int decayKnob;
      int sustainKnob = normToLinearParamKnob(em.sustain());
      int releaseKnob;
      if (model.isEnvKnobSet(i)) {
        attackKnob = model.getEnvAttackKnobQ31(i);
        decayKnob = model.getEnvDecayKnobQ31(i);
        releaseKnob = model.getEnvReleaseKnobQ31(i);
      } else {
        float normAttack = org.chuck.deluge.xml.DelugeHexMapper.normFromEnvTime(em.attack());
        attackKnob = (int) Math.rint(normAttack * 2147483647.0);
        float normDecay = org.chuck.deluge.xml.DelugeHexMapper.normFromEnvTime(em.decay());
        decayKnob = (int) Math.rint(normDecay * 2147483647.0);
        float normRelease = org.chuck.deluge.xml.DelugeHexMapper.normFromEnvTime(em.release());
        releaseKnob = (int) Math.rint(normRelease * 2147483647.0);
      }

      sound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK + i] = attackKnob;
      sound.paramNeutralValues[Param.LOCAL_ENV_0_DECAY + i] = decayKnob;
      sound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN + i] = sustainKnob;
      sound.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE + i] = releaseKnob;
    }

    // C sound.cpp:616-626: numUnison clamped 0..kMaxNumVoicesUnison(8), detune 0..50, spread
    // 0..50 — all USER units (fw2 setupUnisonStereoSpread scales by 42949672 itself; the previous
    // *2^31 here overflowed and fed garbage spread values into the unison pan setup).
    sound.numUnison = Math.max(0, Math.min(8, model.getUnisonNum()));
    sound.unisonDetune = Math.max(0, Math.min(50, (int) model.getUnisonDetune()));
    sound.unisonStereoSpread = Math.max(0, Math.min(50, (int) model.getUnisonStereoSpread()));

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
          // (firmware getLocal/GlobalLFOPhaseIncrement returns paramFinalValues[LFO_FREQ]
          // directly).
          // Store the RAW knob: the fw2 Patcher applies the single faithful curve
          // (getExp(121739, combineExp(knob, 2^30))) per block. Pre-curving here DOUBLE-curved
          // the rate (found via hardware comparison: knob 0x1999997E → hardware 3.79 Hz, our
          // render ~1 Hz because the curved phase increment was curved again).
          sound.paramNeutralValues[paramId] = model.getLfoRateKnobQ31(i);
        }
      }
    }

    // Populate firmware2 raw knobs: copy all paramNeutralValues (which are raw bipolar
    // knobs for most params set by normToBipolarParam/Volume and cutoffKnobFromHz),
    // then fix envelope params to use the raw knobs (paramNeutralValues stores curve outputs).
    System.arraycopy(sound.paramNeutralValues, 0, sound.paramKnobs, 0, Param.kNumParams);
    for (int i = 0; i < 4; i++) {
      if (model.isEnvKnobSet(i)) {
        sound.paramKnobs[Param.LOCAL_ENV_0_ATTACK + i] = model.getEnvAttackKnobQ31(i);
        sound.paramKnobs[Param.LOCAL_ENV_0_DECAY + i] = model.getEnvDecayKnobQ31(i);
        sound.paramKnobs[Param.LOCAL_ENV_0_RELEASE + i] = model.getEnvReleaseKnobQ31(i);
      }
    }
    sound.paramKnobsPopulated = true;

    // Raw Q31 param-knob overrides from a song clip's <soundParams> (firmware reads these verbatim;
    // applied after the float-based mapping so they win for params the float round-trip mis-ranges,
    // e.g. filter resonance/morph/cutoff). See SynthTrackModel.rawParamKnobs.
    for (var e : model.getRawParamKnobs().entrySet()) {
      int pid = e.getKey();
      sound.paramNeutralValues[pid] = e.getValue();
      sound.paramKnobs[pid] = e.getValue();
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
          if (pcm.polarity() == org.chuck.deluge.model.PatchCable.Polarity.UNIPOLAR) {
            engineCable.polarity =
                org.chuck.deluge.firmware.modulation.patch.PatchCable.Polarity.UNIPOLAR;
          } else {
            engineCable.polarity =
                org.chuck.deluge.firmware.modulation.patch.PatchCable.Polarity.BIPOLAR;
          }
          sound.paramManager.getPatchCableSet().addCable(paramId, engineCable);
        }
      } catch (Exception e) {
        // Skip invalid cables
      }
    }

    // ── LFO depth/target → synthesized patch cables ──
    // The LfoModel carries a depth + target (the UI's LFO tab) but only the rate/waveform were
    // mapped, so the depth/target controls were dead. An LFO modulating a destination IS a patch
    // cable on the Deluge — synthesize one per configured LFO slot (0=global1, 1=local1,
    // 2=global2, 3=local2, matching the rate mapping above).
    for (int i = 0; i < 4; i++) {
      LfoModel lm = model.getLfo(i);
      if (lm == null || lm.target() == null || lm.depth() == 0f) {
        continue;
      }
      int paramId =
          switch (lm.target().trim().toUpperCase()) {
            case "FILTER", "LPF", "LPFFREQUENCY" -> Param.LOCAL_LPF_FREQ;
            case "RES", "RESONANCE" -> Param.LOCAL_LPF_RESONANCE;
            case "PAN" -> Param.LOCAL_PAN;
            case "PITCH" -> Param.LOCAL_PITCH_ADJUST;
            case "VOL", "VOLUME" -> Param.LOCAL_VOLUME;
            case "FM" -> Param.LOCAL_MODULATOR_0_VOLUME;
            default -> -1;
          };
      if (paramId == -1) {
        continue;
      }
      PatchCable cable = new PatchCable();
      cable.from =
          switch (i) {
            case 0 -> PatchSource.LFO_GLOBAL_1;
            case 1 -> PatchSource.LFO_LOCAL_1;
            case 2 -> PatchSource.LFO_GLOBAL_2;
            default -> PatchSource.LFO_LOCAL_2;
          };
      cable.amount = (int) (Math.max(-1f, Math.min(1f, lm.depth())) * 2147483647.0);
      sound.paramManager.getPatchCableSet().addCable(paramId, cable);
    }
  }

  private static int cutoffKnobFromHz(double hz) {
    double norm = 2.0 * Math.log(Math.max(20.0, hz) / 20.0) / Math.log(1000.0) - 1.0;
    norm = Math.max(-1.0, Math.min(1.0, norm));
    return (int) Math.rint(norm * 2147483647.0);
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
    // The Deluge XML names (C stringToOscType, functions.cpp:760-790) don't match the enum
    // constants for the analog models — map them explicitly (they silently became SINE before).
    String t = s.trim();
    if (t.equalsIgnoreCase("analogSaw")) return OscType.ANALOG_SAW_2;
    if (t.equalsIgnoreCase("analogSquare")) return OscType.ANALOG_SQUARE;
    if (t.equalsIgnoreCase("inLeft")) return OscType.INPUT_L;
    if (t.equalsIgnoreCase("inRight")) return OscType.INPUT_R;
    if (t.equalsIgnoreCase("inStereo")) return OscType.INPUT_STEREO;
    try {
      return OscType.valueOf(t.toUpperCase());
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
  /**
   * Map a 0..1 knob to a bipolar Q31 knob for non-volume params. 0 -> MIN_VALUE, 1 -> MAX_VALUE.
   */
  private static int normToBipolarParam(float norm) {
    if (norm <= 0f) return Integer.MIN_VALUE;
    return (int) Math.round((double) clamp01(norm) * 4294967295.0 - 2147483648.0);
  }

  private static int normToLinearParamKnob(float norm) {
    if (norm <= 0f) return -536870912;
    return (int) Math.round((double) clamp01(norm) * 1073741824.0 - 536870912.0);
  }

  /**
   * Map a 0..1 knob to the firmware's volume param range. 0 -> MIN_VALUE ("off"), 1 -> ~MAX_VALUE
   * (full). The firmware volume knob (getParamFromUserValue) spans from (uint32_t)0*FACTOR-2^30 =
   * -2^30 (MIN user value 0, off) up to (uint32_t)50*FACTOR-2^30 ≈ +2^31 (MAX user value 50, full).
   * This scaling is the bipolar mapping of a 0..50 menu item onto int32_t, so norm ∈ [0,1] maps
   * linearly onto that full range.
   */
  private static int normToBipolarParamVolume(float norm) {
    if (norm <= 0f) return Integer.MIN_VALUE;
    return (int) Math.round((double) clamp01(norm) * 4294967295.0 - 2147483648.0);
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
    var s = sound.arpSettings;
    if (arp == null || !arp.active()) {
      s.mode = org.chuck.deluge.firmware2.Arpeggiator.ArpMode.OFF;
      return;
    }
    s.mode = stringToArpMode(arp.mode());
    s.octaveMode = stringToArpOctaveMode(arp.octaveMode());
    s.numOctaves = Math.max(1, arp.octaves());
    s.numStepRepeats = Math.max(1, arp.stepRepeat());
    s.gate = (int) (Math.max(0f, Math.min(1f, arp.gate())) * 2147483647.0);
    // Rhythm pattern: fw2 settings.rhythm is the raw uint32 menu value (the C reads it back with
    // computeCurrentValueForUnsignedMenuItem, arpeggiator.cpp:762). XML may carry either the small
    // 0..50 index or the raw stored value — normalize to an index, then scale
    // (value_scaling.cpp:60-62).
    int rhythmIdx = arp.rhythmIndex();
    if (rhythmIdx > org.chuck.deluge.firmware2.Functions.K_MAX_MENU_VALUE || rhythmIdx < 0) {
      rhythmIdx =
          org.chuck.deluge.firmware2.Functions.computeCurrentValueForUnsignedMenuItem(rhythmIdx);
    }
    rhythmIdx =
        Math.max(0, Math.min(org.chuck.deluge.firmware2.Functions.K_MAX_MENU_VALUE, rhythmIdx));
    s.rhythm = org.chuck.deluge.firmware2.Functions.computeFinalValueForUnsignedMenuItem(rhythmIdx);
    // syncLevel here is a note-division denominator (1=whole, 4=quarter, 16=16th); default 16th.
    sound.arpDivision = (arp.syncLevel() > 0) ? arp.syncLevel() : 16;
    // Free-rate multiplier on the BPM-derived arp clock (PureFirmwareEngine divides the step
    // length by it). 1.0 = nominal.
    sound.arpRateMultiplier = (arp.rate() > 0.01f) ? arp.rate() : 1.0f;

    // ── Full Settings mapping (previously only mode/octaves/repeat/gate/rhythm were wired, so
    // the ARP tab's note-mode/seq-length/spread/probability/ratchet/chord controls were dead) ──
    s.noteMode = stringToArpNoteMode(arp.noteMode());
    s.sequenceLength = Math.max(0, arp.seqLength());
    s.syncType =
        org.chuck.deluge.firmware2.Arpeggiator.SyncType.values()[
            Math.max(0, arp.syncType())
                % org.chuck.deluge.firmware2.Arpeggiator.SyncType.values().length];
    s.mpeVelocity =
        (arp.mpeVelocity() == 1)
            ? org.chuck.deluge.firmware2.Arpeggiator.ArpMpeModSource.AFTERTOUCH
            : org.chuck.deluge.firmware2.Arpeggiator.ArpMpeModSource.OFF;
    // Probabilities + spreads + ratchet are raw uint32 menu values in fw2 (value_scaling.cpp:18 —
    // user 0..50 × 85899345); the model carries them as 0..1 floats.
    s.noteProbability =
        normToRawMenuValue(arp.noteProbability() <= 0f ? 1f : arp.noteProbability());
    s.bassProbability = normToRawMenuValue(arp.bassProbability());
    s.swapProbability = normToRawMenuValue(arp.swapProbability());
    s.glideProbability = normToRawMenuValue(arp.glideProbability());
    s.reverseProbability = normToRawMenuValue(arp.reverseProbability());
    s.chordProbability = normToRawMenuValue(arp.chordProbability());
    s.ratchetProbability = normToRawMenuValue(arp.ratchetProbability());
    s.spreadOctave = normToRawMenuValue(arp.octaveSpread());
    s.spreadGate = normToRawMenuValue(arp.gateSpread());
    s.spreadVelocity = normToRawMenuValue(arp.velSpread());
    s.ratchetAmount = normToRawMenuValue(arp.ratchetAmount() / 4.0f); // panel range 0-4
    s.chordPolyphony = Math.max(0, Math.min(8, arp.chordPolyphony()));
  }

  /**
   * Inverse of the firmware LFO-rate curve: finds the raw knob whose exp-curved phase increment
   * (getExp(121739, combineExp(knob, 2^30)) — see the LFO mapping in mapModelToSound) matches the
   * requested rate in Hz. Monotone in the knob, so a plain binary search converges exactly; used by
   * the LFO tab so rate edits in Hz drive the same knob path the XML parser feeds.
   */
  public static int lfoRateKnobFromHz(double hz) {
    long targetInc = (long) (Math.max(0.01, Math.min(40.0, hz)) * 4294967296.0 / 44100.0);
    long lo = Integer.MIN_VALUE;
    long hi = Integer.MAX_VALUE;
    while (lo < hi) {
      long mid = (lo + hi) >> 1;
      long inc =
          FirmwareUtils.getExp(121739, FirmwareUtils.patchCombineExpStep(0, (int) mid, 1073741824))
              & 0xFFFFFFFFL;
      if (inc < targetInc) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return (int) lo;
  }

  /** 0..1 float → raw uint32 unpatched-menu value (user 0..50 × 85899345, value_scaling.cpp:18). */
  private static int normToRawMenuValue(float norm) {
    int user = Math.round(Math.max(0f, Math.min(1f, norm)) * 50f);
    return org.chuck.deluge.firmware2.Functions.computeFinalValueForUnsignedMenuItem(user);
  }

  private static org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode stringToArpNoteMode(String m) {
    if (m == null) return org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.UP;
    return switch (m.trim().toUpperCase()) {
      case "DOWN" -> org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.DOWN;
      case "UPDN", "UP_DOWN", "UPDOWN" ->
          org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.UP_DOWN;
      case "RAND", "RANDOM" -> org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.RANDOM;
      case "WLK1", "WALK1" -> org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.WALK1;
      case "WLK2", "WALK2" -> org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.WALK2;
      case "WLK3", "WALK3" -> org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.WALK3;
      case "PLAY", "AS_PLAYED" -> org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.AS_PLAYED;
      case "PATT", "PATTERN" -> org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.PATTERN;
      default -> org.chuck.deluge.firmware2.Arpeggiator.ArpNoteMode.UP;
    };
  }

  private static org.chuck.deluge.firmware2.Arpeggiator.ArpMode stringToArpMode(String m) {
    if (m == null) return org.chuck.deluge.firmware2.Arpeggiator.ArpMode.ARP;
    var t = m.trim().toUpperCase();
    return "OFF".equals(t)
        ? org.chuck.deluge.firmware2.Arpeggiator.ArpMode.OFF
        : org.chuck.deluge.firmware2.Arpeggiator.ArpMode.ARP;
  }

  private static org.chuck.deluge.firmware2.Arpeggiator.ArpOctaveMode stringToArpOctaveMode(
      String m) {
    if (m == null) return org.chuck.deluge.firmware2.Arpeggiator.ArpOctaveMode.UP;
    return switch (m.trim().toUpperCase()) {
      case "DOWN" -> org.chuck.deluge.firmware2.Arpeggiator.ArpOctaveMode.DOWN;
      case "UP_DOWN", "UPDN", "UPDOWN", "ALT" ->
          org.chuck.deluge.firmware2.Arpeggiator.ArpOctaveMode.UP_DOWN;
      default -> org.chuck.deluge.firmware2.Arpeggiator.ArpOctaveMode.UP;
    };
  }

  private static org.chuck.deluge.firmware.model.PolyphonyMode toPolyphonyMode(
      SynthTrackModel.PolyphonyMode m) {
    if (m == null) return org.chuck.deluge.firmware.model.PolyphonyMode.POLY;
    try {
      return org.chuck.deluge.firmware.model.PolyphonyMode.valueOf(m.name());
    } catch (IllegalArgumentException e) {
      return org.chuck.deluge.firmware.model.PolyphonyMode.POLY;
    }
  }

  public static InstrumentClip createKitClip(KitTrackModel model) {
    InstrumentClip clip = new InstrumentClip();
    FirmwareKit kit = new FirmwareKit();
    clip.sound = kit;
    clip.loopLength = 16 * 24;

    File sdRoot = PreferencesManager.getLibraryDir();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      int drumIdx = 0;
      for (Drum d : model.getDrums()) {
        if (drumIdx >= kit.drumSounds.size()) break;
        if (d instanceof SoundDrum sd) {
          final FirmwareSound drumSound = kit.drumSounds.get(drumIdx);
          mapDrumToSound(sd, drumSound, drumIdx, model);
          final SoundDrum finalSd = sd;
          executor.submit(() -> loadDrumResources(finalSd, drumSound));
        }
        drumIdx++;
      }
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

  private static File resolveSample(String path, File sdRoot) {
    File f = new File(path);
    if (f.exists()) return f;
    if (sdRoot != null) {
      f = new File(sdRoot, path);
      if (f.exists()) return f;
    }
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

  public static void applyModelToLiveSound(KitTrackModel model, FirmwareKit kit) {
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      int drumIdx = 0;
      for (Drum d : model.getDrums()) {
        if (drumIdx >= kit.drumSounds.size()) break;
        if (d instanceof SoundDrum sd) {
          final FirmwareSound drumSound = kit.drumSounds.get(drumIdx);
          synchronized (drumSound) {
            mapDrumToSound(sd, drumSound, drumIdx, model);
          }
          final SoundDrum finalSd = sd;
          executor.submit(() -> loadDrumResources(finalSd, drumSound));
        }
        drumIdx++;
      }
    }
  }

  public static void mapDrumToSound(
      SoundDrum sd, FirmwareSound drumSound, int drumIdx, KitTrackModel model) {
    drumSound.paramManager.automatedParams.clear();
    drumSound.paramManager.getPatchCableSet().destinations.clear();

    drumSound.isDrum = true;
    drumSound.oscTypes[0] = OscType.SAMPLE;
    drumSound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = org.chuck.deluge.firmware.util.Q31.ONE;
    drumSound.paramNeutralValues[Param.LOCAL_VOLUME] = org.chuck.deluge.firmware.util.Q31.ONE;
    drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    drumSound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    drumSound.osc1RetriggerPhase = sd.getOsc1RetrigPhase();
    drumSound.osc2RetriggerPhase = sd.getOsc2RetrigPhase();
    drumSound.mod1RetrigPhase = sd.getMod1RetrigPhase();
    drumSound.mod2RetrigPhase = sd.getMod2RetrigPhase();
    drumSound.sidechainSend = (int) (sd.getSidechainSend() * 2147483647.0);

    // Per-drum modulation FX
    drumSound.modFXType = stringToModFXType(sd.getModFxType());
    drumSound.modFXRateIncrement = (int) ((double) sd.getModFxRate() * 4294967296.0 / 44100.0);
    drumSound.modFXDepth = (int) (clamp01(sd.getModFxDepth()) * 2147483647.0);
    drumSound.modFXOffset = (int) (clamp01(sd.getModFxOffset()) * 2147483647.0);
    drumSound.modFXFeedback = (int) (clamp01(sd.getModFxFeedback()) * 2147483647.0);
    drumSound.bitcrushParam = normToBipolarParam(sd.getBitCrush());
    drumSound.srrParam = normToBipolarParam(sd.getSampleRateReduction());
    drumSound.eqBassParam = dbToBipolarParam(sd.getEqBass());
    drumSound.eqTrebleParam = dbToBipolarParam(sd.getEqTreble());

    // Sample playback settings
    drumSound.sampleSettings[0].reverse = sd.isReverse();
    if (sd.getStartSamplePos() >= 0) {
      drumSound.sampleSettings[0].startPoint = sd.getStartSamplePos();
    }
    if (sd.getEndSamplePos() >= 0) {
      drumSound.sampleSettings[0].endPoint = sd.getEndSamplePos();
    }
    if (sd.getStartLoopPos() >= 0) {
      drumSound.sampleSettings[0].loopStart = sd.getStartLoopPos();
    }
    if (sd.getEndLoopPos() >= 0) {
      drumSound.sampleSettings[0].loopEnd = sd.getEndLoopPos();
    }
    drumSound.sampleSettings[0].transpose = (int) sd.getPitchSemitones();

    // Second source
    if (sd.getOsc2Type() != null && !sd.getOsc2Type().equalsIgnoreCase("NONE")) {
      drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] =
          org.chuck.deluge.firmware.util.Q31.ONE;
      drumSound.oscTypes[1] = stringToOscType(sd.getOsc2Type());
    } else {
      drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
      drumSound.oscTypes[1] = null;
    }

    // Envelope
    var env = sd.getAdsr();
    drumSound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK] =
        org.chuck.deluge.firmware2.Functions.getParamFromUserValue(
            Param.LOCAL_ENV_0_ATTACK, (int) (env.attack() * 50));
    drumSound.paramNeutralValues[Param.LOCAL_ENV_0_DECAY] =
        org.chuck.deluge.firmware2.Functions.getParamFromUserValue(
            Param.LOCAL_ENV_0_DECAY, (int) (env.decay() * 50));
    drumSound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] =
        org.chuck.deluge.firmware2.Functions.getParamFromUserValue(
            Param.LOCAL_ENV_0_SUSTAIN, (int) (env.sustain() * 50));
    drumSound.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE] =
        org.chuck.deluge.firmware2.Functions.getParamFromUserValue(
            Param.LOCAL_ENV_0_RELEASE, (int) (env.release() * 50));

    // Filter
    drumSound.paramNeutralValues[Param.LOCAL_LPF_FREQ] =
        (int) (sd.getLpfFreq() / 22050.0f * 2147483647.0f);
    drumSound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] =
        (int) (sd.getLpfRes() * 2147483647.0f);

    // Polyphony / unison
    drumSound.polyphonic = toPolyphonyMode(sd.getPolyphony());
    drumSound.numUnison = sd.getUnisonNum();
    drumSound.unisonDetune = (int) sd.getUnisonDetune();
    drumSound.unisonStereoSpread = (int) sd.getUnisonStereoSpread();

    // Map step automation
    if (model != null && !model.getClips().isEmpty()) {
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
  }

  public static void loadDrumResources(SoundDrum sd, FirmwareSound drumSound) {
    File sdRoot = PreferencesManager.getLibraryDir();
    String path = sd.getSamplePath();
    boolean wantsFile = path != null && !path.isEmpty();
    if (!wantsFile) {
      synchronized (drumSound) {
        drumSound.loadedOscPath[0] = null;
      }
      return;
    }
    String key = "SAMPLE:" + path;
    synchronized (drumSound) {
      if (key.equals(drumSound.loadedOscPath[0])) {
        return; // already loaded
      }
    }
    File f = resolveSample(path, sdRoot);
    if (f == null || !f.exists()) {
      return;
    }
    try {
      Sample smp = AudioFileReader.readSample(f.getAbsolutePath());
      if (smp != null) {
        var fw2Smp = org.chuck.deluge.firmware2.Sample.fromFirmwareSample(smp);
        synchronized (drumSound) {
          drumSound.samples[0] = smp;
          drumSound.fw2SampleCache[0] = fw2Smp;
          drumSound.loadedOscPath[0] = key;
        }
        System.out.println("[FirmwareFactory] Loaded drum sample: " + f.getName());
      }
    } catch (IOException e) {
      System.err.println("[FirmwareFactory] Failed to load drum sample: " + path);
    }
  }
}
