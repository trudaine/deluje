package org.deluge;

import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.playback.PlaybackHandler;

/**
 * Offline profiling harness (not a unit test): builds a dense synth song (many tracks × sustained
 * chords → high steady polyphony) and renders blocks in a tight loop with NO real-time pacing, to
 * maximize DSP samples for a JFR/async profiler. Run with {@code
 * -XX:StartFlightRecording=filename=/tmp/prof.jfr,settings=profile,dumponexit=true}.
 *
 * <p>Args: [numTracks] [numBlocks]. Defaults 24 tracks, 8000 blocks (~23s of audio).
 */
public class ProfileRender {
  public static void main(String[] args) throws Exception {
    int numTracks = args.length > 0 ? Integer.parseInt(args[0]) : 24;
    int numBlocks = args.length > 1 ? Integer.parseInt(args[1]) : 8000;
    boolean sampleMode = args.length > 2 && args[2].equalsIgnoreCase("sample");

    // A real, on-disk WAV so SAMPLE-osc tracks actually resample (pitched playback -> sinc).
    String wav = "deluge/src/main/resources/SAMPLES/Artists/Campbell Kneale/arctic trem guitar.wav";

    String[] oscTypes = {"SAW", "SQUARE", "SINE", "TRIANGLE"};
    int[] chord = {0, 4, 7, 12}; // 4-note chord (root, 3rd, 5th, octave)

    ProjectModel project = new ProjectModel();
    for (int t = 0; t < numTracks; t++) {
      SynthTrackModel track = new SynthTrackModel("T" + t);
      if (sampleMode) {
        track.setOsc1Type("SAMPLE");
        track.setOsc1SamplePath(wav);
        track.setOsc2Type("NONE");
      } else {
        track.setOsc1Type(oscTypes[t % oscTypes.length]);
        track.setOsc2Type(t % 3 == 0 ? "SAW" : "NONE"); // some 2-osc tracks
      }
      // Engage the ladder filter on ~half the tracks (a known DSP cost), spread cutoffs.
      track.setLpfFreq(t % 2 == 0 ? 0.45f + 0.4f * (t / (float) numTracks) : 20000.0f);
      track.setLpfRes(t % 2 == 0 ? 0.3f : 0.0f);

      ClipModel clip = new ClipModel("C" + t, chord.length, 16);
      int base = 36 + (t % 12); // spread roots across an octave (off-root -> non-unity pitch)
      for (int r = 0; r < chord.length; r++) {
        int midi = base + chord[r];
        clip.setRowYNote(r, midi);
        if (sampleMode) {
          // Retrigger every 4 steps so the (one-shot) sample keeps replaying -> continuous
          // resample.
          for (int s = 0; s < 16; s += 4) {
            clip.setStep(r, s, StepData.of(true, 0.9f, 12.0f, 1.0f, midi));
          }
        } else {
          // Sustained chord: long gate so the 4 notes hold the whole loop → steady polyphony.
          clip.setStep(r, 0, StepData.of(true, 0.9f, 64.0f, 1.0f, midi));
        }
      }
      track.addClip(clip);
      project.addTrack(track);
    }

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.sounds.clear();
    int voices = 0;
    for (var clip : song.getClips()) {
      if (clip instanceof ClipModel ic && ic.getSound() != null) {
        engine.sounds.add((org.deluge.firmware2.GlobalEffectable) ic.getSound());
        voices++;
      }
    }

    PlaybackHandler playbackHandler = new PlaybackHandler();
    playbackHandler.setProject(song);
    playbackHandler.start();

    int n = 128;
    double ticksPerSample = (120.0 / 60.0) * 96.0 / 44100.0;
    double acc = 0;

    // Warm up the JIT first (not profiled meaningfully, but JFR captures steady state in the long
    // run).
    long t0 = System.nanoTime();
    for (int b = 0; b < numBlocks; b++) {
      acc += ticksPerSample * n;
      int whole = (int) acc;
      if (whole > 0) {
        playbackHandler.advanceTicks(whole);
        acc -= whole;
      }
      engine.renderBlock(n);
    }
    long ms = (System.nanoTime() - t0) / 1_000_000;
    double audioSec = numBlocks * n / 44100.0;
    System.out.printf(
        "[PROFILE] tracks=%d sounds=%d blocks=%d -> rendered %.1fs of audio in %dms (%.1fx realtime)%n",
        numTracks, voices, numBlocks, audioSec, ms, audioSec * 1000.0 / ms);
  }
}
