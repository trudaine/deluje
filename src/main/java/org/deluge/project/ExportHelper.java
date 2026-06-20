package org.deluge.project;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.List;
import javax.sound.midi.*;
import javax.sound.sampled.*;
import org.deluge.firmware.engine.*;
import org.deluge.firmware.model.*;
import org.deluge.firmware.playback.*;
import org.deluge.model.*;

/** Offline rendering and multi-track MIDI/WAV stem export utility. */
public class ExportHelper {

  public interface ProgressCallback {
    void onProgress(String status, int percent);
  }

  /** Exports the project tracks to individual WAV stems and a master mix WAV file. */
  public static void exportStems(
      ProjectModel model, File targetDir, double durationSeconds, ProgressCallback callback)
      throws Exception {
    exportStems(model, targetDir, durationSeconds, null, callback);
  }

  /**
   * Exports the project tracks to individual WAV stems and a master mix WAV file, with an optional
   * prefix.
   */
  public static void exportStems(
      ProjectModel model,
      File targetDir,
      double durationSeconds,
      String stemNamePrefix,
      ProgressCallback callback)
      throws Exception {
    int totalTracks = model.getTracks().size();
    int totalSteps = totalTracks + 1; // Tracks + Master Mix

    // Auto-detect duration if <= 0
    if (durationSeconds <= 0) {
      int maxTicks = 0;
      for (ArrangerClip ac : model.getArrangerTimeline()) {
        maxTicks = Math.max(maxTicks, ac.startTicks() + ac.durationTicks());
      }
      if (maxTicks > 0) {
        durationSeconds = maxTicks * (60.0 / (model.getBpm() * 96.0));
      } else {
        durationSeconds = 16.0; // Default to 16 seconds (approx 8 bars at 120bpm)
      }
    }

    int sampleRate = 44100;
    int blockSize = 128;
    long totalSamples = (long) (durationSeconds * sampleRate);
    long totalBlocks = totalSamples / blockSize;

    // 1. Render Master Mix (All tracks)
    if (callback != null) {
      callback.onProgress("Rendering Master Mix...", 0);
    }
    String masterName =
        (stemNamePrefix != null && !stemNamePrefix.isEmpty())
            ? stemNamePrefix + ".wav"
            : "Master_mix.wav";
    renderWav(model, null, new File(targetDir, masterName), totalBlocks, blockSize, sampleRate);

    // 2. Render each track individually
    for (int t = 0; t < totalTracks; t++) {
      TrackModel track = model.getTracks().get(t);
      String trackName = track.getName();
      String cleanTrackName =
          (stemNamePrefix != null && !stemNamePrefix.isEmpty())
              ? trackName.replaceAll("[\\\\/:*?\"<>|]", "_")
              : trackName.replaceAll("[^a-zA-Z0-9_-]", "_");
      String fileName =
          (stemNamePrefix != null && !stemNamePrefix.isEmpty())
              ? String.format("%s %s.wav", stemNamePrefix, cleanTrackName)
              : String.format("Track_%d_%s_stem.wav", t + 1, cleanTrackName);

      if (callback != null) {
        callback.onProgress(
            "Rendering Track: " + track.getName() + "...",
            (int) (((double) (t + 1) / totalSteps) * 100));
      }

      renderWav(model, t, new File(targetDir, fileName), totalBlocks, blockSize, sampleRate);
    }

    if (callback != null) {
      callback.onProgress("Export Complete!", 100);
    }
  }

  private static void renderWav(
      ProjectModel model,
      Integer targetTrackIndex,
      File targetFile,
      long totalBlocks,
      int blockSize,
      int sampleRate)
      throws Exception {
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;

    // Build FW Song from model
    Song fwSong = FirmwareFactory.createSong(model);

    // Populate compileable tracks list to match JNI clips to original tracks
    java.util.List<TrackModel> compileableTracks = new java.util.ArrayList<>();
    for (TrackModel track : model.getTracks()) {
      if (track instanceof SynthTrackModel
          || track instanceof KitTrackModel
          || track instanceof MidiTrackModel
          || track instanceof AudioTrackModel) {
        compileableTracks.add(track);
      }
    }

    int targetClipIdx = -1;
    if (targetTrackIndex != null) {
      TrackModel targetTrack = model.getTracks().get(targetTrackIndex);
      targetClipIdx = compileableTracks.indexOf(targetTrack);
    }

    // Populate sounds:
    int currentTrackIdx = 0;
    for (var clip : fwSong.clips) {
      if (clip instanceof InstrumentClip ic) {
        if (ic.sound instanceof FirmwareSound fs) {
          if (targetTrackIndex == null || targetClipIdx == currentTrackIdx) {
            engine.sounds.add(fs);
          }
        }
      }
      currentTrackIdx++;
    }

    PlaybackHandler handler = new PlaybackHandler();
    handler.setSong(fwSong);
    handler.start();

    byte[] wavBytes = new byte[(int) (totalBlocks * blockSize * 2 * 2)]; // 16-bit stereo PCM
    int byteIdx = 0;

    double bpm = model.getBpm();
    double ticksPerSample = (bpm / 60.0 * 96.0) / 44100.0;
    double accumulatedTicks = 0;

    for (int b = 0; b < totalBlocks; b++) {
      accumulatedTicks += ticksPerSample * blockSize;
      int toAdvance = (int) accumulatedTicks;
      if (toAdvance > 0) {
        handler.advanceTicks(toAdvance);
        accumulatedTicks -= toAdvance;
      }

      engine.renderBlock(blockSize);

      for (int i = 0; i < blockSize; i++) {
        float xL = (float) engine.masterBuffer[i].l / 2147483648.0f;
        float xR = (float) engine.masterBuffer[i].r / 2147483648.0f;

        // Apply master clipping and driver gain
        float boostedL = xL * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        if (boostedL > 0.7f) boostedL = 0.7f + 0.3f * (float) Math.tanh((boostedL - 0.7f) / 0.3f);
        if (boostedL < -0.7f) boostedL = -0.7f + 0.3f * (float) Math.tanh((boostedL + 0.7f) / 0.3f);
        short s16L = (short) Math.max(-32768, Math.min(32767, boostedL * 32767.0f));

        float boostedR = xR * org.deluge.engine.JavaAudioDriver.monitorGainMul;
        if (boostedR > 0.7f) boostedR = 0.7f + 0.3f * (float) Math.tanh((boostedR - 0.7f) / 0.3f);
        if (boostedR < -0.7f) boostedR = -0.7f + 0.3f * (float) Math.tanh((boostedR + 0.7f) / 0.3f);
        short s16R = (short) Math.max(-32768, Math.min(32767, boostedR * 32767.0f));

        wavBytes[byteIdx++] = (byte) (s16L & 0xFF);
        wavBytes[byteIdx++] = (byte) ((s16L >> 8) & 0xFF);
        wavBytes[byteIdx++] = (byte) (s16R & 0xFF);
        wavBytes[byteIdx++] = (byte) ((s16R >> 8) & 0xFF);
      }
    }
    handler.stop();

    AudioFormat format = new AudioFormat(44100.0f, 16, 2, true, false);
    try (AudioInputStream ais =
        new AudioInputStream(new ByteArrayInputStream(wavBytes), format, totalBlocks * blockSize)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, targetFile);
    }
  }

  /**
   * Exports the project tracks as a multi-track MIDI file cabled to arranger timeline placements.
   */
  public static void exportMidi(ProjectModel model, File file) throws Exception {
    // 96 PPQ (Pulses Per Quarter Note)
    Sequence sequence = new Sequence(Sequence.PPQ, 96);

    int trackIndex = 0;
    for (TrackModel track : model.getTracks()) {
      Track midiTrack = sequence.createTrack();

      // Set Track Name
      MetaMessage nameMsg = new MetaMessage();
      String trackName = track.getName();
      nameMsg.setMessage(0x03, trackName.getBytes(), trackName.length());
      midiTrack.add(new MidiEvent(nameMsg, 0));

      boolean isKit = track instanceof KitTrackModel;

      final int currentTrackIdx = trackIndex;
      // Gather all notes from arranger timeline clips
      List<ArrangerClip> acList =
          model.getArrangerTimeline().stream()
              .filter(ac -> ac.trackIndex() == currentTrackIdx)
              .toList();

      if (!acList.isEmpty()) {
        for (ArrangerClip ac : acList) {
          ClipModel clip = ac.clip();
          long clipStartTick = ac.startTicks();
          long clipDuration = ac.durationTicks();

          int stepTicks = clip.isTripletMode() ? 32 : 24;

          // Loop over note rows
          for (int r = 0; r < clip.getRowCount(); r++) {
            int pitch = clip.getRowYNote(r);
            if (pitch == -1) {
              pitch = clip.getRowCount() - 1 - r;
            }
            if (isKit) {
              pitch = 36 + r; // Map Pad 0 to C3 (36), Pad 1 to C#3 (37), etc.
            }

            for (int s = 0; s < clip.getStepCount(); s++) {
              StepData step = clip.getStep(r, s);
              if (step != null && step.active()) {
                long noteStartTick = clipStartTick + ((long) s * stepTicks);
                if (noteStartTick >= clipStartTick + clipDuration) {
                  continue; // Past arranger clip boundary
                }

                long gateLength = (long) (step.gate() * stepTicks);
                long noteEndTick = noteStartTick + gateLength;

                int velocity = (int) (step.velocity() * 127.0f);
                velocity = Math.max(1, Math.min(127, velocity));

                // Note On
                ShortMessage onMsg = new ShortMessage();
                onMsg.setMessage(ShortMessage.NOTE_ON, 0, pitch, velocity);
                midiTrack.add(new MidiEvent(onMsg, noteStartTick));

                // Note Off
                ShortMessage offMsg = new ShortMessage();
                offMsg.setMessage(ShortMessage.NOTE_OFF, 0, pitch, 0);
                midiTrack.add(new MidiEvent(offMsg, noteEndTick));
              }
            }
          }
        }
      } else {
        // Fallback: If arranger timeline is empty, export session clips sequentially
        long currentStartTick = 0;
        for (ClipModel clip : track.getClips()) {
          int stepTicks = clip.isTripletMode() ? 32 : 24;
          long clipLength = (long) clip.getStepCount() * stepTicks;

          for (int r = 0; r < clip.getRowCount(); r++) {
            int pitch = clip.getRowYNote(r);
            if (pitch == -1) {
              pitch = clip.getRowCount() - 1 - r;
            }
            if (isKit) {
              pitch = 36 + r;
            }

            for (int s = 0; s < clip.getStepCount(); s++) {
              StepData step = clip.getStep(r, s);
              if (step != null && step.active()) {
                long noteStartTick = currentStartTick + ((long) s * stepTicks);
                long gateLength = (long) (step.gate() * stepTicks);
                long noteEndTick = noteStartTick + gateLength;

                int velocity = (int) (step.velocity() * 127.0f);
                velocity = Math.max(1, Math.min(127, velocity));

                ShortMessage onMsg = new ShortMessage();
                onMsg.setMessage(ShortMessage.NOTE_ON, 0, pitch, velocity);
                midiTrack.add(new MidiEvent(onMsg, noteStartTick));

                ShortMessage offMsg = new ShortMessage();
                offMsg.setMessage(ShortMessage.NOTE_OFF, 0, pitch, 0);
                midiTrack.add(new MidiEvent(offMsg, noteEndTick));
              }
            }
          }
          currentStartTick += clipLength + 96; // add 1 bar gap between clips
        }
      }

      trackIndex++;
    }

    MidiSystem.write(sequence, 1, file);
  }
}
