package org.deluge.project;

import java.io.File;
import java.util.List;
import javax.sound.midi.*;
import javax.sound.sampled.*;
import org.deluge.engine.*;
import org.deluge.model.*;
import org.deluge.playback.*;

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
      if (trackName == null || trackName.trim().isEmpty()) {
        trackName = "Track_" + (t + 1);
      }
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
    ProjectModel project = FirmwareFactory.createSong(model);

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
      if (targetClipIdx == -1) {
        throw new IllegalArgumentException(
            "Cannot export track of type "
                + targetTrack.getClass().getSimpleName()
                + " because it is not a compileable audio track.");
      }
    }

    // Populate sounds:
    for (int i = 0; i < project.getTracks().size(); i++) {
      TrackModel tm = project.getTracks().get(i);
      ClipModel activeClip = tm.getActiveClip();
      if (activeClip != null && activeClip.getSound() instanceof FirmwareSound fs) {
        if (targetTrackIndex == null || targetClipIdx == i) {
          engine.sounds.add(fs);
        }
      }
    }

    PlaybackHandler handler = new PlaybackHandler();
    handler.setProject(project);
    handler.start();

    AudioFormat format = new AudioFormat(44100.0f, 16, 2, true, false);
    try (PullRenderInputStream pris =
            new PullRenderInputStream(model, engine, handler, totalBlocks, blockSize);
        AudioInputStream ais = new AudioInputStream(pris, format, totalBlocks * blockSize)) {
      AudioSystem.write(ais, AudioFileFormat.Type.WAVE, targetFile);
    } finally {
      handler.stop();
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

  private static class PullRenderInputStream extends java.io.InputStream {
    private final ProjectModel model;
    private final FirmwareAudioEngine engine;
    private final PlaybackHandler handler;
    private final long totalFrames;
    private final int blockSize;
    private final double ticksPerSample;
    private final long totalBlocks;

    private long framesRead = 0;
    private int blockIdx = 0;
    private double accumulatedTicks = 0;

    private final byte[] blockBuffer;
    private int bufferOffset = 0;
    private int bufferLength = 0;

    public PullRenderInputStream(
        ProjectModel model,
        FirmwareAudioEngine engine,
        PlaybackHandler handler,
        long totalBlocks,
        int blockSize) {
      this.model = model;
      this.engine = engine;
      this.handler = handler;
      this.totalBlocks = totalBlocks;
      this.blockSize = blockSize;
      this.totalFrames = totalBlocks * blockSize;
      this.ticksPerSample = (model.getBpm() / 60.0 * 96.0) / 44100.0;
      this.blockBuffer = new byte[blockSize * 4];
      // Offline export is not real-time constrained: force full-quality sinc interpolation by
      // clearing any CPU direness left over from a prior live session (export never calls
      // updateDireness, so this only needs doing once at start). C: cpuDireness governs sample
      // interpolation quality (sample_controls.cpp:29).
      org.deluge.engine.FirmwareAudioEngine.cpuDireness = 0;
    }

    @Override
    public int read() throws java.io.IOException {
      byte[] oneByte = new byte[1];
      int n = read(oneByte, 0, 1);
      if (n <= 0) return -1;
      return oneByte[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws java.io.IOException {
      if (framesRead >= totalFrames && bufferLength == 0) {
        return -1;
      }

      int bytesWritten = 0;
      while (bytesWritten < len) {
        if (bufferLength == 0) {
          if (blockIdx >= totalBlocks) {
            break;
          }

          accumulatedTicks += ticksPerSample * blockSize;
          int toAdvance = (int) accumulatedTicks;
          if (toAdvance > 0) {
            handler.advanceTicks(toAdvance);
            accumulatedTicks -= toAdvance;
          }

          try {
            engine.renderBlock(blockSize);
          } catch (Exception e) {
            throw new java.io.IOException("DSP render failed", e);
          }

          int byteIdx = 0;
          for (int i = 0; i < blockSize; i++) {
            int sampleL = engine.masterBuffer[i].l >> 16;
            int sampleR = engine.masterBuffer[i].r >> 16;

            short s16L = (short) Math.max(-32768, Math.min(32767, sampleL));
            short s16R = (short) Math.max(-32768, Math.min(32767, sampleR));

            blockBuffer[byteIdx++] = (byte) (s16L & 0xFF);
            blockBuffer[byteIdx++] = (byte) ((s16L >> 8) & 0xFF);
            blockBuffer[byteIdx++] = (byte) (s16R & 0xFF);
            blockBuffer[byteIdx++] = (byte) ((s16R >> 8) & 0xFF);
          }

          bufferOffset = 0;
          bufferLength = blockSize * 4;
          blockIdx++;
        }

        int toCopy = Math.min(bufferLength, len - bytesWritten);
        System.arraycopy(blockBuffer, bufferOffset, b, off + bytesWritten, toCopy);
        bufferOffset += toCopy;
        bufferLength -= toCopy;
        bytesWritten += toCopy;
        framesRead += toCopy / 4;
      }

      return bytesWritten == 0 ? -1 : bytesWritten;
    }
  }
}
