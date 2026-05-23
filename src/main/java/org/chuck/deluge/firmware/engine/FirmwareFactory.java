package org.chuck.deluge.firmware.engine;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.model.note.NoteRow;
import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchCable;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.storage.audio.AudioFileReader;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.Drum;
import org.chuck.deluge.model.EnvelopeModel;
import org.chuck.deluge.model.KitTrackModel;
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
      clip.loopLength = clipModel.getStepCount() * 24;
      for (int r = 0; r < clipModel.getRowCount(); r++) {
        int pitch = clipModel.getStep(r, 0).pitch();
        NoteRow row = new NoteRow(pitch);
        for (int s = 0; s < clipModel.getStepCount(); s++) {
          StepData step = clipModel.getStep(r, s);
          if (step.active()) {
            row.attemptNoteAdd(
                s * 24, (int) (step.gate() * 24), (int) step.velocity(), 100, null, 0);
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

    // Map FM Ratios
    sound.fmRatio1 = model.getFmRatio();
    sound.fmRatio2 = model.getFmRatio() * 2.0f;

    // Volume/Pan
    sound.paramNeutralValues[Param.LOCAL_VOLUME] = (int) (model.getVolume() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_PAN] = (int) ((model.getPan() + 1.0) * 1073741823.0);

    // Oscillator & Noise Volumes (derived from mix/noiseVol)
    float oscMix = model.getOscMix();
    sound.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = (int) (oscMix * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = (int) ((1.0f - oscMix) * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = (int) (model.getNoiseVol() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_OSC_B_PITCH_ADJUST] =
        (model.getOsc2Transpose() * 100 + model.getOsc2Cents()) * 178956;

    // Filter (scaled to Q26 format to match tangent log curver)
    sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] =
        (int) (model.getLpfFreq() / 20000.0 * 67108864.0);
    sound.paramNeutralValues[Param.LOCAL_LPF_RESONANCE] = (int) (model.getLpfRes() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_LPF_MORPH] = (int) (model.getLpfMorph() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_HPF_FREQ] =
        (int) (model.getHpfFreq() / 20000.0 * 67108864.0);
    sound.paramNeutralValues[Param.LOCAL_HPF_RESONANCE] = (int) (model.getHpfRes() * 2147483647.0);
    sound.paramNeutralValues[Param.LOCAL_HPF_MORPH] = (int) (model.getHpfMorph() * 2147483647.0);

    sound.setLpfMode(model.getFilterMode());
    sound.setHpfMode(model.getHpfMode());
    sound.setFilterRoute(model.getFilterRoute());

    // Retrigger Phases
    sound.osc1RetriggerPhase = model.getOsc1RetrigPhase();
    sound.osc2RetriggerPhase = model.getOsc2RetrigPhase();
    sound.mod1RetrigPhase = model.getMod1RetrigPhase();
    sound.mod2RetrigPhase = model.getMod2RetrigPhase();

    // Envelopes
    for (int i = 0; i < 4; i++) {
      EnvelopeModel em = model.getEnv(i);
      // Scale 0.0-1.0 to increments
      int attackInc = (int) (20000 / (em.attack() * 100.0 + 1));
      int decayInc = (int) (1000 / (em.decay() * 10.0 + 1));
      int releaseInc = (int) (1000 / (em.release() * 10.0 + 1));

      sound.paramNeutralValues[Param.LOCAL_ENV_0_ATTACK + i] = attackInc;
      sound.paramNeutralValues[Param.LOCAL_ENV_0_DECAY + i] = decayInc;
      sound.paramNeutralValues[Param.LOCAL_ENV_0_SUSTAIN + i] = (int) (em.sustain() * 2147483647.0);
      sound.paramNeutralValues[Param.LOCAL_ENV_0_RELEASE + i] = releaseInc;
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

    // Patch Cables
    for (org.chuck.deluge.model.PatchCable pcm : model.getPatchCables()) {
      try {
        String destStr = pcm.destination().toUpperCase();
        String srcStr = pcm.source().toUpperCase();

        // Manual mapping from string to Param ID
        int paramId = -1;
        if (destStr.contains("LPFFREQUENCY") || destStr.contains("LPF_FREQ"))
          paramId = Param.LOCAL_LPF_FREQ;
        else if (destStr.contains("LPFRESONANCE") || destStr.contains("LPF_RES"))
          paramId = Param.LOCAL_LPF_RESONANCE;
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

  public static InstrumentClip createKitClip(KitTrackModel model) {
    InstrumentClip clip = new InstrumentClip();
    FirmwareKit kit = new FirmwareKit();
    clip.sound = kit;
    clip.loopLength = 16 * 24;

    File sdRoot = PreferencesManager.getLibraryDir();
    File devSamples = new File("deluge/src/main/resources");

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

        // Map per-lane step automation from the ClipModel to the drum sound's ParamManager
        if (!model.getClips().isEmpty()) {
          org.chuck.deluge.model.ClipModel clipModel = model.getClips().get(0);
          java.util.Map<String, float[]> rowAutos = clipModel.getRowAutomationData().get(drumIdx);
          if (rowAutos != null) {
            for (java.util.Map.Entry<String, float[]> entry : rowAutos.entrySet()) {
              int paramId = getParamIdFromName(entry.getKey());
              if (paramId != -1) {
                float[] array = entry.getValue();
                for (int s = 0; s < array.length; s++) {
                  if (array[s] > 0.0f) {
                    int q31Val = (int) (array[s] * 2147483647.0);
                    int pos = s * 24; // 24 ticks per step (16th notes)
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
      clip.loopLength = clipModel.getStepCount() * 24;
      for (int r = 0; r < clipModel.getRowCount(); r++) {
        int pitch = clipModel.getStep(r, 0).pitch();
        NoteRow row = new NoteRow(pitch);
        for (int s = 0; s < clipModel.getStepCount(); s++) {
          StepData step = clipModel.getStep(r, s);
          if (step.active()) {
            row.attemptNoteAdd(
                s * 24, (int) (step.gate() * 24), (int) step.velocity(), 100, null, 0);
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
    // TODO: Implement reverse mapping for saving
    System.out.println("[FirmwareFactory] syncFirmwareToModel called (STUB)");
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
}
