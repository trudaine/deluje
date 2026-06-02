package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.model.sample.Sample;
import org.chuck.deluge.firmware.model.sample.SampleVoiceSettings;
import org.junit.jupiter.api.Test;

/**
 * Verifies sample time-stretch (GAP-13b): with STRETCH / pitch-and-speed-independent playback, the
 * note pitch no longer changes the playback duration — a sample pitched an octave up plays for ~the
 * same length as the un-pitched note, whereas normal resampling plays it ~half as long. Previously
 * the TimeStretcher was ported but never used, so all samples resampled.
 */
public class TimeStretchParityTest {

  private static Sample makeSample(int n) {
    Sample s = new Sample();
    s.numChannels = 1;
    s.sampleRate = 44100.0f;
    s.midiNoteFromFile = 60.0f;
    s.data = new float[n];
    for (int i = 0; i < n; i++) s.data[i] = (float) Math.sin(i * 0.05) * 0.8f;
    return s;
  }

  /** Render until the voice goes inactive; return the number of output samples produced. */
  private static int playbackLength(boolean timestretch, int note) {
    Sample sample = makeSample(8000);
    SampleVoiceSettings settings = new SampleVoiceSettings();
    settings.timestretch = timestretch;
    settings.loopMode = 0; // one-shot

    VoiceSample vs = new VoiceSample();
    vs.noteOn(sample, settings, 0);

    int phaseInc = FirmwareSound.noteToPhaseInc(note);
    int block = 128;
    int produced = 0;
    int[] buf = new int[block];
    for (int guard = 0; guard < 4000 && vs.active; guard++) {
      java.util.Arrays.fill(buf, 0);
      vs.render(buf, block, phaseInc, sample, Integer.MAX_VALUE);
      produced += block;
    }
    return produced;
  }

  @Test
  public void stretchDecouplesPitchFromDuration() {
    int rootLen = playbackLength(true, 60);
    int octaveLen = playbackLength(true, 72);
    assertTrue(rootLen > 0 && octaveLen > 0, "time-stretched playback must be non-empty");
    // With time-stretch, an octave-up note should last roughly as long as the root (±35%).
    double ratio = (double) octaveLen / rootLen;
    assertTrue(
        ratio > 0.65 && ratio < 1.35,
        "time-stretch should keep duration ~pitch-independent (ratio=" + ratio + ")");
  }

  @Test
  public void withoutStretchPitchShortensDuration() {
    int rootLen = playbackLength(false, 60);
    int octaveLen = playbackLength(false, 72);
    // Normal resampling: an octave up reads twice as fast → roughly half the duration.
    assertTrue(
        octaveLen < rootLen * 0.7,
        "without stretch, octave-up should play markedly shorter (root="
            + rootLen
            + ", octave="
            + octaveLen
            + ")");
  }
}
