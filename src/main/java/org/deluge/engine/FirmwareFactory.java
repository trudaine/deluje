package org.deluge.engine;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.WaveTable;
import org.deluge.firmware2.WaveTableReader;
import org.deluge.model.AudioTrackModel;
import org.deluge.model.ClipModel;
import org.deluge.model.ClipType;
import org.deluge.model.Drum;
import org.deluge.model.EnvelopeModel;
import org.deluge.model.KeyZone;
import org.deluge.model.KitTrackModel;
import org.deluge.model.LfoModel;
import org.deluge.model.MidiTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SoundDrum;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.modulation.patch.PatchCable;
import org.deluge.modulation.patch.PatchSource;
import org.deluge.playback.Sample;
import org.deluge.project.PreferencesManager;
import org.deluge.storage.audio.AudioFileReader;

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
    // C sourceToString (functions.cpp:268-280): lfo1=GLOBAL_1, lfo2=LOCAL_1, lfo3=GLOBAL_2,
    // lfo4=LOCAL_2.
    SOURCE_MAP.put("LFO1", PatchSource.LFO_GLOBAL_1);
    SOURCE_MAP.put("LFO2", PatchSource.LFO_LOCAL_1);
    SOURCE_MAP.put("LFO3", PatchSource.LFO_GLOBAL_2);
    SOURCE_MAP.put("LFO4", PatchSource.LFO_LOCAL_2);
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

  public static ProjectModel createSong(ProjectModel model) {
    model.calculateNoteFrequencies();

    System.out.println(
        "[FirmwareFactory] Compiling DSP Sound Engines on unified ProjectModel. Tracks: "
            + model.getTracks().size());

    for (TrackModel track : model.getTracks()) {
      if (track.getClips().isEmpty()) {
        track.addClip(new ClipModel("Default Clip", 8, 16));
      }
      int trackIndex = model.getTracks().indexOf(track);
      org.deluge.firmware2.GlobalEffectable sound = null;

      if (track instanceof SynthTrackModel synthTrack) {
        FirmwareSound synth = new FirmwareSound();
        synth.fw2Sound.tuning = model; // project is the tuning provider
        synth.inputTickMagnitude = model.getInputTickMagnitude(); // for file→internal sync levels
        mapModelToSound(synthTrack, synth);
        loadOscResources(synthTrack, synth);
        sound = synth;
      } else if (track instanceof KitTrackModel kitTrack) {
        sound = createKitSound(kitTrack, model);
      } else if (track instanceof MidiTrackModel midiTrack) {
        sound = createMidiSound(midiTrack);
      } else if (track instanceof AudioTrackModel audioTrack) {
        sound = createAudioSound(audioTrack, trackIndex, model);
      }

      if (sound != null) {
        for (ClipModel clip : track.getClips()) {
          clip.setSound(sound);
          clip.setType(ClipType.INSTRUMENT);
          clip.setLoopLength(clip.getStepCount() * (clip.isTripletMode() ? 32 : 24));
          clip.syncNoteRowsFromGrid();
        }
      }
    }
    return model;
  }

  public static FirmwareKit createKitSound(KitTrackModel model, ProjectModel project) {
    FirmwareKit kit = new FirmwareKit();
    kit.clippingAmount = model.getClippingAmount();
    for (FirmwareSound drumSound : kit.drumSounds) {
      drumSound.fw2Sound.tuning = project;
    }

    // Synchronize kit-level parameters from the first clip
    if (!model.getClips().isEmpty()) {
      ClipModel firstClip = model.getClips().get(0);
      Float vol = firstClip.getKitParams().get("volume");
      if (vol != null) {
        kit.kitVolume = normToBipolarParamVolume(vol);
      }
      Float rev = firstClip.getKitParams().get("reverbAmount");
      if (rev != null) {
        kit.reverbSendKnob = normToBipolarParamVolume(rev);
      }
    }

    File sdRoot = PreferencesManager.getLibraryDir();
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      int drumIdx = 0;
      for (Drum d : model.getDrums()) {
        if (drumIdx >= kit.drumSounds.size()) break;
        if (d instanceof SoundDrum sd) {
          final FirmwareSound drumSound = kit.drumSounds.get(drumIdx);
          mapDrumToSound(sd, drumSound, drumIdx, model);
          if (!model.getClips().isEmpty()) {
            ClipModel firstClip = model.getClips().get(0);
            org.deluge.model.NoteRowModel rowModel = null;
            for (org.deluge.model.NoteRowModel r : firstClip.getNoteRowsMap().values()) {
              if (r.getPitch() == drumIdx) {
                rowModel = r;
                break;
              }
            }
            if (rowModel != null) {
              applyRowOverrides(rowModel, drumSound);
            }
          }
          final SoundDrum finalSd = sd;
          executor.submit(() -> loadDrumResources(finalSd, drumSound));
        }
        drumIdx++;
      }
    }
    return kit;
  }

  public static FirmwareMidiInstrument createMidiSound(MidiTrackModel model) {
    FirmwareMidiInstrument midi = new FirmwareMidiInstrument(model.getMidiChannel(), model.isMpe());
    midi.setMpeZone(model.getMpeZone());
    return midi;
  }

  public static org.deluge.firmware2.GlobalEffectable createAudioSound(
      AudioTrackModel model, int trackIndex, ProjectModel project) {
    // Phase 1 (docs/AUDIO_TRACK_PORT_PLAN.md): an AudioOutput that streams the first audio clip's
    // sample through the track FX chain. Pitch/time-stretch and transport sync are later phases.
    org.deluge.firmware2.AudioOutput out = new org.deluge.firmware2.AudioOutput();
    out.clippingAmount = model.getClippingAmount();
    if (model.getAudioClips() != null && !model.getAudioClips().isEmpty()) {
      AudioTrackModel.AudioClip clip = model.getAudioClips().get(0);
      String path = clip.getFilePath();
      if (path != null && !path.isEmpty()) {
        File f = new File(path);
        if (!f.exists()) {
          f = resolveSample(path, PreferencesManager.getLibraryDir());
        }
        if (f != null && f.exists()) {
          try {
            var smp = AudioFileReader.readSample(f.getAbsolutePath());
            if (smp != null) {
              out.setReversed(clip.isReversed());
              out.setClip(org.deluge.firmware2.Sample.fromFirmwareSample(smp), true);
              // Phase 2: playback rate / pitch (coupled resample vs independent time-stretch).
              out.setPlayback(model.getPlayRate(), clip.isPitchSpeedIndependent());
              // Phase 3b: loop at the clip's musical length (ticks → samples at the song tempo, 96
              // PPQN), so the clip loops in time with the song rather than at the raw sample end.
              float bpm = project.getBpm();
              if (bpm > 0 && clip.getLength() > 0) {
                double samplesPerTick = 44100.0 / (bpm / 60.0 * 96.0);
                out.setLoopLengthSamples((int) Math.round(clip.getLength() * samplesPerTick));
              }
              // Phase 3a: the engine starts/stops it on the transport play edge (not at load time).
              System.out.println(
                  "[FirmwareFactory] Audio track '"
                      + model.getName()
                      + "' streaming clip: "
                      + f.getName());
            }
          } catch (Exception e) {
            System.err.println("[FirmwareFactory] Audio clip load failed: " + path + " — " + e);
          }
        }
      }
    }
    // Phase 3b part 2: gate playback to this track's arrangement placements (every ArrangerClip for
    // the track becomes a [startTicks, startTicks+durationTicks) window). No placement → session
    // behaviour (plays whenever the transport plays).
    if (project.getArrangerTimeline() != null) {
      for (org.deluge.model.ArrangerClip ac : project.getArrangerTimeline()) {
        if (ac.trackIndex() == trackIndex) {
          out.addTimelineRange(ac.startTicks(), (long) ac.startTicks() + ac.durationTicks());
        }
      }
    }
    return out;
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
              OscType type = sound.oscTypes[finalS];
              var zones =
                  (finalS == 0)
                      ? model.getKeyZones().getOsc1Zones()
                      : model.getKeyZones().getOsc2Zones();

              if (!zones.isEmpty() && type == OscType.SAMPLE) {
                // MULTISAMPLE LOADING PATH
                java.util.List<org.deluge.firmware2.Sound.CompiledKeyZone> compiledZones =
                    new java.util.ArrayList<>();
                for (KeyZone kz : zones) {
                  if (kz.samplePath == null || kz.samplePath.isEmpty()) {
                    continue;
                  }
                  File f = resolveSample(kz.samplePath, sdRoot);
                  if (f == null || !f.exists()) {
                    System.err.println(
                        "[FirmwareFactory] Warning: keyzone sample not found: " + kz.samplePath);
                    continue;
                  }
                  try {
                    Sample smp = AudioFileReader.readSample(f.getAbsolutePath());
                    if (smp != null) {
                      var fw2Smp = org.deluge.firmware2.Sample.fromFirmwareSample(smp);
                      org.deluge.firmware2.Sound.CompiledKeyZone ckz =
                          new org.deluge.firmware2.Sound.CompiledKeyZone();
                      ckz.sample = fw2Smp;
                      ckz.minPitch = kz.minPitch;
                      ckz.maxPitch = kz.maxPitch;
                      ckz.minVelocity = kz.minVelocity;
                      ckz.maxVelocity = kz.maxVelocity;
                      ckz.startSamplePos = kz.startSamplePos;
                      ckz.endSamplePos =
                          kz.endSamplePos == -1 ? (int) fw2Smp.lengthInSamples : kz.endSamplePos;
                      ckz.startLoopPos = kz.startLoopPos;
                      ckz.endLoopPos = kz.endLoopPos;
                      ckz.looping = kz.looping;
                      ckz.transpose = kz.transpose;
                      ckz.cents = kz.cents;
                      compiledZones.add(ckz);
                    }
                  } catch (IOException e) {
                    System.err.println(
                        "[FirmwareFactory] Failed to load keyzone sample: " + kz.samplePath);
                  }
                }
                synchronized (sound) {
                  sound.sourceZones[finalS].clear();
                  sound.sourceZones[finalS].addAll(compiledZones);
                  sound.loadedOscPath[finalS] = "MULTISAMPLE:" + zones.size();
                }
                System.out.println(
                    "[FirmwareFactory] Loaded multisample oscillator "
                        + finalS
                        + " with "
                        + compiledZones.size()
                        + " zones.");
                return;
              }

              // SINGLE SAMPLE / WAVETABLE LOADING PATH (FALLBACK)
              String path = (finalS == 0) ? model.getOsc1SamplePath() : model.getOsc2SamplePath();
              boolean wantsFile =
                  (type == OscType.SAMPLE || type == OscType.WAVETABLE)
                      && path != null
                      && !path.isEmpty();
              if (!wantsFile) {
                synchronized (sound) {
                  sound.loadedOscPath[finalS] = null;
                  sound.sourceZones[finalS].clear();
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
                    var fw2Smp = org.deluge.firmware2.Sample.fromFirmwareSample(smp);
                    synchronized (sound) {
                      sound.samples[finalS] = smp;
                      sound.fw2SampleCache[finalS] = fw2Smp;
                      sound.loadedOscPath[finalS] = key;
                      sound.sourceZones[finalS].clear();
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
                    sound.sourceZones[finalS].clear();
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
    // ── Live Automation Recording ──
    boolean isPlaying = false;
    boolean isRecording = false;
    int currentStep = -1;
    try {
      var bridge = org.deluge.hid.BridgeHolder.getBridge();
      if (bridge != null) {
        Object playState = bridge.getGlobalInt(org.deluge.BridgeContract.G_PLAY);
        isPlaying = (playState instanceof Long && ((Long) playState) == 1L);
        isRecording = org.deluge.ui.SwingGridPanel.isLiveRecordModeActive;
        currentStep = (int) bridge.getGlobalInt(org.deluge.BridgeContract.G_CURRENT_STEP);
      }
    } catch (Exception ignored) {
    }

    if (isPlaying && isRecording && currentStep >= 0 && !model.getClips().isEmpty()) {
      int activeIdx = model.getActiveClipIndex();
      if (activeIdx >= 0 && activeIdx < model.getClips().size()) {
        org.deluge.model.ClipModel clip = model.getClips().get(activeIdx);
        int stepTicks = clip.isTripletMode() ? 32 : 24;

        synchronized (sound) {
          // 1. LPF Frequency
          int newLpfQ31 = cutoffKnobFromHz(model.getLpfFreq());
          if (newLpfQ31 != sound.paramNeutralValues[Param.LOCAL_LPF_FREQ]) {
            float normVal = model.getLpfFreq() / 20000.0f;
            clip.setAutomation("lpfFrequency", currentStep, normVal);
            int q31Val = (int) (normVal * 2147483647.0);
            sound.paramManager.recordParamValue(
                Param.LOCAL_LPF_FREQ, q31Val, currentStep * stepTicks);
          }

          // 2. LPF Resonance
          int newResQ31 = normToLinearParamKnob(model.getLpfRes());
          if (newResQ31 != sound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE]) {
            clip.setAutomation("lpfResonance", currentStep, model.getLpfRes());
            int q31Val = (int) (model.getLpfRes() * 2147483647.0);
            sound.paramManager.recordParamValue(
                Param.LOCAL_LPF_RESONANCE, q31Val, currentStep * stepTicks);
          }

          // 3. HPF Frequency
          int newHpfQ31 = cutoffKnobFromHz(model.getHpfFreq());
          if (newHpfQ31 != sound.paramNeutralValues[Param.LOCAL_HPF_FREQ]) {
            float normVal = model.getHpfFreq() / 20000.0f;
            clip.setAutomation("hpfFrequency", currentStep, normVal);
            int q31Val = (int) (normVal * 2147483647.0);
            sound.paramManager.recordParamValue(
                Param.LOCAL_HPF_FREQ, q31Val, currentStep * stepTicks);
          }

          // 4. Volume
          int newVolQ31 = normToBipolarParamVolume(model.getVolume());
          if (newVolQ31 != sound.paramNeutralValues[Param.LOCAL_VOLUME]) {
            float normVal = Math.max(0.0f, Math.min(1.0f, model.getVolume() / 1.5f));
            clip.setAutomation("volume", currentStep, normVal);
            int q31Val = (int) (normVal * 2147483647.0);
            sound.paramManager.recordParamValue(
                Param.LOCAL_VOLUME, q31Val, currentStep * stepTicks);
          }

          // 5. Pan
          int newPanQ31 = (int) (Math.max(-1.0, Math.min(1.0, model.getPan())) * 1073741824.0);
          if (newPanQ31 != sound.paramNeutralValues[Param.LOCAL_PAN]) {
            float normVal = (model.getPan() + 1.0f) / 2.0f;
            clip.setAutomation("pan", currentStep, normVal);
            int q31Val = (int) (normVal * 2147483647.0);
            sound.paramManager.recordParamValue(Param.LOCAL_PAN, q31Val, currentStep * stepTicks);
          }
        }
      }
    }

    synchronized (sound) {
      mapModelToSound(model, sound);
    }
    loadOscResources(model, sound);
  }

  private static void mapModelToSound(SynthTrackModel model, FirmwareSound sound) {
    // Initialize paramKnobs with the static defaults first, so any unset params have their
    // defaults.
    System.arraycopy(sound.paramNeutralValues, 0, sound.paramKnobs, 0, Param.kNumParams);

    // Per-sound voice cap (C: sound.h:116, default 8). The model clamps to [1,16] and reads the
    // XML "maxVoices" attribute; propagate it so dense playing steals voices instead of stacking
    // 64.
    sound.fw2Sound.maxPolyphony = model.getMaxVoiceCount();
    sound.fw2Sound.voicePriority =
        model.getVoicePriority(); // voice-stealing priority (was stuck at default)
    // Idempotent: clear the cable set so live re-applies don't duplicate cables (they are
    // re-added from the model at the end of this method).
    sound.paramManager.getPatchCableSet().destinations.clear();
    sound.paramManager.automatedParams.clear();
    sound.oscTypes[0] = stringToOscType(model.getOsc1Type());
    // C SampleControls.interpolationMode (sample_controls.cpp:30-33): the per-osc
    // "linearInterpolation" preset flag selects the 2-tap lo-fi path.
    sound.fw2Sound.sourceLinearInterpolation[0] = model.isOsc1LinearInterpolation();
    sound.fw2Sound.sourceLinearInterpolation[1] = model.isOsc2LinearInterpolation();
    sound.oscTypes[1] = stringToOscType(model.getOsc2Type());

    // Map Synth Mode (0=Subtractive, 1=FM, 2=RingMod)
    int modeVal = model.getSynthMode();
    if (modeVal == 1) {
      sound.setSynthMode(FirmwareSound.SynthMode.FM);
    } else if (modeVal == 2) {
      sound.setSynthMode(FirmwareSound.SynthMode.RINGMOD);
    } else {
      sound.setSynthMode(FirmwareSound.SynthMode.SUBTRACTIVE);
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
    if (sound.getSynthMode() == FirmwareSound.SynthMode.FM) {
      // Store the raw knob values; the live amplitude (base + patch cables, e.g. envelope2 ->
      // modulator volume) is computed each block in FirmwareVoice via the volume curve.
      sound.fmModulatorAmountBase[0] = model.getModulator1AmountQ31();
      sound.fmModulatorAmountBase[1] = model.getModulator2AmountQ31();
      sound.fmModulatorFeedback[0] =
          finalLinearParam(model.getModulator1FeedbackQ31(), 5931642, 1073741824);
      sound.fmModulatorFeedback[1] =
          finalLinearParam(model.getModulator2FeedbackQ31(), 5931642, 1073741824);
      sound.fmCarrierFeedback[0] =
          finalLinearParam(model.getCarrier1FeedbackQ31(), 5931642, 1073741824);
      sound.fmCarrierFeedback[1] =
          finalLinearParam(model.getCarrier2FeedbackQ31(), 5931642, 1073741824);
      sound.fmModulator1ToModulator0 = model.isModulator1ToModulator0();

      // The C reads these XML values straight into the patched-param KNOBS (sound.cpp:520-548
      // readParam of modulator1Amount/modulator2Amount/feedbacks; default INT_MIN = off from
      // initParams, sound.cpp:159-162). Without this the fw2 modulator volume stays INT_MIN →
      // paramFinal 0 → no modulation → FM patches play a plain sine.
      sound.paramKnobs[Param.LOCAL_MODULATOR_0_VOLUME] = model.getModulator1AmountQ31();
      sound.paramKnobs[Param.LOCAL_MODULATOR_1_VOLUME] = model.getModulator2AmountQ31();
      sound.paramKnobs[Param.LOCAL_MODULATOR_0_FEEDBACK] = model.getModulator1FeedbackQ31();
      sound.paramKnobs[Param.LOCAL_MODULATOR_1_FEEDBACK] = model.getModulator2FeedbackQ31();
      sound.paramKnobs[Param.LOCAL_CARRIER_0_FEEDBACK] = model.getCarrier1FeedbackQ31();
      sound.paramKnobs[Param.LOCAL_CARRIER_1_FEEDBACK] = model.getCarrier2FeedbackQ31();
      // Modulator chaining
      sound.fmModulator1ToModulator0 = model.isModulator1ToModulator0();
    }

    // Oscillator pulse/phase width (C LOCAL_OSC_A/B_PHASE_WIDTH; Voice.java:922 → renderPulseWave).
    // Verified end-to-end: duty tracks the value (SquarePwmRenderTest). Apply only when specified.
    if (model.getOsc1PhaseWidthQ31() != Integer.MIN_VALUE) {
      sound.paramKnobs[Param.LOCAL_OSC_A_PHASE_WIDTH] = model.getOsc1PhaseWidthQ31();
    }
    if (model.getOsc2PhaseWidthQ31() != Integer.MIN_VALUE) {
      sound.paramKnobs[Param.LOCAL_OSC_B_PHASE_WIDTH] = model.getOsc2PhaseWidthQ31();
    }

    // Overall voice pitch adjust (C LOCAL_PITCH_ADJUST; Voice.java:412). Apply only when specified.
    // NOTE: per-osc oscA/BPitchAdjust (LOCAL_OSC_A/B_PITCH_ADJUST) are NOT wired — verified via
    // PitchAdjustParamTest that setting them through this path does not reach the single-osc render
    // path, so they remain KNOWN_GAPS pending an engine-path fix (don't claim a false fix).
    if (model.getPitchAdjustQ31() != Integer.MIN_VALUE) {
      sound.paramKnobs[Param.LOCAL_PITCH_ADJUST] = model.getPitchAdjustQ31();
    }

    // Volume/Pan
    sound.paramKnobs[Param.LOCAL_VOLUME] = normToBipolarParamVolume(model.getVolume());
    // LOCAL_PAN is BIPOLAR (0 = centre, ±2^30 = hard L/R), matching the firmware shouldDoPanning
    // input.
    sound.paramKnobs[Param.LOCAL_PAN] =
        (int) (Math.max(-1.0, Math.min(1.0, model.getPan())) * 1073741824.0);

    // Oscillator & Noise Volumes. In FM mode these are the carrier amplitudes (the modulator depth
    // is carried separately in sound.fmModulatorAmount, no longer smuggled through OSC_B_VOLUME).
    sound.paramKnobs[Param.LOCAL_OSC_A_VOLUME] = normToBipolarParamVolume(model.getOscAVolume());
    sound.paramKnobs[Param.LOCAL_OSC_B_VOLUME] = normToBipolarParamVolume(model.getOscBVolume());
    // Osc 2 "NONE"/off must silence osc B. The C has no NONE osc type — osc 2 is turned off by its
    // volume param being MIN_VALUE (isSourceActiveCurrently). stringToOscType maps "NONE"→SINE, so
    // without this a phantom SINE renders (audible whenever oscs differ in pitch).
    String osc2t = model.getOsc2Type();
    if (osc2t == null
        || osc2t.isBlank()
        || osc2t.equalsIgnoreCase("none")
        || osc2t.equalsIgnoreCase("off")) {
      sound.paramKnobs[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    }
    sound.paramKnobs[Param.LOCAL_NOISE_VOLUME] = normToBipolarParamVolume(model.getNoiseVol());
    if (model.getOsc1PitchAdjustQ31() != Integer.MIN_VALUE) {
      sound.paramKnobs[Param.LOCAL_OSC_A_PITCH_ADJUST] = model.getOsc1PitchAdjustQ31();
    } else {
      sound.paramKnobs[Param.LOCAL_OSC_A_PITCH_ADJUST] = 0;
    }
    // oscBPitch (the automatable fine param, raw-Q31 path) only; coarse osc-2 transpose + cents are
    // applied faithfully via sources[s].transpose / fineTuner below (C voice.cpp:439-442/505) — the
    // old (transpose*100+cents)*178956 routing through this param was mis-scaled and is removed.
    if (model.getOsc2PitchAdjustQ31() != Integer.MIN_VALUE) {
      sound.paramKnobs[Param.LOCAL_OSC_B_PITCH_ADJUST] = model.getOsc2PitchAdjustQ31();
    } else {
      sound.paramKnobs[Param.LOCAL_OSC_B_PITCH_ADJUST] = 0;
    }
    // Sound-level master transpose (C sound.transpose, voice.cpp:419) — applies to all sources.
    sound.fw2Sound.masterTranspose = model.getTranspose();
    // Per-osc coarse transpose (semitones) + cents detune — C sources[s].transpose / fineTuner.
    sound.fw2Sound.sourceTranspose[0] = model.getOsc1Transpose();
    sound.fw2Sound.sourceTranspose[1] = model.getOsc2Transpose();
    sound.fw2Sound.setSourceCents(0, model.getOsc1Cents());
    sound.fw2Sound.setSourceCents(1, model.getOsc2Cents());

    // Filter cutoff: the faithful filter (FirmwareFilter.curveFrequency: instantTan of the q31
    // LPF_FREQ param) expects the firmware's exp-curved param value, NOT a Hz-derived number. The
    // model carries Hz (the parser ran the cutoff knob through hexToHz); recover the original knob
    // value by inverting hexToHz (bijective), then run the firmware param path:
    // getFinalParameterValueExp(neutral, combineCablesExp(knob, paramRange)). LPF: neutral 2000000,
    // range 536870912*1.4; HPF: neutral 2672947, range 1073741824.
    sound.paramKnobs[Param.LOCAL_LPF_FREQ] = cutoffKnobFromHz(model.getLpfFreq());
    sound.paramKnobs[Param.LOCAL_LPF_RESONANCE] = normToLinearParamKnob(model.getLpfRes());
    sound.paramKnobs[Param.LOCAL_LPF_MORPH] = normToLinearParamKnob(model.getLpfMorph());
    sound.paramKnobs[Param.LOCAL_HPF_FREQ] = cutoffKnobFromHz(model.getHpfFreq());
    sound.paramKnobs[Param.LOCAL_HPF_RESONANCE] = normToLinearParamKnob(model.getHpfRes());
    sound.paramKnobs[Param.LOCAL_HPF_MORPH] = normToLinearParamKnob(model.getHpfMorph());

    sound.setLpfMode(model.getFilterMode());
    sound.setHpfMode(model.getHpfMode());
    sound.setFilterRoute(model.getFilterRoute());

    // Copy Custom LFO Waveform
    System.arraycopy(model.getCustomLfoWave(), 0, sound.fw2Sound.customLfoWave, 0, 256);

    // Populating unpatched FX parameter defaults/neutrals in paramKnobs.
    // These will be overlaid with rawParamKnobs overrides (from XML) later in this method.
    sound.paramKnobs[Param.GLOBAL_MOD_FX_RATE] = lfoRateKnobFromHz(model.getModFxRate());
    sound.paramKnobs[Param.GLOBAL_MOD_FX_DEPTH] = normToLinearParamKnob(model.getModFxDepth());
    sound.paramKnobs[Param.UNPATCHED_MOD_FX_OFFSET] = normToLinearParamKnob(model.getModFxOffset());
    sound.paramKnobs[Param.UNPATCHED_MOD_FX_FEEDBACK] =
        normToLinearParamKnob(model.getModFxFeedback());
    sound.paramKnobs[Param.GLOBAL_DELAY_RATE] = lfoRateKnobFromHz(model.getDelaySend());
    sound.paramKnobs[Param.GLOBAL_DELAY_FEEDBACK] = model.getDelayFeedbackQ31();
    sound.paramKnobs[Param.GLOBAL_REVERB_AMOUNT] = normToBipolarParamVolume(model.getReverbSend());
    sound.paramKnobs[Param.UNPATCHED_STUTTER_RATE] =
        normToLinearParamKnob(model.getStutter().getStutterRate());
    sound.paramKnobs[Param.UNPATCHED_SAMPLE_RATE_REDUCTION] =
        normToBipolarParam(model.getSampleRateReduction());
    sound.paramKnobs[Param.UNPATCHED_BITCRUSHING] = normToBipolarParam(model.getBitCrush());
    sound.paramKnobs[Param.UNPATCHED_BASS] = dbToBipolarParam(model.getEqBass());
    sound.paramKnobs[Param.UNPATCHED_TREBLE] = dbToBipolarParam(model.getEqTreble());
    sound.paramKnobs[Param.UNPATCHED_BASS_FREQ] = 0; // default/flat
    sound.paramKnobs[Param.UNPATCHED_TREBLE_FREQ] = 0; // default/flat
    sound.paramKnobs[Param.UNPATCHED_SIDECHAIN_SHAPE] =
        normToLinearParamKnob(model.getCompressorShape());
    sound.paramKnobs[Param.UNPATCHED_SIDECHAIN_VOLUME] = 0; // default/flat

    // Delay static configuration
    sound.delaySyncLevel = model.getDelaySyncLevel();
    sound.delaySyncType = model.getDelaySyncType();
    sound.delayPingPong = model.isDelayPingPong();
    sound.delayAnalog = model.isDelayAnalog();

    // Mod FX static configuration
    sound.fw2Sound.modFXType = stringToModFXType(model.getModFxType());

    // Compressor static configuration
    sound.fw2Sound.compressor.setup(
        (int) (model.getCompressorAttack() * 2147483647.0),
        (int) (model.getCompressorRelease() * 2147483647.0),
        (int) (model.getCompressorThreshold() * 2147483647.0),
        (int) (model.getCompressorRatio() * 2147483647.0),
        (int) (model.getCompressorSidechainHpf() * 2147483647.0),
        (int) (model.getCompressorBlend() * 2147483647.0),
        1.35f);

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
    sound.fw2Sound.oscillatorSync = model.isOscillatorSync();

    // Wave Index parameters (mapped to unipolar Q31)
    int waveIndexQ31 = (int) Math.round((double) clamp01(model.getWaveIndex()) * 2147483647.0);
    sound.paramKnobs[Param.LOCAL_OSC_A_WAVE_INDEX] = waveIndexQ31;
    sound.paramKnobs[Param.LOCAL_OSC_B_WAVE_INDEX] = waveIndexQ31;

    // Wavefolder knob (raw Q31 like the C readParam of "waveFold" → LOCAL_FOLD; INT_MIN = off).
    sound.paramKnobs[Param.LOCAL_FOLD] = model.getWaveFoldQ31();

    // Saturation/clipping amount (C clippingAmount; 0 = off).
    sound.fw2Sound.clippingAmount = model.getClippingAmount();

    // Portamento knob (C UNPATCHED_PORTAMENTO, raw Q31; INT_MIN = off).
    sound.fw2Sound.portamentoKnob = model.getPortamentoQ31();

    // Envelopes
    for (int i = 0; i < 4; i++) {
      EnvelopeModel em = model.getEnv(i);
      int attackInc;
      int decayInc;
      int releaseInc;
      if (model.getRawKnobs().isEnvKnobSet(i)) {
        // Faithful: the envelope rate increments follow the firmware's per-stage curves
        // (getFinalParameterValueExpWithDumbEnvelopeHack): attack via getExp on the negated patched
        // knob; decay/release via the release-rate table. Neutrals 4096 / 70<<9 / 140<<9; attack
        // range 536870912*1.5, decay/release range 2^30.
        attackInc =
            finalEnvRateParam(
                4096,
                org.deluge.firmware2.Functions.patchCombineExpStep(
                    0, model.getRawKnobs().getEnvAttackKnobQ31(i), 805306368),
                0);
        decayInc =
            finalEnvRateParam(
                70 << 9,
                org.deluge.firmware2.Functions.patchCombineExpStep(
                    0, model.getRawKnobs().getEnvDecayKnobQ31(i), 1073741824),
                1);
        releaseInc =
            finalEnvRateParam(
                140 << 9,
                org.deluge.firmware2.Functions.patchCombineExpStep(
                    0, model.getRawKnobs().getEnvReleaseKnobQ31(i), 1073741824),
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
      int sustainKnob;
      int releaseKnob;
      if (model.getRawKnobs().isEnvKnobSet(i)) {
        attackKnob = model.getRawKnobs().getEnvAttackKnobQ31(i);
        decayKnob = model.getRawKnobs().getEnvDecayKnobQ31(i);
        sustainKnob = model.getRawKnobs().getEnvSustainKnobQ31(i);
        releaseKnob = model.getRawKnobs().getEnvReleaseKnobQ31(i);
      } else {
        sustainKnob = normToBipolarParam(em.sustain());
        float normAttack = org.deluge.xml.DelugeHexMapper.normFromEnvTime(em.attack());
        attackKnob = (int) Math.rint(normAttack * 2147483647.0);
        float normDecay = org.deluge.xml.DelugeHexMapper.normFromEnvTime(em.decay());
        decayKnob = (int) Math.rint(normDecay * 2147483647.0);
        float normRelease = org.deluge.xml.DelugeHexMapper.normFromEnvTime(em.release());
        releaseKnob = (int) Math.rint(normRelease * 2147483647.0);
      }

      sound.paramKnobs[Param.LOCAL_ENV_0_ATTACK + i] = attackKnob;
      sound.paramKnobs[Param.LOCAL_ENV_0_DECAY + i] = decayKnob;
      sound.paramKnobs[Param.LOCAL_ENV_0_SUSTAIN + i] = sustainKnob;
      sound.paramKnobs[Param.LOCAL_ENV_0_RELEASE + i] = releaseKnob;
    }

    // C sound.cpp:616-626: numUnison clamped 0..kMaxNumVoicesUnison(8), detune 0..50, spread
    // 0..50 — all USER units (fw2 setupUnisonStereoSpread scales by 42949672 itself; the previous
    // *2^31 here overflowed and fed garbage spread values into the unison pan setup).
    sound.fw2Sound.numUnison = Math.max(0, Math.min(8, model.getUnison().getUnisonNum()));
    sound.fw2Sound.unisonDetune =
        Math.max(0, Math.min(50, (int) model.getUnison().getUnisonDetune()));
    sound.fw2Sound.unisonStereoSpread =
        Math.max(0, Math.min(50, (int) model.getUnison().getUnisonStereoSpread()));

    // Sidechain settings. The preset XML carries the C's RAW rate ints
    // (mod_controllable_audio.cpp:892-896); when the tag is absent, keep the Sidechain
    // constructor defaults (sidechain.cpp:29-30 — attackRateTable[7]*4 / releaseRateTable[28]*8).
    if (model.getSidechainAttackRaw() >= 0) {
      sound.sidechain.attack = model.getSidechainAttackRaw();
    }
    if (model.getSidechainReleaseRaw() >= 0) {
      sound.sidechain.release = model.getSidechainReleaseRaw();
    }
    // File→internal conversion like every sync tag (mod_controllable_audio.cpp:903-906). When
    // the preset carries no <sidechain> tag at all (raw attack unset), the C constructor default
    // applies: syncLevel = 8 - insideWorldTickMagnitude (mod_controllable_audio.cpp:71-82).
    int scSyncFile = Math.max(0, model.getSidechainSyncLevel());
    if (scSyncFile > 0) {
      sound.sidechain.syncLevel =
          org.deluge.xml.DelugeXmlUtil.convertSyncLevelFromFileValueToInternalValue(
              scSyncFile, sound.inputTickMagnitude);
    } else if (model.getSidechainAttackRaw() < 0) {
      sound.sidechain.syncLevel = 8 - sound.inputTickMagnitude;
    } else {
      sound.sidechain.syncLevel = 0;
    }
    sound.sidechain.syncType = Math.max(0, Math.min(model.getSidechainSyncType(), 2));
    sound.paramKnobs[org.deluge.firmware2.Param.UNPATCHED_SIDECHAIN_SHAPE] = 0; // default shape
    try {
      sound.fw2Sound.polyphonic =
          org.deluge.firmware2.Sound.PolyphonyMode.valueOf(model.getPolyphony().name());
    } catch (Exception e) {
      sound.fw2Sound.polyphonic = org.deluge.firmware2.Sound.PolyphonyMode.POLY;
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
          if (model.getRawKnobs().isLfoRateKnobSet(i)) {
            sound.paramKnobs[paramId] = model.getRawKnobs().getLfoRateKnobQ31(i);
          }
        }
        // Tempo sync: the preset's <lfo1/lfo2 syncLevel/syncType> drives lfoConfig — the fw2
        // Sound then uses getSyncedLFOPhaseIncrement instead of the rate knob
        // (C sound.cpp:2700-2707) and resyncGlobalLFOs pins the phase to the bar. The XML
        // carries FILE values; convert to internal like the C load path (song.cpp:5761-5778).
        int internalSync =
            org.deluge.xml.DelugeXmlUtil.convertSyncLevelFromFileValueToInternalValue(
                lm.syncLevel(), sound.inputTickMagnitude);
        org.deluge.firmware2.Lfo.SyncType[] types = org.deluge.firmware2.Lfo.SyncType.values();
        sound.fw2Sound.lfoConfig[i].syncLevel =
            org.deluge.firmware2.Lfo.SyncLevel.values()[internalSync];
        sound.fw2Sound.lfoConfig[i].syncType =
            types[Math.max(0, Math.min(lm.syncType(), types.length - 1))];
      }
    }

    // Populate firmware2 raw knobs: envelope rates are already written directly to paramKnobs.
    // The previous redundant arraycopy is removed.
    for (int i = 0; i < 4; i++) {
      if (model.getRawKnobs().isEnvKnobSet(i)) {
        sound.paramKnobs[Param.LOCAL_ENV_0_ATTACK + i] = model.getRawKnobs().getEnvAttackKnobQ31(i);
        sound.paramKnobs[Param.LOCAL_ENV_0_DECAY + i] = model.getRawKnobs().getEnvDecayKnobQ31(i);
        sound.paramKnobs[Param.LOCAL_ENV_0_RELEASE + i] =
            model.getRawKnobs().getEnvReleaseKnobQ31(i);
      }
    }
    sound.paramKnobsPopulated = true;

    // Raw Q31 param-knob overrides from a song clip's <soundParams> (firmware reads these verbatim;
    // applied after the float-based mapping so they win for params the float round-trip mis-ranges,
    // e.g. filter resonance/morph/cutoff). See SynthTrackModel.rawParamKnobs.
    for (var e : model.getRawKnobs().getRawParamKnobs().entrySet()) {
      int pid = e.getKey();
      sound.paramKnobs[pid] = e.getValue();
    }

    // Compute final FX values from the fully-populated and overridden paramKnobs array.
    sound.fw2Sound.modFXRateIncrement =
        org.deluge.firmware2.Patcher.computeFinalValueForParam(
            Param.GLOBAL_MOD_FX_RATE, sound.paramKnobs[Param.GLOBAL_MOD_FX_RATE]);
    sound.fw2Sound.modFXDepth =
        org.deluge.firmware2.Patcher.computeFinalValueForParam(
            Param.GLOBAL_MOD_FX_DEPTH, sound.paramKnobs[Param.GLOBAL_MOD_FX_DEPTH]);
    sound.fw2Sound.modFXOffset =
        org.deluge.firmware2.Patcher.computeFinalValueForParam(
            Param.UNPATCHED_MOD_FX_OFFSET, sound.paramKnobs[Param.UNPATCHED_MOD_FX_OFFSET]);
    sound.fw2Sound.modFXFeedback =
        org.deluge.firmware2.Patcher.computeFinalValueForParam(
            Param.UNPATCHED_MOD_FX_FEEDBACK, sound.paramKnobs[Param.UNPATCHED_MOD_FX_FEEDBACK]);

    // Load-time delay finals (overwritten by FirmwareSound.syncParamsToFw2 before every render,
    // which also converts delaySyncLevel file→internal and applies the C's in-Delay tempo sync).
    sound.delayFeedbackAmount =
        org.deluge.firmware2.Patcher.computeFinalValueForParam(
            Param.GLOBAL_DELAY_FEEDBACK, sound.paramKnobs[Param.GLOBAL_DELAY_FEEDBACK]);
    sound.fw2Sound.delayUserRate =
        org.deluge.firmware2.Patcher.computeFinalValueForParam(
            Param.GLOBAL_DELAY_RATE, sound.paramKnobs[Param.GLOBAL_DELAY_RATE]);

    sound.reverbSendKnob = sound.paramKnobs[Param.GLOBAL_REVERB_AMOUNT];
    sound.fw2Sound.bitcrushParam = sound.paramKnobs[Param.UNPATCHED_BITCRUSHING];
    sound.fw2Sound.srrParam = sound.paramKnobs[Param.UNPATCHED_SAMPLE_RATE_REDUCTION];
    sound.fw2Sound.eqBassParam = sound.paramKnobs[Param.UNPATCHED_BASS];
    sound.fw2Sound.eqTrebleParam = sound.paramKnobs[Param.UNPATCHED_TREBLE];

    // Patch Cables
    mapPatchCables(model.getModulation().getPatchCables(), sound);

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

    // ── Envelope depth/target → synthesized patch cables ──
    // In the Deluge architecture, only Envelope 0 is hardwired (to master voice volume).
    // Envelopes 1, 2, and 3 are routed dynamically via the Patch Matrix (Modulation Matrix).
    // Without synthesizing these virtual "patch cables", filter envelopes and pitch sweeps
    // configured in the project model remain completely dead and disconnected in the DSP engine.
    // Here we dynamically compile any active Env 1, 2, or 3 targets into engine-level PatchCables.
    for (int i = 1; i < 4; i++) {
      EnvelopeModel em = model.getEnv(i);
      if (em == null || em.target() == null || "NONE".equalsIgnoreCase(em.target())) {
        continue;
      }
      int paramId =
          switch (em.target().trim().toUpperCase()) {
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
            case 1 -> PatchSource.ENVELOPE_1;
            case 2 -> PatchSource.ENVELOPE_2;
            default -> PatchSource.ENVELOPE_3;
          };
      float amt = em.amount() != 0.0f ? em.amount() : 1.0f;
      cable.amount = (int) (Math.max(-1f, Math.min(1f, amt)) * 2147483647.0);
      sound.paramManager.getPatchCableSet().addCable(paramId, cable);
    }

    // Map step automation from the active clip
    if (model != null && !model.getClips().isEmpty()) {
      int activeIdx = model.getActiveClipIndex();
      if (activeIdx >= 0 && activeIdx < model.getClips().size()) {
        org.deluge.model.ClipModel clipModel = model.getClips().get(activeIdx);
        int stepTicks = clipModel.isTripletMode() ? 32 : 24;
        for (java.util.Map.Entry<String, float[]> entry :
            clipModel.getAutomationData().entrySet()) {
          int paramId = getParamIdFromName(entry.getKey());
          if (paramId != -1) {
            float[] array = entry.getValue();
            for (int s = 0; s < array.length; s++) {
              if (array[s] > 0.0f) {
                int q31Val = (int) (array[s] * 2147483647.0);
                int pos = s * stepTicks;
                sound.paramManager.recordParamValue(paramId, q31Val, pos);
              }
            }
          }
        }
      }
    }
    sound.fw2Sound.resyncGlobalLFOs();
  }

  private static int cutoffKnobFromHz(double hz) {
    double norm = 2.0 * Math.log(Math.max(20.0, hz) / 20.0) / Math.log(1000.0) - 1.0;
    norm = Math.max(-1.0, Math.min(1.0, norm));
    return (int) Math.rint(norm * 2147483647.0);
  }

  public static PatchSource stringToPatchSource(String str) {
    if (str == null) return PatchSource.NONE;
    String clean = str.trim().toUpperCase().replace("_", "").replace(" ", "");
    // C (functions.cpp:268-280 sourceToString): lfo1=GLOBAL_1, lfo2=LOCAL_1, lfo3=GLOBAL_2,
    // lfo4=LOCAL_2. (Was mapping lfo1/lfo2 to the LOCAL sources, which sent e.g. an lfo1→pitch
    // vibrato through the wrong, default-SINE local LFO instead of the configured global one.)
    if (clean.equals("LFO1") || clean.equals("LFOGLOBAL1")) return PatchSource.LFO_GLOBAL_1;
    if (clean.equals("LFO2") || clean.equals("LFOLOCAL1")) return PatchSource.LFO_LOCAL_1;
    if (clean.equals("LFO3") || clean.equals("LFOGLOBAL2")) return PatchSource.LFO_GLOBAL_2;
    if (clean.equals("LFO4") || clean.equals("LFOLOCAL2")) return PatchSource.LFO_LOCAL_2;
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

  private static org.deluge.firmware2.ModFx.ModFXType stringToModFXType(String s) {
    if (s == null) return org.deluge.firmware2.ModFx.ModFXType.NONE;
    try {
      return org.deluge.firmware2.ModFx.ModFXType.valueOf(s.trim().toUpperCase());
    } catch (Exception e) {
      return org.deluge.firmware2.ModFx.ModFXType.NONE;
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
    return normToBipolarParam(norm);
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
      org.deluge.engine.FirmwareSound sound, org.deluge.model.ArpModel arp) {
    var s = sound.arpSettings;
    if (arp == null || !arp.active()) {
      s.mode = org.deluge.firmware2.Arpeggiator.ArpMode.OFF;
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
    if (rhythmIdx > org.deluge.firmware2.Functions.K_MAX_MENU_VALUE || rhythmIdx < 0) {
      rhythmIdx = org.deluge.firmware2.Functions.computeCurrentValueForUnsignedMenuItem(rhythmIdx);
    }
    rhythmIdx = Math.max(0, Math.min(org.deluge.firmware2.Functions.K_MAX_MENU_VALUE, rhythmIdx));
    s.rhythm = org.deluge.firmware2.Functions.computeFinalValueForUnsignedMenuItem(rhythmIdx);
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
        org.deluge.firmware2.Arpeggiator.SyncType.values()[
            Math.max(0, arp.syncType())
                % org.deluge.firmware2.Arpeggiator.SyncType.values().length];
    s.mpeVelocity =
        (arp.mpeVelocity() == 1)
            ? org.deluge.firmware2.Arpeggiator.ArpMpeModSource.AFTERTOUCH
            : org.deluge.firmware2.Arpeggiator.ArpMpeModSource.OFF;
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
          org.deluge.firmware2.Functions.getExp(
                  121739,
                  org.deluge.firmware2.Functions.patchCombineExpStep(0, (int) mid, 1073741824))
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
    return org.deluge.firmware2.Functions.computeFinalValueForUnsignedMenuItem(user);
  }

  private static org.deluge.firmware2.Arpeggiator.ArpNoteMode stringToArpNoteMode(String m) {
    if (m == null) return org.deluge.firmware2.Arpeggiator.ArpNoteMode.UP;
    return switch (m.trim().toUpperCase()) {
      case "DOWN" -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.DOWN;
      case "UPDN", "UP_DOWN", "UPDOWN" -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.UP_DOWN;
      case "RAND", "RANDOM" -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.RANDOM;
      case "WLK1", "WALK1" -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.WALK1;
      case "WLK2", "WALK2" -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.WALK2;
      case "WLK3", "WALK3" -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.WALK3;
      case "PLAY", "AS_PLAYED" -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.AS_PLAYED;
      case "PATT", "PATTERN" -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.PATTERN;
      default -> org.deluge.firmware2.Arpeggiator.ArpNoteMode.UP;
    };
  }

  private static org.deluge.firmware2.Arpeggiator.ArpMode stringToArpMode(String m) {
    if (m == null) return org.deluge.firmware2.Arpeggiator.ArpMode.ARP;
    var t = m.trim().toUpperCase();
    return "OFF".equals(t)
        ? org.deluge.firmware2.Arpeggiator.ArpMode.OFF
        : org.deluge.firmware2.Arpeggiator.ArpMode.ARP;
  }

  private static org.deluge.firmware2.Arpeggiator.ArpOctaveMode stringToArpOctaveMode(String m) {
    if (m == null) return org.deluge.firmware2.Arpeggiator.ArpOctaveMode.UP;
    return switch (m.trim().toUpperCase()) {
      case "DOWN" -> org.deluge.firmware2.Arpeggiator.ArpOctaveMode.DOWN;
      case "UP_DOWN", "UPDN", "UPDOWN", "ALT" ->
          org.deluge.firmware2.Arpeggiator.ArpOctaveMode.UP_DOWN;
      default -> org.deluge.firmware2.Arpeggiator.ArpOctaveMode.UP;
    };
  }

  private static org.deluge.playback.PolyphonyMode toPolyphonyMode(
      SynthTrackModel.PolyphonyMode m) {
    if (m == null) return org.deluge.playback.PolyphonyMode.POLY;
    try {
      return org.deluge.playback.PolyphonyMode.valueOf(m.name());
    } catch (IllegalArgumentException e) {
      return org.deluge.playback.PolyphonyMode.POLY;
    }
  }

  public static File resolveSample(String path, File sdRoot) {
    if (path == null) return null;
    // Normalize path separators: replace Windows backslashes with forward slashes
    String normPath = path.replace('\\', '/');

    File f = new File(normPath);
    if (f.exists()) return f;

    // 1. Try directly under local src/main/resources
    File localRes = new File("src/main/resources", normPath);
    if (localRes.exists()) return localRes;

    // 2. Try directly under local target/classes
    File localTarget = new File("target/classes", normPath);
    if (localTarget.exists()) return localTarget;

    // 3. Try under neighbor deluge module src/main/resources (for multi-module tests)
    File delugeRes = new File("../deluge/src/main/resources", normPath);
    if (delugeRes.exists()) return delugeRes;

    // 4. Try under neighbor deluge module target/classes
    File delugeTarget = new File("../deluge/target/classes", normPath);
    if (delugeTarget.exists()) return delugeTarget;

    if (sdRoot != null) {
      f = new File(sdRoot, normPath);
      if (f.exists()) return f;
      // Case-insensitive match under the SD-card/library root: the Deluge saves on FAT32 (case-
      // insensitive), so a preset can reference "808 Clap.wav" while the file on disk is
      // "808 Clap.WAV". Java's File.exists is case-sensitive on Linux, so resolve each component
      // CI.
      File ciSd = resolveCaseInsensitive(sdRoot, normPath);
      if (ciSd != null) return ciSd;
      File fallbackSd = new File("../deluge/" + sdRoot.getPath(), normPath);
      if (fallbackSd.exists()) return fallbackSd;
    }

    // 5. Try under user's home "deluge-card" directory (or custom path via -Ddeluge.card)
    String cardName = System.getProperty("deluge.card", "deluge-card");
    File sdCard = new File(System.getProperty("user.home"), cardName);
    if (sdCard.isDirectory()) {
      File fallback = new File(sdCard, normPath);
      if (fallback.exists()) return fallback;
      // Case-insensitive fallback: real hardware (FAT32) is case-insensitive, but Deluge presets
      // often store a different case than the on-disk dir (e.g. "Multisamples" vs "MULTISAMPLES").
      // On case-sensitive filesystems (Linux) File.exists fails; resolve each path component CI.
      File ci = resolveCaseInsensitive(sdCard, normPath);
      if (ci != null) return ci;
    }

    return f;
  }

  /** Walk {@code relPath} under {@code root}, matching each component case-insensitively. */
  private static File resolveCaseInsensitive(File root, String relPath) {
    File cur = root;
    for (String part : relPath.split("/")) {
      if (part.isEmpty()) continue;
      File exact = new File(cur, part);
      if (exact.exists()) {
        cur = exact;
        continue;
      }
      File[] kids = cur.listFiles();
      if (kids == null) return null;
      File match = null;
      for (File k : kids) {
        if (k.getName().equalsIgnoreCase(part)) {
          match = k;
          break;
        }
      }
      if (match == null) return null;
      cur = match;
    }
    return cur.isFile() ? cur : null;
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

  private static org.deluge.firmware2.Lfo.LfoType mapLfoType(org.deluge.model.LfoType type) {
    if (type == null) return org.deluge.firmware2.Lfo.LfoType.SINE;
    switch (type) {
      case S_AND_H:
        return org.deluge.firmware2.Lfo.LfoType.SAMPLE_AND_HOLD;
      default:
        return org.deluge.firmware2.Lfo.LfoType.valueOf(type.name());
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

    drumSound.fw2Sound.maxPolyphony =
        sd.getMaxVoiceCount(); // C: sound.h:116 (per-drum voice cap, default 8)
    drumSound.fw2Sound.voicePriority =
        sd.getVoicePriority(); // voice-stealing priority (was stuck at default)
    drumSound.isDrum = true;

    // Map synthesis oscillator types
    drumSound.oscTypes[0] = stringToOscType(sd.getOsc1Type());
    drumSound.oscTypes[1] = stringToOscType(sd.getOsc2Type());

    // Map Synth Mode (0=Subtractive, 1=FM, 2=RingMod)
    int modeVal = sd.getSynthMode();
    if (modeVal == 1) {
      drumSound.setSynthMode(FirmwareSound.SynthMode.FM);
    } else if (modeVal == 2) {
      drumSound.setSynthMode(FirmwareSound.SynthMode.RINGMOD);
    } else {
      drumSound.setSynthMode(FirmwareSound.SynthMode.SUBTRACTIVE);
    }

    // Map FM modulator-to-carrier frequency ratios and transposes/cents
    drumSound.fmRatio1 = sd.getFmRatio();
    drumSound.fmRatio2 = sd.getFmRatio2();
    drumSound.fmModulator1Transpose = sd.getModulator1Transpose();
    drumSound.fmModulator1Cents = sd.getModulator1Cents();
    drumSound.fmModulator2Transpose = sd.getModulator2Transpose();
    drumSound.fmModulator2Cents = sd.getModulator2Cents();

    // Map FM modulator amounts and carrier feedback
    drumSound.fmModulatorAmountBase[0] = sd.getModulator1AmountQ31();
    drumSound.fmModulatorAmountBase[1] = sd.getModulator2AmountQ31();
    drumSound.paramNeutralValues[Param.LOCAL_MODULATOR_0_VOLUME] = sd.getModulator1AmountQ31();
    drumSound.paramNeutralValues[Param.LOCAL_MODULATOR_1_VOLUME] = sd.getModulator2AmountQ31();
    drumSound.paramNeutralValues[Param.LOCAL_MODULATOR_0_FEEDBACK] = sd.getModulator1FeedbackQ31();
    drumSound.paramNeutralValues[Param.LOCAL_MODULATOR_1_FEEDBACK] = sd.getModulator2FeedbackQ31();
    drumSound.paramNeutralValues[Param.LOCAL_CARRIER_0_FEEDBACK] = sd.getCarrier1FeedbackQ31();
    drumSound.paramNeutralValues[Param.LOCAL_CARRIER_1_FEEDBACK] = sd.getCarrier2FeedbackQ31();

    // Map oscillator coarse transpose and cents detune
    drumSound.fw2Sound.masterTranspose = (int) sd.getPitchSemitones();
    drumSound.fw2Sound.sourceTranspose[0] = sd.getOsc1Transpose();
    drumSound.fw2Sound.sourceTranspose[1] = sd.getOsc2Transpose();
    drumSound.fw2Sound.setSourceCents(0, sd.getOsc1Cents());
    drumSound.fw2Sound.setSourceCents(1, sd.getOsc2Cents());

    // Map oscillator/noise volumes
    float oscAVol = sd.getOscAVolume();
    if (sd.getOsc1Type().equalsIgnoreCase("SAMPLE") && oscAVol == 0.0f) {
      oscAVol = 1.0f; // Legacy sample-based row default
    }
    drumSound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = normToBipolarParamVolume(oscAVol);
    drumSound.paramNeutralValues[Param.LOCAL_VOLUME] = normToBipolarParamVolume(sd.getVolume());
    drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] =
        normToBipolarParamVolume(sd.getOscBVolume());
    drumSound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] =
        normToBipolarParamVolume(sd.getNoiseVolume());

    // If osc 2 is NONE/off, silence it
    String osc2t = sd.getOsc2Type();
    if (osc2t == null
        || osc2t.isBlank()
        || osc2t.equalsIgnoreCase("none")
        || osc2t.equalsIgnoreCase("off")) {
      drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    }

    // Trigger phases
    drumSound.osc1RetriggerPhase = sd.getOsc1RetrigPhase();
    drumSound.osc2RetriggerPhase = sd.getOsc2RetrigPhase();
    drumSound.mod1RetrigPhase = sd.getMod1RetrigPhase();
    drumSound.mod2RetrigPhase = sd.getMod2RetrigPhase();
    drumSound.fw2Sound.sidechainSend = (int) (sd.getSidechainSend() * 2147483647.0);

    // Map drum-specific patch cables
    mapPatchCables(sd.getPatchCables(), drumSound);

    // Per-drum modulation FX
    drumSound.fw2Sound.modFXType = stringToModFXType(sd.getModFxType());
    drumSound.fw2Sound.modFXRateIncrement =
        (int) ((double) sd.getModFxRate() * 4294967296.0 / 44100.0);
    drumSound.fw2Sound.modFXDepth = (int) (clamp01(sd.getModFxDepth()) * 2147483647.0);
    drumSound.fw2Sound.modFXOffset = (int) (clamp01(sd.getModFxOffset()) * 2147483647.0);
    drumSound.fw2Sound.modFXFeedback = (int) (clamp01(sd.getModFxFeedback()) * 2147483647.0);
    drumSound.fw2Sound.bitcrushParam = normToBipolarParam(sd.getBitCrush());
    drumSound.fw2Sound.srrParam = normToBipolarParam(sd.getSampleRateReduction());
    drumSound.fw2Sound.eqBassParam = dbToBipolarParam(sd.getEqBass());
    drumSound.fw2Sound.eqTrebleParam = dbToBipolarParam(sd.getEqTreble());
    drumSound.paramNeutralValues[Param.UNPATCHED_SIDECHAIN_SHAPE] =
        normToLinearParamKnob(sd.getCompressorShape());

    // Sample playback settings
    drumSound.sampleSettings[0].reverse = sd.isReverse();
    if (sd.getStartSamplePos() >= 0) {
      drumSound.sampleSettings[0].startPoint = sd.getStartSamplePos();
    }
    if (sd.getEndSamplePos() >= 0) {
      System.out.println(
          "[FirmwareFactory] Drum "
              + drumIdx
              + " ("
              + sd.getName()
              + ") endSamplePos from model: "
              + sd.getEndSamplePos());
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
          org.deluge.firmware2.Functions.ONE_Q31;
      drumSound.oscTypes[1] = stringToOscType(sd.getOsc2Type());
    } else {
      drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
      drumSound.oscTypes[1] = null;
    }

    // Envelope
    var env = sd.getAdsr();
    drumSound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK] =
        org.deluge.firmware2.Functions.getParamFromUserValue(
            Param.LOCAL_ENV_0_ATTACK, (int) (env.attack() * 50));
    drumSound.paramNeutralValues[Param.LOCAL_ENV_0_DECAY] =
        org.deluge.firmware2.Functions.getParamFromUserValue(
            Param.LOCAL_ENV_0_DECAY, (int) (env.decay() * 50));
    drumSound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] =
        org.deluge.firmware2.Functions.getParamFromUserValue(
            Param.LOCAL_ENV_0_SUSTAIN, (int) (env.sustain() * 50));
    drumSound.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE] =
        org.deluge.firmware2.Functions.getParamFromUserValue(
            Param.LOCAL_ENV_0_RELEASE, (int) (env.release() * 50));

    // Filter
    drumSound.paramNeutralValues[Param.LOCAL_LPF_FREQ] =
        (int) (sd.getLpfFreq() / 22050.0f * 2147483647.0f);
    drumSound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] =
        (int) (sd.getLpfRes() * 2147483647.0f);

    // Polyphony / unison
    try {
      drumSound.fw2Sound.polyphonic =
          org.deluge.firmware2.Sound.PolyphonyMode.valueOf(
              toPolyphonyMode(sd.getPolyphony()).name());
    } catch (Exception e) {
      drumSound.fw2Sound.polyphonic = org.deluge.firmware2.Sound.PolyphonyMode.POLY;
    }
    drumSound.fw2Sound.numUnison = sd.getUnisonNum();
    drumSound.fw2Sound.unisonDetune = (int) sd.getUnisonDetune();
    drumSound.fw2Sound.unisonStereoSpread = (int) sd.getUnisonStereoSpread();

    // Map step automation
    if (model != null && !model.getClips().isEmpty()) {
      org.deluge.model.ClipModel clipModel = model.getClips().get(0);
      int stepTicks = clipModel.isTripletMode() ? 32 : 24;
      org.deluge.model.NoteRowModel rowModel = null;
      for (org.deluge.model.NoteRowModel r : clipModel.getNoteRowsMap().values()) {
        if (r.getPitch() == drumIdx) {
          rowModel = r;
          break;
        }
      }
      java.util.Map<String, float[]> rowAutos =
          (rowModel != null) ? rowModel.getRowAutomation() : null;
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
    drumSound.fw2Sound.resyncGlobalLFOs();
  }

  private static void applyRowOverrides(
      org.deluge.model.NoteRowModel rowModel, FirmwareSound drumSound) {
    for (java.util.Map.Entry<String, Float> entry : rowModel.getSoundParams().entrySet()) {
      String param = entry.getKey();
      Float val = entry.getValue();
      if (val == null) continue;
      switch (param) {
        case "volume" ->
            drumSound.paramNeutralValues[Param.LOCAL_VOLUME] = normToBipolarParamVolume(val);
        case "oscAVolume" ->
            drumSound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = normToBipolarParamVolume(val);
        case "oscBVolume" ->
            drumSound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = normToBipolarParamVolume(val);
        case "noiseVolume" ->
            drumSound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = normToBipolarParamVolume(val);
        case "lpfFrequency" ->
            drumSound.paramNeutralValues[Param.LOCAL_LPF_FREQ] =
                (int) (val * (20000.0f / 22050.0f) * 2147483647.0f);
        case "lpfResonance" ->
            drumSound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = (int) (val * 2147483647.0f);
        case "hpfFrequency" ->
            drumSound.paramNeutralValues[Param.LOCAL_HPF_FREQ] =
                (int) (val * (20000.0f / 22050.0f) * 2147483647.0f);
        case "hpfResonance" ->
            drumSound.paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = (int) (val * 2147483647.0f);
        case "envelope1_attack" ->
            drumSound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK] =
                org.deluge.firmware2.Functions.getParamFromUserValue(
                    Param.LOCAL_ENV_0_ATTACK, (int) (val * 50));
        case "envelope1_decay" ->
            drumSound.paramNeutralValues[Param.LOCAL_ENV_0_DECAY] =
                org.deluge.firmware2.Functions.getParamFromUserValue(
                    Param.LOCAL_ENV_0_DECAY, (int) (val * 50));
        case "envelope1_sustain" ->
            drumSound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN] =
                org.deluge.firmware2.Functions.getParamFromUserValue(
                    Param.LOCAL_ENV_0_SUSTAIN, (int) (val * 50));
        case "envelope1_release" ->
            drumSound.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE] =
                org.deluge.firmware2.Functions.getParamFromUserValue(
                    Param.LOCAL_ENV_0_RELEASE, (int) (val * 50));
        case "envelope2_attack" ->
            drumSound.paramNeutralValues[Param.LOCAL_ENV_1_ATTACK] =
                org.deluge.firmware2.Functions.getParamFromUserValue(
                    Param.LOCAL_ENV_1_ATTACK, (int) (val * 50));
        case "envelope2_decay" ->
            drumSound.paramNeutralValues[Param.LOCAL_ENV_1_DECAY] =
                org.deluge.firmware2.Functions.getParamFromUserValue(
                    Param.LOCAL_ENV_1_DECAY, (int) (val * 50));
        case "envelope2_sustain" ->
            drumSound.paramNeutralValues[Param.LOCAL_ENV_1_SUSTAIN] =
                org.deluge.firmware2.Functions.getParamFromUserValue(
                    Param.LOCAL_ENV_1_SUSTAIN, (int) (val * 50));
        case "envelope2_release" ->
            drumSound.paramNeutralValues[Param.LOCAL_ENV_1_RELEASE] =
                org.deluge.firmware2.Functions.getParamFromUserValue(
                    Param.LOCAL_ENV_1_RELEASE, (int) (val * 50));
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
        smp.midiNoteFromFile =
            60.0f; // Default to C4 (60) so drum kit samples play at original speed (internal
        // trigger is at 60)
        var fw2Smp = org.deluge.firmware2.Sample.fromFirmwareSample(smp);
        synchronized (drumSound) {
          drumSound.samples[0] = smp;
          drumSound.fw2SampleCache[0] = fw2Smp;
          drumSound.loadedOscPath[0] = key;
        }
        System.out.println("[FirmwareFactory] Loaded drum sample: " + f.getAbsolutePath());
      }
    } catch (IOException e) {
      System.err.println("[FirmwareFactory] Failed to load drum sample: " + path);
    }
  }

  private static int combineCablesLinearNoCable(int storedValue, int paramRange) {
    return org.deluge.firmware2.Functions.patchCombineLinearStep(536870912, storedValue, paramRange)
        - 536870912;
  }

  private static int finalVolumeParam(int storedValue, int paramNeutralValue, int paramRange) {
    return org.deluge.firmware2.Functions.getFinalParameterValueVolume(
        paramNeutralValue, combineCablesLinearNoCable(storedValue, paramRange));
  }

  private static int finalLinearParam(int storedValue, int paramNeutralValue, int paramRange) {
    return org.deluge.firmware2.Functions.getFinalParameterValueLinear(
        paramNeutralValue, combineCablesLinearNoCable(storedValue, paramRange));
  }

  private static int finalEnvRateParam(int paramNeutralValue, int patchedValue, int stage) {
    return org.deluge.firmware2.Functions.getFinalParameterValueExpWithDumbEnvelopeHack(
        paramNeutralValue, patchedValue, stage);
  }

  private static void mapPatchCables(
      java.util.List<org.deluge.model.PatchCable> patchCables, FirmwareSound sound) {
    for (org.deluge.model.PatchCable pcm : patchCables) {
      try {
        String destStr = pcm.destination().toUpperCase();
        String srcStr = pcm.source().toUpperCase();

        // Manual mapping from string to Param ID. Order matters: the more specific modulator/osc
        // volume names must be matched before the generic "VOLUME" catch-all, otherwise FM
        // modulator-depth cables (e.g. envelope2 -> modulator1Volume) get misrouted to the master
        // LOCAL_VOLUME and the FM depth never tracks the envelope.
        int paramId = -1;
        // 1. Filter Cutoffs, Resonances, and Morphs
        if (destStr.contains("LPFFREQUENCY") || destStr.contains("LPF_FREQ"))
          paramId = Param.LOCAL_LPF_FREQ;
        else if (destStr.contains("LPFRESONANCE") || destStr.contains("LPF_RES"))
          paramId = Param.LOCAL_LPF_RESONANCE;
        else if (destStr.contains("HPFFREQUENCY") || destStr.contains("HPF_FREQ"))
          paramId = Param.LOCAL_HPF_FREQ;
        else if (destStr.contains("HPFRESONANCE") || destStr.contains("HPF_RES"))
          paramId = Param.LOCAL_HPF_RESONANCE;
        else if (destStr.contains("LPFMORPH") || destStr.contains("LPF_MORPH"))
          paramId = Param.LOCAL_LPF_MORPH;
        else if (destStr.contains("HPFMORPH") || destStr.contains("HPF_MORPH"))
          paramId = Param.LOCAL_HPF_MORPH;

        // 2. FM Modulators & Carriers
        else if (destStr.contains("MODULATOR1VOLUME")) paramId = Param.LOCAL_MODULATOR_0_VOLUME;
        else if (destStr.contains("MODULATOR2VOLUME")) paramId = Param.LOCAL_MODULATOR_1_VOLUME;
        else if (destStr.contains("CARRIER1FEEDBACK")) paramId = Param.LOCAL_CARRIER_0_FEEDBACK;
        else if (destStr.contains("CARRIER2FEEDBACK")) paramId = Param.LOCAL_CARRIER_1_FEEDBACK;
        else if (destStr.contains("MODULATOR1FEEDBACK")) paramId = Param.LOCAL_MODULATOR_0_FEEDBACK;
        else if (destStr.contains("MODULATOR2FEEDBACK")) paramId = Param.LOCAL_MODULATOR_1_FEEDBACK;

        // 3. Independent Pitch Modulations (Specific before generic PITCH)
        else if (destStr.contains("OSCAPITCH") || destStr.contains("OSC1PITCH"))
          paramId = Param.LOCAL_OSC_A_PITCH_ADJUST;
        else if (destStr.contains("OSCBPITCH") || destStr.contains("OSC2PITCH"))
          paramId = Param.LOCAL_OSC_B_PITCH_ADJUST;
        else if (destStr.contains("MODULATOR1PITCH") || destStr.contains("MOD1PITCH"))
          paramId = Param.LOCAL_MODULATOR_0_PITCH_ADJUST;
        else if (destStr.contains("MODULATOR2PITCH") || destStr.contains("MOD2PITCH"))
          paramId = Param.LOCAL_MODULATOR_1_PITCH_ADJUST;
        else if (destStr.contains("PITCH")) paramId = Param.LOCAL_PITCH_ADJUST;

        // 4. Pulse Width (PWM) & Wavetable Index (Phase Width / Wave Index)
        else if (destStr.contains("OSCAPULSEWIDTH")
            || destStr.contains("OSC1PULSEWIDTH")
            || destStr.contains("OSCAPHASEWIDTH")
            || destStr.contains("OSC1PHASEWIDTH")) paramId = Param.LOCAL_OSC_A_PHASE_WIDTH;
        else if (destStr.contains("OSCBPULSEWIDTH")
            || destStr.contains("OSC2PULSEWIDTH")
            || destStr.contains("OSCBPHASEWIDTH")
            || destStr.contains("OSC2PHASEWIDTH")) paramId = Param.LOCAL_OSC_B_PHASE_WIDTH;
        else if (destStr.contains("OSCAWAVETABLE")
            || destStr.contains("OSC1WAVETABLE")
            || destStr.contains("OSCAWAVEINDEX")
            || destStr.contains("OSC1WAVEINDEX")) paramId = Param.LOCAL_OSC_A_WAVE_INDEX;
        else if (destStr.contains("OSCBWAVETABLE")
            || destStr.contains("OSC2WAVETABLE")
            || destStr.contains("OSCBWAVEINDEX")
            || destStr.contains("OSC2WAVEINDEX")) paramId = Param.LOCAL_OSC_B_WAVE_INDEX;

        // 5. LFO Rates
        else if (destStr.contains("LFO1RATE")
            || destStr.contains("LFO1FREQ")
            || destStr.contains("LFO_1_RATE")) paramId = Param.LOCAL_LFO_LOCAL_FREQ_1;
        else if (destStr.contains("LFO2RATE")
            || destStr.contains("LFO2FREQ")
            || destStr.contains("LFO_2_RATE")) paramId = Param.LOCAL_LFO_LOCAL_FREQ_2;

        // 6. Delay and Reverb Effects
        else if (destStr.contains("DELAYRATE") || destStr.contains("DELAY_RATE"))
          paramId = Param.GLOBAL_DELAY_RATE;
        else if (destStr.contains("DELAYFEEDBACK") || destStr.contains("DELAY_FEEDBACK"))
          paramId = Param.GLOBAL_DELAY_FEEDBACK;
        else if (destStr.contains("REVERBAMOUNT") || destStr.contains("REVERB_SEND"))
          paramId = Param.GLOBAL_REVERB_AMOUNT;

        // 7. Mod FX (Chorus/Flanger/Phaser)
        else if (destStr.contains("MODFXRATE") || destStr.contains("MOD_FX_RATE"))
          paramId = Param.GLOBAL_MOD_FX_RATE;
        else if (destStr.contains("MODFXDEPTH") || destStr.contains("MOD_FX_DEPTH"))
          paramId = Param.GLOBAL_MOD_FX_DEPTH;

        // 8. Panning (Specific PAN check)
        else if (destStr.contains("PAN")) paramId = Param.LOCAL_PAN;

        // 9. Shielded Specific Volumes (Before generic VOLUME)
        else if (destStr.contains("OSCAVOLUME") || destStr.contains("OSC1VOLUME"))
          paramId = Param.LOCAL_OSC_A_VOLUME;
        else if (destStr.contains("OSCBVOLUME") || destStr.contains("OSC2VOLUME"))
          paramId = Param.LOCAL_OSC_B_VOLUME;
        else if (destStr.contains("NOISEVOLUME") || destStr.contains("NOISE_VOL"))
          paramId = Param.LOCAL_NOISE_VOLUME;
        else if (destStr.contains("VOLUME")) paramId = Param.LOCAL_VOLUME;

        if (paramId != -1) {
          PatchSource source = stringToPatchSource(pcm.source());
          int amount = (int) (pcm.amount() * 2147483647.0);
          PatchCable engineCable = new PatchCable();
          engineCable.from = source;
          engineCable.amount = amount;
          if (pcm.polarity() == org.deluge.model.PatchCable.Polarity.UNIPOLAR) {
            engineCable.polarity = org.deluge.modulation.patch.PatchCable.Polarity.UNIPOLAR;
          } else {
            engineCable.polarity = org.deluge.modulation.patch.PatchCable.Polarity.BIPOLAR;
          }
          sound.paramManager.getPatchCableSet().addCable(paramId, engineCable);

          // Compile nested range modulation cables under depthControlledBy
          if (pcm.depthControlledBy() != null) {
            for (org.deluge.model.PatchCable dc : pcm.depthControlledBy()) {
              PatchSource dcSource = stringToPatchSource(dc.source());
              int dcAmount = (int) (dc.amount() * 2147483647.0);
              PatchCable dcEngineCable = new PatchCable();
              dcEngineCable.from = dcSource;
              dcEngineCable.amount = dcAmount;
              if (dc.polarity() == org.deluge.model.PatchCable.Polarity.UNIPOLAR) {
                dcEngineCable.polarity = org.deluge.modulation.patch.PatchCable.Polarity.UNIPOLAR;
              } else {
                dcEngineCable.polarity = org.deluge.modulation.patch.PatchCable.Polarity.BIPOLAR;
              }
              sound.paramManager.getPatchCableSet().addRangeCable(paramId, source, dcEngineCable);
            }
          }
        }
      } catch (Exception e) {
        // Skip invalid cables
      }
    }
  }
}
