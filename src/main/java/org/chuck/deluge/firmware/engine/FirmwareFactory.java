package org.chuck.deluge.firmware.engine;

import java.io.File;
import java.io.IOException;
import org.chuck.deluge.firmware.dsp.oscillators.OscType;
import org.chuck.deluge.firmware.model.InstrumentClip;
import org.chuck.deluge.firmware.model.Song;
import org.chuck.deluge.firmware.model.note.NoteRow;
import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware.modulation.params.Param;
import org.chuck.deluge.firmware.modulation.patch.PatchSource;
import org.chuck.deluge.firmware.storage.audio.AudioFileReader;
import org.chuck.deluge.model.ClipModel;
import org.chuck.deluge.model.Drum;
import org.chuck.deluge.model.KitTrackModel;
import org.chuck.deluge.model.ProjectModel;
import org.chuck.deluge.model.SoundDrum;
import org.chuck.deluge.model.StepData;
import org.chuck.deluge.model.SynthTrackModel;
import org.chuck.deluge.model.TrackModel;
import org.chuck.deluge.project.PreferencesManager;

/** Glue code to convert the existing XML-loaded models into the high-fidelity firmware engine. */
public class FirmwareFactory {

  public static Song createSong(ProjectModel model) {
    Song song = new Song();
    song.tempoBPM = model.getBpm();

    System.out.println("[FirmwareFactory] Creating FW Song. Tracks in model: " + model.getTracks().size());
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

  public static InstrumentClip createKitClip(KitTrackModel model) {
    InstrumentClip clip = new InstrumentClip();
    FirmwareKit kit = new FirmwareKit();
    clip.sound = kit;
    clip.loopLength = 16 * 24;

    File sdRoot = PreferencesManager.getLibraryDir();
    // ── High-Fidelity Path Resolution ──
    // In dev environment, samples are often in src/main/resources/SAMPLES
    File devSamples = new File("deluge/src/main/resources");

    int drumIdx = 0;
    for (Drum d : model.getDrums()) {
      if (drumIdx >= kit.drumSounds.size()) break;
      if (d instanceof SoundDrum sd) {
        FirmwareSound drumSound = kit.drumSounds.get(drumIdx);
        drumSound.oscTypes[0] = OscType.SAMPLE;
        
        String path = sd.getSamplePath();
        if (path != null && !path.isEmpty()) {
            File f = resolveSample(path, sdRoot, devSamples);
            if (f != null && f.exists()) {
                try {
                    Sample sample = AudioFileReader.readSample(f.getAbsolutePath());
                    drumSound.samples[0] = sample;
                    System.out.println("[FirmwareFactory] Loaded sample: " + f.getName() + " (size: " + sample.getNumSamples() + ")");
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

  private static File resolveSample(String path, File sdRoot, File devRoot) {
      File[] roots = {sdRoot, devRoot, new File(".")};
      for (File root : roots) {
          if (root == null) continue;
          File f = new File(root, path);
          if (f.exists()) return f;
          
          // Case-insensitive fallback
          File parent = new File(root, new File(path).getParent() != null ? new File(path).getParent() : "");
          if (parent.isDirectory()) {
              String name = new File(path).getName();
              File[] matches = parent.listFiles((dir, n) -> n.equalsIgnoreCase(name));
              if (matches != null && matches.length > 0) return matches[0];
          }
      }
      // Absolute path check
      File absolute = new File(path);
      if (absolute.exists()) return absolute;
      return null;
  }
}
