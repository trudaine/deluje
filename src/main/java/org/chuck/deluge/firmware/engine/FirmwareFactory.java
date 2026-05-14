package org.chuck.deluge.firmware.engine;

import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.model.note.NoteRow;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;

/** Glue code to convert the existing XML-loaded models into the high-fidelity firmware engine. */
public class FirmwareFactory {

  public static Song createSong(ProjectModel model) {
    Song song = new Song();
    song.tempoBPM = model.getBpm();

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

    // Map note rows
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

    // Map patch cables
    for (org.chuck.deluge.model.PatchCable cable : model.getPatchCables()) {
      int paramId = mapParam(cable.destination());
      if (paramId != -1) {
        org.chuck.deluge.firmware.modulation.patch.PatchCable fwCable =
            new org.chuck.deluge.firmware.modulation.patch.PatchCable();
        fwCable.from = mapSource(cable.source());
        fwCable.amount = (int) (cable.amount() * 2147483647.0);
        sound.paramManager.getPatchCableSet().addCable(paramId, fwCable);
      }
    }

    return clip;
  }

  private static InstrumentClip createKitClip(KitTrackModel model) {
    InstrumentClip clip = new InstrumentClip();
    FirmwareSound sound = new FirmwareSound();
    clip.sound = sound;
    clip.loopLength = 16 * 24;

    if (!model.getClips().isEmpty()) {
      ClipModel clipModel = model.getClips().get(0);
      clip.loopLength = clipModel.getStepCount() * 24;
      for (int r = 0; r < clipModel.getRowCount(); r++) {
        // For Kits, y attribute or row index maps to drumIndex
        NoteRow row = new NoteRow(r);
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

  private static int mapParam(String p) {
    if (p == null) return -1;
    return switch (p) {
      case "lpfFrequency" -> Param.LOCAL_LPF_FREQ;
      case "lpfResonance" -> Param.LOCAL_LPF_RESONANCE;
      case "hpfFrequency" -> Param.LOCAL_HPF_FREQ;
      case "hpfResonance" -> Param.LOCAL_HPF_RESONANCE;
      case "volume" -> Param.LOCAL_VOLUME;
      case "pan" -> Param.LOCAL_PAN;
      case "oscAVolume" -> Param.LOCAL_OSC_A_VOLUME;
      case "oscBVolume" -> Param.LOCAL_OSC_B_VOLUME;
      case "noiseVolume" -> Param.LOCAL_NOISE_VOLUME;
      case "modulator1Feedback" -> Param.LOCAL_MODULATOR_0_FEEDBACK;
      case "modulator2Feedback" -> Param.LOCAL_MODULATOR_1_FEEDBACK;
      case "carrier1Feedback" -> Param.LOCAL_CARRIER_0_FEEDBACK;
      case "carrier2Feedback" -> Param.LOCAL_CARRIER_1_FEEDBACK;
      case "modFXRate" -> Param.GLOBAL_MOD_FX_RATE;
      case "modFXDepth" -> Param.GLOBAL_MOD_FX_DEPTH;
      case "delayRate" -> Param.GLOBAL_DELAY_RATE;
      case "reverbAmount" -> Param.GLOBAL_REVERB_AMOUNT;
      case "stutterRate" -> Param.UNPATCHED_STUTTER_RATE;
      case "sampleRateReduction" -> Param.UNPATCHED_SAMPLE_RATE_REDUCTION;
      case "bitCrush" -> Param.UNPATCHED_BITCRUSHING;
      case "waveIndex" -> Param.LOCAL_OSC_A_WAVE_INDEX;
      default -> -1;
    };
  }

  private static PatchSource mapSource(String s) {
    if (s == null) return PatchSource.NONE;
    return switch (s.toUpperCase()) {
      case "LFO1" -> PatchSource.LFO_LOCAL_1;
      case "LFO2" -> PatchSource.LFO_LOCAL_2;
      case "ENVELOPE1" -> PatchSource.ENVELOPE_0;
      case "ENVELOPE2" -> PatchSource.ENVELOPE_1;
      case "ENVELOPE3" -> PatchSource.ENVELOPE_2;
      case "ENVELOPE4" -> PatchSource.ENVELOPE_3;
      case "VELOCITY" -> PatchSource.VELOCITY;
      case "AFTERTOUCH" -> PatchSource.AFTERTOUCH;
      case "RANDOM" -> PatchSource.RANDOM;
      case "X" -> PatchSource.X;
      case "Y" -> PatchSource.Y;
      case "SIDECHAIN" -> PatchSource.SIDECHAIN;
      default -> PatchSource.NONE;
    };
  }
}
